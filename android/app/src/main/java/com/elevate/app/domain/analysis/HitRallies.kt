package com.elevate.app.domain.analysis

/**
 * A hit the engine proposed (`window_result.v1` event with `type: "hit"`, contracts §5): where the
 * shuttle's trajectory turned, optionally confirmed by a nearby audio onset. Always a candidate:
 * it is never a confirmed shot and carries no player or rally.
 *
 * [trackId] names the observed shuttle trail, not a player. [confidence] is null because the
 * contract's hit event has no confidence field; it is never filled in from anywhere else.
 */
data class HitCandidate(
    val tMs: Long,
    /** Timing uncertainty (one sigma) in ms, as given by the engine. */
    val sigmaMs: Long,
    val trackId: String?,
    /** `[x, y]` in image space (source pixels), null when the event gave no usable pair. */
    val imageXy: Pair<Double, Double>?,
    /** Evidence behind the hit, verbatim: "trajectory", "audio". */
    val cues: List<String>,
    /** Provenance state, verbatim (contracts §0); the engine emits "candidate". */
    val state: String,
    val confidence: Double? = null,
)

/**
 * A stretch of the clip where hit candidates follow each other closely enough to look like an
 * exchange. Provisional: not a confirmed rally and says nothing about who won.
 * (Not the audio-based `domain.audio.RallyCandidate`, which is grouped from sound onsets.)
 */
data class RallyCandidate(val startMs: Long, val endMs: Long, val hitCount: Int)

object HitRallies {

    /**
     * Candidate rule, tuned only on dev clips (not validated on held-out matches): a silence longer
     * than this between two consecutive hit candidates ends a rally candidate. Badminton shots are
     * roughly 0.5 to 2 s apart; this matches the sound-based grouping in `RallyParams.maxGapMs`.
     */
    const val MAX_GAP_MS = 2_500L

    /** A single hit is not an exchange: a rally candidate needs at least this many hits. */
    const val MIN_HITS = 2

    /**
     * Groups [hits] (any order) into rally candidates: consecutive hits at most [maxGapMs] apart
     * (a gap longer than that ends the group) form one group; groups with at least [MIN_HITS]
     * hits become a candidate spanning first to last hit. Lone hits belong to no rally candidate.
     */
    fun group(hits: List<HitCandidate>, maxGapMs: Long = MAX_GAP_MS): List<RallyCandidate> {
        require(maxGapMs >= 0) { "maxGapMs must not be negative" }
        if (hits.isEmpty()) return emptyList()
        val times = hits.map { it.tMs }.sorted()
        val out = ArrayList<RallyCandidate>()
        var first = times[0]
        var last = times[0]
        var count = 1
        fun close() {
            if (count >= MIN_HITS) out += RallyCandidate(first, last, count)
        }
        for (i in 1 until times.size) {
            val t = times[i]
            if (t - last > maxGapMs) {
                close()
                first = t; count = 1
            } else {
                count++
            }
            last = t
        }
        close()
        return out
    }
}
