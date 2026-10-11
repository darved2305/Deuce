package com.elevate.app.domain.review

import com.elevate.app.data.api.MatchEvent
import com.elevate.app.data.api.MatchRecord
import kotlin.math.abs

/** One real landing event of the match, with its call. Only landings the engine put on the court are listed. */
data class ReviewLanding(
    val eventId: String,
    val tMs: Long,
    /** The landing's `image_xy` in source pixels, or null. */
    val imageXy: Pair<Double, Double>?,
    val rallyId: String?,
    /** observed | candidate | inferred | user, verbatim. */
    val state: String,
    val call: LineCall,
)

object ReviewLandings {
    /**
     * Every `landing` event with a finite `contact_xy_m`, in time order. Empty (so the entry is hidden) for a
     * match with none, a non-badminton match, or no match at all. Nothing is invented: a landing with no
     * position is not listed at all.
     */
    fun from(match: MatchRecord?): List<ReviewLanding> {
        if (match == null || match.sport != "badminton") return emptyList()
        val cal = match.calibration
        return match.events.asSequence()
            .filter { it.type == "landing" && hasPosition(it) }
            .sortedBy { it.tMs }
            .map { e ->
                ReviewLanding(
                    eventId = e.eventId,
                    tMs = e.tMs,
                    imageXy = e.imageXy?.takeIf { it.size == 2 && it.all(Double::isFinite) }?.let { it[0] to it[1] },
                    rallyId = e.rallyId,
                    state = e.state,
                    call = LineCall.of(
                        LandingInput(
                            xyM = e.contactXyM,
                            covM2 = e.covM2,
                            plane = e.plane,
                            calibrationPlane = cal?.plane,
                            tier = cal?.tier,
                            template = cal?.template,
                            singles = match.format != "doubles",
                        ),
                    ),
                )
            }.toList()
    }

    private fun hasPosition(e: MatchEvent): Boolean = e.contactXyM?.let { it.size == 2 && it.all(Double::isFinite) } == true

    /** The real frame time (ms) nearest [tMs], else [tMs] itself when the file's frame times are unknown. */
    fun frameMs(frameTimesUs: LongArray?, tMs: Long): Long {
        if (frameTimesUs == null || frameTimesUs.isEmpty()) return tMs
        val target = tMs * 1000
        var lo = 0
        var hi = frameTimesUs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (frameTimesUs[mid] < target) lo = mid + 1 else hi = mid
        }
        val best = if (lo > 0 && abs(frameTimesUs[lo - 1] - target) <= abs(frameTimesUs[lo] - target)) lo - 1 else lo
        // Rounded up so the player shows exactly that frame, never an earlier one.
        return (frameTimesUs[best] + 999) / 1000
    }
}
