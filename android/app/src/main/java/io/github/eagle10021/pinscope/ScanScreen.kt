@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LocalContentColor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LabelOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val AnchorColors = listOf(Color(0xFFFFC95C), Color(0xFF22D3EE), Color(0xFF4ADE80), Color(0xFFFB923C))

@Composable
fun ScanScreen(vm: AppViewModel) {
    when (vm.stage) {
        ScanStage.EMPTY -> ScanIntro(vm)
        ScanStage.CAMERA -> LiveScan(vm, rememberPhotoPicker(vm))
        ScanStage.ALIGN -> AlignView(vm)
        ScanStage.EXPLORE -> ExploreView(vm)
    }
}

// ---------- photo import ----------

@Composable
private fun rememberPhotoPicker(vm: AppViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val big = max(info.size.width, info.size.height)
                        if (big > MAX_SIDE) {
                            val f = MAX_SIDE.toFloat() / big
                            dec.setTargetSize((info.size.width * f).roundToInt(), (info.size.height * f).roundToInt())
                        }
                    }
                }.getOrNull()
            } ?: return@launch
            val det = withContext(Dispatchers.Default) { vm.detectIn(bmp) }
            if (det != null) vm.usePhoto(bmp, vm.anchorsFrom(det.first), found = det.second)
            else vm.usePhoto(bmp, guessCorners(vm.kit, bmp.width.toFloat(), bmp.height.toFloat(), 0.82f))
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private const val MAX_SIDE = 2560

// The board outline fitted into a w×h area: USB at the top in portrait, on the left in landscape
private class Fit(val kit: KitData, w: Float, h: Float, fill: Float) {
    val portrait = h >= w
    val bw = if (portrait) kit.board.height else kit.board.width
    val bh = if (portrait) kit.board.width else kit.board.height
    val s = min(w * fill / bw, h * fill / bh)
    val ox = (w - bw * s) / 2f
    val oy = (h - bh * s) / 2f
    fun map(x: Float, y: Float) = if (portrait) Offset(ox + (kit.board.height - y) * s, oy + x * s) else Offset(ox + x * s, oy + y * s)
}

fun guessCorners(kit: KitData, w: Float, h: Float, fill: Float): List<Offset> {
    val f = Fit(kit, w, h, fill)
    return kit.anchors.map { a -> kit.pins[a.pin].let { f.map(it.x, it.y) } }
}

// ---------- intro ----------

@Composable
private fun ScanIntro(vm: AppViewModel) {
    val pick = rememberPhotoPicker(vm)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                BoardDiagram(vm, emptyMap(), Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(24.dp))
            Text("See the pins on your own board", style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Point the camera at your Mega and Pinscope finds the headers and labels every pin, live. Tap any pin for what it does and which kit parts use it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.stage = ScanStage.CAMERA }, modifier = Modifier.fillMaxWidth().height(60.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(10.dp)); Text("Start live view", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth().height(60.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(10.dp)); Text("Choose a photo")
            }
        }
    }
}

fun toUprightBitmap(image: ImageProxy): Bitmap {
    var bmp = image.toBitmap()
    val crop = image.cropRect
    if (bmp.width == image.width && bmp.height == image.height && (crop.width() != bmp.width || crop.height() != bmp.height)) {
        bmp = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
    }
    val rot = image.imageInfo.rotationDegrees
    val big = max(bmp.width, bmp.height)
    val f = if (big > MAX_SIDE) MAX_SIDE.toFloat() / big else 1f
    if (rot == 0 && f == 1f) return bmp
    val m = Matrix().apply { postScale(f, f); postRotate(rot.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

@Composable
fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = tint),
    ) { Icon(icon, desc) }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.padding(horizontal = 24.dp), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ---------- shared photo view: fit, pinch-zoom and pan ----------

private class PhotoView(val img: ImageBitmap) {
    var size by mutableStateOf(IntSize.Zero)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    val fit get() = min(size.width / img.width.toFloat(), size.height / img.height.toFloat()).coerceAtLeast(0.0001f)
    private val base get() = Offset((size.width - img.width * fit) / 2f, (size.height - img.height * fit) / 2f)
    val scale get() = fit * zoom
    fun toView(p: Offset) = (base + p * fit) * zoom + pan
    fun toImage(v: Offset) = ((v - pan) / zoom - base) / fit

    fun transform(centroid: Offset, panChange: Offset, zoomChange: Float) {
        val nz = (zoom * zoomChange).coerceIn(1f, 8f)
        pan = centroid - (centroid - pan) * (nz / zoom) + panChange
        zoom = nz
        if (zoom <= 1.001f) pan = Offset.Zero
    }

    fun DrawScope.drawPhoto() {
        val tl = toView(Offset.Zero)
        drawImage(img, dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
            dstSize = IntSize((img.width * scale).roundToInt(), (img.height * scale).roundToInt()))
    }
}

// ---------- alignment ----------

@Composable
private fun AlignView(vm: AppViewModel) {
    val bmp = vm.photo ?: return
    val img = remember(bmp) { bmp.asImageBitmap() }
    val pv = remember(img) { PhotoView(img) }
    val kit = vm.kit
    val density = LocalDensity.current
    val grab = with(density) { 44.dp.toPx() }
    var active by remember { mutableIntStateOf(-1) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val colors = LocalPinColors.current

    Box(Modifier.fillMaxSize().background(Color(0xFF05080C))) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { pv.size = it }.pointerInput(pv) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val idx = vm.corners.indices.minByOrNull { (pv.toView(vm.corners[it]) - down.position).getDistance() }
                    if (idx != null && (pv.toView(vm.corners[idx]) - down.position).getDistance() < grab) {
                        active = idx; touch = down.position
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val d = ch.positionChange() / pv.scale
                            vm.corners = vm.corners.toMutableList().also { it[idx] = it[idx] + d }
                            touch = ch.position
                            ch.consume()
                        }
                        active = -1
                    } else {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                            val z = ev.calculateZoom(); val p = ev.calculatePan(); val c = ev.calculateCentroid()
                            if (c != Offset.Unspecified) pv.transform(c, p, z)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        ) {
            with(pv) { drawPhoto() }
            val h = vm.homography()
            if (h != null) {
                // projected outline and pins, updating while a marker moves
                val outline = listOf(0f to 0f, kit.board.width to 0f, kit.board.width to kit.board.height, 0f to kit.board.height)
                    .map { (x, y) -> pv.toView(h.map(x, y)) }
                val path = Path().apply { moveTo(outline[0].x, outline[0].y); outline.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, Color.White.copy(alpha = 0.7f), style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                for (p in kit.pins) {
                    val c = pv.toView(h.map(p.x, p.y))
                    val r = (h.scaleAt(p.x, p.y) * pv.scale * 0.55f).coerceIn(2f, 14f)
                    drawCircle(Color.Black.copy(alpha = 0.5f), r * 1.3f, c)
                    drawCircle(colors.padOn, r * 0.9f, c)
                }
            }
            // anchor handles
            vm.corners.forEachIndexed { i, pt ->
                val c = pv.toView(pt)
                val col = AnchorColors[i]
                val big = if (i == active) 30f else 24f
                drawCircle(Color.Black.copy(alpha = 0.35f), big + 6f, c)
                drawCircle(col, big, c, style = Stroke(5f))
                drawLine(col, c - Offset(big * 0.55f, 0f), c + Offset(big * 0.55f, 0f), 2.5f)
                drawLine(col, c - Offset(0f, big * 0.55f), c + Offset(0f, big * 0.55f), 2.5f)
                val l = measurer.measure(kit.anchors[i].name, androidx.compose.ui.text.TextStyle(fontSize = (30f / density.density).sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                drawRoundRect(col, c + Offset(-l.size.width / 2f - 12f, -big - 26f - l.size.height / 2f - 4f),
                    Size(l.size.width + 24f, l.size.height + 8f), androidx.compose.ui.geometry.CornerRadius(30f))
                label(measurer, kit.anchors[i].name, c + Offset(0f, -big - 26f), Color.Black, 30f, center = true, bold = true)
            }
            // magnifier while dragging, in the corner away from the finger
            if (active >= 0) {
                val lr = 150f
                val center = Offset(if (touch.x > size.width / 2) lr + 32f else size.width - lr - 32f, lr + 200f)
                val pt = vm.corners[active]
                val mag = 3.5f
                val circle = Path().apply { addOval(Rect(center, lr)) }
                drawCircle(Color.Black, lr + 4f, center)
                clipPath(circle) {
                    val s = pv.scale * mag
                    val tl = center - pt * s
                    drawImage(img, dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                        dstSize = IntSize((img.width * s).roundToInt(), (img.height * s).roundToInt()))
                }
                val col = AnchorColors[active]
                drawCircle(col, lr, center, style = Stroke(5f))
                drawLine(col, center - Offset(26f, 0f), center + Offset(26f, 0f), 3f)
                drawLine(col, center - Offset(0f, 26f), center + Offset(0f, 26f), 3f)
            }
        }

        // instructions
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            var help by remember { mutableStateOf(false) }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)) {
                Column(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.TouchApp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (active >= 0) kit.anchors[active].hint else "Drag each marker onto its pin", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2)
                        IconButton(onClick = { help = !help }) { Icon(if (help) Icons.Rounded.ExpandLess else Icons.AutoMirrored.Rounded.HelpOutline, if (help) "Hide help" else "Help") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        kit.anchors.forEachIndexed { i, a ->
                            Surface(shape = RoundedCornerShape(50), color = AnchorColors[i].copy(alpha = if (i == active) 0.45f else 0.18f)) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(AnchorColors[i]))
                                    Spacer(Modifier.width(6.dp))
                                    Text(a.name, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (help) Column(Modifier.padding(top = 10.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        kit.anchors.forEachIndexed { i, a ->
                            Text("${a.name}: ${a.hint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Pinch to zoom for precision. The yellow dots should land on the header holes. Tapping Done also teaches Pinscope your board for live tracking.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(onClick = { vm.stage = ScanStage.CAMERA }, modifier = Modifier.weight(1f).height(56.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Retake")
            }
            Button(onClick = { vm.finishAlign() }, enabled = vm.homography() != null, modifier = Modifier.weight(1f).height(56.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)); Text("Done")
            }
        }
    }
}

// ---------- explore ----------

@Composable
private fun ExploreView(vm: AppViewModel) {
    val bmp = vm.photo ?: return
    val img = remember(bmp) { bmp.asImageBitmap() }
    val pv = remember(img) { PhotoView(img) }
    val kit = vm.kit
    val colors = LocalPinColors.current
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val firmata by vm.bench.firmata.collectAsState()
    val link by vm.bench.link.collectAsState()
    val liveOn = vm.showLive && link is Conn.Connected && (link as Conn.Connected).mode == BenchMode.FIRMATA
    val live = if (liveOn) firmata.pins else emptyMap()
    val h = remember(vm.corners) { vm.homography() }
    var menu by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val labelMinPitch = with(density) { 9.dp.toPx() }
    val context = LocalContext.current
    var inkColor by remember { mutableIntStateOf(0) }
    var inkStroke by remember { mutableStateOf<List<Offset>>(emptyList()) } // image px, while drawing
    var inkErasing by remember { mutableStateOf(false) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var saved by remember { mutableStateOf<Boolean?>(null) }

    Box(Modifier.fillMaxSize().background(Color(0xFF05080C))) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { pv.size = it }
                .penHover { hover = it }
                // the pen (or a finger with drawing on) writes notes on the photo; side button held erases
                .pointerInput(pv) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (down.type != PointerType.Stylus && down.type != PointerType.Eraser && !vm.fingerDraws) return@awaitEachGesture
                        down.consume(); hover = null
                        var erase = currentEvent.penButton()
                        val pts = arrayListOf(pv.toImage(down.position))
                        inkErasing = erase; inkStroke = pts.toList()
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            if (ev.penButton()) { erase = true; inkErasing = true }
                            pts += pv.toImage(ch.position); inkStroke = pts.toList()
                            ev.changes.forEach { it.consume() }
                        }
                        inkStroke = emptyList(); inkErasing = false
                        val reach = 14f / pv.scale
                        if (erase) {
                            val keep = vm.ink.filter { st -> st.points.none { a -> pts.any { b -> (a - b).getDistance() < reach } } }
                            if (keep.size != vm.ink.size) { vm.clearInk(); keep.forEach { vm.addInk(it) } }
                        } else if (pts.size > 1) vm.addInk(InkStroke(simplify(pts, 1.5f / pv.scale), INK_COLORS[inkColor].argb(), 4.dp.toPx() / pv.scale))
                    }
                }
                .pointerInput(pv, h) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var moved = false
                    var multi = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        if (ev.changes.size > 1) multi = true
                        val z = ev.calculateZoom(); val p = ev.calculatePan(); val c = ev.calculateCentroid()
                        if ((ev.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop || multi) moved = true
                        if (moved && c != Offset.Unspecified) pv.transform(c, p, z)
                        ev.changes.forEach { it.consume() }
                    }
                    if (!moved && h != null) {
                        val hit = kit.pins.minByOrNull { (pv.toView(h.map(it.x, it.y)) - down.position).getDistance() }
                        val pitch = h.scaleAt(hit?.x ?: 0f, hit?.y ?: 0f) * 2.54f * pv.scale
                        vm.selectedPin = hit?.takeIf { (pv.toView(h.map(it.x, it.y)) - down.position).getDistance() < max(pitch * 0.7f, 22f) }?.index
                    }
                }
            },
        ) {
            with(pv) { drawPhoto() }
            if (h == null) return@Canvas
            // gentle shade so markers read on busy photos
            drawRect(Color.Black.copy(alpha = if (filterActive(vm.filter)) 0.35f else 0.15f))
            for (p in kit.pins) {
                val c = pv.toView(h.map(p.x, p.y))
                val pxPerMm = h.scaleAt(p.x, p.y) * pv.scale
                val st = markerStyle(p, vm, colors, live)
                drawPinMarker(c, (pxPerMm * 0.62f).coerceIn(3f, 22f), st, colors, accent, onPhoto = true)
                val pitch = pxPerMm * 2.54f
                val show = st.selected || (vm.showLabels && !st.dimmed && (pitch > labelMinPitch || st.highlight != null))
                if (show && p.label.isNotEmpty()) {
                    val o = outwardMm(p.side)
                    val px = (pitch * 0.55f).coerceIn(with(density) { 8.sp.toPx() }, with(density) { 15.sp.toPx() })
                    pinLabel(measurer, p.label, c, pv.toView(h.map(p.x + o.x, p.y + o.y)) - c, pxPerMm * 1.3f, st.highlight ?: Color.White, px)
                }
            }
        }

        // notes on top of the photo and pin labels
        Canvas(Modifier.fillMaxSize()) {
            fun line(pts: List<Offset>, c: Color, w: Float) {
                if (pts.size < 2) return
                val path = androidx.compose.ui.graphics.Path().apply { pts.forEachIndexed { i, p -> val v = pv.toView(p); if (i == 0) moveTo(v.x, v.y) else lineTo(v.x, v.y) } }
                drawPath(path, c, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            for (st in vm.ink) line(st.points, Color(st.color), st.width * pv.scale)
            if (inkStroke.size > 1) line(inkStroke, if (inkErasing) Color.White.copy(alpha = 0.35f) else INK_COLORS[inkColor], (if (inkErasing) 28f else 4.dp.toPx()))
        }
        hover?.let { hv -> h?.let { hm ->
            kit.pins.minByOrNull { (pv.toView(hm.map(it.x, it.y)) - hv).getDistance() }
                ?.takeIf { (pv.toView(hm.map(it.x, it.y)) - hv).getDistance() < max(hm.scaleAt(it.x, it.y) * 2.54f * pv.scale * 0.7f, 22f) }
                ?.let { PinHoverCard(it, hv, pv.size) }
        } }

        // top: filters over a soft gradient
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .statusBarsPadding().padding(top = 8.dp, bottom = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your board", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (liveOn) "Live pin states from Firmata" else "Tap a pin for details",
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FilterBar(vm.filter, kit, { vm.filter = it; vm.selectedPin = null })
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        AnimatedVisibility(pv.zoom > 1.01f, enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.clickable { pv.zoom = 1f; pv.pan = Offset.Zero }) {
                Text("%.1f×  ·  reset".format(pv.zoom), color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp)
            }
        }
        if (vm.fingerDraws || vm.ink.isNotEmpty()) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                INK_COLORS.forEachIndexed { i, c ->
                    Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(c)
                        .then(if (i == inkColor) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { inkColor = i })
                }
                IconButton(onClick = { vm.undoInk() }, enabled = vm.ink.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo the last note") }
                IconButton(onClick = { vm.clearInk() }, enabled = vm.ink.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, "Clear the notes") }
                IconButton(onClick = { saved = InkStore.export(context, bmp, vm.ink) }) {
                    Icon(if (saved == true) Icons.Rounded.Check else Icons.Rounded.SaveAlt, "Save the photo with notes to Pictures")
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = true,
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { vm.selectedPin = null; vm.stage = ScanStage.CAMERA }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Icon(Icons.Rounded.CameraAlt, "Live view")
                }
            },
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                toolbarContentColor = MaterialTheme.colorScheme.onSurface,
                fabContainerColor = MaterialTheme.colorScheme.primary,
                fabContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            IconButton(onClick = { vm.selectedPin = null; vm.stage = ScanStage.ALIGN }) { Icon(Icons.Rounded.Tune, "Adjust alignment") }
            IconButton(onClick = { vm.fingerDraws = !vm.fingerDraws }) {
                Icon(Icons.Rounded.Gesture, if (vm.fingerDraws) "Stop drawing with a finger" else "Draw notes with a finger",
                    tint = if (vm.fingerDraws) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            IconButton(onClick = { vm.showLabels = !vm.showLabels }) { Icon(if (vm.showLabels) Icons.Rounded.Label else Icons.Rounded.LabelOff, "Labels") }
            IconButton(onClick = { vm.showLive = !vm.showLive }) { Icon(Icons.Rounded.Sensors, if (vm.showLive) "Hide live states" else "Show live states", tint = if (vm.showLive) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.45f)) }
            IconButton(onClick = { vm.forgetPhoto() }) { Icon(Icons.Rounded.Close, "Remove photo") }
        }
        }
    }
}
