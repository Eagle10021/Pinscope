@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SeriesColors = listOf(
    Color(0xFFFFC95C), Color(0xFF22D3EE), Color(0xFF4ADE80), Color(0xFFFB923C),
    Color(0xFF60A5FA), Color(0xFFF87171), Color(0xFFA3E635), Color(0xFFD6A26B),
)

@Composable
fun BenchScreen(vm: AppViewModel) {
    val bench = vm.bench
    val link by bench.link.collectAsState()
    val mode by bench.mode.collectAsState()
    val connected = link is Conn.Connected
    var sub by rememberSaveable { mutableStateOf(0) }
    vm.sim?.let { SimulatorScreen(vm, it); return }
    vm.editingSketch?.let { f -> SketchEditor(vm, f) { vm.editingSketch = null }; return }

    Column(Modifier.fillMaxSize().imePadding()) {
        val slim = LocalWide.current && vm.instrument != null
        Column(Modifier.padding(horizontal = 16.dp).padding(top = if (connected || slim) 10.dp else 20.dp)) {
            if (connected) CompactConnection(vm, link as Conn.Connected, mode)
            else if (slim) SlimConnection(vm, link, mode)
            else {
                Text("Bench", style = MaterialTheme.typography.headlineSmall)
                Text("Talk to your Mega over a USB OTG cable", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                ConnectionCard(vm, link, mode)
            }
        }
        Spacer(Modifier.height(8.dp))
        vm.instrument?.let { inst ->
            val firmata by bench.firmata.collectAsState()
            Box(Modifier.weight(1f)) {
                InstrumentScreen(vm, inst, connected && mode == BenchMode.FIRMATA && firmata.pins.isNotEmpty()) { bench.stopFastScope(); vm.instrument = null }
            }
            return@Column
        }
        val tabs = if (mode == BenchMode.SERIAL) listOf("Monitor", "Plotter", "Sketches") else listOf("Pins", "Plotter", "Sketches")
        if (sub >= tabs.size) sub = 0
        PrimaryTabRow(selectedTabIndex = sub, containerColor = Color.Transparent) {
            tabs.forEachIndexed { i, t -> Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box(Modifier.weight(1f)) {
            when {
                sub == 2 -> SketchesTab(vm)
                sub == 1 -> Plotter(vm)
                mode == BenchMode.SERIAL -> Monitor(vm, connected)
                else -> FirmataPins(vm, connected)
            }
        }
    }
}

// Wide screens with an instrument open, before connecting: one row instead of the full card
@Composable
private fun SlimConnection(vm: AppViewModel, link: Conn, mode: BenchMode) {
    val bench = vm.bench
    val up by bench.upload.collectAsState()
    val firmata by bench.firmata.collectAsState()
    val (title, sub) = when (link) {
        Conn.NoBoard -> "No board connected" to "Plug the Mega in with a USB-C OTG cable"
        is Conn.Found -> link.name to "Ready to connect"
        Conn.Asking -> "Waiting for permission" to "Allow Pinscope to use the USB device"
        is Conn.Connecting -> link.name to "Connecting…"
        is Conn.Failed -> "Not connected" to link.message
        is Conn.Connected -> link.name to ""
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (link is Conn.Failed) MaterialTheme.colorScheme.error else LocalPinColors.current.warn))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.width(340.dp).padding(horizontal = 12.dp)) {
                ConnectedToggles(listOf("Serial monitor", "Firmata pins"), if (mode == BenchMode.SERIAL) 0 else 1, small = true) {
                    bench.switchMode(if (it == 0) BenchMode.SERIAL else BenchMode.FIRMATA)
                }
            }
            when {
                up is UploadState.Running -> { LoadingIndicator(Modifier.size(36.dp)); Text((up as UploadState.Running).step, style = MaterialTheme.typography.bodySmall) }
                mode == BenchMode.FIRMATA && firmata.noReply -> FilledTonalButton(onClick = { bench.installFirmata() }, shapes = ButtonDefaults.shapes()) { Text("Upload StandardFirmata") }
                link is Conn.Connecting || link is Conn.Asking -> LoadingIndicator(Modifier.size(36.dp))
                else -> Button(onClick = { bench.connect() }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Usb, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Connect")
                }
            }
        }
    }
}

// Slim bar while connected, so the monitor, plotter and instruments get the room
@Composable
private fun CompactConnection(vm: AppViewModel, link: Conn.Connected, mode: BenchMode) {
    val bench = vm.bench
    val c = LocalPinColors.current
    val firmata by bench.firmata.collectAsState()
    val baud by bench.baud.collectAsState()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.animateContentSize()) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.ok))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(link.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (mode == BenchMode.FIRMATA) firmata.firmware ?: if (firmata.noReply) "No Firmata reply" else "Starting Firmata…" else "Serial · ${link.chip.label}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                // wide screens: everything on one row, to save height in landscape
                if (LocalWide.current) Box(Modifier.width(340.dp).padding(horizontal = 12.dp)) {
                    ConnectedToggles(listOf("Serial monitor", "Firmata pins"), if (mode == BenchMode.SERIAL) 0 else 1, small = true) {
                        bench.switchMode(if (it == 0) BenchMode.SERIAL else BenchMode.FIRMATA)
                    }
                }
                if (mode == BenchMode.SERIAL) BaudMenu(baud) { bench.setBaud(it) }
                IconButton(onClick = { bench.resetBoard() }) { Icon(Icons.Rounded.RestartAlt, "Reset the board") }
                IconButton(onClick = { bench.disconnect() }) { Icon(Icons.Rounded.UsbOff, "Disconnect") }
            }
            if (!LocalWide.current) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.padding(end = 10.dp)) {
                    ConnectedToggles(listOf("Serial monitor", "Firmata pins"), if (mode == BenchMode.SERIAL) 0 else 1, small = true) {
                        bench.switchMode(if (it == 0) BenchMode.SERIAL else BenchMode.FIRMATA)
                    }
                }
            }
            if (mode == BenchMode.FIRMATA && firmata.noReply) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = { bench.installFirmata() }, shapes = ButtonDefaults.shapes()) { Text("Upload StandardFirmata") }
            }
        }
    }
}

@Composable
private fun ConnectionCard(vm: AppViewModel, link: Conn, mode: BenchMode) {
    val bench = vm.bench
    val c = LocalPinColors.current
    val baud by bench.baud.collectAsState()
    val firmata by bench.firmata.collectAsState()
    val (dot, title, sub) = when (link) {
        Conn.NoBoard -> Triple(MaterialTheme.colorScheme.outline, "No board connected", "Plug the Mega into the phone with a USB-C OTG cable or adapter.")
        is Conn.Found -> Triple(c.warn, link.name, "Ready to connect.")
        Conn.Asking -> Triple(c.warn, "Waiting for permission", "Allow Pinscope to use the USB device.")
        is Conn.Connecting -> Triple(c.warn, link.name, "Connecting…")
        is Conn.Connected -> Triple(c.ok, link.name, buildString {
            append(link.chip.label)
            append(" · ${link.baud} baud")
            if (link.mode == BenchMode.FIRMATA) append(" · ").append(firmata.firmware ?: if (firmata.noReply) "no Firmata reply" else "starting Firmata…")
        })
        is Conn.Failed -> Triple(MaterialTheme.colorScheme.error, "Not connected", link.message)
    }
    Card(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(dot.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(if (link is Conn.Connected) Icons.Rounded.Usb else Icons.Rounded.UsbOff, null, tint = dot)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        ConnectedToggles(listOf("Serial monitor", "Firmata pins"), if (mode == BenchMode.SERIAL) 0 else 1) {
            bench.switchMode(if (it == 0) BenchMode.SERIAL else BenchMode.FIRMATA)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (link is Conn.Connected) {
                OutlinedButton(onClick = { bench.disconnect() }, shapes = ButtonDefaults.shapes()) { Text("Disconnect") }
                FilledTonalButton(onClick = { bench.resetBoard() }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reset") }
            } else if (link is Conn.Connecting || link is Conn.Asking) {
                LoadingIndicator(Modifier.size(44.dp))
                Text(if (link is Conn.Asking) "Waiting…" else "Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Button(onClick = { bench.connect() }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Usb, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Connect")
                }
            }
            Spacer(Modifier.weight(1f))
            if (mode == BenchMode.SERIAL) BaudMenu(baud) { bench.setBaud(it) }
            else Text("57600 baud", fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (mode == BenchMode.FIRMATA && (firmata.noReply || link !is Conn.Connected)) {
            Spacer(Modifier.height(12.dp))
            val up by bench.upload.collectAsState()
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Firmata mode needs the StandardFirmata sketch on the board. Pinscope can put it there; the sketch on the board now is backed up first, so it can be restored from Sketches.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (up is UploadState.Running) Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(8.dp))
                        Text((up as UploadState.Running).step, style = MaterialTheme.typography.bodySmall)
                    } else FilledTonalButton(onClick = { bench.installFirmata() }, shapes = ButtonDefaults.shapes()) { Text("Upload StandardFirmata") }
                }
            }
        }
    }
}

@Composable
private fun BaudMenu(baud: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("$baud baud", fontFamily = Mono)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Bench.BAUDS.forEach { b ->
                DropdownMenuItem(text = { Text("$b", fontFamily = Mono, fontWeight = if (b == baud) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; onPick(b) })
            }
        }
    }
}

// ---------- serial monitor ----------

private val Endings = listOf("Newline" to "\n", "None" to "", "CR" to "\r", "CR + LF" to "\r\n")

@Composable
private fun Monitor(vm: AppViewModel, connected: Boolean) {
    val bench = vm.bench
    val lines by bench.lines.collectAsState()
    val state = rememberLazyListState()
    var stamps by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf("") }
    var ending by rememberSaveable { mutableStateOf(0) }
    var endingOpen by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val atBottom by remember { derivedStateOf { val info = state.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 2 } ?: true } }

    LaunchedEffect(lines.size, follow) { if (follow && lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${lines.size} lines", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).weight(1f))
            ToolToggle(Icons.Rounded.Schedule, "Timestamps", stamps) { stamps = !stamps }
            ToolToggle(Icons.Rounded.VerticalAlignBottom, "Auto-scroll", follow) { follow = !follow }
            IconButton(onClick = { bench.clearLog() }) { Icon(Icons.Rounded.DeleteSweep, "Clear") }
        }
        Surface(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (lines.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (connected) "Waiting for Serial.println() output…" else "Connect a board to see its serial output.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            } else LazyColumn(state = state, contentPadding = PaddingValues(12.dp)) {
                items(lines, key = { it.id }) { l ->
                    Row {
                        if (stamps) Text(fmt.format(Date(l.time)) + "  ", fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when (l.kind) { LineKind.OUT -> "→ ${l.text}"; LineKind.INFO -> "· ${l.text}"; else -> l.text },
                            fontFamily = Mono, fontSize = 13.sp,
                            color = when (l.kind) {
                                LineKind.OUT -> MaterialTheme.colorScheme.primary
                                LineKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
        if (!atBottom && !follow) Spacer(Modifier.height(0.dp))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Send to the board") }, singleLine = true, enabled = connected,
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotEmpty()) { bench.sendText(text, Endings[ending].second); text = "" } }),
                trailingIcon = {
                    Box {
                        TextButton(onClick = { endingOpen = true }) { Text(Endings[ending].first, fontSize = 12.sp) }
                        DropdownMenu(expanded = endingOpen, onDismissRequest = { endingOpen = false }) {
                            Endings.forEachIndexed { i, (n, _) -> DropdownMenuItem(text = { Text(n) }, onClick = { ending = i; endingOpen = false }) }
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { bench.sendText(text, Endings[ending].second); text = "" }, enabled = connected && text.isNotEmpty(),
                modifier = Modifier.size(52.dp).clip(CircleShape).background(if (connected && text.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (connected && text.isNotEmpty()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ToolToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, desc, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- plotter ----------

@Composable
private fun Plotter(vm: AppViewModel) {
    val bench = vm.bench
    val version by bench.plotVersion.collectAsState()
    val paused by bench.paused.collectAsState()
    val mode by bench.mode.collectAsState()
    val data = remember(version) { bench.plot.snapshot() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (mode == BenchMode.FIRMATA) "Analog pins in volts" else "Numbers from each line",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            IconButton(onClick = { bench.paused.value = !paused }) { Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause") }
            IconButton(onClick = { bench.clearPlot() }) { Icon(Icons.Rounded.DeleteSweep, "Clear") }
        }
        PlotChart(data, if (mode == BenchMode.FIRMATA) "Set a pin to Analog to graph it."
            else "Print numbers to plot them, for example\nSerial.print(\"temp:\"); Serial.println(t);")
    }
}

// The plotter's chart and legend, shared with the simulator; tapping a legend entry hides that series
@Composable
fun ColumnScope.PlotChart(data: List<Pair<String, FloatArray>>, emptyText: String) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    var hidden by remember { mutableStateOf(setOf<String>()) }
        Surface(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            val visible = data.filter { it.first !in hidden && it.second.isNotEmpty() }
            if (data.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    emptyText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else Canvas(Modifier.fillMaxSize().padding(start = 44.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
                var lo = visible.minOfOrNull { s -> s.second.min() } ?: 0f
                var hi = visible.maxOfOrNull { s -> s.second.max() } ?: 1f
                if (hi - lo < 1e-6f) { hi += 1f; lo -= 1f }
                val pad = (hi - lo) * 0.08f
                lo -= pad; hi += pad
                val n = 600
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                for (i in 0..4) {
                    val v = lo + (hi - lo) * i / 4f
                    val y = size.height - (v - lo) / (hi - lo) * size.height
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
                    val t = measurer.measure(fmtNum(v), androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = Mono, color = axis))
                    drawText(t, topLeft = Offset(-t.size.width - 8f, y - t.size.height / 2f))
                }
                data.forEachIndexed { si, (name, values) ->
                    if (name in hidden || values.isEmpty()) return@forEachIndexed
                    val path = Path()
                    val step = size.width / (n - 1)
                    val start = n - values.size
                    values.forEachIndexed { i, v ->
                        val x = (start + i) * step
                        val y = size.height - (v - lo) / (hi - lo) * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, SeriesColors[si % SeriesColors.size], style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round))
                }
            }
        }
        // legend with latest values; tap to hide a series
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            data.forEachIndexed { si, (name, values) ->
                val col = SeriesColors[si % SeriesColors.size]
                val off = name in hidden
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (off) Color.Transparent else col.copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, col.copy(alpha = if (off) 0.3f else 0.5f)),
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { hidden = if (off) hidden - name else hidden + name },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (off) col.copy(alpha = 0.3f) else col))
                        Spacer(Modifier.width(8.dp))
                        Text(name, fontSize = 13.sp, color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        values.lastOrNull()?.let { Spacer(Modifier.width(8.dp)); Text(fmtNum(it), fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
}

private fun fmtNum(v: Float): String = when {
    kotlin.math.abs(v) >= 1000 -> "%.0f".format(v)
    kotlin.math.abs(v) >= 10 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

// ---------- Firmata pins ----------

@Composable
private fun FirmataPins(vm: AppViewModel, connected: Boolean) {
    val firmata by vm.bench.firmata.collectAsState()
    val c = LocalPinColors.current
    if (connected && firmata.pins.isEmpty() && !firmata.noReply) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            ContainedLoadingIndicator(Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("Reading the board…", style = MaterialTheme.typography.titleMedium)
            Text("Asking which modes each pin supports", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (!connected || firmata.pins.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            InstrumentRow(vm)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Rounded.Usb,
                if (!connected) "Connect in Firmata mode" else if (firmata.noReply) "No reply from Firmata" else "Reading the board…",
                if (!connected) "Then set any pin to input, output, PWM, analog or servo right from your phone. Live values also show on your board photo."
                else if (firmata.noReply) "Upload StandardFirmata to the board and press Reset."
                else "Asking the board which modes each pin supports.",
            )
            }
        }
        return
    }
    val pins = firmata.pins.values.filter { it.number >= 2 && it.modes.isNotEmpty() }.sortedBy { it.number }
    val groups = listOf(
        "PWM pins 2–13" to pins.filter { it.number in 2..13 },
        "Digital 14–53" to pins.filter { it.number in 14..53 },
        "Analog A0–A15" to pins.filter { it.number >= 54 },
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "instruments") { InstrumentRow(vm) }
        for ((title, list) in groups) {
            if (list.isEmpty()) continue
            item(key = title) { Eyebrow(title, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)) }
            items(list, key = { it.number }) { p ->
                val pin = vm.kit.pinByNumber(p.number)
                FirmataRow(vm, p, pin, c)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
private fun FirmataRow(vm: AppViewModel, p: FirmataPin, pin: Pin?, c: PinColors) {
    var menu by remember { mutableStateOf(false) }
    val name = if (p.number >= 54) "A${p.number - 54}" else p.number.toString()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinBadge(name, Modifier.clickable { pin?.let { vm.selectedPin = it.index } })
            Spacer(Modifier.width(12.dp))
            Text(
                pin?.tags?.filter { it != "digital" && it != "analog" }?.joinToString(" · ") { TAG_NAMES[it] ?: it }.orEmpty(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(p.mode?.label ?: "Set mode")
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    PinMode.controllable.filter { it in p.modes }.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label) }, onClick = { menu = false; vm.bench.setPinMode(p.number, m) })
                    }
                }
            }
        }
        if (p.mode != null && p.mode in PinMode.controllable) {
            Spacer(Modifier.height(4.dp))
            PinValue(p.number, p, vm, c)
        }
    }
}
