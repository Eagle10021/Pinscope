@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.DeveloperBoardOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.log2
import kotlin.math.roundToInt

private val partArt = HashMap<String, ImageBitmap?>()

@Composable
private fun art(key: String?): ImageBitmap? {
    val ctx = LocalContext.current
    if (key == null) return null
    return remember(key) { partArt.getOrPut(key) { runCatching { ctx.assets.open("parts/$key.png").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull() } }
}

@Composable
fun SimulatorScreen(vm: AppViewModel, sim: Simulator) {
    BackHandler { vm.saveSimParts(); vm.closeSim() }
    val tick by sim.tick.collectAsState()
    val warnings by sim.warnings.collectAsState()
    var tabIdx by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val wide = LocalWide.current
    val live = remember(tick) { sim.pinStates() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pinscope", 0) }
    var muted by remember { mutableStateOf(prefs.getBoolean("simMuted", false)) }
    LaunchedEffect(sim, muted) { sim.muted = muted; prefs.edit().putBoolean("simMuted", muted).apply() }
    var partsOnly by remember { mutableStateOf(prefs.getBoolean("simPartsOnly", false)) }
    // buzzer sound only while this screen is in front
    LifecycleResumeEffect(sim) {
        val audio = BuzzerAudio(sim).also { it.start() }
        onPauseOrDispose { audio.stop() }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.saveSimParts(); vm.closeSim() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close the simulator") }
            Column(Modifier.weight(1f)) {
                Text("Simulator · ${sim.name}", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val t = sim.now / CLOCK
                Text(
                    (if (sim.running) "Running" else "Paused") + " · %d:%02d · ".format(t / 60, t % 60) + speedText(sim) + " · no real board needed",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { partsOnly = !partsOnly; prefs.edit().putBoolean("simPartsOnly", partsOnly).apply() }) {
                Icon(if (partsOnly) Icons.Rounded.DeveloperBoardOff else Icons.Rounded.DeveloperBoard, if (partsOnly) "Show the board" else "Show only the parts",
                    tint = if (partsOnly) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current)
            }
            SpeedMenu(sim)
            IconButton(onClick = { muted = !muted }) { Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (muted) "Unmute buzzers" else "Mute buzzers") }
            IconButton(onClick = { sim.running = !sim.running }) { Icon(if (sim.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (sim.running) "Pause" else "Run") }
            IconButton(onClick = { sim.reset() }) { Icon(Icons.Rounded.RestartAlt, "Reset the simulated board") }
        }
        val board: @Composable (Modifier) -> Unit = { m -> BoardDiagram(vm, live, m) }
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                PrimaryTabRow(selectedTabIndex = tabIdx, containerColor = Color.Transparent) {
                    Tab(tabIdx == 0, { tabIdx = 0 }, text = { Text("Parts · ${sim.parts.size}") })
                    Tab(tabIdx == 1, { tabIdx = 1 }, text = { Text("Serial") })
                }
                Box(Modifier.weight(1f)) {
                    if (tabIdx == 0) PartsPanel(vm, sim, tick, warnings) { adding = true } else SimSerial(sim, tick)
                }
            }
        }
        if (partsOnly) panel(Modifier.weight(1f).fillMaxWidth())
        else if (wide) Row(Modifier.weight(1f).fillMaxWidth()) {
            board(Modifier.weight(1f).fillMaxHeight().padding(12.dp))
            panel(Modifier.weight(1f).fillMaxHeight())
        } else {
            board(Modifier.fillMaxWidth().height(260.dp).padding(8.dp))
            panel(Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text("Add a part", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Parts start on the pins their ELEGOO kit lesson uses; tap a part's pins to change them.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyVerticalGrid(GridCells.Adaptive(120.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(PartType.entries) { t ->
                Surface(onClick = { sim.addPart(createPart(t, defaultPins(t))); adding = false; tabIdx = 0; vm.saveSimPartsLater(sim) },
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        PartThumb(t, Modifier.height(64.dp).fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(t.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun PartThumb(t: PartType, modifier: Modifier) {
    val img = art(t.art)
    if (img != null) Image(img, t.title, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    else Canvas(modifier) { drawFallback(t) }
}

// Simple drawings for parts the Fritzing library lacks
private fun DrawScope.drawFallback(t: PartType) {
    val w = size.minDimension; val c = center
    when (t) {
        PartType.DHT11 -> { drawRoundRect(Color(0xFF3B82F6), Offset(c.x - w * 0.3f, c.y - w * 0.45f), Size(w * 0.6f, w * 0.8f), androidx.compose.ui.geometry.CornerRadius(8f))
            for (i in 0 until 5) for (j in 0 until 3) drawCircle(Color(0xFF1E3A8A), w * 0.04f, Offset(c.x - w * 0.15f + j * w * 0.15f, c.y - w * 0.3f + i * w * 0.12f)) }
        PartType.WATER -> { drawRoundRect(Color(0xFFDC2626), Offset(c.x - w * 0.25f, c.y - w * 0.45f), Size(w * 0.5f, w * 0.9f), androidx.compose.ui.geometry.CornerRadius(6f))
            for (i in 0 until 5) drawRect(Color(0xFFE5C07B), Offset(c.x - w * 0.18f + i * w * 0.08f, c.y - w * 0.3f), Size(w * 0.03f, w * 0.6f)) }
        PartType.SOUND -> { drawRoundRect(Color(0xFF1D4ED8), Offset(c.x - w * 0.4f, c.y - w * 0.2f), Size(w * 0.8f, w * 0.4f), androidx.compose.ui.geometry.CornerRadius(6f))
            drawCircle(Color(0xFF9CA3AF), w * 0.15f, Offset(c.x - w * 0.22f, c.y)); drawCircle(Color(0xFF111827), w * 0.1f, Offset(c.x - w * 0.22f, c.y)) }
        PartType.KEYPAD -> for (r in 0 until 4) for (k in 0 until 4) drawRoundRect(if (k == 3) Color(0xFFDC2626) else Color(0xFF2563EB),
            Offset(c.x - w * 0.4f + k * w * 0.2f, c.y - w * 0.4f + r * w * 0.2f), Size(w * 0.17f, w * 0.17f), androidx.compose.ui.geometry.CornerRadius(4f))
        PartType.SCOPE -> {
            drawRoundRect(Color(0xFF374151), Offset(c.x - w * 0.45f, c.y - w * 0.32f), Size(w * 0.9f, w * 0.64f), androidx.compose.ui.geometry.CornerRadius(6f))
            drawRect(Color(0xFF05090D), Offset(c.x - w * 0.38f, c.y - w * 0.25f), Size(w * 0.56f, w * 0.5f))
            val path = Path(); for (i in 0..20) { val x = c.x - w * 0.38f + i * w * 0.028f; val y = c.y - (if ((i / 5) % 2 == 0) 0.12f else -0.12f) * w; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, Color(0xFF4ADE80), style = Stroke(2f))
            for (k in 0 until 3) drawCircle(Color(0xFF9CA3AF), w * 0.04f, Offset(c.x + w * 0.31f, c.y - w * 0.18f + k * w * 0.18f))
        }
        PartType.RC -> {
            drawLine(Color.Gray, Offset(c.x - w * 0.45f, c.y - w * 0.15f), Offset(c.x + w * 0.45f, c.y - w * 0.15f), 3f)
            drawRect(Color(0xFFD6B98C), Offset(c.x - w * 0.32f, c.y - w * 0.23f), Size(w * 0.3f, w * 0.16f))
            for (k in 0 until 3) drawRect(listOf(Color(0xFF8B4513), Color.Black, Color.Red)[k], Offset(c.x - w * 0.28f + k * w * 0.08f, c.y - w * 0.23f), Size(w * 0.03f, w * 0.16f))
            drawLine(Color.Gray, Offset(c.x + w * 0.2f, c.y - w * 0.15f), Offset(c.x + w * 0.2f, c.y + w * 0.05f), 3f)
            drawLine(Color(0xFF60A5FA), Offset(c.x + w * 0.08f, c.y + w * 0.05f), Offset(c.x + w * 0.32f, c.y + w * 0.05f), 5f)
            drawLine(Color(0xFF60A5FA), Offset(c.x + w * 0.08f, c.y + w * 0.13f), Offset(c.x + w * 0.32f, c.y + w * 0.13f), 5f)
            drawLine(Color.Gray, Offset(c.x + w * 0.2f, c.y + w * 0.13f), Offset(c.x + w * 0.2f, c.y + w * 0.35f), 3f)
        }
        else -> drawCircle(Color.Gray, w * 0.3f, c)
    }
}

@Composable
private fun PartsPanel(vm: AppViewModel, sim: Simulator, tick: Long, warnings: List<String>, onAdd: () -> Unit) {
    var editing by remember { mutableStateOf<SimPart?>(null) }
    LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(360.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalItemSpacing = 10.dp, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        gridItems(warnings, span = { StaggeredGridItemSpan.FullLine }) { w ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)) {
                Row(Modifier.padding(12.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text(w, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (sim.parts.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            Text("No parts yet. Add the sensors, buttons and displays your sketch expects; they behave like the real ones, so the sketch can be tried without risking a board.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        gridItems(sim.parts.toList(), key = { it.id }) { p -> PartCard(sim, p, tick, onPins = { editing = p }, onRemove = { sim.removePart(p); vm.saveSimPartsLater(sim) }) }
        item(span = StaggeredGridItemSpan.FullLine) {
            Button(onClick = onAdd, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add a part")
            }
        }
    }
    editing?.let { p -> PinDialog(sim, p) { editing = null; vm.saveSimPartsLater(sim) } }
}

@Composable
private fun PinDialog(sim: Simulator, p: SimPart, onDone: () -> Unit) {
    val pins = remember { mutableStateOf(p.pins.toList()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("${p.type.title} pins") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.type.roles.forEachIndexed { i, role ->
                    val choices = if (p.type.isAnalogRole(i)) (54..69).toList() else (2..69).toList()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(role, modifier = Modifier.weight(1f))
                        IconButton(onClick = { val l = pins.value.toMutableList(); val k = choices.indexOf(l[i]); l[i] = choices[(k - 1 + choices.size) % choices.size]; pins.value = l }) { Text("−", fontSize = 20.sp) }
                        PinBadge(sim.pinName(pins.value[i]))
                        IconButton(onClick = { val l = pins.value.toMutableList(); val k = choices.indexOf(l[i]); l[i] = choices[(k + 1) % choices.size]; pins.value = l }) { Text("+", fontSize = 20.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                sim.post { p.pins.forEach { sim.drive(it, null) }; pins.value.forEachIndexed { i, n -> p.pins[i] = n }; sim.rewire(); p.attach() }
                onDone()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun PartCard(sim: Simulator, p: SimPart, tick: Long, onPins: () -> Unit, onRemove: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PartThumb(p.type, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.type.title, style = MaterialTheme.typography.titleSmall)
                    FlowRow(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPins), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        p.type.roles.forEachIndexed { i, r -> Text("$r ${sim.pinName(p.pins[i])}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove ${p.type.title}") }
            }
            Spacer(Modifier.height(8.dp))
            PartControl(sim, p, tick)
        }
    }
}

// What each part shows, and how it is operated
@Composable
private fun PartControl(sim: Simulator, p: SimPart, tick: Long) {
    tick.hashCode() // re-read the part's state on every frame
    val mono = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp)
    when (p) {
        is Led -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.type == PartType.RGB) {
                val b = p.bright
                Glow(Color(b.getOrElse(0) { 0f }, b.getOrElse(1) { 0f }, b.getOrElse(2) { 0f }), maxOf(b.getOrElse(0) { 0f }, b.getOrElse(1) { 0f }, b.getOrElse(2) { 0f }))
                Text("R %d%%  G %d%%  B %d%%".format((b[0] * 100).toInt(), (b[1] * 100).toInt(), (b[2] * 100).toInt()), style = mono)
            } else { Glow(Color(0xFFFF4D4D), p.bright[0]); Text(if (p.bright[0] < 0.01f) "Off" else "%d%%".format((p.bright[0] * 100).toInt()), style = mono) }
        }
        is Button -> if (p.type == PartType.TILT) Row(verticalAlignment = Alignment.CenterVertically) { Text("Tilted (switch closed)", Modifier.weight(1f)); Switch(p.pressed, { p.press(it) }) }
            else Column {
                HoldButton(if (p.pressed) "Pressed · pin reads ${if (p.toVcc) "HIGH" else "LOW"}" else "Hold to press") { p.press(it) }
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!p.toVcc, { p.wire(false) }, label = { Text("To GND (INPUT_PULLUP)") })
                    FilterChip(p.toVcc, { p.wire(true) }, label = { Text("To 5V + pull-down (INPUT)") })
                }
            }
        is AnalogPart -> {
            var v by remember { mutableStateOf(p.value) }
            val label = when (p.type) {
                PartType.THERMISTOR -> "Temperature %.1f °C".format(v * 50)
                PartType.PHOTO -> "Light %d%%".format((v * 100).toInt())
                PartType.WATER -> "Water level %d%%".format((v * 100).toInt())
                PartType.SOUND -> "Loudness %d%%".format((v * 100).toInt()) + if (v > 0.5f) " · DO HIGH" else ""
                else -> "Wiper %.2f V".format(v * 5)
            }
            Text(label, style = mono)
            Slider(v, { v = it; p.apply(it) })
        }
        is Joystick -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .pointerInput(p) { detectDragGestures(onDragEnd = { p.move(0.5f, 0.5f) }) { ch, _ -> p.move((ch.position.x / size.width).coerceIn(0f, 1f), (ch.position.y / size.height).coerceIn(0f, 1f)) } }) {
                Canvas(Modifier.fillMaxSize()) { tick.hashCode(); drawCircle(Color(0xFF60A5FA), 14.dp.toPx(), Offset(p.x * size.width, p.y * size.height)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("X %d · Y %d".format((p.x * 1023).toInt(), (p.y * 1023).toInt()), style = mono)
                HoldButton(if (p.pressed) "SW pressed" else "Press SW") { p.press(it) }
            }
        }
        is Pir -> Row(verticalAlignment = Alignment.CenterVertically) { Text("Motion in front of the sensor", Modifier.weight(1f)); Switch(p.motion, { p.trigger(it) }) }
        is Meter -> if (p.type == PartType.SERVO) Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(90.dp, 50.dp)) {
                tick.hashCode()
                val cc = Offset(size.width / 2, size.height); val r = size.height * 0.9f
                drawArc(Color.DarkGray, 180f, 180f, false, Offset(cc.x - r, cc.y - r), Size(r * 2, r * 2), style = Stroke(4f))
                if (p.angle >= 0) { val a = Math.toRadians(180.0 + p.angle); drawLine(Color(0xFFFFC95C), cc, Offset(cc.x + (r * Math.cos(a)).toFloat(), cc.y + (r * Math.sin(a)).toFloat()), 6f) }
            }
            Spacer(Modifier.width(12.dp))
            Text(if (p.angle < 0) "No signal" else "%d° · %d µs pulses".format(p.angle.roundToInt(), p.pulseUs.roundToInt()), style = mono)
        } else Text(when {
            p.freq > 20 -> "Sounding %.0f Hz (%s)".format(p.freq, note(p.freq))
            p.on -> "On (active buzzer)"
            else -> "Silent"
        }, style = mono)
        is Relay -> Text(if (p.closed) "Closed: the switched circuit is on" else "Open", style = mono)
        is Segments -> if (p.type == PartType.SHIFT595) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 8) Glow(Color(0xFFFF4D4D), ((p.latched shr i) and 1).toFloat(), 18.dp)
        } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val n = if (p.type == PartType.SEG7X4) 4 else 1
            for (d in 0 until n) Canvas(Modifier.size(36.dp, 56.dp)) { tick.hashCode(); drawDigit(p.segs[d]) }
        }
        is Lcd -> LcdView(p, tick)
        is Keypad -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 4) { val k = r * 4 + c; KeyCap(p.labels[k], p.pressedKey == k) { on -> p.press(if (on) k else -1) } }
            }
        }
        is Encoder -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { p.turn(-1) }) { Text("⟲ Left") }
            FilledTonalButton(onClick = { p.turn(1) }) { Text("Right ⟳") }
            Text("${p.position}", style = mono, modifier = Modifier.weight(1f))
            HoldButton("Press") { p.press(it) }
        }
        is Ultrasonic -> { var v by remember { mutableStateOf(p.cm) }
            Text("Object at %.0f cm%s · %d measurements".format(v, if (v > 400) " (out of range)" else "", p.pings), style = mono)
            Slider(v, { v = it; p.cm = it }, valueRange = 2f..450f) }
        is Dht11 -> { var t by remember { mutableStateOf(p.tempC) }; var h by remember { mutableStateOf(p.humidity) }
            Text("%.1f °C · %.0f%% humidity · read %d times".format(t, h, p.reads), style = mono)
            Slider(t, { t = it; p.tempC = it }, valueRange = 0f..50f); Slider(h, { h = it; p.humidity = it }, valueRange = 20f..90f) }
        is Matrix -> Canvas(Modifier.size(120.dp)) {
            tick.hashCode()
            val cell = size.width / 8
            for (r in 0 until 8) for (c in 0 until 8) {
                val on = !p.shutdown && (p.rows[r] shr (7 - c)) and 1 == 1
                drawCircle(if (on) Color(0xFFFF3B30).copy(alpha = 0.4f + p.intensity / 25f) else Color(0xFF3A2020), cell * 0.38f, Offset(c * cell + cell / 2, r * cell + cell / 2))
            }
        }
        is IrRemote -> Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                p.keys.forEach { (label, cmd) -> Surface(onClick = { p.send(label, cmd) }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp) } }
            }
            p.lastKey?.let { Text("Sent $it", style = mono, modifier = Modifier.padding(top = 4.dp)) }
        }
        is Stepper -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).rotate(p.angle).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.size(6.dp, 22.dp).background(Color(0xFFFFC95C)))
            }
            Spacer(Modifier.width(12.dp)); Text("${p.steps} half-steps · %.1f°".format(p.angle), style = mono)
        }
        is Motor -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).rotate(p.turn).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.size(6.dp, 22.dp).background(Color(0xFF60A5FA)))
            }
            Spacer(Modifier.width(12.dp))
            Text(when { p.speed > 0 -> "Forward %d%%".format((p.speed * 100).toInt()); p.speed < 0 -> "Reverse %d%%".format((-p.speed * 100).toInt()); else -> "Stopped" }, style = mono)
        }
        is Rtc -> Text("Clock: " + java.time.LocalDateTime.now().plusSeconds(p.offsetSec).toString().replace('T', ' ').take(19) + if (p.offsetSec != 0L) " (set by the sketch)" else " (this phone's time)", style = mono)
        is Mpu6050 -> MpuControl(p)
        is Scope -> ScopeView(sim, p, tick)
        is RcFilter -> RcControl(p, tick)
    }
    if (p is Button && p.type == PartType.BUTTON) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Bouncy contacts (like a real button)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(p.bouncy, { p.bouncy = it; p.version++ })
    }
}

private val SCOPE_SPANS = listOf(0.5f, 1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f)

// The probe part's screen: 10 divisions across, 0–5 V up, plus span, trigger and hold controls
@Composable
private fun ScopeView(sim: Simulator, p: Scope, tick: Long) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    var span by remember { mutableStateOf(p.spanMs) }
    var edge by remember { mutableIntStateOf(p.edge) }
    var level by remember { mutableStateOf(p.level) }
    var hold by remember { mutableStateOf(p.hold) }
    val tr = p.trace
    Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF05090D)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(2f).padding(8.dp)) {
            tick.hashCode()
            val w = size.width; val h = size.height
            for (i in 0..10) drawLine(grid.copy(alpha = if (i == 5) 0.6f else 0.25f), Offset(w * i / 10, 0f), Offset(w * i / 10, h), 1f)
            for (i in 0..5) drawLine(grid.copy(alpha = 0.25f), Offset(0f, h * i / 5), Offset(w, h * i / 5), 1f)
            val ls = androidx.compose.ui.text.TextStyle(color = Color.Gray, fontSize = 10.sp, fontFamily = Mono)
            val div = span / 10f
            val tl = measurer.measure(if (div < 1) "%.2f ms/div".format(div) else "%.0f ms/div".format(div), ls)
            drawText(tl, topLeft = Offset(w - tl.size.width - 4f, h - tl.size.height - 2f))
            if (edge != 0) drawLine(Color(0xFF4ADE80).copy(alpha = 0.5f), Offset(0f, h - level / 5f * h), Offset(w, h - level / 5f * h), 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            if (tr.size > 1) {
                val path = Path()
                tr.forEachIndexed { k, v -> val x = k.toFloat() / (tr.size - 1) * w; val y = h - (v / 5f).coerceIn(0f, 1f) * h; if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                drawPath(path, Color(0xFF4ADE80), style = Stroke(2.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
    if (tr.size > 1) {
        val lo = tr.min(); val hi = tr.max(); val mid = (lo + hi) / 2
        val ups = (1 until tr.size).filter { tr[it - 1] < mid && tr[it] >= mid }
        val freq = if (ups.size >= 2 && hi - lo > 0.3f) (ups.size - 1) / ((ups.last() - ups.first()) * span / 1000f / (tr.size - 1)) else null
        Text("%.2f–%.2f V · %.2f Vpp%s%s".format(lo, hi, hi - lo,
            freq?.let { if (it >= 1000) " · %.2f kHz".format(it / 1000) else " · %.1f Hz".format(it) } ?: "",
            if (edge != 0 && !p.triggered) " · waiting for an edge" else ""),
            fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SCOPE_SPANS.forEach { s -> FilterChip(s == span, { span = s; p.spanMs = s }, label = { Text(if (s < 1000) (if (s < 1) "%.1f ms" else "%.0f ms").format(s) else "1 s") }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1 to "Rising", 2 to "Falling", 0 to "Free").forEach { (e, n) -> FilterChip(edge == e, { edge = e; p.edge = e; p.triggered = false }, label = { Text(n) }) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { hold = !hold; p.hold = hold }) { Icon(if (hold) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (hold) "Run" else "Hold") }
    }
    if (edge != 0) {
        Text("Trigger level %.2f V".format(level), fontFamily = Mono, fontSize = 12.sp)
        Slider(level, { level = it; p.level = it }, valueRange = 0f..5f)
    }
}

private val RC_R = listOf(1_000.0 to "1 kΩ", 10_000.0 to "10 kΩ", 100_000.0 to "100 kΩ")
private val RC_C = listOf(100e-9 to "100 nF", 1e-6 to "1 µF", 10e-6 to "10 µF", 100e-6 to "100 µF")

@Composable
private fun RcControl(p: RcFilter, tick: Long) {
    tick.hashCode()
    var r by remember { mutableStateOf(p.ohms) }
    var c by remember { mutableStateOf(p.farads) }
    val tau = r * c * 1000
    Text("Out %.2f V · τ = R × C = %s".format(p.vOut, if (tau >= 1) "%.0f ms".format(tau) else "%.2f ms".format(tau)), fontFamily = Mono, fontSize = 13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RC_R.forEach { (v, n) -> FilterChip(v == r, { r = v; p.setValues(r, c) }, label = { Text(n) }) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RC_C.forEach { (v, n) -> FilterChip(v == c, { c = v; p.setValues(r, c) }, label = { Text(n) }) }
    }
}

@Composable
private fun MpuControl(p: Mpu6050) {
    val ctx = LocalContext.current
    var phone by remember { mutableStateOf(false) }
    var ax by remember { mutableStateOf(p.ax) }; var ay by remember { mutableStateOf(p.ay) }
    DisposableEffect(phone) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) { p.ax = -e.values[0] / 9.81f; p.ay = e.values[1] / 9.81f; p.az = e.values[2] / 9.81f; ax = p.ax; ay = p.ay }
                if (e.sensor.type == Sensor.TYPE_GYROSCOPE) { p.gx = Math.toDegrees(e.values[0].toDouble()).toFloat(); p.gy = Math.toDegrees(e.values[1].toDouble()).toFloat(); p.gz = Math.toDegrees(e.values[2].toDouble()).toFloat() }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (phone) { sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }; sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) } }
        onDispose { sm?.unregisterListener(l) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Use this device's motion sensors", Modifier.weight(1f)); Switch(phone, { phone = it; if (!it) { p.gx = 0f; p.gy = 0f; p.gz = 0f } }) }
    Text("Accel X %.2f g · Y %.2f g · Z %.2f g".format(p.ax, p.ay, p.az), style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp))
    if (!phone) {
        Slider(ax, { ax = it; p.ax = it; p.az = kotlin.math.sqrt((1 - it * it - p.ay * p.ay).coerceAtLeast(0f)) }, valueRange = -1f..1f)
        Slider(ay, { ay = it; p.ay = it; p.az = kotlin.math.sqrt((1 - p.ax * p.ax - it * it).coerceAtLeast(0f)) }, valueRange = -1f..1f)
    }
}

@Composable
private fun LcdView(p: Lcd, tick: Long) {
    var c by remember { mutableStateOf(p.contrast) }
    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF1F3A93)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 2.6f).padding(8.dp)) {
            tick.hashCode() // redraw every frame
            val cw = size.width / 16; val ch = size.height / 2
            // like a real HD44780: too little contrast and the text fades away; too much and every cell shows as a dark block
            val ink = ((c - 0.15f) / 0.45f).coerceIn(0f, 1f)
            val boxes = ((c - 0.8f) / 0.2f).coerceIn(0f, 1f)
            val off = Color(0xFF2A4AA8)
            val onCol = androidx.compose.ui.graphics.lerp(off, Color(0xFFE8F0FF), ink)
            val offCol = androidx.compose.ui.graphics.lerp(off, Color(0xFF0D1C55), boxes)
            for (r in 0 until 2) for ((i, code) in p.line(r).withIndex()) {
                val x0 = i * cw; val y0 = r * ch
                val rows = if (code < 8) IntArray(8) { p.cgram[code * 8 + it] } else glyph(code)
                val px = cw / 6; val py = ch / 9
                for (yy in 0 until 8) for (xx in 0 until 5) {
                    val on = p.displayOn && (rows[yy] shr (4 - xx)) and 1 == 1
                    drawRect(if (on) onCol else offCol, Offset(x0 + xx * px + px * 0.5f, y0 + yy * py), Size(px * 0.85f, py * 0.85f))
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Contrast (V0 pot)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(130.dp))
        Slider(c, { c = it; p.contrast = it }, Modifier.weight(1f))
    }
}

// The HD44780's character ROM (A00), 0x20–0x7F as 5×7 columns, bit 0 at the top; 0x5C is ¥ and 0x7E/0x7F are → and ←
private val ROM = intArrayOf(
0x00,0x00,0x00,0x00,0x00, 0x00,0x00,0x5F,0x00,0x00, 0x00,0x07,0x00,0x07,0x00, 0x14,0x7F,0x14,0x7F,0x14, 0x24,0x2A,0x7F,0x2A,0x12, 0x23,0x13,0x08,0x64,0x62, 0x36,0x49,0x55,0x22,0x50, 0x00,0x05,0x03,0x00,0x00,
0x00,0x1C,0x22,0x41,0x00, 0x00,0x41,0x22,0x1C,0x00, 0x08,0x2A,0x1C,0x2A,0x08, 0x08,0x08,0x3E,0x08,0x08, 0x00,0x50,0x30,0x00,0x00, 0x08,0x08,0x08,0x08,0x08, 0x00,0x60,0x60,0x00,0x00, 0x20,0x10,0x08,0x04,0x02,
0x3E,0x51,0x49,0x45,0x3E, 0x00,0x42,0x7F,0x40,0x00, 0x42,0x61,0x51,0x49,0x46, 0x21,0x41,0x45,0x4B,0x31, 0x18,0x14,0x12,0x7F,0x10, 0x27,0x45,0x45,0x45,0x39, 0x3C,0x4A,0x49,0x49,0x30, 0x01,0x71,0x09,0x05,0x03,
0x36,0x49,0x49,0x49,0x36, 0x06,0x49,0x49,0x29,0x1E, 0x00,0x36,0x36,0x00,0x00, 0x00,0x56,0x36,0x00,0x00, 0x00,0x08,0x14,0x22,0x41, 0x14,0x14,0x14,0x14,0x14, 0x41,0x22,0x14,0x08,0x00, 0x02,0x01,0x51,0x09,0x06,
0x32,0x49,0x79,0x41,0x3E, 0x7E,0x11,0x11,0x11,0x7E, 0x7F,0x49,0x49,0x49,0x36, 0x3E,0x41,0x41,0x41,0x22, 0x7F,0x41,0x41,0x22,0x1C, 0x7F,0x49,0x49,0x49,0x41, 0x7F,0x09,0x09,0x01,0x01, 0x3E,0x41,0x41,0x51,0x32,
0x7F,0x08,0x08,0x08,0x7F, 0x00,0x41,0x7F,0x41,0x00, 0x20,0x40,0x41,0x3F,0x01, 0x7F,0x08,0x14,0x22,0x41, 0x7F,0x40,0x40,0x40,0x40, 0x7F,0x02,0x04,0x02,0x7F, 0x7F,0x04,0x08,0x10,0x7F, 0x3E,0x41,0x41,0x41,0x3E,
0x7F,0x09,0x09,0x09,0x06, 0x3E,0x41,0x51,0x21,0x5E, 0x7F,0x09,0x19,0x29,0x46, 0x46,0x49,0x49,0x49,0x31, 0x01,0x01,0x7F,0x01,0x01, 0x3F,0x40,0x40,0x40,0x3F, 0x1F,0x20,0x40,0x20,0x1F, 0x7F,0x20,0x18,0x20,0x7F,
0x63,0x14,0x08,0x14,0x63, 0x03,0x04,0x78,0x04,0x03, 0x61,0x51,0x49,0x45,0x43, 0x00,0x00,0x7F,0x41,0x41, 0x15,0x16,0x7C,0x16,0x15, 0x41,0x41,0x7F,0x00,0x00, 0x04,0x02,0x01,0x02,0x04, 0x40,0x40,0x40,0x40,0x40,
0x00,0x01,0x02,0x04,0x00, 0x20,0x54,0x54,0x54,0x78, 0x7F,0x48,0x44,0x44,0x38, 0x38,0x44,0x44,0x44,0x20, 0x38,0x44,0x44,0x48,0x7F, 0x38,0x54,0x54,0x54,0x18, 0x08,0x7E,0x09,0x01,0x02, 0x08,0x14,0x54,0x54,0x3C,
0x7F,0x08,0x04,0x04,0x78, 0x00,0x44,0x7D,0x40,0x00, 0x20,0x40,0x44,0x3D,0x00, 0x00,0x7F,0x10,0x28,0x44, 0x00,0x41,0x7F,0x40,0x00, 0x7C,0x04,0x18,0x04,0x78, 0x7C,0x08,0x04,0x04,0x78, 0x38,0x44,0x44,0x44,0x38,
0x7C,0x14,0x14,0x14,0x08, 0x08,0x14,0x14,0x18,0x7C, 0x7C,0x08,0x04,0x04,0x08, 0x48,0x54,0x54,0x54,0x20, 0x04,0x3F,0x44,0x40,0x20, 0x3C,0x40,0x40,0x20,0x7C, 0x1C,0x20,0x40,0x20,0x1C, 0x3C,0x40,0x30,0x40,0x3C,
0x44,0x28,0x10,0x28,0x44, 0x0C,0x50,0x50,0x50,0x3C, 0x44,0x64,0x54,0x4C,0x44, 0x00,0x08,0x36,0x41,0x00, 0x00,0x00,0x7F,0x00,0x00, 0x00,0x41,0x36,0x08,0x00, 0x08,0x08,0x2A,0x1C,0x08, 0x08,0x1C,0x2A,0x08,0x08
)
private fun glyph(code: Int): IntArray {
    if (code !in 0x20..0x7F) return IntArray(8)
    val o = (code - 0x20) * 5
    return IntArray(8) { y -> (0 until 5).fold(0) { a, x -> a or (((ROM[o + x] shr y) and 1) shl (4 - x)) } }
}

private fun DrawScope.drawDigit(segs: Int) {
    val w = size.width; val h = size.height; val t = w * 0.16f
    fun seg(i: Int) = if ((segs shr i) and 1 == 1) Color(0xFFFF3B30) else Color(0xFF3A2020)
    drawRect(seg(0), Offset(t, 0f), Size(w - 2 * t, t))                 // A
    drawRect(seg(1), Offset(w - t, t), Size(t, h / 2 - t))              // B
    drawRect(seg(2), Offset(w - t, h / 2), Size(t, h / 2 - t))          // C
    drawRect(seg(3), Offset(t, h - t), Size(w - 2 * t, t))              // D
    drawRect(seg(4), Offset(0f, h / 2), Size(t, h / 2 - t))             // E
    drawRect(seg(5), Offset(0f, t), Size(t, h / 2 - t))                 // F
    drawRect(seg(6), Offset(t, h / 2 - t / 2), Size(w - 2 * t, t))      // G
    drawCircle(seg(7), t * 0.6f, Offset(w + t * 0.2f, h - t / 2))       // DP
}

@Composable
private fun Glow(color: Color, level: Float, s: androidx.compose.ui.unit.Dp = 28.dp) {
    Canvas(Modifier.size(s)) {
        level.hashCode()
        drawCircle(color.copy(alpha = 0.12f + 0.88f * level), size.minDimension / 2)
        if (level > 0.05f) drawCircle(color.copy(alpha = 0.35f * level), size.minDimension / 2 * 1.0f, style = Stroke(size.minDimension * 0.2f))
    }
}

@Composable
private fun HoldButton(label: String, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(50), color = if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(); down = true; onPress(true)
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                down = false; onPress(false)
            }
        },
    ) { Text(label, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = if (down) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer) }
}

@Composable
private fun KeyCap(label: String, down: Boolean, onPress: (Boolean) -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
        .pointerInput(label) { awaitEachGesture { awaitFirstDown(); onPress(true); do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed }); onPress(false) } },
        contentAlignment = Alignment.Center) { Text(label, fontWeight = FontWeight.Bold, color = if (down) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
}

private fun note(hz: Double): String {
    val n = (12 * log2(hz / 440.0)).roundToInt() + 57
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[((n % 12) + 12) % 12] + (n / 12)
}

@Composable
private fun SimSerial(sim: Simulator, tick: Long) {
    var text by remember { mutableStateOf("") }
    var plot by rememberSaveable { mutableStateOf(false) }
    val lines = remember(sim.serialVersion, tick) { sim.serialText().takeLast(6000).lines().takeLast(200) }
    val state = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull(), plot) { if (!plot && lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!plot, { plot = false }, label = { Text("Monitor") })
            FilterChip(plot, { plot = true }, label = { Text("Plotter") })
        }
        if (plot) {
            val data = remember(sim.plotVersion, tick) { sim.plot.snapshot() }
            PlotChart(data, "Print numbers to plot them, for example\nSerial.print(\"temp:\"); Serial.println(t);")
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = state) {
            items(lines) { Text(it, fontFamily = Mono, fontSize = 13.sp) }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Send to the sketch") }, singleLine = true, shape = RoundedCornerShape(50))
            IconButton(onClick = { sim.send(text + "\n"); text = "" }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
        }
    }
}

private val RATES = listOf(0.25f to "¼×", 0.5f to "½×", 1f to "1×", 2f to "2×", 4f to "4×", 0f to "Max")

// "real time", or what the device actually manages when it can't keep up with the chosen speed
private fun speedText(sim: Simulator): String {
    val pct = (sim.speed * 100).roundToInt()
    val want = sim.rate
    return when {
        want == 1f && pct >= 97 -> "real time"
        want > 0f && sim.speed >= want * 0.97f -> RATES.first { it.first == want }.second + " speed"
        else -> "%d%% of real time".format(pct)
    }
}

@Composable
private fun SpeedMenu(sim: Simulator) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(RATES.firstOrNull { it.first == sim.rate }?.second ?: "1×", fontFamily = Mono, fontWeight = FontWeight.Bold) }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RATES.forEach { (r, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label + when (r) { 0.25f -> "  slow motion"; 1f -> "  real time"; 0f -> "  as fast as possible"; else -> "" }) },
                    onClick = { sim.rate = r; open = false },
                )
            }
        }
    }
}
