package io.github.eagle10021.pinscope

// Firmata 2.x protocol, as spoken by the StandardFirmata example sketch (57600 baud).

enum class PinMode(val code: Int, val label: String) {
    INPUT(0, "Input"), OUTPUT(1, "Output"), ANALOG(2, "Analog"), PWM(3, "PWM"),
    SERVO(4, "Servo"), I2C(6, "I2C"), PULLUP(11, "Pull-up");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
        // Modes shown in the pin controls, in menu order
        val controllable = listOf(INPUT, PULLUP, OUTPUT, PWM, ANALOG, SERVO)
    }
}

data class FirmataPin(
    val number: Int,
    val modes: Set<PinMode> = emptySet(),
    val mode: PinMode? = null,
    val value: Int = 0,           // digital level, PWM duty, servo angle, or analog reading
    val analogChannel: Int? = null,
)

class FirmataParser(private val listener: Listener) {
    interface Listener {
        fun onVersion(major: Int, minor: Int)
        fun onFirmware(name: String, major: Int, minor: Int)
        fun onDigitalPort(port: Int, mask: Int)
        fun onAnalog(channel: Int, value: Int)
        fun onCapabilities(modes: List<Set<PinMode>>)
        fun onAnalogMapping(channels: List<Int?>)
        fun onPinState(pin: Int, mode: Int, state: Int)
    }

    private var cmd = 0
    private val data = ArrayList<Int>(64)
    private var inSysex = false
    private var need = 0

    fun feed(bytes: ByteArray) { for (b in bytes) feed(b.toInt() and 0xFF) }

    private fun feed(b: Int) {
        if (inSysex) {
            if (b == END_SYSEX) { inSysex = false; sysex() } else if (data.size < 4096) data += b
            return
        }
        if (b and 0x80 != 0) {
            data.clear()
            when {
                b == START_SYSEX -> { inSysex = true; cmd = 0 }
                b and 0xF0 == DIGITAL_MESSAGE || b and 0xF0 == ANALOG_MESSAGE || b == REPORT_VERSION -> { cmd = b; need = 2 }
                else -> { cmd = 0; need = 0 }
            }
            return
        }
        if (need == 0) return
        data += b
        if (data.size < need) return
        val v = data[0] or (data[1] shl 7)
        when {
            cmd and 0xF0 == DIGITAL_MESSAGE -> listener.onDigitalPort(cmd and 0x0F, v)
            cmd and 0xF0 == ANALOG_MESSAGE -> listener.onAnalog(cmd and 0x0F, v)
            cmd == REPORT_VERSION -> listener.onVersion(data[0], data[1])
        }
        data.clear()
    }

    private fun sysex() {
        if (data.isEmpty()) return
        val id = data[0]
        val d = data.subList(1, data.size)
        when (id) {
            REPORT_FIRMWARE -> if (d.size >= 2) {
                val name = StringBuilder()
                var i = 2
                while (i + 1 < d.size) { name.append((d[i] or (d[i + 1] shl 7)).toChar()); i += 2 }
                listener.onFirmware(name.toString(), d[0], d[1])
            }
            CAPABILITY_RESPONSE -> {
                val pins = mutableListOf<Set<PinMode>>()
                var cur = mutableSetOf<PinMode>()
                var i = 0
                while (i < d.size) {
                    if (d[i] == 0x7F) { pins += cur; cur = mutableSetOf(); i++; continue }
                    PinMode.of(d[i])?.let { cur += it }
                    i += 2 // mode, resolution
                }
                listener.onCapabilities(pins)
            }
            ANALOG_MAPPING_RESPONSE -> listener.onAnalogMapping(d.map { if (it == 0x7F) null else it })
            PIN_STATE_RESPONSE -> if (d.size >= 3) {
                var state = 0
                for (k in 2 until d.size) state = state or (d[k] shl (7 * (k - 2)))
                listener.onPinState(d[0], d[1], state)
            }
        }
    }

    companion object {
        const val DIGITAL_MESSAGE = 0x90
        const val ANALOG_MESSAGE = 0xE0
        const val REPORT_ANALOG = 0xC0
        const val REPORT_DIGITAL = 0xD0
        const val SET_PIN_MODE = 0xF4
        const val SET_DIGITAL_PIN_VALUE = 0xF5
        const val REPORT_VERSION = 0xF9
        const val START_SYSEX = 0xF0
        const val END_SYSEX = 0xF7
        const val EXTENDED_ANALOG = 0x6F
        const val CAPABILITY_QUERY = 0x6B
        const val CAPABILITY_RESPONSE = 0x6C
        const val ANALOG_MAPPING_QUERY = 0x69
        const val ANALOG_MAPPING_RESPONSE = 0x6A
        const val PIN_STATE_QUERY = 0x6D
        const val PIN_STATE_RESPONSE = 0x6E
        const val REPORT_FIRMWARE = 0x79
        const val SAMPLING_INTERVAL = 0x7A
        const val SERVO_CONFIG = 0x70
    }
}

// Builds outgoing Firmata messages
object FirmataOut {
    private fun sysex(vararg b: Int) = (listOf(FirmataParser.START_SYSEX) + b.toList() + FirmataParser.END_SYSEX).map { it.toByte() }.toByteArray()

    fun firmwareQuery() = sysex(FirmataParser.REPORT_FIRMWARE)
    fun capabilityQuery() = sysex(FirmataParser.CAPABILITY_QUERY)
    fun analogMappingQuery() = sysex(FirmataParser.ANALOG_MAPPING_QUERY)
    fun pinStateQuery(pin: Int) = sysex(FirmataParser.PIN_STATE_QUERY, pin)
    fun samplingInterval(ms: Int) = sysex(FirmataParser.SAMPLING_INTERVAL, ms and 0x7F, ms shr 7 and 0x7F)

    fun setMode(pin: Int, mode: PinMode) = bytes(FirmataParser.SET_PIN_MODE, pin, mode.code)
    fun digitalWrite(pin: Int, high: Boolean) = bytes(FirmataParser.SET_DIGITAL_PIN_VALUE, pin, if (high) 1 else 0)
    fun reportAnalog(channel: Int, on: Boolean) = bytes(FirmataParser.REPORT_ANALOG or (channel and 0x0F), if (on) 1 else 0)
    fun reportDigital(port: Int, on: Boolean) = bytes(FirmataParser.REPORT_DIGITAL or (port and 0x0F), if (on) 1 else 0)

    // Pulse widths (µs) the servo is driven with at 0° and 180°
    fun servoConfig(pin: Int, minUs: Int, maxUs: Int) =
        sysex(FirmataParser.SERVO_CONFIG, pin, minUs and 0x7F, minUs shr 7 and 0x7F, maxUs and 0x7F, maxUs shr 7 and 0x7F)

    fun analogWrite(pin: Int, value: Int): ByteArray =
        if (pin < 16) bytes(FirmataParser.ANALOG_MESSAGE or pin, value and 0x7F, value shr 7 and 0x7F)
        else sysex(FirmataParser.EXTENDED_ANALOG, pin, value and 0x7F, value shr 7 and 0x7F)

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
}
