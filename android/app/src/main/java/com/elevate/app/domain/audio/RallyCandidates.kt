package com.elevate.app.domain.audio

/**
 * A stretch of the clip where sound onsets cluster like an exchange of shots: the
 * `rally_candidates` item of contracts §2 (`{start_ms, end_ms, onsets, state: "candidate"}`).
 * Provisional by definition: it is never a confirmed rally and says nothing about who won.
 */
data class RallyCandidate(val startMs: Long, val endMs: Long, val onsets: Int) {
    /** Provenance state (contracts §0); sound alone never produces anything stronger. */
    val state: String get() = STATE

    companion object {
        const val STATE = "candidate"
    }
}

/**
 * Parameters of [RallyCandidates.group]. Defaults come from badminton timing, not from tuning on
 * real matches (accuracy is UNVERIFIED until measured against Person 4's labels):
 *  - shots in a rally are roughly 0.5–2 s apart, so a silence longer than [maxGapMs] = 2.5 s
 *    ends a candidate;
 *  - a serve plus at least two returns is [minOnsets] = 3 onsets; fewer is too easy to get from
 *    a dropped racket, a clap or a footstep;
 *  - [padBeforeMs] shows the serve set-up, [padAfterMs] lets the last shuttle land.
 */
data class RallyParams(
    val maxGapMs: Long = 2_500,
    val minOnsets: Int = 3,
    val padBeforeMs: Long = 500,
    val padAfterMs: Long = 1_000,
)

object RallyCandidates {

    /**
     * Groups [onsets] (any order) into candidates: consecutive onsets at most
     * [RallyParams.maxGapMs] apart form one group; groups with at least [RallyParams.minOnsets]
     * onsets become candidates, padded and clamped to `[0, durationMs]` (when known) and never
     * overlapping the previous candidate.
     */
    fun group(onsets: List<Onset>, durationMs: Long? = null, params: RallyParams = RallyParams()): List<RallyCandidate> {
        require(params.maxGapMs >= 0 && params.minOnsets >= 1 && params.padBeforeMs >= 0 && params.padAfterMs >= 0) { "bad params" }
        if (onsets.isEmpty()) return emptyList()
        val times = onsets.map { it.tMs }.sorted()
        val out = ArrayList<RallyCandidate>()
        var first = times[0]
        var last = times[0]
        var count = 1
        fun close() {
            if (count < params.minOnsets) return
            var start = (first - params.padBeforeMs).coerceAtLeast(0)
            var end = last + params.padAfterMs
            if (durationMs != null) {
                end = end.coerceAtMost(durationMs)
                start = start.coerceAtMost(end)
            }
            out.lastOrNull()?.let { if (start < it.endMs) start = it.endMs }
            out += RallyCandidate(start, end, count)
        }
        for (i in 1 until times.size) {
            val t = times[i]
            if (t - last <= params.maxGapMs) {
                last = t; count++
            } else {
                close()
                first = t; last = t; count = 1
            }
        }
        close()
        return out
    }
}
