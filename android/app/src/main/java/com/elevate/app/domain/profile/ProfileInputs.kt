package com.elevate.app.domain.profile

import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.HitRallies
import com.elevate.app.domain.analysis.RallyCandidate
import com.elevate.app.domain.coach.LandingHeat

/** Builds [MatchSummaryInput]s from what the cache holds. Pure: no IO. */
object ProfileInputs {

    /** Seconds between consecutive hits inside each rally candidate (the gaps CoachReport's tempo rule uses). */
    fun gapsSeconds(hits: List<HitCandidate>, rallies: List<RallyCandidate>): List<Double> {
        val times = hits.map { it.tMs }.sorted()
        val out = ArrayList<Double>()
        for (r in rallies) {
            val inside = times.filter { it in r.startMs..r.endMs }
            for (i in 1 until inside.size) out += (inside[i] - inside[i - 1]) / 1000.0
        }
        return out
    }

    /**
     * One match from its hit candidates and the landing heat of its record. Rally candidates are grouped
     * from [hits] ([HitRallies]) unless [rallies] is given. [heat] is empty for a match without
     * landings with court positions (e.g. only windows are cached).
     */
    fun fromParts(
        id: String,
        title: String,
        dateMs: Long,
        durationMs: Long?,
        hits: List<HitCandidate>,
        heat: LandingHeat?,
        rallies: List<RallyCandidate> = HitRallies.group(hits),
        reflexBest: Int? = null,
        readLab: ReadLabStat? = null,
    ): MatchSummaryInput = MatchSummaryInput(
        id = id,
        title = title,
        dateMs = dateMs,
        durationMs = durationMs?.takeIf { it > 0 },
        hits = hits.size,
        rallyHitCounts = rallies.map { it.hitCount },
        gapsSeconds = gapsSeconds(hits, rallies),
        zoneCounts = heat?.counts.orEmpty().filterValues { it > 0 },
        unzoned = heat?.unzoned ?: 0,
        reflexBest = reflexBest,
        readLab = readLab,
    )
}
