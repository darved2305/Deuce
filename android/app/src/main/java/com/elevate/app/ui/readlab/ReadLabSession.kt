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
) {
    val drill: Drill? get() = drills.getOrNull(index)
    val finished: Boolean get() = index >= drills.size
    val isLast: Boolean get() = index == drills.lastIndex

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
        return copy(index = i, attempt = drills.getOrNull(i)?.let { ReadAttempt(it.challenge) }, done = done + a)
    }

    val summary: ReadSummary get() = ReadSummary.of(done)

    companion object {
        /** Seek target (ms, rounded up) for a real frame time: shows exactly that frame, never an earlier one. */
        fun seekMs(frameUs: Long): Long = (frameUs + 999) / 1000
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

    fun verdict(r: ReadResult) = when (r) {
        ReadResult.Exact -> "Exact read"
        ReadResult.Adjacent -> "Adjacent: one zone away"
        ReadResult.Wrong -> "Not this time"
        is ReadResult.Unscored -> "Unconfirmed, not scored"
    }

    /**
     * The honest landing line. A trusted landing (observed by the engine, or set by the user) is
     * "Real landing"; anything else is "Engine candidate, not confirmed" and is never scored.
     */
    fun reveal(d: Drill, r: ReadResult): RevealText {
        val zone = zoneOf(d)
        val where = zone?.let { zoneName(it) }
        val landing = when (r) {
            is ReadResult.Unscored -> when (r.reason) {
                UnscoredReason.UNTRUSTED_LANDING ->
                    if (where != null) "Engine candidate: $where, not confirmed" else "Engine candidate landing, not confirmed"
                UnscoredReason.LANDED_OUT -> "Landing: out of the court (${d.landingState})"
                UnscoredReason.DID_NOT_CROSS -> "Landing: on the hitter's own half (${d.landingState})"
                else -> "Landing: ${d.landingState}. ${r.reason.text}"
            }
            else -> {
                val src = if (d.landingState == "user") "set by you" else "observed"
                "Real landing: ${where ?: "on court"}, $src"
            }
        }
        val full = if (r is ReadResult.Unscored && r.reason == UnscoredReason.UNTRUSTED_LANDING) "$landing. ${r.reason.text}, so this read is not scored." else landing
        return RevealText(verdict(r), full, r !is ReadResult.Unscored)
    }

    /** "Read 5 of 8 exactly · 2 adjacent", plus the unconfirmed count when there is one. */
    fun summary(s: ReadSummary): String {
        if (s.scored == 0) {
            return if (s.unscored == 0) "No drills answered" else "${s.unscored} unconfirmed: the engine's landings are candidates, so none could be scored"
        }
        val parts = mutableListOf("Read ${s.exact} of ${s.scored} exactly", "${s.adjacent} adjacent")
        if (s.unscored > 0) parts += "${s.unscored} unconfirmed"
        return parts.joinToString(" · ")
    }

    fun margin(a: ReadAttempt): String? = a.readMarginMs?.let { "Read margin: $it ms before contact" }

}
