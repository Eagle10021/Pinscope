package io.github.eagle10021.pinscope

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

// Runs a sketch on the emulated ATmega2560 in real time, with virtual parts wired to its pins.
// Everything that touches the chip happens on the simulator thread; the UI sends actions and reads snapshots.

const val CLOCK = 16_000_000L

class Simulator(val kit: KitData, image: ByteArray, val name: String) {
    val avr = Avr(image)
    val parts = ArrayList<SimPart>()

    // Arduino pin number → (port index, bit), from the AVR port names in the pin data (PE4 → port E, bit 4)
    private val pinPort = IntArray(70) { -1 }
    private val pinBit = IntArray(70)
    init {
        for (n in 0 until 70) {
            val port = kit.pinByNumber(n)?.lines?.firstOrNull { it.first == "AVR port" }?.second ?: continue
            pinPort[n] = "ABCDEFGHJKL".indexOf(port[1]); pinBit[n] = port[2] - '0'
        }
    }

    // Arduino pin → (timer, compare channel) for analogWrite()
    private val pwmMap = mapOf(2 to (3 to 1), 3 to (3 to 2), 4 to (0 to 1), 5 to (3 to 0), 6 to (4 to 0), 7 to (4 to 1), 8 to (4 to 2),
        9 to (2 to 1), 10 to (2 to 0), 11 to (1 to 0), 12 to (1 to 1), 13 to (0 to 0), 44 to (5 to 2), 45 to (5 to 1), 46 to (5 to 0))

    // ---------- pins, for parts ----------
    fun level(n: Int): Int { val p = pinPort[n]; return if (p < 0) 0 else (avr.pinLevels(p) shr pinBit[n]) and 1 }
    fun isOutput(n: Int): Boolean { val p = pinPort[n]; return p >= 0 && (avr.ddr(p) shr pinBit[n]) and 1 == 1 }
    fun pullUp(n: Int): Boolean { val p = pinPort[n]; return p >= 0 && !isOutput(n) && (avr.port(p) shr pinBit[n]) and 1 == 1 }
    // PWM duty 0–255 when analogWrite drives the pin, else -1
    fun duty(n: Int): Int = pwmMap[n]?.let { (t, c) -> avr.pwmDuty(t, c) } ?: -1
    // How bright an LED on this pin looks, 0–1
    fun brightness(n: Int): Float {
        if (n !in 0..69) return 0f
        if (isOutput(n)) { val d = duty(n); return if (d >= 0) d / 255f else level(n).toFloat() }
        return if (pullUp(n)) 0.08f else 0f // a pull-up feeds an LED a faint current
    }

    // A part drives an input: 0 or 1, or null to let go
    fun drive(n: Int, value: Int?) {
        val p = pinPort[n]; if (p < 0) return
        val before = avr.pinLevels(p)
        val m = 1 shl pinBit[n]
        if (value == null) avr.extDriven[p] = avr.extDriven[p] and m.inv()
        else { avr.extDriven[p] = avr.extDriven[p] or m; avr.extLevel[p] = if (value != 0) avr.extLevel[p] or m else avr.extLevel[p] and m.inv() }
        val after = avr.pinLevels(p)
        if (before != after) { avr.inputChanged(p, before, after); for ((part, _) in listeners[n]) if (part is Scope) part.changed() }
    }
    // CPU cycles per PWM period on a pin driven by analogWrite, else 0
    fun pwmPeriod(n: Int): Int = pwmMap[n]?.let { (t, _) -> avr.pwmPeriod(t) } ?: 0
    // The pin's voltage as a part would see it on average: PWM counts as its mean level
    fun volts(n: Int): Double {
        if (n !in 0..69) return 0.0
        if (isOutput(n)) { val d = duty(n); return if (d >= 0) d * 5.0 / 255 else level(n) * 5.0 }
        if (n >= 54 && parts.any { p -> p.analogPins().contains(n - 54) }) return avr.analog[n - 54] * 5.0 / 1023
        return level(n) * 5.0
    }
    fun drives(n: Int): Boolean { val p = pinPort[n]; return p >= 0 && (avr.extDriven[p] shr pinBit[n]) and 1 == 1 }
    fun analog(ch: Int, value: Int) { if (ch in 0..15) avr.analog[ch] = value.coerceIn(0, 1023) }

    // ---------- timed events ----------
    private class Ev(val at: Long, val fn: () -> Unit) : Comparable<Ev> { override fun compareTo(other: Ev) = at.compareTo(other.at) }
    private val events = PriorityQueue<Ev>()
    private var nextEvent = Long.MAX_VALUE
    fun at(cycle: Long, fn: () -> Unit) { events += Ev(cycle, fn); if (cycle < nextEvent) nextEvent = cycle }
    // runs the chip up to cycle `end`, firing scheduled part events on time
    private fun runTo(end: Long) {
        while (avr.cycles < end) {
            if (avr.cycles >= nextEvent) {
                val e = events.poll()
                nextEvent = events.peek()?.at ?: Long.MAX_VALUE
                e?.fn?.invoke()
                continue
            }
            // a part may schedule a response during a step, so the next event is re-read every instruction
            while (avr.cycles < end && avr.cycles < nextEvent) avr.step()
        }
    }
    fun after(us: Double, fn: () -> Unit) = at(avr.cycles + (us * 16).toLong(), fn)
    val now get() = avr.cycles
    fun us(cycles: Long) = cycles / 16.0

    // ---------- UI side ----------
    val serialOut = StringBuilder()
    val plot = PlotBuffer()
    @Volatile var plotVersion = 0
    private val plotLine = StringBuilder()
    @Volatile var serialVersion = 0
    @Volatile var running = false
    @Volatile var speed = 0f          // simulated time ÷ real time, as measured
    @Volatile var rate = 1f           // chosen speed: 1 = real time, 0.25 = slow motion, 0 = as fast as possible
        set(v) { field = v; rateChanged = true }
    @Volatile private var rateChanged = false
    @Volatile var stopped = false
    val tick = MutableStateFlow(0L)   // bumps about 30 times a second
    val warnings: StateFlow<List<String>> get() = _warnings
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    private val actions = ConcurrentLinkedQueue<() -> Unit>()
    fun post(fn: () -> Unit) { actions += fn }

    // Pin states for the board view, in the same form as Firmata's
    fun pinStates(): Map<Int, FirmataPin> {
        val out = HashMap<Int, FirmataPin>()
        for (n in 0 until 70) {
            if (pinPort[n] < 0) continue
            val d = duty(n)
            out[n] = when {
                isOutput(n) && d >= 0 -> FirmataPin(n, mode = PinMode.PWM, value = d)
                isOutput(n) -> FirmataPin(n, mode = PinMode.OUTPUT, value = level(n))
                n >= 54 && parts.any { p -> p.analogPins().contains(n - 54) } -> FirmataPin(n, mode = PinMode.ANALOG, value = avr.analog[n - 54])
                pullUp(n) -> FirmataPin(n, mode = PinMode.PULLUP, value = level(n))
                else -> FirmataPin(n, mode = PinMode.INPUT, value = level(n))
            }
        }
        return out
    }

    init {
        avr.onSerial = { b ->
            synchronized(serialOut) { serialOut.append(b.toChar()); if (serialOut.length > 20000) serialOut.delete(0, 5000) }; serialVersion++
            if (b == '\n'.code) { if (plot.addLine(plotLine.toString())) plotVersion++; plotLine.clear() }
            else if (plotLine.length < 200) plotLine.append(b.toChar())
        }
        avr.onPortWrite = { p, old, new ->
            val changed = old xor new
            for (b in 0 until 8) if (changed shr b and 1 == 1) {
                val n = pinOfPort(p, b) ?: continue
                val lv = (new shr b) and 1
                for ((part, role) in listeners[n]) part.onEdge(role, lv)
            }
        }
        avr.beforeAdc = { ch -> for (p in parts) p.beforeAdc(ch) }
        avr.onTimerWrite = { for (p in parts) p.onPwm() }
        avr.twiBus = object : Avr.TwiBus {
            var dev: I2cDevice? = null
            override fun address(addr: Int, read: Boolean): Boolean {
                dev = parts.filterIsInstance<I2cDevice>().firstOrNull { it.i2cAddress == addr }
                dev?.start(read); return dev != null
            }
            override fun write(b: Int) = dev?.write(b) ?: false
            override fun read(ack: Boolean) = dev?.read() ?: 0xFF
            override fun stop() { dev = null }
        }
    }
    private val portBitPin = IntArray(11 * 8) { -1 }.also { t -> for (n in 0 until 70) if (pinPort[n] >= 0) t[pinPort[n] * 8 + pinBit[n]] = n }
    private fun pinOfPort(p: Int, b: Int): Int? = portBitPin[p * 8 + b].takeIf { it >= 0 }
    // pin → (part, role) that react to its edges; rebuilt when parts or their pins change
    private var listeners: Array<Array<Pair<SimPart, Int>>> = Array(70) { emptyArray() }
    fun rewire() {
        val l = Array(70) { ArrayList<Pair<SimPart, Int>>() }
        for (p in parts) p.pins.forEachIndexed { role, n -> if (n in 0..69) l[n] += p to role }
        listeners = Array(70) { l[it].toTypedArray() }
    }

    fun addPart(p: SimPart) = post { p.sim = this; parts += p; rewire(); p.attach() }
    fun removePart(p: SimPart) = post { parts.remove(p); rewire(); p.pins.forEach { drive(it, null) } }
    fun send(text: String) = post { avr.serialIn(text.toByteArray()) }
    fun reset() = post {
        avr.reset(); events.clear(); nextEvent = Long.MAX_VALUE
        synchronized(serialOut) { serialOut.clear() }; serialVersion++; plot.clear(); plotLine.clear(); plotVersion++
        for (p in parts) { p.pins.forEach { drive(it, null) }; p.reset(); p.attach() }
    }

    fun start() {
        running = true
        thread(name = "pinscope-sim", isDaemon = true) {
            var wall0 = SystemClock.elapsedRealtimeNanos(); var cyc0 = avr.cycles
            var lastPublish = 0L
            var spdWall = SystemClock.elapsedRealtimeNanos(); var spdCyc = avr.cycles
            while (!stopped) {
                while (true) { (actions.poll() ?: break).invoke() }
                if (!running) { Thread.sleep(20); wall0 = SystemClock.elapsedRealtimeNanos(); cyc0 = avr.cycles; spdWall = wall0; spdCyc = cyc0; continue }
                // run ~4 ms of chip time at a time, then wait for real time to catch up
                runTo(avr.cycles + CLOCK / 250)
                val nowNs = SystemClock.elapsedRealtimeNanos()
                if (rateChanged) { rateChanged = false; wall0 = nowNs; cyc0 = avr.cycles; spdWall = nowNs; spdCyc = avr.cycles }
                val r = rate
                if (r > 0f) {
                    // where the simulated clock should be at this rate; sleep if ahead
                    val simNs = ((avr.cycles - cyc0) * 1_000_000_000L / CLOCK / r).toLong()
                    val ahead = simNs - (nowNs - wall0)
                    if (ahead > 2_000_000) Thread.sleep(minOf(ahead / 1_000_000, 50))
                    if (ahead < -200_000_000) { wall0 = nowNs; cyc0 = avr.cycles } // fell behind: stop trying to catch up
                }
                // speed: simulated time ÷ real time over the last second (100% = real time)
                if (nowNs - spdWall >= 1_000_000_000) {
                    speed = ((avr.cycles - spdCyc).toFloat() / CLOCK) / ((nowNs - spdWall) / 1e9f)
                    spdWall = nowNs; spdCyc = avr.cycles
                }
                if (nowNs - lastPublish > 33_000_000) {
                    lastPublish = nowNs
                    parts.forEach { it.frame() }
                    _warnings.value = checks()
                    tick.value = nowNs
                }
            }
        }
    }

    fun stop() { stopped = true }

    // Runs without the real-time thread (tests, and stepping)
    fun runCycles(n: Long) {
        while (true) { (actions.poll() ?: break).invoke() }
        val end = avr.cycles + n
        while (avr.cycles < end) {
            runTo(minOf(end, avr.cycles + CLOCK / 30))
            parts.forEach { it.frame() }
        }
        parts.forEach { it.frame() }
        _warnings.value = checks()
    }
    fun serialText() = synchronized(serialOut) { serialOut.toString() }

    // Things that would hurt a real board, or are easy mistakes
    private fun checks(): List<String> {
        val w = ArrayList<String>()
        val serial = avr.data[0xC1] and 0x18 != 0
        for (p in parts) {
            if (p is Button && p.type == PartType.BUTTON && !p.toVcc && p.pins[0] in 0..69 && !isOutput(p.pins[0]) && !pullUp(p.pins[0]))
                w += "Pin ${pinName(p.pins[0])} floats while the button (wired to GND) is up: on a real board it reads random HIGHs and LOWs. Use pinMode(${pinName(p.pins[0])}, INPUT_PULLUP), or wire the button to 5V with a 10 kΩ pull-down (switch it on the button's card)."
            for ((i, n) in p.pins.withIndex()) {
                if (n !in 0..69) continue
                val role = p.type.roles[i]
                if (p is Button && !p.pressed) continue // only a resistor holds the pin while the button is up
                if (p.drivesRole(i) && isOutput(n) && drives(n) && !(p is Button && (if (p.toVcc) level(n) == 1 else level(n) == 0))) {
                    w += if (p is Button && p.toVcc) "Short circuit: pin ${pinName(n)} is a LOW output while the button connects it to 5V. On a real board this can damage the pin."
                    else if (p is Switch) "Short circuit: pin ${pinName(n)} is a HIGH output while the ${p.type.title.lowercase()} connects it to GND. On a real board this can damage the pin."
                    else "Pin ${pinName(n)} is an output, but the ${p.type.title.lowercase()}'s $role also drives it. Two outputs fighting can damage both; use pinMode(${pinName(n)}, INPUT)."
                }
                if (serial && (n == 0 || n == 1)) w += "The ${p.type.title.lowercase()} uses pin $n, which Serial uses for USB. Use another pin."
            }
            if (p is Led) p.pins.forEachIndexed { i, n -> if (n in 0..69 && !isOutput(n) && pullUp(n)) w += "The LED on pin ${pinName(n)} glows only faintly: the pin is an input. Add pinMode(${pinName(n)}, OUTPUT)." }
        }
        val i2c = parts.filterIsInstance<I2cDevice>().groupBy { it.i2cAddress }.filter { it.value.size > 1 }
        i2c.forEach { (a, l) -> w += "Two I2C parts use address 0x%02X (%s). Only one can answer.".format(a, l.joinToString { (it as SimPart).type.title }) }
        return w.distinct()
    }

    fun pinName(n: Int) = if (n >= 54) "A${n - 54}" else "$n"
}

// ---------- parts ----------

enum class PartType(val title: String, val roles: List<String>, val kitId: String?, val art: String?, val drivesRoles: Set<Int> = emptySet()) {
    LED("LED", listOf("Anode (+)"), "buttons", "led"),
    RGB("RGB LED", listOf("Red", "Green", "Blue"), "rgb", "rgb"),
    BUTTON("Push button", listOf("Pin (other leg to GND)"), "buttons", "button", setOf(0)),
    TILT("Tilt switch", listOf("Leg 1 (leg 2 to GND)"), "tilt", "tilt", setOf(0)),
    POT("Potentiometer", listOf("Wiper"), "pot", "pot"),
    PHOTO("Photoresistor", listOf("Divider"), "photocell", "ldr"),
    THERMISTOR("Thermistor", listOf("Divider"), "thermistor", "thermistor"),
    WATER("Water level sensor", listOf("S"), "water", "water"),
    SOUND("Sound sensor", listOf("AO", "DO"), "sound", "sound", setOf(1)),
    JOYSTICK("Joystick", listOf("VRx", "VRy", "SW"), "joystick", "joystick", setOf(2)),
    PIR("PIR motion sensor", listOf("OUT"), "hcsr501", "pir", setOf(0)),
    BUZZER("Buzzer", listOf("+"), "buzzers", "buzzer"),
    SERVO("Servo (SG90)", listOf("Signal"), "sg90", "servo"),
    RELAY("Relay", listOf("Coil"), null, "relay"),
    SEG7("1-digit 7-segment", listOf("A", "B", "C", "D", "E", "F", "G", "DP"), null, "seg7"),
    SEG7X4("4-digit 7-segment", listOf("A", "B", "C", "D", "E", "F", "G", "DP", "DIG1", "DIG2", "DIG3", "DIG4"), "5461as", "seg7x4"),
    SHIFT595("74HC595 + 8 LEDs", listOf("DS", "ST_CP", "SH_CP"), "74hc595", "sr595"),
    SHIFT595_SEG("74HC595 + 7-segment", listOf("DS", "ST_CP", "SH_CP"), "5161as", "seg7"),
    LCD1602("LCD1602", listOf("RS", "E", "D4", "D5", "D6", "D7"), "lcd1602", "lcd"),
    KEYPAD("4×4 keypad", listOf("R1", "R2", "R3", "R4", "C1", "C2", "C3", "C4"), "keypad", "keypad", setOf(0, 1, 2, 3, 4, 5, 6, 7)),
    ENCODER("Rotary encoder", listOf("CLK", "DT", "SW"), "encoder", "encoder", setOf(0, 1, 2)),
    HCSR04("Ultrasonic sensor", listOf("Trig", "Echo"), "hcsr04", "hcsr04", setOf(1)),
    DHT11("DHT11", listOf("Data"), "dht11", "dht11", setOf(0)),
    MAX7219("8×8 LED matrix", listOf("DIN", "CS", "CLK"), "max7219", "max7219"),
    IR("IR receiver + remote", listOf("S"), "ir", "ir", setOf(0)),
    STEPPER("Stepper + ULN2003", listOf("IN1", "IN2", "IN3", "IN4"), "stepper", "stepper"),
    MOTOR("DC motor (L293D)", listOf("EN1,2", "1A", "2A"), "l293d", "motor"),
    RTC("DS1307 clock (I2C)", listOf("SDA", "SCL"), "rtc", "rtc"),
    MPU6050("MPU-6050 (I2C)", listOf("SDA", "SCL"), "gy521", "mpu6050"),
    SCOPE("Oscilloscope probe", listOf("Probe"), null, null),
    RC("RC filter (R then C to GND)", listOf("In", "Out"), null, null);

    fun isAnalogRole(i: Int) = when (this) {
        POT, PHOTO, THERMISTOR, WATER -> true
        SOUND -> i == 0
        JOYSTICK -> i < 2
        RC -> i == 1
        else -> false
    }
}

abstract class SimPart(val type: PartType, val pins: IntArray) {
    val id = System.nanoTime()
    lateinit var sim: Simulator
    open fun attach() {}
    open fun reset() {}
    open fun onEdge(role: Int, level: Int) {}
    open fun frame() {}               // ~30 times a second, on the simulator thread
    open fun onPwm() {}               // the sketch changed a timer (PWM duty or frequency)
    open fun beforeAdc(ch: Int) {}    // an analog reading is about to be taken
    fun pinIndex(n: Int) = pins.indices.filter { pins[it] == n }
    fun drivesRole(i: Int) = i in type.drivesRoles
    fun analogPins() = pins.indices.filter { type.isAnalogRole(it) }.map { pins[it] - 54 }.filter { it >= 0 }
    @Volatile var version = 0         // bumps when what the card shows changes
    fun set(ch: Int, v: Int) = sim.analog(ch, v)
    fun ach(i: Int) = pins[i] - 54
}

interface Switch
interface I2cDevice {
    val i2cAddress: Int
    fun start(read: Boolean)
    fun write(b: Int): Boolean
    fun read(): Int
}

class Led(t: PartType, pins: IntArray) : SimPart(t, pins) {
    @Volatile var bright = FloatArray(pins.size)
    override fun frame() { bright = FloatArray(pins.size) { sim.brightness(pins[it]) } }
}

class Button(t: PartType, pins: IntArray) : SimPart(t, pins), Switch {
    @Volatile var pressed = false
    @Volatile var bouncy = false      // contacts chatter for about a millisecond, like real ones
    // false: the other leg goes to GND (pressed reads LOW; needs INPUT_PULLUP).
    // true: the other leg goes to 5V, with a 10 kΩ pull-down from the pin to GND (pressed reads HIGH; works with INPUT).
    @Volatile var toVcc = false
    private fun level(closed: Boolean): Int? = if (toVcc) (if (closed) 1 else 0) else (if (closed) 0 else null)
    override fun attach() { sim.drive(pins[0], level(pressed)) }
    fun wire(vcc: Boolean) = sim.post { toVcc = vcc; sim.drive(pins[0], level(pressed)); version++ }
    fun press(on: Boolean) = sim.post {
        pressed = on
        if (!bouncy) { sim.drive(pins[0], level(on)); return@post }
        // a few random open/close flickers 20–300 µs apart, settling in the new state
        val r = java.util.Random()
        var t = 0.0
        val flips = 2 * (1 + r.nextInt(if (on) 4 else 2))
        for (i in 0..flips) {
            val closed = (i % 2 == 0) == on
            sim.after(t) { if (pressed == on) sim.drive(pins[0], level(closed)) }
            t += 20 + r.nextInt(280)
        }
    }
}

// Change points of a signal, kept in a ring: the voltage from each point on, or a PWM wave (duty 0–255, period in cycles)
class SignalLog(private val size: Int = 16384) {
    private val at = LongArray(size); private val v = DoubleArray(size); private val v2 = DoubleArray(size)
    private val duty = IntArray(size); private val per = IntArray(size)
    private var head = 0; var count = 0; private set
    fun clear() { head = 0; count = 0 }
    fun add(t: Long, volts: Double, d: Int = -1, period: Int = 0, aux: Double = 0.0) {
        at[head] = t; v[head] = volts; duty[head] = d; per[head] = period; v2[head] = aux
        head = (head + 1) % size; if (count < size) count++
    }
    private fun idx(k: Int) = (head - count + k + size) % size
    // index (in ring order) of the last point at or before t, or 0
    fun find(t: Long): Int {
        var lo = 0; var hi = count - 1; var best = 0
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (at[idx(mid)] <= t) { best = mid; lo = mid + 1 } else hi = mid - 1 }
        return idx(best)
    }
    fun last() = if (count == 0) -1 else idx(count - 1)
    fun time(i: Int) = at[i]; fun volts(i: Int) = v[i]; fun aux(i: Int) = v2[i]
    // a square wave at the logged duty and period, or the logged level
    fun eval(t: Long): Double {
        if (count == 0) return 0.0
        val i = find(t)
        if (duty[i] < 0 || per[i] <= 0) return v[i]
        val pos = Math.floorMod(t - at[i], per[i].toLong())
        return if (pos * 255 < duty[i].toLong() * per[i]) 5.0 else 0.0
    }
}

// A resistor from one pin to an analog pin, with a capacitor from there to GND: the analog pin charges
// towards the input with time constant R × C. PWM inputs count as their average.
class RcFilter(pins: IntArray) : SimPart(PartType.RC, pins) {
    @Volatile var ohms = 1000.0
    @Volatile var farads = 10e-6
    @Volatile var vOut = 0.0
    val log = SignalLog()             // (time, output voltage at that time, input voltage from then on in aux)
    private var t0 = 0L; private var vin = 0.0
    private fun tau() = ohms * farads * CLOCK
    private fun advance() {
        val t = sim.now
        if (t > t0) { vOut = vin + (vOut - vin) * kotlin.math.exp(-(t - t0) / tau()); t0 = t }
    }
    private fun update() {
        advance()
        val n = sim.volts(pins[0])
        if (n != vin) { vin = n; log.add(t0, vOut, aux = vin) }
    }
    private fun push() { if (pins[1] in 54..69) set(pins[1] - 54, (vOut / 5 * 1023).toInt()) }
    override fun attach() { t0 = sim.now; vOut = 0.0; vin = sim.volts(pins[0]); log.clear(); log.add(t0, vOut, aux = vin); push() }
    override fun reset() { vOut = 0.0 }
    override fun onEdge(role: Int, level: Int) { if (role == 0) update() }
    override fun onPwm() = update()
    override fun beforeAdc(ch: Int) { if (ch == pins[1] - 54) { advance(); push() } }
    override fun frame() { update(); push() }
    fun setValues(r: Double, c: Double) = sim.post { advance(); ohms = r; farads = c; log.add(t0, vOut, aux = vin); version++ }
    // the output voltage at a past time
    fun eval(t: Long): Double {
        if (log.count == 0) return vOut
        val i = log.find(t)
        val dt = (t - log.time(i)).coerceAtLeast(0)
        val vi = log.aux(i)
        return vi + (log.volts(i) - vi) * kotlin.math.exp(-dt / tau())
    }
}

// A one-channel oscilloscope on any pin: logs each change of the pin and draws the last screenful,
// optionally lined up on a rising or falling edge. Samples are instants, so too slow a time base aliases like a real scope.
class Scope(pins: IntArray) : SimPart(PartType.SCOPE, pins) {
    @Volatile var spanMs = 10f
    @Volatile var edge = 1                // 0 free running, 1 rising, 2 falling
    @Volatile var level = 2.5f
    @Volatile var hold = false
    @Volatile var trace = FloatArray(0)
    @Volatile var triggered = false
    private val log = SignalLog()
    private var lastV = Double.NaN; private var lastDuty = -2; private var lastPer = -1
    fun changed() {
        val n = pins[0]
        val d = if (n in 0..69 && sim.isOutput(n)) sim.duty(n) else -1
        val per = if (d >= 0) sim.pwmPeriod(n) else 0
        val v = sim.volts(n)
        if (d >= 0) { if (d != lastDuty || per != lastPer) log.add(sim.now, v, d, per) }
        else if (v != lastV || lastDuty >= 0) log.add(sim.now, v)
        lastV = v; lastDuty = d; lastPer = per
    }
    override fun attach() { log.clear(); lastV = Double.NaN; lastDuty = -2; changed() }
    override fun onEdge(role: Int, level: Int) = changed()
    override fun onPwm() = changed()
    private fun source(): (Long) -> Double {
        val rc = sim.parts.firstOrNull { it is RcFilter && it.pins[1] == pins[0] } as RcFilter?
        return if (rc != null) rc::eval else log::eval
    }
    override fun frame() {
        changed()
        if (hold) return
        val f = source()
        val span = (spanMs * CLOCK / 1000).toLong().coerceAtLeast(16)
        val now = sim.now
        var start = now - span
        var found = false
        if (edge != 0) {
            // newest edge with a full screen after it, looking back up to three screens
            val steps = 1200
            val from = now - 3 * span; val to = now - span * 3 / 4
            val dt = (to - from) / steps
            if (dt > 0) {
                var prev = f(to)
                for (k in steps - 1 downTo 0) {
                    val t = from + k * dt
                    val v = f(t)
                    val hit = if (edge == 1) v < level && prev >= level else v > level && prev <= level
                    if (hit) { start = t + dt - span / 4; found = true; break }
                    prev = v
                }
            }
            if (!found && triggered) return       // keep the last triggered screen, like a scope's normal mode
        }
        val pts = 600
        trace = FloatArray(pts) { f(start + span * it / (pts - 1)).toFloat() }
        triggered = found
    }
}

// One analog value, 0–1023, from a slider (potentiometer, light, temperature, water…)
class AnalogPart(t: PartType, pins: IntArray, initial: Float) : SimPart(t, pins) {
    @Volatile var value = initial     // 0–1 slider position
    override fun attach() = apply(value)
    fun apply(v: Float) = sim.post {
        value = v
        val raw = when (type) {
            PartType.THERMISTOR -> {
                // slider is 0–50 °C; NTC 10 kΩ at 25 °C, B = 3950, from 5V to the pin, 10 kΩ to GND
                val tC = v * 50f; val r = 10000.0 * Math.exp(3950.0 * (1 / (tC + 273.15) - 1 / 298.15))
                (1023 * 10000.0 / (r + 10000.0)).toInt()
            }
            PartType.PHOTO -> (1023 * (0.05 + 0.9 * v)).toInt()
            PartType.WATER -> (680 * v).toInt()
            else -> (1023 * v).toInt()
        }
        set(ach(0), raw)
        if (type == PartType.SOUND) sim.drive(pins[1], if (v > 0.5f) 1 else 0)
    }
}

class Joystick(pins: IntArray) : SimPart(PartType.JOYSTICK, pins), Switch {
    @Volatile var x = 0.5f; @Volatile var y = 0.5f; @Volatile var pressed = false
    override fun attach() { move(x, y) }
    fun move(nx: Float, ny: Float) = sim.post { x = nx; y = ny; set(ach(0), (nx * 1023).toInt()); set(ach(1), (ny * 1023).toInt()) }
    fun press(on: Boolean) = sim.post { pressed = on; sim.drive(pins[2], if (on) 0 else null) }
}

class Pir(pins: IntArray) : SimPart(PartType.PIR, pins) {
    @Volatile var motion = false
    override fun attach() = sim.drive(pins[0], 0)
    fun trigger(on: Boolean) = sim.post { motion = on; sim.drive(pins[0], if (on) 1 else 0) }
}

// Pulse timing on an output pin: servo angle, buzzer pitch, motor speed
class Meter(t: PartType, pins: IntArray) : SimPart(t, pins) {
    private var rise = 0L; private var lastEdge = 0L; private var toggles = 0; private var window = 0L
    @Volatile var pulseUs = 0.0; @Volatile var freq = 0.0; @Volatile var on = false
    @Volatile var minUs = 544f; @Volatile var maxUs = 2400f
    override fun onEdge(role: Int, level: Int) {
        val t = sim.now
        if (level == 1) rise = t else if (rise > 0) pulseUs = sim.us(t - rise)
        toggles++; lastEdge = t
    }
    override fun frame() {
        val t = sim.now
        if (window == 0L) window = t
        val span = t - window
        if (span > CLOCK / 4) { freq = toggles / 2.0 / (span.toDouble() / CLOCK); toggles = 0; window = t }
        if (t - lastEdge > CLOCK / 4) { freq = 0.0; if (type == PartType.SERVO) pulseUs = 0.0 }
        on = sim.level(pins[0]) == 1 || freq > 0
    }
    val angle: Float get() = if (pulseUs <= 0) -1f else ((pulseUs.toFloat() - minUs) / (maxUs - minUs) * 180f).coerceIn(0f, 180f)
}

class Motor(pins: IntArray) : SimPart(PartType.MOTOR, pins) {
    @Volatile var speed = 0f; @Volatile var turn = 0f
    override fun frame() {
        val en = sim.duty(pins[0]).let { if (it >= 0) it / 255f else sim.level(pins[0]).toFloat() }
        val a = sim.level(pins[1]); val b = sim.level(pins[2])
        speed = when { a == 1 && b == 0 -> en; a == 0 && b == 1 -> -en; else -> 0f }
        turn = (turn + speed * 20f) % 360f
    }
}

class Stepper(pins: IntArray) : SimPart(PartType.STEPPER, pins) {
    @Volatile var steps = 0
    private var last = -1
    // half-step order of the coil pattern IN1..IN4
    private val seq = intArrayOf(0b1000, 0b1100, 0b0100, 0b0110, 0b0010, 0b0011, 0b0001, 0b1001)
    override fun onEdge(role: Int, level: Int) {
        val pat = pins.indices.fold(0) { acc, i -> acc or (sim.level(pins[i]) shl (3 - i)) }
        val idx = seq.indexOf(pat); if (idx < 0) return
        if (last >= 0) { val d = (idx - last + 8) % 8; if (d in 1..2) steps += d; else if (d in 6..7) steps -= 8 - d }
        last = idx
    }
    val angle get() = (steps % 4096) * 360f / 4096f // 28BYJ-48: 4096 half-steps a turn
}

// Digit segments: a direct 7-segment display, or a 74HC595 feeding LEDs or a display
class Segments(t: PartType, pins: IntArray) : SimPart(t, pins) {
    @Volatile var segs = IntArray(4)          // per digit, bits A..DP
    private val litTime = Array(4) { IntArray(8) }
    private var lastSample = 0L
    // 74HC595
    private var shift = 0; @Volatile var latched = 0
    override fun onEdge(role: Int, level: Int) {
        if (type == PartType.SHIFT595 || type == PartType.SHIFT595_SEG) {
            if (role == 2 && level == 1) shift = ((shift shl 1) or sim.level(pins[0])) and 0xFF
            if (role == 1 && level == 1) { latched = shift; version++ }
        }
    }
    override fun frame() {
        when (type) {
            PartType.SEG7 -> segs = intArrayOf((0 until 8).fold(0) { a, i -> a or ((if (sim.brightness(pins[i]) > 0.3f) 1 else 0) shl i) }, 0, 0, 0)
            PartType.SHIFT595_SEG -> segs = intArrayOf(latched.let { v -> (0 until 8).fold(0) { a, i -> a or (((v shr i) and 1) shl i) } }, 0, 0, 0)
            PartType.SEG7X4 -> {
                // multiplexed: a segment shows when its anode is HIGH and its digit's cathode is LOW
                val out = IntArray(4)
                for (d in 0 until 4) {
                    val digitOn = sim.isOutput(pins[8 + d]) && sim.level(pins[8 + d]) == 0
                    for (s in 0 until 8) {
                        val on = digitOn && sim.level(pins[s]) == 1
                        litTime[d][s] = if (on) 6 else maxOf(0, litTime[d][s] - 1)
                        if (litTime[d][s] > 0) out[d] = out[d] or (1 shl s)
                    }
                }
                segs = out
            }
            else -> {}
        }
    }
    // Multiplexing changes faster than frames: sample the digits often between frames
    override fun attach() { if (type == PartType.SEG7X4) poll() }
    private fun poll() {
        sim.after(500.0) {
            for (d in 0 until 4) {
                if (!(sim.isOutput(pins[8 + d]) && sim.level(pins[8 + d]) == 0)) continue
                for (s in 0 until 8) if (sim.level(pins[s]) == 1) litTime[d][s] = 6
            }
            if (sim.parts.contains(this)) poll()
        }
    }
}

// HD44780 in 4-bit mode, as driven by the LiquidCrystal library
class Lcd(pins: IntArray) : SimPart(PartType.LCD1602, pins) {
    val ddram = IntArray(0x68) { 0x20 }
    val cgram = IntArray(64)
    private var addr = 0; private var cg = false; private var eight = true; private var high = -1; private var inc = true
    @Volatile var displayOn = false
    // the contrast pot on V0, which is wired to the LCD rather than the Arduino: 0 = V0 at 5 V (blank), 1 = V0 at 0 V (darkest)
    @Volatile var contrast = 0.7f
    override fun reset() { ddram.fill(0x20); addr = 0; eight = true; high = -1; displayOn = false; version++ }
    override fun onEdge(role: Int, level: Int) {
        if (role != 1 || level != 0) return // latch on E falling
        val nib = (0 until 4).fold(0) { a, i -> a or (sim.level(pins[2 + i]) shl i) }
        val rs = sim.level(pins[0])
        if (eight) { byte(rs, nib shl 4); return }
        if (high < 0) { high = nib; return }
        byte(rs, (high shl 4) or nib); high = -1
    }
    private fun byte(rs: Int, b: Int) {
        if (rs == 1) {
            if (cg) { cgram[addr and 63] = b; addr = (addr + 1) and 63 } else { ddram[addr % 0x68] = b; addr = (if (inc) addr + 1 else addr - 1).mod(0x68) }
            version++; return
        }
        when {
            b == 0x01 -> { ddram.fill(0x20); addr = 0; cg = false }
            b and 0xFE == 0x02 -> { addr = 0; cg = false }
            b and 0xFC == 0x04 -> inc = b and 2 != 0
            b and 0xF8 == 0x08 -> displayOn = b and 4 != 0
            b and 0xE0 == 0x20 -> eight = b and 0x10 != 0
            b and 0xC0 == 0x40 -> { addr = b and 63; cg = true }
            b and 0x80 == 0x80 -> { addr = b and 0x7F; cg = false }
        }
        version++
    }
    fun line(r: Int) = (0 until 16).map { ddram[(if (r == 0) 0 else 0x40) + it] }
}

// MAX7219 driven by LedControl's software SPI: 16-bit frames latched on CS rising
class Matrix(pins: IntArray) : SimPart(PartType.MAX7219, pins) {
    val rows = IntArray(8)
    private var frame = 0
    @Volatile var shutdown = true; @Volatile var intensity = 8
    override fun onEdge(role: Int, level: Int) {
        if (role == 2 && level == 1) frame = ((frame shl 1) or sim.level(pins[0])) and 0xFFFF
        if (role == 1 && level == 1) {
            val reg = (frame shr 8) and 0xF; val d = frame and 0xFF
            when (reg) { in 1..8 -> rows[reg - 1] = d; 0xA -> intensity = d and 0xF; 0xC -> shutdown = d and 1 == 0; 0xF -> if (d and 1 == 1) rows.fill(0xFF) }
            version++
        }
    }
}

// Keypad: a pressed key joins its row and column; whichever side the sketch drives, the other follows
class Keypad(pins: IntArray) : SimPart(PartType.KEYPAD, pins), Switch {
    @Volatile var pressedKey = -1          // row*4 + column
    val labels = listOf("1", "2", "3", "A", "4", "5", "6", "B", "7", "8", "9", "C", "*", "0", "#", "D")
    fun press(k: Int) = sim.post { pressedKey = k; update() }
    override fun onEdge(role: Int, level: Int) = update()
    private fun update() {
        for (i in 0 until 8) sim.drive(pins[i], null)
        val k = pressedKey; if (k < 0) return
        val r = pins[k / 4]; val c = pins[4 + k % 4]
        when {
            sim.isOutput(c) && !sim.isOutput(r) -> sim.drive(r, sim.level(c))
            sim.isOutput(r) && !sim.isOutput(c) -> sim.drive(c, sim.level(r))
        }
    }
}

// Rotary encoder: CLK and DT with pull-ups; each detent is a quarter-phase sequence
class Encoder(pins: IntArray) : SimPart(PartType.ENCODER, pins), Switch {
    @Volatile var position = 0; @Volatile var pressed = false
    override fun attach() { sim.drive(pins[0], 1); sim.drive(pins[1], 1) }
    fun turn(dir: Int) = sim.post {
        position += dir
        val (a, b) = if (dir > 0) pins[0] to pins[1] else pins[1] to pins[0]
        sim.drive(a, 0); sim.after(1500.0) { sim.drive(b, 0) }
        sim.after(3000.0) { sim.drive(a, 1) }; sim.after(4500.0) { sim.drive(b, 1) }
    }
    fun press(on: Boolean) = sim.post { pressed = on; sim.drive(pins[2], if (on) 0 else null) }
}

// HC-SR04: a 10 µs trigger pulse starts a measurement; Echo goes HIGH for 58 µs per centimetre
class Ultrasonic(pins: IntArray) : SimPart(PartType.HCSR04, pins) {
    @Volatile var cm = 30f
    private var trigRise = 0L
    @Volatile var pings = 0
    override fun attach() = sim.drive(pins[1], 0)
    override fun onEdge(role: Int, level: Int) {
        if (role != 0) return
        if (level == 1) { trigRise = sim.now; return }
        if (sim.us(sim.now - trigRise) < 8) return
        pings++
        val echo = if (cm > 400) 38000.0 else cm * 58.0
        sim.after(450.0) { sim.drive(pins[1], 1); sim.after(echo) { sim.drive(pins[1], 0) } }
    }
}

// DHT11: after the sketch holds the line LOW for 18 ms and releases it, the sensor answers with 40 bits
class Dht11(pins: IntArray) : SimPart(PartType.DHT11, pins) {
    @Volatile var tempC = 22f; @Volatile var humidity = 45f; @Volatile var reads = 0
    private var lowAt = -1L
    override fun onEdge(role: Int, level: Int) {
        if (level == 0 && sim.isOutput(pins[0])) { lowAt = sim.now; return }
        if (level == 1 && lowAt >= 0 && sim.us(sim.now - lowAt) > 15000) { lowAt = -1; respond() } else if (level == 1) lowAt = -1
    }
    private fun respond() {
        reads++
        val h = humidity.toInt().coerceIn(0, 99); val t = tempC.toInt().coerceIn(0, 60)
        val td = kotlin.math.round((tempC - t) * 10).toInt().coerceIn(0, 9)
        val bytes = intArrayOf(h, 0, t, td, (h + t + td) and 0xFF)
        var t0 = 30.0
        fun at(us: Double, v: Int?) = sim.after(us) { sim.drive(pins[0], v) }
        at(t0, 0); t0 += 80; at(t0, 1); t0 += 80
        for (byte in bytes) for (b in 7 downTo 0) {
            at(t0, 0); t0 += 50
            at(t0, 1); t0 += if ((byte shr b) and 1 == 1) 70.0 else 26.0
        }
        at(t0, 0); t0 += 50; at(t0, null)
    }
}

// IR receiver: the remote's buttons send NEC frames (address 0x00), output idles HIGH
class IrRemote(pins: IntArray) : SimPart(PartType.IR, pins) {
    // ELEGOO remote layout: label to NEC command
    val keys = listOf("POWER" to 0x45, "VOL+" to 0x46, "FUNC" to 0x47, "|<<" to 0x44, ">||" to 0x40, ">>|" to 0x43, "DOWN" to 0x07, "VOL-" to 0x15, "UP" to 0x09,
        "0" to 0x16, "EQ" to 0x19, "ST/REPT" to 0x0D, "1" to 0x0C, "2" to 0x18, "3" to 0x5E, "4" to 0x08, "5" to 0x1C, "6" to 0x5A, "7" to 0x42, "8" to 0x52, "9" to 0x4A)
    @Volatile var lastKey: String? = null
    override fun attach() = sim.drive(pins[0], 1)
    fun send(label: String, cmd: Int) = sim.post {
        lastKey = label
        val bits = (0x00) or (0xFF shl 8) or (cmd shl 16) or ((cmd xor 0xFF) shl 24)
        var t = 0.0
        fun at(us: Double, v: Int) = sim.after(us) { sim.drive(pins[0], v) }
        at(t, 0); t += 9000; at(t, 1); t += 4500
        for (i in 0 until 32) { at(t, 0); t += 562.5; at(t, 1); t += if ((bits ushr i) and 1 == 1) 1687.5 else 562.5 }
        at(t, 0); t += 562.5; at(t, 1)
    }
}

class Relay(pins: IntArray) : SimPart(PartType.RELAY, pins) {
    @Volatile var closed = false
    override fun frame() { closed = sim.isOutput(pins[0]) && sim.level(pins[0]) == 1 }
}

// DS1307 real-time clock at 0x68: time from the phone's clock plus any offset the sketch sets
class Rtc(pins: IntArray) : SimPart(PartType.RTC, pins), I2cDevice {
    override val i2cAddress = 0x68
    private var ptr = 0; private var first = false
    private val ram = IntArray(64)
    @Volatile var offsetSec = 0L
    private fun bcd(v: Int) = ((v / 10) shl 4) or (v % 10)
    private fun unbcd(v: Int) = ((v shr 4) and 0xF) * 10 + (v and 0xF)
    private fun now() = java.time.LocalDateTime.now().plusSeconds(offsetSec)
    override fun start(read: Boolean) { first = !read }
    override fun write(b: Int): Boolean {
        if (first) { ptr = b and 63; first = false; return true }
        if (ptr < 7) {
            val t = now()
            val f = intArrayOf(t.second, t.minute, t.hour, t.dayOfWeek.value % 7 + 1, t.dayOfMonth, t.monthValue, t.year % 100)
            f[ptr] = unbcd(b and 0x7F)
            runCatching {
                val set = java.time.LocalDateTime.of(2000 + f[6], f[5].coerceIn(1, 12), f[4].coerceIn(1, 28), f[2].coerceIn(0, 23), f[1].coerceIn(0, 59), f[0].coerceIn(0, 59))
                offsetSec = java.time.Duration.between(java.time.LocalDateTime.now(), set).seconds
            }
        } else ram[ptr] = b
        ptr = (ptr + 1) and 63; version++
        return true
    }
    override fun read(): Int {
        val t = now()
        val v = when (ptr) { 0 -> bcd(t.second); 1 -> bcd(t.minute); 2 -> bcd(t.hour); 3 -> t.dayOfWeek.value % 7 + 1; 4 -> bcd(t.dayOfMonth); 5 -> bcd(t.monthValue); 6 -> bcd(t.year % 100); else -> ram[ptr] }
        ptr = (ptr + 1) and 63
        return v
    }
}

// MPU-6050 at 0x68: acceleration and rotation from sliders or the phone's own motion sensors
class Mpu6050(pins: IntArray) : SimPart(PartType.MPU6050, pins), I2cDevice {
    override val i2cAddress = 0x68
    private var ptr = 0; private var first = false
    private val regs = IntArray(128).also { it[0x75] = 0x68; it[0x6B] = 0x40 }
    @Volatile var ax = 0f; @Volatile var ay = 0f; @Volatile var az = 1f   // g
    @Volatile var gx = 0f; @Volatile var gy = 0f; @Volatile var gz = 0f   // °/s
    @Volatile var tempC = 24f
    override fun start(read: Boolean) { first = !read }
    override fun write(b: Int): Boolean { if (first) { ptr = b and 0x7F; first = false } else { regs[ptr] = b; ptr = (ptr + 1) and 0x7F }; return true }
    override fun read(): Int {
        fun s16(v: Float) = v.toInt().coerceIn(-32768, 32767) and 0xFFFF
        val aScale = 16384f / (1 shl ((regs[0x1C] shr 3) and 3)); val gScale = 131f / (1 shl ((regs[0x1B] shr 3) and 3))
        val words = intArrayOf(s16(ax * aScale), s16(ay * aScale), s16(az * aScale), s16((tempC - 36.53f) * 340f), s16(gx * gScale), s16(gy * gScale), s16(gz * gScale))
        val v = if (ptr in 0x3B..0x48) { val w = words[(ptr - 0x3B) / 2]; if ((ptr - 0x3B) % 2 == 0) w shr 8 else w and 0xFF } else regs[ptr]
        ptr = (ptr + 1) and 0x7F
        return v
    }
}

fun createPart(t: PartType, pins: IntArray): SimPart = when (t) {
    PartType.LED, PartType.RGB -> Led(t, pins)
    PartType.BUTTON, PartType.TILT -> Button(t, pins)
    PartType.POT -> AnalogPart(t, pins, 0.5f)
    PartType.PHOTO -> AnalogPart(t, pins, 0.6f)
    PartType.THERMISTOR -> AnalogPart(t, pins, 0.45f)
    PartType.WATER -> AnalogPart(t, pins, 0f)
    PartType.SOUND -> AnalogPart(t, pins, 0.1f)
    PartType.JOYSTICK -> Joystick(pins)
    PartType.PIR -> Pir(pins)
    PartType.BUZZER, PartType.SERVO -> Meter(t, pins)
    PartType.RELAY -> Relay(pins)
    PartType.SEG7, PartType.SEG7X4, PartType.SHIFT595, PartType.SHIFT595_SEG -> Segments(t, pins)
    PartType.LCD1602 -> Lcd(pins)
    PartType.KEYPAD -> Keypad(pins)
    PartType.ENCODER -> Encoder(pins)
    PartType.HCSR04 -> Ultrasonic(pins)
    PartType.DHT11 -> Dht11(pins)
    PartType.MAX7219 -> Matrix(pins)
    PartType.IR -> IrRemote(pins)
    PartType.STEPPER -> Stepper(pins)
    PartType.MOTOR -> Motor(pins)
    PartType.RTC -> Rtc(pins)
    PartType.MPU6050 -> Mpu6050(pins)
    PartType.SCOPE -> Scope(pins)
    PartType.RC -> RcFilter(pins)
}

// Default pins: the ELEGOO kit lesson's wiring for each part (see the Parts tab), so kit sketches work unchanged
fun defaultPins(t: PartType): IntArray {
    return when (t) {
        PartType.LED -> intArrayOf(13)
        PartType.RGB -> intArrayOf(6, 5, 3)
        PartType.BUTTON -> intArrayOf(9)
        PartType.TILT -> intArrayOf(2)
        PartType.POT, PartType.PHOTO, PartType.THERMISTOR, PartType.WATER -> intArrayOf(54)
        PartType.SOUND -> intArrayOf(54, 3)
        PartType.JOYSTICK -> intArrayOf(54, 55, 2)
        PartType.PIR -> intArrayOf(7)
        PartType.BUZZER -> intArrayOf(12)
        PartType.SERVO -> intArrayOf(9)
        PartType.RELAY -> intArrayOf(7)
        PartType.SEG7 -> intArrayOf(22, 23, 24, 25, 26, 27, 28, 29)
        PartType.SEG7X4 -> intArrayOf(22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33)
        PartType.SHIFT595 -> intArrayOf(12, 11, 9)
        PartType.SHIFT595_SEG -> intArrayOf(2, 3, 4)
        PartType.LCD1602 -> intArrayOf(7, 8, 9, 10, 11, 12)
        PartType.KEYPAD -> intArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        PartType.ENCODER -> intArrayOf(2, 3, 4)
        PartType.HCSR04 -> intArrayOf(12, 11)
        PartType.DHT11 -> intArrayOf(2)
        PartType.MAX7219 -> intArrayOf(12, 11, 10)
        PartType.IR -> intArrayOf(11)
        PartType.STEPPER -> intArrayOf(8, 9, 10, 11)
        PartType.MOTOR -> intArrayOf(5, 3, 4)
        PartType.RTC, PartType.MPU6050 -> intArrayOf(20, 21)
        PartType.SCOPE -> intArrayOf(54)
        PartType.RC -> intArrayOf(9, 54)
    }
}
