package io.github.eagle10021.pinscope

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

// Finds a Mega in a camera frame or photo.
//  1. The blue PCB gives a first guess of the board outline (or the previous frame's fit, when tracking).
//  2. A "local darkness" map peaks at the header holes. The four board corners are nudged until
//     all 86 model pins sit on dark spots, from a blurry map to a sharp one.
//  3. Both ways round are tried; the pin pattern is lopsided, so only the right one scores well.
// The result maps board millimetres to image pixels.

class Detection(val h: DoubleArray, val quality: Float, val refined: Boolean) {
    fun map(x: Float, y: Float): Offset {
        val w = h[6] * x + h[7] * y + h[8]
        return Offset(((h[0] * x + h[1] * y + h[2]) / w).toFloat(), ((h[3] * x + h[4] * y + h[5]) / w).toFloat())
    }
}

class BoardDetector(private val kit: KitData) {
    var debug = false
    var dumpDir: File? = null
    private var lastDump = 0L
    private fun log(msg: String) { if (debug) Log.d("PinscopeDet", msg) }

    private val bw = kit.board.width
    private val bh = kit.board.height
    private val modelCorners = listOf(Offset(0f, 0f), Offset(bw, 0f), Offset(bw, bh), Offset(0f, bh))
    private val pinPts = kit.pins.map { Offset(it.x, it.y) }

    private val small = Mat(); private val rgb = Mat(); private val hsv = Mat(); private val blue = Mat()
    private val tmp = Mat(); private val darkF = Mat(); private val b1 = Mat(); private val b2 = Mat(); private val dog = Mat(); private val vWork = Mat()
    private val channels = ArrayList<Mat>()
    private val kClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
    private val kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))

    // Tracking state, in this detector's working pixels
    private var lastCorners: List<Offset>? = null
    private var lastTime = 0L
    private var lastSize = Size()

    fun reset() { lastCorners = null }

    fun detect(bitmap: Bitmap): Detection? {
        val m = Mat()
        Utils.bitmapToMat(bitmap, m)
        return try { synchronized(this) { reset(); detect(m, track = false) } } finally { m.release() }
    }

    // rgba: CV_8UC4. Coordinates in the result are in this image's pixels.
    @Synchronized
    fun detect(rgba: Mat, track: Boolean = true, seed: DoubleArray? = null): Detection? {
        val t0 = SystemClock.uptimeMillis()
        val scale = if (max(rgba.cols(), rgba.rows()) > 960) 960.0 / max(rgba.cols(), rgba.rows()) else 1.0
        if (scale < 1.0) Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA) else rgba.copyTo(small)
        Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        channels.forEach { it.release() }; channels.clear()
        Core.split(hsv, channels)
        val v = channels[2]
        val frameArea = small.rows() * small.cols().toDouble()
        if (small.size() != lastSize) { lastSize = small.size(); lastCorners = null }

        // ---------- starting guesses ----------
        val starts = ArrayList<List<Offset>>()
        val seedCorners = seed?.let { h -> modelCorners.map { p ->
            val w = h[6] * p.x + h[7] * p.y + h[8]
            Offset(((h[0] * p.x + h[1] * p.y + h[2]) / w * scale).toFloat(), ((h[3] * p.x + h[4] * p.y + h[5]) / w * scale).toFloat())
        } }
        val prev = seedCorners ?: lastCorners
        val tracking = seedCorners != null || (track && prev != null && t0 - lastTime < 600)
        if (tracking) starts += prev!!
        var shortSide = 0.0
        val quad = if (tracking) null else boardQuad(frameArea)
        if (quad != null) {
            val e = (0 until 4).map { (quad[it] - quad[(it + 1) % 4]).getDistance() }
            var q = quad
            if (e[0] + e[2] < e[1] + e[3]) q = listOf(q[1], q[2], q[3], q[0])
            shortSide = min(e[0] + e[2], e[1] + e[3]) / 2.0
            starts += q
            starts += listOf(q[2], q[3], q[0], q[1]) // turned 180°
        }
        if (starts.isEmpty()) { dump(); return null }
        val pxPerMm: Double = if (tracking) (prev!![0] - prev[3]).getDistance().toDouble() / bh else shortSide / bh
        if (pxPerMm < 2.0) { dump(); log("board too small: ${"%.2f".format(pxPerMm)} px/mm"); return null }

        // ---------- darkness maps (difference of Gaussians on inverted brightness), at about 5 px/mm ----------
        val f = min(1.0, 5.0 / pxPerMm)
        if (f < 1.0) Imgproc.resize(v, vWork, Size(), f, f, Imgproc.INTER_AREA) else v.copyTo(vWork)
        val workPxPerMm = pxPerMm * f
        minSide = 0.12 * min(vWork.cols(), vWork.rows())
        vWork.convertTo(darkF, CvType.CV_32F, -1.0, 255.0)
        val levels = if (tracking) listOf(0.9, 0.45) else listOf(1.8, 0.9, 0.45)
        val maps = levels.map { sigmaMm ->
            val s = max(0.8, sigmaMm * workPxPerMm)
            Imgproc.GaussianBlur(darkF, b1, Size(0.0, 0.0), s)
            Imgproc.GaussianBlur(darkF, b2, Size(0.0, 0.0), s * 3.5)
            Core.subtract(b1, b2, dog)
            val arr = FloatArray(dog.rows() * dog.cols())
            dog.get(0, 0, arr)
            DarkMap(arr, dog.cols(), dog.rows()) to sigmaMm
        }

        // ---------- align each start, keep the best ----------
        var best: List<Offset>? = null; var bestScore = -1e9f
        for (s0w in starts) {
            var c = s0w.map { it * f.toFloat() }
            if (!valid(c)) { log("start invalid"); continue }
            for ((map, sigmaMm) in maps) c = align(c, map, step = (sigmaMm * 2.2 * workPxPerMm).toFloat(), minStep = 0.2f)
            val sc = score(c, maps.last().first)
            log("start score=${"%.2f".format(sc)}")
            if (sc > bestScore) { bestScore = sc; best = c }
        }
        val c = best ?: return null
        if (seedCorners != null && c.indices.any { (c[it] / f.toFloat() - seedCorners[it]).getDistance() > 3.0 * pxPerMm }) {
            log("refine drifted from the learned fit"); return null
        }
        // contrast: pins should be clearly darker than spots half a pitch away
        val sharp = maps.last().first
        val off = listOf(Offset(1.27f, 1.27f), Offset(-1.27f, 1.27f), Offset(1.27f, -1.27f), Offset(-1.27f, -1.27f))
            .map { d -> score(c, sharp, d) }.average().toFloat()
        val contrast = bestScore - off
        val ok = bestScore > 2f && contrast > 2.5f
        log("score=${"%.1f".format(bestScore)} off=${"%.1f".format(off)} ok=$ok tracking=$tracking px/mm=${"%.2f".format(pxPerMm)} ${SystemClock.uptimeMillis() - t0}ms")
        dump()
        if (!ok || !valid(c)) { lastCorners = null; return null }
        val cs = c.map { it / f.toFloat() }
        lastCorners = cs; lastTime = t0

        val full = cs.map { Offset((it.x / scale).toFloat(), (it.y / scale).toFloat()) }
        val hh = homographyMatrix(modelCorners, full) ?: return null
        return Detection(hh, (contrast / 12f).coerceIn(0.3f, 1f), refined = true)
    }

    private class DarkMap(val a: FloatArray, val w: Int, val h: Int) {
        fun at(x: Float, y: Float): Float {
            if (x < 0 || y < 0 || x >= w - 1 || y >= h - 1) return -8f
            val x0 = x.toInt(); val y0 = y.toInt(); val fx = x - x0; val fy = y - y0
            val i = y0 * w + x0
            return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + w] * (1 - fx) + a[i + w + 1] * fx) * fy
        }
    }

    // Mean darkness at the pins for a board whose corners sit at c (optionally shifted in board mm)
    private fun score(c: List<Offset>, m: DarkMap, shift: Offset = Offset.Zero): Float {
        val h = Homography.solve(modelCorners, c) ?: return -1e9f
        var sum = 0f
        for (p in pinPts) { val q = h.map(p.x + shift.x, p.y + shift.y); sum += m.at(q.x, q.y) }
        return sum / pinPts.size
    }

    // Pattern search over the eight corner coordinates, plus whole-board moves
    private fun align(start: List<Offset>, m: DarkMap, step: Float, minStep: Float): List<Offset> {
        val c = start.toMutableList()
        var cur = score(c, m)
        var s = step
        var guard = 0
        while (s >= minStep && guard++ < 400) {
            var improved = false
            for (i in 0 until 4) for (axis in 0 until 2) for (dir in intArrayOf(1, -1)) {
                val old = c[i]
                c[i] = if (axis == 0) Offset(old.x + dir * s, old.y) else Offset(old.x, old.y + dir * s)
                if (!valid(c)) { c[i] = old; continue }
                val sc = score(c, m)
                if (sc > cur + 1e-4f) { cur = sc; improved = true } else c[i] = old
            }
            for ((dx, dy) in listOf(s to 0f, -s to 0f, 0f to s, 0f to -s)) {
                val moved = c.map { Offset(it.x + dx, it.y + dy) }
                val sc = score(moved, m)
                if (sc > cur + 1e-4f) { cur = sc; for (k in 0 until 4) c[k] = moved[k]; improved = true }
            }
            if (!improved) s *= 0.5f
        }
        return c
    }

    // A believable view of the board: convex, near-right angles, board-like proportions, not tiny
    private var minSide = 40.0
    private fun valid(c: List<Offset>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]; val d = c[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            val sg = if (cross > 0) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return false
            val u = a - b; val w = d - b
            val cos = (u.x * w.x + u.y * w.y) / (u.getDistance() * w.getDistance() + 1e-6f)
            if (kotlin.math.abs(cos) > 0.62f) return false // corner angle outside about 52°–128°
        }
        val long = ((c[0] - c[1]).getDistance() + (c[2] - c[3]).getDistance()) / 2
        val short = ((c[1] - c[2]).getDistance() + (c[3] - c[0]).getDistance()) / 2
        val ratio = long / short
        return ratio in 1.3f..2.8f && short >= minSide
    }

    private fun homographyMatrix(src: List<Offset>, dst: List<Offset>): DoubleArray? {
        val m = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(*src.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()),
            MatOfPoint2f(*dst.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()),
        )
        if (m.empty()) return null
        val out = DoubleArray(9); m.get(0, 0, out); m.release()
        return out
    }

    // Outline of the most board-like blue blob, corners clockwise
    private fun boardQuad(frameArea: Double): List<Offset>? {
        val contours = ArrayList<MatOfPoint>()
        var board: MatOfPoint? = null; var area = 0.0
        for ((lo, hi) in listOf(Scalar(92.0, 90.0, 40.0) to Scalar(130.0, 255.0, 190.0), Scalar(90.0, 55.0, 18.0) to Scalar(132.0, 255.0, 170.0))) {
            contours.forEach { it.release() }; contours.clear()
            Core.inRange(hsv, lo, hi, blue)
            Imgproc.morphologyEx(blue, blue, Imgproc.MORPH_CLOSE, kClose)
            Imgproc.morphologyEx(blue, blue, Imgproc.MORPH_OPEN, kOpen)
            Imgproc.findContours(blue, contours, tmp, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            var bestFit = 0.0
            for (c in contours) {
                val a = Imgproc.contourArea(c)
                if (a < frameArea * 0.02) continue
                val rr = Imgproc.minAreaRect(MatOfPoint2f(*c.toArray()))
                val ratio = max(rr.size.width, rr.size.height) / max(1.0, min(rr.size.width, rr.size.height))
                if (ratio > 3.2 || ratio < 1.15) continue
                val fill = a / max(1.0, rr.size.area())
                if (fill < 0.55) continue
                if (a * fill > bestFit) { bestFit = a * fill; board = c; area = a }
            }
            if (board != null) break
        }
        if (board == null) { contours.forEach { it.release() }; log("no board blob"); return null }
        val bb = Imgproc.boundingRect(board)
        val gx = bb.width * 0.15; val gy = bb.height * 0.15
        val merged = ArrayList<Point>()
        for (c in contours) {
            if (c !== board && Imgproc.contourArea(c) < area * 0.01) continue
            val r = Imgproc.boundingRect(c)
            val cx = r.x + r.width / 2.0; val cy = r.y + r.height / 2.0
            if (c === board || (cx > bb.x - gx && cx < bb.x + bb.width + gx && cy > bb.y - gy && cy < bb.y + bb.height + gy)) merged.addAll(c.toList())
        }
        contours.forEach { it.release() }
        val m = MatOfPoint(*merged.toTypedArray())
        val idx = MatOfInt()
        Imgproc.convexHull(m, idx)
        val hp = idx.toArray().map { merged[it] }
        m.release(); idx.release()
        if (hp.size < 4) return null
        val h2 = MatOfPoint2f(*hp.toTypedArray())
        val peri = Imgproc.arcLength(h2, true)
        val approx = MatOfPoint2f()
        var res: List<Point>? = null
        var eps = 0.01
        while (eps <= 0.08) {
            Imgproc.approxPolyDP(h2, approx, eps * peri, true)
            if (approx.rows() == 4) { res = approx.toArray().toList(); break }
            if (approx.rows() < 4) break
            eps += 0.005
        }
        if (res == null) {
            val p = arrayOfNulls<Point>(4); Imgproc.minAreaRect(h2).points(p); res = p.filterNotNull()
        }
        h2.release(); approx.release()
        val cx = res.sumOf { it.x } / 4; val cy = res.sumOf { it.y } / 4
        return res.sortedBy { atan2(it.y - cy, it.x - cx) }.map { Offset(it.x.toFloat(), it.y.toFloat()) }
    }

    private fun dump() {
        val dir = dumpDir ?: return
        if (!debug || System.currentTimeMillis() - lastDump < 4000) return
        lastDump = System.currentTimeMillis()
        Imgcodecs.imwrite(File(dir, "frame.png").path, Mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_RGBA2BGR) })
        Imgcodecs.imwrite(File(dir, "blue.png").path, blue)
        if (!dog.empty()) {
            val vis = Mat(); Core.normalize(dog, vis, 0.0, 255.0, Core.NORM_MINMAX); vis.convertTo(vis, CvType.CV_8U)
            Imgcodecs.imwrite(File(dir, "dark.png").path, vis); vis.release()
        }
    }
}
