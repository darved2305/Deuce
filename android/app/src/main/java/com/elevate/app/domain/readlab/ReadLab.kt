package com.elevate.app.domain.readlab

import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.court.ZoneHit
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.training.FreezeFrames

/** Record states an answer may be scored against: seen by the engine, or set by a person. */
val TRUSTED_STATES = setOf("observed", "user")

enum class UnscoredReason(val text: String) {
    NO_LANDING("The landing wasn't observed"),
    UNTRUSTED_LANDING("The landing is only a candidate"),
    NO_COURT("This court has no zone grid"),
    UNKNOWN_HITTER_SIDE("The hitter's position wasn't observed"),
    LANDED_OUT("The shot landed out"),
    DID_NOT_CROSS("The shot didn't cross the net"),
}

/** What the real recording says about where the shot went, or why it cannot say. */
sealed interface LandingTruth {
    data class Landed(val hit: ZoneHit, val state: String) : LandingTruth
    data class Unavailable(val reason: UnscoredReason) : LandingTruth
}

sealed interface ReadResult {
    data object Exact : ReadResult
    data object Adjacent : ReadResult
    data object Wrong : ReadResult
    data class Unscored(val reason: UnscoredReason) : ReadResult

    /** The contract's `simulation-runs` result word (contracts §8). */
    val wire: String get() = when (this) {
        Exact -> "exact"
        Adjacent -> "adjacent"
        Wrong -> "wrong"
        is Unscored -> "unscored"
    }
}

/** A real shot from a match record, as ReadLab needs it (times on the source file's clock). */
data class ReadShot(
    val matchId: String,
    val shotId: String,
    val contactMs: Long,
    val contactSigmaMs: Long,
    /** Hitter's court y (metres) from the record's `roles.focal`, with its state. */
    val hitterYM: Double?,
    val hitterState: String?,
    val landingXyM: List<Double>?,
    val landingState: String?,
)

data class ReadChallenge(
    val matchId: String,
    val shotId: String,
    val contactMs: Long,
    /** Real frame times (µs) the player may see before locking, earliest first; never empty. */
    val ladderUs: List<Long>,
    val truth: LandingTruth,
)

sealed interface Eligibility {
    data class Ok(val challenge: ReadChallenge) : Eligibility
    /** No real frame precedes the contact safely: the shot cannot be frozen honestly. */
    data object NoSafeFrame : Eligibility
}

object ReadLab {
    /** Landing ground truth for [shot]; only a trusted landing on a validated grid is scorable. */
    fun truth(shot: ReadShot, template: String): LandingTruth {
        val grid = ZoneGrid.forTemplate(template) ?: return LandingTruth.Unavailable(UnscoredReason.NO_COURT)
        val xy = shot.landingXyM?.takeIf { it.size == 2 } ?: return LandingTruth.Unavailable(UnscoredReason.NO_LANDING)
        val state = shot.landingState ?: return LandingTruth.Unavailable(UnscoredReason.NO_LANDING)
        if (state !in TRUSTED_STATES) return LandingTruth.Unavailable(UnscoredReason.UNTRUSTED_LANDING)
        val side = shot.hitterYM?.takeIf { shot.hitterState in TRUSTED_STATES }?.let { CourtSide.of(it) }
            ?: return LandingTruth.Unavailable(UnscoredReason.UNKNOWN_HITTER_SIDE)
        return LandingTruth.Landed(grid.hit(xy[0], xy[1], side), state)
    }

    fun challenge(
        shot: ReadShot,
        template: String,
        frameTimesUs: LongArray,
        offsetsMs: List<Long> = FreezeFrames.DEFAULT_LADDER_MS,
    ): Eligibility {
        val ladder = FreezeFrames.ladder(frameTimesUs, shot.contactMs, shot.contactSigmaMs, offsetsMs)
        if (ladder.isEmpty()) return Eligibility.NoSafeFrame
        return Eligibility.Ok(ReadChallenge(shot.matchId, shot.shotId, shot.contactMs, ladder, truth(shot, template)))
    }

    fun evaluate(pick: ZoneId, truth: LandingTruth): ReadResult = when (truth) {
        is LandingTruth.Unavailable -> ReadResult.Unscored(truth.reason)
        is LandingTruth.Landed -> when (val h = truth.hit) {
            ZoneHit.Out -> ReadResult.Unscored(UnscoredReason.LANDED_OUT)
            ZoneHit.OwnHalf -> ReadResult.Unscored(UnscoredReason.DID_NOT_CROSS)
            is ZoneHit.In -> when (pick.cellsTo(h.zone)) {
                0 -> ReadResult.Exact
                1 -> ReadResult.Adjacent
                else -> ReadResult.Wrong
            }
        }
    }
}

/**
 * One attempt at a challenge. Immutable: every action returns the next state. Until [locked], the
 * player can never be shown footage past the current ladder frame ([playableUntilUs]).
 */
data class ReadAttempt(
    val challenge: ReadChallenge,
    val step: Int = 0,
    val pick: ZoneId? = null,
    val result: ReadResult? = null,
    /** Read Margin: ms of video before contact still unseen when the answer was locked. */
    val readMarginMs: Long? = null,
) {
    val locked: Boolean get() = result != null
    val frameUs: Long get() = challenge.ladderUs[step]
    val canRevealMore: Boolean get() = !locked && step < challenge.ladderUs.lastIndex

    /** Furthest presentation time the player may reach; unbounded only after locking. */
    val playableUntilUs: Long get() = if (locked) Long.MAX_VALUE else frameUs

    fun revealMore() = if (canRevealMore) copy(step = step + 1) else this

    fun choose(zone: ZoneId) = if (locked) this else copy(pick = zone)

    fun lock(): ReadAttempt {
        val p = pick ?: return this
        if (locked) return this
        return copy(result = ReadLab.evaluate(p, challenge.truth), readMarginMs = challenge.contactMs - frameUs / 1000)
    }
}

/** Session totals. Only a shot's first attempt counts: a repeat measures memory, not reading. */
data class ReadSummary(
    val completed: Int,
    val exact: Int,
    val adjacent: Int,
    val wrong: Int,
    val unscored: Int,
    val repeats: Int,
    /** Median Read Margin of scored first attempts, null when none were scored. */
    val medianScoredMarginMs: Long?,
) {
    val scored: Int get() = exact + adjacent + wrong

    companion object {
        fun of(attempts: List<ReadAttempt>): ReadSummary {
            val done = attempts.filter { it.locked }
            val seen = HashSet<Pair<String, String>>()
            val first = done.filter { seen.add(it.challenge.matchId to it.challenge.shotId) }
            fun count(p: (ReadResult) -> Boolean) = first.count { p(it.result!!) }
            val margins = first.filter { it.result !is ReadResult.Unscored }.mapNotNull { it.readMarginMs }.sorted()
            return ReadSummary(
                completed = first.size,
                exact = count { it == ReadResult.Exact },
                adjacent = count { it == ReadResult.Adjacent },
                wrong = count { it == ReadResult.Wrong },
                unscored = count { it is ReadResult.Unscored },
                repeats = done.size - first.size,
                medianScoredMarginMs = margins.takeIf { it.isNotEmpty() }?.let { it[(it.size - 1) / 2] },
            )
        }
    }
}
