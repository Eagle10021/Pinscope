package io.github.eagle10021.pinscope

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

// The USB bench: one connection shared by the monitor, plotter, pin controls and the live board overlay.

enum class BenchMode { SERIAL, FIRMATA }

sealed interface Conn {
    data object NoBoard : Conn
    data class Found(val name: String) : Conn
    data object Asking : Conn
    data class Connecting(val name: String) : Conn
    data class Connected(val name: String, val chip: ChipKind, val mode: BenchMode, val baud: Int) : Conn
    data class Failed(val message: String) : Conn
}

enum class LineKind { IN, OUT, INFO }

sealed interface UploadState {
    data object Idle : UploadState
    data class Running(val name: String, val progress: Float, val step: String) : UploadState
    data class Done(val name: String, val seconds: Float, val bytes: Int, val backup: String?) : UploadState
    data class Failed(val name: String, val message: String) : UploadState
}
data class LogLine(val id: Long, val time: Long, val text: String, val kind: LineKind)

data class FirmataState(
    val firmware: String? = null,
    val waiting: Boolean = false,  // connected, but no reply from Firmata yet
    val noReply: Boolean = false,  // gave up waiting
    val pins: Map<Int, FirmataPin> = emptyMap(),
)

// Rolling samples per named series, for the plotter
class PlotBuffer(private val capacity: Int = 600) {
    private val series = LinkedHashMap<String, FloatArray>()
    private val counts = HashMap<String, Int>()
    private val heads = HashMap<String, Int>()

    @Synchronized fun add(name: String, v: Float) {
        val buf = series.getOrPut(name) { FloatArray(capacity) }
        val head = heads[name] ?: 0
        buf[head] = v
        heads[name] = (head + 1) % capacity
        counts[name] = minOf(capacity, (counts[name] ?: 0) + 1)
    }

    @Synchronized fun clear() { series.clear(); counts.clear(); heads.clear() }

    // Arduino Serial Plotter format: numbers split by spaces, commas or tabs, optionally "label:value"
    fun addLine(line: String): Boolean {
        var i = 0
        for (tok in line.trim().split(Regex("[\\s,;]+"))) {
            if (tok.isEmpty()) continue
            val colon = tok.indexOf(':')
            val (name, num) = if (colon > 0) tok.substring(0, colon) to tok.substring(colon + 1) else "Value ${i + 1}" to tok
            val v = num.toFloatOrNull() ?: continue
            add(name, v)
            i++
        }
        return i > 0
    }

    // Oldest-first copy of each series
    @Synchronized fun snapshot(): List<Pair<String, FloatArray>> = series.map { (name, buf) ->
        val n = counts[name] ?: 0
        val head = heads[name] ?: 0
        name to FloatArray(n) { buf[(head - n + it + capacity * 2) % capacity] }
    }
}

// Test signals from the scope sketch: square on pin 11 (0 = off), PWM duty on pin 9, a wave on pin 6
// (shape 0 off, 1 sine, 2 triangle, 3 sawtooth), and the capture pin's pull-up
data class GenSettings(
    val squareHz: Double = 0.0,
    val pwmDuty: Int = 64,
    val waveShape: Int = 0,
    val waveHz: Double = 10.0,
    val pullUp: Boolean = false,
)

// One capture from the fast-scope sketch: 8-bit samples (0–255 = 0–5 V) taken at a known rate
data class ScopeFrame(val samples: ByteArray, val rateHz: Double, val triggered: Boolean, val at: Long = System.currentTimeMillis())

data class ScopeRequest(val channel: Int, val prescaler: Int, val decimate: Int, val samples: Int, val level: Int, val edge: Int, val timeoutMs: Int = 150)

enum class FastScopeState { OFF, CHECKING, MISSING, RUNNING }

// Timestamped history for the instruments: analog readings per channel, and digital level changes per pin
class History(private val cap: Int = 4000) {
    private val analog = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()
    private val digital = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()

    @Synchronized fun addAnalog(ch: Int, v: Int, t: Long = System.currentTimeMillis()) {
        val q = analog.getOrPut(ch) { ArrayDeque() }; q.addLast(t to v); while (q.size > cap) q.removeFirst()
    }
    @Synchronized fun addDigital(pin: Int, v: Int, t: Long = System.currentTimeMillis()) {
        val q = digital.getOrPut(pin) { ArrayDeque() }
        if (q.lastOrNull()?.second == v) return
        q.addLast(t to v); while (q.size > cap) q.removeFirst()
    }
    @Synchronized fun analog(ch: Int): List<Pair<Long, Int>> = analog[ch]?.toList() ?: emptyList()
    @Synchronized fun digital(pin: Int): List<Pair<Long, Int>> = digital[pin]?.toList() ?: emptyList()
    @Synchronized fun clear() { analog.clear(); digital.clear() }
}

class Bench(private val context: Context, private val scope: CoroutineScope) {
    val history = History()
    private val usb = context.getSystemService(UsbManager::class.java)

    private val _link = MutableStateFlow<Conn>(Conn.NoBoard)
    val link: StateFlow<Conn> = _link
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines
    private val _plotVersion = MutableStateFlow(0L)
    val plotVersion: StateFlow<Long> = _plotVersion
    private val _upload = MutableStateFlow<UploadState>(UploadState.Idle)
    val upload: StateFlow<UploadState> = _upload
    @Volatile private var stk: Stk500v2? = null

    // ---------- fast scope (PinscopeScope sketch, 1,000,000 baud) ----------
    private val _scope = MutableStateFlow<ScopeFrame?>(null)
    val scopeFrame: StateFlow<ScopeFrame?> = _scope
    private val _scopeState = MutableStateFlow(FastScopeState.OFF)
    val scopeState: StateFlow<FastScopeState> = _scopeState
    @Volatile private var scopeRx: ((ByteArray) -> Unit)? = null
    @Volatile var scopeRequest = ScopeRequest(0, 4, 1, 1000, 128, 1)
    // signal generator on the scope sketch: commands go out between captures
    private val scopeCmds = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val _gen = MutableStateFlow(GenSettings())
    val gen: StateFlow<GenSettings> = _gen
    fun setGen(g: GenSettings) {
        val old = _gen.value; _gen.value = g
        val new = genCommands(g); val prev = genCommands(old)
        new.forEachIndexed { i, c -> if (c != prev[i]) scopeCmds.offer(c) }
    }
    private fun genCommands(g: GenSettings) = listOf(
        "Q${(g.squareHz * 10).toLong()}",
        "P${g.pwmDuty.coerceIn(0, 255)}",
        "W${g.waveShape},${(g.waveHz * 10).toLong()}",
        "U${if (g.pullUp) 1 else 0}",
    )
    private var scopeJob: Job? = null
    private val _firmata = MutableStateFlow(FirmataState())
    val firmata: StateFlow<FirmataState> = _firmata

    val plot = PlotBuffer()
    var mode = MutableStateFlow(BenchMode.SERIAL)
    var baud = MutableStateFlow(9600)
    val paused = MutableStateFlow(false)

    private var port: UsbSerialPort? = null
    private val log = ArrayDeque<LogLine>()
    private var nextId = 0L
    private val partial = ByteArrayOutputStream()
    @Volatile private var dirtyLines = false
    @Volatile private var dirtyPlot = false
    @Volatile private var dirtyPins = false
    private val pins = HashMap<Int, FirmataPin>()
    private var firmataJob: Job? = null
    private var pendingConnect = false

    private val parser = FirmataParser(object : FirmataParser.Listener {
        override fun onVersion(major: Int, minor: Int) {}
        override fun onFirmware(name: String, major: Int, minor: Int) {
            _firmata.value = _firmata.value.copy(firmware = "$name $major.$minor", waiting = false, noReply = false)
            info("Firmata: $name $major.$minor")
        }
        override fun onDigitalPort(port: Int, mask: Int) = synchronized(pins) {
            for (bit in 0 until 8) {
                val n = port * 8 + bit
                val p = pins[n] ?: continue
                if (p.mode == PinMode.INPUT || p.mode == PinMode.PULLUP) { pins[n] = p.copy(value = mask shr bit and 1); history.addDigital(n, mask shr bit and 1) }
            }
            dirtyPins = true
        }
        override fun onAnalog(channel: Int, value: Int) {
            synchronized(pins) {
                val e = pins.values.firstOrNull { it.analogChannel == channel } ?: return
                pins[e.number] = e.copy(value = value)
                dirtyPins = true
                history.addAnalog(channel, value)
            }
            if (!paused.value) { plot.add("A$channel", value * 5f / 1023f); dirtyPlot = true }
        }
        override fun onCapabilities(modes: List<Set<PinMode>>) = synchronized(pins) {
            modes.forEachIndexed { i, m -> pins[i] = (pins[i] ?: FirmataPin(i)).copy(modes = m) }
            dirtyPins = true
        }
        override fun onAnalogMapping(channels: List<Int?>) = synchronized(pins) {
            channels.forEachIndexed { i, ch -> pins[i] = (pins[i] ?: FirmataPin(i)).copy(analogChannel = ch) }
            dirtyPins = true
        }
        override fun onPinState(pin: Int, mode: Int, state: Int) = synchronized(pins) {
            pins[pin]?.let { pins[pin] = it.copy(mode = PinMode.of(mode), value = state) }
            dirtyPins = true
        }
    })

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && pendingConnect) connect()
                    else if (!granted) { pendingConnect = false; _link.value = Conn.Failed("USB access was not allowed") }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (d != null && d == port?.device) disconnect("The board was unplugged")
                    else refresh()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refresh()
            }
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        // Publish batched updates at most 20 times a second
        scope.launch {
            while (isActive) {
                delay(50)
                if (dirtyLines) { dirtyLines = false; _lines.value = synchronized(log) { log.toList() } }
                if (dirtyPlot) { dirtyPlot = false; _plotVersion.value++ }
                if (dirtyPins) { dirtyPins = false; _firmata.value = _firmata.value.copy(pins = synchronized(pins) { HashMap(pins) }) }
            }
        }
    }

    fun refresh() {
        if (port != null) return
        val d = UsbIds.find(usb)
        val cur = _link.value
        if (cur is Conn.Asking || cur is Conn.Connecting) return
        _link.value = if (d != null) Conn.Found(UsbIds.describe(d)) else if (cur is Conn.Failed) cur else Conn.NoBoard
    }

    fun connect() {
        if (port != null) return
        val d = UsbIds.find(usb) ?: run { _link.value = Conn.Failed("No Arduino board found. Connect it with a USB-C OTG cable or adapter."); return }
        if (!usb.hasPermission(d)) {
            pendingConnect = true
            _link.value = Conn.Asking
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(d, pi)
            return
        }
        pendingConnect = false
        val name = UsbIds.describe(d)
        val m = mode.value
        val rate = if (m == BenchMode.FIRMATA) 57600 else baud.value
        _link.value = Conn.Connecting(name)
        scope.launch(Dispatchers.IO) {
            try {
                val p = UsbSerialPort(usb, d, ::onBytes) { msg -> scope.launch { disconnect(msg) } }
                p.open(rate)
                port = p
                partial.reset()
                _link.value = Conn.Connected(name, p.kind, m, rate)
                info("Connected to $name at $rate baud")
                if (m == BenchMode.FIRMATA) startFirmata()
            } catch (e: Exception) {
                port = null
                _link.value = Conn.Failed(e.message ?: "Could not connect")
            }
        }
    }

    private fun startFirmata() {
        synchronized(pins) { pins.clear() }
        _firmata.value = FirmataState(waiting = true)
        firmataJob?.cancel()
        firmataJob = scope.launch(Dispatchers.IO) {
            delay(2000) // the board resets on connect; the bootloader runs for about 1.5 s
            repeat(3) { attempt ->
                send(FirmataOut.firmwareQuery())
                send(FirmataOut.capabilityQuery())
                send(FirmataOut.analogMappingQuery())
                send(FirmataOut.samplingInterval(50))
                delay(1500)
                if (_firmata.value.firmware != null && synchronized(pins) { pins.isNotEmpty() }) return@launch
                if (attempt == 1) runCatching { port?.resetBoard() }.also { delay(2000) }
            }
            if (_firmata.value.firmware == null) {
                _firmata.value = _firmata.value.copy(waiting = false, noReply = true)
                info("No Firmata reply. Upload StandardFirmata to the board, then reconnect.")
            }
        }
    }

    fun disconnect(reason: String? = null) {
        if (reason == null) stopFastScope()
        firmataJob?.cancel()
        val p = port
        port = null
        if (p != null) scope.launch(Dispatchers.IO) { p.close() }
        _firmata.value = FirmataState()
        synchronized(pins) { pins.clear() }
        if (reason != null) info(reason)
        _link.value = if (reason != null && p != null) Conn.Failed(reason) else Conn.NoBoard
        refresh()
    }

    fun switchMode(m: BenchMode) {
        if (mode.value == m) return
        val wasConnected = port != null
        if (wasConnected) disconnect()
        mode.value = m
        if (wasConnected) connect()
    }

    fun setBaud(b: Int) {
        baud.value = b
        port?.let { p -> if (mode.value == BenchMode.SERIAL) scope.launch(Dispatchers.IO) { runCatching { p.setBaud(b) } } }
        val l = _link.value
        if (l is Conn.Connected && l.mode == BenchMode.SERIAL) _link.value = l.copy(baud = b)
    }

    fun resetBoard() {
        val p = port ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { p.resetBoard() }
            info("Board reset")
            if (mode.value == BenchMode.FIRMATA) startFirmata()
        }
    }

    // ---------- serial text ----------
    fun sendText(text: String, ending: String) {
        val p = port ?: return
        scope.launch(Dispatchers.IO) {
            try {
                p.write((text + ending).toByteArray())
                add(text, LineKind.OUT)
            } catch (e: Exception) { info(e.message ?: "Send failed") }
        }
    }

    fun clearLog() { synchronized(log) { log.clear() }; dirtyLines = true }
    fun clearPlot() { plot.clear(); dirtyPlot = true }

    private fun onBytes(bytes: ByteArray) {
        stk?.let { it.feed(bytes); return }
        scopeRx?.let { it(bytes); return }
        if (mode.value == BenchMode.FIRMATA) { parser.feed(bytes); return }
        for (b in bytes) {
            if (b == '\n'.code.toByte()) {
                val line = partial.toString(Charsets.UTF_8).trimEnd('\r')
                partial.reset()
                add(line, LineKind.IN)
                if (!paused.value) plotLine(line)
            } else if (partial.size() < 4096) partial.write(b.toInt())
        }
    }

    // Arduino Serial Plotter format: numbers split by spaces, commas or tabs, optionally "label:value"
    private fun plotLine(line: String) { if (plot.addLine(line)) dirtyPlot = true }

    private fun add(text: String, kind: LineKind) {
        synchronized(log) {
            log.addLast(LogLine(nextId++, System.currentTimeMillis(), text, kind))
            while (log.size > 2000) log.removeFirst()
        }
        dirtyLines = true
    }

    fun info(text: String) = add(text, LineKind.INFO)

    private fun send(bytes: ByteArray) {
        try { port?.write(bytes) } catch (e: Exception) { info(e.message ?: "USB write failed") }
    }

    // ---------- Firmata pin control ----------
    fun setPinMode(n: Int, m: PinMode) {
        if (port == null) return
        val old = synchronized(pins) { pins[n] } ?: return
        scope.launch(Dispatchers.IO) {
            if (old.mode == PinMode.ANALOG && m != PinMode.ANALOG) old.analogChannel?.let { send(FirmataOut.reportAnalog(it, false)) }
            send(FirmataOut.setMode(n, m))
            when (m) {
                PinMode.INPUT, PinMode.PULLUP -> send(FirmataOut.reportDigital(n / 8, true))
                PinMode.ANALOG -> old.analogChannel?.let { send(FirmataOut.reportAnalog(it, true)) }
                PinMode.SERVO -> send(FirmataOut.analogWrite(n, 90))
                else -> {}
            }
            synchronized(pins) { pins[n] = old.copy(mode = m, value = if (m == PinMode.SERVO) 90 else 0) }
            dirtyPins = true
        }
    }

    // How often the board reports analog pins, in milliseconds
    fun setSampling(ms: Int) { if (port != null) scope.launch(Dispatchers.IO) { send(FirmataOut.samplingInterval(ms.coerceIn(1, 1000))) } }

    // Puts StandardFirmata on the board (bundled), then connects in Firmata mode
    fun installFirmata() {
        val image = runCatching { IntelHex.parse(context.assets.open("sketches/StandardFirmata.hex").bufferedReader().use { it.readText() }) }.getOrNull() ?: return
        flash(image, "StandardFirmata", BenchMode.FIRMATA, null)
    }

    fun servoRange(pin: Int, minUs: Int, maxUs: Int) {
        if (port == null) return
        scope.launch(Dispatchers.IO) { send(FirmataOut.servoConfig(pin, minUs, maxUs)) }
    }

    fun digitalWrite(n: Int, high: Boolean) = update(n, if (high) 1 else 0) { send(FirmataOut.digitalWrite(n, high)) }
    fun analogWrite(n: Int, value: Int) = update(n, value) { send(FirmataOut.analogWrite(n, value)) }

    private fun update(n: Int, value: Int, io: () -> Unit) {
        if (port == null) return
        synchronized(pins) { pins[n]?.let { pins[n] = it.copy(value = value) } }
        dirtyPins = true
        scope.launch(Dispatchers.IO) { io() }
    }

    // Starts the fast scope: reconnects at 1,000,000 baud, checks for the sketch, then captures over and over
    fun startFastScope() {
        if (scopeJob?.isActive == true) return
        scopeJob = scope.launch(Dispatchers.IO) {
            _scopeState.value = FastScopeState.CHECKING
            val d = UsbIds.find(usb) ?: run { _scopeState.value = FastScopeState.OFF; return@launch }
            if (!usb.hasPermission(d)) { _scopeState.value = FastScopeState.OFF; kotlinx.coroutines.withContext(Dispatchers.Main) { connect() }; return@launch }
            firmataJob?.cancel()
            port?.let { old -> port = null; old.close() }
            _firmata.value = FirmataState()
            val inbox = java.util.concurrent.LinkedBlockingQueue<Byte>()
            scopeRx = { b -> for (x in b) inbox.offer(x) }
            val p = UsbSerialPort(usb, d, ::onBytes) { }
            try {
                p.open(1_000_000)
                port = p
                mode.value = BenchMode.SERIAL; baud.value = 1_000_000
                _link.value = Conn.Connected(UsbIds.describe(d), p.kind, BenchMode.SERIAL, 1_000_000)
                delay(1800) // the board resets on connect
                // is the sketch there?
                var ok = false
                repeat(3) {
                    if (ok) return@repeat
                    inbox.clear(); p.write("?\n".toByteArray())
                    val sb = StringBuilder(); val until = System.currentTimeMillis() + 600
                    while (System.currentTimeMillis() < until) {
                        val b = inbox.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        sb.append((b.toInt() and 0xFF).toChar())
                        if (sb.contains("PINSCOPE-SCOPE 3")) { ok = true; break }
                    }
                }
                if (!ok) { _scopeState.value = FastScopeState.MISSING; return@launch }
                _scopeState.value = FastScopeState.RUNNING
                // the board reset on connect, so the generator settings go out again
                genCommands(_gen.value).forEach { scopeCmds.offer(it) }
                while (isActive) {
                    while (true) { val c = scopeCmds.poll() ?: break; p.write("$c\n".toByteArray()) }
                    val q = scopeRequest
                    inbox.clear()
                    p.write("S${q.channel},${q.prescaler},${q.decimate},${q.samples},${q.level},${q.edge},${q.timeoutMs}\n".toByteArray())
                    readFrame(inbox, q)?.let { _scope.value = it }
                }
            } catch (e: Exception) {
                info(e.message ?: "Fast scope stopped")
            } finally {
                scopeRx = null
                if (_scopeState.value != FastScopeState.MISSING) _scopeState.value = FastScopeState.OFF
            }
        }
    }

    fun stopFastScope() {
        scopeJob?.cancel(); scopeJob = null
        scopeRx = null
        if (_scopeState.value == FastScopeState.RUNNING || _scopeState.value == FastScopeState.CHECKING) _scopeState.value = FastScopeState.OFF
    }

    fun installFastScope() {
        stopFastScope()
        val image = runCatching { IntelHex.parse(context.assets.open("sketches/PinscopeScope.hex").bufferedReader().use { it.readText() }) }.getOrNull() ?: return
        _scopeState.value = FastScopeState.OFF
        flash(image, "Pinscope fast scope", null, null)
    }

    private fun readFrame(inbox: java.util.concurrent.LinkedBlockingQueue<Byte>, q: ScopeRequest): ScopeFrame? {
        val deadline = System.currentTimeMillis() + q.timeoutMs + 400 + q.samples.toLong() * q.decimate / 4
        fun next(): Int { val w = deadline - System.currentTimeMillis(); return (if (w > 0) inbox.poll(w, java.util.concurrent.TimeUnit.MILLISECONDS) else null)?.toInt()?.and(0xFF) ?: throw java.io.IOException("timeout") }
        return try {
            var prev = -1
            while (true) { val b = next(); if (prev == 0xA5 && b == 0x5A) break; prev = b }
            val n = next() or (next() shl 8)
            val flags = next()
            var us = 0L; for (k in 0 until 4) us = us or (next().toLong() shl (8 * k))
            if (n <= 0 || n > 6000) return null
            val data = ByteArray(n) { next().toByte() }
            ScopeFrame(data, if (us > 0) n * 1e6 / us else 0.0, flags and 1 != 0)
        } catch (_: java.io.IOException) { null }
    }

    // ---------- uploading sketches ----------
    // Sketches the app ships (StandardFirmata, the scope, the quick-start examples) can be put back any time, so they are not backed up
    private fun isBundled(image: ByteArray): Boolean = runCatching {
        context.assets.list("sketches")!!.filter { it.endsWith(".hex") }.any { f ->
            val b = IntelHex.parse(context.assets.open("sketches/$f").bufferedReader().use { it.readText() })
            var end = b.size; while (end > 0 && b[end - 1] == 0xFF.toByte()) end--
            b.copyOf(end).contentEquals(image)
        }
    }.getOrDefault(false)

    fun backups(): List<java.io.File> = java.io.File(context.filesDir, "backups").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clearUpload() { if (_upload.value !is UploadState.Running) _upload.value = UploadState.Idle }

    // Flashes an image onto the Mega, then reconnects in the mode the sketch needs
    fun flash(image: ByteArray, name: String, afterMode: BenchMode?, afterBaud: Int?) {
        if (_upload.value is UploadState.Running) return
        val d = UsbIds.find(usb) ?: run { _upload.value = UploadState.Failed(name, "No Arduino board found. Connect it with a USB-C OTG cable or adapter."); return }
        if (!usb.hasPermission(d)) {
            _upload.value = UploadState.Failed(name, "Allow USB access first: tap Connect once, then upload.")
            connect()
            return
        }
        _upload.value = UploadState.Running(name, 0f, "Resetting the board")
        scope.launch(Dispatchers.IO) {
            firmataJob?.cancel()
            port?.let { old -> port = null; old.close() }
            _firmata.value = FirmataState()
            val t0 = System.currentTimeMillis()
            var p: UsbSerialPort? = null
            try {
                val loader = Stk500v2 { bytes -> p?.write(bytes) }
                stk = loader
                p = UsbSerialPort(usb, d, ::onBytes) { }
                p.open(115200)
                p.resetBoard()
                _link.value = Conn.Connecting(UsbIds.describe(d))
                loader.signOn()
                val sig = loader.signature()
                if (sig != Stk500v2.MEGA2560_SIGNATURE) throw java.io.IOException("This is not an ATmega2560 (signature %06X)".format(sig))
                loader.enterProgramming()
                // back up whatever is on the board first (sketches sit at the start of flash)
                _upload.value = UploadState.Running(name, 0.02f, "Backing up the sketch on the board")
                val old = java.io.ByteArrayOutputStream()
                var empty = 0
                var addr = 0
                while (addr < 0x3E000 && empty < 4) {
                    val pg = loader.readPage(addr, Stk500v2.PAGE)
                    old.write(pg)
                    empty = if (pg.all { it == 0xFF.toByte() }) empty + 1 else 0
                    addr += Stk500v2.PAGE
                    _upload.value = UploadState.Running(name, 0.02f, "Backing up the sketch on the board (${addr / 1024} KB)")
                }
                var oldImage = old.toByteArray()
                var end = oldImage.size
                while (end > 0 && oldImage[end - 1] == 0xFF.toByte()) end--
                oldImage = oldImage.copyOf(end)
                var backupName: String? = null
                if (oldImage.isNotEmpty() && !oldImage.contentEquals(image) && !isBundled(oldImage)) {
                    val dir = java.io.File(context.filesDir, "backups").apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
                    val f = java.io.File(dir, "Board backup $stamp.hex")
                    f.writeText(IntelHex.write(oldImage))
                    backupName = f.name
                    info("Saved the previous sketch as ${f.name}")
                }
                val pages = (image.size + Stk500v2.PAGE - 1) / Stk500v2.PAGE
                for (i in 0 until pages) {
                    val page = ByteArray(Stk500v2.PAGE) { 0xFF.toByte() }
                    System.arraycopy(image, i * Stk500v2.PAGE, page, 0, minOf(Stk500v2.PAGE, image.size - i * Stk500v2.PAGE))
                    loader.writePage(i * Stk500v2.PAGE, page)
                    _upload.value = UploadState.Running(name, (i + 1f) / pages * 0.7f, "Writing ${(i + 1) * Stk500v2.PAGE / 1024} of ${pages * Stk500v2.PAGE / 1024} KB")
                }
                for (i in 0 until pages) {
                    val n = minOf(Stk500v2.PAGE, image.size - i * Stk500v2.PAGE)
                    val back = loader.readPage(i * Stk500v2.PAGE, Stk500v2.PAGE)
                    for (k in 0 until n) if (back[k] != image[i * Stk500v2.PAGE + k])
                        throw java.io.IOException("Verify failed at 0x%05X".format(i * Stk500v2.PAGE + k))
                    _upload.value = UploadState.Running(name, 0.7f + (i + 1f) / pages * 0.3f, "Checking")
                }
                loader.leaveProgramming()
                _upload.value = UploadState.Done(name, (System.currentTimeMillis() - t0) / 1000f, image.size, backupName)
                info("Uploaded $name (${image.size / 1024} KB)")
            } catch (e: Exception) {
                _upload.value = UploadState.Failed(name, e.message ?: "Upload failed")
            } finally {
                stk = null
                p?.close()
                _link.value = Conn.NoBoard
            }
            // start the sketch's own connection
            kotlinx.coroutines.delay(400)
            if (_upload.value is UploadState.Done) {
                afterMode?.let { mode.value = it }
                afterBaud?.let { baud.value = it }
                if (afterMode != null) kotlinx.coroutines.withContext(Dispatchers.Main) { connect() } else refresh()
            } else refresh()
        }
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
        port?.close()
        port = null
    }

    companion object {
        const val ACTION_PERMISSION = "io.github.eagle10021.pinscope.USB_PERMISSION"
        val BAUDS = listOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 74880, 115200, 230400, 250000, 500000, 1000000)
    }
}
