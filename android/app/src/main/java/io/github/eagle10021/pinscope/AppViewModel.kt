package io.github.eagle10021.pinscope

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Tab { SCAN, BOARD, PARTS, BENCH }
enum class ScanStage { EMPTY, CAMERA, ALIGN, EXPLORE }

// What the board views highlight
sealed interface Filter {
    data object All : Filter
    data object Kit : Filter
    data class Tag(val tag: String) : Filter
    data class Part(val id: String) : Filter
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val kit = KitData.load(app)
    val bench = Bench(app, viewModelScope)
    val opencv = runCatching { org.opencv.android.OpenCVLoader.initLocal() }.getOrDefault(false)
    val detector = BoardDetector(kit).apply {
        debug = app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        dumpDir = app.filesDir
    }
    private val prefs = app.getSharedPreferences("pinscope", 0)
    private val filesDir = app.filesDir
    // The user's own board, learned from an aligned photo
    @Volatile private var learnedMemory: BoardMemory? = null
    // Shipped ELEGOO Mega 2560 memory, used until the user teaches their own board
    private val preset: BoardMemory? by lazy { if (opencv) BoardMemory.preset(app, "elegoo-mega2560")?.also { it.debug = detector.debug } else null }
    val memory: BoardMemory? get() = learnedMemory ?: preset
    val usingPreset get() = learnedMemory == null && preset != null
    var learned by mutableStateOf(false)
    var learning by mutableStateOf(false)
    private val photoFile = File(app.filesDir, "board.jpg")

    var tab by mutableStateOf(Tab.SCAN)
    var filter by mutableStateOf<Filter>(Filter.All)
    var selectedPin by mutableStateOf<Int?>(null)
    var openPart by mutableStateOf<String?>(null)
    var editingSketch by mutableStateOf<java.io.File?>(null)
    var instrument by mutableStateOf<Instrument?>(null)

    // ---------- pen: wiring sketchpad and photo notes ----------
    private val appCtx = app
    var wires by mutableStateOf(WireStore.load(app))
        private set
    var fingerDraws by mutableStateOf(false) // phones without a pen: a finger draws instead of panning
    var editingWire by mutableStateOf<Long?>(null)
    var ink by mutableStateOf(InkStore.load(app))
        private set

    fun addWire(w: Wire) { wires = wires + w; WireStore.save(appCtx, wires); editingWire = w.id }
    fun updateWire(w: Wire) { wires = wires.map { if (it.id == w.id) w else it }; WireStore.save(appCtx, wires) }
    fun removeWire(id: Long) { wires = wires.filter { it.id != id }; WireStore.save(appCtx, wires); if (editingWire == id) editingWire = null }
    fun clearWires() { wires = emptyList(); WireStore.save(appCtx, wires) }
    fun wiresToSketch() { editingSketch = SketchStore.create(appCtx, "wiring", WireStore.sketch(kit, wires)); tab = Tab.BENCH }

    // ---------- simulator ----------
    var sim by mutableStateOf<Simulator?>(null)
        private set

    // Parts come from the sketch itself when it says what it uses; otherwise the last set-up, then the drawn wires
    fun simulate(image: ByteArray, name: String, source: String? = null) {
        sim?.stop()
        val s = Simulator(kit, image, name)
        val hints = source?.let { runCatching { SketchParts.hints(it) }.getOrNull() }.orEmpty()
        hints.forEach { h -> s.addPart(createPart(h.type, h.pins).also { SketchParts.configure(it, h.opts) }) }
        val inferred = if (hints.isNotEmpty()) emptyList() else source?.let { runCatching { SketchParts.infer(it) }.getOrNull() }.orEmpty()
        val saved = if (hints.isNotEmpty()) hints.map { it.type to it.pins } else inferred.ifEmpty { loadSimParts() }
        if (hints.isEmpty()) saved.forEach { (t, pins) -> s.addPart(createPart(t, pins).also { p -> if (source != null && inferred.isNotEmpty()) SketchParts.configureFromSource(p, source) }) }
        // no parts yet: start from the wires drawn on the Board tab, guessing the part from each label
        if (saved.isEmpty()) for (w in wires) {
            val pin = kit.pins.getOrNull(w.from)?.num ?: continue
            val l = w.label.lowercase()
            val t = when {
                Regex("led|light|lamp").containsMatchIn(l) -> PartType.LED
                Regex("button|btn|switch|key").containsMatchIn(l) -> PartType.BUTTON
                Regex("buzz|beep|speaker|piezo").containsMatchIn(l) -> PartType.BUZZER
                Regex("servo").containsMatchIn(l) -> PartType.SERVO
                Regex("pot|knob|dial").containsMatchIn(l) && pin >= 54 -> PartType.POT
                Regex("ldr|photo").containsMatchIn(l) && pin >= 54 -> PartType.PHOTO
                Regex("pir|motion").containsMatchIn(l) -> PartType.PIR
                Regex("dht|humid|temp").containsMatchIn(l) && pin < 54 -> PartType.DHT11
                Regex("tilt").containsMatchIn(l) -> PartType.TILT
                Regex("relay").containsMatchIn(l) -> PartType.RELAY
                else -> null
            } ?: continue
            s.addPart(createPart(t, intArrayOf(pin)))
        }
        s.start()
        sim = s
        tab = Tab.BENCH
    }
    fun saveSimPartsLater(s: Simulator) { viewModelScope.launch { kotlinx.coroutines.delay(300); if (sim === s) saveSimParts() } }
    fun closeSim() { sim?.stop(); sim = null }

    // the parts set-up is remembered between runs
    fun saveSimParts() {
        val s = sim ?: return
        val a = org.json.JSONArray()
        s.parts.forEach { p -> a.put(org.json.JSONObject().put("t", p.type.name).put("p", org.json.JSONArray(p.pins.toList()))) }
        prefs.edit().putString("simParts", a.toString()).apply()
    }
    private fun loadSimParts(): List<Pair<PartType, IntArray>> = runCatching {
        val a = org.json.JSONArray(prefs.getString("simParts", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val t = runCatching { PartType.valueOf(o.getString("t")) }.getOrNull() ?: return@mapNotNull null
            val p = o.getJSONArray("p"); t to IntArray(p.length()) { p.getInt(it) }
        }
    }.getOrDefault(emptyList())

    fun addInk(s: InkStroke) { ink = ink + s; InkStore.save(appCtx, ink) }
    fun undoInk() { ink = ink.dropLast(1); InkStore.save(appCtx, ink) }
    fun clearInk() { ink = emptyList(); InkStore.clear(appCtx) }

    var stage by mutableStateOf(ScanStage.EMPTY)
    var photo by mutableStateOf<Bitmap?>(null)
        private set
    var corners by mutableStateOf<List<Offset>>(emptyList())
    var showLabels by mutableStateOf(true)
    var showLive by mutableStateOf(true)

    init {
        if (opencv) viewModelScope.launch(Dispatchers.IO) {
            BoardMemory.load(filesDir)?.let { m -> m.debug = detector.debug; learnedMemory = m; withContext(Dispatchers.Main) { learned = true } }
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                if (!photoFile.exists()) null else runCatching { BitmapFactory.decodeFile(photoFile.path) }.getOrNull()
            }
            val pts = prefs.getString("corners", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            if (saved != null && pts != null && pts.size == 8) {
                photo = saved
                corners = pts.chunked(2).map { Offset(it[0], it[1]) }
                stage = ScanStage.EXPLORE
            }
        }
    }

    // A new photo: explore straight away when the board was found, otherwise align by hand from a guess
    fun usePhoto(bmp: Bitmap, guess: List<Offset>, found: Boolean = false) {
        photo = bmp
        clearInk()
        corners = guess
        selectedPin = null
        if (found) finishAlign(learn = false) else stage = ScanStage.ALIGN
        viewModelScope.launch(Dispatchers.IO) {
            photoFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }

    fun finishAlign(learn: Boolean = true) {
        stage = ScanStage.EXPLORE
        prefs.edit().putString("corners", corners.joinToString(",") { "${it.x},${it.y}" }).apply()
        if (learn) learnBoard()
    }

    // Teaches live tracking what this board looks like, from the current photo and alignment
    fun learnBoard(append: Boolean = false) {
        val bmp = photo ?: return
        val h = homography() ?: return
        if (!opencv) return
        learning = true
        viewModelScope.launch(Dispatchers.Default) {
            val m = runCatching { BoardMemory.learn(bmp, h, kit) }.getOrNull()
            val result = if (m != null && append) learnedMemory?.merged(m) ?: m else m
            if (result != null) { result.debug = detector.debug; result.save(filesDir); learnedMemory = result }
            withContext(Dispatchers.Main) { learning = false; if (m != null) learned = true }
        }
    }

    fun forgetBoard() {
        learnedMemory = null; learned = false
        BoardMemory.forget(filesDir)
    }

    fun forgetPhoto() {
        photo = null
        corners = emptyList()
        stage = ScanStage.EMPTY
        selectedPin = null
        photoFile.delete()
        prefs.edit().remove("corners").apply()
    }

    // Anchor pin positions from a detection, in the detection's image pixels
    fun anchorsFrom(d: Detection): List<Offset> = kit.anchors.map { a -> kit.pins[a.pin].let { d.map(it.x, it.y) } }

    // Finds the board in a still photo. With a learned board its own features are matched (trusted);
    // otherwise the generic detector runs (shown for confirmation before use).
    fun detectIn(bmp: Bitmap): Pair<Detection, Boolean>? {
        if (!opencv) return null
        return runCatching {
            val mem = memory
            if (mem != null) {
                val mat = org.opencv.core.Mat()
                org.opencv.android.Utils.bitmapToMat(bmp, mat)
                try {
                    mem.track(mat, kit)?.let { t ->
                        val polished = detector.detect(mat, track = false, seed = t.first)
                        val trusted = !usingPreset || t.second >= 60
                        return@runCatching (polished ?: Detection(t.first, 1f, true)) to trusted
                    }
                } finally { mat.release() }
            }
            detector.detect(bmp)?.let { it to false }
        }.getOrNull()
    }

    fun homography(): Homography? {
        if (corners.size != 4) return null
        val src = kit.anchors.map { kit.pins[it.pin].let { p -> Offset(p.x, p.y) } }
        return Homography.solve(src, corners)
    }

    fun showPartOnBoard(id: String) {
        filter = Filter.Part(id)
        selectedPin = null
        openPart = null
        tab = if (photo != null) Tab.SCAN else Tab.BOARD
    }

    override fun onCleared() { bench.close() }
}

// Whether a pin stands out under the current filter, and in which colour
fun pinHighlight(pin: Pin, filter: Filter, kit: KitData, colors: PinColors): Color? = when (filter) {
    Filter.All -> null
    Filter.Kit -> pin.uses.firstOrNull()?.takeIf { !pin.isPower }?.let { kit.part(it.part) }?.let { colors.forCategory(it.catKey) }
    is Filter.Tag -> if (filter.tag in pin.tags) colors.forTag(filter.tag) else null
    is Filter.Part -> kit.part(filter.id)?.takeIf { pin.ref in it.refs }?.let { colors.forCategory(it.catKey) }
}

fun filterActive(filter: Filter) = filter != Filter.All
