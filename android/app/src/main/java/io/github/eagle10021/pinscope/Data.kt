package io.github.eagle10021.pinscope

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Board, pin and kit-part data from assets/pinscope.json (built by tools/build-data.mjs).

data class PinUse(val part: String, val set: String, val pin: String)

data class Pin(
    val index: Int,
    val ref: String,         // D13, A0, 5V, GND, ...
    val label: String,       // silkscreen label ("SCL", "13", "")
    val name: String,        // short display name ("13", "A0", "3.3V")
    val title: String,       // "Pin 13", "A0", "5V"
    val x: Float, val y: Float, // mm from the board's top-left corner
    val side: String,
    val header: String,
    val tags: List<String>,
    val lines: List<Pair<String, String>>,
    val desc: String,
    val num: Int?,           // Arduino pin number (A0 = 54), null for power pins
    val uses: List<PinUse>,
) {
    val isPower get() = num == null
    val isAnalog get() = ref.startsWith("A") && ref != "AREF"
}

data class Anchor(val pin: Int, val name: String, val hint: String)

// One physical header housing in board mm; the double 2×18 header runs vertically
data class HeaderBlock(val name: String, val l: Float, val t: Float, val r: Float, val b: Float, val vertical: Boolean, val pins: Int) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
}
data class PartPin(val num: String, val name: String, val desc: String)
data class WireRow(val pin: String, val mega: String, val note: String, val refs: List<String>)
data class WireSet(val title: String, val rows: List<WireRow>)
data class Link(val label: String, val url: String)
data class ResistorNote(val where: String, val value: String, val why: String)

data class Part(
    val id: String, val name: String, val part: String, val cat: String, val catKey: String,
    val lesson: Int?, val blurb: String, val layout: String,
    val pins: List<PartPin>, val wiring: List<WireSet>, val notes: List<String>,
    val links: List<Link>, val resistors: List<ResistorNote>, val noResistor: String?,
) {
    // Mega pins this part uses, across all wiring sets
    val refs: List<String> by lazy { wiring.flatMap { w -> w.rows.flatMap { it.refs } }.distinct() }
}

data class Board(
    val name: String, val rev: String, val width: Float, val height: Float,
    val usb: List<Float>, val jack: List<Float>,
    val facts: List<Pair<String, String>>, val howToTell: List<String>,
)

data class KitData(
    val board: Board,
    val categories: List<Pair<String, String>>, // name, key
    val pins: List<Pin>,
    val anchors: List<Anchor>,
    val parts: List<Part>,
    val sources: List<Link>,
    val site: String,
) {
    private val partById = parts.associateBy { it.id }
    fun part(id: String) = partById[id]
    fun pinsFor(ref: String) = pins.filter { it.ref == ref }
    fun pinByNumber(n: Int) = pins.firstOrNull { it.num == n }

    // Header housings: pins of the same header split wherever the gap is wider than one pitch
    val headerBlocks: List<HeaderBlock> by lazy {
        val out = ArrayList<HeaderBlock>()
        for ((name, g) in pins.groupBy { it.header }) {
            val vertical = g.map { it.x }.distinct().size <= 2 && g.size > 8
            val sorted = if (vertical) g.sortedBy { it.y } else g.sortedBy { it.x }
            var run = mutableListOf(sorted.first())
            fun flush() {
                out += HeaderBlock(name, run.minOf { it.x } - 1.27f, run.minOf { it.y } - 1.27f, run.maxOf { it.x } + 1.27f, run.maxOf { it.y } + 1.27f, vertical, run.size)
            }
            for (p in sorted.drop(1)) {
                val gap = if (vertical) p.y - run.last().y else p.x - run.last().x
                if (gap > 2.7f) { flush(); run = mutableListOf(p) } else run += p
            }
            flush()
        }
        out
    }

    companion object {
        @Volatile private var cached: KitData? = null

        fun load(context: Context): KitData = cached ?: synchronized(this) {
            cached ?: parse(JSONObject(context.assets.open("pinscope.json").bufferedReader().use { it.readText() })).also { cached = it }
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        private fun JSONArray.floats() = (0 until length()).map { getDouble(it).toFloat() }
        private fun JSONObject.link() = Link(getString("label"), getString("url"))

        fun parse(o: JSONObject): KitData {
            val b = o.getJSONObject("board")
            val board = Board(
                b.getString("name"), b.getString("rev"),
                b.getDouble("width").toFloat(), b.getDouble("height").toFloat(),
                b.getJSONArray("usb").floats(), b.getJSONArray("jack").floats(),
                b.getJSONArray("facts").objects().map { it.getString("k") to it.getString("v") },
                b.getJSONArray("howToTell").strings(),
            )
            val pins = o.getJSONArray("pins").objects().mapIndexed { i, p ->
                Pin(
                    index = i, ref = p.getString("ref"), label = p.getString("label"), name = p.getString("name"),
                    title = p.getString("title"),
                    x = p.getDouble("x").toFloat(), y = p.getDouble("y").toFloat(),
                    side = p.getString("side"), header = p.getString("header"),
                    tags = p.getJSONArray("tags").strings(),
                    lines = p.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { l -> l.getString(0) to l.getString(1) } } },
                    desc = p.getString("desc"),
                    num = if (p.isNull("num")) null else p.getInt("num"),
                    uses = p.getJSONArray("uses").objects().map { PinUse(it.getString("part"), it.getString("set"), it.getString("pin")) },
                )
            }
            val parts = o.getJSONArray("components").objects().map { c ->
                Part(
                    id = c.getString("id"), name = c.getString("name"), part = c.getString("part"),
                    cat = c.getString("cat"), catKey = c.getString("catKey"),
                    lesson = if (c.isNull("lesson")) null else c.getInt("lesson"),
                    blurb = c.getString("blurb"), layout = c.getString("layout"),
                    pins = c.getJSONArray("pins").objects().map { PartPin(it.getString("num"), it.getString("name"), it.getString("desc")) },
                    wiring = c.getJSONArray("wiring").objects().map { w ->
                        WireSet(w.getString("title"), w.getJSONArray("rows").objects().map {
                            WireRow(it.getString("pin"), it.getString("mega"), it.getString("note"), it.getJSONArray("refs").strings())
                        })
                    },
                    notes = c.getJSONArray("notes").strings(),
                    links = c.getJSONArray("links").objects().map { it.link() },
                    resistors = c.getJSONArray("resistors").objects().map { ResistorNote(it.getString("where"), it.getString("value"), it.getString("why")) },
                    noResistor = if (c.isNull("noResistor")) null else c.getString("noResistor"),
                )
            }
            return KitData(
                board = board,
                categories = o.getJSONArray("categories").objects().map { it.getString("name") to it.getString("key") },
                pins = pins,
                anchors = o.getJSONArray("anchors").objects().map { Anchor(it.getInt("pin"), it.getString("name"), it.getString("hint")) },
                parts = parts,
                sources = o.getJSONArray("sources").objects().map { it.link() },
                site = o.getString("site"),
            )
        }
    }
}

// Display names for pin function tags, in filter order
val TAG_NAMES = linkedMapOf(
    "pwm" to "PWM", "analog" to "Analog", "interrupt" to "Interrupt", "serial" to "Serial",
    "i2c" to "I2C", "spi" to "SPI", "power" to "Power", "digital" to "Digital",
)
