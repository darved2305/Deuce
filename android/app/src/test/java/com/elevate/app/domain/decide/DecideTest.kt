package com.elevate.app.domain.decide

import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.shotrewind.Decision
import com.elevate.app.domain.shotrewind.Placed
import com.elevate.app.domain.shotrewind.Spacing
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DecideTest {
    private val tpl = "badminton_singles"
    private val w = 5.18

    private fun freeze(defenders: List<Placed>, real: RealShot? = null, id: String = "h") = Freeze(
        hitEventId = id, contactMs = 1000, freezeUs = 700_000, template = tpl, side = CourtSide.NEAR,
        hitterXyM = 2.5 to 1.0, yours = true, defenders = defenders, real = real,
    )

    private fun defender(x: Double, y: Double) = Placed("t_far", x, y, "observed")

    @Test fun `lines count as in and just past them is out`() {
        val f = freeze(emptyList())
        listOf(0.0 to 13.4, w to 13.4, 0.0 to 6.71, w to 6.71).forEach { (x, y) ->
            assertThat(Decide.evaluate(f, x, y).legal).isTrue()
        }
        listOf(-0.01 to 10.0, w + 0.01 to 10.0, 2.5 to 13.41).forEach { (x, y) ->
            assertThat(Decide.evaluate(f, x, y).decision).isEqualTo(Decision.Out)
        }
    }

    @Test fun `own half and the net line are not legal`() {
        val f = freeze(emptyList())
        assertThat(Decide.evaluate(f, 2.5, 3.0).decision).isEqualTo(Decision.OwnHalf)
        assertThat(Decide.evaluate(f, 2.5, 6.7).decision).isEqualTo(Decision.OwnHalf)
    }

    @Test fun `spacing verdicts follow the zone distance to the defender`() {
        // Defender mid-court, centre column.
        val f = freeze(listOf(defender(2.59, 11.0)))
        assertThat(Decide.evaluate(f, 2.59, 11.5).reach).isEqualTo(Reach.RIGHT_AT_THEM)
        assertThat(Decide.evaluate(f, 2.59, 7.5).reach).isEqualTo(Reach.STRETCH)
        // Front-left is one row and one column from mid-centre: adjacent.
        assertThat(Decide.evaluate(f, 0.5, 7.5).reach).isEqualTo(Reach.STRETCH)
        // Defender at the net: a back-court aim is 2 rows away.
        val net = freeze(listOf(defender(2.59, 7.5)))
        assertThat(Decide.evaluate(net, 4.5, 13.0).reach).isEqualTo(Reach.OPEN_COURT)
        assertThat((Decide.evaluate(net, 4.5, 13.0).decision as Decision.Legal).spacing).isEqualTo(Spacing.SEPARATED)
    }

    @Test fun `a missing defender gives no verdict and never a beat`() {
        val real = RealShot(2.0, 11.0, Decision.Legal(com.elevate.app.domain.court.ZoneId(com.elevate.app.domain.court.ZoneRow.MIDDLE, 2), Spacing.COVERED, null))
        val f = freeze(emptyList(), real)
        val o = Decide.evaluate(f, 2.5, 12.9)
        assertThat(o.legal).isTrue()
        assertThat(o.reach).isNull()
        assertThat(o.beatReal).isFalse()
        assertThat(f.defenderSeen).isFalse()
    }

    private fun legalReal(x: Double, y: Double, f: Freeze) =
        RealShot(x, y, com.elevate.app.domain.shotrewind.ShotRewind.decide(tpl, f.side, x, y, f.defenders))

    @Test fun `beating your own shot needs a more open choice by spacing`() {
        val d = listOf(defender(2.59, 11.0))
        val base = freeze(d)
        val f = base.copy(real = legalReal(2.59, 11.2, base)) // real: right at them
        assertThat(Decide.evaluate(f, 2.59, 7.5).beatReal).isTrue()        // stretch beats covered
        assertThat(Decide.evaluate(f, 2.59, 11.3).beatReal).isFalse()      // same zone: tie
        val f2 = base.copy(real = legalReal(0.5, 7.5, base))               // real: stretch
        assertThat(Decide.evaluate(f2, 2.59, 11.0).beatReal).isFalse()     // worse than real
        // Real shot out: no spacing, so nothing to beat.
        val f3 = base.copy(real = legalReal(9.0, 11.0, base))
        assertThat(Decide.evaluate(f3, 2.59, 7.5).beatReal).isFalse()
        // Illegal aim never beats.
        assertThat(Decide.evaluate(f, 9.0, 11.0).beatReal).isFalse()
    }

    @Test fun `end card stats count open court legal share and beats`() {
        val d = listOf(defender(2.59, 7.5)) // defender at the net
        val base = freeze(d)
        val covered = base.copy(real = legalReal(2.59, 7.6, base))
        val session = DecideSession(List(5) { covered.copy(hitEventId = "h$it") }, started = true)
            .commit(4.5, 13.0).next()   // open court, beat
            .commit(2.59, 7.6).next()   // right at them, tie
            .commit(9.0, 10.0).next()   // out
            .commit(0.5, 13.0).next()   // open court, beat
            .commit(2.59, 3.0).next()   // own half
        assertThat(session.finished).isTrue()
        val s = session.summary
        assertThat(s.decisions).isEqualTo(5)
        assertThat(s.legal).isEqualTo(3)
        assertThat(s.legalPct).isEqualTo(60)
        assertThat(s.openCourt).isEqualTo(2)
        assertThat(s.beats).isEqualTo(2)
        assertThat(s.comparable).isEqualTo(3)
    }

    @Test fun `a commit is locked once and next needs a commit`() {
        val f = freeze(emptyList())
        val s = DecideSession(listOf(f, f.copy(hitEventId = "b")), started = true)
        assertThat(s.next().index).isEqualTo(0)
        val c = s.commit(2.5, 10.0)
        assertThat(c.commit(0.0, 13.0).outcomes).hasSize(1)
        assertThat(c.commit(0.0, 13.0).outcome!!.aimXM).isEqualTo(2.5)
        assertThat(c.next().index).isEqualTo(1)
    }

    @Test fun `a run takes at most five moments and a new seed changes the pick`() {
        val all = List(12) { freeze(emptyList(), id = "h$it").copy(contactMs = 1000L * it) }
        val a = Decide.pick(all, 1)
        assertThat(a).hasSize(5)
        assertThat(a.map { it.contactMs }).isInOrder()
        assertThat(Decide.pick(all, 2).map { it.hitEventId }).isNotEqualTo(a.map { it.hitEventId })
        assertThat(Decide.pick(all.take(3), 1)).hasSize(3)
    }

    @Test fun `player feet are matched by nearest time per track within tolerance`() {
        val feet = PlayerFeet(listOf(
            FootSample(100, "a", 1.0, 2.0), FootSample(140, "a", 1.1, 2.0), FootSample(120, "b", 3.0, 9.0), FootSample(900, "b", 3.0, 9.0),
        ))
        val near = feet.nearAt(130, 50)
        assertThat(near.map { it.trackId }).containsExactly("a", "b").inOrder()
        assertThat(near.first { it.trackId == "a" }.tMs).isEqualTo(140)
        assertThat(feet.nearAt(500, 50)).isEmpty()
    }
}
