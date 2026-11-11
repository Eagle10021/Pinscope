package io.github.eagle10021.pinscope

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// A newer release on GitHub: its version, notes and APK
data class AppUpdate(val version: String, val title: String, val notes: String, val apkUrl: String?, val pageUrl: String, val prerelease: Boolean)

// Checks the GitHub releases (pre-releases included, as Pinscope ships betas) for a newer version than the installed one,
// downloads its APK and hands it to Android's package installer. Updates install over the old app because both are
// signed with the same key.
object Updater {
    private const val REPO = "Eagle10021/Pinscope"

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    // "v0.1.1-beta" → [0, 1, 1]
    fun numbers(v: String): List<Int> = Regex("""\d+""").findAll(v.removePrefix("v").substringBefore('-')).map { it.value.toInt() }.toList()

    fun newer(a: String, b: String): Boolean {
        val x = numbers(a); val y = numbers(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    // Network call; run off the main thread. Null when up to date or offline.
    fun check(current: String): AppUpdate? = runCatching {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=10").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Pinscope-app")
        c.connectTimeout = 8000; c.readTimeout = 8000
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val a = JSONArray(text)
        val releases = (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("draft") }
        val best = releases.maxWithOrNull { r1, r2 -> if (newer(r1.getString("tag_name"), r2.getString("tag_name"))) 1 else if (newer(r2.getString("tag_name"), r1.getString("tag_name"))) -1 else 0 }
            ?: return@runCatching null
        val tag = best.getString("tag_name")
        if (!newer(tag, current)) return@runCatching null
        val assets = best.optJSONArray("assets") ?: JSONArray()
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
        AppUpdate(
            version = tag.removePrefix("v"),
            title = best.optString("name").ifBlank { tag },
            notes = best.optString("body"),
            apkUrl = apk?.getString("browser_download_url"),
            pageUrl = best.getString("html_url"),
            prerelease = best.optBoolean("prerelease"),
        )
    }.getOrNull()

    // Downloads the APK into the cache; progress 0–1. Network call; run off the main thread.
    fun download(context: Context, url: String, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Pinscope-update.apk")
        var c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Pinscope-app")
        c.instanceFollowRedirects = true
        // GitHub sends release assets through a redirect to another host
        var hops = 0
        while (c.responseCode in 300..399 && hops++ < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect(); c = URL(next).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Pinscope-app")
        }
        if (c.responseCode != 200) error("Download failed (HTTP ${c.responseCode})")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        return out
    }

    // Opens Android's installer. The first time, Android asks to allow installs from Pinscope; returns false if that
    // settings screen was opened instead, so the caller can offer to try again.
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
