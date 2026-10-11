package com.elevate.app.ui.overview

import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.analysis.BallTrack
import com.elevate.app.domain.analysis.WindowCoverage
import com.elevate.app.domain.analysis.WindowResultStore
import java.util.Locale

/** One stretch of the per-window coverage strip, in source ms. */
data class WindowSegment(
    /** `window_seq`, null for a reported stretch whose result is not on this phone yet. */
    val windowSeq: Int?,
    val startMs: Long,
    val endMs: Long,
    val status: Status,
    /** Distinct frames with an observed shuttle row in this window (read windows only). */
    val shuttleFrames: Int? = null,
) {
    enum class Status {
        /** Result file downloaded: its rows and hit candidates are drawn. */
        READ,
        /** `window.completed` arrived but its result is not on this phone (download pending / not served). */
        REPORTED,
        /** `window.failed`: nothing for this stretch. */
        FAILED,
    }
}

/** One rally candidate row (grouped from engine hit candidates; never a confirmed rally). */
data class LiveRallyRow(
    /** Same id as the timeline's span: `HIT_RALLY_ID_PREFIX` + 1-based index. */
    val rallyId: String,
    val startMs: Long,
    val endMs: Long,
    val hitCount: Int,
    /** "Rally candidate 3 · 0:21.8–0:22.8 · 6 hit candidates". */
    val label: String,
)

/**
 * The overview built from the live windows while `match.v1` is missing (engine has not finalized,
 * or the route is not served). Everything is engine output, labelled as candidates; every number
 * has its denominator, and an unknown denominator is left out, never estimated.
 */
data class LiveOverviewState(
    /** Every stretch of the source is inside a completed window (see [WindowCoverage]). */
    val windowsComplete: Boolean,
    /** Distinct frames with an observed shuttle row, over the downloaded windows. */
    val shuttleFrames: Int,
    /** Real source frames inside the downloaded windows; null when frame times are not probed. */
    val framesInWindows: Int?,
    /** Downloaded coverage, µs. */
    val readUs: Long,
    /** "shuttle seen in 101 of 767 frames", or "… in 101 frames · 25.6 s analysed"; null before any window is read. */
    val shuttleLine: String?,
    /** "5 of 5 windows read · 25.6 of 25.6 s" (+ " · 1 failed"). */
    val coverageLine: String,
    val segments: List<WindowSegment>,
    /** Strip length: the clip when known, else the furthest covered point. */
    val timelineMs: Long,
    val rallies: List<LiveRallyRow>,
    /** One plain line on what is not here yet; null when nothing is pending. */
    val pendingLine: String?,
    val engineVersion: String?,
)

object LiveOverview {

    fun derive(
        analysis: AnalysisUiState,
        ball: BallTrack,
        windows: List<WindowResultStore.Window>,
        downloadedCoverageUs: List<LongRange>,
        hitTimeline: HitTimelineState,
        sourceFrameTimesUs: LongArray?,
    ): LiveOverviewState {
        val readCoverage = downloadedCoverageUs
        val readUs = AnalysisUiState.coveredUs(readCoverage)
        val observed = observedFramesIn(ball, readCoverage)
        val frames = sourceFrameTimesUs?.takeIf { it.isNotEmpty() }?.let { ft -> readCoverage.sumOf { countIn(ft, it.first, it.last) } }
        val complete = WindowCoverage.isComplete(analysis.coverageUs, analysis.totalUs)
        val segments = segments(analysis, windows, readCoverage)
        val timeline = maxOf(
            analysis.totalUs?.let { it / 1000 } ?: 0,
            segments.maxOfOrNull { it.endMs } ?: 0,
            hitTimeline.timelineMs,
        )
        return LiveOverviewState(
            windowsComplete = complete,
            shuttleFrames = observed,
            framesInWindows = frames,
            readUs = readUs,
            shuttleLine = if (windows.isEmpty()) null else shuttleLine(observed, frames, readUs),
            coverageLine = coverageLine(analysis, windows.size),
            segments = segments,
            timelineMs = timeline,
            rallies = rallyRows(hitTimeline),
            pendingLine = pendingLine(analysis, complete),
            engineVersion = analysis.engineVersion,
        )
    }

    /**
     * Exact frame denominator only when the real frame times are known (and consistent: more seen
     * frames than real frames means the times do not line up, so no denominator); else the seconds read.
     */
    fun shuttleLine(observed: Int, frames: Int?, readUs: Long): String =
        if (frames != null && frames > 0 && observed <= frames) "shuttle seen in $observed of $frames frames"
        else "shuttle seen in $observed frame${if (observed == 1) "" else "s"} · ${seconds(readUs)} s analysed"

    /** "5 of 5 windows read · 25.6 of 25.6 s"; the window total is the engine's expected count when given. */
    fun coverageLine(a: AnalysisUiState, windowsRead: Int): String {
        val total = a.windowsExpected ?: (a.windowsDone + a.windowsFailed)
        val of = maxOf(total, windowsRead)
        val windows = "$windowsRead of $of window${if (of == 1) "" else "s"} read"
        val time = a.totalUs?.takeIf { it > 0 }?.let { "${seconds(a.coveredUs)} of ${seconds(it)} s" } ?: "${seconds(a.coveredUs)} s · length unknown"
        val failed = a.windowsFailed.takeIf { it > 0 }?.let { "$it failed" }
        return listOfNotNull(windows, time, failed).joinToString(" · ")
    }

    fun pendingLine(a: AnalysisUiState, complete: Boolean): String? = when {
        a.state == AnalysisState.COMPLETED -> "Match summary (score, landings) not served yet"
        a.state == AnalysisState.FAILED -> "Analysis stopped · no match summary"
        complete -> AnalysingFlow.SUMMARY_PENDING
        else -> "Engine still analysing · ${AnalysingFlow.SUMMARY_PENDING.replaceFirstChar { it.lowercase(Locale.ROOT) }}"
    }

    fun rallyRows(t: HitTimelineState): List<LiveRallyRow> = t.rallies.mapIndexed { i, r ->
        LiveRallyRow(
            rallyId = "$HIT_RALLY_ID_PREFIX${i + 1}",
            startMs = r.startMs,
            endMs = r.endMs,
            hitCount = r.hitCount,
            label = "Rally candidate ${i + 1} · ${clock(r.startMs)}–${clock(r.endMs)} · ${r.hitCount} hit candidate${if (r.hitCount == 1) "" else "s"}",
        )
    }

    /**
     * Read windows (by `window_seq`), then reported-but-unread stretches (event coverage minus the
     * downloaded coverage), then failed windows with a range. Sorted by start.
     */
    fun segments(a: AnalysisUiState, windows: List<WindowResultStore.Window>, readCoverage: List<LongRange>): List<WindowSegment> {
        val out = ArrayList<WindowSegment>()
        for (w in windows) {
            val seen = w.rows.asSequence().filter { it.state == BallTrack.OBSERVED }.map { it.tUs }.distinct().count()
            out += WindowSegment(w.windowSeq, w.emitUs.first / 1000, ceilMs(w.emitUs.last), WindowSegment.Status.READ, seen)
        }
        for (r in subtract(a.coverageUs, readCoverage)) {
            out += WindowSegment(null, r.first / 1000, ceilMs(r.last), WindowSegment.Status.REPORTED)
        }
        for (f in a.windowFailures) {
            val r = f.emitRangeUs ?: continue
            out += WindowSegment(f.windowSeq, r.first / 1000, ceilMs(r.last), WindowSegment.Status.FAILED)
        }
        return out.sortedWith(compareBy({ it.startMs }, { it.status.ordinal }))
    }

    /** Parts of [a] (merged, inclusive) not inside [b]; stretches of 1 µs or less are dropped. */
    internal fun subtract(a: List<LongRange>, b: List<LongRange>): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (r in a) {
            var start = r.first
            for (c in b.sortedBy { it.first }) {
                if (c.last <= start || c.first >= r.last) continue
                if (c.first > start) out += start..c.first
                start = maxOf(start, c.last)
            }
            if (start < r.last) out += start..r.last
        }
        return out.filter { it.last - it.first > 1 }
    }

    private fun observedFramesIn(ball: BallTrack, coverage: List<LongRange>): Int {
        var n = 0
        var last = Long.MIN_VALUE
        for (i in 0 until ball.size) {
            val t = ball.tUs[i]
            if (ball.state[i] != BallTrack.OBSERVED || t == last) continue
            if (coverage.any { t in it }) { n++; last = t }
        }
        return n
    }

    private fun countIn(sorted: LongArray, fromUs: Long, toUs: Long): Int {
        fun lb(key: Long): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] < key) lo = m + 1 else hi = m }
            return lo
        }
        return lb(toUs + 1) - lb(fromUs)
    }

    private fun ceilMs(us: Long) = (us + 999) / 1000
    private fun seconds(us: Long) = String.format(Locale.ROOT, "%.1f", us / 1e6)
}
