package com.elevate.app.domain.profile

import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.RallyCandidate
import com.elevate.app.domain.coach.CoachReport
import com.elevate.app.domain.coach.LandingEvidence
import com.elevate.app.domain.coach.LandingPoint
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerProfileTest {

    private fun z(s: String) = ZoneId.parse(s)!!

    private fun match(
        id: String,
        date: Long,
        durationMs: Long? = 600_000,
        hits: Int = 0,
        rallies: List<Int> = emptyList(),
        gaps: List<Double> = emptyList(),
        zones: Map<String, Int> = emptyMap(),
        reflex: Int? = null,
        readLab: ReadLabStat? = null,
    ) = MatchSummaryInput(
        id = id, title = id, dateMs = date, durationMs = durationMs, hits = hits,
        rallyHitCounts = rallies, gapsSeconds = gaps,
        zoneCounts = zones.mapKeys { z(it.key) }, reflexBest = reflex, readLab = readLab,
    )

    @Test
    fun emptyInputIsEmptyAndInventsNothing() {
        val p = PlayerProfile.build(emptyList())
        assertThat(p.isEmpty).isTrue()
        assertThat(p.totals).isEqualTo(Totals(0, 0, 0, 0, 0))
        assertThat(p.placement.isEmpty).isTrue()
        assertThat(p.placement.cells).hasSize(9)
        assertThat(p.tags).isEmpty()
        assertThat(p.tagsHint).isEqualTo(PlayerProfile.TAGS_HINT)
        assertThat(p.trends.all { it.points.isEmpty() && !it.shown && it.message == null }).isTrue()
        assertThat(p.training.isEmpty).isTrue()
        assertThat(p.training.readLabExactPercent).isNull()
        assertThat(p.focus).isEqualTo(FocusAreas(null, null))
        assertThat(PlayerProfile.EMPTY_TITLE).isEqualTo("Analyse your first match to start your profile")
    }

    @Test
    fun totalsSumMatchesMinutesHitsAndRallies() {
        val p = PlayerProfile.build(
            listOf(
                match("a", 1, durationMs = 90_000, hits = 10, rallies = listOf(3, 4)),
                match("b", 2, durationMs = 150_000, hits = 7, rallies = listOf(5)),
                match("c", 3, durationMs = null, hits = 1),
            ),
        )
        assertThat(p.totals.matches).isEqualTo(3)
        assertThat(p.totals.minutes).isEqualTo(4) // 240 s
        assertThat(p.totals.matchesWithoutDuration).isEqualTo(1)
        assertThat(p.totals.hits).isEqualTo(18)
        assertThat(p.totals.rallies).isEqualTo(3)
    }

    @Test
    fun zoneSharesKeepCountAndDenominator() {
        val p = PlayerProfile.build(
            listOf(
                match("a", 1, zones = mapOf("B1" to 6, "F2" to 2)),
                match("b", 2, zones = mapOf("B1" to 2, "M3" to 4)),
                match("c", 3), // no landings: does not count as a placement match
            ),
        )
        val pl = p.placement
        assertThat(pl.total).isEqualTo(14)
        assertThat(pl.matches).isEqualTo(2)
        assertThat(pl.cell(z("B1"))).isEqualTo(ZoneShare(z("B1"), 8, 14))
        assertThat(pl.cell(z("B1")).percent).isEqualTo(57)
        assertThat(pl.cell(z("F2")).n).isEqualTo(2)
        assertThat(pl.cell(z("B2")).n).isEqualTo(0)
        assertThat(pl.cells.sumOf { it.n }).isEqualTo(14)
        assertThat(pl.rowTotal(ZoneRow.BACK)).isEqualTo(8)
    }

    @Test
    fun deepHitterNeedsAtLeast15LandingsAndFiftyFivePercent() {
        // 18 of 30 = 60%: tag with the exact evidence string.
        val tagged = PlayerProfile.build(listOf(match("a", 1, zones = mapOf("B1" to 10, "B3" to 8, "M2" to 12))))
        assertThat(tagged.tags.map { it.id }).containsExactly(StyleTagId.DEEP_HITTER)
        assertThat(tagged.tags.single().evidence).isEqualTo("18 of 30 landings deep")
        assertThat(tagged.tags.single().label).isEqualTo("Deep hitter")

        // 11 of 20 = exactly 55%: counts (no float rounding trap).
        val edge = PlayerProfile.build(listOf(match("a", 1, zones = mapOf("B2" to 11, "M2" to 9))))
        assertThat(edge.tags.single().evidence).isEqualTo("11 of 20 landings deep")

        // 54%: no tag. 8 of 14 deep but only 14 landings: no tag.
        val below = PlayerProfile.build(listOf(match("a", 1, zones = mapOf("B2" to 27, "M2" to 23))))
        assertThat(below.tags).isEmpty()
        val tooFew = PlayerProfile.build(listOf(match("a", 1, zones = mapOf("B2" to 13, "M2" to 1))))
        assertThat(tooFew.tags).isEmpty()
        assertThat(tooFew.tagsHint).isEqualTo(PlayerProfile.TAGS_HINT)
    }

    @Test
    fun netPlayerGate() {
        val p = PlayerProfile.build(listOf(match("a", 1, zones = mapOf("F1" to 6, "F3" to 6, "M2" to 18))))
        assertThat(p.tags.single().id).isEqualTo(StyleTagId.NET_PLAYER)
        assertThat(p.tags.single().evidence).isEqualTo("12 of 30 landings near the net")
        val low = PlayerProfile.build(listOf(match("a", 1, zones = mapOf("F1" to 11, "M2" to 19))))
        assertThat(low.tags).isEmpty()
    }

    @Test
    fun rallyBuilderAndShortRalliesUsePooledMedianAndMinimumRallies() {
        val builder = PlayerProfile.build(listOf(match("a", 1, rallies = listOf(8, 9)), match("b", 2, rallies = listOf(7, 10))))
        assertThat(builder.tags.single().id).isEqualTo(StyleTagId.RALLY_BUILDER)
        assertThat(builder.tags.single().evidence).isEqualTo("Median rally 8.5 hits over 4 rallies")

        val exact7 = PlayerProfile.build(listOf(match("a", 1, rallies = listOf(6, 7, 7, 9))))
        assertThat(exact7.tags.single().evidence).isEqualTo("Median rally 7 hits over 4 rallies")

        val tooFew = PlayerProfile.build(listOf(match("a", 1, rallies = listOf(9, 9, 9))))
        assertThat(tooFew.tags).isEmpty()

        val short = PlayerProfile.build(listOf(match("a", 1, rallies = listOf(2, 3, 3, 5))))
        assertThat(short.tags.single().id).isEqualTo(StyleTagId.SHORT_RALLIES)
        assertThat(short.tags.single().evidence).isEqualTo("Median rally 3 hits over 4 rallies")

        val mid = PlayerProfile.build(listOf(match("a", 1, rallies = listOf(5, 5, 5, 5))))
        assertThat(mid.tags).isEmpty()
    }

    @Test
    fun fastExchangesNeedsMedianUnderOneSecondAndSixGaps() {
        val fast = PlayerProfile.build(listOf(match("a", 1, gaps = listOf(0.7, 0.8, 0.9, 0.8, 0.8, 1.4))))
        assertThat(fast.tags.single().id).isEqualTo(StyleTagId.FAST_EXCHANGES)
        assertThat(fast.tags.single().evidence).isEqualTo("Median gap 0.8 s over 6 gaps")

        val atOne = PlayerProfile.build(listOf(match("a", 1, gaps = List(6) { 1.0 })))
        assertThat(atOne.tags).isEmpty()
        val few = PlayerProfile.build(listOf(match("a", 1, gaps = List(5) { 0.5 })))
        assertThat(few.tags).isEmpty()
    }

    @Test
    fun trendShownOnlyWithTwoOrMoreMatchesOfThatMetric() {
        val one = PlayerProfile.build(listOf(match("a", 1, hits = 12)))
        val hits1 = one.trends.first { it.metric == TrendMetric.HITS }
        assertThat(hits1.shown).isFalse()
        assertThat(hits1.message).isEqualTo("Trend appears after your 2nd analysed match")

        val two = PlayerProfile.build(
            listOf(
                match("b", 20, hits = 30, rallies = listOf(4, 4, 6, 6), reflex = 400),
                match("a", 10, hits = 12, rallies = listOf(3, 3, 3, 3)),
            ),
        )
        val hits = two.trends.first { it.metric == TrendMetric.HITS }
        assertThat(hits.shown).isTrue()
        assertThat(hits.message).isNull()
        assertThat(hits.points.map { it.value }).containsExactly(12.0, 30.0).inOrder() // time order
        val rally = two.trends.first { it.metric == TrendMetric.MEDIAN_RALLY }
        assertThat(rally.points.map { it.value }).containsExactly(3.0, 5.0).inOrder()
        // Only one match has a Reflex score: no line.
        val reflex = two.trends.first { it.metric == TrendMetric.REFLEX_BEST }
        assertThat(reflex.shown).isFalse()
        assertThat(reflex.message).isEqualTo(Trend.TREND_MESSAGE)
        // No gaps anywhere: nothing to show or say.
        val gap = two.trends.first { it.metric == TrendMetric.MEDIAN_GAP }
        assertThat(gap.points).isEmpty()
        assertThat(gap.message).isNull()
    }

    @Test
    fun perMatchTrendValueNeedsMinimumEvidence() {
        val p = PlayerProfile.build(
            listOf(
                match("a", 1, rallies = listOf(9, 9, 9)), // 3 rallies: below the minimum, no point
                match("b", 2, rallies = listOf(9, 9, 9, 9)),
            ),
        )
        assertThat(p.trends.first { it.metric == TrendMetric.MEDIAN_RALLY }.points).hasSize(1)
    }

    @Test
    fun trainingStatsUseOnlyStoredResults() {
        val p = PlayerProfile.build(
            listOf(
                match("a", 1, reflex = 500, readLab = ReadLabStat(exact = 3, adjacent = 1, scored = 5)),
                match("b", 2, reflex = 420, readLab = ReadLabStat(exact = 5, adjacent = 0, scored = 5)),
                match("c", 3),
                match("d", 4, readLab = ReadLabStat(0, 0, 0)), // nothing scored: not counted
            ),
        )
        val t = p.training
        assertThat(t.reflexMatches).isEqualTo(2)
        assertThat(t.reflexMax).isEqualTo(500)
        assertThat(t.reflexLatest).isEqualTo(420)
        assertThat(t.readLabMatches).isEqualTo(2)
        assertThat(t.readLabExact).isEqualTo(8)
        assertThat(t.readLabScored).isEqualTo(10)
        assertThat(t.readLabExactPercent).isEqualTo(80)
    }

    @Test
    fun repeatedSuggestionIsCountedAcrossMatchesWithEnoughData() {
        // Deep landings in 3 of the 4 matches that have 8+ landings; one match has too few to say anything.
        val deep = mapOf("B1" to 6, "B2" to 2, "M1" to 2)
        val spread = mapOf("B1" to 3, "M2" to 3, "F2" to 3)
        val p = PlayerProfile.build(
            listOf(
                match("a", 1, zones = deep),
                match("b", 2, zones = deep),
                match("c", 3, zones = spread),
                match("d", 4, zones = deep),
                match("e", 5, zones = mapOf("B1" to 3)),
            ),
        )
        val focus = p.focus.focus!!
        assertThat(focus.key).isEqualTo("deep")
        assertThat(focus.seen).isEqualTo(3)
        assertThat(focus.of).isEqualTo(4)
        assertThat(focus.evidence).isEqualTo("Seen in 3 of 4 matches")
        assertThat(focus.advice).isEqualTo("Mix in drops to pull the opponent forward.")
        // The spread pattern is seen once only: not offered.
        assertThat(p.focus.strength).isNull()
    }

    @Test
    fun patternSeenInOnlyOneMatchIsNotOffered() {
        val p = PlayerProfile.build(listOf(match("a", 1, rallies = listOf(2, 2, 2, 2), gaps = List(6) { 0.5 })))
        assertThat(p.focus).isEqualTo(FocusAreas(null, null))
    }

    @Test
    fun strengthAndFocusComeFromDifferentRules() {
        val m = { id: String, d: Long -> match(id, d, rallies = listOf(9, 9, 9, 9), gaps = List(6) { 1.2 }) }
        val p = PlayerProfile.build(listOf(m("a", 1), m("b", 2)))
        assertThat(p.focus.strength).isNotNull()
        assertThat(p.focus.focus).isNull() // long rallies and steady tempo are strengths, nothing to fix
        assertThat(p.focus.strength!!.evidence).isEqualTo("Seen in 2 of 2 matches")
    }

    @Test
    fun focusAgreesWithCoachReportsOwnRules() {
        // Same 10 landings through CoachReport: 8 deep says "Most landings are deep".
        val pts = List(8) { LandingPoint(1.0 + it * 0.1, 12.5) } + List(2) { LandingPoint(3.0, 9.0) }
        val heat = CoachReport.heat(LandingEvidence(10, pts, "badminton_singles", "A"))
        val report = CoachReport.build(LandingEvidence(10, pts, "badminton_singles", "A"), emptyList(), emptyList())
        assertThat(report.suggestions.first().text).startsWith("Most landings are deep")
        val input = ProfileInputs.fromParts("a", "a", 1, 60_000, emptyList(), heat)
        val p = PlayerProfile.build(listOf(input, input.copy(id = "b", dateMs = 2)))
        assertThat(p.focus.focus!!.key).isEqualTo("deep")
        assertThat(p.placement.total).isEqualTo(heat.zoned * 2)
    }

    @Test
    fun inputsComputeGapsInsideRalliesOnly() {
        fun hit(t: Long) = HitCandidate(t, 10, null, null, emptyList(), "candidate")
        val hits = listOf(hit(1000), hit(2000), hit(3500), hit(20_000))
        val rallies = listOf(RallyCandidate(1000, 3500, 3))
        assertThat(ProfileInputs.gapsSeconds(hits, rallies)).containsExactly(1.0, 1.5).inOrder()
        val input = ProfileInputs.fromParts("a", "a", 5, 0, hits, heat = null, rallies = rallies)
        assertThat(input.hits).isEqualTo(4)
        assertThat(input.rallyHitCounts).containsExactly(3)
        assertThat(input.durationMs).isNull()
        assertThat(input.zoneCounts).isEmpty()
    }
}
