package com.elevate.app.domain.community

import com.elevate.app.domain.community.CommunityTestData.drills
import com.elevate.app.domain.community.CommunityTestData.finishedSession
import com.elevate.app.ui.readlab.ReflexRounds
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ChallengesTest {
    private val session = finishedSession()

    @Test fun `the create request carries the exact freeze set and the real score`() {
        val r = Challenges.createRequest("m_1", session, "Hetansh")
        assertThat(r.rounds.map { it.hitEventId }).containsExactly("e_h1", "e_h2", "e_h3", "e_h4").inOrder()
        assertThat(r.rounds.map { it.landingEventId }).containsExactly("e_l1", "e_l2", "e_l3", "e_l4").inOrder()
        val first = session.rounds.first().drill
        assertThat(r.rounds.first().ladderUs).isEqualTo(first.challenge.ladderUs)
        assertThat(r.rounds.first().contactMs).isEqualTo(3_000)
        assertThat(r.rounds.first().contactSigmaMs).isEqualTo(12)
        assertThat(r.score).isEqualTo(session.score)
        assertThat(r.score).isEqualTo(r.roundResults.sumOf { it.points })
        assertThat(r.roundResults.map { it.verdict }).containsExactly("exact", "adjacent", "too_slow", "exact").inOrder()
        assertThat(r.roundResults[2].pick).isNull()
        assertThat(r.roundResults[2].reactionMs).isNull()
        assertThat(r.roundResults[0].pick).isEqualTo("B1")
    }

    @Test fun `request json writes nullable fields as null and leaves absent optionals out`() {
        val o = Json.parseToJsonElement(Challenges.createRequest("m_1", session, null).toJson()).jsonObject
        assertThat(o.keys).containsExactly("match_id", "rounds", "score", "round_results")
        val tooSlow = o["round_results"]!!.jsonArray[2].jsonObject
        assertThat(tooSlow["pick"]).isEqualTo(JsonNull)
        assertThat(tooSlow["reaction_ms"]).isEqualTo(JsonNull)
        val withName = Json.parseToJsonElement(Challenges.createRequest("m_1", session, "Asha", "ch_old").toJson()).jsonObject
        assertThat(withName.keys).containsAtLeast("display_name", "rematch_of")
        assertThat(Json.parseToJsonElement(ChallengeAcceptRequest(null).toJson()).jsonObject).isEmpty()
        assertThat(Json.parseToJsonElement(Challenges.entryRequest(session, null).toJson()).jsonObject.keys).containsExactly("score", "round_results")
    }

    private fun challengeOf(): Challenge {
        val req = Challenges.createRequest("m_1", session, "Hetansh")
        return CommunityTestData.challenge(req.rounds, req.roundResults)
    }

    @Test fun `the friend rebuilds the same rounds in the challenge order`() {
        val sel = Challenges.select(challengeOf(), drills.reversed())
        assertThat(sel.rounds.map { it.drill.hitEventId }).containsExactly("e_h1", "e_h2", "e_h3", "e_h4").inOrder()
        assertThat(sel.missing).isEqualTo(0)
    }

    @Test fun `rounds that cannot be rebuilt are counted missing, never invented`() {
        val c = challengeOf()
        val sel = Challenges.select(c, drills.filter { it.hitEventId != "e_h2" })
        assertThat(sel.rounds.map { it.drill.hitEventId }).containsExactly("e_h1", "e_h3", "e_h4").inOrder()
        assertThat(sel.total).isEqualTo(4)
        assertThat(sel.missing).isEqualTo(1)
        // A round whose real frames differ from the creator's is a different freeze: left out.
        val shifted = c.copy(rounds = c.rounds.mapIndexed { i, r -> if (i == 0) r.copy(ladderUs = r.ladderUs.map { it + 20_000 }) else r })
        assertThat(Challenges.select(shifted, drills).missing).isEqualTo(1)
        assertThat(ReflexRounds.of(Challenges.select(c, drills).rounds.map { it.drill })).hasSize(4)
    }

    @Test fun `shots are compared by hit id in the challenge order`() {
        val c = challengeOf()
        val mine = listOf(
            ChallengeRoundResult("e_h3", "B3", 700, "exact", 110),
            ChallengeRoundResult("e_h1", null, null, "too_slow", 0),
        )
        val cmp = Challenges.compare(c, mine)
        assertThat(cmp.map { it.hitEventId }).containsExactly("e_h1", "e_h2", "e_h3", "e_h4").inOrder()
        assertThat(cmp[0].creator?.verdict).isEqualTo("exact")
        assertThat(cmp[0].player?.verdict).isEqualTo("too_slow")
        assertThat(cmp[1].player).isNull()
        assertThat(cmp[2].player?.points).isEqualTo(110)
    }

    @Test fun `text uses the server's names and numbers`() {
        val c = challengeOf()
        assertThat(Challenges.inviteTitle(c)).isEqualTo("Hetansh challenged you")
        assertThat(Challenges.inviteLine(c)).isEqualTo("beat 1,240 on 4 shots")
        val played = ChallengeEntry("22222222-2222-4222-8222-222222222222", "Asha", 900, emptyList(), "creator", "2026-10-11T10:00:00Z", "2026-10-11T10:05:00Z")
        assertThat(Challenges.winnerText(c, played)).isEqualTo("Hetansh won")
        assertThat(Challenges.winnerText(c.copy(viewer = c.viewer.copy(role = "creator")), played)).isEqualTo("You won")
        assertThat(Challenges.winnerText(c, played.copy(outcome = "opponent"))).isEqualTo("You won")
        assertThat(Challenges.winnerText(c, played.copy(outcome = "tie"))).isEqualTo("It's a tie")
        assertThat(Challenges.winnerText(c, played.copy(outcome = null, score = null))).isNull()
    }

    @Test fun `share text carries the elevate link and no em dash`() {
        val t = ChallengeLink.shareText("ch_abc", 1240)
        assertThat(t).contains("elevate://c/ch_abc")
        assertThat(t).contains("1,240")
        assertThat(t).doesNotContain("\u2014")
    }

    @Test fun `friends text and name to send`() {
        val f = Friend("22222222-2222-4222-8222-222222222222", "Asha", 3, 2, 1, 1, 0, "2026-10-11T10:00:00Z")
        assertThat(Challenges.friendRecord(f)).isEqualTo("1-1-0")
        assertThat(Challenges.friendDenominator(f)).isEqualTo("2 played of 3 challenges")
        assertThat(Challenges.nameToSend("You", "You")).isNull()
        assertThat(Challenges.nameToSend(" Hetansh ", "You")).isEqualTo("Hetansh")
    }
}
