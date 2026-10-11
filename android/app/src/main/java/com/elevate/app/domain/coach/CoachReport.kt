package com.elevate.app.domain.coach

import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.RallyCandidate
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.court.ZoneHit
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import java.util.Locale

/**
 * One landing event's court position as the match record gave it: metres in the calibration
 * template's frame (x across from the left sideline, y from the near baseline, net at 6.70).
 */
data class LandingPoint(val xM: Double, val yM: Double)

/**
 * The landing evidence behind the coach report. [totalEvents] counts every landing event in the
 * record, [points] only those with a usable court position; [template] and [tier] come from the
 * record's calibration (null when it has none).
 */
data class LandingEvidence(
    val totalEvents: Int,
    val points: List<LandingPoint>,
    val template: String?,
    val tier: String?,
    /** True when every landing event is a candidate (the engine's own state), so the copy says so. */
    val allCandidates: Boolean = true,
) {
    companion object {
        val NONE = LandingEvidence(0, emptyList(), null, null)
    }
}

/** 3x3 landing counts, hitter's view (contracts §9); [zoned] is the denominator of every cell. */
data class LandingHeat(
    val counts: Map<ZoneId, Int>,
    /** Landings placed in a cell. */
    val zoned: Int,
    /** Landings with a position that fell beyond the grid or on the net line; not in any cell. */
    val unzoned: Int,
    val caption: String,
) {
    fun count(zone: ZoneId): Int = counts[zone] ?: 0
    fun rowTotal(row: ZoneRow): Int = ZoneId.ALL.filter { it.row == row }.sumOf(::count)
}

/** Rally candidates, summarised. Medians are null when there is nothing to take a median of. */
data class RallyRhythm(
    val rallies: Int,
    val longestHits: Int?,
    val longestSeconds: Double?,
    val medianHits: Double?,
    /** Median time between consecutive hits inside a rally, in seconds. */
    val medianGapSeconds: Double?,
    /** Number of between-hit gaps the tempo is the median of. */
    val gaps: Int,
)

/** A short coaching card. [text] is the whole sentence pair; [evidence] says what it is out of. */
data class Suggestion(val title: String, val text: String, val evidence: String, val enoughData: Boolean)

data class CoachReport(
    val heat: LandingHeat,
    val rhythm: RallyRhythm,
    val suggestions: List<Suggestion>,
) {
    companion object {
        /** Minimum evidence before a rule speaks. Below these, a "not enough data yet" line shows. */
        const val MIN_LANDINGS = 8
        const val MIN_RALLIES = 4
        const val MIN_GAPS = 6

        /** Depth rule: a back row share of at least half is "deep", a front row share of 40% is "short". */
        const val DEEP_SHARE = 0.5
        const val SHORT_SHARE = 0.4

        /** Rally rule: median hits at or below / at or above these. */
        const val SHORT_RALLY_HITS = 3.0
        const val LONG_RALLY_HITS = 8.0

        /** Tempo rule, seconds between hits: below FAST is fast, above SLOW is slow. */
        const val FAST_GAP_S = 1.0
        const val SLOW_GAP_S = 1.6

        const val NO_LANDINGS = "No landings with court positions in this match"

        fun build(landings: LandingEvidence, hits: List<HitCandidate>, rallies: List<RallyCandidate>): CoachReport {
            val heat = heat(landings)
            val rhythm = rhythm(hits, rallies)
            return CoachReport(heat, rhythm, listOf(depth(heat, landings), rallyLength(rhythm), tempo(rhythm)))
        }

        /**
         * Counts landings per zone with the validated [ZoneGrid]. The hitter is the side opposite the
         * landing (a landing on the far half was hit from the near half), so columns are always
         * left / centre / right from the hitter's view and rows front / middle / back from the net.
         */
        fun heat(e: LandingEvidence): LandingHeat {
            val grid = e.template?.let(ZoneGrid::forTemplate)
            if (grid == null || e.points.isEmpty()) return LandingHeat(emptyMap(), 0, 0, NO_LANDINGS)
            val counts = HashMap<ZoneId, Int>()
            var unzoned = 0
            for (p in e.points) {
                val hitter = CourtSide.of(p.yM)?.other
                val hit = if (hitter == null) null else grid.hit(p.xM, p.yM, hitter)
                if (hit is ZoneHit.In) counts.merge(hit.zone, 1, Int::plus) else unzoned++
            }
            val zoned = counts.values.sum()
            if (zoned == 0) return LandingHeat(emptyMap(), 0, unzoned, NO_LANDINGS)
            val noun = if (e.allCandidates) "landing candidate" else "landing"
            val tier = e.tier?.let { ", tier $it court" }.orEmpty()
            val extra = if (unzoned > 0) " · $unzoned beyond the grid or on the net line, not counted" else ""
            return LandingHeat(counts, zoned, unzoned, "$zoned $noun${if (zoned == 1) "" else "s"}$tier$extra")
        }

        fun rhythm(hits: List<HitCandidate>, rallies: List<RallyCandidate>): RallyRhythm {
            if (rallies.isEmpty()) return RallyRhythm(0, null, null, null, null, 0)
            val longest = rallies.maxWithOrNull(compareBy<RallyCandidate>({ it.hitCount }, { it.endMs - it.startMs }))!!
            val times = hits.map { it.tMs }.sorted()
            val gaps = ArrayList<Double>()
            for (r in rallies) {
                val inside = times.filter { it in r.startMs..r.endMs }
                for (i in 1 until inside.size) gaps += (inside[i] - inside[i - 1]) / 1000.0
            }
            return RallyRhythm(
                rallies = rallies.size,
                longestHits = longest.hitCount,
                longestSeconds = (longest.endMs - longest.startMs) / 1000.0,
                medianHits = median(rallies.map { it.hitCount.toDouble() }),
                medianGapSeconds = median(gaps),
                gaps = gaps.size,
            )
        }

        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
        }

        private fun notEnough(title: String, have: Int, need: Int, what: String) =
            Suggestion(title, "$title: not enough data yet ($have of $need $what)", "$have of $need $what needed", false)

        private fun depth(heat: LandingHeat, e: LandingEvidence): Suggestion {
            val title = "Landing depth"
            val n = heat.zoned
            if (n < MIN_LANDINGS) return notEnough(title, n, MIN_LANDINGS, "landing candidates")
            val back = heat.rowTotal(ZoneRow.BACK)
            val mid = heat.rowTotal(ZoneRow.MIDDLE)
            val front = heat.rowTotal(ZoneRow.FRONT)
            val text = when {
                back >= n * DEEP_SHARE ->
                    "Most landings are deep: $back of $n in the back row. Mix in drops to pull the opponent forward."
                front >= n * SHORT_SHARE ->
                    "Many landings are short: $front of $n in the front row. Add clears to push the opponent back."
                else ->
                    "Landings are spread: $front front, $mid middle, $back back of $n. Keep varying depth to stay hard to read."
            }
            val tier = e.tier?.let { ", tier $it court" }.orEmpty()
            return Suggestion(title, text, "$n landing candidates$tier", true)
        }

        private fun rallyLength(r: RallyRhythm): Suggestion {
            val title = "Rally length"
            val median = r.medianHits
            if (r.rallies < MIN_RALLIES || median == null) return notEnough(title, r.rallies, MIN_RALLIES, "rally candidates")
            val m = num(median)
            val text = when {
                median <= SHORT_RALLY_HITS -> "Rallies are short: median $m hits over ${r.rallies} rallies. Work on consistency to extend rallies."
                median >= LONG_RALLY_HITS -> "Rallies run long: median $m hits over ${r.rallies} rallies. Stay patient and pick your moment to attack."
                else -> "Rallies are mid-length: median $m hits over ${r.rallies} rallies. Build each point with depth before you attack."
            }
            return Suggestion(title, text, "${r.rallies} rally candidates", true)
        }

        private fun tempo(r: RallyRhythm): Suggestion {
            val title = "Tempo"
            val gap = r.medianGapSeconds
            if (r.gaps < MIN_GAPS || gap == null) return notEnough(title, r.gaps, MIN_GAPS, "gaps between hits")
            val s = String.format(Locale.ROOT, "%.1f", gap)
            val text = when {
                gap < FAST_GAP_S -> "Fast exchanges: $s s between hits (median of ${r.gaps} gaps). Recovery to base matters."
                gap > SLOW_GAP_S -> "Slower exchanges: $s s between hits (median of ${r.gaps} gaps). Use the time to reset to base and choose your shot."
                else -> "Steady tempo: $s s between hits (median of ${r.gaps} gaps). Vary the pace to break your opponent's rhythm."
            }
            return Suggestion(title, text, "${r.gaps} gaps between hit candidates", true)
        }

        /** "3" for a whole median, "3.5" otherwise. */
        fun num(v: Double): String =
            if (v == Math.rint(v)) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%.1f", v)
    }
}
