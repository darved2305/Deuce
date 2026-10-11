package com.elevate.app.domain.decide

import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.shotrewind.Decision
import com.elevate.app.domain.shotrewind.Placed
import com.elevate.app.domain.shotrewind.ShotRewind
import com.elevate.app.domain.shotrewind.Spacing
import kotlin.random.Random

/** How open the court was at the aim, from the defender's spacing (`06` §6). */
enum class Reach(val label: String, val openness: Int) {
    RIGHT_AT_THEM("Right at them", 0),
    STRETCH("Stretch", 1),
    OPEN_COURT("Open court", 2);

    companion object {
        fun of(s: Spacing?): Reach? = when (s) {
            Spacing.COVERED -> RIGHT_AT_THEM
            Spacing.ADJACENT -> STRETCH
            Spacing.SEPARATED -> OPEN_COURT
            null -> null
        }
    }
}

/** Where the real shot went, if the match record has a trusted landing for it. */
data class RealShot(val xM: Double, val yM: Double, val decision: Decision) {
    val reach: Reach? get() = (decision as? Decision.Legal)?.let { Reach.of(it.spacing) }
}

/**
 * One moment to decide: a real frame strictly before the hit's contact, the hitter's side of the
 * court, the defenders' real foot positions at that frame (possibly none) and the real landing.
 */
data class Freeze(
    val hitEventId: String,
    val contactMs: Long,
    val freezeUs: Long,
    val template: String,
    val side: CourtSide,
    /** Hitter's foot position at the freeze (court metres), null when not seen. */
    val hitterXyM: Pair<Double, Double>?,
    /** True when the hit is known to be the player's own; false means "a shot from this rally". */
    val yours: Boolean,
    /** Opponent positions on the target half at the freeze frame; empty means "position not seen". */
    val defenders: List<Placed>,
    val real: RealShot?,
) {
    val defenderSeen: Boolean get() = defenders.isNotEmpty()
    val caption: String get() = if (yours) "Your shot" else "A shot from this rally"
}

/** The result of one committed aim. */
data class Outcome(
    val freeze: Freeze,
    val aimXM: Double,
    val aimYM: Double,
    val decision: Decision,
) {
    val legal: Boolean get() = decision is Decision.Legal
    val reach: Reach? get() = (decision as? Decision.Legal)?.let { Reach.of(it.spacing) }
    val realReach: Reach? get() = freeze.real?.reach

    /** The aim is more open than the real shot by spacing; needs a verdict on both, never guessed. */
    val beatReal: Boolean get() {
        val mine = reach ?: return false
        val real = realReach ?: return false
        return mine.openness > real.openness
    }
}

data class DecideSummary(
    val decisions: Int,
    val legal: Int,
    val openCourt: Int,
    val beats: Int,
    /** Decisions where both the aim and the real shot had a spacing verdict. */
    val comparable: Int,
) {
    val legalPct: Int get() = if (decisions == 0) 0 else Math.round(legal * 100f / decisions)
}

/** Aim evaluation and session state. Pure: every action returns the next state. */
object Decide {
    const val SESSION_SIZE = 5
    const val MIN_FREEZES = 3

    /** Legality at the lines and defender spacing, both from [ShotRewind.decide]. */
    fun evaluate(freeze: Freeze, xM: Double, yM: Double): Outcome =
        Outcome(freeze, xM, yM, ShotRewind.decide(freeze.template, freeze.side, xM, yM, freeze.defenders))

    fun summarise(outcomes: List<Outcome>) = DecideSummary(
        decisions = outcomes.size,
        legal = outcomes.count { it.legal },
        openCourt = outcomes.count { it.reach == Reach.OPEN_COURT },
        beats = outcomes.count { it.beatReal },
        comparable = outcomes.count { it.reach != null && it.realReach != null },
    )

    /** Up to [n] freezes for a run, in match order; a different [seed] gives a different run. */
    fun pick(freezes: List<Freeze>, seed: Long, n: Int = SESSION_SIZE): List<Freeze> =
        freezes.shuffled(Random(seed)).take(n).sortedBy { it.contactMs }
}

data class DecideSession(
    val freezes: List<Freeze>,
    val index: Int = 0,
    val outcomes: List<Outcome> = emptyList(),
    val started: Boolean = false,
) {
    val current: Freeze? get() = freezes.getOrNull(index)
    val committed: Boolean get() = outcomes.size > index
    val finished: Boolean get() = index >= freezes.size
    val outcome: Outcome? get() = outcomes.getOrNull(index)
    val summary: DecideSummary get() = Decide.summarise(outcomes)

    fun begin() = if (started) this else copy(started = true)

    /** Locks the aim for the current freeze, once. */
    fun commit(xM: Double, yM: Double): DecideSession {
        val f = current ?: return this
        if (committed) return this
        return copy(outcomes = outcomes + Decide.evaluate(f, xM, yM))
    }

    /** To the next moment; only after the aim is locked. */
    fun next() = if (committed) copy(index = index + 1) else this
}

/** The zone a point falls in on the grid, or null outside the target half. Used for snap haptics. */
fun zoneKey(freeze: Freeze, xM: Double, yM: Double): String =
    when (val d = ShotRewind.decide(freeze.template, freeze.side, xM, yM, emptyList())) {
        is Decision.Legal -> d.zone.id
        Decision.Out -> "out"
        Decision.OwnHalf -> "own"
        Decision.NoCourt -> "none"
    }
