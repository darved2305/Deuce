package com.elevate.app.domain.tactics

import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.WindowResultStore
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.calibration.Homography

/** Who "you" are, and how the app knows it (shown to the player, who can swap it). */
enum class YouSource { MATCH_RECORD, YOUR_TAP, CAMERA_SIDE, SWAPPED }

data class TacticsView(val state: TacticsState, val youSource: YouSource?, val live: Boolean)

/**
 * Builds [Tactics] inputs from what has arrived: the finished `match.v1` when it is there (hits with
 * ids, landings in metres), otherwise the live windows (hits, landings projected through the court
 * calibration). Player rows always come from the windows.
 */
object TacticsInputs {
    fun view(
        match: MatchRecord?,
        liveHits: List<HitCandidate>,
        liveLandings: List<WindowResultStore.LiveLanding>,
        players: List<PlayerRow>,
        liveTemplate: String?,
        liveImageToCourt: Homography?,
        whoIsMe: Triple<Long, Double, Double>?,
        swapped: Boolean,
    ): TacticsView? {
        val template = match?.calibration?.template ?: liveTemplate ?: return null
        val fromRecord = match != null && match.events.any { it.type == "hit" }
        val hits = if (fromRecord) {
            match!!.events.filter { it.type == "hit" }.map { e ->
                TacHit(e.eventId, e.tMs, e.imageXy?.takeIf { it.size == 2 && it.all(Double::isFinite) }?.let { it[0] to it[1] })
            }
        } else {
            liveHits.map { h -> TacHit("live_${h.tMs}", h.tMs, h.imageXy) }
        }
        val landings = if (fromRecord) {
            match!!.events.filter { it.type == "landing" && (it.plane == null || it.plane == "court") }.mapNotNull { e ->
                val xy = e.contactXyM?.takeIf { it.size == 2 && it.all(Double::isFinite) } ?: return@mapNotNull null
                TacLanding(e.eventId, e.tMs, xy[0], xy[1], e.state)
            }
        } else {
            val h = liveImageToCourt
            if (h == null) emptyList() else liveLandings.mapNotNull { l ->
                val p = h.projectToCourt(l.imageXy.first, l.imageXy.second)
                if (p[2] <= 0.0) return@mapNotNull null
                TacLanding(l.eventId, l.tMs, p[0] / p[2], p[1] / p[2], l.state)
            }
        }
        val (you, source) = resolveYou(match, players, whoIsMe, swapped)
        val state = Tactics.compute(template, hits, landings, players, you)
        return TacticsView(state, source, live = !fromRecord)
    }

    /**
     * "You": the match record's track ids for the "you" player, else the player box under your
     * setup tap, else the player on the camera's side of the net (the filming tip puts the phone
     * behind your baseline). [swapped] flips to the other player.
     */
    fun resolveYou(
        match: MatchRecord?,
        players: List<PlayerRow>,
        whoIsMe: Triple<Long, Double, Double>?,
        swapped: Boolean,
    ): Pair<String?, YouSource?> {
        val tracks = players.groupBy { it.trackId }.entries.sortedByDescending { it.value.size }.take(2).map { it.key }
        if (tracks.isEmpty()) return null to null
        var source: YouSource
        var you: String? = match?.players?.firstOrNull { it.label == "you" }?.trackIds?.firstOrNull { it in tracks }
        source = YouSource.MATCH_RECORD
        if (you == null && whoIsMe != null) {
            val (t, x, y) = whoIsMe
            you = players.filter { kotlin.math.abs(it.tMs - t) <= 500 && x in it.x1..it.x2 && y in it.y1..it.y2 }
                .minByOrNull { kotlin.math.abs(it.tMs - t) }?.trackId?.takeIf { it in tracks }
            source = YouSource.YOUR_TAP
        }
        if (you == null) {
            you = tracks.minByOrNull { id ->
                val ys = players.filter { it.trackId == id }.mapNotNull { it.footYm }.sorted()
                if (ys.isEmpty()) Double.MAX_VALUE else ys[ys.size / 2]
            }?.takeIf { id -> players.any { it.trackId == id && (it.footYm ?: Double.MAX_VALUE) < CourtModel.NET_Y_M } }
            source = YouSource.CAMERA_SIDE
        }
        if (you != null && swapped) {
            you = tracks.firstOrNull { it != you } ?: you
            source = YouSource.SWAPPED
        }
        return you to source.takeIf { you != null }
    }
}
