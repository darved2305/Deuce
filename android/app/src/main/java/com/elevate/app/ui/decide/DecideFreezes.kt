package com.elevate.app.ui.decide

import com.elevate.app.data.api.MatchEvent
import com.elevate.app.data.api.MatchPlayer
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.calibration.Homography
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.decide.Freeze
import com.elevate.app.domain.decide.PlayerFeet
import com.elevate.app.domain.decide.RealShot
import com.elevate.app.domain.readlab.TRUSTED_STATES
import com.elevate.app.domain.shotrewind.Placed
import com.elevate.app.domain.shotrewind.RewindShot
import com.elevate.app.domain.shotrewind.ShotRewind
import com.elevate.app.ui.readlab.ReadLabDrills
import kotlin.math.hypot

/**
 * Builds the moments Decide can freeze from a served `match.v1`: the player's own hits (or every
 * hit when none can be told apart), each with a real frame strictly before its contact, the
 * hitter's side, the defenders' real feet at that frame and the real landing when one is trusted.
 */
object DecideFreezes {
    /** The freeze is this long before contact (the earliest step of the training ladder). */
    const val LEAD_MS = 250L

    /** A foot row counts for a frame only if it is this close in time; nothing is interpolated. */
    const val FOOT_TOLERANCE_MS = 200L

    /** The person confirmed as the player; no confirmed identity, no "yours". */
    fun you(match: MatchRecord?): MatchPlayer? = match?.players?.firstOrNull { it.identity == "user_confirmed" }

    /**
     * Whether [hit] is the player's: by the event's own player or track id when it has one, else by
     * the foot nearest the hit point. Null when it cannot be told.
     */
    fun attribute(hit: MatchEvent, you: MatchPlayer?, hitPointM: Pair<Double, Double>?, feet: PlayerFeet): Boolean? {
        if (you == null) return null
        hit.playerId?.let { return it == you.playerId }
        hit.trackId?.takeIf { you.trackIds.isNotEmpty() }?.let { return it in you.trackIds }
        val p = hitPointM ?: return null
        val nearest = feet.nearAt(hit.tMs, FOOT_TOLERANCE_MS).minByOrNull { hypot(it.xM - p.first, it.yM - p.second) } ?: return null
        return nearest.trackId in you.trackIds
    }

    fun build(
        events: List<MatchEvent>,
        template: String,
        you: MatchPlayer?,
        frameTimesUs: LongArray,
        feet: PlayerFeet,
        hitPointM: (MatchEvent) -> Pair<Double, Double>?,
    ): List<Freeze> {
        if (ZoneGrid.forTemplate(template) == null) return emptyList()
        val hits = events.filter { it.type == "hit" }.sortedBy { it.tMs }
        val verdicts = hits.associateWith { attribute(it, you, hitPointM(it), feet) }
        val anyTold = verdicts.values.any { it != null }
        val chosen = if (anyTold) hits.filter { verdicts[it] == true } else hits
        val landings = ReadLabDrills.pair(events).associate { (h, l) -> h.eventId to l }
        return chosen.mapNotNull { hit ->
            val shot = RewindShot(hit.eventId, hit.rallyId.orEmpty(), 0, hit.playerId.orEmpty(), hit.tMs, hit.tSigmaMs)
            val freezeUs = ShotRewind.rewindFrameUs(shot, frameTimesUs, LEAD_MS) ?: return@mapNotNull null
            val atFreeze = feet.nearAt(freezeUs / 1000, FOOT_TOLERANCE_MS)
            val point = hitPointM(hit)
            val yours = anyTold
            val hitterFoot = atFreeze.firstOrNull { f ->
                f.trackId == hit.trackId || (yours && you != null && f.trackId in you.trackIds)
            }
            val landing = landings[hit.eventId]?.let(ReadLabDrills::landingXy)
            val side = hitterFoot?.let { CourtSide.of(it.yM) } ?: point?.let { CourtSide.of(it.second) }
                ?: landing?.let { CourtSide.of(it.second)?.other } ?: return@mapNotNull null
            val defenders = atFreeze.filter { CourtSide.of(it.yM) == side.other }
                .map { Placed(it.trackId, it.xM, it.yM, "observed") }
            val landingState = landings[hit.eventId]?.state
            val real = if (landing != null && landingState in TRUSTED_STATES) {
                RealShot(landing.first, landing.second, ShotRewind.decide(template, side, landing.first, landing.second, defenders))
            } else null
            Freeze(
                hitEventId = hit.eventId, contactMs = hit.tMs, freezeUs = freezeUs, template = template, side = side,
                hitterXyM = hitterFoot?.let { it.xM to it.yM }, yours = yours, defenders = defenders, real = real,
            )
        }
    }

    /** Freezes of a calibrated badminton match on the open file; empty when anything needed is missing. */
    fun fromMatch(match: MatchRecord?, frameTimesUs: LongArray?, imageW: Int?, imageH: Int?, feet: PlayerFeet): List<Freeze> {
        if (match == null || match.sport != "badminton" || frameTimesUs == null || frameTimesUs.isEmpty()) return emptyList()
        val cal = match.calibration?.takeIf { it.plane == "court" } ?: return emptyList()
        if (imageW == null || imageH == null || imageW <= 0 || imageH <= 0) return emptyList()
        val h = Homography.fromImageToCourt(cal.h, imageW, imageH)
        return build(match.events, cal.template, you(match), frameTimesUs, feet) { hit -> h?.let { hitPoint(it, hit) } }
    }

    /** Court metres under the hit's image point; null at or past the horizon or without a point. */
    fun hitPoint(h: Homography, hit: MatchEvent): Pair<Double, Double>? {
        val p = hit.imageXy?.takeIf { it.size == 2 && it.all(Double::isFinite) } ?: return null
        val c = h.projectToCourt(p[0], p[1])
        if (c[2] <= 0.0) return null
        val x = c[0] / c[2]
        val y = c[1] / c[2]
        return if (x.isFinite() && y.isFinite()) x to y else null
    }
}
