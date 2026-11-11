package io.github.eagle10021.pinscope

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot

// Perspective mapping from board millimetres to photo pixels, solved from four point pairs.
class Homography private constructor(private val h: DoubleArray) {

    fun map(x: Float, y: Float): Offset {
        val w = h[6] * x + h[7] * y + 1.0
        return Offset(((h[0] * x + h[1] * y + h[2]) / w).toFloat(), ((h[3] * x + h[4] * y + h[5]) / w).toFloat())
    }

    // Approximate pixels per millimetre around a board point, used to size pin markers
    fun scaleAt(x: Float, y: Float): Float {
        val a = map(x, y)
        val b = map(x + 1f, y)
        val c = map(x, y + 1f)
        return ((hypot(b.x - a.x, b.y - a.y) + hypot(c.x - a.x, c.y - a.y)) / 2f)
    }

    companion object {
        // src: board points (mm), dst: image points (px). Returns null for degenerate input.
        fun solve(src: List<Offset>, dst: List<Offset>): Homography? {
            require(src.size == 4 && dst.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i].x.toDouble() to src[i].y.toDouble()
                val (u, v) = dst[i].x.toDouble() to dst[i].y.toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            // Gaussian elimination with partial pivoting
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-9) return null
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                for (r in 0 until 8) if (r != col) {
                    val f = a[r][col] / a[col][col]
                    if (f != 0.0) for (c in col until 9) a[r][c] -= f * a[col][c]
                }
            }
            val h = DoubleArray(8) { a[it][8] / a[it][it] }
            if (h.any { it.isNaN() || it.isInfinite() }) return null
            return Homography(h)
        }
    }
}
