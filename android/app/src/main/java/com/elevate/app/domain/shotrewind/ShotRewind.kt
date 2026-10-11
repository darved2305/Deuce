package com.elevate.app.domain.shotrewind

import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.court.ZoneHit
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.readlab.TRUSTED_STATES
import com.elevate.app.domain.training.FreezeFrames

data class RewindRally(val rallyId: String, val winner: String, val winnerState: String)

data class RewindShot(
    val shotId: String,
    val rallyId: String,
    val index: Int,
    val playerId: String,
    val contactMs: Long,
    val contactSigmaMs: Long,
)

/** A lost rally's last shot by the player, offered for review; never claimed as the cause. */
data class RewindMoment(val rallyId: String, val shot: RewindShot)

/** A court position from the record's `roles`, with its state. */
data class Placed(val playerId: String, val xM: Double, val yM: Double, val state: String)

enum class Spacing(val wire: String) { COVERED("covered"), ADJACENT("adjacent"), SEPARATED("separated") }

sealed interface Decision {
    /** Inside the opponent's court; [spacing] is null when no defender position is trusted. */
    data class Legal(val zone: ZoneId, val spacing: Spacing?, val nearest: Placed?) : Decision
    data object Out : Decision
    data object OwnHalf : Decision
    data object NoCourt : Decision
}

object ShotRewind {
    /**
     * Rallies [playerId] lost by a confirmed or observed winner, each with the player's last shot
     * in it. A rally whose winner is only a candidate is never called a loss.
     */
    fun moments(playerId: String, rallies: List<RewindRally>, shots: List<RewindShot>): List<RewindMoment> {
        val byRally = shots.groupBy { it.rallyId }
        return rallies.filter { it.winnerState in TRUSTED_STATES && it.winner != playerId }.mapNotNull { r ->
            byRally[r.rallyId]?.filter { it.playerId == playerId }?.maxByOrNull { it.index }?.let { RewindMoment(r.rallyId, it) }
        }
    }

    /** Real frame to rewind to: [leadMs] before contact, and strictly before its uncertainty. */
    fun rewindFrameUs(shot: RewindShot, frameTimesUs: LongArray, leadMs: Long = 300): Long? =
        FreezeFrames.frameAtOrBefore(
            frameTimesUs,
            (shot.contactMs - leadMs) * 1000,
            FreezeFrames.safeLimitUs(shot.contactMs, shot.contactSigmaMs),
        )

    /** Legality and defender spacing of a target tapped at ([xM], [yM]) by a hitter on [hitter]. */
    fun decide(template: String, hitter: CourtSide, xM: Double, yM: Double, defenders: List<Placed>): Decision {
        val grid = ZoneGrid.forTemplate(template) ?: return Decision.NoCourt
        return when (val h = grid.hit(xM, yM, hitter)) {
            ZoneHit.Out -> Decision.Out
            ZoneHit.OwnHalf -> Decision.OwnHalf
            is ZoneHit.In -> spacing(grid, hitter, h.zone, defenders)
        }
    }

    /** Spacing for a zone chosen on the 3 × 3 picker; a picker zone is always inside the court. */
    fun decideZone(template: String, hitter: CourtSide, zone: ZoneId, defenders: List<Placed>): Decision {
        val grid = ZoneGrid.forTemplate(template) ?: return Decision.NoCourt
        return spacing(grid, hitter, zone, defenders)
    }

    // `06` §6: covered = same cell, adjacent = one cell, separated = two or more; nearest defender.
    private fun spacing(grid: ZoneGrid, hitter: CourtSide, zone: ZoneId, defenders: List<Placed>): Decision.Legal {
        val best = defenders.filter { it.state in TRUSTED_STATES }
            .mapNotNull { d -> grid.cellOfDefender(d.xM, d.yM, hitter)?.let { d to zone.cellsTo(it) } }
            .minByOrNull { it.second }
        val spacing = best?.second?.let {
            when (it) {
                0 -> Spacing.COVERED
                1 -> Spacing.ADJACENT
                else -> Spacing.SEPARATED
            }
        }
        return Decision.Legal(zone, spacing, best?.first)
    }
}
