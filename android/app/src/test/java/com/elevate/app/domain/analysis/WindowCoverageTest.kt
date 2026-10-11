package com.elevate.app.domain.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Test vectors shaped like the live run in PHASE2_STATUS.md (5 windows, 0–25.6 s); not engine output. */
class WindowCoverageTest {

    private val fiveWindows = listOf(0L..6_000_000L, 6_000_000L..12_000_000L, 12_000_000L..18_000_000L, 18_000_000L..24_000_000L, 24_000_000L..25_600_000L)
    private val merged = listOf(0L..25_600_000L)

    private fun ui(state: AnalysisState?, coverage: List<LongRange>, totalUs: Long? = 25_600_000, failures: List<WindowFailure> = emptyList()) = AnalysisUiState(
        source = AnalysisSource.Live("a_1"),
        state = state,
        coverageUs = coverage,
        coveredUs = AnalysisUiState.coveredUs(coverage),
        totalUs = totalUs,
        windowsDone = 5,
        windowFailures = failures,
    )

    @Test fun `merged emit ranges equal to the source length are complete`() {
        assertThat(WindowCoverage.isComplete(merged, 25_600_000)).isTrue()
        // Unmerged but touching windows too.
        assertThat(WindowCoverage.isComplete(fiveWindows, 25_600_000)).isTrue()
    }

    @Test fun `a last window ending a frame short of the source end is still complete`() {
        assertThat(WindowCoverage.isComplete(merged, 25_633_000)).isTrue()
        assertThat(WindowCoverage.gapsUs(merged, 25_633_000)).isEmpty()
    }

    @Test fun `a missing window is a gap and never complete`() {
        val missing = fiveWindows.filterIndexed { i, _ -> i != 2 }
        assertThat(WindowCoverage.isComplete(missing, 25_600_000)).isFalse()
        assertThat(WindowCoverage.gapsUs(missing, 25_600_000)).containsExactly(12_000_000L..18_000_000L)
        // Tail not covered yet.
        assertThat(WindowCoverage.gapsUs(fiveWindows.take(4), 25_600_000)).containsExactly(24_000_000L..25_600_000L)
    }

    @Test fun `an unknown source length is never complete`() {
        assertThat(WindowCoverage.isComplete(merged, null)).isFalse()
        assertThat(WindowCoverage.isComplete(emptyList(), 25_600_000)).isFalse()
    }

    @Test fun `ready from the windows only while the engine is still partial and nothing failed`() {
        assertThat(WindowCoverage.readyFromWindows(ui(AnalysisState.PARTIAL, merged))).isTrue()
        assertThat(WindowCoverage.readyFromWindows(ui(AnalysisState.PROCESSING, merged))).isTrue()
        assertThat(WindowCoverage.readyFromWindows(ui(AnalysisState.PARTIAL, listOf(0L..18_000_000L)))).isFalse()
        assertThat(WindowCoverage.readyFromWindows(ui(AnalysisState.PARTIAL, merged, totalUs = null))).isFalse()
        val failed = WindowFailure(2, "decode error", 12_000_000L..18_000_000L, "2026-10-11T10:00:00Z")
        assertThat(WindowCoverage.readyFromWindows(ui(AnalysisState.PARTIAL, merged, failures = listOf(failed)))).isFalse()
        // Completed / finalizing have their own stage values.
        assertThat(WindowCoverage.readyFromWindows(ui(AnalysisState.COMPLETED, merged))).isFalse()
        assertThat(WindowCoverage.readyFromWindows(ui(AnalysisState.FINALIZING, merged))).isFalse()
        assertThat(WindowCoverage.readyFromWindows(null)).isFalse()
    }
}
