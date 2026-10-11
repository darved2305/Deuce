package com.elevate.app.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up inputs; the card says only what the report says. */
class ShareCardTest {

    private val heat = LandingHeat(emptyMap(), 0, 0, CoachReport.NO_LANDINGS)
    private fun report(rallies: Int, longest: Int?, suggestions: List<Suggestion> = emptyList(), zoned: Int = 0) =
        CoachReport(heat.copy(zoned = zoned), RallyRhythm(rallies, longest, null, null, null, 0), suggestions)
    private val gated = Suggestion("Tempo", "Tempo: not enough data yet (1 of 6 gaps between hits)", "x", false)
    private val ok = Suggestion("Rally length", "Rallies are mid-length: median 5 hits over 7 rallies. Build each point with depth before you attack.", "x", true)

    @Test fun lines_carry_the_real_counts_and_the_top_available_suggestion() {
        val l = ShareCard.lines(report(7, 9, listOf(gated, ok)), 41)
        assertEquals("Elevate", l.brand)
        assertEquals("Badminton · singles", l.sport)
        assertEquals(
            listOf(ShareCard.Stat("Hits", "41"), ShareCard.Stat("Rallies", "7"), ShareCard.Stat("Longest rally", "9 hits")),
            l.stats,
        )
        assertEquals(ok.text, l.topSuggestion)
        assertEquals("Analysed from one phone video", l.footer)
    }

    @Test fun lines_without_rallies_or_enough_data_say_none_and_drop_the_suggestion() {
        val l = ShareCard.lines(report(0, null, listOf(gated)), 3, "doubles")
        assertEquals("Badminton · doubles", l.sport)
        assertEquals(ShareCard.Stat("Rallies", "0"), l.stats[1])
        assertEquals(ShareCard.Stat("Longest rally", "none"), l.stats[2])
        assertNull(l.topSuggestion)
    }

    @Test fun hasData_needs_hits_rallies_or_landings() {
        assertFalse(ShareCard.hasData(report(0, null), 0))
        assertTrue(ShareCard.hasData(report(0, null), 1))
        assertTrue(ShareCard.hasData(report(2, 3), 0))
        assertTrue(ShareCard.hasData(report(0, null, zoned = 4), 0))
    }

    @Test fun format_follows_the_template() {
        assertEquals("doubles", ShareCard.format("badminton_doubles"))
        assertEquals("singles", ShareCard.format("badminton_singles"))
        assertEquals("singles", ShareCard.format(null))
    }

    @Test fun courtFrame_keeps_proportions_and_centres_in_the_box() {
        // Box 400 wide x 700 tall, doubles court 6.10 x 13.40 m: height is the limit.
        val f = ShareCard.courtFrame(100f, 200f, 400f, 700f, Pair(0.0, 6.10))
        assertEquals(700f, f.height, 0.01f)
        assertEquals(700f * 6.10f / 13.40f, f.width, 0.01f)
        assertEquals(100f + (400f - f.width) / 2, f.left, 0.01f)
        assertEquals(200f, f.top, 0.01f)
    }

    @Test fun courtToPixel_puts_the_near_baseline_at_the_bottom_and_x_left_to_right() {
        val f = ShareCard.courtFrame(0f, 0f, 610f, 1340f, Pair(0.0, 6.10)) // 100 px per metre
        assertEquals(0f, f.px(0.0), 0.01f)
        assertEquals(610f, f.px(6.10), 0.01f)
        assertEquals(1340f, f.py(0.0), 0.01f)   // near baseline: bottom
        assertEquals(0f, f.py(13.40), 0.01f)    // far baseline: top
        assertEquals(670f, f.py(6.70), 0.01f)   // net: middle
        assertEquals(305f, f.px(3.05), 0.01f)
    }

    @Test fun courtToPixel_singles_frame_starts_at_the_negative_inset() {
        val f = ShareCard.courtFrame(0f, 0f, 610f, 1340f, Pair(-0.46, 5.64)) // 6.10 m span
        assertEquals(0f, f.px(-0.46), 0.01f)
        assertEquals(46f, f.px(0.0), 0.01f)
    }

    @Test fun contains_allows_a_small_margin_and_rejects_far_points() {
        val f = ShareCard.courtFrame(0f, 0f, 610f, 1340f, Pair(0.0, 6.10))
        assertTrue(f.contains(3.0, 6.0))
        assertTrue(f.contains(-0.3, 13.6))
        assertFalse(f.contains(-2.0, 5.0))
        assertFalse(f.contains(3.0, 15.0))
    }
}
