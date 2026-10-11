package com.elevate.app.ui.decide

import com.elevate.app.data.api.MatchEvent
import com.elevate.app.data.api.MatchPlayer
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.decide.FootSample
import com.elevate.app.domain.decide.PlayerFeet
import com.elevate.app.domain.decide.Reach
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DecideFreezesTest {
    private val tpl = "badminton_singles"
    private val frames = LongArray(200) { it * 33_333L }
    private val you = MatchPlayer("p_you", listOf("t_me"), "you", "user_confirmed")
    private val other = MatchPlayer("p_opp", listOf("t_opp"), "opponent", "unconfirmed")

    private fun hit(id: String, tMs: Long, player: String? = null, track: String? = null, sigma: Long = 20) =
        MatchEvent(id, "hit", tMs, sigma, emptyList(), "observed", listOf(100.0, 100.0), null, player, track)

    private fun landing(id: String, tMs: Long, x: Double, y: Double, state: String = "observed") =
        MatchEvent(id, "landing", tMs, 20, emptyList(), state, null, null, null, null, "court", listOf(x, y))

    private fun build(events: List<MatchEvent>, who: MatchPlayer? = you, feet: PlayerFeet = PlayerFeet.EMPTY, point: (MatchEvent) -> Pair<Double, Double>? = { 2.5 to 1.0 }) =
        DecideFreezes.build(events, tpl, who, frames, feet, point)

    @Test fun `the freeze frame is a real frame strictly before contact and its uncertainty`() {
        val f = build(listOf(hit("h1", 2000, "p_you"))).single()
        assertThat(f.freezeUs).isIn(frames.toList())
        assertThat(f.freezeUs).isLessThan((2000 - 20) * 1000L)
        assertThat(f.freezeUs).isAtMost((2000 - 250) * 1000L)
    }

    @Test fun `contact exactly on a frame still freezes before it`() {
        val contactMs = frames[90] / 1000 + 1 // 2999 ms, frame 90 sits at 2999.97 ms
        val f = build(listOf(hit("h1", frames[60] / 1000, "p_you", sigma = 0))).single()
        assertThat(f.freezeUs).isLessThan(frames[60])
        assertThat(contactMs).isGreaterThan(0)
    }

    @Test fun `a hit with no real frame before it is not offered`() {
        assertThat(build(listOf(hit("h1", 100, "p_you")))).isEmpty()
    }

    @Test fun `only your own hits are used when they can be told apart`() {
        val f = build(listOf(hit("a", 2000, "p_you"), hit("b", 3000, "p_opp"), hit("c", 4000, track = "t_me"), hit("d", 5000, track = "t_opp")))
        assertThat(f.map { it.hitEventId }).containsExactly("a", "c").inOrder()
        assertThat(f.all { it.yours }).isTrue()
    }

    @Test fun `hits are attributed by the nearest foot when the event names nobody`() {
        val feet = PlayerFeet(listOf(
            FootSample(2000, "t_me", 2.5, 1.2), FootSample(2000, "t_opp", 2.5, 12.0),
            FootSample(3000, "t_me", 2.5, 1.2), FootSample(3000, "t_opp", 2.5, 12.0),
        ))
        val point = { e: MatchEvent -> if (e.eventId == "mine") 2.4 to 1.5 else 2.6 to 11.5 }
        val f = build(listOf(hit("mine", 2000), hit("theirs", 3000)), feet = feet, point = point)
        assertThat(f.map { it.hitEventId }).containsExactly("mine")
    }

    @Test fun `without a confirmed identity every hit is offered and labelled as a rally shot`() {
        val f = build(listOf(hit("a", 2000), hit("b", 3000)), who = null)
        assertThat(f).hasSize(2)
        assertThat(f.none { it.yours }).isTrue()
        assertThat(f.first().caption).isEqualTo("A shot from this rally")
        // An unconfirmed-only match record has no "you" at all.
        assertThat(DecideFreezes.you(null)).isNull()
        assertThat(other.identity).isNotEqualTo("user_confirmed")
    }

    @Test fun `defenders come from real foot rows on the far half and a missing one is empty`() {
        val feet = PlayerFeet(listOf(FootSample(1750, "t_opp", 2.5, 11.0), FootSample(1750, "t_me", 2.5, 1.0)))
        val seen = build(listOf(hit("a", 2000, "p_you")), feet = feet).single()
        assertThat(seen.side).isEqualTo(CourtSide.NEAR)
        assertThat(seen.defenders.map { it.playerId }).containsExactly("t_opp")
        assertThat(seen.hitterXyM).isEqualTo(2.5 to 1.0)
        val unseen = build(listOf(hit("a", 2000, "p_you"))).single()
        assertThat(unseen.defenders).isEmpty()
        assertThat(unseen.defenderSeen).isFalse()
        // Rows too far from the freeze frame are not used.
        val stale = build(listOf(hit("a", 2000, "p_you")), feet = PlayerFeet(listOf(FootSample(500, "t_opp", 2.5, 11.0)))).single()
        assertThat(stale.defenders).isEmpty()
    }

    @Test fun `the real landing is shown only when trusted and carries its own reach`() {
        val feet = PlayerFeet(listOf(FootSample(1750, "t_opp", 2.5, 7.5)))
        val f = build(listOf(hit("a", 2000, "p_you"), landing("l", 2600, 4.5, 13.0)), feet = feet).single()
        assertThat(f.real).isNotNull()
        assertThat(f.real!!.reach).isEqualTo(Reach.OPEN_COURT)
        val cand = build(listOf(hit("a", 2000, "p_you"), landing("l", 2600, 4.5, 13.0, state = "candidate")), feet = feet).single()
        assertThat(cand.real).isNull()
        assertThat(build(listOf(hit("a", 2000, "p_you"))).single().real).isNull()
    }
}
