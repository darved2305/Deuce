package com.elevate.app.domain.review

import com.elevate.app.domain.analysis.BallTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The magnified "line view": the court seen from above, turned so the boundary line runs left to right
 * and the inside of the court is DOWN the screen. [u] runs along the line (right), [v] is the distance
 * from the line's outer edge (+ inside, down). A pure rotation of the court (no mirror), re-centred on
 * the landing so u = 0 there. Everything the picture draws comes from the [LineCall], nothing else.
 */
class LineFrame private constructor(
    /** Landing in the frame: (0, signed distance). */
    val landingV: Double,
    /** Covariance in (u, v) metres squared. */
    val uu: Double,
    val uv: Double,
    val vv: Double,
    val marginM: Double,
) {
    /** Half-width of the 95% ellipse along v, i.e. the band that decides the call. */
    val bandV: Double get() = LineCall.Z * sqrt(vv)

    /** Points of the 95% ellipse (k = 1.96) as (u, v) around the landing, [steps] of them. */
    fun ellipse(steps: Int = 72): List<Pair<Double, Double>> {
        val a = sqrt(uu)
        val b = uv / a
        val c = sqrt(max(0.0, vv - b * b))
        return (0 until steps).map { i ->
            val th = 2 * Math.PI * i / steps
            val cs = Math.cos(th)
            val sn = Math.sin(th)
            val k = LineCall.Z
            // L = [[a, 0], [b, c]] gives L L^T = Sigma.
            (k * a * cs) to (landingV + k * (b * cs + c * sn))
        }
    }

    /**
     * The visible v-range [lo, hi] (metres) when fully zoomed in: the line paint, the landing, and the whole
     * margin band, with breathing room and never tighter than [MIN_SPAN_M] so the 40 mm line stays readable.
     */
    fun zoomedRange(): Pair<Double, Double> {
        var lo = min(-0.02, min(landingV, landingV - bandV))
        var hi = max(BoundaryBox.LINE_WIDTH_M + 0.02, max(landingV, landingV + bandV))
        val pad = (hi - lo) * 0.22
        lo -= pad; hi += pad
        if (hi - lo < MIN_SPAN_M) {
            val mid = (hi + lo) / 2
            lo = mid - MIN_SPAN_M / 2; hi = mid + MIN_SPAN_M / 2
        }
        return lo to hi
    }

    companion object {
        const val MIN_SPAN_M = 0.30

        /** Null for a landing with no call (nothing to magnify). */
        fun of(call: LineCall): LineFrame? {
            val line = call.line ?: return null
            val box = call.box ?: return null
            val cov = call.covUsed ?: return null
            val (nx, ny) = box.normal(line)
            // Screen-right tangent for "inside is down": a rotation, so the picture is never mirrored.
            val tx = -ny
            val ty = nx
            val uu = cov.varianceAlong(tx, ty)
            val vv = cov.varianceAlong(nx, ny)
            val uv = tx * nx * cov.xx + (tx * ny + ty * nx) * cov.xy + ty * ny * cov.yy
            return LineFrame(call.distanceM, uu, uv, vv, call.marginM)
        }

        /** A friendly ruler step in cm so ticks stay at least [minPx] apart: 1, 2, 5, 10, 20, 50, 100, 200, 500. */
        fun rulerStepCm(pxPerCm: Double, minPx: Double = 7.0): Int {
            for (s in listOf(1, 2, 5, 10, 20, 50, 100, 200, 500)) if (s * pxPerCm >= minPx) return s
            return 1000
        }
    }
}

/** The shuttle's real positions just before it touched the floor, for the tiny flight picture. */
object FlightTrail {
    const val WINDOW_MS = 700L

    /** Observed ball rows in image pixels within [WINDOW_MS] before [tMs] (and a hair after it), oldest first. */
    fun before(ball: BallTrack?, tMs: Long): List<Pair<Float, Float>> {
        if (ball == null || ball.size == 0) return emptyList()
        val lo = (tMs - WINDOW_MS) * 1000
        val hi = (tMs + 40) * 1000
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until ball.size) {
            val t = ball.tUs[i]
            if (t in lo..hi && ball.state[i] == BallTrack.OBSERVED) out += ball.x[i] to ball.y[i]
        }
        return if (out.size >= 3) out else emptyList()
    }

    /** Points scaled into a unit box (aspect kept), y down, centred; for a thumbnail drawing. */
    fun normalised(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        val minX = points.minOf { it.first }; val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }; val maxY = points.maxOf { it.second }
        val span = max(maxX - minX, maxY - minY).takeIf { it > 1e-3f } ?: 1f
        val offX = (span - (maxX - minX)) / 2
        val offY = (span - (maxY - minY)) / 2
        return points.map { ((it.first - minX + offX) / span) to ((it.second - minY + offY) / span) }
    }
}
