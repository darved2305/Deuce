package com.elevate.app.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up inputs; the script must say only what the report says. */
class CoachVoiceTest {

    private val heat = LandingHeat(emptyMap(), 0, 0, CoachReport.NO_LANDINGS)
    private fun rhythm(rallies: Int, longest: Int?) = RallyRhythm(rallies, longest, longest?.let { it * 1.5 }, null, null, 0)
    private fun ok(title: String, text: String) = Suggestion(title, text, "evidence", true)
    private fun gated(title: String) = Suggestion(title, "$title: not enough data yet (2 of 8 things)", "2 of 8 things needed", false)

    @Test fun script_is_exact_for_a_report_with_available_and_gated_suggestions() {
        val report = CoachReport(
            heat, rhythm(7, 9),
            listOf(
                ok("Landing depth", "Most landings are deep: 6 of 9 in the back row. Mix in drops to pull the opponent forward."),
                gated("Rally length"),
                ok("Tempo", "Steady tempo: 1.2 s between hits (median of 8 gaps). Vary the pace to break your opponent's rhythm."),
            ),
        )
        assertEquals(
            "Here is your match. 7 rallies, the longest was 9 hits. " +
                "Most landings are deep 6 of 9 in the back row. Mix in drops to pull the opponent forward. " +
                "Not enough data yet for rally length. " +
                "Steady tempo 1.2 seconds between hits median of 8 gaps. Vary the pace to break your opponent's rhythm.",
            CoachVoice.script(report),
        )
    }

    @Test fun every_suggestion_gated_gives_one_short_line_each() {
        val report = CoachReport(heat, rhythm(0, null), listOf(gated("Landing depth"), gated("Rally length"), gated("Tempo")))
        assertEquals(
            "Here is your match. No rallies found yet. Not enough data yet for landing depth. " +
                "Not enough data yet for rally length. Not enough data yet for tempo.",
            CoachVoice.script(report),
        )
    }

    @Test fun singular_rally_and_hit() {
        val s = CoachVoice.script(CoachReport(heat, rhythm(1, 1), emptyList()))
        assertEquals("Here is your match. 1 rally, the longest was 1 hit.", s)
    }

    @Test fun real_report_script_has_no_symbols() {
        val hits = (0 until 12).map { com.elevate.app.domain.analysis.HitCandidate(it * 900L, 40, "t", null, listOf("trajectory"), "candidate") }
        val rallies = listOf(
            com.elevate.app.domain.analysis.RallyCandidate(0, 3600, 5),
            com.elevate.app.domain.analysis.RallyCandidate(3600, 8100, 6),
        )
        val points = (0 until 9).map { LandingPoint(1.0 + it * 0.3, 12.8) }
        val report = CoachReport.build(LandingEvidence(9, points, "badminton_singles", "B"), hits, rallies)
        val script = CoachVoice.script(report)
        assertTrue(script, script.startsWith("Here is your match. 2 rallies, the longest was 6 hits. "))
        assertTrue(script, script.matches(Regex("[A-Za-z0-9 .,']+")))
        assertFalse(script.contains("/"))
        assertFalse(script.contains(" s "))
        assertTrue(script, script.contains("Not enough data yet for rally length."))
    }

    @Test fun plain_turns_slashes_and_unit_s_into_words() {
        assertEquals("3 of 8 and 1.5 seconds", CoachVoice.plain("3/8 and 1.5 s"))
        assertEquals("Fine ok.", CoachVoice.plain("Fine: ok · ."))
    }
}
