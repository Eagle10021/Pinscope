@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.animation.scaleIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleUsb(intent)
        setContent { PinscopeTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsb(intent)
    }

    // Debug builds only: adb can place the four alignment markers in photo pixels, for repeatable tests
    //   adb shell am start -n io.github.eagle10021.pinscope/.MainActivity --es corners "x1,y1,x2,y2,x3,y4,x4,y4"
    private fun handleDebug(intent: Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent?.getBooleanExtra("append", false) == true) { vm.learnBoard(append = true); android.util.Log.d("PinscopeDbg", "appending photo to memory"); return }
        val c = intent?.getStringExtra("corners")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return
        if (c.size != 8 || vm.photo == null) return
        vm.corners = c.chunked(2).map { androidx.compose.ui.geometry.Offset(it[0], it[1]) }
        vm.tab = Tab.SCAN
        vm.stage = ScanStage.ALIGN
        android.util.Log.d("PinscopeDbg", "corners set: ${vm.corners}")
    }

    // Opened because a board was plugged in: Android has already granted access, so connect straight away
    private fun handleUsb(intent: Intent?) {
        handleDebug(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            vm.tab = Tab.BENCH
            vm.bench.refresh()
            vm.bench.connect()
        }
    }
}

private data class Dest(val tab: Tab, val label: String, val on: ImageVector, val off: ImageVector)

private val Dests = listOf(
    Dest(Tab.SCAN, "Scan", Icons.Rounded.CameraAlt, Icons.Outlined.CameraAlt),
    Dest(Tab.BOARD, "Board", Icons.Rounded.DeveloperBoard, Icons.Outlined.DeveloperBoard),
    Dest(Tab.PARTS, "Parts", Icons.Rounded.Widgets, Icons.Outlined.Widgets),
    Dest(Tab.BENCH, "Bench", Icons.Rounded.Usb, Icons.Outlined.Usb),
)

@Composable
private fun App(vm: AppViewModel) {
    val link by vm.bench.link.collectAsState()
    val firmata by vm.bench.firmata.collectAsState()
    val immersive = (vm.tab == Tab.SCAN && vm.stage in setOf(ScanStage.CAMERA, ScanStage.ALIGN)) || (vm.tab == Tab.BENCH && (vm.editingSketch != null || vm.sim != null))
    val sheetAllowed = !(vm.tab == Tab.SCAN && vm.stage == ScanStage.ALIGN)

    BackHandler(enabled = vm.tab == Tab.SCAN && vm.stage == ScanStage.CAMERA) {
        vm.stage = if (vm.photo != null) ScanStage.EXPLORE else ScanStage.EMPTY
    }
    BackHandler(enabled = vm.tab == Tab.SCAN && vm.stage == ScanStage.ALIGN && vm.homography() != null) { vm.finishAlign() }
    BackHandler(enabled = vm.tab != Tab.SCAN && vm.openPart == null) { vm.tab = Tab.SCAN }

    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val rail = widthDp >= 600
    androidx.compose.runtime.CompositionLocalProvider(LocalWide provides (widthDp >= 840), LocalRail provides rail) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!immersive && !rail) ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                for (d in Dests) {
                    val sel = vm.tab == d.tab
                    ShortNavigationBarItem(
                        selected = sel,
                        onClick = { if (sel && d.tab == Tab.PARTS) vm.openPart = null; vm.tab = d.tab },
                        icon = {
                            BadgedBox(badge = { if (d.tab == Tab.BENCH && link is Conn.Connected) Badge(containerColor = LocalPinColors.current.ok) }) {
                                Icon(if (sel) d.on else d.off, null)
                            }
                        },
                        label = { Text(d.label) },
                        colors = ShortNavigationBarItemDefaults.colors(
                            selectedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
        if (rail && !immersive) NavRail(vm, link is Conn.Connected)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
            val fastOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(vm.tab, transitionSpec = { (fadeIn(effects) + scaleIn(spatial, initialScale = 0.94f)) togetherWith fadeOut(fastOut) }, label = "tab") { tab ->
                Box(Modifier.fillMaxSize().then(if (tab == Tab.SCAN) Modifier else Modifier.statusBarsPadding())) {
                    when (tab) {
                        Tab.SCAN -> ScanScreen(vm)
                        Tab.BOARD -> BoardScreen(vm)
                        Tab.PARTS -> PartsScreen(vm)
                        Tab.BENCH -> BenchScreen(vm)
                    }
                }
            }
        }
        }
    }
    }

    vm.update?.let { u ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (vm.updateProgress == null) vm.update = null },
            icon = { Icon(androidx.compose.material.icons.Icons.Rounded.Upload, null) },
            title = { Text("Pinscope ${u.version} is available") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("You have ${vm.appVersion}." + if (u.prerelease) " This is a beta release." else "", style = MaterialTheme.typography.bodyMedium)
                    if (u.notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(u.notes.replace("\r", "").trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    vm.updateProgress?.let { p ->
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… %d%%".format((p * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                    }
                    if (vm.updateReady != null && vm.updateProgress == null) {
                        Spacer(Modifier.height(10.dp))
                        Text("If Android asked to allow installs from Pinscope, allow it, come back and tap Install.", style = MaterialTheme.typography.bodySmall)
                    }
                    vm.updateError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = { vm.startUpdate() }, enabled = vm.updateProgress == null) {
                    Text(if (vm.updateReady != null) "Install" else if (u.apkUrl == null) "Open release page" else "Update")
                }
            },
            dismissButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = { vm.skipUpdate() }, enabled = vm.updateProgress == null) { Text("Skip this version") }
                    androidx.compose.material3.TextButton(onClick = { vm.update = null }, enabled = vm.updateProgress == null) { Text("Later") }
                }
            },
        )
    }

    // pin details, from the photo, the diagram or the pin list
    val pin = vm.selectedPin?.let { vm.kit.pins.getOrNull(it) }
    if (pin != null && sheetAllowed) {
        val connected = link is Conn.Connected && (link as Conn.Connected).mode == BenchMode.FIRMATA && firmata.pins.isNotEmpty()
        ModalBottomSheet(
            onDismissRequest = { vm.selectedPin = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PinDetails(pin, vm, pin.num?.let { firmata.pins[it] }, connected)
            }
        }
    }
}

// Tablets: the four destinations down the left edge, with the content next to them
@Composable
private fun NavRail(vm: AppViewModel, connected: Boolean) {
    androidx.compose.material3.NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxHeight().statusBarsPadding(),
    ) {
        Spacer(Modifier.weight(1f))
        for (d in Dests) {
            val sel = vm.tab == d.tab
            androidx.compose.material3.NavigationRailItem(
                selected = sel,
                onClick = { if (sel && d.tab == Tab.PARTS) vm.openPart = null; vm.tab = d.tab },
                icon = {
                    BadgedBox(badge = { if (d.tab == Tab.BENCH && connected) Badge(containerColor = LocalPinColors.current.ok) }) {
                        Icon(if (sel) d.on else d.off, null)
                    }
                },
                label = { Text(d.label) },
                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun BoardScreen(vm: AppViewModel) {
    val firmata by vm.bench.firmata.collectAsState()
    val link by vm.bench.link.collectAsState()
    var about by remember { mutableStateOf(false) }
    val live = if (link is Conn.Connected && vm.showLive) firmata.pins else emptyMap()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mega 2560", style = MaterialTheme.typography.headlineSmall)
                Text(if (vm.fingerDraws) "Drawing wires · drag from a pin" else "Rev3 · tap a pin · pinch to zoom · draw wires with a pen",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            androidx.compose.material3.FilledIconToggleButton(checked = vm.fingerDraws, onCheckedChange = { vm.fingerDraws = it }) {
                Icon(Icons.Rounded.Gesture, if (vm.fingerDraws) "Stop drawing with a finger" else "Draw wires with a finger")
            }
            IconButton(onClick = { about = true }) { Icon(Icons.Rounded.Info, "About the board") }
        }
        Spacer(Modifier.height(14.dp))
        FilterBar(vm.filter, vm.kit, { vm.filter = it; vm.selectedPin = null })
        BoardDiagram(vm, live, Modifier.weight(1f).fillMaxWidth().padding(12.dp))
        if (vm.wires.isNotEmpty()) WireBar(vm)
    }
    vm.editingWire?.let { id -> vm.wires.firstOrNull { it.id == id }?.let { WireDialog(vm, it) } }
    if (about) ModalBottomSheet(onDismissRequest = { about = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        AboutBoard(vm)
    }
}

// Drawn wires: tap one to name or recolour it; Make sketch turns them into pin constants
@Composable
private fun WireBar(vm: AppViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.wires.forEach { w ->
                val from = vm.kit.pins.getOrNull(w.from)
                val to = w.to?.let { vm.kit.pins.getOrNull(it) }
                androidx.compose.material3.AssistChip(
                    onClick = { vm.editingWire = w.id },
                    label = { Text((w.label.ifEmpty { "Wire" }) + " · " + listOfNotNull(from?.label?.ifEmpty { from.name }, to?.let { it.label.ifEmpty { it.name } }).joinToString(" → ")) },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(w.color))) },
                )
            }
        }
        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Remove all wires") }
        androidx.compose.material3.Button(onClick = { vm.wiresToSketch() }, shapes = androidx.compose.material3.ButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Make sketch")
        }
    }
    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Remove all wires?") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.clearWires(); confirmClear = false }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun WireDialog(vm: AppViewModel, w: Wire) {
    var label by remember(w.id) { mutableStateOf(w.label) }
    var color by remember(w.id) { mutableStateOf(w.color) }
    val from = vm.kit.pins.getOrNull(w.from)
    val to = w.to?.let { vm.kit.pins.getOrNull(it) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.updateWire(w.copy(label = label.trim(), color = color)); vm.editingWire = null },
        title = { Text("Wire from " + (from?.title ?: "?") + (to?.let { " to " + it.title } ?: "")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(label, { label = it }, label = { Text("What it connects to") }, placeholder = { Text("LED, trig, buzzer…") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WIRE_COLORS.forEach { c ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(c)
                            .then(if (c.argb() == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { color = c.argb() })
                    }
                }
                Text("The label becomes the constant's name in Make sketch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.updateWire(w.copy(label = label.trim(), color = color)); vm.editingWire = null }) { Text("Done") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.removeWire(w.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
    )
}

@Composable
private fun AboutBoard(vm: AppViewModel) {
    val uri = LocalUriHandler.current
    val b = vm.kit.board
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text("${b.name} ${b.rev}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Card(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            b.facts.forEach { (k, v) -> FactRow(k, v) }
        }
        Spacer(Modifier.height(20.dp))
        Eyebrow("How to tell it's a Rev3")
        Spacer(Modifier.height(8.dp))
        b.howToTell.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
        Spacer(Modifier.height(20.dp))
        Eyebrow("Sources")
        Spacer(Modifier.height(4.dp))
        vm.kit.sources.forEach { s ->
            Text(s.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { uri.openUri(s.url) })
        }
        Spacer(Modifier.height(16.dp))
        Text("Pin data from Mega Kit Pinouts (${vm.kit.site.removePrefix("https://").trimEnd('/')}). Header positions from KiCad's Arduino Mega template.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
