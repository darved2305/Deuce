package com.elevate.app.ui.readlab

import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.court.ZoneHit
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.elevate.app.domain.readlab.LandingTruth
import com.elevate.app.domain.readlab.ReadAttempt
import com.elevate.app.domain.readlab.ReadResult
import com.elevate.app.domain.readlab.ReadSummary
import com.elevate.app.domain.readlab.UnscoredReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One run through the drills. Immutable: each action returns the next state; [done] holds locked attempts. */
data class ReadLabSession(
    val drills: List<Drill>,
    val index: Int = 0,
    val attempt: ReadAttempt? = drills.firstOrNull()?.let { ReadAttempt(it.challenge) },
    val done: List<ReadAttempt> = emptyList(),
    /** One grade per entry of [done], same order. */
    val grades: List<Graded> = emptyList(),
) {
    val drill: Drill? get() = drills.getOrNull(index)
    val finished: Boolean get() = index >= drills.size
    val isLast: Boolean get() = index == drills.lastIndex

    /** The grade of the current, locked attempt. */
    val grade: Graded? get() = drill?.let { d -> attempt?.let { ReadLabGrading.grade(d, it) } }

    /** Tapping a zone locks the answer, once: later taps on the same drill change nothing. */
    fun pick(zone: ZoneId): ReadLabSession {
        val a = attempt ?: return this
        if (a.locked) return this
        return copy(attempt = a.choose(zone).lock())
    }

    /** A later real frame (still before contact), only while unanswered. */
    fun anotherLook(): ReadLabSession {
        val a = attempt ?: return this
        return copy(attempt = a.revealMore())
    }

    /** To the next drill; only after the answer is locked. */
    fun next(): ReadLabSession {
        val a = attempt?.takeIf { it.locked } ?: return this
        val i = index + 1
        val g = ReadLabGrading.grade(drills[index], a) ?: return this
        return copy(index = i, attempt = drills.getOrNull(i)?.let { ReadAttempt(it.challenge) }, done = done + a, grades = grades + g)
    }

    /** Counts split by what the answer was scored against. */
    val summary: ReadLabSummary get() = ReadLabSummary.of(done, grades)

    companion object {
        /** Seek target (ms, rounded up) for a real frame time: shows exactly that frame, never an earlier one. */
        fun seekMs(frameUs: Long): Long = (frameUs + 999) / 1000
    }
}

/** A locked answer's grade: [vsEstimate] means it was compared with an engine estimate, not a confirmed landing. */
data class Graded(val result: ReadResult, val vsEstimate: Boolean)

/**
 * Grades an answer. Confirmed landings (observed or user) keep the domain scorer's result untouched.
 * Where the domain refuses only because the landing is a candidate, or the hitter's side was not
 * observed, the picked zone is compared with the estimate's zone (picker oriented from the landing
 * side) and the grade is flagged [Graded.vsEstimate]. Everything else stays unscored.
 */
object ReadLabGrading {
    fun grade(d: Drill, a: ReadAttempt): Graded? {
        val r = a.result ?: return null
        if (r !is ReadResult.Unscored) return Graded(r, false)
        if (r.reason != UnscoredReason.UNTRUSTED_LANDING && r.reason != UnscoredReason.UNKNOWN_HITTER_SIDE) return Graded(r, false)
        val pick = a.pick ?: return Graded(r, false)
        val zone = ReadLabText.zoneOf(d) ?: return Graded(r, false)
        val est = when (pick.cellsTo(zone)) {
            0 -> ReadResult.Exact
            1 -> ReadResult.Adjacent
            else -> ReadResult.Wrong
        }
        return Graded(est, true)
    }
}

/** Session totals, kept apart: confirmed landings (domain summary) and engine estimates. */
data class ReadLabSummary(
    val confirmed: ReadSummary,
    val estExact: Int,
    val estAdjacent: Int,
    val estWrong: Int,
    /** Median Read Margin over every scored answer, null when none. */
    val medianMarginMs: Long?,
) {
    val estScored: Int get() = estExact + estAdjacent + estWrong

    companion object {
        fun of(done: List<ReadAttempt>, grades: List<Graded>): ReadLabSummary {
            val pairs = done.zip(grades)
            val confirmed = ReadSummary.of(pairs.filter { !it.second.vsEstimate }.map { it.first })
            val est = pairs.filter { it.second.vsEstimate }.map { it.second.result }
            val margins = pairs.filter { it.second.result !is ReadResult.Unscored }.mapNotNull { it.first.readMarginMs }.sorted()
            return ReadLabSummary(
                confirmed,
                est.count { it == ReadResult.Exact }, est.count { it == ReadResult.Adjacent }, est.count { it == ReadResult.Wrong },
                margins.takeIf { it.isNotEmpty() }?.let { it[(it.size - 1) / 2] },
            )
        }
    }
}

/** Holds the session across screen changes and rotation; the drill list identifies it. */
class ReadLabState {
    private val _session = MutableStateFlow<ReadLabSession?>(null)
    val session: StateFlow<ReadLabSession?> = _session.asStateFlow()

    /** Starts over unless this exact list of drills is already running. */
    fun start(drills: List<Drill>) {
        _session.update { cur -> if (cur != null && cur.drills.map { it.hitEventId } == drills.map { it.hitEventId }) cur else ReadLabSession(drills) }
    }

    fun restart() = _session.update { cur -> cur?.let { ReadLabSession(it.drills) } }
    fun update(f: (ReadLabSession) -> ReadLabSession) = _session.update { it?.let(f) }
}

/** What the reveal says, in plain words. */
data class RevealText(val verdict: String, val landing: String, val scored: Boolean)

object ReadLabText {
    const val HEADER = "ReadLab · train your read on your own match"
    const val EMPTY = "No landings with court positions yet. Analyse a match with the court set up."

    fun entry(n: Int) = "Train your read ($n drill${if (n == 1) "" else "s"})"

    private val ROW = mapOf(ZoneRow.FRONT to "front", ZoneRow.MIDDLE to "middle", ZoneRow.BACK to "back")
    private val COL = mapOf(1 to "left", 2 to "centre", 3 to "right")

    /** "back left (B1)"; the middle column reads "back centre (B2)". Left and right are the hitter's view. */
    fun zoneName(z: ZoneId) = "${ROW.getValue(z.row)} ${COL.getValue(z.col)} (${z.id})"

    /** The zone a landing point falls in for a hitter on [side], or null if it is out or on the hitter's half. */
    fun zoneOf(d: Drill): ZoneId? =
        (ZoneGrid.forTemplate(d.template)?.hit(d.landingXyM.first, d.landingXyM.second, d.viewSide) as? ZoneHit.In)?.zone

    fun verdict(g: Graded): String {
        val r = g.result
        if (g.vsEstimate) return "Your read: ${r.wire} (vs engine estimate, not confirmed)"
        return when (r) {
            ReadResult.Exact -> "Exact read"
            ReadResult.Adjacent -> "Adjacent: one zone away"
            ReadResult.Wrong -> "Not this time"
            is ReadResult.Unscored -> "Unconfirmed, not scored"
        }
    }

    /**
     * The landing line. A confirmed landing (observed by the engine, or set by the user) is
     * "Real landing"; an estimate is "Engine estimate" and its grade says so. Never presented as certain.
     */
    fun reveal(d: Drill, g: Graded): RevealText {
        val r = g.result
        val where = zoneOf(d)?.let { zoneName(it) }
        val landing = when {
            g.vsEstimate -> "Engine estimate: $where"
            r is ReadResult.Unscored -> when (r.reason) {
                UnscoredReason.UNTRUSTED_LANDING ->
                    if (where != null) "Engine estimate: $where, not confirmed" else "Engine estimate, not confirmed"
                UnscoredReason.LANDED_OUT -> "Landing: out of the court (${d.landingState})"
                UnscoredReason.DID_NOT_CROSS -> "Landing: on the hitter's own half (${d.landingState})"
                else -> "Landing: ${d.landingState}. ${r.reason.text}"
            }
            else -> {
                val src = if (d.landingState == "user") "set by you" else "observed"
                "Real landing: ${where ?: "on court"}, $src"
            }
        }
        val full = if (!g.vsEstimate && r is ReadResult.Unscored && r.reason == UnscoredReason.UNTRUSTED_LANDING) "$landing. ${r.reason.text}, so this read is not scored." else landing
        return RevealText(verdict(g), full, r !is ReadResult.Unscored)
    }

    /**
     * Summary lines. Confirmed and estimate counts are never merged: "Read 4 of 7 exactly · 2 adjacent
     * (vs engine estimates)". When both exist, the confirmed line says "(confirmed landings)".
     */
    fun summaryLines(s: ReadLabSummary): List<String> {
        val lines = mutableListOf<String>()
        val c = s.confirmed
        val mixed = c.scored > 0 && s.estScored > 0
        if (c.scored > 0) lines += "Read ${c.exact} of ${c.scored} exactly · ${c.adjacent} adjacent" + if (mixed) " (confirmed landings)" else ""
        if (s.estScored > 0) lines += "Read ${s.estExact} of ${s.estScored} exactly · ${s.estAdjacent} adjacent (vs engine estimates)"
        if (c.unscored > 0) lines += "${c.unscored} not scorable (no usable landing zone)"
        if (lines.isEmpty()) lines += "No drills answered"
        return lines
    }

    fun margin(a: ReadAttempt): String? = a.readMarginMs?.let { "Read margin: $it ms before contact" }

}
