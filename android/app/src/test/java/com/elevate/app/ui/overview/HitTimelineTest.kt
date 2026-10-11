package com.elevate.app.ui.overview

import com.elevate.app.domain.analysis.HitCandidate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitTimelineTest {

    private fun hit(tMs: Long) = HitCandidate(tMs, 9, null, null, listOf("trajectory"), "candidate")

    @Test fun `caption names counts, denominator and basis`() {
        val s = OverviewDerivation.hitTimeline(
            hits = listOf(hit(30_000), hit(1_000), hit(2_000), hit(3_000)),
            windowsRead = 4, windowsReported = 4, windowsExpected = 5,
            coverageUs = listOf(0L..24_000_000L), sourceDurationMs = 60_000,
        )
        assertThat(s.rallies.map { it.hitCount }).containsExactly(3)
        assertThat(HitTimelineText.caption(s))
            .isEqualTo("4 hit candidates · 1 rally candidate · from 4 of 5 windows analysed (engine, candidates)")
        assertThat(s.timelineMs).isEqualTo(60_000)
        assertThat(s.hits.map { it.tMs }).isInOrder()
    }

    @Test fun `the engine's expected total wins over windows reported so far`() {
        val s = OverviewDerivation.hitTimeline(listOf(hit(1)), 2, 3, 10, emptyList(), null)
        assertThat(HitTimelineText.windows(s)).isEqualTo("2 of 10 windows")
        assertThat(s.totalIsExpected).isTrue()
        assertThat(HitTimelineText.detail(s)).doesNotContain("so far")
    }

    @Test fun `windows reported never fall below windows read`() {
        val s = OverviewDerivation.hitTimeline(listOf(hit(1)), windowsRead = 3, windowsReported = 1, windowsExpected = null, coverageUs = emptyList(), sourceDurationMs = null)
        assertThat(HitTimelineText.windows(s)).isEqualTo("3 of 3 windows")
    }

    @Test fun `timeline falls back to coverage or the last hit when the clip length is unknown`() {
        val s = OverviewDerivation.hitTimeline(listOf(hit(5_000)), 1, 1, null, listOf(0L..6_000_000L), null)
        assertThat(s.timelineMs).isEqualTo(6_000)
    }

    @Test fun `no hits says so and keeps the denominator`() {
        val s = OverviewDerivation.hitTimeline(emptyList(), 2, 3, null, emptyList(), null)
        assertThat(HitTimelineText.EMPTY).isEqualTo("No hit candidates yet")
        assertThat(HitTimelineText.emptyDetail(s)).isEqualTo("2 of 3 windows analysed so far, none with a hit candidate")
        assertThat(HitTimelineText.emptyDetail(HitTimelineState.EMPTY)).isNull()
    }
}
