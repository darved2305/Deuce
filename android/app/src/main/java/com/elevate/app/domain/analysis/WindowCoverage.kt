package com.elevate.app.domain.analysis

/**
 * Whether the engine's windows cover the whole source, from the merged `emit_range_us` of the
 * completed windows (contracts §3) and the real source length. Pure; no estimate is made: an
 * unknown length is never "complete".
 */
object WindowCoverage {

    /**
     * Largest uncovered stretch (at the clip edges or between windows) still counted as covered:
     * the engine's last emit end is rounded to its frame grid, so a clip of 25.633 s ends its last
     * window at 25.6 s. 100 ms is under 3 frames at 30 fps and never hides a whole window.
     */
    const val TOLERANCE_US = 100_000L

    /** Uncovered stretches of 0..[totalUs] longer than [toleranceUs]; empty = complete. */
    fun gapsUs(coverageUs: List<LongRange>, totalUs: Long, toleranceUs: Long = TOLERANCE_US): List<LongRange> {
        require(totalUs > 0) { "totalUs must be positive" }
        val out = ArrayList<LongRange>()
        var at = 0L
        for (r in coverageUs.sortedBy { it.first }) {
            if (r.first > at && r.first - at > toleranceUs) out += at..minOf(r.first, totalUs)
            at = maxOf(at, r.last)
            if (at >= totalUs) break
        }
        if (totalUs - at > toleranceUs) out += at..totalUs
        return out
    }

    /** True when every part of the source is inside a completed window (within [toleranceUs]). */
    fun isComplete(coverageUs: List<LongRange>, totalUs: Long?, toleranceUs: Long = TOLERANCE_US): Boolean =
        totalUs != null && totalUs > 0 && coverageUs.isNotEmpty() && gapsUs(coverageUs, totalUs, toleranceUs).isEmpty()

    /**
     * The analysing / overview reading: the engine is still running or `partial` (no finalize yet),
     * no window failed, and the completed windows cover the source. Then the match can be opened
     * from the windows; the match record (score, landings) is still pending.
     */
    fun readyFromWindows(a: AnalysisUiState?): Boolean =
        a != null &&
            (a.state == AnalysisState.PROCESSING || a.state == AnalysisState.PARTIAL) &&
            a.windowsFailed == 0 &&
            isComplete(a.coverageUs, a.totalUs)
}
