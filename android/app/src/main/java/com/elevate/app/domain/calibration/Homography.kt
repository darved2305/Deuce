package com.elevate.app.domain.calibration

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A 3×3 homography between the court plane (metres) and the image (pixels), fitted exactly to the
 * four tapped corners by a direct linear transform — no libraries, no iteration. [h] maps
 * court → image; [hInv] maps image → court. Both are row-major.
 *
 * [fromFourPoints] returns null when the four correspondences are degenerate (three on a line,
 * coincident points): the 8×8 solve reports it as a vanished pivot.
 */
class Homography private constructor(
    /** Court metres → image pixels. */
    val h: DoubleArray,
    /** Image pixels → court metres. Normalised so the four tapped corners have w > 0. */
    val hInv: DoubleArray,
) {
    /** Court metres → image pixels. Points near the vanishing line blow up; callers clip. */
    fun toImage(x: Double, y: Double): ImagePoint {
        val w = h[6] * x + h[7] * y + h[8]
        return ImagePoint((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    /** Image pixels → court metres. */
    fun toCourt(px: Double, py: Double): Pair<Double, Double> {
        val w = hInv[6] * px + hInv[7] * py + hInv[8]
        return Pair(
            (hInv[0] * px + hInv[1] * py + hInv[2]) / w,
            (hInv[3] * px + hInv[4] * py + hInv[5]) / w,
        )
    }

    /**
     * Image pixels → unnormalised court coordinates [x, y, w]: the court point is (x/w, y/w).
     * w ≤ 0 means the pixel sits on or beyond the horizon — it has no court-plane point in front
     * of the camera — so callers can clip instead of trusting the division.
     */
    fun projectToCourt(px: Double, py: Double): DoubleArray = doubleArrayOf(
        hInv[0] * px + hInv[1] * py + hInv[2],
        hInv[3] * px + hInv[4] * py + hInv[5],
        hInv[6] * px + hInv[7] * py + hInv[8],
    )

    companion object {
        /** Below this pivot the 8×8 system is singular (normalised coordinates keep terms ~1). */
        private const val EPS = 1e-10

        /**
         * Exact homography from four corner correspondences in the same order (contract order:
         * near_left, near_right, far_right, far_left). Each point set is isotropically normalised
         * (Hartley) before the solve so conditioning does not depend on frame size; the result is
         * denormalised back to court metres → image pixels.
         */
        fun fromFourPoints(court: List<Pair<Double, Double>>, image: List<ImagePoint>): Homography? {
            if (court.size != 4 || image.size != 4) return null
            val tc = normalise(court.map { it.first }, court.map { it.second }) ?: return null
            val ti = normalise(image.map { it.x }, image.map { it.y }) ?: return null
            // h33 = 1: for each pair, rows [x y 1 0 0 0 -ux -uy] = u and [0 0 0 x y 1 -vx -vy] = v.
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val xn = tc[0] * court[i].first + tc[1] * court[i].second + tc[2]
                val yn = tc[3] * court[i].first + tc[4] * court[i].second + tc[5]
                val un = ti[0] * image[i].x + ti[1] * image[i].y + ti[2]
                val vn = ti[3] * image[i].x + ti[4] * image[i].y + ti[5]
                a[2 * i] = doubleArrayOf(xn, yn, 1.0, 0.0, 0.0, 0.0, -un * xn, -un * yn)
                b[2 * i] = un
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, xn, yn, 1.0, -vn * xn, -vn * yn)
                b[2 * i + 1] = vn
            }
            val s = solve(a, b) ?: return null
            val hn = doubleArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], 1.0)
            // Denormalise: image = ti⁻¹ · Hn · tc · court. ti is a similarity, always invertible.
            val h = mul3(inv3(ti)!!, mul3(hn, tc))
            val hInv = inv3(h) ?: return null
            // The map is unchanged by a global sign; fix it so tapped corners read w > 0 (they are
            // in front of the camera by construction), which gives w its horizon meaning.
            if (image.sumOf { hInv[6] * it.x + hInv[7] * it.y + hInv[8] } < 0) {
                for (i in 0 until 9) hInv[i] = -hInv[i]
            }
            return Homography(h, hInv)
        }

        /** Row-major 3×3 product a·b. */
        private fun mul3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
            val r = i / 3
            val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[c + 3] + a[r * 3 + 2] * b[c + 6]
        }

        /** Row-major 3×3 inverse, null when singular within the matrix's own scale. */
        private fun inv3(m: DoubleArray): DoubleArray? {
            val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
                m[2] * (m[3] * m[7] - m[4] * m[6])
            val peak = m.maxOf { abs(it) }
            if (peak <= 0 || abs(det) <= EPS * peak * peak * peak) return null
            return doubleArrayOf(
                (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
            )
        }

        /** Hartley normalisation: origin at the centroid, scaled so the mean distance is √2. */
        private fun normalise(xs: List<Double>, ys: List<Double>): DoubleArray? {
            val cx = xs.average()
            val cy = ys.average()
            val mean = xs.indices.sumOf { hypot(xs[it] - cx, ys[it] - cy) } / xs.size
            if (!(mean > 0)) return null
            val s = sqrt(2.0) / mean
            return doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
        }

        /** Gaussian elimination with partial pivoting on the 8×8 system [a]x = b. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) <= EPS) return null
                if (piv != col) {
                    val row = a[piv]; a[piv] = a[col]; a[col] = row
                    val t = b[piv]; b[piv] = b[col]; b[col] = t
                }
                for (r in col + 1 until n) {
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (cc in col until n) a[r][cc] -= f * a[col][cc]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var sum = b[r]
                for (cc in r + 1 until n) sum -= a[r][cc] * x[cc]
                x[r] = sum / a[r][r]
            }
            return x
        }
    }
}
