package com.elevate.app.domain.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitRalliesTest {

    private fun hit(tMs: Long) = HitCandidate(tMs, 9, "t_1", 1.0 to 2.0, listOf("trajectory"), "candidate")
    private fun group(vararg t: Long) = HitRallies.group(t.map(::hit))

    @Test fun `no hits give no rally candidates`() {
        assertThat(HitRallies.group(emptyList())).isEmpty()
    }

    @Test fun `a rally candidate needs at least two hits`() {
        assertThat(group(1_000)).isEmpty()
        assertThat(group(1_000, 2_000)).containsExactly(RallyCandidate(1_000, 2_000, 2))
    }

    @Test fun `a gap longer than 2500 ms ends a rally, exactly 2500 ms does not`() {
        assertThat(HitRallies.MAX_GAP_MS).isEqualTo(2_500L)
        assertThat(group(0, 1_000, 3_500)).containsExactly(RallyCandidate(0, 3_500, 3))
        assertThat(group(0, 1_000, 3_501)).containsExactly(RallyCandidate(0, 1_000, 2))
    }

    @Test fun `separate rallies, with lone hits between them dropped`() {
        val r = group(1_000, 2_000, 3_000, 10_000, 20_000, 21_000, 22_500, 40_000)
        assertThat(r).containsExactly(RallyCandidate(1_000, 3_000, 3), RallyCandidate(20_000, 22_500, 3)).inOrder()
    }

    @Test fun `unsorted input is sorted first`() {
        val r = group(22_500, 2_000, 20_000, 1_000, 21_000, 3_000)
        assertThat(r).containsExactly(RallyCandidate(1_000, 3_000, 3), RallyCandidate(20_000, 22_500, 3)).inOrder()
    }

    @Test fun `hits at the same time count as hits`() {
        assertThat(group(5_000, 5_000)).containsExactly(RallyCandidate(5_000, 5_000, 2))
    }

    @Test fun `a custom gap is honoured`() {
        val r = HitRallies.group(listOf(0L, 600L, 1_200L).map(::hit), maxGapMs = 500)
        assertThat(r).isEmpty()
    }
}
