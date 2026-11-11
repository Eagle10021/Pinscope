package io.github.eagle10021.pinscope

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

// "Learn your board": a flattened, top-down picture of the user's own Mega is reduced to ORB feature
// points, each tied to a position in board millimetres. Live frames are matched against them, so the
// board's own silkscreen and parts act as the landmarks.

class BoardMemory private constructor(
    private val pts: FloatArray,   // x0, y0, x1, y1… in board mm
    private val desc: Mat,         // N × 32 ORB descriptors
) {
    val size get() = pts.size / 2

    companion object {
        const val PX_PER_MM = 8.0
        private const val FILE = "board-memory.bin"

        // Builds a memory from a photo and the mapping board mm → photo pixels
        fun learn(photo: Bitmap, boardToPhoto: Homography, kit: KitData): BoardMemory? {
            val src = Mat(); Utils.bitmapToMat(photo, src)
            val gray = Mat(); Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY); src.release()
            val w = kit.board.width; val h = kit.board.height
            val corners = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
            val imgPts = corners.map { boardToPhoto.map(it.x, it.y) }
            val refPts = corners.map { Offset((it.x * PX_PER_MM).toFloat(), (it.y * PX_PER_MM).toFloat()) }
            val m = Imgproc.getPerspectiveTransform(mat2f(imgPts), mat2f(refPts))
            val ref = Mat()
            Imgproc.warpPerspective(gray, ref, m, Size(w * PX_PER_MM, h * PX_PER_MM), Imgproc.INTER_AREA)
            gray.release(); m.release()
            // even out lighting so features survive brightness changes
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(ref, ref)
            val orb = ORB.create(1200, 1.2f, 8, 21, 0, 2, ORB.HARRIS_SCORE, 21, 12)
            val kps = MatOfKeyPoint(); val desc = Mat()
            orb.detectAndCompute(ref, Mat(), kps, desc)
            ref.release()
            val kp = kps.toArray(); kps.release()
            if (kp.size < 60) { desc.release(); return null }
            val pts = FloatArray(kp.size * 2)
            kp.forEachIndexed { i, k -> pts[2 * i] = (k.pt.x / PX_PER_MM).toFloat(); pts[2 * i + 1] = (k.pt.y / PX_PER_MM).toFloat() }
            return BoardMemory(pts, desc)
        }

        fun load(dir: File): BoardMemory? = runCatching { read(File(dir, FILE).inputStream()) }.getOrNull()

        // Bundled preset (assets/boards/<name>.bin), same format as a saved memory
        fun preset(context: android.content.Context, name: String): BoardMemory? =
            runCatching { read(context.assets.open("boards/$name.bin")) }.getOrNull()

        private fun read(stream: java.io.InputStream): BoardMemory =
            DataInputStream(stream.buffered()).use { inp ->
                val n = inp.readInt()
                val pts = FloatArray(n * 2) { inp.readFloat() }
                val bytes = ByteArray(n * 32); inp.readFully(bytes)
                val desc = Mat(n, 32, CvType.CV_8U); desc.put(0, 0, bytes)
                BoardMemory(pts, desc)
            }

        fun forget(dir: File) { File(dir, FILE).delete() }

        private fun mat2f(p: List<Offset>) = MatOfPoint2f(*p.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
    }

    // Adds another photo's features, so one memory covers several angles and lighting
    fun merged(other: BoardMemory, cap: Int = 6000): BoardMemory {
        val n = minOf(size + other.size, cap)
        val keepOld = n - other.size
        val pts2 = FloatArray(n * 2)
        val skip = size - keepOld.coerceAtLeast(0)
        System.arraycopy(pts, skip * 2, pts2, 0, keepOld.coerceAtLeast(0) * 2)
        System.arraycopy(other.pts, 0, pts2, keepOld.coerceAtLeast(0) * 2, other.size * 2)
        val d = Mat()
        org.opencv.core.Core.vconcat(listOf(desc.rowRange(skip, size), other.desc), d)
        return BoardMemory(pts2, d)
    }

    fun save(dir: File) {
        DataOutputStream(File(dir, FILE).outputStream().buffered()).use { out ->
            out.writeInt(size)
            pts.forEach { out.writeFloat(it) }
            val bytes = ByteArray(size * 32); desc.get(0, 0, bytes); out.write(bytes)
        }
    }

    // ---------- live matching ----------
    private val orb = ORB.create(1500, 1.2f, 8, 21, 0, 2, ORB.HARRIS_SCORE, 21, 12)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
    private val gray = Mat(); private val small = Mat()
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))

    var debug = false

    // ---------- frame-to-frame flow between full matches ----------
    private val prevGray = Mat()
    private var flowImg: MatOfPoint2f? = null   // tracked points in the working image
    private var flowModel: List<Point> = emptyList() // the same points in board mm
    private var sinceKey = 0
    private var lastScale = 0.0

    fun resetFlow() { flowImg = null; sinceKey = 0 }

    // Live frames: optical flow most of the time, a full feature match every few frames
    @Synchronized
    fun trackLive(rgba: Mat, kit: KitData): Pair<DoubleArray, Int>? {
        val maxSide = 720
        val scale = if (maxOf(rgba.cols(), rgba.rows()) > maxSide) maxSide.toDouble() / maxOf(rgba.cols(), rgba.rows()) else 1.0
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        if (scale < 1.0) Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA) else gray.copyTo(small)
        clahe.apply(small, small)
        val prev = flowImg
        if (prev != null && sinceKey < 10 && scale == lastScale && !prevGray.empty()) {
            val t0 = SystemClock.uptimeMillis()
            val next = MatOfPoint2f(); val status = org.opencv.core.MatOfByte(); val err = org.opencv.core.MatOfFloat()
            org.opencv.video.Video.calcOpticalFlowPyrLK(prevGray, small, prev, next, status, err, Size(21.0, 21.0), 3)
            val st = status.toArray(); val np = next.toArray()
            val src = ArrayList<Point>(); val dst = ArrayList<Point>()
            for (i in st.indices) if (st[i].toInt() == 1) { src += flowModel[i]; dst += np[i] }
            status.release(); err.release(); next.release()
            if (src.size >= 25) {
                val inl = Mat()
                val hm = Calib3d.findHomography(MatOfPoint2f(*src.toTypedArray()), MatOfPoint2f(*dst.toTypedArray()), Calib3d.RANSAC, 3.0, inl, 500, 0.99)
                val n = if (inl.empty()) 0 else Core.countNonZero(inl)
                if (!hm.empty() && n >= 25) {
                    // keep only inliers for the next frame
                    val mask = ByteArray(inl.rows()); inl.get(0, 0, mask)
                    val keepImg = ArrayList<Point>(); val keepModel = ArrayList<Point>()
                    for (i in mask.indices) if (mask[i].toInt() != 0) { keepImg += dst[i]; keepModel += src[i] }
                    flowImg = MatOfPoint2f(*keepImg.toTypedArray()); flowModel = keepModel
                    small.copyTo(prevGray); sinceKey++
                    val out = DoubleArray(9); hm.get(0, 0, out); hm.release(); inl.release()
                    for (i in 0 until 6) out[i] /= scale
                    if (debug) Log.d("PinscopeMem", "flow n=$n ${SystemClock.uptimeMillis() - t0}ms")
                    return out to n
                }
                hm.release(); inl.release()
            }
            flowImg = null
        }
        val res = matchSmall(scale, keepForFlow = true) ?: run { flowImg = null; return null }
        small.copyTo(prevGray); lastScale = scale; sinceKey = 0
        return res
    }

    // rgba frame → board mm to frame pixels homography and inlier count, or null
    @Synchronized
    fun track(rgba: Mat, kit: KitData, maxSide: Int = 960): Pair<DoubleArray, Int>? {
        val scale = if (maxOf(rgba.cols(), rgba.rows()) > maxSide) maxSide.toDouble() / maxOf(rgba.cols(), rgba.rows()) else 1.0
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        if (scale < 1.0) Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA) else gray.copyTo(small)
        clahe.apply(small, small)
        return matchSmall(scale, keepForFlow = false)
    }

    private fun matchSmall(scale: Double, keepForFlow: Boolean): Pair<DoubleArray, Int>? {
        val t0 = SystemClock.uptimeMillis()
        val kps = MatOfKeyPoint(); val d = Mat()
        orb.detectAndCompute(small, Mat(), kps, d)
        val kp = kps.toArray(); kps.release()
        if (kp.size < 20 || d.empty()) { d.release(); return null }
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(d, desc, knn, 2)
        d.release()
        val src = ArrayList<Point>(); val dst = ArrayList<Point>()
        for (mm in knn) {
            val two: Array<DMatch> = mm.toArray(); mm.release()
            if (two.size < 2 || two[0].distance > 0.78f * two[1].distance) continue
            val t = two[0].trainIdx; val q = two[0].queryIdx
            src += Point(pts[2 * t].toDouble(), pts[2 * t + 1].toDouble())
            dst += Point(kp[q].pt.x, kp[q].pt.y)
        }
        if (src.size < 12) { if (debug) Log.d("PinscopeMem", "matches=${src.size}"); return null }
        val inl = Mat()
        val hm = Calib3d.findHomography(MatOfPoint2f(*src.toTypedArray()), MatOfPoint2f(*dst.toTypedArray()), Calib3d.RANSAC, 4.0, inl, 2000, 0.995)
        val n = if (inl.empty()) 0 else Core.countNonZero(inl)
        val mask = ByteArray(src.size)
        if (!inl.empty()) inl.get(0, 0, mask)
        inl.release()
        if (debug) Log.d("PinscopeMem", "kp=${kp.size} good=${src.size} inliers=$n ${SystemClock.uptimeMillis() - t0}ms")
        if (hm.empty() || n < 12) { hm.release(); return null }
        if (keepForFlow) {
            val keepImg = ArrayList<Point>(); val keepModel = ArrayList<Point>()
            for (i in mask.indices) if (mask[i].toInt() != 0) { keepImg += dst[i]; keepModel += src[i] }
            flowImg = MatOfPoint2f(*keepImg.toTypedArray()); flowModel = keepModel
        }
        val out = DoubleArray(9); hm.get(0, 0, out); hm.release()
        for (i in 0 until 6) out[i] /= scale
        return out to n
    }
}
