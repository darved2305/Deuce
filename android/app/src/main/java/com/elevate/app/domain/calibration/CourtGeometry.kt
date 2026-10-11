package com.elevate.app.domain.calibration

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** A point in source image space (contracts §0): pixels of the upright frame, origin top-left. */
data class ImagePoint(val x: Double, val y: Double)

/**
 * Maps between a view that shows the upright frame with "fit" scaling (letterboxed, centred) and
 * source image space. [srcW]/[srcH] are the upright source size (rotation already applied).
 */
data class FitTransform(
    val viewW: Double,
    val viewH: Double,
    val srcW: Double,
    val srcH: Double,
    /** View px kept free on every side of the fitted picture, so a handle can sit just outside it. */
    val padding: Double = 0.0,
) {
    val scale = min((viewW - 2 * padding) / srcW, (viewH - 2 * padding) / srcH)
    val offsetX = (viewW - srcW * scale) / 2
    val offsetY = (viewH - srcH * scale) / 2

    /** Null when the tap falls in the letterbox bars, outside the picture. */
    fun toSource(vx: Double, vy: Double): ImagePoint? {
        val x = (vx - offsetX) / scale
        val y = (vy - offsetY) / scale
        return if (x in 0.0..srcW && y in 0.0..srcH) ImagePoint(x, y) else null
    }

    /** Like [toSource] but pinned to the frame edge: for dragging a handle past the picture. */
    fun toSourceClamped(vx: Double, vy: Double) =
        ImagePoint(((vx - offsetX) / scale).coerceIn(0.0, srcW), ((vy - offsetY) / scale).coerceIn(0.0, srcH))

    /**
     * View to source without pinning to the frame, for a corner that is really outside the picture
     * (a near baseline below the frame). Limited to [margin] times the frame size beyond each edge
     * so a wild drag cannot fly away.
     */
    fun toSourceExtended(vx: Double, vy: Double, margin: Double = CourtGeometry.EXTENDED_MARGIN) =
        CourtGeometry.clampExtended(ImagePoint((vx - offsetX) / scale, (vy - offsetY) / scale), srcW, srcH, margin)

    fun toView(p: ImagePoint) = Pair(offsetX + p.x * scale, offsetY + p.y * scale)
}

/** The four named court corners, in the contract's order (contracts §2 `points_px`). */
enum class Corner(val key: String, val label: String) {
    NearLeft("near_left", "Near left"),
    NearRight("near_right", "Near right"),
    FarRight("far_right", "Far right"),
    FarLeft("far_left", "Far left"),
}

sealed interface CourtCheck {
    data object Ok : CourtCheck
    data class Problem(val code: String, val text: String) : CourtCheck
}

object CourtGeometry {
    /** Shorter court sides than this (source px) are taps on top of each other, not a court. */
    const val MIN_SIDE_PX = 24.0
    /** The court must cover at least this fraction of the frame to be a usable tap calibration. */
    const val MIN_AREA_FRACTION = 0.01
    /** How far past each frame edge (as a fraction of the frame size) a court corner may be placed. */
    const val EXTENDED_MARGIN = 0.5

    /** Pins [p] to the frame grown by [margin] of its size on every side. */
    fun clampExtended(p: ImagePoint, srcW: Double, srcH: Double, margin: Double = EXTENDED_MARGIN) = ImagePoint(
        p.x.coerceIn(-margin * srcW, (1 + margin) * srcW),
        p.y.coerceIn(-margin * srcH, (1 + margin) * srcH),
    )

    /** True when [p] lies outside the picture: contracts §2 allows such `points_px`. */
    fun isOffFrame(p: ImagePoint, srcW: Double, srcH: Double) = p.x < 0 || p.x > srcW || p.y < 0 || p.y > srcH

    /**
     * Checks a tapped quad in contract order before it is sent. Geometry only: it says nothing
     * about whether the lines are really the court's (the engine fits that, `residual_px`).
     * Corners outside the frame are fine (the near ones often are); the area is the whole quad's.
     */
    fun check(p: Map<Corner, ImagePoint>, srcW: Double, srcH: Double): CourtCheck {
        if (p.size != 4) return CourtCheck.Problem("incomplete", "Place all four corners")
        val q = Corner.entries.map { p.getValue(it) }
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            if (hypot(a.x - b.x, a.y - b.y) < MIN_SIDE_PX) return CourtCheck.Problem("too_close", "Two corners are on top of each other")
        }
        // Signs of the turn at each corner: all equal = convex and not self-crossing.
        val turns = (0 until 4).map { i -> cross(q[i], q[(i + 1) % 4], q[(i + 2) % 4]) }
        if (!(turns.all { it > 0 } || turns.all { it < 0 })) {
            return CourtCheck.Problem("crossed", "The corners cross over: check left and right")
        }
        if (polygonArea(q) < MIN_AREA_FRACTION * srcW * srcH) {
            return CourtCheck.Problem("too_small", "The court is too small in the frame")
        }
        val (nl, nr, fr, fl) = q
        if (nl.x >= nr.x || fl.x >= fr.x) return CourtCheck.Problem("left_right", "Left and right look swapped")
        // A camera behind one baseline sees the near baseline lower in the picture than the far one.
        if ((nl.y + nr.y) / 2 <= (fl.y + fr.y) / 2) return CourtCheck.Problem("near_far", "Near and far look swapped")
        return CourtCheck.Ok
    }

    private fun cross(a: ImagePoint, b: ImagePoint, c: ImagePoint) =
        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)

    private fun polygonArea(q: List<ImagePoint>) =
        abs(q.indices.sumOf { i -> q[i].x * q[(i + 1) % q.size].y - q[(i + 1) % q.size].x * q[i].y }) / 2
}
