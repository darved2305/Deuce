package com.elevate.app.domain.shotrewind

import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShotRewindTest {
    private val t = "badminton_singles"

    @Test fun `moments are lost rallies with a trusted winner and the player's last shot`() {
        val rallies = listOf(
            RewindRally("r_1", winner = "p_opp", winnerState = "user"),
            RewindRally("r_2", winner = "p_me", winnerState = "user"),
            RewindRally("r_3", winner = "p_opp", winnerState = "candidate"),
            RewindRally("r_4", winner = "p_opp", winnerState = "observed"),
        )
        val shots = listOf(
            RewindShot("sh_a", "r_1", 0, "p_me", 1_000, 10),
            RewindShot("sh_b", "r_1", 1, "p_opp", 1_800, 10),
            RewindShot("sh_c", "r_1", 2, "p_me", 2_600, 10),
            RewindShot("sh_d", "r_2", 0, "p_me", 5_000, 10),
            RewindShot("sh_e", "r_3", 0, "p_me", 9_000, 10),
            RewindShot("sh_f", "r_4", 0, "p_opp", 12_000, 10),
        )
        assertThat(ShotRewind.moments("p_me", rallies, shots).map { it.shot.shotId }).containsExactly("sh_c")
    }

    @Test fun `rewind lands on a real frame before contact and its uncertainty`() {
        val frames = LongArray(200) { it * 20_000L }
        val s = RewindShot("sh", "r", 0, "p_me", contactMs = 2_000, contactSigmaMs = 15)
        assertThat(ShotRewind.rewindFrameUs(s, frames)).isEqualTo(1_700_000L)
        assertThat(ShotRewind.rewindFrameUs(s, frames, leadMs = 0)).isEqualTo(1_980_000L)
        assertThat(ShotRewind.rewindFrameUs(s.copy(contactMs = 5), frames)).isNull()
    }

    @Test fun `tapped target legality`() {
        assertThat(ShotRewind.decide(t, CourtSide.NEAR, 2.6, 3.0, emptyList())).isEqualTo(Decision.OwnHalf)
        assertThat(ShotRewind.decide(t, CourtSide.NEAR, 5.5, 10.0, emptyList())).isEqualTo(Decision.Out)
        assertThat(ShotRewind.decide("tennis_singles", CourtSide.NEAR, 2.6, 10.0, emptyList())).isEqualTo(Decision.NoCourt)
        assertThat(ShotRewind.decide(t, CourtSide.NEAR, 0.3, 13.0, emptyList()))
            .isEqualTo(Decision.Legal(ZoneId.parse("B1")!!, spacing = null, nearest = null))
    }

    @Test fun `spacing from the nearest trusted defender`() {
        val opp = Placed("p_opp", 2.6, 10.0, "observed") // M2 from a near hitter
        fun sp(zone: String, d: List<Placed>) = (ShotRewind.decideZone(t, CourtSide.NEAR, ZoneId.parse(zone)!!, d) as Decision.Legal).spacing
        assertThat(sp("M2", listOf(opp))).isEqualTo(Spacing.COVERED)
        assertThat(sp("B1", listOf(opp))).isEqualTo(Spacing.ADJACENT)
        assertThat(sp("F1", listOf(Placed("p_opp", 4.9, 13.2, "observed")))).isEqualTo(Spacing.SEPARATED)
        // Untrusted positions are never used.
        assertThat(sp("M2", listOf(opp.copy(state = "candidate")))).isNull()
        // Doubles: the closer of two defenders decides.
        val two = listOf(Placed("p_a", 4.9, 13.2, "observed"), Placed("p_b", 0.3, 7.5, "user"))
        val d = ShotRewind.decideZone(t, CourtSide.NEAR, ZoneId.parse("F1")!!, two) as Decision.Legal
        assertThat(d.spacing).isEqualTo(Spacing.COVERED)
        assertThat(d.nearest?.playerId).isEqualTo("p_b")
    }
}
