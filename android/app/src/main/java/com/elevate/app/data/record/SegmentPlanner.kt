package com.elevate.app.data.record

/** One recorded piece on the recording's own timeline (microseconds from the first frame). */
data class SegmentSpan(val seq: Int, val ptsStartUs: Long, val ptsEndUs: Long) {
    val durationUs: Long get() = ptsEndUs - ptsStartUs
}

/** Why the recording should end now, decided by [SegmentPlanner.stopReason]. */
enum class StopReason { Budget, MaxDuration }

/**
 * The rolling-segment rules. A new piece starts every [TARGET_SEGMENT_US], matching the engine's
 * 6 s windows (`engine/elevate_engine/pipeline.py` WINDOW_MS), and the whole recording stays inside
 * the 300 MiB per-video limit (G1) the server enforces on the sum of all pieces.
 */
object SegmentPlanner {
    /** Engine window length (WINDOW_MS = 6000). Pieces are this long, give or take one frame. */
    const val TARGET_SEGMENT_US = 6_000_000L

    /** Pieces shorter than this at the end of a recording are real frames, kept; below it they are dropped as empty. */
    const val MIN_USEFUL_SEGMENT_US = 200_000L

    /** Room kept under the cap so the last piece cannot cross it. */
    const val CAP_MARGIN_BYTES = 4L * 1024 * 1024

    /** True once the open piece has reached its target length. */
    fun shouldRoll(elapsedInSegmentUs: Long): Boolean = elapsedInSegmentUs >= TARGET_SEGMENT_US

    /** Expected size of one full piece at [totalBitrateBps]. */
    fun expectedSegmentBytes(totalBitrateBps: Int): Long =
        RecordingParamsChooser.estimateBytes(totalBitrateBps, TARGET_SEGMENT_US / 1_000_000)

    /** How many pieces [durationUs] makes (the last one may be partial). */
    fun segmentCount(durationUs: Long): Int =
        if (durationUs <= 0) 0 else ((durationUs + TARGET_SEGMENT_US - 1) / TARGET_SEGMENT_US).toInt()

    /**
     * Bytes the next piece may use, for the recorder's per-file size limit: what is left of the
     * budget minus the margin, never above [maxSegmentBytes] (the server's `max_segment_bytes`).
     */
    fun nextFileSizeLimit(bytesSoFar: Long, maxSegmentBytes: Long, cap: Long = RecordingParamsChooser.VIDEO_BYTE_CAP): Long =
        minOf(maxSegmentBytes, cap - bytesSoFar - CAP_MARGIN_BYTES).coerceAtLeast(0)

    /**
     * Whether recording must end now. [bytesSoFar] is every finished piece plus the open one's
     * size so far; [elapsedUs] is the recorded time.
     */
    fun stopReason(bytesSoFar: Long, elapsedUs: Long, params: RecordingParams): StopReason? = when {
        bytesSoFar + CAP_MARGIN_BYTES >= RecordingParamsChooser.VIDEO_BYTE_CAP -> StopReason.Budget
        elapsedUs / 1_000_000 >= params.maxDurationS -> StopReason.MaxDuration
        else -> null
    }

    /**
     * Puts measured piece lengths on one continuous timeline: piece k starts where piece k-1 ended.
     * This is the same timeline the concatenated local copy has, so the server's `pts_start_us` /
     * `pts_end_us` and the overlays on the local copy agree. A gap of a few frames between two
     * pieces (CameraX restarts its muxer) is not part of the timeline: it is the one place the
     * recording's wall clock and this timeline can differ (RECORDING.md, risks).
     */
    fun timeline(durationsUs: List<Long>): List<SegmentSpan> {
        var t = 0L
        return durationsUs.mapIndexed { i, d ->
            require(d > 0) { "piece $i has no duration" }
            SegmentSpan(i, t, t + d).also { t += d }
        }
    }
}
