package com.elevate.app.domain.tactics

import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Hand-built inputs exercise the rules; nothing here is shown in the app. */
class TacticsTest {

    private val t = "badminton_singles"

    /** A near player "a" (feet y≈2) and a far player "b" (y≈11); boxes in a 1920×1080 image. */
    private fun rows(tMs: Long, ax: Double = 2.5, ay: Double = 2.0, bx: Double = 2.5, by: Double = 11.0) = listOf(
        PlayerRow(tMs, "a", 900.0, 700.0, 1000.0, 1000.0, ax, ay),
        PlayerRow(tMs, "b", 900.0, 300.0, 960.0, 450.0, bx, by),
    )

    private fun hitA(id: String, tMs: Long) = TacHit(id, tMs, 950.0 to 650.0)
    private fun hitB(id: String, tMs: Long) = TacHit(id, tMs, 930.0 to 280.0)

    @Test fun hitterIsThePlayerWhoseBoxHoldsTheShuttle() {
        val byTrack = rows(1000).groupBy { it.trackId }
        assertThat(Tactics.hitterOf(hitA("h", 1000), listOf("a", "b"), byTrack)).isEqualTo("a")
        assertThat(Tactics.hitterOf(hitB("h", 1000), listOf("a", "b"), byTrack)).isEqualTo("b")
        assertThat(Tactics.hitterOf(TacHit("h", 1000, null), listOf("a", "b"), byTrack)).isNull()
        // No row near the contact time: no guess.
        assertThat(Tactics.hitterOf(hitA("h", 5000), listOf("a", "b"), byTrack)).isNull()
    }

    @Test fun ownCellIsSeenFromTheHittersOwnView() {
        val grid = com.elevate.app.domain.court.ZoneGrid.forTemplate(t)!!
        // Near player deep on their left (small x, small y): back row, column 1.
        assertThat(Tactics.ownCell(grid, Pos(0.5, 0.5), CourtSide.NEAR)).isEqualTo(ZoneId(ZoneRow.BACK, 1))
        // Far player at their left: they face -y, so their left is large x.
        assertThat(Tactics.ownCell(grid, Pos(4.7, 12.9), CourtSide.FAR)).isEqualTo(ZoneId(ZoneRow.BACK, 1))
        assertThat(Tactics.ownCell(grid, Pos(2.6, 6.0), CourtSide.NEAR).row).isEqualTo(ZoneRow.FRONT)
    }

    @Test fun shotDestinationIsTheReceiversFeetAtTheirContact() {
        val players = rows(1000) + rows(2000, bx = 0.5, by = 12.5)
        val s = Tactics.compute(t, listOf(hitA("1", 1000), hitB("2", 2000)), emptyList(), players, "a")
        val first = s.shots.first { it.hit.id == "1" }
        assertThat(first.hitter).isEqualTo("a")
        assertThat(first.destKind).isEqualTo(DestKind.INTERCEPT)
        assertThat(first.dest).isEqualTo(Pos(0.5, 12.5))
        assertThat(first.destZone).isEqualTo(ZoneId(ZoneRow.BACK, 1))
    }

    @Test fun repeatedChoiceFromOneCellIsAPattern() {
        // "a" hits 5 times from the same spot; 4 go deep left of "b", 1 short.
        val hits = ArrayList<TacHit>()
        val players = ArrayList<PlayerRow>()
        var tm = 0L
        repeat(5) { i ->
            hits += hitA("a$i", tm); players += rows(tm)
            val deep = i < 4
            hits += hitB("b$i", tm + 900); players += rows(tm + 900, bx = if (deep) 0.5 else 2.6, by = if (deep) 12.6 else 7.2)
            tm += 6_000
        }
        val s = Tactics.compute(t, hits, emptyList(), players, "a")
        val p = s.you!!.patterns.single()
        assertThat(p.count).isEqualTo(4)
        assertThat(p.total).isEqualTo(5)
        assertThat(p.to).isEqualTo(ZoneId(ZoneRow.BACK, 1))
        assertThat(s.you!!.usualTarget).isEqualTo(4)
    }

    @Test fun lessThanFourShotsFromACellIsNoPattern() {
        val players = rows(0) + rows(900, bx = 0.5, by = 12.6)
        val s = Tactics.compute(t, listOf(hitA("1", 0), hitB("2", 900)), emptyList(), players, "a")
        assertThat(s.you!!.patterns).isEmpty()
    }

    @Test fun ghostNeedsEnoughOpponentShotsAndMeasuresHowFarYouWere() {
        // b always sends it to a's back right; a stays at the front: far from the ghost.
        val hits = ArrayList<TacHit>()
        val players = ArrayList<PlayerRow>()
        var tm = 0L
        repeat(5) { i ->
            hits += hitB("b$i", tm); players += rows(tm, ax = 2.6, ay = 5.5)
            hits += hitA("a$i", tm + 800); players += rows(tm + 800, ax = 4.8, ay = 0.6)
            tm += 6_000
        }
        val s = Tactics.compute(t, hits, emptyList(), players, "a")
        assertThat(s.ghosts).isNotEmpty()
        val g = s.ghosts.first()
        assertThat(g.ghost.yM).isLessThan(1.5)
        assertThat(g.offM).isGreaterThan(2.0)
    }

    @Test fun rallyLostWhenYourLastShotLandsOut() {
        val players = rows(0) + rows(800)
        val hits = listOf(hitB("1", 0), hitA("2", 800))
        val out = TacLanding("l", 1500, 2.5, 14.5, "candidate") // past the far baseline
        val s = Tactics.compute(t, hits, listOf(out), players, "a")
        assertThat(s.ralliesLostEstimated).isEqualTo(1)
        val inside = TacLanding("l", 1500, 2.5, 10.0, "candidate")
        assertThat(Tactics.compute(t, hits, listOf(inside), players, "a").ralliesLostEstimated).isEqualTo(0)
    }

    @Test fun landingOnTheLineIsUnknownNotAGuess() {
        val players = rows(0) + rows(800)
        val hits = listOf(hitB("1", 0), hitA("2", 800))
        val line = TacLanding("l", 1500, 2.5, 13.35, "candidate")
        assertThat(Tactics.compute(t, hits, listOf(line), players, "a").ralliesLostEstimated).isEqualTo(0)
    }

    @Test fun withoutPlayerRowsNothingIsInvented() {
        val s = Tactics.compute(t, listOf(hitA("1", 0), hitB("2", 900)), emptyList(), emptyList(), "a")
        assertThat(s.shots).isEmpty()
        assertThat(s.ghosts).isEmpty()
        assertThat(s.limits.joinToString()).contains("No player positions")
    }
}
