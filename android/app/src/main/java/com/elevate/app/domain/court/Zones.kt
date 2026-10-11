package com.elevate.app.domain.court

import com.elevate.app.domain.calibration.CourtModel
import kotlin.math.abs
import kotlin.math.max

/** Which end of the court a player stands at, in the court frame (near = y below the net). */
enum class CourtSide { NEAR, FAR;
    val other get() = if (this == NEAR) FAR else NEAR

    companion object {
        /** The side a court-plane point is on; null exactly on the net line. */
        fun of(yM: Double): CourtSide? = when {
            yM < CourtModel.NET_Y_M -> NEAR
            yM > CourtModel.NET_Y_M -> FAR
            else -> null
        }
    }
}

enum class ZoneRow(val letter: Char) { FRONT('F'), MIDDLE('M'), BACK('B') }

/**
 * A target cell `F1…B3` (contracts §9): badminton half 3 × 3, columns left / centre / right from
 * the hitter's view, rows front (net to short service line) / middle / back (last 1.5 m).
 */
data class ZoneId(val row: ZoneRow, val col: Int) {
    init { require(col in 1..3) { "column $col" } }

    val id: String get() = "${row.letter}$col"

    /** Cells apart (Chebyshev): 0 same, 1 touching, 2 far. */
    fun cellsTo(o: ZoneId) = max(abs(row.ordinal - o.row.ordinal), abs(col - o.col))

    override fun toString() = id

    companion object {
        val ALL: List<ZoneId> = ZoneRow.entries.flatMap { r -> (1..3).map { ZoneId(r, it) } }

        fun parse(s: String): ZoneId? {
            if (s.length != 2) return null
            val row = ZoneRow.entries.firstOrNull { it.letter == s[0] } ?: return null
            val col = s[1].digitToIntOrNull()?.takeIf { it in 1..3 } ?: return null
            return ZoneId(row, col)
        }
    }
}

/** Where a court-plane point falls for a shot by a hitter on [hitter]'s side. */
sealed interface ZoneHit {
    data class In(val zone: ZoneId) : ZoneHit
    /** Wide of the sidelines or past the back boundary of the target half. */
    data object Out : ZoneHit
    /** On the hitter's own half (did not cross the net). */
    data object OwnHalf : ZoneHit
}

/** Zone grid for badminton templates; no other sport has a validated grid in this build. */
class ZoneGrid private constructor(val template: String, val widthM: Double) {

    /** Zone of a landing/target point in metres, lines count as in (BWF Laws). */
    fun hit(xM: Double, yM: Double, hitter: CourtSide): ZoneHit {
        val side = CourtSide.of(yM)
        if (side == null || side == hitter) return ZoneHit.OwnHalf
        if (xM < 0.0 || xM > widthM || yM < 0.0 || yM > CourtModel.LENGTH_M) return ZoneHit.Out
        return ZoneHit.In(cell(xM, yM, hitter))
    }

    /**
     * The cell a player standing at ([xM], [yM]) on the target half occupies, clamped into the grid
     * (a defender behind the baseline still covers the back row). Null on the other half.
     */
    fun cellOfDefender(xM: Double, yM: Double, hitter: CourtSide): ZoneId? {
        if (CourtSide.of(yM) != hitter.other) return null
        val x = xM.coerceIn(0.0, widthM)
        val y = yM.coerceIn(0.0, CourtModel.LENGTH_M)
        return cell(x, y, hitter)
    }

    /** Centre of [zone] in metres for a hitter on [hitter]'s side (where a target marker is drawn). */
    fun centre(zone: ZoneId, hitter: CourtSide): Pair<Double, Double> {
        val third = widthM / 3
        val leftToRight = (zone.col - 1) * third + third / 2
        val x = if (hitter == CourtSide.NEAR) leftToRight else widthM - leftToRight
        val (from, to) = rowSpan(zone.row)
        val d = (from + to) / 2
        val y = if (hitter == CourtSide.NEAR) CourtModel.NET_Y_M + d else CourtModel.NET_Y_M - d
        return Pair(x, y)
    }

    private fun cell(x: Double, y: Double, hitter: CourtSide): ZoneId {
        val d = abs(y - CourtModel.NET_Y_M)
        val row = when {
            d <= CourtModel.SHORT_SERVICE_M -> ZoneRow.FRONT
            d >= HALF_M - BACK_DEPTH_M -> ZoneRow.BACK
            else -> ZoneRow.MIDDLE
        }
        // Left from the hitter's view: a near hitter faces +y, so their left is small x.
        val fromLeft = if (hitter == CourtSide.NEAR) x else widthM - x
        val col = (fromLeft / (widthM / 3)).toInt().coerceIn(0, 2) + 1
        return ZoneId(row, col)
    }

    private fun rowSpan(row: ZoneRow) = when (row) {
        ZoneRow.FRONT -> Pair(0.0, CourtModel.SHORT_SERVICE_M)
        ZoneRow.MIDDLE -> Pair(CourtModel.SHORT_SERVICE_M, HALF_M - BACK_DEPTH_M)
        ZoneRow.BACK -> Pair(HALF_M - BACK_DEPTH_M, HALF_M)
    }

    companion object {
        /** Contracts §9: badminton back row is the last 1.5 m of the half. */
        const val BACK_DEPTH_M = 1.5
        private const val HALF_M = CourtModel.LENGTH_M - CourtModel.NET_Y_M

        /** The grid for a calibration template, or null where no grid is validated. */
        fun forTemplate(template: String): ZoneGrid? = when (template) {
            "badminton_singles" -> ZoneGrid(template, CourtModel.WIDTH_SINGLES_M)
            "badminton_doubles" -> ZoneGrid(template, CourtModel.WIDTH_DOUBLES_M)
            else -> null
        }
    }
}
