package com.elevate.app.ui.profile

import android.content.Context
import com.elevate.app.data.analysis.WindowCache
import com.elevate.app.data.history.HistoryStatus
import com.elevate.app.data.history.MatchHistoryEntry
import com.elevate.app.data.history.MatchHistoryRuntime
import com.elevate.app.data.history.cardTimeMs
import com.elevate.app.data.history.status
import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.profile.MatchSummaryInput
import com.elevate.app.domain.profile.PlayerProfile
import com.elevate.app.domain.profile.ProfileInputs
import com.elevate.app.ui.overview.OverviewDerivation
import com.elevate.app.ui.readlab.SharedPrefsReflexBest
import com.elevate.app.domain.coach.CoachReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The profile plus the matches that could not be used, so the screen can say how many. */
data class ProfileLoad(val profile: PlayerProfile, val notYetAnalysed: Int)

/**
 * Reads this phone's history and, per match, the cached `match.v1` (else the cached windows) from the
 * [WindowCache]. A match with neither is skipped and counted in [ProfileLoad.notYetAnalysed]; nothing is
 * filled in for it. A match whose cache has windows but no record contributes hits and rallies only
 * (windows carry no court landings). Blocking file IO, so it runs on [Dispatchers.IO].
 */
object ProfileLoader {
    suspend fun load(context: Context): ProfileLoad = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val entries = runCatching { MatchHistoryRuntime.get(app).history.list() }.getOrDefault(emptyList())
        val inputs = ArrayList<MatchSummaryInput>()
        var skipped = 0
        for (e in entries) {
            val input = runCatching { inputOf(app, e) }.getOrNull()
            if (input == null) skipped++ else inputs += input
        }
        ProfileLoad(PlayerProfile.build(inputs), skipped)
    }

    private fun inputOf(app: Context, e: MatchHistoryEntry): MatchSummaryInput? {
        if (e.status() == HistoryStatus.Failed) return null
        val cache = WindowCache.forAnalysis(app.filesDir, e.analysisId)
        val record = cache.match()?.first
        val durationMs = e.durationMs
        val date = e.cardTimeMs()
        if (record != null) {
            val matchId = record.matchId
            val hits = OverviewDerivation.recordHitTimeline(record, durationMs)?.hits.orEmpty()
            val heat = CoachReport.heat(OverviewDerivation.landingEvidence(record))
            return ProfileInputs.fromParts(
                id = e.analysisId, title = e.title, dateMs = date, durationMs = durationMs,
                hits = hits, heat = heat,
                reflexBest = reflexBest(app, matchId), readLab = ReadLabResults.get(app, matchId),
            )
        }
        // No record cached: the windows, if the match was analysed to the end (hits only).
        if (e.status() == HistoryStatus.Analysing) return null
        val windows = cache.windows()
        if (windows.isEmpty()) return null
        val hits = windows.flatMap { it.result.events }.filter { it.type == "hit" }
            .distinctBy { it.eventId }
            .filter { it.tMs >= 0 }
            .map { HitCandidate(it.tMs, it.tSigmaMs, it.trackId.ifBlank { null }, null, it.cues, it.state) }
        val matchId = e.matchId
        return ProfileInputs.fromParts(
            id = e.analysisId, title = e.title, dateMs = date, durationMs = durationMs,
            hits = hits, heat = null,
            reflexBest = matchId?.let { reflexBest(app, it) }, readLab = matchId?.let { ReadLabResults.get(app, it) },
        )
    }

    /** Reflex's stored best for a match (`elevate_reflex`, `best:<matchId>`), null when never played. */
    private fun reflexBest(app: Context, matchId: String): Int? = SharedPrefsReflexBest(app).best(matchId)
}
