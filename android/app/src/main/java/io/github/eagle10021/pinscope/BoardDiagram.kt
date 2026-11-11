package io.github.eagle10021.pinscope

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.min

// How one pin marker looks, shared by the diagram and the photo overlay
data class MarkerStyle(
    val highlight: Color?,   // filter colour, or null
    val dimmed: Boolean,     // a filter is on and this pin is not part of it
    val selected: Boolean,
    val live: FirmataPin?,
)

fun markerStyle(pin: Pin, vm: AppViewModel, colors: PinColors, live: Map<Int, FirmataPin>): MarkerStyle {
    val h = pinHighlight(pin, vm.filter, vm.kit, colors)
    return MarkerStyle(
        highlight = h,
        dimmed = filterActive(vm.filter) && h == null,
        selected = vm.selectedPin == pin.index,
        live = pin.num?.let { live[it] }?.takeIf { it.mode != null },
    )
}

// Draws a pad (square, gold), its live state and selection ring. r = half the pad size in px.
fun DrawScope.drawPinMarker(center: Offset, r: Float, s: MarkerStyle, colors: PinColors, accent: Color, onPhoto: Boolean) {
    val alpha = if (s.dimmed) 0.28f else 1f
    val live = s.live
    // live glow underneath
    if (live != null) {
        val on = when (live.mode) {
            PinMode.OUTPUT, PinMode.INPUT, PinMode.PULLUP -> live.value != 0
            PinMode.PWM -> live.value > 0
            else -> false
        }
        if (on) drawCircle(colors.ok.copy(alpha = 0.45f), radius = r * 2.3f, center = center)
    }
    val fill = when {
        s.highlight != null -> s.highlight
        onPhoto -> Color.White.copy(alpha = 0.85f)
        else -> colors.pad
    }
    if (onPhoto) {
        drawCircle(Color.Black.copy(alpha = 0.45f * alpha), radius = r * 1.15f, center = center)
        drawCircle(fill.copy(alpha = alpha), radius = r * 0.78f, center = center)
    } else {
        drawRoundRect(fill.copy(alpha = alpha), Offset(center.x - r, center.y - r), Size(r * 2, r * 2), CornerRadius(r * 0.35f))
        drawCircle(colors.header.copy(alpha = 0.9f * alpha), radius = r * 0.42f, center = center)
    }
    if (live != null) {
        val frac = when (live.mode) {
            PinMode.ANALOG -> live.value / 1023f
            PinMode.PWM -> live.value / 255f
            PinMode.SERVO -> live.value / 180f
            else -> null
        }
        if (frac != null) {
            val rr = r * 1.7f
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = rr, center = center, style = Stroke(r * 0.45f))
            drawArc(
                colors.forTag(if (live.mode == PinMode.ANALOG) "analog" else "pwm"), -90f, 360f * frac, false,
                topLeft = Offset(center.x - rr, center.y - rr), size = Size(rr * 2, rr * 2), style = Stroke(r * 0.45f),
            )
        }
    }
    if (s.selected) {
        drawCircle(accent, radius = r * 2.1f, center = center, style = Stroke(r * 0.4f))
        drawCircle(Color.White.copy(alpha = 0.9f), radius = r * 2.55f, center = center, style = Stroke(r * 0.12f))
    }
}

@Composable
fun BoardDiagram(vm: AppViewModel, live: Map<Int, FirmataPin>, modifier: Modifier = Modifier) {
    val kit = vm.kit
    val b = kit.board
    val colors = LocalPinColors.current
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer(cacheSize = 256)
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var base by remember { mutableStateOf(Triple(0f, 0f, 1f)) } // ox, oy, mm→px

    // Board mm → canvas px. The board is turned so USB is at the top: x runs down, y runs right-to-left.
    fun map(x: Float, y: Float): Offset {
        val (ox, oy, s) = base
        return Offset(ox + (b.height - y) * s, oy + x * s) * zoom + pan
    }

    // board mm from canvas px (the inverse of map)
    fun unmap(v: Offset): Offset {
        val (ox, oy, s) = base
        val p = (v - pan) / zoom
        return Offset((p.y - oy) / s, b.height - (p.x - ox) / s)
    }
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) } // board mm, while drawing
    var hover by remember { mutableStateOf<Offset?>(null) }
    var erasing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Wire?>(null) } // a wire being dragged, drawn in place of the stored one
    var areaSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(modifier.onSizeChanged { areaSize = it }) {
    Canvas(
        Modifier.fillMaxSize()
            .penHover { hover = it }
            // the pen (or a finger with drawing on) draws a wire; it claims the touch before pan and tap see it
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type != PointerType.Stylus && !vm.fingerDraws) return@awaitEachGesture
                    down.consume()
                    hover = null
                    // grabbing an existing wire moves it instead of drawing a new one
                    val start = unmap(down.position)
                    val grab = if (currentEvent.penButton()) null else grabWire(vm.wires, start)
                    if (grab != null) {
                        val (w, idx) = grab
                        var cur = start
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            cur = unmap(ch.position)
                            moving = w.copy(points = reshape(w.points, idx, cur - start))
                            ev.changes.forEach { it.consume() }
                        }
                        moving?.let { finishMove(vm, w, it, idx) }
                        moving = null
                        return@awaitEachGesture
                    }
                    val pts = arrayListOf(start)
                    stroke = pts.toList()
                    // pen side button held at any point: the stroke erases instead of drawing
                    var erase = currentEvent.penButton()
                    erasing = erase
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        if (ev.penButton()) { erase = true; erasing = true }
                        pts += unmap(ch.position); stroke = pts.toList()
                        ev.changes.forEach { it.consume() }
                    }
                    stroke = emptyList(); erasing = false
                    if (erase) vm.wires.filter { w -> w.points.any { a -> pts.any { b -> (a - b).getDistance() < 2f } } }.forEach { vm.removeWire(it.id) }
                    else finishWire(vm, simplify(pts, 0.6f))
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val nz = (zoom * zoomChange).coerceIn(1f, 6f)
                    pan = centroid - (centroid - pan) * (nz / zoom) + panChange
                    zoom = nz
                    if (zoom == 1f) pan = Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                    onTap = { tap ->
                        val s = base.third * zoom
                        val hit = kit.pins.minByOrNull { (map(it.x, it.y) - tap).getDistance() }
                        vm.selectedPin = hit?.takeIf { (map(it.x, it.y) - tap).getDistance() < 2.4f * s }?.index
                    },
                )
            },
    ) {
        // fit the turned board (b.height wide, b.width tall) with room for the USB socket on top
        val marginTop = 8f
        val marginSide = 3f
        val s = min(size.width / (b.height + marginSide * 2), size.height / (b.width + marginTop + 2f))
        val ox = (size.width - b.height * s) / 2f
        val oy = (size.height - (b.width + marginTop) * s) / 2f + marginTop * s
        if (base != Triple(ox, oy, s)) base = Triple(ox, oy, s)
        val k = s * zoom

        // board outline
        val tl = map(0f, b.height)
        drawRoundRect(colors.pcbEdge, tl - Offset(0.5f * k, 0.5f * k), Size((b.height + 1f) * k, (b.width + 1f) * k), CornerRadius(2.5f * k))
        drawRoundRect(colors.pcb, tl, Size(b.height * k, b.width * k), CornerRadius(2f * k))

        // USB socket and power jack
        fun part(r: List<Float>, color: Color, edge: Color) {
            val p = map(r[0], r[1] + r[3])
            drawRoundRect(color, p, Size(r[3] * k, r[2] * k), CornerRadius(0.8f * k))
            drawRoundRect(edge, p, Size(r[3] * k, r[2] * k), CornerRadius(0.8f * k), style = Stroke(0.3f * k))
        }
        part(b.usb, Color(0xFFB9C1CA), Color(0xFF7D8793))
        part(b.jack, Color(0xFF15181C), Color(0xFF3A4048))

        // ATmega2560, turned 45°
        val chip = map(52f, 27f)
        rotate(45f, chip) {
            drawRoundRect(Color(0xFF16191D), chip - Offset(7f * k, 7f * k), Size(14f * k, 14f * k), CornerRadius(0.6f * k))
            drawCircle(Color(0xFF2B3036), 0.9f * k, chip - Offset(5f * k, 5f * k))
        }
        label(measurer, "ATmega2560", chip, colors.silk.copy(alpha = 0.75f), 1.5f * k, center = true)
        label(measurer, "MEGA 2560", map(70f, 26.7f), colors.silk, 3.0f * k, bold = true, center = true)
        label(measurer, "Rev3", map(74.5f, 26.7f), colors.silk.copy(alpha = 0.6f), 1.6f * k, center = true)

        // header housings
        for (hb in kit.headerBlocks) {
            val a = map(hb.l, hb.b)
            val c = map(hb.r, hb.t)
            drawRoundRect(colors.header, a, Size(c.x - a.x, c.y - a.y), CornerRadius(0.4f * k))
        }

        // group names along the long edges
        val groupLabels = listOf(
            Triple("PWM · I2C", 30.226f, 10.2f), Triple("PWM", 54.61f, 10.2f), Triple("COMMUNICATION", 77.47f, 10.2f),
            Triple("POWER", 36.83f, 43.1f), Triple("ANALOG IN", 59.69f, 43.1f), Triple("ANALOG IN", 82.55f, 43.1f),
        )
        for ((t, x, y) in groupLabels) {
            val p = map(x, y)
            rotate(90f, p) { label(measurer, t, p, colors.silk.copy(alpha = 0.55f), 1.5f * k, center = true, bold = true) }
        }

        // drawn wires, under the pads so the pins stay readable
        for (w0 in vm.wires) { val w = moving?.takeIf { it.id == w0.id } ?: w0; drawWire(w.points.map { map(it.x, it.y) }, Color(w.color), (0.7f * k).coerceIn(3f, 14f)) }
        if (stroke.size > 1) drawWire(stroke.map { map(it.x, it.y) },
            if (erasing) Color.White.copy(alpha = 0.35f) else WIRE_COLORS[vm.wires.size % WIRE_COLORS.size].copy(alpha = 0.8f), (if (erasing) 2.2f else 0.7f) * k)

        // pads and silkscreen labels
        val r = 0.85f * k
        val fontPx = (1.45f * k).coerceAtMost(15.sp.toPx())
        for (p in kit.pins) {
            val c = map(p.x, p.y)
            val st = markerStyle(p, vm, colors, live)
            drawPinMarker(c, r, st, colors, accent, onPhoto = false)
            val text = p.label.ifEmpty { continue }
            val col = (st.highlight ?: colors.silk).copy(alpha = if (st.dimmed) 0.35f else 1f)
            when (p.side) {
                "top" -> label(measurer, text, c - Offset(1.6f * k, 0f), col, fontPx, alignEnd = true)
                "bottom" -> label(measurer, text, c + Offset(1.6f * k, 0f), col, fontPx)
                "left" -> label(measurer, text, c - Offset(0f, 1.9f * k), col, fontPx, center = true)
                else -> label(measurer, text, c + Offset(0f, 1.9f * k), col, fontPx, center = true)
            }
        }
    }
        // pen hovering over a pin: show what it is
        hover?.let { h ->
            val s = base.third * zoom
            kit.pins.minByOrNull { (map(it.x, it.y) - h).getDistance() }?.takeIf { (map(it.x, it.y) - h).getDistance() < 2.4f * s }?.let { PinHoverCard(it, h, areaSize) }
        }
    }
}

// A finished stroke becomes a wire when it starts or ends on a pin (mm distances)
private fun finishWire(vm: AppViewModel, pts: List<Offset>) {
    if (pts.size < 2) return
    fun nearest(p: Offset) = vm.kit.pins.minByOrNull { (Offset(it.x, it.y) - p).getDistance() }?.takeIf { (Offset(it.x, it.y) - p).getDistance() < 2.2f }
    var a = nearest(pts.first()); var b = nearest(pts.last()); var line = pts
    if (a == null && b == null) return
    if (a == null) { a = b; b = null; line = pts.reversed() }
    if (b?.index == a!!.index) b = null
    // snap the ends onto the pins
    val snapped = line.toMutableList().apply {
        this[0] = Offset(a.x, a.y)
        b?.let { this[lastIndex] = Offset(it.x, it.y) }
    }
    if (b == null && (snapped.last() - snapped.first()).getDistance() < 3f) return
    val c = WIRE_COLORS[vm.wires.size % WIRE_COLORS.size]
    vm.addWire(Wire(System.currentTimeMillis(), snapped, a.index, b?.index, "", c.argb()))
}

// Text centred vertically at p; left-aligned, right-aligned (alignEnd) or centred
fun DrawScope.label(
    m: TextMeasurer, text: String, p: Offset, color: Color, px: Float,
    center: Boolean = false, alignEnd: Boolean = false, bold: Boolean = false, shadow: Boolean = false,
) {
    if (px < 4f) return
    val style = TextStyle(color = color, fontSize = (px / density).sp, fontFamily = Mono, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    val l = m.measure(text, style)
    val x = when { center -> p.x - l.size.width / 2f; alignEnd -> p.x - l.size.width; else -> p.x }
    val tl = Offset(x, p.y - l.size.height / 2f)
    if (shadow) {
        drawRoundRect(Color.Black.copy(alpha = 0.62f), tl - Offset(px * 0.3f, 0f), Size(l.size.width + px * 0.6f, l.size.height.toFloat()), CornerRadius(px * 0.35f))
    }
    drawText(l, topLeft = tl)
}

// Label for a pin on a photo or live view. The label starts just past the pin and runs outward
// (dir points from the pin away from the board's middle). Where the pin row lies across the screen,
// the text is turned to run sideways, so neighbouring labels never sit on top of other pins.
fun DrawScope.pinLabel(m: TextMeasurer, text: String, pin: Offset, dir: Offset, gap: Float, color: Color, px: Float) {
    if (px < 4f) return
    val len = dir.getDistance()
    if (len < 1e-3f) return
    val u = dir / len
    val style = TextStyle(color = color, fontSize = (px / density).sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    val l = m.measure(text, style)
    val w = l.size.width.toFloat(); val h = l.size.height.toFloat()
    val pad = px * 0.3f
    val start = pin + u * gap
    if (kotlin.math.abs(u.x) >= kotlin.math.abs(u.y)) {
        // upright text, left- or right-aligned depending on the side
        val x = if (u.x > 0) start.x else start.x - w
        val tl = Offset(x, start.y - h / 2)
        drawRoundRect(Color.Black.copy(alpha = 0.62f), tl - Offset(pad, 0f), Size(w + pad * 2, h), CornerRadius(px * 0.35f))
        drawText(l, topLeft = tl)
    } else {
        // turned text reading outward from the pin
        val angle = Math.toDegrees(kotlin.math.atan2(u.y.toDouble(), u.x.toDouble())).toFloat()
        rotate(angle, start) {
            val tl = Offset(start.x, start.y - h / 2)
            drawRoundRect(Color.Black.copy(alpha = 0.62f), tl - Offset(pad, 0f), Size(w + pad * 2, h), CornerRadius(px * 0.35f))
            drawText(l, topLeft = tl)
        }
    }
}

// Unit direction (board mm) pointing away from the board for a pin's side
fun outwardMm(side: String): Offset = when (side) { "top" -> Offset(0f, -1f); "bottom" -> Offset(0f, 1f); "left" -> Offset(-1f, 0f); else -> Offset(1f, 0f) }

// The wire under the finger: (wire, point index), preferring its ends; mm distances
private fun grabWire(wires: List<Wire>, p: Offset): Pair<Wire, Int>? {
    for (w in wires.reversed()) {
        if ((w.points.last() - p).getDistance() < 2.4f) return w to w.points.lastIndex
        if ((w.points.first() - p).getDistance() < 1.2f) continue // the start sits on its pin: tapping there draws a new wire
    }
    for (w in wires.reversed()) {
        val i = w.points.indices.minByOrNull { (w.points[it] - p).getDistance() } ?: continue
        if ((w.points[i] - p).getDistance() < 1.6f && i in 1 until w.points.lastIndex) return w to i
    }
    return null
}

// Moves point idx by delta; neighbours follow smoothly. An end drags the whole wire like a rope; a middle point bends it.
private fun reshape(pts: List<Offset>, idx: Int, delta: Offset): List<Offset> {
    val n = pts.lastIndex.coerceAtLeast(1)
    return pts.mapIndexed { i, q ->
        val w = when (idx) {
            pts.lastIndex -> i.toFloat() / n
            0 -> 1f - i.toFloat() / n
            else -> if (i <= idx) i.toFloat() / idx else (pts.lastIndex - i).toFloat() / (pts.lastIndex - idx)
        }
        q + delta * (w * w * (3 - 2 * w)) // smoothstep
    }
}

// On release a dragged end snaps to the nearest pin (or stays free); the wire keeps its label and colour
private fun finishMove(vm: AppViewModel, old: Wire, moved: Wire, idx: Int) {
    val end = moved.points[idx]
    if (idx != moved.points.lastIndex) { vm.updateWire(moved); return }
    val pin = vm.kit.pins.minByOrNull { (Offset(it.x, it.y) - end).getDistance() }?.takeIf { (Offset(it.x, it.y) - end).getDistance() < 2.2f && it.index != old.from }
    val pts = if (pin != null) moved.points.toMutableList().apply { this[lastIndex] = Offset(pin.x, pin.y) } else moved.points
    vm.updateWire(moved.copy(points = pts, to = pin?.index))
}
