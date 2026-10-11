package com.elevate.app.domain.training

/**
 * Freeze points before a contact, chosen from the file's real decoded frame times (never a timer).
 * A frame is safe when its presentation time is strictly before `contact − sigma`: the contact's
 * own uncertainty must not let a post-contact frame through.
 */
object FreezeFrames {
    /** Default Information Ladder offsets before contact, earliest first. */
    val DEFAULT_LADDER_MS = listOf(250L, 150L, 70L)

    /** Exclusive upper bound (µs) for frames that may be shown before the answer is locked. */
    fun safeLimitUs(contactMs: Long, contactSigmaMs: Long): Long = (contactMs - contactSigmaMs) * 1000

    /** Last real frame at or before [targetUs] and strictly before [limitUs], or null. */
    fun frameAtOrBefore(frameTimesUs: LongArray, targetUs: Long, limitUs: Long): Long? {
        val bound = minOf(targetUs, limitUs - 1)
        var lo = 0
        var hi = frameTimesUs.size - 1
        var best: Long? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (frameTimesUs[mid] <= bound) { best = frameTimesUs[mid]; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /**
     * The ladder: one distinct real frame per offset, in reveal order (earliest first). Offsets
     * whose frame repeats the previous step's frame (low frame rate) are dropped, so each "another
     * look" shows new footage. Empty when no frame precedes the contact safely.
     */
    fun ladder(
        frameTimesUs: LongArray,
        contactMs: Long,
        contactSigmaMs: Long,
        offsetsMs: List<Long> = DEFAULT_LADDER_MS,
    ): List<Long> {
        require(contactSigmaMs >= 0) { "sigma $contactSigmaMs" }
        val limit = safeLimitUs(contactMs, contactSigmaMs)
        val out = ArrayList<Long>()
        for (off in offsetsMs.sortedDescending()) {
            val f = frameAtOrBefore(frameTimesUs, (contactMs - off) * 1000, limit) ?: continue
            if (out.isEmpty() || f > out.last()) out += f
        }
        return out
    }
}
