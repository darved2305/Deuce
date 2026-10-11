package com.elevate.app.domain.analysis

/**
 * One engine hit candidate as the player draws it: source time (ms) and, when the engine gave a
 * usable one, the image position in source pixels (after rotation, contracts §0). Always a
 * candidate; never a confirmed hit.
 */
data class LiveHit(
    val tMs: Long,
    /** `image_xy` in source pixels, null when absent or outside the image. */
    val imageXy: Pair<Float, Float>?,
    val sigmaMs: Long,
    /** Provenance state, verbatim from the event ("candidate" from the engine). */
    val state: String,
    /** True for a `type: "landing"` event (drawn as "LANDING?"), false for a hit candidate. */
    val landing: Boolean = false,
)

object LiveHits {

    /**
     * The hit candidates to show on the open file. Empty unless the open file is the very file that
     * was analysed ([openSha256] equals the upload's [analysedSha256]; both must be known): hits of
     * another file must never be drawn on this one.
     *
     * Hits with a negative time, or after [durationMs] when it is known, are dropped. An `image_xy`
     * outside [imageW] x [imageH] (when known) is dropped but the hit is kept (it still has a time).
     * The result is sorted by time; identical duplicates collapse.
     */
    fun forFile(
        hits: List<HitCandidate>,
        analysedSha256: String?,
        openSha256: String?,
        durationMs: Long? = null,
        imageW: Int? = null,
        imageH: Int? = null,
    ): List<LiveHit> {
        if (analysedSha256.isNullOrBlank() || openSha256.isNullOrBlank()) return emptyList()
        if (!analysedSha256.equals(openSha256, ignoreCase = true)) return emptyList()
        return hits.asSequence()
            .filter { it.tMs >= 0 && (durationMs == null || durationMs <= 0 || it.tMs <= durationMs) }
            .map { h -> LiveHit(h.tMs, h.imageXy?.let { usableXy(it, imageW, imageH) }, h.sigmaMs, h.state) }
            .distinct()
            .sortedBy { it.tMs }
            .toList()
    }

    private fun usableXy(xy: Pair<Double, Double>, w: Int?, h: Int?): Pair<Float, Float>? {
        val (x, y) = xy
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0) return null
        if (w != null && w > 0 && x > w) return null
        if (h != null && h > 0 && y > h) return null
        return x.toFloat() to y.toFloat()
    }

    /**
     * Indices of [sortedTMs] within ±[windowMs] of [atMs] (inclusive), as a range (empty if none).
     * Binary search, no allocation besides the range: called for every video frame.
     */
    fun indicesNear(sortedTMs: LongArray, atMs: Long, windowMs: Long): IntRange {
        val lo = lowerBound(sortedTMs, atMs - windowMs)
        val hi = lowerBound(sortedTMs, atMs + windowMs + 1)
        return lo until hi
    }

    private fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
