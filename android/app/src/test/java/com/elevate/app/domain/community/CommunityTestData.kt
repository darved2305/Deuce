package com.elevate.app.domain.community

import com.elevate.app.data.api.MatchEvent
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.ui.readlab.ReadLabDrills
import com.elevate.app.ui.readlab.ReflexRounds
import com.elevate.app.ui.readlab.ReflexSession

/** Real-shaped fixtures for the pure community logic: hits and landings of one match on a 50 fps file. */
object CommunityTestData {
    const val TEMPLATE = "badminton_singles"
    val frames = LongArray(600) { it * 20_000L }

    private fun hit(id: String, ms: Long) = MatchEvent(id, "hit", ms, 12, listOf("audio"), "observed", imageXy = listOf(640.0, 400.0))
    private fun landing(id: String, ms: Long, x: Double, y: Double) =
        MatchEvent(id, "landing", ms, 9, listOf("trajectory"), "observed", plane = "court", contactXyM = listOf(x, y))

    val events = listOf(
        hit("e_h1", 3_000), landing("e_l1", 3_900, 0.4, 12.8),
        hit("e_h2", 5_000), landing("e_l2", 5_900, 2.6, 8.0),
        hit("e_h3", 7_000), landing("e_l3", 7_900, 4.8, 12.5),
        hit("e_h4", 9_000), landing("e_l4", 9_900, 0.4, 12.8),
    )

    val drills get() = ReadLabDrills.build(events, TEMPLATE, frames) { 1.5 }

    fun zone(s: String) = ZoneId.parse(s)!!

    /** A finished run: exact, adjacent, too slow, exact. */
    fun finishedSession(): ReflexSession {
        var s = ReflexSession(ReflexRounds.of(drills)).begin()
        s = s.answer(zone("B1"), 300)
        s = s.answer(zone("M2"), 400)
        s = s.answer(null, null)
        s = s.answer(zone("B1"), 500)
        return s
    }

    fun challenge(
        rounds: List<ChallengeRound>,
        creatorResults: List<ChallengeRoundResult> = emptyList(),
        role: String = "opponent",
        entries: List<ChallengeEntry> = emptyList(),
    ) = Challenge(
        id = "ch_abc", matchId = "m_1", status = "open", videoSha256 = "a".repeat(64), rematchOf = null,
        rounds = rounds,
        creator = ChallengePlayer("11111111-1111-4111-8111-111111111111", "Hetansh", 1240, creatorResults),
        entries = entries,
        viewer = ChallengeViewer(if (role == "creator") "11111111-1111-4111-8111-111111111111" else "22222222-2222-4222-8222-222222222222", role),
        createdAt = "2026-10-11T09:00:00Z",
    )
}
