package com.elevate.app.playback

import com.elevate.app.domain.analysis.BallTrack
import com.elevate.app.domain.model.ClipIdentity
import com.elevate.app.domain.model.Provenance
import com.elevate.app.domain.model.TrailTrack

/**
 * Turns merged engine ball rows ([BallTrack], from `window_result.v1`) into the [TrailTrack]s the
 * existing [TrailOverlay] draws, without changing what the overlay means:
 *
 *  - [EngineTrail.observed] holds every engine row; only `observed` rows have coordinates, every
 *    other row is NaN there. TrailOverlay draws NaN as a gap, so today the overlay shows exactly
 *    the observed shuttle and never draws an inferred or candidate point as if it were seen.
 *  - [EngineTrail.inferred] / [EngineTrail.candidate] hold only those rows (others NaN), for a
 *    future overlay pass that draws them differently (see android/ANALYSIS_OVERVIEW.md: hollow
 *    rings, dashed joins, never joined to observed points by a solid line).
 *
 * Provenance is the engine's (`engine_version` from the events, never "hand-labelled"), so
 * `Provenance.isHandLabelled` is false and the replay screen says "Engine <version>".
 *
 * Times: ball rows are integer ms; `t_us = t_ms * 1000`. When the real frame times of the played
 * file are known ([sourceFrameTimesUs], sorted), each row is snapped to the frame it names: the
 * nearest frame within [TimeMapping.matchToleranceUs] of those frames. A row that matches no frame
 * is dropped and counted ([EngineTrail.unmatchedRows]), never moved further.
 */
object WindowTrailAdapter {

    class EngineTrail(
        val observed: TrailTrack,
        val inferred: TrailTrack,
        val candidate: TrailTrack,
        val observedRows: Int,
        val inferredRows: Int,
        val candidateRows: Int,
        /** Rows with state `user` from the engine are not engine output: excluded and counted. */
        val userRowsExcluded: Int,
        val unmatchedRows: Int,
    )

    /**
     * Returns null when there are no rows, or no coverage to bound them. [coverageUs] = merged emit
     * ranges of the downloaded windows; the track's range is their span, and rows outside every
     * covered range are dropped (they cannot be credited to a window).
     */
    fun toTrail(
        ball: BallTrack,
        clip: ClipIdentity,
        engineVersion: String,
        coverageUs: List<LongRange>,
        sourceFrameTimesUs: LongArray? = null,
    ): EngineTrail? {
        require(engineVersion.isNotBlank() && engineVersion != "hand-labelled") { "engine output needs the engine's version" }
        if (ball.size == 0 || coverageUs.isEmpty()) return null

        val frames = sourceFrameTimesUs
        val tol = frames?.let { TimeMapping.matchToleranceUs(it) } ?: 0L
        val n = ball.size
        val t = LongArray(n)
        val keep = BooleanArray(n)
        var unmatched = 0
        var userExcluded = 0
        var last = Long.MIN_VALUE
        var lastIdx = -1
        for (i in 0 until n) {
            if (ball.state[i] == BallTrack.USER) { userExcluded++; continue }
            var tu = ball.tUs[i]
            if (frames != null) {
                val f = nearestFrame(frames, tu, tol)
                if (f < 0) { unmatched++; continue }
                tu = frames[f]
            }
            if (coverageUs.none { tu in it }) { unmatched++; continue }
            // Two rows on one frame (e.g. several shuttle candidates): keep the higher confidence one
            // (null confidence ranks lowest) and count the other as unmatched.
            if (tu == last) {
                unmatched++
                val prev = lastIdx
                if (prev >= 0 && rank(ball.conf[i]) > rank(ball.conf[prev])) {
                    keep[prev] = false
                    t[i] = tu
                    keep[i] = true
                    lastIdx = i
                }
                continue
            }
            t[i] = tu
            keep[i] = true
            last = tu
            lastIdx = i
        }
        val idx = (0 until n).filter { keep[it] }
        if (idx.isEmpty()) return null
        val tUs = LongArray(idx.size) { t[idx[it]] }
        val start = coverageUs.first().first
        val end = coverageUs.last().last
        val framesInRange = frames?.let { countIn(it, start, end) } ?: idx.size

        fun track(code: Byte): TrailTrack = TrailTrack(
            clip = clip,
            provenance = Provenance(BallTrack.STATES[code.toInt()], engineVersion, labelledBy = null, tool = "window_result.v1"),
            rangeStartUs = start,
            rangeEndUs = end,
            framesInRange = maxOf(framesInRange, idx.size),
            tUs = tUs,
            x = FloatArray(idx.size) { val i = idx[it]; if (ball.state[i] == code) ball.x[i] else Float.NaN },
            y = FloatArray(idx.size) { val i = idx[it]; if (ball.state[i] == code) ball.y[i] else Float.NaN },
        )

        return EngineTrail(
            observed = track(BallTrack.OBSERVED),
            inferred = track(BallTrack.INFERRED),
            candidate = track(BallTrack.CANDIDATE),
            observedRows = idx.count { ball.state[it] == BallTrack.OBSERVED },
            inferredRows = idx.count { ball.state[it] == BallTrack.INFERRED },
            candidateRows = idx.count { ball.state[it] == BallTrack.CANDIDATE },
            userRowsExcluded = userExcluded,
            unmatchedRows = unmatched,
        )
    }

    /** Index of the frame nearest [us] within ±[tol], or -1. */
    private fun rank(conf: Float) = if (conf.isNaN()) -1f else conf

    internal fun nearestFrame(frames: LongArray, us: Long, tol: Long): Int {
        if (frames.isEmpty()) return -1
        val i = TimeMapping.lowerBound(frames, us)
        var best = -1
        var bestD = Long.MAX_VALUE
        for (j in intArrayOf(i - 1, i)) {
            if (j < 0 || j >= frames.size) continue
            val d = kotlin.math.abs(frames[j] - us)
            if (d <= tol && d < bestD) { best = j; bestD = d }
        }
        return best
    }

    private fun countIn(frames: LongArray, from: Long, to: Long): Int =
        TimeMapping.lowerBound(frames, to + 1) - TimeMapping.lowerBound(frames, from)
}
