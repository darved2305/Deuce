package com.elevate.app.ui.readlab

import com.elevate.app.data.api.MatchEvent
import com.elevate.app.domain.court.ZoneId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReflexGameTest {
    private val t = "badminton_singles"
    private val frames = LongArray(600) { it * 20_000L } // 50 fps, 0 to 11.98 s

    private fun hit(id: String, ms: Long) = MatchEvent(id, "hit", ms, 12, listOf("audio"), "observed", imageXy = listOf(640.0, 400.0))
    private fun landing(id: String, ms: Long, x: Double, y: Double, state: String = "observed") =
        MatchEvent(id, "landing", ms, 9, listOf("trajectory"), state, plane = "court", contactXyM = listOf(x, y))

    private fun drills(events: List<MatchEvent>) = ReadLabDrills.build(events, t, frames) { 1.5 }

    private fun zone(s: String) = ZoneId.parse(s)!!

    /** B1 (observed), F2 (observed), B3 (observed), B1 (candidate estimate). */
    private val four = drills(listOf(
        hit("h1", 3_000), landing("l1", 3_900, 0.4, 12.8),
        hit("h2", 5_000), landing("l2", 5_900, 2.6, 8.0),
        hit("h3", 7_000), landing("l3", 7_900, 4.8, 12.5),
        hit("h4", 9_000), landing("l4", 9_900, 0.4, 12.8, state = "candidate"),
    ))

    private fun session(relaxed: Boolean = false) = ReflexSession(ReflexRounds.of(four), relaxed).begin()

    @Test fun `speed bonus is capped, linear and never below one`() {
        assertThat(ReflexRules.speedBonusPct(0)).isEqualTo(150)
        assertThat(ReflexRules.speedBonusPct(400)).isEqualTo(150)
        assertThat(ReflexRules.speedBonusPct(950)).isEqualTo(125)
        assertThat(ReflexRules.speedBonusPct(1_500)).isEqualTo(100)
        assertThat(ReflexRules.speedBonusPct(2_900)).isEqualTo(100)
    }

    @Test fun `streak multiplier starts at one and caps at one and a half`() {
        assertThat(listOf(1, 2, 3, 6, 7, 50).map(ReflexRules::streakPct)).containsExactly(100, 110, 120, 150, 150, 150).inOrder()
    }

    @Test fun `points are accuracy times speed bonus times streak`() {
        assertThat(ReflexRules.points(ReflexVerdict.EXACT, 400, 1)).isEqualTo(150)
        assertThat(ReflexRules.points(ReflexVerdict.ADJACENT, 1_500, 1)).isEqualTo(50)
        assertThat(ReflexRules.points(ReflexVerdict.EXACT, 950, 3)).isEqualTo(150) // 100 x 1.25 x 1.2
        assertThat(ReflexRules.points(ReflexVerdict.EXACT, 100, 99)).isEqualTo(225) // the cap: 100 x 1.5 x 1.5
        assertThat(ReflexRules.points(ReflexVerdict.WRONG, 100, 4)).isEqualTo(0)
        assertThat(ReflexRules.points(ReflexVerdict.TOO_SLOW, null, 4)).isEqualTo(0)
    }

    @Test fun `a played run scores exact adjacent too slow and an estimate`() {
        var s = session()
        s = s.answer(zone("B1"), 300)   // exact, streak 1: 150
        s = s.answer(zone("M2"), 400)   // F2 truth, M2 touches: adjacent, streak 2: 50 x 1.5 x 1.1
        s = s.answer(null, null)        // too slow, streak resets
        s = s.answer(zone("B1"), 500)   // estimate, exact, streak 1: 100 x 1.46 x 1
        assertThat(s.finished).isTrue()
        assertThat(s.results.map { it.verdict }).containsExactly(
            ReflexVerdict.EXACT, ReflexVerdict.ADJACENT, ReflexVerdict.TOO_SLOW, ReflexVerdict.EXACT,
        ).inOrder()
        assertThat(s.results.map { it.points }).containsExactly(150, 82, 0, 146).inOrder()
        assertThat(s.results.map { it.streak }).containsExactly(1, 2, 0, 1).inOrder()
        assertThat(s.score).isEqualTo(378)
    }

    @Test fun `too slow gives no pick, no reaction and no points`() {
        val r = session().answer(null, null).results.single()
        assertThat(r.verdict).isEqualTo(ReflexVerdict.TOO_SLOW)
        assertThat(r.pick).isNull()
        assertThat(r.reactionMs).isNull()
        assertThat(r.points).isEqualTo(0)
    }

    @Test fun `a tap after the timer is too slow, unless relaxed gives more time`() {
        assertThat(session().answer(zone("B1"), 1_501).results.single().verdict).isEqualTo(ReflexVerdict.TOO_SLOW)
        assertThat(session().answer(zone("B1"), 1_500).results.single().verdict).isEqualTo(ReflexVerdict.EXACT)
        val relaxed = session(relaxed = true).answer(zone("B1"), 1_501).results.single()
        assertThat(relaxed.verdict).isEqualTo(ReflexVerdict.EXACT)
        assertThat(relaxed.points).isEqualTo(100) // no speed bonus past 1.5 s
        assertThat(session(relaxed = true).timerMs).isEqualTo(3_000)
        assertThat(session().timerMs).isEqualTo(1_500)
    }

    @Test fun `a wrong read or a too slow round resets the streak`() {
        var s = session()
        s = s.answer(zone("B1"), 300)
        s = s.answer(zone("F2"), 300)
        assertThat(s.streak).isEqualTo(2)
        s = s.answer(zone("F1"), 300) // B3 truth: wrong
        assertThat(s.results.last().verdict).isEqualTo(ReflexVerdict.WRONG)
        assertThat(s.streak).isEqualTo(0)
        assertThat(s.results.last().points).isEqualTo(0)
    }

    @Test fun `rounds keep match order and every real round is played once`() {
        val rounds = ReflexRounds.of(four.reversed())
        assertThat(rounds.map { it.drill.hitEventId }).containsExactly("h1", "h2", "h3", "h4").inOrder()
        var s = session()
        assertThat(s.round!!.drill.hitEventId).isEqualTo("h1")
        s = s.answer(zone("B1"), 300)
        assertThat(s.round!!.drill.hitEventId).isEqualTo("h2")
        s = s.answer(null, null).answer(null, null).answer(null, null)
        assertThat(s.finished).isTrue()
        assertThat(s.answer(zone("B1"), 300)).isEqualTo(s) // nothing after the last round
    }

    @Test fun `rounds with no scorable landing are left out`() {
        val events = listOf(
            hit("h1", 3_000), landing("l1", 3_900, 9.0, 12.0),  // wide of the sideline: out
            hit("h2", 5_000), landing("l2", 5_900, 2.6, 2.0),   // on the hitter's own half
            hit("h3", 7_000), landing("l3", 7_900, 2.6, 8.0),   // F2
        )
        assertThat(ReflexRounds.of(drills(events)).map { it.drill.hitEventId }).containsExactly("h3")
    }

    @Test fun `estimate rounds are flagged from the landing state`() {
        assertThat(ReflexRounds.of(four).map { it.vsEstimate }).containsExactly(false, false, false, true).inOrder()
    }

    @Test fun `round start is the freeze minus two seconds, clamped at zero`() {
        assertThat(ReflexRules.startUs(5_000_000)).isEqualTo(3_000_000)
        assertThat(ReflexRules.startUs(2_000_000)).isEqualTo(0)
        assertThat(ReflexRules.startUs(1_000_000)).isEqualTo(0)
        assertThat(ReflexRules.startUs(0)).isEqualTo(0)
        assertThat(ReflexRules.startUs(2_000_001)).isEqualTo(1)
    }

    @Test fun `the freeze is ReadLab's first ladder frame, strictly before contact`() {
        val d = four.first()
        val freezeUs = d.challenge.ladderUs.first()
        assertThat(freezeUs).isLessThan((3_000L - 12) * 1000)
        assertThat(ReflexRules.startUs(freezeUs)).isEqualTo(freezeUs - 2_000_000)
    }

    @Test fun `the summary counts the split, median reaction, best streak and estimates`() {
        var s = session()
        s = s.answer(zone("B1"), 300).answer(zone("M2"), 400).answer(null, null).answer(zone("B1"), 500)
        val sum = s.summary
        assertThat(sum.score).isEqualTo(378)
        assertThat(listOf(sum.exact, sum.adjacent, sum.wrong, sum.tooSlow)).containsExactly(2, 1, 0, 1).inOrder()
        assertThat(sum.accuracyPct).isEqualTo(75)
        assertThat(sum.medianReactionMs).isEqualTo(400)
        assertThat(sum.bestStreak).isEqualTo(2)
        assertThat(sum.estimateRounds).isEqualTo(1)
        assertThat(ReflexText.estimateNote(sum)).isEqualTo("1 of 4 rounds scored against engine estimates")
    }

    @Test fun `no estimate note when every landing was confirmed, and no median when all too slow`() {
        val s = ReflexSession(ReflexRounds.of(four.take(3)), false).begin().let { x -> x.answer(null, null).answer(null, null).answer(null, null) }
        assertThat(s.summary.medianReactionMs).isNull()
        assertThat(s.summary.bestStreak).isEqualTo(0)
        assertThat(ReflexText.estimateNote(s.summary)).isNull()
    }

    @Test fun `the timer can only change before the run starts`() {
        val fresh = ReflexSession(ReflexRounds.of(four))
        assertThat(fresh.withRelaxed(true).timerMs).isEqualTo(3_000)
        assertThat(fresh.withRelaxed(true).begin().withRelaxed(false).timerMs).isEqualTo(3_000)
    }

    @Test fun `the estimate suffix and reaction time are in the verdict line`() {
        val s = session().answer(zone("B1"), 300).answer(zone("F2"), 250).answer(zone("B3"), 200).answer(zone("B1"), 640)
        assertThat(ReflexText.verdictLine(s.results[0])).isEqualTo("Exact in 300 ms")
        assertThat(ReflexText.verdictLine(s.results[3])).isEqualTo("Exact in 640 ms (vs engine estimate)")
        assertThat(ReflexText.verdictLine(session().answer(null, null).results.single())).isEqualTo("Too slow")
        assertThat(ReflexText.entry(5)).isEqualTo("Reflex")
        assertThat(ReflexText.BEST_LABEL).isEqualTo("Your best on this match")
    }

    private class MemoryStore : ReflexBestStore {
        val map = HashMap<String, Int>()
        override fun best(matchKey: String) = map[matchKey]
        override fun save(matchKey: String, score: Int) { map[matchKey] = score }
    }

    @Test fun `the best score is kept per match and only beaten, once per run`() {
        val store = MemoryStore()
        val state = ReflexState()
        state.start(ReflexRounds.of(four))
        state.update { it.begin() }
        state.settle(store, "m1") // unfinished: nothing stored
        assertThat(store.map).isEmpty()
        repeat(4) { state.update { it.answer(zone("B1"), 300) } }
        state.settle(store, "m1")
        val first = state.settlement.value!!
        assertThat(first.before).isNull()
        assertThat(first.newBest).isTrue()
        assertThat(store.best("m1")).isEqualTo(first.score)
        assertThat(store.best("m2")).isNull()
        state.settle(store, "m1")
        assertThat(state.settlement.value).isEqualTo(first)

        state.restart(); state.update { it.begin() }
        repeat(4) { state.update { it.answer(null, null) } }
        state.settle(store, "m1")
        val second = state.settlement.value!!
        assertThat(second.newBest).isFalse()
        assertThat(second.best).isEqualTo(first.score)
        assertThat(store.best("m1")).isEqualTo(first.score)
    }
}
