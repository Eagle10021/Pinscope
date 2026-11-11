@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalCamera2Interop::class, TransformExperimental::class)

package io.github.eagle10021.pinscope

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LabelOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors
import kotlin.math.max

// Smooths the four tracked anchor points: steady when the phone is still, quick when it moves
private class Tracker {
    var corners by mutableStateOf<List<Offset>?>(null)
    var lastSeen by mutableLongStateOf(0L)
    var refined by mutableStateOf(false)

    fun update(pts: List<Offset>, isRefined: Boolean) {
        val old = corners
        corners = if (old == null || SystemClock.uptimeMillis() - lastSeen > 600) pts else pts.mapIndexed { i, p ->
            val o = old[i]
            val d = (p - o).getDistance()
            val a = when { d > 60f -> 1f; d > 18f -> 0.6f; else -> 0.28f }
            o + (p - o) * a
        }
        lastSeen = SystemClock.uptimeMillis()
        refined = isRefined
    }
}

@Composable
fun LiveScan(vm: AppViewModel, pick: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    val leave = { vm.stage = if (vm.photo != null) ScanStage.EXPLORE else ScanStage.EMPTY }
    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Rounded.CameraAlt, "Camera access needed",
                "Pinscope uses the camera only to find your board and label its pins. You can also pick a photo you already have.",
            ) {
                Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }, shapes = ButtonDefaults.shapes()) { Text("Allow camera") }
                TextButton(onClick = pick) { Text("Choose a photo instead") }
            }
        }
        return
    }

    val tracker = remember { Tracker() }
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var torch by remember { mutableStateOf(false) }
    var ois by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val main = remember { Handler(Looper.getMainLooper()) }
    val firmata by vm.bench.firmata.collectAsState()
    val link by vm.bench.link.collectAsState()
    val live = if (vm.showLive && link is Conn.Connected) firmata.pins else emptyMap()

    // keep "lost" state fresh even when no frames find the board
    LaunchedEffect(Unit) { while (true) { delay(150); now = SystemClock.uptimeMillis() } }
    val locked = tracker.corners != null && now - tracker.lastSeen < 700
    val overlayAlpha by animateFloatAsState(if (locked) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "overlay")

    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val exec = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        val anchorsMm = vm.kit.anchors.map { vm.kit.pins[it.pin] }

        fun analyze(image: ImageProxy) {
            try {
                if (!vm.opencv) return
                val plane = image.planes[0]
                val mat = Mat(image.height, image.width, CvType.CV_8UC4, plane.buffer, plane.rowStride.toLong())
                val mem = vm.memory
                val det = runCatching {
                    if (mem != null) {
                        val t = mem.trackLive(mat, vm.kit)
                        when {
                            t == null -> null
                            // strong match: use it directly for speed; weak: let the pin holes settle it
                            t.second >= 40 -> Detection(t.first, (t.second / 60f).coerceIn(0.3f, 1f), refined = true)
                            else -> vm.detector.detect(mat, seed = t.first) ?: Detection(t.first, 0.4f, refined = true)
                        }
                    } else vm.detector.detect(mat)
                }.getOrNull()
                mat.release()
                if (det == null) return
                val pts = FloatArray(8)
                anchorsMm.forEachIndexed { i, p -> val o = det.map(p.x, p.y); pts[2 * i] = o.x; pts[2 * i + 1] = o.y }
                // frame pixels → upright crop → view pixels (the crop already matches the preview's aspect)
                val crop = image.cropRect
                val rot = image.imageInfo.rotationDegrees
                val cw = crop.width().toFloat(); val ch = crop.height().toFloat()
                for (i in 0 until 4) {
                    val x = pts[2 * i] - crop.left; val y = pts[2 * i + 1] - crop.top
                    val (ux, uy) = when (rot) { 90 -> (ch - y) to x; 180 -> (cw - x) to (ch - y); 270 -> y to (cw - x); else -> x to y }
                    pts[2 * i] = ux; pts[2 * i + 1] = uy
                }
                val uw = if (rot == 90 || rot == 270) ch else cw
                val uh = if (rot == 90 || rot == 270) cw else ch
                main.post {
                    val sx = previewView.width / uw; val sy = previewView.height / uh
                    if (sx <= 0f || sy <= 0f) return@post
                    for (i in 0 until 4) { pts[2 * i] *= sx; pts[2 * i + 1] *= sy }
                    tracker.update((0 until 4).map { Offset(pts[2 * it], pts[2 * it + 1]) }, det.refined)
                }
            } finally { image.close() }
        }

        future.addListener({
            val p = future.get()
            provider = p
            previewView.post {
                val vp = previewView.viewPort ?: return@post
                val rotation = previewView.display?.rotation ?: 0
                // Optical stabilisation on, electronic off: EIS would warp the preview away from the analysed frames
                fun stabilise(e: Camera2Interop.Extender<*>) {
                    e.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    e.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
                val preview = Preview.Builder().also { stabilise(Camera2Interop.Extender(it)) }.build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setTargetRotation(rotation)
                    .also { stabilise(Camera2Interop.Extender(it)) }.build()
                analysis.setAnalyzer(exec, ::analyze)
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotation)
                    .also { stabilise(Camera2Interop.Extender(it)) }.build()
                val group = UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(analysis).addUseCase(ic).build()
                try {
                    p.unbindAll()
                    val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    camera = cam
                    capture = ic
                    val modes = Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ois = modes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                } catch (_: Exception) { }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll(); exec.shutdown() }
    }

    val freeze: () -> Unit = freeze@{
        val ic = capture ?: return@freeze
        busy = true
        val liveCorners = tracker.corners?.takeIf { locked }
        ic.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                scope.launch {
                    val bmp = withContext(Dispatchers.Default) { image.use { toUprightBitmap(it) } }
                    val det = withContext(Dispatchers.Default) { vm.detectIn(bmp) }
                    busy = false
                    torch = false
                    if (det != null) vm.usePhoto(bmp, vm.anchorsFrom(det.first), found = det.second)
                    else {
                        // capture is cropped to the preview, so view positions scale straight across
                        val vw = viewSize.width.toFloat().coerceAtLeast(1f); val vh = viewSize.height.toFloat().coerceAtLeast(1f)
                        val guess = liveCorners?.map { Offset(it.x / vw * bmp.width, it.y / vh * bmp.height) }
                            ?: guessCorners(vm.kit, bmp.width.toFloat(), bmp.height.toFloat(), 0.8f)
                        vm.usePhoto(bmp, guess, found = false)
                    }
                }
            }
            override fun onError(e: ImageCaptureException) { busy = false }
        })
    }

    val colors = LocalPinColors.current
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize().onSizeChanged { viewSize = it })

        // live overlay
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { tap ->
                    val c = tracker.corners ?: return@detectTapGestures
                    val h = Homography.solve(vm.kit.anchors.map { a -> vm.kit.pins[a.pin].let { Offset(it.x, it.y) } }, c) ?: return@detectTapGestures
                    val hit = vm.kit.pins.minByOrNull { (h.map(it.x, it.y) - tap).getDistance() } ?: return@detectTapGestures
                    val pitch = h.scaleAt(hit.x, hit.y) * 2.54f
                    vm.selectedPin = if ((h.map(hit.x, hit.y) - tap).getDistance() < max(pitch * 0.75f, 24.dp.toPx())) hit.index else null
                }
            },
        ) {
            val c = tracker.corners
            if (c == null || overlayAlpha <= 0.01f) {
                // scanning brackets while looking for the board
                val w = size.width * 0.78f; val hh = w * 1.9f
                val l = (size.width - w) / 2; val t = (size.height - hh) / 2
                val arm = 48.dp.toPx(); val s = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val col = Color.White.copy(alpha = 0.85f)
                for ((x, y, dx, dy) in listOf(
                    Quad(l, t, 1f, 1f), Quad(l + w, t, -1f, 1f), Quad(l, t + hh, 1f, -1f), Quad(l + w, t + hh, -1f, -1f),
                )) {
                    drawLine(col, Offset(x, y), Offset(x + arm * dx, y), s.width, s.cap)
                    drawLine(col, Offset(x, y), Offset(x, y + arm * dy), s.width, s.cap)
                }
                return@Canvas
            }
            val h = Homography.solve(vm.kit.anchors.map { a -> vm.kit.pins[a.pin].let { Offset(it.x, it.y) } }, c) ?: return@Canvas
            val b = vm.kit.board
            val outline = listOf(0f to 0f, b.width to 0f, b.width to b.height, 0f to b.height).map { (x, y) -> h.map(x, y) }
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(outline[0].x, outline[0].y); outline.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, accent.copy(alpha = 0.9f * overlayAlpha), style = Stroke(2.5.dp.toPx()))
            val minLabelPitch = 9.dp.toPx()
            for (p in vm.kit.pins) {
                val pos = h.map(p.x, p.y)
                val pxPerMm = h.scaleAt(p.x, p.y)
                val st = markerStyle(p, vm, colors, live)
                drawPinMarker(pos, (pxPerMm * 0.62f).coerceIn(3f, 22f), st.copy(dimmed = st.dimmed), colors, accent, onPhoto = true)
                val pitch = pxPerMm * 2.54f
                if (p.label.isNotEmpty() && (st.selected || (vm.showLabels && !st.dimmed && (pitch > minLabelPitch || st.highlight != null)))) {
                    val o = outwardMm(p.side)
                    val px = (pitch * 0.55f).coerceIn(with(density) { 8.sp.toPx() }, with(density) { 15.sp.toPx() })
                    pinLabel(measurer, p.label, pos, h.map(p.x + o.x, p.y + o.y) - pos, pxPerMm * 1.3f, st.highlight ?: Color.White, px)
                }
            }
        }

        // top: status and filters
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                .statusBarsPadding().padding(top = 8.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.width(4.dp))
                val fast = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                AnimatedContent(locked, transitionSpec = { fadeIn(fast) togetherWith fadeOut(fast) }, label = "status") { on ->
                    Surface(shape = RoundedCornerShape(50), color = if (on) colors.ok.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.5f)) {
                        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (on) Icon(Icons.Rounded.CheckCircle, null, tint = colors.ok, modifier = Modifier.padding(4.dp).size(20.dp))
                            else LoadingIndicator(Modifier.size(28.dp), color = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when { !vm.opencv -> "Board detection unavailable"; on && vm.learned -> "Tracking your board"; on && vm.usingPreset -> "Tracking (ELEGOO Mega)"; on -> "Board found"; vm.learned -> "Looking for your board…"; else -> "Looking for your Mega…" },
                                color = Color.White, style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (ois == true) Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.5f)) {
                    Text("OIS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            FilterBar(vm.filter, vm.kit, { vm.filter = it; vm.selectedPin = null })
        }

        // bottom: hint and the expressive floating toolbar
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!locked || (!vm.learned && !vm.usingPreset)) Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(if (!vm.learned) "Tip: tap the shutter and line the board up once. Pinscope then learns your board and tracks it live." else "Hold the phone above the board so the whole Mega is in view",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
            }
            HorizontalFloatingToolbar(
                expanded = true,
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { if (!busy) freeze() }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        if (busy) LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        else Icon(Icons.Rounded.CenterFocusStrong, "Freeze and save")
                    }
                },
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                toolbarContentColor = MaterialTheme.colorScheme.onSurface,
                fabContainerColor = MaterialTheme.colorScheme.primary,
                fabContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            ) {
                IconButton(onClick = pick) { Icon(Icons.Rounded.PhotoLibrary, "Choose a photo") }
                IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) {
                    Icon(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Torch")
                }
                IconButton(onClick = { vm.showLabels = !vm.showLabels }) {
                    Icon(if (vm.showLabels) Icons.Rounded.Label else Icons.Rounded.LabelOff, "Labels")
                }
            }
        }
    }
}

private data class Quad(val x: Float, val y: Float, val dx: Float, val dy: Float)
