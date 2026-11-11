@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// Ready-made bench instruments built on Firmata: each sets up its pins, explains the wiring, and shows a live view.

enum class Instrument(val title: String, val blurb: String, val icon: ImageVector) {
    VOLTMETER("Voltmeter", "Read a voltage on any analog pin", Icons.Rounded.Bolt),
    SCOPE("Oscilloscope", "Watch up to four slow signals", Icons.Rounded.GraphicEq),
    LOGIC("Logic monitor", "See buttons and digital signals over time", Icons.Rounded.SettingsInputComponent),
    PWM("PWM generator", "Set a duty cycle on a PWM pin", Icons.Rounded.Speed),
    SERVO("Servo tester", "Move a servo by angle or sweep it", Icons.Rounded.Tune),
}

private val TRACE = listOf(Color(0xFFFFC95C), Color(0xFF22D3EE), Color(0xFF4ADE80), Color(0xFFFB923C))
private val PWM_PINS = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 44, 45, 46)

@Composable
fun InstrumentRow(vm: AppViewModel) {
    Column(Modifier.padding(top = 12.dp)) {
        Eyebrow("Instruments", Modifier.padding(start = 20.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Instrument.entries.forEachIndexed { i, inst ->
                Surface(onClick = { vm.instrument = inst }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(150.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        val shape = listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Pill, MaterialShapes.SoftBurst)[i % 5].toShape()
                        Box(Modifier.size(44.dp).clip(shape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(inst.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(inst.title, style = MaterialTheme.typography.titleSmall)
                        Text(inst.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun InstrumentScreen(vm: AppViewModel, inst: Instrument, ready: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(inst.title, style = MaterialTheme.typography.titleLarge)
                Text(inst.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val banner: @Composable () -> Unit = {
        if (!ready && inst != Instrument.SCOPE && !LocalWide.current) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text("Connect in Firmata mode to use this. The wiring steps below work the same either way.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(14.dp))
        }
    }
    val body: @Composable () -> Unit = {
        when (inst) {
            Instrument.VOLTMETER -> Voltmeter(vm, ready)
            Instrument.SCOPE -> Oscilloscope(vm, ready)
            Instrument.LOGIC -> LogicMonitor(vm, ready)
            Instrument.PWM -> PwmGenerator(vm, ready)
            Instrument.SERVO -> ServoTester(vm, ready)
        }
    }
    if (LocalWide.current) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            header(); banner()
            Box(Modifier.weight(1f)) { body() }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { header() }
            item { banner() }
            item { body() }
        }
    }
}

// Phones: the display, then its controls, in one column. Wide screens: the display on the left and the
// controls on the right, each scrolling on its own, so the display always stays in view.
@Composable
private fun Panes(display: @Composable ColumnScope.() -> Unit, controls: @Composable ColumnScope.() -> Unit) {
    if (LocalWide.current) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.35f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = display)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = controls)
        }
    } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { display(); controls() }
}

// ---------- shared pieces ----------

@Composable
private fun Wiring(steps: List<String>, warning: String? = null) {
    Card {
        Text("How to wire it", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        steps.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
        warning?.let {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)).padding(10.dp)) {
                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun <T> ChipPicker(label: String, options: List<T>, selected: (T) -> Boolean, name: (T) -> String, onPick: (T) -> Unit) {
    Column {
        Eyebrow(label, Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { o -> FilterChip(selected = selected(o), onClick = { onPick(o) }, label = { Text(name(o), fontFamily = Mono) }) }
        }
    }
}

// Keeps the chosen analog channels in ANALOG mode while the instrument is open
@Composable
private fun UseAnalog(vm: AppViewModel, ready: Boolean, channels: List<Int>, samplingMs: Int) {
    LaunchedEffect(ready, channels, samplingMs) {
        if (!ready) return@LaunchedEffect
        channels.forEach { vm.bench.setPinMode(54 + it, PinMode.ANALOG) }
        vm.bench.setSampling(samplingMs)
    }
}

// Re-reads the bench history several times a second
@Composable
private fun ticker(ms: Long = 100): Long {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(ms); t = System.currentTimeMillis() } }
    return t
}

// ---------- voltmeter ----------

private data class Divider(val name: String, val ratio: Float, val max: String)
private val DIVIDERS = listOf(
    Divider("Direct", 1f, "0–5 V"), Divider("÷2", 2f, "up to 10 V"),
    Divider("÷3", 3f, "up to 15 V"), Divider("÷5", 5f, "up to 25 V"),
)

@Composable
private fun Voltmeter(vm: AppViewModel, ready: Boolean) {
    var ch by remember { mutableIntStateOf(0) }
    var div by remember { mutableStateOf(DIVIDERS[0]) }
    var resetAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    UseAnalog(vm, ready, listOf(ch), 50)
    val now = ticker(120)
    val samples = remember(now, ch) { vm.bench.history.analog(ch) }
    val recent = samples.filter { it.first >= resetAt }
    // Readings are relative to the board's own 5V rail. Over a phone's USB it is often 4.5–4.8 V, so the
    // real reference voltage is calibrated once against a known voltage and saved.
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("pinscope", 0)
    var vref by remember { mutableFloatStateOf(prefs.getFloat("vref", 5f)) }
    var calibrating by remember { mutableStateOf(false) }
    val toV = { raw: Int -> raw * vref / 1023f * div.ratio }
    val last = samples.lastOrNull()?.let { toV(it.second) }
    val c = LocalPinColors.current
    Panes(display = {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("A$ch", fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(last?.let { "%.2f".format(it) } ?: "–.––", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 64.sp)
                Text("volts", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(((last ?: 0f) / (5f * div.ratio)).coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(c.forTag("analog")))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val vs = recent.map { toV(it.second) }
                    Stat("Min", vs.minOrNull()); Stat("Avg", vs.takeIf { it.isNotEmpty() }?.average()?.toFloat()); Stat("Max", vs.maxOrNull())
                    IconButton(onClick = { resetAt = System.currentTimeMillis() }) { Icon(Icons.Rounded.RestartAlt, "Reset min and max") }
                }
                samples.lastOrNull()?.let { Text("Raw ${it.second} of 1023", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }, controls = {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Calibration", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (vref == 5f) "Not calibrated: assumes the board's 5V pin is exactly 5.00 V. Powered from a phone it is often lower, which makes readings high."
                        else "Board reference %.3f V. Recalibrate if the board is powered differently.".format(vref),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vref != 5f) androidx.compose.material3.TextButton(onClick = { vref = 5f; prefs.edit().remove("vref").apply() }) { Text("Reset") }
                androidx.compose.material3.FilledTonalButton(onClick = { calibrating = true }, enabled = samples.isNotEmpty()) { Text("Calibrate") }
            }
        }
        Card {
            ChipPicker("Input pin", (0..15).toList(), { it == ch }, { "A$it" }) { ch = it; resetAt = System.currentTimeMillis() }
            Spacer(Modifier.height(10.dp))
            ChipPicker("Voltage divider", DIVIDERS, { it == div }, { "${it.name} · ${it.max}" }) { div = it; resetAt = System.currentTimeMillis() }
        }
        Wiring(
            if (div.ratio == 1f) listOf("Connect the circuit's ground to a GND pin on the Mega.", "Connect the point to measure to A$ch.", "Keep it between 0 and 5 V.")
            else listOf(
                "Make a divider: resistor R1 from the point to measure to A$ch, and R2 from A$ch to GND.",
                when (div.ratio) { 2f -> "÷2: R1 = 10 kΩ, R2 = 10 kΩ."; 3f -> "÷3: R1 = 20 kΩ (two 10 kΩ in series), R2 = 10 kΩ."; else -> "÷5: R1 = 40 kΩ (four 10 kΩ in series), R2 = 10 kΩ." },
                "Connect the circuit's ground to a GND pin on the Mega.",
                "Readings are scaled back up, so the number shows the real voltage.",
            ),
            "Never put more than 5 V, or any negative voltage, directly on a pin. Only measure low-voltage DC that shares ground with the board; never mains.",
        )
    })
    if (calibrating) {
        var text by remember { mutableStateOf("") }
        val raw = samples.takeLast(10).map { it.second }.average().toFloat()
        val actual = text.replace(',', '.').toFloatOrNull()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { calibrating = false },
            title = { Text("Calibrate the voltmeter") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Measure the same point with a multimeter (or use a fresh battery whose voltage you know) and enter the real voltage. The board's 5V pin also works: connect it to A$ch with the divider set to Direct and enter what a multimeter reads there.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Pinscope reads %.2f V now".format(raw * vref / 1023f * div.ratio), fontFamily = Mono)
                    androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("Real voltage") }, suffix = { Text("V") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        // new reference: the voltage that would make this raw reading equal the real one
                        val v = actual!! * 1023f / (raw * div.ratio)
                        vref = v; prefs.edit().putFloat("vref", v).apply(); calibrating = false; resetAt = System.currentTimeMillis()
                    },
                    enabled = actual != null && actual > 0f && raw > 20f && (actual!! * 1023f / (raw * div.ratio)) in 4f..5.5f,
                ) { Text("Save") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { calibrating = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(label: String, v: Float?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v?.let { "%.2f".format(it) } ?: "–", fontFamily = Mono, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- oscilloscope ----------

@Composable
private fun Oscilloscope(vm: AppViewModel, ready: Boolean) {
    var fast by remember { mutableStateOf(false) }
    val wide = LocalWide.current
    Column(if (wide) Modifier.fillMaxSize() else Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.widthIn(max = 520.dp)) {
            ConnectedToggles(listOf("Fast · 1 channel", "Firmata · 4 channels"), if (fast) 0 else 1) { fast = it == 0; if (!fast) vm.bench.stopFastScope() }
        }
        Box(if (wide) Modifier.weight(1f) else Modifier) { if (fast) FastScope(vm) else SlowScope(vm, ready) }
    }
}

// ADC conversion rate for each prescaler setting (16 MHz / prescaler / 13 cycles)
private val ADC_RATES = mapOf(4 to 76923.0, 5 to 38461.0, 6 to 19230.0, 7 to 9615.0)

private fun planCapture(spanMs: Int, channel: Int, level: Int, edge: Int): ScopeRequest {
    val spanS = spanMs / 1000.0
    val wanted = 800 / spanS
    val ps = listOf(7, 6, 5, 4).firstOrNull { ADC_RATES.getValue(it) >= wanted } ?: 4
    val dec = (ADC_RATES.getValue(ps) / wanted).toInt().coerceIn(1, 255)
    val rate = ADC_RATES.getValue(ps) / dec
    val n = (rate * spanS).toInt().coerceIn(50, 6000)
    return ScopeRequest(channel, ps, dec, n, level, edge, timeoutMs = (spanMs * 2).coerceIn(60, 1500))
}

@Composable
private fun FastScope(vm: AppViewModel) {
    val cur = remember { ScopeCursors() }
    val bench = vm.bench
    val state by bench.scopeState.collectAsState()
    val frame by bench.scopeFrame.collectAsState()
    val upload by bench.upload.collectAsState()
    var ch by remember { mutableIntStateOf(0) }
    var span by remember { mutableIntStateOf(10) }
    var level by remember { mutableFloatStateOf(2.5f) }
    var edge by remember { mutableIntStateOf(1) }
    var hold by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<ScopeFrame?>(null) }
    LaunchedEffect(ch, span, level, edge) { bench.scopeRequest = planCapture(span, ch, (level / 5f * 255).roundToInt(), edge) }
    val f = if (hold) held else frame.also { held = it }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()

    Panes(display = {
    when (state) {
        FastScopeState.MISSING -> Card {
            Text("Fast mode needs the Pinscope scope sketch", style = MaterialTheme.typography.titleMedium)
            Text("It samples one analog pin up to about 77,000 times a second. The sketch on the board now is backed up first, so it can be restored from Sketches.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (upload is UploadState.Running) Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(8.dp)); Text((upload as UploadState.Running).step)
            } else androidx.compose.material3.Button(onClick = { bench.installFastScope() }, shapes = androidx.compose.material3.ButtonDefaults.shapes()) { Text("Upload the scope sketch") }
            if (upload is UploadState.Done) { Spacer(Modifier.height(8.dp)); androidx.compose.material3.FilledTonalButton(onClick = { bench.startFastScope() }) { Text("Start") } }
        }
        FastScopeState.OFF -> Card {
            Text("Fast mode reconnects the board at 1,000,000 baud and captures one pin at a time.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.Button(onClick = { bench.startFastScope() }, shapes = androidx.compose.material3.ButtonDefaults.shapes()) { Text("Start") }
        }
        FastScopeState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.LoadingIndicator(Modifier.size(36.dp)); Spacer(Modifier.width(10.dp)); Text("Connecting to the scope sketch…")
        }
        FastScopeState.RUNNING -> {}
    }

    Surface(shape = MaterialTheme.shapes.large, color = Color(0xFF05090D)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(if (LocalWide.current) 1.9f else 1.4f).padding(10.dp).scopeCursors(cur)) {
            val w = size.width; val h = size.height
            for (i in 0..10) drawLine(grid.copy(alpha = if (i == 5) 0.6f else 0.25f), Offset(w * i / 10, 0f), Offset(w * i / 10, h), 1f)
            for (i in 0..5) drawLine(grid.copy(alpha = 0.25f), Offset(0f, h * i / 5), Offset(w, h * i / 5), 1f)
            val ls = androidx.compose.ui.text.TextStyle(color = Color.Gray, fontSize = 10.sp, fontFamily = Mono)
            for (v in 0..5) { val t = measurer.measure("${v}V", ls); drawText(t, topLeft = Offset(4f, (h - v / 5f * h - t.size.height - 2f).coerceIn(0f, h - t.size.height))) }
            val tl = measurer.measure(if (span < 10) "%.1f ms/div".format(span / 10f) else if (span < 10000) "${span / 10} ms/div" else "${span / 10000} s/div", ls)
            drawText(tl, topLeft = Offset(w - tl.size.width - 4f, h - tl.size.height - 2f))
            if (edge != 0) drawLine(TRACE[0].copy(alpha = 0.5f), Offset(0f, h - level / 5f * h), Offset(w, h - level / 5f * h), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            val fr = f ?: return@Canvas
            val n = fr.samples.size
            val shownN = (fr.rateHz * span / 1000.0).toInt().coerceIn(2, n)
            val path = Path()
            for (k in 0 until shownN) {
                val v = fr.samples[k].toInt() and 0xFF
                val x = k.toFloat() / (shownN - 1) * w; val y = h - v / 255f * h
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, TRACE[0], style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            drawCursors(cur, measurer)
        }
    }
    CursorReadout(cur, span.toFloat())
    f?.let { fr ->
        val vs = fr.samples.map { (it.toInt() and 0xFF) * 5f / 255f }
        val mid = (vs.max() + vs.min()) / 2
        val ups = vs.indices.drop(1).filter { vs[it - 1] < mid && vs[it] >= mid }
        val freq = if (ups.size >= 2 && vs.max() - vs.min() > 0.3f) fr.rateHz * (ups.size - 1) / (ups.last() - ups.first()) else null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(TRACE[0]))
            Spacer(Modifier.width(8.dp))
            Text(
                "A$ch · %.2f–%.2f V · %.2f Vpp%s".format(vs.min(), vs.max(), vs.max() - vs.min(), freq?.let { if (it >= 1000) " · %.2f kHz".format(it / 1000) else " · %.1f Hz".format(it) } ?: ""),
                fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
        }
        Text("%s samples a second%s".format(if (fr.rateHz >= 1000) "%.1fk".format(fr.rateHz / 1000) else "%.0f".format(fr.rateHz), if (edge != 0 && !fr.triggered) " · waiting for the trigger" else ""),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }, controls = {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Time across the screen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { hold = !hold }) { Icon(if (hold) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (hold) "Run" else "Hold") }
        }
        ChipPicker("", listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000), { it == span }, { if (it < 1000) "$it ms" else "1 s" }) { span = it }
        Spacer(Modifier.height(8.dp))
        ChipPicker("Pin", (0..15).toList(), { it == ch }, { "A$it" }) { ch = it }
        Spacer(Modifier.height(8.dp))
        ChipPicker("Trigger", listOf(1, 2, 0), { it == edge }, { when (it) { 1 -> "Rising"; 2 -> "Falling"; else -> "Off" } }) { edge = it }
        if (edge != 0) {
            Text("Level %.2f V".format(level), fontFamily = Mono, fontSize = 13.sp)
            Slider(level, { level = it }, valueRange = 0f..5f)
        }
    }
    val g by bench.gen.collectAsState()
    var demo by remember { mutableStateOf<LabDemo?>(null) }
    Card {
        Text("Lab demos", style = MaterialTheme.typography.titleMedium)
        Text("Each sets up the generator and the scope for a classic experiment.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LAB_DEMOS.forEach { d ->
                FilterChip(selected = demo == d, onClick = {
                    demo = d; bench.setGen(d.gen); span = d.spanMs; edge = d.edge; level = d.level; ch = 0; hold = false
                }, label = { Text(d.title) })
            }
        }
        demo?.let { d ->
            Spacer(Modifier.height(6.dp))
            Text("Build", style = MaterialTheme.typography.titleSmall)
            d.wiring.forEachIndexed { i, w -> Text("${i + 1}. $w", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.height(6.dp))
            Text("What to see", style = MaterialTheme.typography.titleSmall)
            Text(d.expect, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Card {
        Text("Signal generator", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        ChipPicker("Square wave on pin 11", SQUARE_HZ, { it == g.squareHz }, { hzName(it) }) { bench.setGen(g.copy(squareHz = it)) }
        Spacer(Modifier.height(8.dp))
        Text("PWM on pin 9 (490 Hz): %d%% duty · %.2f V average".format((g.pwmDuty * 100 + 127) / 255, g.pwmDuty * 5f / 255), fontFamily = Mono, fontSize = 13.sp)
        Slider(g.pwmDuty.toFloat(), { bench.setGen(g.copy(pwmDuty = it.roundToInt())) }, valueRange = 0f..255f)
        ChipPicker("Wave on pin 6 (needs an RC filter)", listOf(0, 1, 2, 3), { it == g.waveShape }, { listOf("Off", "Sine", "Triangle", "Sawtooth")[it] }) { bench.setGen(g.copy(waveShape = it)) }
        if (g.waveShape != 0) {
            Spacer(Modifier.height(4.dp))
            ChipPicker("", WAVE_HZ, { it == g.waveHz }, { hzName(it) }) { bench.setGen(g.copy(waveHz = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pull-up on the scope pin (for buttons)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(g.pullUp, { bench.setGen(g.copy(pullUp = it)) })
        }
    }
    Wiring(
        listOf(
            "Connect the circuit's ground to a GND pin on the Mega, and the signal to the chosen analog pin.",
            "Self-test: pin 9 outputs PWM (25% at start). Join pin 9 to A0 with a jumper wire and it appears on screen.",
            "Then try a sound sensor's analog output, or a capacitor charging through a resistor.",
            "Firmata mode needs StandardFirmata again afterwards: Bench → Firmata pins → Upload StandardFirmata.",
        ),
        "Keep signals between 0 and 5 V. Readings are 8-bit (about 20 mV steps); at the fastest settings the ADC runs above its rated speed, so expect a little extra noise.",
    )
    })
}

private val SQUARE_HZ = listOf(0.0, 1.0, 10.0, 50.0, 100.0, 1000.0, 5000.0, 10000.0, 50000.0)
private val WAVE_HZ = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0)
private fun hzName(f: Double) = when {
    f == 0.0 -> "Off"
    f >= 1000 -> (if (f % 1000 == 0.0) "%.0f kHz" else "%.1f kHz").format(f / 1000)
    else -> "%.0f Hz".format(f)
}

// A ready-made experiment: generator settings, scope settings, how to build it and what it shows
private class LabDemo(val title: String, val gen: GenSettings, val spanMs: Int, val edge: Int, val level: Float, val wiring: List<String>, val expect: String)

private val LAB_DEMOS = listOf(
    LabDemo("PWM duty", GenSettings(pwmDuty = 64), 5, 1, 2.5f,
        listOf("Jumper from pin 9 to A0."),
        "A 490 Hz square wave, high for 25% of each 2.04 ms period. Move the PWM slider: the high part grows while the period stays the same. The average voltage is duty × 5 V."),
    LabDemo("Frequency", GenSettings(squareHz = 1000.0), 2, 1, 2.5f,
        listOf("Jumper from pin 11 to A0."),
        "A 1 kHz square wave. Tap two rising edges to place cursors A and B: Δt reads 1.00 ms and the frequency 1.00 kHz. Try other frequencies and spans."),
    LabDemo("RC charging", GenSettings(squareHz = 10.0), 100, 1, 1f,
        listOf("Pin 11 → 1 kΩ resistor → A0.", "10 µF capacitor from A0 to GND (stripe / short leg to GND).", "Optional: second jumper from pin 11 to A1 to compare the input."),
        "τ = R × C = 1 kΩ × 10 µF = 10 ms. The voltage climbs to 63% (3.16 V) after 10 ms and is nearly 5 V after 5τ = 50 ms, then falls the same way. Put cursor A at the start of the rise and B where it reaches 3.16 V: Δt ≈ 10 ms."),
    LabDemo("PWM to DC", GenSettings(pwmDuty = 128), 50, 0, 2.5f,
        listOf("Pin 9 → 10 kΩ resistor → A0.", "10 µF capacitor from A0 to GND."),
        "A low-pass filter averages the PWM into a nearly flat line at duty × 5 V (2.5 V at 50%), with a small ripple. Move the PWM slider: the line follows after a moment (τ = 100 ms). This is how a DAC can be made from PWM."),
    LabDemo("Sine wave", GenSettings(pwmDuty = 64, waveShape = 1, waveHz = 10.0), 200, 1, 2.5f,
        listOf("Pin 6 → 10 kΩ resistor → A0.", "100 nF capacitor (marked 104) from A0 to GND."),
        "Pin 6 is 62.5 kHz PWM whose duty follows a sine table. The RC filter (cutoff about 160 Hz) removes the PWM and leaves a 10 Hz sine between 0 and 5 V. Switch to triangle or sawtooth. Above the cutoff the wave shrinks and lags: that is the filter's response."),
    LabDemo("Button bounce", GenSettings(pullUp = true), 5, 2, 2.5f,
        listOf("Push button between A0 and GND. The internal pull-up holds A0 at 5 V."),
        "Press the button: the trigger catches the falling edge. Contacts bounce, so the line often jumps between 0 and 5 V several times in the first millisecond. Tap pause to keep a good capture. This is why sketches debounce buttons."),
    LabDemo("Aliasing", GenSettings(squareHz = 10000.0), 100, 1, 2.5f,
        listOf("Jumper from pin 11 to A0."),
        "At 100 ms across the screen the scope takes about 9,600 samples a second, too slow for a 10 kHz signal, so it shows a false slow wave (an alias). Switch to 1 ms across to see the real 10 kHz. The Nyquist rule: sample more than twice as fast as the signal."),
)

@Composable
private fun SlowScope(vm: AppViewModel, ready: Boolean) {
    val cur = remember { ScopeCursors() }
    var channels by remember { mutableStateOf(listOf(0)) }
    var interval by remember { mutableIntStateOf(10) }
    var span by remember { mutableIntStateOf(2000) } // ms across the screen
    var trigger by remember { mutableFloatStateOf(2.5f) }
    var useTrigger by remember { mutableStateOf(true) }
    var hold by remember { mutableStateOf(false) }
    UseAnalog(vm, ready, channels, interval)
    val now = ticker(60)
    var frozen by remember { mutableStateOf<Map<Int, List<Pair<Long, Int>>>>(emptyMap()) }
    val data = if (hold) frozen else remember(now, channels) { channels.associateWith { vm.bench.history.analog(it) } }.also { frozen = it }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()

    // window: end at the latest sample, or at a rising crossing of the trigger level on the first channel
    val first = data[channels.firstOrNull()].orEmpty()
    val end = first.lastOrNull()?.first ?: 0L
    var start = end - span
    if (useTrigger && first.size > 4) {
        val lvl = trigger / 5f * 1023f
        val crossing = first.zipWithNext().lastOrNull { (a, b) -> a.second < lvl && b.second >= lvl && b.first <= end - span / 2 }
        crossing?.let { start = it.second.first - span / 2 }
    }

    Panes(display = {
        if (!ready) Text("Firmata mode: connect in Firmata pins first. For faster signals, switch to Fast.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = Color(0xFF05090D)) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(if (LocalWide.current) 1.9f else 1.4f).padding(10.dp).scopeCursors(cur)) {
                val w = size.width; val h = size.height
                for (i in 0..10) drawLine(grid.copy(alpha = if (i == 5) 0.6f else 0.25f), Offset(w * i / 10, 0f), Offset(w * i / 10, h), 1f)
                for (i in 0..5) drawLine(grid.copy(alpha = 0.25f), Offset(0f, h * i / 5), Offset(w, h * i / 5), 1f)
                // axis labels: volts on the left, time at the bottom
                val ls = androidx.compose.ui.text.TextStyle(color = Color.Gray, fontSize = 10.sp, fontFamily = Mono)
                for (v in 0..5) {
                    val t = measurer.measure("${v}V", ls)
                    drawText(t, topLeft = Offset(4f, (h - v / 5f * h - t.size.height - 2f).coerceIn(0f, h - t.size.height)))
                }
                val tl = measurer.measure(if (span < 1000) "${span / 10} ms/div" else "%.1f s/div".format(span / 10000f), ls)
                drawText(tl, topLeft = Offset(w - tl.size.width - 4f, h - tl.size.height - 2f))
                if (useTrigger) drawLine(TRACE[0].copy(alpha = 0.5f), Offset(0f, h - trigger / 5f * h), Offset(w, h - trigger / 5f * h), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                channels.forEachIndexed { i, ch ->
                    val pts = data[ch].orEmpty().filter { it.first in start..(start + span) }
                    if (pts.size < 2) return@forEachIndexed
                    val path = Path()
                    pts.forEachIndexed { k, (t, v) ->
                        val x = (t - start).toFloat() / span * w; val y = h - v / 1023f * h
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, TRACE[i % 4], style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
                }
                drawCursors(cur, measurer)
            }
        }
        CursorReadout(cur, span.toFloat())
        // readouts per channel
        channels.forEachIndexed { i, ch ->
            val shown = data[ch].orEmpty().filter { it.first in start..(start + span) }
            val vs = shown.map { it.second * 5f / 1023f }
            val freq = shown.let { pts ->
                val mid = ((vs.maxOrNull() ?: 0f) + (vs.minOrNull() ?: 0f)) / 2 / 5f * 1023f
                val ups = pts.zipWithNext().filter { (a, b) -> a.second < mid && b.second >= mid }.map { it.second.first }
                if (ups.size >= 2 && (vs.maxOrNull() ?: 0f) - (vs.minOrNull() ?: 0f) > 0.3f) 1000f * (ups.size - 1) / (ups.last() - ups.first()) else null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(TRACE[i % 4]))
                Spacer(Modifier.width(8.dp))
                Text("A$ch", fontFamily = Mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                Text(
                    if (vs.isEmpty()) "no data" else "%.2f–%.2f V · %.2f Vpp%s".format(vs.min(), vs.max(), vs.max() - vs.min(), freq?.let { " · %.1f Hz".format(it) } ?: ""),
                    fontFamily = Mono, fontSize = 13.sp,
                )
            }
        }
    }, controls = {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${span / 10} ms per division · sampling every $interval ms", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { hold = !hold }) { Icon(if (hold) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (hold) "Run" else "Hold") }
            }
            ChipPicker("Channels", (0..7).toList(), { it in channels }, { "A$it" }) { c ->
                channels = if (c in channels) (channels - c).ifEmpty { listOf(c) } else (channels + c).takeLast(4)
            }
            Spacer(Modifier.height(8.dp))
            ChipPicker("Time across the screen", listOf(500, 1000, 2000, 5000, 10000), { it == span }, { if (it < 1000) "$it ms" else "${it / 1000} s" }) { span = it }
            Spacer(Modifier.height(8.dp))
            ChipPicker("Sampling", listOf(5, 10, 20, 50), { it == interval }, { "$it ms" }) { interval = it }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trigger on rising edge", modifier = Modifier.weight(1f))
                Switch(useTrigger, { useTrigger = it })
            }
            if (useTrigger) {
                Text("Level %.2f V".format(trigger), fontFamily = Mono, fontSize = 13.sp)
                Slider(trigger, { trigger = it }, valueRange = 0f..5f)
            }
        }
        Wiring(
            listOf(
                "Connect the circuit's ground to a GND pin on the Mega.",
                "Connect each signal to an analog pin: A0 is the yellow trace, then cyan, green and orange.",
                "Use the trigger so a repeating wave stands still, and Hold to freeze it.",
                "Try it: a potentiometer (ends to 5V and GND, middle to A0), or the PWM generator on pin 9 through 10 kΩ to A0 with 10 µF from A0 to GND, which smooths the pulses into a voltage.",
            ),
            "Signals must stay between 0 and 5 V. Firmata over USB samples a few hundred times a second at best, so this shows slow signals (up to roughly 20–50 Hz), not audio.",
        )
    })
}

// ---------- logic monitor ----------

@Composable
private fun LogicMonitor(vm: AppViewModel, ready: Boolean) {
    var pins by remember { mutableStateOf(listOf(2, 3, 4, 5)) }
    var pullup by remember { mutableStateOf(true) }
    LaunchedEffect(ready, pins, pullup) { if (ready) pins.forEach { vm.bench.setPinMode(it, if (pullup) PinMode.PULLUP else PinMode.INPUT) } }
    val now = ticker(80)
    val firmata by vm.bench.firmata.collectAsState()
    val span = 10_000L
    val grid = MaterialTheme.colorScheme.outlineVariant
    Panes(display = {
        Surface(shape = MaterialTheme.shapes.large, color = Color(0xFF05090D)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pins.forEachIndexed { i, pin ->
                    val level = firmata.pins[pin]?.value ?: 0
                    val changes = vm.bench.history.digital(pin)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$pin", fontFamily = Mono, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.width(32.dp))
                        Canvas(Modifier.weight(1f).height(28.dp)) {
                            val w = size.width; val h = size.height
                            drawLine(grid.copy(alpha = 0.25f), Offset(0f, h / 2), Offset(w, h / 2), 1f)
                            val path = Path()
                            val startT = now - span
                            var lv = changes.lastOrNull { it.first <= startT }?.second ?: changes.firstOrNull()?.second ?: level
                            val y = { v: Int -> if (v != 0) 2f else h - 2f }
                            path.moveTo(0f, y(lv))
                            for ((t, v) in changes.filter { it.first > startT }) {
                                val x = (t - startT).toFloat() / span * w
                                path.lineTo(x, y(lv)); path.lineTo(x, y(v)); lv = v
                            }
                            path.lineTo(w, y(lv))
                            drawPath(path, TRACE[i % 4], style = Stroke(2.dp.toPx()))
                        }
                        Text(if (level != 0) "HIGH" else "LOW", fontFamily = Mono, color = if (level != 0) TRACE[i % 4] else Color.Gray, fontSize = 12.sp, modifier = Modifier.width(44.dp).padding(start = 6.dp))
                    }
                }
                Text("Last 10 seconds", color = Color.Gray, fontSize = 11.sp)
            }
        }
    }, controls = {
        Card {
            ChipPicker("Pins (up to 6)", (2..13).toList(), { it in pins }, { "$it" }) { p ->
                pins = if (p in pins) (pins - p).ifEmpty { listOf(p) } else (pins + p).sorted().takeLast(6)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Internal pull-up")
                    Text("Pins read HIGH until pulled to GND", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(pullup, { pullup = it })
            }
        }
        Wiring(
            listOf(
                "For a button: one leg to a pin above, the other leg to GND. With the pull-up on, the trace drops LOW while it is pressed.",
                "For a sensor with a digital output (motion sensor, tilt switch, IR receiver), connect its output to a pin and its GND to GND.",
                "Turn the pull-up off for signals that drive the pin both ways.",
            ),
            "Inputs take 0–5 V. Firmata reports changes as they happen, but very fast pulses (shorter than a few milliseconds) can be missed.",
        )
    })
}

// ---------- PWM generator ----------

@Composable
private fun PwmGenerator(vm: AppViewModel, ready: Boolean) {
    var pin by remember { mutableIntStateOf(9) }
    var duty by remember { mutableFloatStateOf(0.5f) }
    LaunchedEffect(ready, pin) { if (ready) { vm.bench.setPinMode(pin, PinMode.PWM); delay(100); vm.bench.analogWrite(pin, (duty * 255).roundToInt()) } }
    val freq = if (pin == 4 || pin == 13) "980 Hz" else "490 Hz"
    Panes(display = {
        Surface(shape = MaterialTheme.shapes.large, color = Color(0xFF05090D)) {
            Column(Modifier.padding(16.dp)) {
                Text("%d%%".format((duty * 100).roundToInt()), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = Color.White)
                Text("duty on pin $pin · $freq · average %.2f V".format(duty * 5), fontFamily = Mono, fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val w = size.width; val h = size.height
                    val path = Path(); val periods = 4
                    path.moveTo(0f, h)
                    for (k in 0 until periods) {
                        val x0 = w * k / periods; val x1 = x0 + w / periods * duty
                        path.lineTo(x0, h); if (duty > 0f) { path.lineTo(x0, 4f); path.lineTo(x1, 4f) }; path.lineTo(x1, h); path.lineTo(x0 + w / periods, h)
                    }
                    drawPath(path, TRACE[0], style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
                }
            }
        }
    }, controls = {
        Card {
            Text("Duty cycle", style = MaterialTheme.typography.titleSmall)
            Slider(duty, { duty = it; if (ready) vm.bench.analogWrite(pin, (it * 255).roundToInt()) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { d ->
                    FilterChip(selected = abs(duty - d) < 0.01f, onClick = { duty = d; if (ready) vm.bench.analogWrite(pin, (d * 255).roundToInt()) }, label = { Text("${(d * 100).roundToInt()}%") })
                }
            }
            Spacer(Modifier.height(8.dp))
            ChipPicker("PWM pin", PWM_PINS, { it == pin }, { "$it" }) { p ->
                if (ready) vm.bench.analogWrite(pin, 0)
                pin = p
            }
        }
        Wiring(
            listOf(
                "LED dimmer: pin $pin → 220 Ω resistor → LED long leg; LED short leg → GND.",
                "Motor or bright LED strip: drive it through a transistor or the L293D, never straight from the pin.",
                "To see it on the oscilloscope as a voltage: pin $pin → 10 kΩ → A0, and 10 µF from A0 to GND.",
            ),
            "Each pin supplies at most 20 mA. PWM switches fully on and off; only the average looks like a lower voltage.",
        )
    })
}

// ---------- servo tester ----------

@Composable
private fun ServoTester(vm: AppViewModel, ready: Boolean) {
    var pin by remember { mutableIntStateOf(9) }
    var angle by remember { mutableFloatStateOf(90f) }
    var sweep by remember { mutableStateOf(false) }
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("pinscope", 0)
    // the user's own pulse range, if saved, is where the tester starts
    var myMin by remember { mutableIntStateOf(prefs.getInt("servoMin", 0)) }
    var myMax by remember { mutableIntStateOf(prefs.getInt("servoMax", 0)) }
    var minUs by remember { mutableFloatStateOf(if (myMin > 0) myMin.toFloat() else 544f) }
    var maxUs by remember { mutableFloatStateOf(if (myMax > 0) myMax.toFloat() else 2400f) }
    var flip by remember { mutableStateOf(prefs.getBoolean("servoFlip", false)) }
    LaunchedEffect(ready, pin) { if (ready) { vm.bench.setPinMode(pin, PinMode.SERVO); delay(100); vm.bench.servoRange(pin, minUs.roundToInt(), maxUs.roundToInt()); delay(50); vm.bench.analogWrite(pin, angle.roundToInt()) } }
    LaunchedEffect(ready, minUs, maxUs) { if (ready) { delay(150); vm.bench.servoRange(pin, minUs.roundToInt(), maxUs.roundToInt()); delay(50); vm.bench.analogWrite(pin, angle.roundToInt()) } }
    LaunchedEffect(sweep, ready, pin) {
        var dir = 1
        while (sweep && ready) {
            angle = (angle + dir * 3).coerceIn(0f, 180f)
            if (angle <= 0f || angle >= 180f) dir = -dir
            vm.bench.analogWrite(pin, angle.roundToInt())
            delay(30)
        }
    }
    Panes(display = {
        Surface(shape = MaterialTheme.shapes.large, color = Color(0xFF05090D)) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(2f)) {
                    val c = Offset(size.width / 2, size.height * 0.95f); val r = size.height * 0.85f
                    drawArc(Color.DarkGray, 180f, 180f, false, topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(6.dp.toPx()))
                    // seen from above with the wires at the top: 0° points left, 180° points right; flip mirrors it
                    val a = Math.toRadians(if (flip) 360.0 - angle else 180.0 + angle)
                    drawLine(TRACE[0], c, Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat()), 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(Color.White, 8.dp.toPx(), c)
                }
                Text("${angle.roundToInt()}°", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 32.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart))
                Text(if (flip) "Mirrored view" else "Seen from above, wires at the top", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.align(Alignment.TopCenter))
                IconButton(onClick = { flip = !flip; prefs.edit().putBoolean("servoFlip", flip).apply() }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.SwapHoriz, if (flip) "Show the normal view" else "Mirror the dial", tint = if (flip) TRACE[0] else Color.Gray)
                }
            }
        }
    }, controls = {
        Card {
            Slider(angle, { angle = it; sweep = false; if (ready) vm.bench.analogWrite(pin, it.roundToInt()) }, valueRange = 0f..180f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0f, 90f, 180f).forEach { d -> FilterChip(selected = !sweep && abs(angle - d) < 1f, onClick = { sweep = false; angle = d; if (ready) vm.bench.analogWrite(pin, d.roundToInt()) }, label = { Text("${d.roundToInt()}°") }) }
                Spacer(Modifier.weight(1f))
                Text("Sweep"); Spacer(Modifier.width(8.dp)); Switch(sweep, { sweep = it })
            }
            Spacer(Modifier.height(8.dp))
            ChipPicker("Signal pin", (2..13).toList(), { it == pin }, { "$it" }) { pin = it }
        }
        Card {
            Text("Pulse range", style = MaterialTheme.typography.titleSmall)
            Text("The pulse widths sent for 0° and 180°. Many SG90s only reach their full turn with the wider range.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val presets = listOf(Triple("Standard", 544f, 2400f), Triple("SG90 full", 500f, 2500f)) +
                    (if (myMin > 0) listOf(Triple("Mine", myMin.toFloat(), myMax.toFloat())) else emptyList())
                presets.forEach { (n, lo, hi) ->
                    FilterChip(selected = minUs == lo && maxUs == hi, onClick = { minUs = lo; maxUs = hi }, label = { Text("$n · ${lo.roundToInt()}–${hi.roundToInt()} µs") })
                }
            }
            Text("0° pulse ${minUs.roundToInt()} µs", fontFamily = Mono, fontSize = 13.sp)
            Slider(minUs, { minUs = (it / 10).roundToInt() * 10f }, valueRange = 400f..1000f)
            Text("180° pulse ${maxUs.roundToInt()} µs", fontFamily = Mono, fontSize = 13.sp)
            Slider(maxUs, { maxUs = (it / 10).roundToInt() * 10f }, valueRange = 2000f..2700f)
            Text("If the servo buzzes or strains at an end, it has hit its stop: move that pulse back in a little.",
                style = MaterialTheme.typography.bodySmall, color = LocalPinColors.current.warn)
            Spacer(Modifier.height(8.dp))
            val saved = myMin == minUs.roundToInt() && myMax == maxUs.roundToInt()
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    myMin = minUs.roundToInt(); myMax = maxUs.roundToInt()
                    prefs.edit().putInt("servoMin", myMin).putInt("servoMax", myMax).apply()
                },
                enabled = !saved, shapes = androidx.compose.material3.ButtonDefaults.shapes(),
            ) { Text(if (saved) "Saved as my preset" else "Save as my preset") }
        }
        Wiring(
            listOf("SG90 brown wire → GND.", "Red wire → 5V.", "Orange wire → pin $pin."),
            "USB power is enough for one small SG90. For bigger servos, power them separately and join the grounds.",
        )
    })
}


// ---------- measurement cursors (pen or finger) ----------

// Two cursors as fractions of the screen: x is time, y is voltage (0 V at the bottom, 5 V at the top)
class ScopeCursors { var pts by mutableStateOf(listOf<Offset>()) }

// A tap places A, then B; dragging moves the nearest; with the pen's side button held, B moves
private fun Modifier.scopeCursors(c: ScopeCursors) = pointerInput(c) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val w = size.width.toFloat(); val h = size.height.toFloat()
        fun frac(p: Offset) = Offset((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))
        val f0 = frac(down.position)
        val idx = when {
            currentEvent.penButton() -> 1
            c.pts.size < 2 -> c.pts.size
            else -> if ((c.pts[0] - f0).getDistance() <= (c.pts[1] - f0).getDistance()) 0 else 1
        }
        fun put(f: Offset) { val l = c.pts.toMutableList(); while (l.size <= idx) l += f; l[idx] = f; c.pts = l }
        put(f0); down.consume()
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            put(frac(ch.position)); ch.consume()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCursors(c: ScopeCursors, m: androidx.compose.ui.text.TextMeasurer) {
    val col = listOf(Color(0xFF22D3EE), Color(0xFFF87171))
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
    c.pts.forEachIndexed { i, f ->
        val x = f.x * size.width; val y = f.y * size.height
        drawLine(col[i], Offset(x, 0f), Offset(x, size.height), 2f, pathEffect = dash)
        drawLine(col[i].copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.5f, pathEffect = dash)
        drawCircle(col[i], 7f, Offset(x, y))
        val t = m.measure(if (i == 0) "A" else "B", androidx.compose.ui.text.TextStyle(color = col[i], fontSize = 12.sp, fontWeight = FontWeight.Bold))
        drawText(t, topLeft = Offset((x + 6f).coerceAtMost(size.width - t.size.width), 2f))
    }
}

@Composable
private fun CursorReadout(c: ScopeCursors, spanMs: Float) {
    if (c.pts.isEmpty()) {
        Text("Tap the screen (or touch it with a pen) to place measurement cursors.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    fun v(f: Offset) = (1f - f.y) * 5f
    fun t(f: Offset) = f.x * spanMs
    Row(verticalAlignment = Alignment.CenterVertically) {
        val text = if (c.pts.size < 2) "A: %s · %.2f V".format(fmtMs(t(c.pts[0])), v(c.pts[0]))
        else {
            val dt = kotlin.math.abs(t(c.pts[1]) - t(c.pts[0])); val dv = v(c.pts[1]) - v(c.pts[0])
            "Δt %s%s · ΔV %+.2f V".format(fmtMs(dt), if (dt > 0f) " · %s".format(fmtHz(1000f / dt)) else "", dv)
        }
        Text(text, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = { c.pts = emptyList() }) { Text("Clear") }
    }
}

private fun fmtMs(ms: Float) = when { ms < 1f -> "%.0f µs".format(ms * 1000); ms < 1000f -> "%.2f ms".format(ms); else -> "%.2f s".format(ms / 1000) }
private fun fmtHz(hz: Float) = if (hz >= 1000f) "%.2f kHz".format(hz / 1000) else "%.1f Hz".format(hz)
