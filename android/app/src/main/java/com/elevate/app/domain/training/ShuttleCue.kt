package com.elevate.app.domain.training

import com.elevate.app.domain.analysis.BallTrack
import kotlin.math.hypot
import kotlin.math.min

/**
 * Where the shuttle is, and which way it is moving, on a frozen frame. Built only from real
 * observed ball rows up to the freeze time, so nothing from the future can leak: it shows direction
 * of travel, never the landing. Pure; no clock, no I/O.
 */
object ShuttleCue {
    /** Rows older than this before the freeze are not used. */
    const val WINDOW_MS = 200L

    /** The newest row must be this close to the freeze, so the ring sits on the shuttle actually in the frame. */
    const val MAX_STALE_MS = 100L

    /** Upper bound of dots in the faint tail (not counting the current position). */
    const val MAX_TAIL = 3

    /** Below this speed (px per second) the direction is noise and no arrow is drawn. */
    const val MIN_SPEED_PX_PER_S = 30f

    /** A real observed position in image pixels. */
    data class Point(val tMs: Long, val x: Float, val y: Float)

    /**
     * [at] is the last position ([x], [y], image pixels), the smoothed velocity ([vxPxPerS],
     * [vyPxPerS]) and the older real positions ([tail], oldest first, at most [MAX_TAIL]).
     */
    data class Cue(
        val x: Float,
        val y: Float,
        val vxPxPerS: Float,
        val vyPxPerS: Float,
        val tail: List<Point>,
    ) {
        val speedPxPerS: Float get() = hypot(vxPxPerS, vyPxPerS)

        /** False when the shuttle is nearly still in the image: draw the ring only. */
        val hasDirection: Boolean get() = speedPxPerS >= MIN_SPEED_PX_PER_S

        /** Unit direction of travel in image pixels (y down), or (0, 0) without [hasDirection]. */
        val dirX: Float get() = if (hasDirection) vxPxPerS / speedPxPerS else 0f
        val dirY: Float get() = if (hasDirection) vyPxPerS / speedPxPerS else 0f
    }

    /**
     * The cue at [freezeMs] (source time, ms), or null when there are fewer than two distinct
     * observed rows in `[freezeMs - WINDOW_MS, freezeMs]`, or the newest is more than
     * [MAX_STALE_MS] before the freeze. Rows after [freezeMs], and rows whose state is not
     * observed (candidate, inferred, user), are never used.
     */
    fun at(freezeMs: Long, ball: BallTrack): Cue? {
        val limitUs = freezeMs * 1000
        val fromUs = (freezeMs - WINDOW_MS) * 1000
        // Last row at or before the freeze (rows are sorted by time).
        var lo = 0
        var hi = ball.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ball.tUs[mid] <= limitUs) lo = mid + 1 else hi = mid
        }
        // Walk back through the window, one point per timestamp (the most confident wins).
        val pts = ArrayList<Point>()
        val conf = ArrayList<Float>()
        var i = lo - 1
        while (i >= 0 && ball.tUs[i] >= fromUs) {
            val x = ball.x[i]
            val y = ball.y[i]
            if (ball.state[i] == BallTrack.OBSERVED && x.isFinite() && y.isFinite()) {
                val tMs = Math.floorDiv(ball.tUs[i], 1000L)
                val c = ball.conf[i].let { if (it.isNaN()) -1f else it }
                val last = pts.lastOrNull()
                if (last != null && last.tMs == tMs) {
                    if (c > conf.last()) { pts[pts.lastIndex] = Point(tMs, x, y); conf[conf.lastIndex] = c }
                } else {
                    pts += Point(tMs, x, y); conf += c
                }
            }
            i--
        }
        if (pts.size < 2) return null
        pts.reverse() // oldest first
        val newest = pts.last()
        if (freezeMs - newest.tMs > MAX_STALE_MS) return null
        val (vx, vy) = velocity(pts) ?: return null
        val tail = pts.subList(0, pts.size - 1).takeLast(MAX_TAIL)
        return Cue(newest.x, newest.y, vx, vy, tail)
    }

    /**
     * Smoothed velocity (px per second): the least-squares slope of x and y against time over
     * [pts] (two or more points). With two points it is the plain difference. Null when all
     * points share one time.
     */
    internal fun velocity(pts: List<Point>): Pair<Float, Float>? {
        val n = pts.size
        if (n < 2) return null
        val t0 = pts.last().tMs
        var st = 0.0; var sx = 0.0; var sy = 0.0
        for (p in pts) { st += (p.tMs - t0); sx += p.x; sy += p.y }
        val mt = st / n; val mx = sx / n; val my = sy / n
        var stt = 0.0; var stx = 0.0; var sty = 0.0
        for (p in pts) {
            val dt = (p.tMs - t0) - mt
            stt += dt * dt; stx += dt * (p.x - mx); sty += dt * (p.y - my)
        }
        if (stt <= 0.0) return null
        return (stx / stt * 1000.0).toFloat() to (sty / stt * 1000.0).toFloat()
    }
}

/**
 * The fit transform of a video shown with ContentScale.Fit (as Media3's ContentFrame does by
 * default) inside a view: one uniform scale, centred. Image pixels (the ball rows' space, source
 * pixels after rotation) map to view pixels as `view = offset + image * scale`.
 */
data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun mapX(imageX: Float) = offsetX + imageX * scale
    fun mapY(imageY: Float) = offsetY + imageY * scale

    companion object {
        /** Null for a non-positive image or view size. */
        fun of(imageW: Int, imageH: Int, viewW: Float, viewH: Float): FitTransform? {
            if (imageW <= 0 || imageH <= 0 || viewW <= 0f || viewH <= 0f) return null
            val s = min(viewW / imageW, viewH / imageH)
            return FitTransform(s, (viewW - imageW * s) / 2f, (viewH - imageH * s) / 2f)
        }
    }
}
