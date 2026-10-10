package com.elevate.app.playback

import com.elevate.app.domain.model.TrailTrack

/**
 * Pure time arithmetic between the three clocks in play:
 *  - the decoded frame's `presentationTimeUs` handed to the Media3 effect pipeline,
 *  - source presentation time (contracts §0: measured from the first presented frame),
 *  - the integer milliseconds used in contract records.
 *
 * Nothing here assumes a frame rate: frame matching uses the label set's own frame spacing.
 */
object TimeMapping {

    /** Contract records use integer ms; round half away from zero like the labeller export. */
    fun usToMs(us: Long): Long = Math.round(us / 1000.0)

    fun msToUs(ms: Long): Long = ms * 1000

    /**
     * Source time of a decoded frame. [clipStartInSourceUs] is the source time of the played
     * file's first presented frame: 0 for the gallery original, `offset_ms` for a cut clip.
     */
    fun presentationToSourceUs(presentationTimeUs: Long, clipStartInSourceUs: Long = 0): Long =
        presentationTimeUs + clipStartInSourceUs

    /**
     * Half the smallest spacing between labelled frames: a decoded frame matches a label only if
     * it is closer to it than to any neighbouring frame. Returns 0 when there is no spacing to
     * measure (a single label), which means exact match only.
     */
    fun matchToleranceUs(sortedTUs: LongArray): Long {
        var min = Long.MAX_VALUE
        for (i in 1 until sortedTUs.size) {
            val d = sortedTUs[i] - sortedTUs[i - 1]
            if (d in 1 until min) min = d
        }
        return if (min == Long.MAX_VALUE) 0 else (min - 1) / 2
    }

    /** Index of the label for the frame at [sourceUs], or -1 if that frame was not labelled. */
    fun labelIndexAt(track: TrailTrack, sourceUs: Long, toleranceUs: Long): Int {
        val t = track.tUs
        if (t.isEmpty()) return -1
        val i = lowerBound(t, sourceUs - toleranceUs)
        return if (i < t.size && t[i] <= sourceUs + toleranceUs) i else -1
    }

    /**
     * Half-open index range [from, to) of labels with time in (sourceUs − windowUs, sourceUs +
     * toleranceUs]: the current frame and the ones just before it. Never anything after it.
     */
    fun trailWindow(track: TrailTrack, sourceUs: Long, windowUs: Long, toleranceUs: Long): IntRange {
        val t = track.tUs
        val from = lowerBound(t, sourceUs - windowUs + 1)
        val to = lowerBound(t, sourceUs + toleranceUs + 1)
        return from until to
    }

    /** First index whose value is >= [key]. */
    internal fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
