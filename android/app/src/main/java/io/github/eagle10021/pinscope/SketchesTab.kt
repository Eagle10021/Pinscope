@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.material.icons.rounded.Science
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray

data class BundledSketch(val id: String, val name: String, val desc: String, val baud: Int, val mode: String, val pins: List<String>, val source: String?)

fun loadSketches(context: Context): List<BundledSketch> = runCatching {
    val a = JSONArray(context.assets.open("sketches/index.json").bufferedReader().use { it.readText() })
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        BundledSketch(
            o.getString("id"), o.getString("name"), o.getString("desc"), o.getInt("baud"), o.getString("mode"),
            o.optJSONArray("pins")?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList(),
            o.optString("source").ifEmpty { null },
        )
    }
}.getOrDefault(emptyList())

@Composable
fun SketchesTab(vm: AppViewModel) {
    val context = LocalContext.current
    val bench = vm.bench
    val state by bench.upload.collectAsState()
    val sketches = remember { loadSketches(context) }
    val busy = state is UploadState.Running
    var refresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val examples = remember { ExampleLibrary.load(context) }
    var exQuery by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var exOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
    var confirmDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<java.io.File?>(null) }
    val pickHex = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Sketch"
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            bench.flash(IntelHex.parse(text), name, afterMode = BenchMode.SERIAL, afterBaud = null)
        }.onFailure { bench.info(it.message ?: "Could not read that file") }
    }

    fun flashBundled(s: BundledSketch) {
        val image = runCatching { IntelHex.parse(context.assets.open("sketches/${s.id}.hex").bufferedReader().use { it.readText() }) }.getOrNull() ?: return
        val mode = when (s.mode) { "firmata" -> BenchMode.FIRMATA; "serial" -> BenchMode.SERIAL; else -> null }
        bench.flash(image, s.name, mode, if (s.mode == "serial") s.baud else null)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnimatedVisibility(state !is UploadState.Idle) { UploadCard(state) { bench.clearUpload() } }
        }
        item {
            Card {
                Text("Your own sketch", style = MaterialTheme.typography.titleMedium)
                Text("In the Arduino IDE use Sketch → Export Compiled Binary, copy the .hex file to the phone, and upload it here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { pickHex.launch(arrayOf("*/*")) }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose a .hex file")
                }
            }
        }
        item { MySketchesCard(vm, refresh) { refresh++ } }
        item { WebSketchCard(vm, busy) }
        item { Eyebrow("Quick start", Modifier.padding(start = 4.dp, top = 8.dp)) }
        items(sketches, key = { it.id }) { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    if (s.id != "StandardFirmata") androidx.compose.material3.IconButton(onClick = {
                        val src = runCatching { context.assets.open("sketches/${s.id}.ino").bufferedReader().use { it.readText() } }.getOrNull()
                        runCatching { IntelHex.parse(context.assets.open("sketches/${s.id}.hex").bufferedReader().use { it.readText() }) }.onSuccess { vm.simulate(it, s.name, src) }
                    }) { Icon(androidx.compose.material.icons.Icons.Rounded.Science, "Try ${s.name} in the simulator") }
                    if (s.id != "StandardFirmata") {
                        androidx.compose.material3.IconButton(onClick = {
                            val src = runCatching { context.assets.open("sketches/${s.id}.ino").bufferedReader().use { it.readText() } }.getOrNull() ?: return@IconButton
                            vm.editingSketch = SketchStore.create(context, s.id, src)
                        }) { Icon(androidx.compose.material.icons.Icons.Rounded.Edit, "Edit a copy of ${s.name}") }
                    }
                    Button(onClick = { flashBundled(s) }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Upload, "Upload ${s.name}", Modifier.size(18.dp))
                    }
                }
                if (s.pins.isNotEmpty() || s.mode != "none") {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.pins.forEach { PinBadge(it.removePrefix("D")) }
                        when (s.mode) {
                            "firmata" -> Text("Opens Firmata pins", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            "serial" -> Text("Opens the monitor at ${s.baud} baud", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        exampleLibrary(
            examples, exQuery, { exQuery = it }, exOpen, { g -> exOpen = if (g in exOpen) exOpen - g else exOpen + g }, busy,
            onUpload = { e ->
                runCatching { IntelHex.parse(ExampleLibrary.hex(context, e)) }.onSuccess {
                    bench.flash(it, e.name, if (e.baud != null) BenchMode.SERIAL else null, e.baud)
                }
            },
            onEdit = { e -> vm.editingSketch = SketchStore.create(context, e.name, ExampleLibrary.source(context, e)) },
            onSimulate = { e -> runCatching { IntelHex.parse(ExampleLibrary.hex(context, e)) }.onSuccess { vm.simulate(it, e.name, runCatching { ExampleLibrary.source(context, e) }.getOrNull()) } },
        )
        val backups = refresh.let { bench.backups() }
        if (backups.isNotEmpty()) {
            item { Eyebrow("Backups", Modifier.padding(start = 4.dp, top = 8.dp)) }
            items(backups, key = { it.name }) { f ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension.removePrefix("Board backup "), style = MaterialTheme.typography.titleSmall)
                            Text("${f.length() / 1024} KB · the sketch that was on the board before an upload", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.IconButton(onClick = { confirmDelete = f }, enabled = !busy) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.DeleteOutline, "Delete this backup")
                        }
                        FilledTonalButton(onClick = {
                            runCatching { bench.flash(IntelHex.parse(f.readText()), "the backup", BenchMode.SERIAL, null) }
                        }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Restore") }
                    }
                }
            }
        }
        item {
            Text("Uploading replaces the sketch on the board. Pinscope backs it up first, so it can be restored here. StandardFirmata comes from the Firmata library (LGPL 2.1). The other examples are part of Pinscope.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
    }
    confirmDelete?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (f.extension == "hex") "Delete this backup?" else "Delete this sketch?") },
            text = { Text("${f.nameWithoutExtension} will be removed from this phone. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { f.delete(); confirmDelete = null; refresh++ }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UploadCard(state: UploadState, onDismiss: () -> Unit) {
    val c = LocalPinColors.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is UploadState.Running -> {
                    Text("Uploading ${state.name}", style = MaterialTheme.typography.titleMedium)
                    Text(state.step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UploadState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.ok)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${state.name} is on the board", style = MaterialTheme.typography.titleMedium)
                        Text("%d KB written and checked in %.1f s".format(state.bytes / 1024, state.seconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.backup?.let { Text("The previous sketch was saved: restore it from Backups below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
                is UploadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Upload of ${state.name} failed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
                UploadState.Idle -> {}
            }
        }
    }
}

// Sketch source or link → the user's compile server → .hex → upload
@Composable
private fun WebSketchCard(vm: AppViewModel, busy: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pinscope", 0) }
    var server by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(prefs.getString("compileServer", "") ?: "") }
    var input by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var working by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Card {
        Text("From the web", style = MaterialTheme.typography.titleMedium)
        Text("Paste a link to an .ino file (GitHub links work) or the sketch code. Your own compile server turns it into a .hex; see tools/compile-server in the Pinscope repo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedTextField(
            value = server, onValueChange = { server = it; prefs.edit().putString("compileServer", it.trim()).apply() },
            label = { Text("Compile server") }, placeholder = { Text("http://my-server:8099") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = input, onValueChange = { input = it; error = null },
            label = { Text("Sketch link or code") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), maxLines = 8)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    working = true; error = null
                    scope.launch {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { compileRemote(server.trim(), input.trim()) }
                        working = false
                        r.onSuccess { hex ->
                            runCatching { IntelHex.parse(hex) }.onSuccess { vm.bench.flash(it, sketchName(input), BenchMode.SERIAL, null) }
                                .onFailure { e -> error = e.message }
                        }.onFailure { error = it.message }
                    }
                },
                enabled = !busy && !working && server.isNotBlank() && input.isNotBlank(), shapes = ButtonDefaults.shapes(),
            ) { Text("Compile and upload") }
            if (working) { Spacer(Modifier.width(12.dp)); androidx.compose.material3.LoadingIndicator(Modifier.size(36.dp)) }
        }
    }
}

private fun sketchName(input: String): String =
    if (input.startsWith("http")) input.substringAfterLast('/').substringBefore('?').ifEmpty { "Sketch" } else "Pasted sketch"

private fun compileRemote(server: String, input: String): Result<String> = runCatching {
    val base = server.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    val body = org.json.JSONObject().apply { if (input.startsWith("http://") || input.startsWith("https://")) put("url", input) else put("source", input) }.toString()
    val c = java.net.URL("$base/compile").openConnection() as java.net.HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 200000
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write(body.toByteArray()) }
    val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    val o = org.json.JSONObject(text)
    if (!o.optBoolean("ok")) throw java.io.IOException(o.optString("error", "Compile failed").trim())
    o.getString("hex")
}

// The user's own sketches, saved on the phone
@Composable
private fun MySketchesCard(vm: AppViewModel, refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val files = remember(refresh, vm.editingSketch) { SketchStore.list(context) }
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<java.io.File?>(null) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My sketches", style = MaterialTheme.typography.titleMedium)
                Text(if (files.isEmpty()) "Write Arduino code here, then verify and upload it." else "${files.size} on this phone",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { vm.editingSketch = SketchStore.create(context, "sketch", SketchStore.BLANK) }, shapes = ButtonDefaults.shapes()) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New")
            }
        }
        files.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(MaterialTheme.shapes.small).clickable { vm.editingSketch = f }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleSmall)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(f.lastModified())),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.IconButton(onClick = { confirm = f }) { Icon(androidx.compose.material.icons.Icons.Rounded.DeleteOutline, "Delete ${f.nameWithoutExtension}") }
            }
        }
    }
    confirm?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this sketch?") },
            text = { Text("${f.nameWithoutExtension} will be removed from this phone. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { f.delete(); confirm = null; onChanged() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
