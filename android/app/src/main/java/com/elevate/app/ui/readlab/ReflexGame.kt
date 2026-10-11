package com.elevate.app.ui.readlab

import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.readlab.ReadAttempt
import com.elevate.app.domain.readlab.ReadResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Fixed timings and scoring of Reflex mode. Pure, deterministic, no clock inside. */
object ReflexRules {
    /** The clip starts playing this long before the freeze point (clamped at the start of the file). */
    const val RUN_UP_MS = 2_000L
    const val TIMER_MS = 1_500L
    const val RELAXED_TIMER_MS = 3_000L
    /** How long the video plays on through the real shot before the next round. */
    const val REVEAL_MS = 1_200L

    /** A reaction at or under this earns the full speed bonus. */
    const val FAST_MS = 400L
    /** A reaction at or over this earns no speed bonus. */
    const val SLOW_MS = 1_500L
    const val MAX_BONUS_PCT = 150
    /** Each consecutive exact or adjacent read after the first adds this, up to [MAX_STREAK_BONUS_STEPS] steps. */
    const val STREAK_STEP_PCT = 10
    const val MAX_STREAK_BONUS_STEPS = 5

    fun timerMs(relaxed: Boolean) = if (relaxed) RELAXED_TIMER_MS else TIMER_MS

    /** Where the clip starts (µs): [RUN_UP_MS] before the freeze frame, never before 0. */
    fun startUs(freezeUs: Long): Long = maxOf(0L, freezeUs - RUN_UP_MS * 1000)

    fun accuracyPoints(r: ReflexVerdict): Int = when (r) {
        ReflexVerdict.EXACT -> 100
        ReflexVerdict.ADJACENT -> 50
        ReflexVerdict.WRONG, ReflexVerdict.TOO_SLOW -> 0
    }

    /** 150 at or under [FAST_MS], 100 at or over [SLOW_MS], linear in between. Absolute, so Relaxed does not change it. */
    fun speedBonusPct(reactionMs: Long): Int = when {
        reactionMs <= FAST_MS -> MAX_BONUS_PCT
        reactionMs >= SLOW_MS -> 100
        else -> MAX_BONUS_PCT - ((MAX_BONUS_PCT - 100) * (reactionMs - FAST_MS) / (SLOW_MS - FAST_MS)).toInt()
    }

    /** [streak] is the run of exact or adjacent reads including this one. 100 for the first, up to 150. */
    fun streakPct(streak: Int): Int = 100 + STREAK_STEP_PCT * (streak - 1).coerceIn(0, MAX_STREAK_BONUS_STEPS)

    /** accuracy x speed bonus x streak multiplier, whole points (rounded down). Zero for a wrong or too slow round. */
    fun points(verdict: ReflexVerdict, reactionMs: Long?, streak: Int): Int {
        val acc = accuracyPoints(verdict)
        if (acc == 0 || reactionMs == null) return 0
        return acc * speedBonusPct(reactionMs) * streakPct(streak) / 10_000
    }
}

enum class ReflexVerdict(val label: String) {
    EXACT("Exact"), ADJACENT("Adjacent"), WRONG("Wrong"), TOO_SLOW("Too slow");

    val keepsStreak: Boolean get() = this == EXACT || this == ADJACENT
}

/** One round: a real drill, and whether its answer is graded against an engine estimate. */
data class ReflexRound(val drill: Drill, val vsEstimate: Boolean)

/** Which drills can be played as rounds, and in which order. */
object ReflexRounds {
    /** The grade of any pick on [d], by ReadLab's own grading; null when no answer could ever be scored. */
    private fun probe(d: Drill): Graded? =
        ReadLabGrading.grade(d, ReadAttempt(d.challenge).choose(ZoneId.ALL.first()).lock())?.takeIf { it.result !is ReadResult.Unscored }

    /** Only drills with a scorable landing (a confirmed one, or an engine estimate), in match order. */
    fun of(drills: List<Drill>): List<ReflexRound> =
        drills.sortedBy { it.challenge.contactMs }.mapNotNull { d -> probe(d)?.let { ReflexRound(d, it.vsEstimate) } }

    const val MIN_ROUNDS = 3
}

/** The result of one played round. */
data class ReflexRoundResult(
    val hitEventId: String,
    val pick: ZoneId?,
    val reactionMs: Long?,
    val verdict: ReflexVerdict,
    val vsEstimate: Boolean,
    val points: Int,
    /** The run of exact or adjacent reads up to and including this round. */
    val streak: Int,
)

/** End-of-run totals. */
data class ReflexSummary(
    val score: Int,
    val rounds: Int,
    val exact: Int,
    val adjacent: Int,
    val wrong: Int,
    val tooSlow: Int,
    /** Median reaction of the rounds that were answered in time; null when none. */
    val medianReactionMs: Long?,
    val bestStreak: Int,
    /** Rounds whose landing was an engine estimate, not a confirmed one. */
    val estimateRounds: Int,
) {
    val accuracyPct: Int get() = if (rounds == 0) 0 else (exact + adjacent) * 100 / rounds
}

/** One run through the rounds. Immutable: each action returns the next state. */
data class ReflexSession(
    val rounds: List<ReflexRound>,
    val relaxed: Boolean = false,
    val started: Boolean = false,
    val results: List<ReflexRoundResult> = emptyList(),
) {
    val index: Int get() = results.size
    val round: ReflexRound? get() = rounds.getOrNull(index)
    val finished: Boolean get() = index >= rounds.size
    val timerMs: Long get() = ReflexRules.timerMs(relaxed)
    val score: Int get() = results.sumOf { it.points }
    val streak: Int get() = results.lastOrNull()?.streak ?: 0

    fun begin(): ReflexSession = if (started || rounds.isEmpty()) this else copy(started = true)

    /** The timer only changes before the first round, so every round of a run has the same time. */
    fun withRelaxed(on: Boolean): ReflexSession = if (started) this else copy(relaxed = on)

    /**
     * Locks the current round. A null [pick], or a [reactionMs] past the timer, is "too slow": no points,
     * streak reset. Otherwise the pick is graded by ReadLab's own grading.
     */
    fun answer(pick: ZoneId?, reactionMs: Long?): ReflexSession {
        val r = round ?: return this
        val inTime = pick != null && reactionMs != null && reactionMs in 0..timerMs
        val verdict = if (!inTime) ReflexVerdict.TOO_SLOW else {
            when (ReadLabGrading.grade(r.drill, ReadAttempt(r.drill.challenge).choose(pick).lock())?.result) {
                ReadResult.Exact -> ReflexVerdict.EXACT
                ReadResult.Adjacent -> ReflexVerdict.ADJACENT
                else -> ReflexVerdict.WRONG
            }
        }
        val streak = if (verdict.keepsStreak) this.streak + 1 else 0
        val rt = if (inTime) reactionMs else null
        val res = ReflexRoundResult(
            r.drill.hitEventId, pick.takeIf { inTime }, rt, verdict, r.vsEstimate,
            ReflexRules.points(verdict, rt, streak), streak,
        )
        return copy(results = results + res)
    }

    val summary: ReflexSummary
        get() {
            val rts = results.mapNotNull { it.reactionMs }.sorted()
            return ReflexSummary(
                score = score,
                rounds = results.size,
                exact = results.count { it.verdict == ReflexVerdict.EXACT },
                adjacent = results.count { it.verdict == ReflexVerdict.ADJACENT },
                wrong = results.count { it.verdict == ReflexVerdict.WRONG },
                tooSlow = results.count { it.verdict == ReflexVerdict.TOO_SLOW },
                medianReactionMs = rts.takeIf { it.isNotEmpty() }?.let { it[(it.size - 1) / 2] },
                bestStreak = results.maxOfOrNull { it.streak } ?: 0,
                estimateRounds = results.count { it.vsEstimate },
            )
        }
}

/** Per-device best score for one match. */
interface ReflexBestStore {
    fun best(matchKey: String): Int?
    fun save(matchKey: String, score: Int)
}

/** What a finished run did to the stored best: [before] is the best it replaced (null at the first run). */
data class ReflexSettlement(val before: Int?, val score: Int) {
    val newBest: Boolean get() = score > 0 && score > (before ?: 0)
    val best: Int get() = maxOf(before ?: 0, score)
}

/** Holds the run across rotation; the round list identifies it. */
class ReflexState {
    private val _session = MutableStateFlow<ReflexSession?>(null)
    val session: StateFlow<ReflexSession?> = _session.asStateFlow()
    private val _settlement = MutableStateFlow<ReflexSettlement?>(null)
    val settlement: StateFlow<ReflexSettlement?> = _settlement.asStateFlow()

    /** Starts over unless this exact list of rounds is already running. */
    fun start(rounds: List<ReflexRound>) {
        _session.update { cur ->
            if (cur != null && cur.rounds.map { it.drill.hitEventId } == rounds.map { it.drill.hitEventId }) cur
            else { _settlement.value = null; ReflexSession(rounds) }
        }
    }

    fun restart() {
        _settlement.value = null
        _session.update { cur -> cur?.let { ReflexSession(it.rounds, relaxed = it.relaxed) } }
    }

    fun update(f: (ReflexSession) -> ReflexSession) = _session.update { it?.let(f) }

    /** Once per finished run: reads the old best, stores the new one. Later calls change nothing. */
    fun settle(store: ReflexBestStore, matchKey: String) {
        val s = _session.value ?: return
        if (!s.finished || _settlement.value != null) return
        val before = store.best(matchKey)
        val st = ReflexSettlement(before, s.score)
        if (st.newBest) store.save(matchKey, st.score)
        _settlement.value = st
    }
}

/** What the screens say. Plain words, no em dashes. */
object ReflexText {
    const val BEST_LABEL = "Your best on this match"
    const val RELAXED_LABEL = "Relaxed (3 s)"

    fun entry(n: Int) = "Reflex"

    fun verdictLine(r: ReflexRoundResult): String {
        val base = when (r.verdict) {
            ReflexVerdict.TOO_SLOW -> "Too slow"
            else -> r.verdict.label
        }
        val suffix = if (r.vsEstimate) " (vs engine estimate)" else ""
        val rt = r.reactionMs?.let { " in $it ms" } ?: ""
        return base + rt + suffix
    }

    fun estimateNote(s: ReflexSummary): String? =
        if (s.estimateRounds > 0) "${s.estimateRounds} of ${s.rounds} rounds scored against engine estimates" else null
}
