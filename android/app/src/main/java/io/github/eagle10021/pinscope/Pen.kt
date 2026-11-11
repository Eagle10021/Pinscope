package io.github.eagle10021.pinscope

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// Pen support: hover tooltips, the wiring sketchpad and photo annotations.

// True while the pen's side button is held (Android reports it as the stylus primary or secondary button)
fun androidx.compose.ui.input.pointer.PointerEvent.penButton(): Boolean =
    changes.any { it.type == PointerType.Stylus || it.type == PointerType.Eraser } && (buttons.isPrimaryPressed || buttons.isSecondaryPressed) ||
        changes.any { it.type == PointerType.Eraser }

// Reports where a stylus hovers (without touching), or null when it leaves. Touches pass through untouched.
fun Modifier.penHover(onHover: (Offset?) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull() ?: continue
            if (c.type != PointerType.Stylus) continue
            when {
                e.type == PointerEventType.Exit || c.pressed -> onHover(null)
                e.type == PointerEventType.Enter || e.type == PointerEventType.Move -> onHover(c.position)
            }
        }
    }
}

// A small card beside the pen tip with the pin's name and functions
@Composable
fun PinHoverCard(pin: Pin, at: Offset, area: IntSize) {
    val d = LocalDensity.current
    val w = with(d) { 220.dp.roundToPx() }
    val x = (at.x + with(d) { 18.dp.toPx() }).roundToInt().let { if (it + w > area.width) (at.x - w - with(d) { 18.dp.toPx() }).roundToInt() else it }
    val y = (at.y - with(d) { 70.dp.toPx() }).roundToInt().coerceAtLeast(0)
    Surface(
        Modifier.offset { IntOffset(x, y) }.widthIn(max = 220.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                PinBadge(pin.label.ifEmpty { pin.name })
                Text("  " + pin.title, style = MaterialTheme.typography.titleSmall)
            }
            if (pin.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pin.tags.forEach { TagPill(it) }
            }
            pin.lines.firstOrNull { it.first != "AVR port" }?.let { (k, v) ->
                Text("$k: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (pin.uses.isNotEmpty()) Text("Used by ${pin.uses.size} kit part" + if (pin.uses.size > 1) "s" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------- wiring sketchpad ----------

val WIRE_COLORS = listOf(Color(0xFFF87171), Color(0xFF60A5FA), Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFF22D3EE), Color(0xFFFB923C), Color(0xFFE5E7EB))

// A drawn wire: points in board mm; it starts on a pin and may end on another pin
data class Wire(val id: Long, val points: List<Offset>, val from: Int, val to: Int?, val label: String, val color: Int)

object WireStore {
    private const val KEY = "wires"
    fun load(context: Context): List<Wire> = runCatching {
        val a = JSONArray(context.getSharedPreferences("pinscope", 0).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val p = o.getJSONArray("p")
            Wire(o.getLong("id"), (0 until p.length() / 2).map { Offset(p.getDouble(2 * it).toFloat(), p.getDouble(2 * it + 1).toFloat()) },
                o.getInt("from"), if (o.isNull("to")) null else o.getInt("to"), o.optString("label"), o.getInt("color"))
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, wires: List<Wire>) {
        val a = JSONArray()
        wires.forEach { w ->
            a.put(JSONObject().apply {
                put("id", w.id); put("from", w.from); put("to", w.to ?: JSONObject.NULL); put("label", w.label); put("color", w.color)
                put("p", JSONArray().apply { w.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
            })
        }
        context.getSharedPreferences("pinscope", 0).edit().putString(KEY, a.toString()).apply()
    }

    // A starting sketch with one constant per labelled wire
    fun sketch(kit: KitData, wires: List<Wire>): String {
        val sb = StringBuilder("// Wiring drawn in Pinscope\n")
        val used = HashSet<String>()
        val consts = ArrayList<Pair<String, Pin>>()
        for (w in wires) {
            for (idx in listOfNotNull(w.from, w.to)) {
                val p = kit.pins.getOrNull(idx) ?: continue
                if (p.num == null) continue
                var name = w.label.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').ifEmpty { "WIRE_${w.id % 1000}" }
                if (name[0].isDigit()) name = "P_$name"
                var n = name; var k = 2
                while (!used.add(n)) n = "${name}_${k++}"
                consts += n to p
            }
        }
        for ((n, p) in consts) {
            val ref = if (p.isAnalog) p.ref else p.num.toString()
            val tags = p.tags.filter { it != "digital" }.joinToString(", ") { TAG_NAMES[it] ?: it }
            sb.append("const int $n = $ref;").append(if (tags.isNotEmpty()) "  // $tags" else "").append('\n')
        }
        sb.append("\nvoid setup() {\n  Serial.begin(9600);\n")
        // a first guess from the label; analog inputs need no pinMode
        for ((n, p) in consts) {
            val l = n.lowercase()
            val mode = when {
                p.isAnalog -> null
                Regex("button|btn|switch|sw|tilt|key").containsMatchIn(l) -> "INPUT_PULLUP"
                Regex("sensor|echo|pir|motion|ir|receiver|input|in\\b|data").containsMatchIn(l) -> "INPUT"
                else -> "OUTPUT"
            }
            if (mode != null) sb.append("  pinMode($n, $mode);\n") else sb.append("  // $n is an analog input: read it with analogRead($n)\n")
        }
        sb.append("}\n\nvoid loop() {\n}\n")
        return sb.toString()
    }
}

// Smooth line through the points, already in screen space
fun DrawScope.drawWire(points: List<Offset>, color: Color, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val m = (points[i - 1] + points[i]) / 2f
            quadraticTo(points[i - 1].x, points[i - 1].y, m.x, m.y)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(width * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(color, width * 1.3f, points.first())
    drawCircle(color, width * 1.3f, points.last())
}

// Thins a stroke so stored wires stay small
fun simplify(points: List<Offset>, minGap: Float): List<Offset> {
    if (points.size < 3) return points
    val out = arrayListOf(points.first())
    for (p in points.drop(1)) if ((p - out.last()).getDistance() >= minGap) out += p
    if (out.last() != points.last()) out += points.last()
    return out
}

// ---------- photo annotations ----------

data class InkStroke(val points: List<Offset>, val color: Int, val width: Float) // image pixels

val INK_COLORS = listOf(Color(0xFFFFC95C), Color(0xFFF87171), Color(0xFF22D3EE), Color.White)

object InkStore {
    private const val KEY = "ink"
    fun load(context: Context): List<InkStroke> = runCatching {
        val a = JSONArray(context.getSharedPreferences("pinscope", 0).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val p = o.getJSONArray("p")
            InkStroke((0 until p.length() / 2).map { Offset(p.getDouble(2 * it).toFloat(), p.getDouble(2 * it + 1).toFloat()) }, o.getInt("c"), o.getDouble("w").toFloat())
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, ink: List<InkStroke>) {
        val a = JSONArray()
        ink.forEach { s -> a.put(JSONObject().apply { put("c", s.color); put("w", s.width.toDouble()); put("p", JSONArray().apply { s.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }) }) }
        context.getSharedPreferences("pinscope", 0).edit().putString(KEY, a.toString()).apply()
    }

    fun clear(context: Context) = context.getSharedPreferences("pinscope", 0).edit().remove(KEY).apply()

    // Photo with the notes drawn on it, saved to Pictures/Pinscope
    fun export(context: Context, photo: Bitmap, ink: List<InkStroke>): Boolean = runCatching {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val c = AndroidCanvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (s in ink) {
            if (s.points.size < 2) continue
            paint.color = s.color; paint.strokeWidth = s.width
            val path = android.graphics.Path().apply { moveTo(s.points[0].x, s.points[0].y); s.points.drop(1).forEach { lineTo(it.x, it.y) } }
            c.drawPath(path, paint)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Pinscope ${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Pinscope")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        true
    }.getOrDefault(false)
}

fun Color.argb() = toArgb()
