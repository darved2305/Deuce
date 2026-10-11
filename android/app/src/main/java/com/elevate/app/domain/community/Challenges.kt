package com.elevate.app.domain.community

import com.elevate.app.ui.readlab.Drill
import com.elevate.app.ui.readlab.ReflexRound
import com.elevate.app.ui.readlab.ReflexRoundResult
import com.elevate.app.ui.readlab.ReflexRounds
import com.elevate.app.ui.readlab.ReflexSession
import com.elevate.app.ui.readlab.ReflexVerdict
import java.text.NumberFormat
import java.util.Locale

/** The challenge link and the text that goes with it (no web domain exists, so the text is the fallback). */
object ChallengeLink {
    val ID = Regex("ch_[A-Za-z0-9_-]{1,128}")

    fun uri(id: String) = "elevate://c/$id"

    /** [score] is the sender's own Reflex score on this match. */
    fun shareText(id: String, score: Int): String =
        "I scored ${Challenges.points(score)} on Reflex in Elevate. Can you beat me? Open this link on a phone with the Elevate app: ${uri(id)}"
}

/** One shot of the creator's run next to the player's, matched by hit event id. */
data class ShotComparison(val index: Int, val hitEventId: String, val creator: ChallengeRoundResult?, val player: ChallengeRoundResult?)

/** The rounds this phone can play, and how many of the challenge's rounds it could not rebuild. */
data class RoundSelection(val rounds: List<ReflexRound>, val total: Int) {
    val missing: Int get() = total - rounds.size
}

object Challenges {
    /** Whole points with a thousands separator: 1240 becomes 1,240. */
    fun points(n: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

    fun verdictWire(v: ReflexVerdict): String = when (v) {
        ReflexVerdict.EXACT -> "exact"
        ReflexVerdict.ADJACENT -> "adjacent"
        ReflexVerdict.WRONG -> "wrong"
        ReflexVerdict.TOO_SLOW -> "too_slow"
    }

    fun verdictLabel(wire: String): String = when (wire) {
        "exact" -> "Exact"
        "adjacent" -> "Adjacent"
        "wrong" -> "Wrong"
        "too_slow" -> "Too slow"
        else -> wire
    }

    /** The freeze set: exactly the drill's real frames and contact. */
    fun roundOf(r: ReflexRound): ChallengeRound {
        val c = r.drill.challenge
        return ChallengeRound(r.drill.hitEventId, r.drill.landingEventId, c.contactMs, r.drill.contactSigmaMs, c.ladderUs)
    }

    fun resultOf(r: ReflexRoundResult) =
        ChallengeRoundResult(r.hitEventId, r.pick?.id, r.reactionMs, verdictWire(r.verdict), r.points)

    /** A finished run becomes a challenge on [matchId]. */
    fun createRequest(matchId: String, session: ReflexSession, displayName: String?, rematchOf: String? = null) =
        ChallengeCreateRequest(
            matchId = matchId,
            displayName = displayName,
            rounds = session.rounds.map(::roundOf),
            score = session.score,
            roundResults = session.results.map(::resultOf),
            rematchOf = rematchOf,
        )

    /** A finished run of a friend's challenge becomes that player's entry. */
    fun entryRequest(session: ReflexSession, displayName: String?) =
        ChallengeEntryRequest(displayName, session.score, session.results.map(::resultOf))

    /**
     * The drills of [challenge] rebuilt from this phone's own match record and video, in the challenge's
     * order. A round whose drill is missing, or whose real frames differ from the creator's, is not
     * played: nothing is invented, the card says how many of the total could be played.
     */
    fun select(challenge: Challenge, drills: List<Drill>): RoundSelection {
        val byHit = drills.associateBy { it.hitEventId }
        val same = challenge.rounds.mapNotNull { r ->
            byHit[r.hitEventId]?.takeIf { it.challenge.ladderUs == r.ladderUs && it.landingEventId == r.landingEventId }
        }
        val order = challenge.rounds.mapIndexed { i, r -> r.hitEventId to i }.toMap()
        val rounds = ReflexRounds.of(same).sortedBy { order[it.drill.hitEventId] }
        return RoundSelection(rounds, challenge.rounds.size)
    }

    /** Per-shot comparison in the challenge's order. */
    fun compare(challenge: Challenge, player: List<ChallengeRoundResult>?): List<ShotComparison> {
        val mine = player.orEmpty().associateBy { it.hitEventId }
        val theirs = challenge.creator.roundResults.associateBy { it.hitEventId }
        return challenge.rounds.mapIndexed { i, r -> ShotComparison(i + 1, r.hitEventId, theirs[r.hitEventId], mine[r.hitEventId]) }
    }

    /** "Hetansh challenged you" */
    fun inviteTitle(c: Challenge) = "${c.creator.displayName} challenged you"

    /** "beat 1,240 on 6 shots" */
    fun inviteLine(c: Challenge) = "beat ${points(c.creator.score)} on ${c.rounds.size} shot${if (c.rounds.size == 1) "" else "s"}"

    /** Who won, from the viewer's side, using the server's outcome. Null until the entry is played. */
    fun winnerText(c: Challenge, entry: ChallengeEntry): String? {
        val outcome = entry.outcome ?: return null
        val viewerIsCreator = c.isCreator
        return when (outcome) {
            "tie" -> "It's a tie"
            "creator" -> if (viewerIsCreator) "You won" else "${c.creator.displayName} won"
            else -> if (viewerIsCreator) "${entry.displayName} won" else "You won"
        }
    }

    /** "2 played of 3 challenges" */
    fun friendDenominator(f: Friend) = "${f.played} played of ${f.challenges} challenge${if (f.challenges == 1) "" else "s"}"

    /** "1-1-0" wins, losses, ties */
    fun friendRecord(f: Friend) = "${f.wins}-${f.losses}-${f.ties}"

    /** The profile name goes with a challenge unless it is still the default. */
    fun nameToSend(profileName: String, default: String): String? = profileName.trim().takeIf { it.isNotEmpty() && it != default }
}
