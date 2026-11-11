package io.github.eagle10021.pinscope

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import kotlin.concurrent.thread

// Minimal USB serial for Arduino boards: CDC-ACM (ATmega16U2, genuine boards) and WCH CH340 (many clones).

enum class ChipKind(val label: String) { CDC("ATmega16U2 (USB CDC)"), CH340("CH340") }

object UsbIds {
    const val ARDUINO_A = 0x2341
    const val ARDUINO_B = 0x2A03
    const val WCH = 0x1A86

    fun kindOf(d: UsbDevice): ChipKind? = when {
        d.vendorId == WCH && (d.productId == 0x7523 || d.productId == 0x5523) -> ChipKind.CH340
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } -> ChipKind.CDC
        else -> null
    }

    fun describe(d: UsbDevice): String {
        val product = d.productName?.takeIf { it.isNotBlank() }
        return when {
            d.vendorId == ARDUINO_A || d.vendorId == ARDUINO_B -> product ?: "Arduino board"
            d.vendorId == WCH -> "Clone board (CH340)"
            else -> product ?: "USB serial device"
        }
    }

    fun find(manager: UsbManager): UsbDevice? = manager.deviceList.values
        .filter { kindOf(it) != null }
        .sortedBy { if (it.vendorId == ARDUINO_A || it.vendorId == ARDUINO_B) 0 else 1 }
        .firstOrNull()
}

class UsbSerialPort(
    private val manager: UsbManager,
    val device: UsbDevice,
    private val onData: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    val kind = UsbIds.kindOf(device) ?: throw IOException("Not a supported serial device")
    private var conn: UsbDeviceConnection? = null
    private var claimed = mutableListOf<UsbInterface>()
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var controlIndex = 0
    @Volatile private var running = false
    private var reader: Thread? = null

    fun open(baud: Int) {
        val c = manager.openDevice(device) ?: throw IOException("Could not open the USB device")
        conn = c
        try {
            when (kind) {
                ChipKind.CDC -> openCdc(c, baud)
                ChipKind.CH340 -> openCh340(c, baud)
            }
        } catch (e: Exception) {
            close(); throw e
        }
        running = true
        reader = thread(name = "usb-serial-read", isDaemon = true) { readLoop() }
    }

    private fun openCdc(c: UsbDeviceConnection, baud: Int) {
        var comm: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM && comm == null) comm = iface
            if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && data == null) data = iface
        }
        data ?: throw IOException("No CDC data interface")
        comm?.let { if (c.claimInterface(it, true)) claimed += it }
        if (!c.claimInterface(data, true)) throw IOException("Could not claim the USB interface")
        claimed += data
        findBulk(data)
        controlIndex = comm?.id ?: 0
        setBaud(baud)
        // DTR + RTS on: the board resets, as it does when the Arduino IDE opens the port
        c.controlTransfer(0x21, 0x22, 0x03, controlIndex, null, 0, 1000)
    }

    private fun openCh340(c: UsbDeviceConnection, baud: Int) {
        val iface = device.getInterface(0)
        if (!c.claimInterface(iface, true)) throw IOException("Could not claim the USB interface")
        claimed += iface
        findBulk(iface)
        val buf = ByteArray(2)
        c.controlTransfer(0xC0, 0x5F, 0, 0, buf, 2, 1000)
        chOut(0xA1, 0, 0)
        chBaud(baud)
        chOut(0x9A, 0x2518, 0x00C3) // 8 data bits, no parity, 1 stop, RX and TX on
        chOut(0xA1, 0x501F, 0xD90A)
        chBaud(baud)
        chOut(0xA4, LINES_ON, 0) // DTR + RTS on (active low)
    }

    private val LINES_ON = (0x20 or 0x40).inv() and 0xFFFF // DTR and RTS bits are active low

    private fun chOut(req: Int, value: Int, index: Int) {
        val r = conn?.controlTransfer(0x40, req, value, index, null, 0, 1000) ?: -1
        if (r < 0) throw IOException("CH340 setup failed")
    }

    private fun chBaud(baud: Int) {
        var factor = 1532620800L / baud
        var divisor = 3L
        while (factor > 0xFFF0 && divisor > 0) { factor = factor shr 3; divisor-- }
        factor = 0x10000 - factor
        divisor = divisor or 0x80
        chOut(0x9A, 0x1312, ((factor and 0xFF00) or divisor).toInt())
        chOut(0x9A, 0x0F2C, (factor and 0xFF).toInt())
    }

    private fun findBulk(iface: UsbInterface) {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) throw IOException("No bulk endpoints")
    }

    fun setBaud(baud: Int) {
        val c = conn ?: return
        when (kind) {
            ChipKind.CDC -> {
                val b = byteArrayOf(
                    (baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(), (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte(),
                    0, 0, 8, // 1 stop bit, no parity, 8 data bits
                )
                c.controlTransfer(0x21, 0x20, 0, controlIndex, b, b.size, 1000)
            }
            ChipKind.CH340 -> chBaud(baud)
        }
    }

    // Pulses DTR, which resets the board (same as pressing its reset button)
    fun resetBoard() {
        val c = conn ?: return
        when (kind) {
            ChipKind.CDC -> {
                c.controlTransfer(0x21, 0x22, 0x00, controlIndex, null, 0, 1000)
                Thread.sleep(120)
                c.controlTransfer(0x21, 0x22, 0x03, controlIndex, null, 0, 1000)
            }
            ChipKind.CH340 -> {
                chOut(0xA4, 0xFFFF, 0); Thread.sleep(120); chOut(0xA4, LINES_ON, 0)
            }
        }
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        val c = conn ?: return
        val ep = epOut ?: return
        var off = 0
        while (off < bytes.size) {
            val n = minOf(ep.maxPacketSize, bytes.size - off)
            val chunk = bytes.copyOfRange(off, off + n)
            if (c.bulkTransfer(ep, chunk, n, 1000) < 0) throw IOException("USB write failed")
            off += n
        }
    }

    private fun readLoop() {
        val c = conn ?: return
        val ep = epIn ?: return
        val buf = ByteArray(maxOf(64, ep.maxPacketSize) * 4)
        var failures = 0
        while (running) {
            val n = c.bulkTransfer(ep, buf, buf.size, 200)
            if (n > 0) { failures = 0; onData(buf.copyOf(n)) }
            else if (n < 0 && !manager.deviceList.containsValue(device)) {
                if (running) onError("The board was unplugged")
                break
            } else if (n < 0 && ++failures > 2000) break
        }
    }

    fun close() {
        running = false
        reader?.join(400)
        reader = null
        conn?.let { c -> claimed.forEach { runCatching { c.releaseInterface(it) } }; c.close() }
        claimed.clear()
        conn = null
    }
}
