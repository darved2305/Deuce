package com.elevate.app.domain.decide

import kotlin.math.abs

/** One real foot position (court metres) of a tracked player, from a `window_result.v1` players row. */
data class FootSample(val tMs: Long, val trackId: String, val xM: Double, val yM: Double)

/**
 * Foot positions of every downloaded window, by time. Rows the engine withheld (null foot metres,
 * uncalibrated or off-court) are never stored, so a missing position stays missing.
 */
class PlayerFeet(samples: List<FootSample>) {
    private val sorted = samples.sortedBy { it.tMs }
    val size: Int get() = sorted.size

    /**
     * The sample of each track nearest in time to [tMs], only those within [toleranceMs]. Nothing is
     * interpolated: a position is either a real row near that frame or absent.
     */
    fun nearAt(tMs: Long, toleranceMs: Long): List<FootSample> {
        if (sorted.isEmpty()) return emptyList()
        var lo = 0
        var hi = sorted.size
        val key = tMs - toleranceMs
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].tMs < key) lo = mid + 1 else hi = mid
        }
        val best = HashMap<String, FootSample>()
        var i = lo
        while (i < sorted.size && sorted[i].tMs <= tMs + toleranceMs) {
            val s = sorted[i]
            val cur = best[s.trackId]
            if (cur == null || abs(s.tMs - tMs) < abs(cur.tMs - tMs)) best[s.trackId] = s
            i++
        }
        return best.values.sortedBy { it.trackId }
    }

    companion object {
        val EMPTY = PlayerFeet(emptyList())
    }
}
