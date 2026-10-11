package com.elevate.app.ui.readlab

import com.elevate.app.data.api.MatchEvent
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.calibration.Homography
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.readlab.Eligibility
import com.elevate.app.domain.readlab.ReadChallenge
import com.elevate.app.domain.readlab.ReadLab
import com.elevate.app.domain.readlab.ReadShot

/**
 * One drill: a real hit from the match, frozen just before contact, and the real landing that
 * followed it. [challenge] comes from the domain ReadLab (freeze frames strictly before contact,
 * landing truth only from observed or user states); this class adds nothing to the scoring.
 */
data class Drill(
    val hitEventId: String,
    val landingEventId: String,
    val challenge: ReadChallenge,
    val template: String,
    val landingXyM: Pair<Double, Double>,
    /** State of the landing record, verbatim (observed, user, candidate, inferred). */
    val landingState: String,
    /** The side the picker is drawn from (the hitter's view). Orientation only; scoring uses the domain truth. */
    val viewSide: CourtSide,
)

object ReadLabDrills {
    /** A landing belongs to the last hit before it, if that hit is at most this far back. */
    const val PAIR_WINDOW_MS = 3_000L

    /** Hits are `type == "hit"`; the landing is `type == "landing"` with court metres. */
    fun landingXy(e: MatchEvent): Pair<Double, Double>? {
        if (e.type != "landing") return null
        if (e.plane != null && e.plane != "court") return null
        val xy = e.contactXyM?.takeIf { it.size == 2 && it.all(Double::isFinite) } ?: return null
        return xy[0] to xy[1]
    }

    /**
     * Each landing with court metres, paired with the last hit strictly before it and within
     * [PAIR_WINDOW_MS]. A hit serves one landing (the earliest after it). No hit, no drill.
     */
    fun pair(events: List<MatchEvent>): List<Pair<MatchEvent, MatchEvent>> {
        val hits = events.filter { it.type == "hit" }.sortedBy { it.tMs }
        val landings = events.filter { landingXy(it) != null }.sortedBy { it.tMs }
        val used = HashSet<String>()
        val out = ArrayList<Pair<MatchEvent, MatchEvent>>()
        for (l in landings) {
            val hit = hits.lastOrNull { it.tMs < l.tMs } ?: continue
            if (l.tMs - hit.tMs > PAIR_WINDOW_MS) continue
            if (!used.add(hit.eventId)) continue
            out += hit to l
        }
        return out
    }

    /**
     * Drills for a calibrated badminton match on a file with known frame times. [hitterYM] gives the
     * hitter's court y for a hit (null when it cannot be placed); the hit's own state travels with it,
     * so an unobserved hitter position leaves the drill unscored in the domain truth.
     */
    fun build(
        events: List<MatchEvent>,
        template: String,
        frameTimesUs: LongArray,
        hitterYM: (MatchEvent) -> Double?,
    ): List<Drill> {
        return pair(events).mapNotNull { (hit, landing) ->
            val xy = landingXy(landing) ?: return@mapNotNull null
            val y = hitterYM(hit)
            val shot = ReadShot(
                matchId = "match", shotId = hit.eventId,
                contactMs = hit.tMs, contactSigmaMs = hit.tSigmaMs,
                hitterYM = y, hitterState = if (y != null) hit.state else null,
                landingXyM = listOf(xy.first, xy.second), landingState = landing.state,
            )
            val ok = ReadLab.challenge(shot, template, frameTimesUs) as? Eligibility.Ok ?: return@mapNotNull null
            val side = y?.let { CourtSide.of(it) } ?: CourtSide.of(xy.second)?.other ?: CourtSide.NEAR
            Drill(hit.eventId, landing.eventId, ok.challenge, template, xy, landing.state, side)
        }
    }

    /** Drills of a served `match.v1`: needs a court-plane calibration and the open file's frame times. */
    fun fromMatch(match: MatchRecord?, frameTimesUs: LongArray?, imageW: Int?, imageH: Int?): List<Drill> {
        if (match == null || match.sport != "badminton" || frameTimesUs == null || frameTimesUs.isEmpty()) return emptyList()
        val cal = match.calibration?.takeIf { it.plane == "court" } ?: return emptyList()
        if (imageW == null || imageH == null || imageW <= 0 || imageH <= 0) return emptyList()
        val homography = Homography.fromImageToCourt(cal.h, imageW, imageH)
        return build(match.events, cal.template, frameTimesUs) { hit -> homography?.let { hitterY(it, hit) } }
    }

    /** Court y under the hit's image point; null at or past the horizon, or without a point. */
    fun hitterY(h: Homography, hit: MatchEvent): Double? {
        val p = hit.imageXy?.takeIf { it.size == 2 && it.all(Double::isFinite) } ?: return null
        val c = h.projectToCourt(p[0], p[1])
        if (c[2] <= 0.0) return null
        return (c[1] / c[2]).takeIf { it.isFinite() }
    }
}
