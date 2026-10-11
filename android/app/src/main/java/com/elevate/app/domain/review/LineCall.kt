package com.elevate.app.domain.review

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What Instant Review concluded about one landing. */
enum class Verdict { Inside, Outside, TooClose, NotSeen }

/** Why a landing got no call. */
enum class NotSeenReason(val text: String) {
    NoPosition("no court position was measured"),
    NotCourtPlane("the position is not on the court plane"),
    NoCalibration("the match has no usable court calibration"),
    TierC("the court fit is grade C, too loose to measure a line"),
}

/** The four boundary lines a rally can end on. A shuttle on the line is in: the boundary is the line's outer edge. */
enum class BoundaryLine(val plainName: String, val key: String) {
    SideLeft("side line", "side_left"),
    SideRight("side line", "side_right"),
    BackNear("back line", "back_near"),
    BackFar("back line", "back_far"),
}

/**
 * The boundary rectangle in the calibration template's court metres: x across, y along (contracts §0).
 * Dimensions are the BWF Laws': they are measured to the outer edge of the 40 mm lines, and every line
 * belongs to the court it bounds. So a boundary here is the OUTER edge of the painted line, and the paint
 * itself sits [LINE_WIDTH_M] inside it.
 */
data class BoundaryBox(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
    /** Inward unit normal of [line], as (nx, ny). */
    fun normal(line: BoundaryLine): Pair<Double, Double> = when (line) {
        BoundaryLine.SideLeft -> 1.0 to 0.0
        BoundaryLine.SideRight -> -1.0 to 0.0
        BoundaryLine.BackNear -> 0.0 to 1.0
        BoundaryLine.BackFar -> 0.0 to -1.0
    }

    /** Signed distance in metres from the boundary: positive inside the court, negative outside. */
    fun signedDistance(line: BoundaryLine, x: Double, y: Double): Double = when (line) {
        BoundaryLine.SideLeft -> x - minX
        BoundaryLine.SideRight -> maxX - x
        BoundaryLine.BackNear -> y - minY
        BoundaryLine.BackFar -> maxY - y
    }

    companion object {
        const val LINE_WIDTH_M = 0.04

        /**
         * The singles court (side lines 0.46 m inside the doubles ones) or the doubles court, in
         * [template]'s frame, or null for a template this build has no badminton court for.
         */
        fun forTemplate(template: String, singles: Boolean): BoundaryBox? {
            val (l, r) = com.elevate.app.domain.calibration.CourtModel.courtBounds(template) ?: return null
            val inset = if (singles) com.elevate.app.domain.calibration.CourtModel.SINGLES_INSET_M else 0.0
            return BoundaryBox(l + inset, r - inset, 0.0, com.elevate.app.domain.calibration.CourtModel.LENGTH_M)
        }
    }
}

/** Everything Instant Review needs about one landing; all of it comes from the match record. */
data class LandingInput(
    /** `contact_xy_m`: court metres in the calibration template's frame; null when not measured. */
    val xyM: List<Double>?,
    /** `cov_m2` as [[xx, xy], [yx, yy]] in m²; null when absent. */
    val covM2: List<List<Double>>?,
    /** The event's `plane`; null is read as "court" (the only plane a landing is measured on). */
    val plane: String?,
    /** The calibration's `plane`; null when the match is not calibrated. */
    val calibrationPlane: String?,
    val tier: String?,
    val template: String?,
    val singles: Boolean,
)

/**
 * The call on one landing. [distanceM] is signed (+ inside the court, - outside) to [line]'s outer edge;
 * [sigmaM] is the 1-sigma spread along that line's normal; [marginM] = 1.96 sigma, the 95% band the call
 * respects. A null [line] means NotSeen.
 */
data class LineCall(
    val verdict: Verdict,
    val line: BoundaryLine?,
    val distanceM: Double,
    val sigmaM: Double,
    val marginM: Double,
    /** True when `cov_m2` was missing or unusable, so [sigmaM] is the calibration grade's stated error. */
    val usedFallbackSigma: Boolean,
    val tier: String?,
    val xyM: Pair<Double, Double>?,
    /** The 2x2 covariance used for the picture (the real one, or an isotropic one built from [sigmaM]). */
    val covUsed: Cov2?,
    val box: BoundaryBox?,
    val notSeen: NotSeenReason?,
    val singles: Boolean,
) {
    companion object {
        const val Z = 1.96
        /** Cautious stand-in sigma when no per-landing covariance exists: the calibration grade's stated error. */
        const val TIER_A_SIGMA_M = 0.15
        const val TIER_B_SIGMA_M = 0.40

        /** Stated 1-sigma error of a calibration grade; null for C (no usable measurement) or an unknown grade. */
        fun tierSigmaM(tier: String?): Double? = when (tier) {
            "A" -> TIER_A_SIGMA_M
            "B" -> TIER_B_SIGMA_M
            else -> null
        }

        /** sqrt(n^T S n) for a unit direction [n]; null when [cov] is not a finite 2x2 with a positive result. */
        fun sigmaAlong(cov: List<List<Double>>?, nx: Double, ny: Double): Double? {
            val c = Cov2.of(cov) ?: return null
            val v = c.varianceAlong(nx, ny)
            return if (v > 0.0 && v.isFinite()) sqrt(v) else null
        }

        fun of(input: LandingInput): LineCall {
            val xy = input.xyM
            val tier = input.tier
            val box = input.template?.let { BoundaryBox.forTemplate(it, input.singles) }
            fun notSeen(reason: NotSeenReason) = LineCall(
                Verdict.NotSeen, null, 0.0, 0.0, 0.0, false, tier, xy?.takeIf { it.size == 2 }?.let { it[0] to it[1] }, null, box, reason, input.singles,
            )
            if (xy == null || xy.size != 2 || !xy.all(Double::isFinite)) return notSeen(NotSeenReason.NoPosition)
            if (input.plane != null && input.plane != "court") return notSeen(NotSeenReason.NotCourtPlane)
            if (input.calibrationPlane != "court" || box == null) return notSeen(NotSeenReason.NoCalibration)
            val tierSigma = tierSigmaM(tier) ?: return notSeen(NotSeenReason.TierC)
            val x = xy[0]
            val y = xy[1]
            val cov = Cov2.of(input.covM2)?.takeIf { it.isUsable }
            val fallbackCov = Cov2(tierSigma * tierSigma, 0.0, tierSigma * tierSigma)

            // Judge every boundary: z = distance / sigma. The decisive line is the one with the smallest z
            // (the most outside, else the closest), so a corner landing is judged on its worst line.
            data class Scored(val line: BoundaryLine, val d: Double, val sigma: Double, val z: Double)
            val scored = BoundaryLine.entries.map { line ->
                val (nx, ny) = box.normal(line)
                val d = box.signedDistance(line, x, y)
                val sigma = if (cov != null) sqrt(cov.varianceAlong(nx, ny)) else tierSigma
                Scored(line, d, sigma, d / sigma)
            }
            val worst = scored.minBy { it.z }
            val verdict = when {
                worst.d > Z * worst.sigma -> Verdict.Inside
                worst.d < -Z * worst.sigma -> Verdict.Outside
                else -> Verdict.TooClose
            }
            return LineCall(
                verdict = verdict,
                line = worst.line,
                distanceM = worst.d,
                sigmaM = worst.sigma,
                marginM = Z * worst.sigma,
                usedFallbackSigma = cov == null,
                tier = tier,
                xyM = x to y,
                covUsed = cov ?: fallbackCov,
                box = box,
                notSeen = null,
                singles = input.singles,
            )
        }
    }

    /** Whole centimetres between the landing and the line, never negative. */
    val gapCm: Int get() = (abs(distanceM) * 100).roundToInt()

    /** The margin in whole centimetres, rounded UP so it is never understated; at least 1. */
    val marginCm: Int get() = maxOf(1, ceil(marginM * 100 - 1e-9).toInt())
}

/** A 2x2 covariance in m²: [[xx, xy], [xy, yy]]. */
data class Cov2(val xx: Double, val xy: Double, val yy: Double) {
    /** n^T S n for the direction (nx, ny). */
    fun varianceAlong(nx: Double, ny: Double): Double = nx * nx * xx + 2 * nx * ny * xy + ny * ny * yy

    /** Positive variances and a proper covariance (|xy| <= sqrt(xx*yy)), all finite. */
    val isUsable: Boolean
        get() = listOf(xx, xy, yy).all(Double::isFinite) && xx > 0.0 && yy > 0.0 && xy * xy <= xx * yy * (1 + 1e-9)

    companion object {
        /** From `[[xx, xy], [yx, yy]]`; null unless it is a finite 2x2 (the off-diagonals are averaged). */
        fun of(m: List<List<Double>>?): Cov2? {
            if (m == null || m.size != 2 || m.any { it.size != 2 }) return null
            val all = m.flatten()
            if (!all.all(Double::isFinite)) return null
            return Cov2(m[0][0], (m[0][1] + m[1][0]) / 2, m[1][1]).takeIf { it.isUsable }
        }
    }
}
