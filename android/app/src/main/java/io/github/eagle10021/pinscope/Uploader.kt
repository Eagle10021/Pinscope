package io.github.eagle10021.pinscope

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Flashing sketches onto a Mega over USB: Intel HEX in, STK500v2 (the Mega's bootloader protocol) out.

object IntelHex {
    // Returns the flash image from address 0, gaps filled with 0xFF
    fun parse(text: String): ByteArray {
        val data = HashMap<Int, Byte>()
        var base = 0
        var top = 0
        for ((n, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith(":")) throw IOException("Line ${n + 1} is not Intel HEX")
            val b = line.substring(1).chunked(2).map { it.toInt(16) }
            if (b.size < 5 || b.sum() and 0xFF != 0) throw IOException("Bad checksum on line ${n + 1}")
            val len = b[0]; val addr = (b[1] shl 8) or b[2]; val type = b[3]
            when (type) {
                0 -> for (i in 0 until len) { val a = base + addr + i; data[a] = b[4 + i].toByte(); if (a + 1 > top) top = a + 1 }
                1 -> break
                2 -> base = ((b[4] shl 8) or b[5]) shl 4
                4 -> base = ((b[4] shl 8) or b[5]) shl 16
            }
        }
        if (top == 0) throw IOException("The file has no data")
        if (top > 256 * 1024 - 8 * 1024) throw IOException("The sketch is too big for the Mega (${top / 1024} KB)")
        val out = ByteArray(top) { 0xFF.toByte() }
        for ((a, v) in data) out[a] = v
        return out
    }

    // Writes an image as Intel HEX (16 bytes a line, extended addresses past 64 KB)
    fun write(image: ByteArray): String {
        val sb = StringBuilder()
        fun rec(len: Int, addr: Int, type: Int, data: ByteArray) {
            var sum = len + (addr shr 8 and 0xFF) + (addr and 0xFF) + type
            sb.append(':').append("%02X%04X%02X".format(len, addr and 0xFFFF, type))
            for (b in data) { val v = b.toInt() and 0xFF; sum += v; sb.append("%02X".format(v)) }
            sb.append("%02X".format((-sum) and 0xFF)).append('\n')
        }
        var upper = -1
        var a = 0
        while (a < image.size) {
            val hi = a shr 16
            if (hi != upper) { rec(2, 0, 4, byteArrayOf((hi shr 8).toByte(), hi.toByte())); upper = hi }
            val n = minOf(16, image.size - a, 0x10000 - (a and 0xFFFF))
            rec(n, a, 0, image.copyOfRange(a, a + n))
            a += n
        }
        rec(0, 0, 1, ByteArray(0))
        return sb.toString()
    }
}

class Stk500v2(private val write: (ByteArray) -> Unit) {
    private val inbox = LinkedBlockingQueue<Byte>()
    private var seq = 0

    fun feed(bytes: ByteArray) { for (b in bytes) inbox.offer(b) }

    private fun send(body: ByteArray, timeoutMs: Long = 1000): ByteArray {
        val s = seq and 0xFF
        val msg = ByteArray(body.size + 6)
        msg[0] = 0x1B; msg[1] = s.toByte(); msg[2] = (body.size shr 8).toByte(); msg[3] = body.size.toByte(); msg[4] = 0x0E
        System.arraycopy(body, 0, msg, 5, body.size)
        var ck = 0
        for (i in 0 until msg.size - 1) ck = ck xor (msg[i].toInt() and 0xFF)
        msg[msg.size - 1] = ck.toByte()
        write(msg)
        seq++
        return receive(s, timeoutMs)
    }

    private fun next(deadline: Long): Int {
        val wait = deadline - System.currentTimeMillis()
        val b = if (wait > 0) inbox.poll(wait, TimeUnit.MILLISECONDS) else null
        return (b ?: throw IOException("The board did not answer")).toInt() and 0xFF
    }

    private fun receive(expectSeq: Int, timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (next(deadline) != 0x1B) continue
            val s = next(deadline)
            val size = (next(deadline) shl 8) or next(deadline)
            if (next(deadline) != 0x0E || size > 1024) continue
            val body = ByteArray(size) { next(deadline).toByte() }
            val ck = next(deadline)
            var c = 0x1B xor s xor (size shr 8) xor (size and 0xFF) xor 0x0E
            for (x in body) c = c xor (x.toInt() and 0xFF)
            if (c != ck || s != expectSeq) continue
            return body
        }
    }

    private fun ok(r: ByteArray, cmd: Int, what: String) {
        if (r.size < 2 || r[0].toInt() and 0xFF != cmd || r[1].toInt() != 0) throw IOException("$what failed")
    }

    // Tries to reach the bootloader right after a reset
    fun signOn(attempts: Int = 12): String {
        repeat(attempts) {
            inbox.clear()
            try {
                val r = send(byteArrayOf(0x01), 250)
                if (r.size >= 3 && r[0].toInt() == 0x01 && r[1].toInt() == 0) return String(r, 3, (r[2].toInt() and 0xFF).coerceAtMost(r.size - 3))
            } catch (_: IOException) {}
        }
        throw IOException("No answer from the bootloader. Press the board's reset button and try again.")
    }

    fun signature(): Int {
        var sig = 0
        for (i in 0 until 3) {
            val r = send(byteArrayOf(0x1B, 0x04, 0x30, 0x00, i.toByte(), 0x00))
            ok(r, 0x1B, "Reading the chip signature")
            sig = (sig shl 8) or (r[2].toInt() and 0xFF)
        }
        return sig
    }

    fun enterProgramming() = ok(send(byteArrayOf(0x10, 0xC8.toByte(), 0x64, 0x19, 0x20, 0x00, 0x53, 0x03, 0xAC.toByte(), 0x53, 0x00, 0x00)), 0x10, "Entering programming mode")
    fun leaveProgramming() = runCatching { send(byteArrayOf(0x11, 0x01, 0x01)) }

    private fun loadAddress(byteAddr: Int) {
        val w = byteAddr / 2
        ok(send(byteArrayOf(0x06, (0x80 or (w shr 24 and 0x7F)).toByte(), (w shr 16).toByte(), (w shr 8).toByte(), w.toByte())), 0x06, "Setting the address")
    }

    fun writePage(addr: Int, page: ByteArray) {
        loadAddress(addr)
        val body = ByteArray(10 + page.size)
        body[0] = 0x13; body[1] = (page.size shr 8).toByte(); body[2] = page.size.toByte()
        body[3] = 0xC1.toByte(); body[4] = 0x0A; body[5] = 0x40; body[6] = 0x4C; body[7] = 0x20; body[8] = 0; body[9] = 0
        System.arraycopy(page, 0, body, 10, page.size)
        ok(send(body, 3000), 0x13, "Writing flash at 0x%05X".format(addr))
    }

    fun readPage(addr: Int, size: Int): ByteArray {
        loadAddress(addr)
        val r = send(byteArrayOf(0x14, (size shr 8).toByte(), size.toByte(), 0x20), 3000)
        ok(r, 0x14, "Reading flash at 0x%05X".format(addr))
        return r.copyOfRange(2, 2 + size)
    }

    companion object {
        const val PAGE = 256
        const val MEGA2560_SIGNATURE = 0x1E9801
    }
}
