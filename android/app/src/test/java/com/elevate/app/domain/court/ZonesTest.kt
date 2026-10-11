package com.elevate.app.domain.court

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZonesTest {
    private val singles = ZoneGrid.forTemplate("badminton_singles")!!
    private val doubles = ZoneGrid.forTemplate("badminton_doubles")!!

    @Test fun `near hitter - columns run left to right in camera x`() {
        assertThat(singles.hit(0.2, 13.0, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.BACK, 1)))
        assertThat(singles.hit(5.0, 13.0, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.BACK, 3)))
    }

    @Test fun `far hitter faces the camera so their left is large x`() {
        assertThat(singles.hit(0.2, 0.5, CourtSide.FAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.BACK, 3)))
        assertThat(singles.hit(5.0, 0.5, CourtSide.FAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.BACK, 1)))
    }

    @Test fun `rows follow contracts section 9 and lines are in`() {
        assertThat(singles.hit(2.6, 6.7 + 1.98, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.FRONT, 2)))
        assertThat(singles.hit(2.6, 6.7 + 2.0, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.MIDDLE, 2)))
        assertThat(singles.hit(2.6, 13.4 - 1.5, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.BACK, 2)))
        assertThat(singles.hit(2.6, 13.4, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.BACK, 2)))
        assertThat(singles.hit(5.18, 10.0, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.MIDDLE, 3)))
    }

    @Test fun `out and own half`() {
        assertThat(singles.hit(5.19, 10.0, CourtSide.NEAR)).isEqualTo(ZoneHit.Out)
        assertThat(singles.hit(-0.01, 10.0, CourtSide.NEAR)).isEqualTo(ZoneHit.Out)
        assertThat(singles.hit(2.6, 13.41, CourtSide.NEAR)).isEqualTo(ZoneHit.Out)
        assertThat(singles.hit(2.6, 3.0, CourtSide.NEAR)).isEqualTo(ZoneHit.OwnHalf)
        assertThat(singles.hit(2.6, 6.7, CourtSide.NEAR)).isEqualTo(ZoneHit.OwnHalf)
        // Doubles is wider: 5.5 m is in.
        assertThat(doubles.hit(5.5, 10.0, CourtSide.NEAR)).isEqualTo(ZoneHit.In(ZoneId(ZoneRow.MIDDLE, 3)))
    }

    @Test fun `every zone centre maps back to its zone from both ends`() {
        for (grid in listOf(singles, doubles)) for (side in CourtSide.entries) for (z in ZoneId.ALL) {
            val (x, y) = grid.centre(z, side)
            assertThat(grid.hit(x, y, side)).isEqualTo(ZoneHit.In(z))
        }
    }

    @Test fun `defender behind the baseline is clamped into the back row`() {
        assertThat(singles.cellOfDefender(2.6, 14.0, CourtSide.NEAR)).isEqualTo(ZoneId(ZoneRow.BACK, 2))
        assertThat(singles.cellOfDefender(2.6, 3.0, CourtSide.NEAR)).isNull()
    }

    @Test fun `ids parse and adjacency is chebyshev`() {
        assertThat(ZoneId.parse("M2")).isEqualTo(ZoneId(ZoneRow.MIDDLE, 2))
        assertThat(ZoneId.parse("X2")).isNull()
        assertThat(ZoneId.parse("F4")).isNull()
        assertThat(ZoneId.ALL.map { it.id }).containsExactly("F1", "F2", "F3", "M1", "M2", "M3", "B1", "B2", "B3").inOrder()
        assertThat(ZoneId.parse("F1")!!.cellsTo(ZoneId.parse("M2")!!)).isEqualTo(1)
        assertThat(ZoneId.parse("F1")!!.cellsTo(ZoneId.parse("B1")!!)).isEqualTo(2)
    }

    @Test fun `no grid for unvalidated sports`() {
        assertThat(ZoneGrid.forTemplate("tennis_singles")).isNull()
    }
}
