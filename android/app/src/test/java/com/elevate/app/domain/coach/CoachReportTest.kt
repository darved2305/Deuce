package com.elevate.app.domain.coach

import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.HitRallies
import com.elevate.app.domain.analysis.RallyCandidate
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logic tests on made-up inputs; none of these numbers are claims about a real match. */
class CoachReportTest {

    private fun hit(t: Long) = HitCandidate(t, 40, "trk", null, listOf("trajectory"), "candidate")
    private fun ev(points: List<LandingPoint>, tier: String? = "B", template: String? = "badminton_singles", total: Int = points.size) =
        LandingEvidence(total, points, template, tier)
    private fun zone(r: ZoneRow, c: Int) = ZoneId(r, c)

    // Far half (y > 6.7) is hit from the near side: left = small x. Back row: y >= 12.4. Front: y <= 8.68.
    private val farBackLeft = LandingPoint(0.8, 13.0)
    private val farBackRight = LandingPoint(4.6, 12.9)
    private val farMidCentre = LandingPoint(2.6, 10.5)
    private val farFrontCentre = LandingPoint(2.6, 7.5)
    // Near half is hit from the far side: the hitter faces -y, so their left is large x.
    private val nearBackLeftOfHitter = LandingPoint(4.5, 0.5)
    private val nearFrontRightOfHitter = LandingPoint(0.7, 5.5)

    @Test fun zoneCounts_use_the_hitters_view_on_both_halves() {
        val heat = CoachReport.heat(ev(listOf(farBackLeft, farBackRight, farMidCentre, farFrontCentre, nearBackLeftOfHitter, nearFrontRightOfHitter)))
        assertEquals(6, heat.zoned)
        assertEquals(0, heat.unzoned)
        assertEquals(2, heat.count(zone(ZoneRow.BACK, 1))) // far back left + near back (hitter's left)
        assertEquals(1, heat.count(zone(ZoneRow.BACK, 3)))
        assertEquals(1, heat.count(zone(ZoneRow.MIDDLE, 2)))
        assertEquals(1, heat.count(zone(ZoneRow.FRONT, 2)))
        assertEquals(1, heat.count(zone(ZoneRow.FRONT, 3)))
        assertEquals(3, heat.rowTotal(ZoneRow.BACK))
        assertEquals("6 landing candidates, tier B court", heat.caption)
    }

    @Test fun zoneCounts_leave_out_beyond_the_grid_and_net_line_and_say_so() {
        val heat = CoachReport.heat(ev(listOf(farBackLeft, LandingPoint(-0.3, 10.0), LandingPoint(2.0, 14.0), LandingPoint(2.0, 6.7))))
        assertEquals(1, heat.zoned)
        assertEquals(3, heat.unzoned)
        assertEquals("1 landing candidate, tier B court · 3 beyond the grid or on the net line, not counted", heat.caption)
    }

    @Test fun zoneCounts_none_without_positions_or_a_known_template() {
        assertEquals(CoachReport.NO_LANDINGS, CoachReport.heat(LandingEvidence.NONE).caption)
        assertEquals(CoachReport.NO_LANDINGS, CoachReport.heat(ev(emptyList(), total = 5)).caption)
        assertEquals(CoachReport.NO_LANDINGS, CoachReport.heat(ev(listOf(farBackLeft), template = "tennis")).caption)
        assertEquals(CoachReport.NO_LANDINGS, CoachReport.heat(ev(listOf(farBackLeft), template = null)).caption)
    }

    @Test fun median_handles_odd_even_and_empty() {
        assertEquals(3.0, CoachReport.median(listOf(5.0, 1.0, 3.0))!!, 1e-9)
        assertEquals(2.5, CoachReport.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 1e-9)
        assertEquals(null, CoachReport.median(emptyList()))
    }

    @Test fun rhythm_counts_longest_median_and_tempo_from_hits_inside_rallies() {
        // Rally A: 0, 1000, 2000 (3 hits). Rally B: 10000, 10800, 11600, 12400, 13200 (5 hits). Lone hit at 30000.
        val hits = listOf(0L, 1000, 2000, 10000, 10800, 11600, 12400, 13200, 30000).map(::hit)
        val rallies = HitRallies.group(hits)
        assertEquals(2, rallies.size)
        val r = CoachReport.rhythm(hits, rallies)
        assertEquals(2, r.rallies)
        assertEquals(5, r.longestHits)
        assertEquals(3.2, r.longestSeconds!!, 1e-9)
        assertEquals(4.0, r.medianHits!!, 1e-9)
        assertEquals(6, r.gaps) // 2 + 4 gaps
        assertEquals(0.8, r.medianGapSeconds!!, 1e-9) // gaps sorted: 0.8 x4, 1, 1 -> median of the middle two
    }

    @Test fun rhythm_empty_without_rallies() {
        val r = CoachReport.rhythm(listOf(hit(0)), emptyList())
        assertEquals(0, r.rallies)
        assertEquals(null, r.longestHits)
        assertEquals(null, r.medianGapSeconds)
    }

    private fun landingsOf(back: Int, mid: Int, front: Int) =
        List(back) { farBackLeft } + List(mid) { farMidCentre } + List(front) { farFrontCentre }

    @Test fun depth_deep_exact_text() {
        val e = ev(landingsOf(18, 9, 6))
        val s = CoachReport.build(e, emptyList(), emptyList()).suggestions[0]
        assertTrue(s.enoughData)
        assertEquals("Most landings are deep: 18 of 33 in the back row. Mix in drops to pull the opponent forward.", s.text)
        assertEquals("33 landing candidates, tier B court", s.evidence)
    }

    @Test fun depth_short_and_spread_exact_text() {
        val short = CoachReport.build(ev(landingsOf(2, 4, 6)), emptyList(), emptyList()).suggestions[0]
        assertEquals("Many landings are short: 6 of 12 in the front row. Add clears to push the opponent back.", short.text)
        val spread = CoachReport.build(ev(landingsOf(3, 5, 2)), emptyList(), emptyList()).suggestions[0]
        assertEquals("Landings are spread: 2 front, 5 middle, 3 back of 10. Keep varying depth to stay hard to read.", spread.text)
    }

    @Test fun depth_is_gated_at_eight_landings() {
        val seven = CoachReport.build(ev(landingsOf(7, 0, 0)), emptyList(), emptyList()).suggestions[0]
        assertFalse(seven.enoughData)
        assertEquals("Landing depth: not enough data yet (7 of 8 landing candidates)", seven.text)
        assertTrue(CoachReport.build(ev(landingsOf(8, 0, 0)), emptyList(), emptyList()).suggestions[0].enoughData)
    }

    private fun ralliesOf(vararg hitCounts: Int) = hitCounts.mapIndexed { i, n -> RallyCandidate(i * 20_000L, i * 20_000L + (n - 1) * 1000L, n) }

    @Test fun rallyLength_short_long_and_mid_exact_text() {
        fun text(vararg n: Int) = CoachReport.build(LandingEvidence.NONE, emptyList(), ralliesOf(*n)).suggestions[1].text
        assertEquals("Rallies are short: median 3 hits over 7 rallies. Work on consistency to extend rallies.", text(2, 3, 3, 3, 4, 2, 5))
        assertEquals("Rallies run long: median 8.5 hits over 4 rallies. Stay patient and pick your moment to attack.", text(8, 9, 12, 5))
        assertEquals("Rallies are mid-length: median 5 hits over 5 rallies. Build each point with depth before you attack.", text(4, 5, 5, 6, 9))
    }

    @Test fun rallyLength_is_gated_at_four_rallies() {
        val s = CoachReport.build(LandingEvidence.NONE, emptyList(), ralliesOf(3, 4, 5)).suggestions[1]
        assertFalse(s.enoughData)
        assertEquals("Rally length: not enough data yet (3 of 4 rally candidates)", s.text)
    }

    private fun tempoText(gapMs: Long, gaps: Int): Suggestion {
        // One rally of gaps+1 hits spaced gapMs apart.
        val hits = (0..gaps).map { hit(it * gapMs) }
        val rallies = listOf(RallyCandidate(0, gaps * gapMs, gaps + 1))
        return CoachReport.build(LandingEvidence.NONE, hits, rallies).suggestions[2]
    }

    @Test fun tempo_fast_slow_and_steady_exact_text() {
        assertEquals("Fast exchanges: 0.9 s between hits (median of 24 gaps). Recovery to base matters.", tempoText(900, 24).text)
        assertEquals("Slower exchanges: 2.0 s between hits (median of 8 gaps). Use the time to reset to base and choose your shot.", tempoText(2000, 8).text)
        assertEquals("Steady tempo: 1.3 s between hits (median of 10 gaps). Vary the pace to break your opponent's rhythm.", tempoText(1300, 10).text)
    }

    @Test fun tempo_is_gated_at_six_gaps() {
        val s = tempoText(900, 5)
        assertFalse(s.enoughData)
        assertEquals("Tempo: not enough data yet (5 of 6 gaps between hits)", s.text)
        assertTrue(tempoText(900, 6).enoughData)
    }

    @Test fun empty_report_has_three_not_enough_lines_and_no_invented_numbers() {
        val r = CoachReport.build(LandingEvidence.NONE, emptyList(), emptyList())
        assertEquals(3, r.suggestions.size)
        assertTrue(r.suggestions.none { it.enoughData })
        assertEquals("Landing depth: not enough data yet (0 of 8 landing candidates)", r.suggestions[0].text)
        assertEquals(0, r.rhythm.rallies)
    }

    @Test fun no_copy_claims_winners_or_in_out_or_uses_em_dashes() {
        val r = CoachReport.build(ev(landingsOf(18, 9, 6)), (0..24).map { hit(it * 900L) }, ralliesOf(2, 3, 3, 3, 4, 2, 5))
        val all = r.suggestions.joinToString(" ") { it.text + " " + it.evidence } + r.heat.caption
        assertFalse(all.contains(0x2014.toChar()))
        assertFalse(Regex("\\b(won|wins|winner|out)\\b", RegexOption.IGNORE_CASE).containsMatchIn(all))
    }
}
