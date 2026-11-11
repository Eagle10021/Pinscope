package io.github.eagle10021.pinscope

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// Compiles Arduino sketches for the Mega on the phone itself (tools/build-toolchain.py).
// GCC's programs are musl builds shipped as native libraries; each one runs through musl's loader.

class OnDeviceCompiler(private val context: Context) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val root = File(context.filesDir, "toolchain")
    private val sys = File(root, "sys")
    private val libDir = File(root, "lib")

    val available: Boolean get() = File(nativeDir, "libpscc1plus.so").exists() && File(nativeDir, "libpsmusl.so").exists()

    // Unpacks the sysroot and links the shared libraries under the names the programs ask for
    @Synchronized
    private fun prepare() {
        val stamp = File(root, "stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version && File(sys, "index.json").exists()) return
        root.deleteRecursively()
        sys.mkdirs(); libDir.mkdirs()
        ZipInputStream(context.assets.open("toolchain/sysroot.zip").buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(sys, e.name)
                if (!out.canonicalPath.startsWith(sys.canonicalPath)) continue
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
            }
        }
        val links = JSONObject(context.assets.open("toolchain/native.json").bufferedReader().use { it.readText() }).getJSONObject("links")
        for (soname in links.keys()) Os.symlink(File(nativeDir, links.getString(soname)).path, File(libDir, soname).path)
        stamp.writeText(version)
    }

    private fun run(tool: String, args: List<String>, dir: File): Pair<Int, String> {
        val cmd = listOf(File(nativeDir, "libpsmusl.so").path, "--library-path", libDir.path, File(nativeDir, tool).path) + args
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).apply { environment()["TMPDIR"] = context.cacheDir.path }.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "Timed out" }
        return p.exitValue() to out
    }

    fun compile(source: String): CompileResult {
        prepare()
        val work = File(context.cacheDir, "build").apply { deleteRecursively(); mkdirs() }
        File(work, "sketch.cpp").writeText(Sketch.toCpp(source))
        val index = JSONObject(File(sys, "index.json").readText()).getJSONArray("libraries")
        val libIncludes = (0 until index.length()).map { "-I" + File(sys, index.getJSONObject(it).getString("dir")).path }
        val archives = (0 until index.length()).mapNotNull { index.getJSONObject(it).optString("archive").takeIf { a -> a.isNotEmpty() && a != "null" }?.let { a -> File(sys, a).path } }

        val cc = listOf(
            "-quiet", "-nostdinc",
            "-isystem", "$sys/gcc/include", "-isystem", "$sys/gcc/include-fixed", "-isystem", "$sys/avr/include",
            "-I$sys/arduino/core", "-I$sys/arduino/variant",
        ) + libIncludes + listOf(
            "-mmcu=avr6", "-mn-flash=4", "-mno-skip-bug",
            "-D__AVR_ATmega2560__", "-D__AVR_DEVICE_NAME__=atmega2560", "-D__AVR_RODATA_IN_RAM__=1",
            "-DF_CPU=16000000L", "-DARDUINO=10607", "-DARDUINO_AVR_MEGA2560", "-DARDUINO_ARCH_AVR",
            "-Os", "-std=gnu++17", "-fpermissive", "-fno-exceptions", "-fno-threadsafe-statics",
            "-ffunction-sections", "-fdata-sections", "-Wall", "-Wno-unused-variable",
            "sketch.cpp", "-o", "sketch.s",
        )
        val (ccCode, ccOut) = run("libpscc1plus.so", cc, work)
        if (ccCode != 0) return failure(ccOut)
        val (asCode, asOut) = run("libpsas.so", listOf("-mmcu=avr6", "-mno-skip-bug", "-mgcc-isr", "-o", "sketch.o", "sketch.s"), work)
        if (asCode != 0) return failure(asOut)
        val ld = listOf("-m", "avr6", "-T", "$sys/ldscripts/avr6.xn", "-Tdata", "0x800200", "--gc-sections", "-o", "sketch.elf",
            "$sys/avr/lib/crtatmega2560.o", "sketch.o", "--start-group", "$sys/arduino/core.a") + archives +
            listOf("$sys/gcc/libgcc.a", "$sys/avr/lib/libm.a", "$sys/avr/lib/libc.a", "$sys/avr/lib/libatmega2560.a", "--end-group")
        val (ldCode, ldOut) = run("libpsld.so", ld, work)
        if (ldCode != 0) return failure(ldOut)
        val (ocCode, ocOut) = run("libpsobjcopy.so", listOf("-O", "ihex", "-R", ".eeprom", "sketch.elf", "sketch.hex"), work)
        if (ocCode != 0) return failure(ocOut)
        val hex = File(work, "sketch.hex").readText()
        val size = runCatching { IntelHex.parse(hex).size }.getOrNull()
        if (size != null && size > 253952) return CompileResult(null, size, emptyList(), "The sketch is ${size / 1024} KB; the Mega has room for 248 KB.")
        // warnings still come back with a successful build
        return CompileResult(hex, size, parseErrors(ccOut).filter { it.warning }, ccOut.trim())
    }

    private fun failure(out: String): CompileResult {
        val msg = out.replace(Regex("""\S*/libps\w+\.so: """), "").replace(sys.path + "/", "").replace(Regex("""\([^()]*\.o\)"""), "").trim().ifEmpty { "Compile failed" }
        return CompileResult(null, null, parseErrors(msg), msg)
    }

    companion object {
        fun parseErrors(msg: String) = Regex("""sketch\.ino:(\d+):(\d+): (error|warning|fatal error): (.*)""").findAll(msg).map {
            CompileError(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[4], it.groupValues[3] == "warning")
        }.toList()
    }
}

// The Arduino IDE's .ino handling: include Arduino.h and declare functions before they are used
object Sketch {
    private val SKIP = setOf("if", "for", "while", "switch", "else", "return", "do", "sizeof", "catch")
    private val DEF = Regex("""(?m)^[ \t]*((?:[A-Za-z_][\w:<>,]*[ \t*&]+)+?)([A-Za-z_]\w*)[ \t]*\(([^;{}]*)\)[ \t\r\n]*(?:const[ \t\r\n]*)?\{""")

    fun toCpp(src: String): String {
        val clean = blankOut(src)
        // brace depth at each position, so only top-level functions count
        val depth = IntArray(clean.length + 1)
        var d = 0
        for (i in clean.indices) { depth[i] = d; if (clean[i] == '{') d++ else if (clean[i] == '}') d = maxOf(0, d - 1) }
        val protos = ArrayList<String>()
        var firstAt = -1
        for (m in DEF.findAll(clean)) {
            if (depth[m.range.first] != 0) continue
            val type = m.groupValues[1].trim(); val name = m.groupValues[2]; val params = m.groupValues[3]
            if (name in SKIP || type.split(Regex("\\s+")).any { it in SKIP } || type.startsWith("#")) continue
            if ('=' in params) continue // default arguments must stay on the definition only
            if (firstAt < 0) firstAt = m.range.first
            protos += "$type $name(${src.substring(m.groups[3]!!.range).replace(Regex("\\s+"), " ").trim()});"
        }
        val sb = StringBuilder("#include <Arduino.h>\n#line 1 \"sketch.ino\"\n")
        if (firstAt < 0 || protos.isEmpty()) return sb.append(src).toString()
        val lineStart = src.lastIndexOf('\n', firstAt - 1) + 1
        val lineNo = src.substring(0, lineStart).count { it == '\n' } + 1
        sb.append(src, 0, lineStart)
        protos.forEach { sb.append(it).append('\n') }
        sb.append("#line $lineNo \"sketch.ino\"\n").append(src.substring(lineStart))
        return sb.toString()
    }

    // Replaces comments, strings and preprocessor lines with spaces, keeping positions
    private fun blankOut(s: String): String {
        val c = s.toCharArray()
        var i = 0
        fun blank(from: Int, to: Int) { for (k in from until minOf(to, c.size)) if (c[k] != '\n') c[k] = ' ' }
        while (i < c.size) {
            when {
                s.startsWith("//", i) -> { val e = s.indexOf('\n', i).let { if (it < 0) c.size else it }; blank(i, e); i = e }
                s.startsWith("/*", i) -> { val e = s.indexOf("*/", i + 2).let { if (it < 0) c.size else it + 2 }; blank(i, e); i = e }
                c[i] == '"' || c[i] == '\'' -> {
                    val q = c[i]; var e = i + 1
                    while (e < c.size && c[e] != q && c[e] != '\n') { if (c[e] == '\\') e++; e++ }
                    blank(i, e + 1); i = e + 1
                }
                c[i] == '#' && (i == 0 || s.lastIndexOf('\n', i - 1).let { nl -> s.substring(nl + 1, i).isBlank() }) -> {
                    val e = s.indexOf('\n', i).let { if (it < 0) c.size else it }; blank(i, e); i = e
                }
                else -> i++
            }
        }
        return String(c)
    }
}
