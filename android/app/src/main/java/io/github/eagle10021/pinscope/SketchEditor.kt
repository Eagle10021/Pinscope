@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.material.icons.rounded.Science
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- sketch files ----------

object SketchStore {
    fun dir(context: Context) = File(context.filesDir, "sketches").apply { mkdirs() }
    fun list(context: Context): List<File> = dir(context).listFiles { f -> f.extension == "ino" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun create(context: Context, base: String, source: String): File {
        val clean = base.replace(Regex("[^A-Za-z0-9_ -]"), "").trim().ifEmpty { "sketch" }
        var f = File(dir(context), "$clean.ino")
        var n = 2
        while (f.exists()) f = File(dir(context), "$clean $n.ino").also { n++ }
        f.writeText(source)
        return f
    }

    const val BLANK = "void setup() {\n  // runs once at start-up\n  Serial.begin(9600);\n}\n\nvoid loop() {\n  // runs over and over\n}\n"
}

// ---------- compiling on the user's compile server ----------

data class CompileResult(val hex: String?, val size: Int?, val errors: List<CompileError>, val raw: String)
data class CompileError(val line: Int, val col: Int, val message: String, val warning: Boolean)

fun compileServer(context: Context): String = context.getSharedPreferences("pinscope", 0).getString("compileServer", "")?.trim() ?: ""

fun compileSource(server: String, source: String, url: Boolean = false): CompileResult {
    val base = server.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    val body = org.json.JSONObject().apply { if (url) put("url", source) else put("source", source) }.toString()
    val c = java.net.URL("$base/compile").openConnection() as java.net.HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 200000
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write(body.toByteArray()) }
    val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    val o = org.json.JSONObject(text)
    if (o.optBoolean("ok")) return CompileResult(o.getString("hex"), if (o.isNull("size")) null else o.optInt("size"), emptyList(), "")
    val msg = o.optString("error", "Compile failed")
    val errs = Regex("""sketch\.ino:(\d+):(\d+): (error|warning): (.*)""").findAll(msg).map {
        CompileError(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[4], it.groupValues[3] == "warning")
    }.toList()
    return CompileResult(null, null, errs, msg.trim())
}

// ---------- syntax highlighting ----------

private val KEYWORDS = setOf("if", "else", "for", "while", "do", "return", "switch", "case", "default", "break", "continue", "const", "static", "volatile", "struct", "class", "new", "delete", "sizeof", "true", "false", "nullptr", "NULL")
private val TYPES = setOf("void", "int", "long", "short", "unsigned", "signed", "float", "double", "bool", "boolean", "char", "byte", "word", "String", "uint8_t", "uint16_t", "uint32_t", "int8_t", "int16_t", "int32_t", "size_t", "auto")
private val CONSTS = setOf("HIGH", "LOW", "INPUT", "OUTPUT", "INPUT_PULLUP", "LED_BUILTIN", "A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11", "A12", "A13", "A14", "A15", "PI", "CHANGE", "RISING", "FALLING")
private val FUNCS = setOf("setup", "loop", "pinMode", "digitalWrite", "digitalRead", "analogRead", "analogWrite", "delay", "delayMicroseconds", "millis", "micros", "tone", "noTone", "map", "constrain", "min", "max", "abs", "random", "randomSeed", "attachInterrupt", "detachInterrupt", "digitalPinToInterrupt", "pulseIn", "shiftOut", "shiftIn", "Serial", "Serial1", "Serial2", "Serial3", "Wire", "SPI", "begin", "print", "println", "available", "read", "write")
private val TOKEN = Regex("""//[^\n]*|/\*[\s\S]*?(\*/|$)|"(\\.|[^"\\\n])*"?|'(\\.|[^'\\\n])*'?|^[ \t]*#[^\n]*|\b0x[0-9A-Fa-f]+\b|\b\d+(\.\d+)?[fFuUlL]*\b|\b[A-Za-z_]\w*\b""", RegexOption.MULTILINE)

private class Highlighter(val keyword: Color, val type: Color, val func: Color, val number: Color, val string: Color, val comment: Color, val pre: Color, val const: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text.text)
        for (m in TOKEN.findAll(text.text)) {
            val t = m.value
            val style = when {
                t.startsWith("//") || t.startsWith("/*") -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
                t.startsWith("\"") || t.startsWith("'") -> SpanStyle(color = string)
                t.trimStart().startsWith("#") -> SpanStyle(color = pre)
                t[0].isDigit() -> SpanStyle(color = number)
                t in KEYWORDS -> SpanStyle(color = keyword, fontWeight = FontWeight.SemiBold)
                t in TYPES -> SpanStyle(color = type)
                t in CONSTS -> SpanStyle(color = const)
                t in FUNCS -> SpanStyle(color = func)
                else -> null
            } ?: continue
            b.addStyle(style, m.range.first, m.range.last + 1)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

// ---------- the editor ----------

private val KEYS = listOf("{", "}", "(", ")", ";", "<", ">", "=", "\"", "#", "/", "[", "]", "&", "|", "!", "+", "-", "*", "_")
private val SNIPPETS = listOf(
    "pinMode" to "pinMode(pin, OUTPUT);", "digitalWrite" to "digitalWrite(pin, HIGH);", "digitalRead" to "digitalRead(pin)",
    "analogRead" to "analogRead(A0)", "analogWrite" to "analogWrite(pin, 128);", "delay" to "delay(500);",
    "println" to "Serial.println();", "if" to "if () {\n}", "for" to "for (int i = 0; i < 10; i++) {\n}", "millis" to "millis()",
)

@Composable
fun SketchEditor(vm: AppViewModel, file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember(file) { mutableStateOf(TextFieldValue(runCatching { file.readText() }.getOrDefault(""))) }
    var dirty by remember { mutableStateOf(false) }
    var compiling by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CompileResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askServer by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val lineHeight = 20.sp
    val lineHeightPx = with(density) { lineHeight.toPx() }
    val cs = MaterialTheme.colorScheme
    val pc = LocalPinColors.current
    val highlighter = remember(cs) {
        Highlighter(keyword = cs.primary, type = pc.forTag("interrupt"), func = pc.forTag("i2c"), number = pc.forTag("pwm"),
            string = pc.forTag("analog"), comment = cs.onSurfaceVariant, pre = pc.forTag("power"), const = pc.forTag("serial"))
    }

    fun save() { if (dirty) { runCatching { file.writeText(value.text) }; dirty = false } }
    LaunchedEffect(value.text) { delay(700); save() }
    BackHandler { save(); onClose() }

    fun insert(s: String) {
        val sel = value.selection
        val t = value.text.replaceRange(sel.min, sel.max, s)
        // snippets put the cursor inside the first brackets
        val inner = s.indexOf('(').takeIf { it >= 0 && s.length > 2 }?.let { it + 1 } ?: s.length
        value = TextFieldValue(t, TextRange(sel.min + inner)); dirty = true
    }

    fun jumpTo(line: Int) {
        val lines = value.text.split('\n')
        val off = lines.take((line - 1).coerceIn(0, lines.size)).sumOf { it.length + 1 }
        value = value.copy(selection = TextRange(off.coerceAtMost(value.text.length)))
        scope.launch { scroll.animateScrollTo(((line - 3) * lineHeightPx).toInt().coerceAtLeast(0)) }
    }

    val compiler = remember { OnDeviceCompiler(context) }
    var took by remember { mutableStateOf<Long?>(null) }

    // Compiles, then runs the sketch on the virtual Mega instead of a real board
    fun simulate() {
        save()
        val local = compiler.available
        val server = compileServer(context)
        if (!local && server.isEmpty()) { askServer = true; return }
        compiling = "Compiling for the simulator…"; result = null; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { if (local) compiler.compile(value.text) else compileSource(server, value.text) } }
            compiling = null
            r.onFailure { error = it.message }
            r.onSuccess { res ->
                result = res
                res.hex?.let { hex -> runCatching { IntelHex.parse(hex) }.onSuccess { vm.simulate(it, file.nameWithoutExtension, value.text) }.onFailure { e -> error = e.message } }
            }
        }
    }

    fun compile(upload: Boolean) {
        save()
        val local = compiler.available
        val server = compileServer(context)
        if (!local && server.isEmpty()) { askServer = true; return }
        compiling = if (upload) "Compiling, then uploading…" else "Compiling…"
        result = null; error = null
        scope.launch {
            val t0 = System.currentTimeMillis()
            val r = withContext(Dispatchers.IO) { runCatching { if (local) compiler.compile(value.text) else compileSource(server, value.text) } }
            took = System.currentTimeMillis() - t0
            compiling = null
            r.onFailure { error = if (local) "The compiler failed to run: ${it.message}" else "Could not reach the compile server: ${it.message}" }
            r.onSuccess { res ->
                result = res
                if (upload && res.hex != null) {
                    // the monitor opens at the sketch's own Serial.begin() speed
                    val baud = Regex("""Serial\.begin\s*\(\s*(\d+)""").find(value.text)?.groupValues?.get(1)?.toIntOrNull()
                    runCatching { IntelHex.parse(res.hex) }.onSuccess { vm.bench.flash(it, file.nameWithoutExtension, BenchMode.SERIAL, baud) }
                        .onFailure { error = it.message }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save(); onClose() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(file.nameWithoutExtension, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(if (dirty) "Editing" else "Saved", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            OutlinedButton(onClick = { compile(false) }, enabled = compiling == null, shapes = ButtonDefaults.shapes()) { Text("Verify") }
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.FilledTonalButton(onClick = { simulate() }, enabled = compiling == null, shapes = ButtonDefaults.shapes()) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Science, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Simulate")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { compile(true) }, enabled = compiling == null && vm.bench.upload.value !is UploadState.Running, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Upload")
            }
        }

        // code
        val lines = value.text.count { it == '\n' } + 1
        val style = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = lineHeight, color = cs.onSurface)
        Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest) {
            Row(Modifier.verticalScroll(scroll).padding(vertical = 10.dp)) {
                Text(
                    (1..lines).joinToString("\n"), style = style.copy(color = cs.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.End),
                    modifier = Modifier.widthIn(min = 36.dp).padding(start = 6.dp, end = 10.dp),
                )
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = value,
                        onValueChange = { nv ->
                            // auto-indent: a new line keeps the previous line's indent, one step more after "{"
                            var v = nv
                            if (nv.text.length == value.text.length + 1 && nv.selection.collapsed && nv.selection.start > 0 && nv.text[nv.selection.start - 1] == '\n') {
                                val before = nv.text.substring(0, nv.selection.start - 1)
                                val prev = before.substringAfterLast('\n')
                                var indent = prev.takeWhile { it == ' ' }
                                if (prev.trimEnd().endsWith("{")) indent += "  "
                                if (indent.isNotEmpty()) v = TextFieldValue(nv.text.substring(0, nv.selection.start) + indent + nv.text.substring(nv.selection.start), TextRange(nv.selection.start + indent.length))
                            }
                            if (v.text != value.text) dirty = true
                            value = v
                        },
                        textStyle = style,
                        visualTransformation = highlighter,
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.widthIn(min = 600.dp).padding(end = 24.dp),
                    )
                }
            }
        }

        // compile status and errors
        AnimatedVisibility(compiling != null || result != null || error != null) {
            Surface(Modifier.fillMaxWidth().padding(8.dp), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh) {
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        compiling != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(10.dp)); Text(compiling!!)
                        }
                        error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = cs.error); Spacer(Modifier.width(8.dp)); Text(error!!, style = MaterialTheme.typography.bodySmall)
                        }
                        result?.hex != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = pc.ok); Spacer(Modifier.width(8.dp))
                            Column {
                                Text(result!!.size?.let { "Compiled: %d bytes, %.1f%% of the Mega's flash".format(it, it * 100f / 253952) } ?: "Compiled")
                                Text((if (compiler.available) "On this phone" else "On the compile server") + (took?.let { " · %.1f s".format(it / 1000f) } ?: ""),
                                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                        result != null -> {
                            val errs = result!!.errors
                            if (errs.isEmpty()) Text(result!!.raw.takeLast(1500), fontFamily = Mono, fontSize = 12.sp)
                            errs.forEach { e ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { jumpTo(e.line) }.padding(4.dp)) {
                                    Text("${e.line}", fontFamily = Mono, fontWeight = FontWeight.Bold, color = if (e.warning) pc.warn else cs.error, modifier = Modifier.width(36.dp))
                                    Text(e.message, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // key bar
        Row(Modifier.fillMaxWidth().background(cs.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KEYS.forEach { k -> KeyChip(k, mono = true) { insert(k) } }
            SNIPPETS.forEach { (label, code) -> KeyChip(label, mono = false) { insert(code) } }
        }
    }

    if (askServer) {
        var server by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askServer = false },
            title = { Text("Compile server") },
            text = {
                Column {
                    Text("Compiling needs the Pinscope compile server running on a computer with the Arduino IDE. See tools/compile-server in the Pinscope repo.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(server, { server = it }, label = { Text("Address") }, placeholder = { Text("http://my-computer:8099") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSharedPreferences("pinscope", 0).edit().putString("compileServer", server.trim()).apply()
                    askServer = false
                }, enabled = server.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { askServer = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyChip(label: String, mono: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(label, fontFamily = if (mono) Mono else null, fontSize = if (mono) 16.sp else 13.sp,
            modifier = Modifier.padding(horizontal = if (mono) 14.dp else 10.dp, vertical = 8.dp))
    }
}
