package com.elevate.app.domain.profile

import com.elevate.app.domain.coach.CoachReport
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import java.util.Locale

/** What ReadLab recorded for one match: the first run's confirmed-landing reads (never engine estimates). */
data class ReadLabStat(val exact: Int, val adjacent: Int, val scored: Int) {
    init { require(exact >= 0 && adjacent >= 0 && exact + adjacent <= scored) { "inconsistent ReadLab counts" } }
}

/**
 * Everything the profile needs from one analysed match, copied from cached data (the cached `match.v1`,
 * or its windows) and the phone's history entry. Nothing here is estimated: a field the cache could
 * not give is null or empty.
 */
data class MatchSummaryInput(
    val id: String,
    val title: String,
    /** Wall-clock ms the match was analysed (history metadata). */
    val dateMs: Long,
    /** Length of the video in ms, null when unknown. */
    val durationMs: Long?,
    val hits: Int,
    /** Hit count of each rally candidate. */
    val rallyHitCounts: List<Int>,
    /** Seconds between consecutive hits inside rally candidates. */
    val gapsSeconds: List<Double>,
    /** Landings per 3x3 zone, hitter's view (see [com.elevate.app.domain.court.ZoneGrid]). */
    val zoneCounts: Map<ZoneId, Int>,
    /** Landings with a position that fell in no zone (out, or on the net line). */
    val unzoned: Int = 0,
    /** Best Reflex score stored for this match, null when never played. */
    val reflexBest: Int? = null,
    val readLab: ReadLabStat? = null,
) {
    val zoned: Int get() = zoneCounts.values.sum()
}

data class Totals(
    val matches: Int,
    /** Sum of the known video lengths, in minutes. */
    val minutes: Int,
    /** Matches whose video length is unknown, so not in [minutes]. */
    val matchesWithoutDuration: Int,
    val hits: Int,
    val rallies: Int,
)

/** One 3x3 cell: [n] landings out of [total], so the share is always shown with its basis. */
data class ZoneShare(val zone: ZoneId, val n: Int, val total: Int) {
    val share: Double get() = if (total == 0) 0.0 else n.toDouble() / total
    val percent: Int get() = if (total == 0) 0 else Math.round(share * 100).toInt()
}

data class Placement(
    val cells: List<ZoneShare>,
    /** Landings placed in a cell, across every match ("N"). */
    val total: Int,
    /** Matches that contributed at least one placed landing. */
    val matches: Int,
    val unzoned: Int,
) {
    fun cell(zone: ZoneId): ZoneShare = cells.first { it.zone == zone }
    fun rowTotal(row: ZoneRow): Int = cells.filter { it.zone.row == row }.sumOf { it.n }
    val isEmpty: Boolean get() = total == 0
}

enum class StyleTagId(val label: String) {
    DEEP_HITTER("Deep hitter"),
    NET_PLAYER("Net player"),
    RALLY_BUILDER("Rally builder"),
    SHORT_RALLIES("Short rallies"),
    FAST_EXCHANGES("Fast exchanges"),
}

/** A style label and the exact counts that earned it. */
data class StyleTag(val id: StyleTagId, val evidence: String) {
    val label: String get() = id.label
}

enum class TrendMetric(val label: String, val unit: String, val decimals: Int) {
    HITS("Hits per match", "", 0),
    MEDIAN_RALLY("Median rally", "hits", 1),
    MEDIAN_GAP("Median gap between hits", "s", 2),
    DEEP_SHARE("Landings in the back row", "%", 0),
    REFLEX_BEST("Reflex best score", "", 0),
    EXACT_READS("ReadLab exact reads", "%", 0),
}

data class TrendPoint(val matchId: String, val dateMs: Long, val value: Double)

/** One metric's value per match, oldest first. A line is drawn only with [shown] (2 or more points). */
data class Trend(val metric: TrendMetric, val points: List<TrendPoint>) {
    val shown: Boolean get() = points.size >= MIN_POINTS
    /** The line shown instead of a trend: set only when exactly one match has this metric. */
    val message: String? get() = if (points.size == 1) TREND_MESSAGE else null
    val first: Double? get() = points.firstOrNull()?.value
    val latest: Double? get() = points.lastOrNull()?.value

    companion object {
        const val MIN_POINTS = 2
        const val TREND_MESSAGE = "Trend appears after your 2nd analysed match"
    }
}

data class TrainingStats(
    /** Matches with a stored Reflex best. */
    val reflexMatches: Int,
    val reflexMax: Int?,
    /** Best score of the most recent match that has one. */
    val reflexLatest: Int?,
    val readLabMatches: Int,
    val readLabExact: Int,
    val readLabAdjacent: Int,
    val readLabScored: Int,
) {
    /** Exact reads as a share of scored reads, null when nothing was scored. */
    val readLabExactPercent: Int? get() =
        if (readLabScored == 0) null else Math.round(readLabExact * 100.0 / readLabScored).toInt()
    val isEmpty: Boolean get() = reflexMatches == 0 && readLabMatches == 0
}

enum class SignalKind { STRENGTH, FOCUS }

/** A repeating pattern in how matches were played, with how often it showed up. */
data class FocusItem(
    val key: String,
    val kind: SignalKind,
    val title: String,
    val advice: String,
    /** Matches showing the pattern. */
    val seen: Int,
    /** Matches with enough data for the rule that detects it. */
    val of: Int,
) {
    val evidence: String get() = "Seen in $seen of $of match${if (of == 1) "" else "es"}"
}

data class FocusAreas(val strength: FocusItem?, val focus: FocusItem?)

data class PlayerProfile(
    val totals: Totals,
    val placement: Placement,
    val tags: List<StyleTag>,
    /** Why there are no tags yet, or null when at least one tag showed. */
    val tagsHint: String?,
    val trends: List<Trend>,
    val training: TrainingStats,
    val focus: FocusAreas,
    /** Pooled medians behind the tags, for display. */
    val medianRallyHits: Double?,
    val rallyCount: Int,
    val medianGapSeconds: Double?,
    val gapCount: Int,
) {
    val isEmpty: Boolean get() = totals.matches == 0

    companion object {
        /** Tag gates: evidence a rule needs before it speaks. */
        const val MIN_LANDINGS = 15
        const val DEEP_PERCENT = 55
        const val NET_PERCENT = 40
        const val MIN_RALLIES = 4
        const val BUILDER_MEDIAN_HITS = 7.0
        const val SHORT_MEDIAN_HITS = 3.0
        const val MIN_GAPS = 6
        const val FAST_GAP_S = 1.0

        const val EMPTY_TITLE = "Analyse your first match to start your profile"
        const val TAGS_HINT = "Style tags appear once there are $MIN_LANDINGS landings or $MIN_RALLIES rallies to base them on."

        /** [matches] in any order; the profile sorts them by date. */
        fun build(matches: List<MatchSummaryInput>): PlayerProfile {
            val ms = matches.sortedBy { it.dateMs }
            val rallyHits = ms.flatMap { it.rallyHitCounts }
            val gaps = ms.flatMap { it.gapsSeconds }
            val placement = placement(ms)
            val medianRally = CoachReport.median(rallyHits.map { it.toDouble() })
            val medianGap = CoachReport.median(gaps)
            val tags = tags(placement, medianRally, rallyHits.size, medianGap, gaps.size)
            return PlayerProfile(
                totals = totals(ms, rallyHits.size),
                placement = placement,
                tags = tags,
                tagsHint = if (tags.isEmpty()) TAGS_HINT else null,
                trends = trends(ms),
                training = training(ms),
                focus = focus(ms),
                medianRallyHits = medianRally,
                rallyCount = rallyHits.size,
                medianGapSeconds = medianGap,
                gapCount = gaps.size,
            )
        }

        private fun totals(ms: List<MatchSummaryInput>, rallies: Int): Totals {
            val known = ms.mapNotNull { it.durationMs?.takeIf { d -> d > 0 } }
            return Totals(
                matches = ms.size,
                minutes = Math.round(known.sum() / 60_000.0).toInt(),
                matchesWithoutDuration = ms.size - known.size,
                hits = ms.sumOf { it.hits },
                rallies = rallies,
            )
        }

        private fun placement(ms: List<MatchSummaryInput>): Placement {
            val counts = ZoneId.ALL.associateWith { z -> ms.sumOf { it.zoneCounts[z] ?: 0 } }
            val total = counts.values.sum()
            return Placement(
                cells = ZoneId.ALL.map { ZoneShare(it, counts.getValue(it), total) },
                total = total,
                matches = ms.count { it.zoned > 0 },
                unzoned = ms.sumOf { it.unzoned },
            )
        }

        private fun tags(p: Placement, medianRally: Double?, rallies: Int, medianGap: Double?, gaps: Int): List<StyleTag> {
            val out = ArrayList<StyleTag>()
            val n = p.total
            if (n >= MIN_LANDINGS) {
                val back = p.rowTotal(ZoneRow.BACK)
                val front = p.rowTotal(ZoneRow.FRONT)
                if (back * 100 >= DEEP_PERCENT * n) out += StyleTag(StyleTagId.DEEP_HITTER, "$back of $n landings deep")
                if (front * 100 >= NET_PERCENT * n) out += StyleTag(StyleTagId.NET_PLAYER, "$front of $n landings near the net")
            }
            if (rallies >= MIN_RALLIES && medianRally != null) {
                if (medianRally >= BUILDER_MEDIAN_HITS) {
                    out += StyleTag(StyleTagId.RALLY_BUILDER, "Median rally ${CoachReport.num(medianRally)} hits over $rallies rallies")
                } else if (medianRally <= SHORT_MEDIAN_HITS) {
                    out += StyleTag(StyleTagId.SHORT_RALLIES, "Median rally ${CoachReport.num(medianRally)} hits over $rallies rallies")
                }
            }
            if (gaps >= MIN_GAPS && medianGap != null && medianGap < FAST_GAP_S) {
                out += StyleTag(StyleTagId.FAST_EXCHANGES, "Median gap ${String.format(Locale.ROOT, "%.1f", medianGap)} s over $gaps gaps")
            }
            return out
        }

        private fun trends(ms: List<MatchSummaryInput>): List<Trend> {
            fun pts(f: (MatchSummaryInput) -> Double?) = ms.mapNotNull { m -> f(m)?.let { TrendPoint(m.id, m.dateMs, it) } }
            return listOf(
                Trend(TrendMetric.HITS, pts { it.hits.toDouble() }),
                Trend(TrendMetric.MEDIAN_RALLY, pts { m ->
                    if (m.rallyHitCounts.size >= CoachReport.MIN_RALLIES) CoachReport.median(m.rallyHitCounts.map { it.toDouble() }) else null
                }),
                Trend(TrendMetric.MEDIAN_GAP, pts { m ->
                    if (m.gapsSeconds.size >= CoachReport.MIN_GAPS) CoachReport.median(m.gapsSeconds) else null
                }),
                Trend(TrendMetric.DEEP_SHARE, pts { m ->
                    if (m.zoned >= CoachReport.MIN_LANDINGS) backRow(m) * 100.0 / m.zoned else null
                }),
                Trend(TrendMetric.REFLEX_BEST, pts { it.reflexBest?.toDouble() }),
                Trend(TrendMetric.EXACT_READS, pts { m -> m.readLab?.takeIf { it.scored > 0 }?.let { it.exact * 100.0 / it.scored } }),
            )
        }

        private fun backRow(m: MatchSummaryInput) = m.zoneCounts.filterKeys { it.row == ZoneRow.BACK }.values.sum()
        private fun frontRow(m: MatchSummaryInput) = m.zoneCounts.filterKeys { it.row == ZoneRow.FRONT }.values.sum()

        private fun training(ms: List<MatchSummaryInput>): TrainingStats {
            val reflex = ms.filter { it.reflexBest != null }
            val rl = ms.mapNotNull { it.readLab }.filter { it.scored > 0 }
            return TrainingStats(
                reflexMatches = reflex.size,
                reflexMax = reflex.maxOfOrNull { it.reflexBest!! },
                reflexLatest = reflex.lastOrNull()?.reflexBest,
                readLabMatches = rl.size,
                readLabExact = rl.sumOf { it.exact },
                readLabAdjacent = rl.sumOf { it.adjacent },
                readLabScored = rl.sumOf { it.scored },
            )
        }

        /**
         * The per-match patterns the coach report already reports (CoachReport's depth, rally length and
         * tempo rules, same thresholds and minimum data), counted across matches. Only a pattern seen in
         * at least [MIN_REPEATS] matches is offered, with "seen in X of Y" where Y is the matches that had
         * enough data for that rule.
         */
        private fun focus(ms: List<MatchSummaryInput>): FocusAreas {
            val depthEligible = ms.filter { it.zoned >= CoachReport.MIN_LANDINGS }
            val rallyEligible = ms.filter { it.rallyHitCounts.size >= CoachReport.MIN_RALLIES }
            val gapEligible = ms.filter { it.gapsSeconds.size >= CoachReport.MIN_GAPS }

            fun depth(m: MatchSummaryInput): String {
                val n = m.zoned
                return when {
                    backRow(m) >= n * CoachReport.DEEP_SHARE -> "deep"
                    frontRow(m) >= n * CoachReport.SHORT_SHARE -> "short"
                    else -> "spread"
                }
            }
            fun rally(m: MatchSummaryInput): String {
                val med = CoachReport.median(m.rallyHitCounts.map { it.toDouble() })!!
                return when {
                    med <= CoachReport.SHORT_RALLY_HITS -> "rally-short"
                    med >= CoachReport.LONG_RALLY_HITS -> "rally-long"
                    else -> "rally-mid"
                }
            }
            fun tempo(m: MatchSummaryInput): String {
                val med = CoachReport.median(m.gapsSeconds)!!
                return when {
                    med < CoachReport.FAST_GAP_S -> "tempo-fast"
                    med > CoachReport.SLOW_GAP_S -> "tempo-slow"
                    else -> "tempo-steady"
                }
            }

            val items = ArrayList<FocusItem>()
            fun add(eligible: List<MatchSummaryInput>, classify: (MatchSummaryInput) -> String) {
                val byKey = eligible.groupingBy(classify).eachCount()
                for ((key, seen) in byKey) {
                    val d = SIGNALS[key] ?: continue
                    if (seen >= MIN_REPEATS) items += FocusItem(key, d.kind, d.title, d.advice, seen, eligible.size)
                }
            }
            add(depthEligible, ::depth)
            add(rallyEligible, ::rally)
            add(gapEligible, ::tempo)
            val order = compareByDescending<FocusItem> { it.seen.toDouble() / it.of }.thenByDescending { it.seen }.thenBy { it.key }
            val sorted = items.sortedWith(order)
            return FocusAreas(
                strength = sorted.firstOrNull { it.kind == SignalKind.STRENGTH },
                focus = sorted.firstOrNull { it.kind == SignalKind.FOCUS },
            )
        }

        const val MIN_REPEATS = 2

        private data class Signal(val kind: SignalKind, val title: String, val advice: String)

        private val SIGNALS = mapOf(
            "deep" to Signal(SignalKind.FOCUS, "Mostly deep landings", "Mix in drops to pull the opponent forward."),
            "short" to Signal(SignalKind.FOCUS, "Many short landings", "Add clears to push the opponent back."),
            "spread" to Signal(SignalKind.STRENGTH, "Varied landing depth", "Keep varying depth to stay hard to read."),
            "rally-short" to Signal(SignalKind.FOCUS, "Short rallies", "Work on consistency to extend rallies."),
            "rally-long" to Signal(SignalKind.STRENGTH, "Long rallies", "Stay patient and pick your moment to attack."),
            "tempo-fast" to Signal(SignalKind.FOCUS, "Fast exchanges", "Recovery to base matters when the pace is high."),
            "tempo-slow" to Signal(SignalKind.FOCUS, "Slower exchanges", "Use the time to reset to base and choose your shot."),
            "tempo-steady" to Signal(SignalKind.STRENGTH, "Steady tempo", "Vary the pace to break your opponent's rhythm."),
        )
    }
}
