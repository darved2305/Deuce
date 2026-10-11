package com.elevate.app.ui.readlab

import com.elevate.app.data.api.MatchEvent
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.readlab.ReadResult
import com.elevate.app.domain.readlab.UnscoredReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadLabDrillsTest {
    private val t = "badminton_singles"
    private val frames = LongArray(400) { it * 20_000L } // 50 fps, 0 to 7.98 s

    private fun hit(id: String, ms: Long, state: String = "observed") =
        MatchEvent(id, "hit", ms, 12, listOf("audio"), state, imageXy = listOf(640.0, 400.0))

    private fun landing(id: String, ms: Long, x: Double, y: Double, state: String = "observed", plane: String? = "court") =
        MatchEvent(id, "landing", ms, 9, listOf("trajectory"), state, plane = plane, contactXyM = listOf(x, y))

    /** The hitter is on the near half for every hit here. */
    private fun build(events: List<MatchEvent>) = ReadLabDrills.build(events, t, frames) { 1.5 }

    @Test fun `a landing pairs with the last hit before it`() {
        val pairs = ReadLabDrills.pair(listOf(hit("h1", 1_000), hit("h2", 2_000), landing("l1", 3_000, 1.0, 10.0)))
        assertThat(pairs.map { it.first.eventId to it.second.eventId }).containsExactly("h2" to "l1")
    }

    @Test fun `a hit more than three seconds back does not pair`() {
        assertThat(ReadLabDrills.pair(listOf(hit("h1", 1_000), landing("l1", 4_001, 1.0, 10.0)))).isEmpty()
        assertThat(ReadLabDrills.pair(listOf(hit("h1", 1_000), landing("l1", 4_000, 1.0, 10.0)))).hasSize(1)
    }

    @Test fun `no hit before the landing means no drill`() {
        assertThat(ReadLabDrills.pair(listOf(landing("l1", 3_000, 1.0, 10.0), hit("h1", 5_000)))).isEmpty()
        assertThat(build(listOf(landing("l1", 3_000, 1.0, 10.0)))).isEmpty()
    }

    @Test fun `landings without court metres make no drill`() {
        val noXy = MatchEvent("l1", "landing", 2_000, 9, emptyList(), "observed", plane = "court", contactXyM = null)
        assertThat(ReadLabDrills.pair(listOf(hit("h1", 1_000), noXy))).isEmpty()
        assertThat(ReadLabDrills.pair(listOf(hit("h1", 1_000), landing("l1", 2_000, 1.0, 10.0, plane = "image")))).isEmpty()
    }

    @Test fun `one hit serves one landing`() {
        val pairs = ReadLabDrills.pair(listOf(hit("h1", 1_000), landing("l1", 2_000, 1.0, 10.0), landing("l2", 2_500, 2.0, 11.0)))
        assertThat(pairs.map { it.second.eventId }).containsExactly("l1")
    }

    @Test fun `freeze frames are strictly before contact minus sigma`() {
        val d = build(listOf(hit("h1", 1_500), landing("l1", 2_400, 1.0, 10.0))).single()
        val limitUs = (1_500L - 12) * 1000
        assertThat(d.challenge.ladderUs).isNotEmpty()
        assertThat(d.challenge.ladderUs.all { it < limitUs }).isTrue()
        assertThat(d.challenge.ladderUs).isInOrder()
        assertThat(ReadLabSession.seekMs(d.challenge.ladderUs.last()) * 1000).isAtMost(limitUs)
    }

    @Test fun `a hit with no real frame before it makes no drill`() {
        assertThat(ReadLabDrills.build(listOf(hit("h1", 5), landing("l1", 900, 1.0, 10.0)), t, frames) { 1.5 }).isEmpty()
    }

    @Test fun `seek rounds the frame time up so that exact frame shows`() {
        assertThat(ReadLabSession.seekMs(1_000_000)).isEqualTo(1_000)
        assertThat(ReadLabSession.seekMs(1_020_833)).isEqualTo(1_021)
    }

    @Test fun `end to end over a small realistic match`() {
        // Hitter near, shuttle crosses to the far half. Far half from the near hitter: B1 is x small, y large.
        val events = listOf(
            hit("h1", 1_000), landing("l1", 1_900, 0.4, 12.8),   // B1, observed
            hit("h2", 3_000), landing("l2", 3_900, 2.6, 8.0),    // F2, observed
            hit("h3", 5_000), landing("l3", 5_900, 4.8, 12.5),   // B3, observed
            hit("h4", 7_000), landing("l4", 7_900, 2.6, 10.0, state = "candidate"),
        )
        val drills = build(events)
        assertThat(drills).hasSize(4)
        var s = ReadLabSession(drills)
        s = s.pick(ZoneId.parse("B1")!!).next()       // exact
        s = s.pick(ZoneId.parse("M2")!!).next()       // F2 truth, M2 touches: adjacent
        s = s.pick(ZoneId.parse("F1")!!).next()       // B3 truth: wrong
        s = s.pick(ZoneId.parse("M2")!!)
        assertThat(s.attempt!!.result).isEqualTo(ReadResult.Unscored(UnscoredReason.UNTRUSTED_LANDING))
        s = s.next()
        assertThat(s.finished).isTrue()
        val sum = s.summary
        assertThat(listOf(sum.exact, sum.adjacent, sum.wrong, sum.unscored)).containsExactly(1, 1, 1, 1).inOrder()
        assertThat(ReadLabText.summary(sum)).isEqualTo("Read 1 of 3 exactly · 1 adjacent · 1 unconfirmed")
    }

    @Test fun `the answer locks once`() {
        val d = build(listOf(hit("h1", 1_500), landing("l1", 2_400, 0.4, 12.8)))
        val s = ReadLabSession(d).pick(ZoneId.parse("B1")!!)
        assertThat(s.pick(ZoneId.parse("F3")!!)).isEqualTo(s)
        assertThat(ReadLabSession(d).next().index).isEqualTo(0) // no skipping an unanswered drill
    }

    @Test fun `a candidate landing is labelled not confirmed and never scored`() {
        val d = build(listOf(hit("h1", 1_500), landing("l1", 2_400, 0.4, 12.8, state = "candidate"))).single()
        assertThat(d.viewSide).isEqualTo(CourtSide.NEAR)
        val r = ReadLabSession(listOf(d)).pick(ZoneId.parse("B1")!!).attempt!!.result!!
        assertThat(r).isEqualTo(ReadResult.Unscored(UnscoredReason.UNTRUSTED_LANDING))
        val text = ReadLabText.reveal(d, r)
        assertThat(text.landing).contains("Engine candidate: back left (B1), not confirmed")
        assertThat(text.scored).isFalse()
        assertThat(text.landing + text.verdict).doesNotContain("\u2014")
    }

    @Test fun `an observed landing reads as the real landing`() {
        val d = build(listOf(hit("h1", 1_500), landing("l1", 2_400, 0.4, 12.8))).single()
        val r = ReadLabSession(listOf(d)).pick(ZoneId.parse("B1")!!).attempt!!.result!!
        assertThat(ReadLabText.reveal(d, r).landing).isEqualTo("Real landing: back left (B1), observed")
    }

    @Test fun `an unobserved hitter position leaves the drill unscored`() {
        val d = ReadLabDrills.build(listOf(hit("h1", 1_500, state = "candidate"), landing("l1", 2_400, 0.4, 12.8)), t, frames) { 1.5 }.single()
        val r = ReadLabSession(listOf(d)).pick(ZoneId.parse("B1")!!).attempt!!.result!!
        assertThat(r).isEqualTo(ReadResult.Unscored(UnscoredReason.UNKNOWN_HITTER_SIDE))
    }

    @Test fun `picker point is in the hitter's view`() {
        val near = ZoneView.point(0.0, 13.4, CourtSide.NEAR, 5.18)
        assertThat(near.first).isWithin(1e-6f).of(0f)
        assertThat(near.second).isWithin(1e-6f).of(1f)
        val far = ZoneView.point(0.0, 0.0, CourtSide.FAR, 5.18)
        assertThat(far.first).isWithin(1e-6f).of(1f)
        assertThat(far.second).isWithin(1e-6f).of(1f)
    }
}
