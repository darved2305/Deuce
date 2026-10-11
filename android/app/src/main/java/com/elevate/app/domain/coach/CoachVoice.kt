package com.elevate.app.domain.coach

import java.util.Locale

/**
 * The spoken version of the coach report, for the phone's built-in text to speech. Built only from
 * the report: rally counts, the suggestions that have enough data, and one short line for each that
 * does not. Plain words only (no symbols), so the engine reads it naturally.
 */
object CoachVoice {

    const val INTRO = "Here is your match."
    const val NO_RALLIES = "No rallies found yet."

    fun script(report: CoachReport): String {
        val parts = ArrayList<String>()
        parts += INTRO
        parts += rallyLine(report.rhythm)
        for (s in report.suggestions) {
            parts += if (s.enoughData) s.text else "Not enough data yet for ${s.title.lowercase(Locale.ROOT)}."
        }
        return plain(parts.joinToString(" "))
    }

    private fun rallyLine(r: RallyRhythm): String {
        val longest = r.longestHits
        if (r.rallies == 0 || longest == null) return NO_RALLIES
        val rallies = if (r.rallies == 1) "1 rally" else "${r.rallies} rallies"
        val hits = if (longest == 1) "1 hit" else "$longest hits"
        return "$rallies, the longest was $hits."
    }

    /** Spoken words only: "s" after a number becomes "seconds", "/" becomes "of", other symbols go. */
    internal fun plain(text: String): String =
        text
            .replace(Regex("(\\d) s\\b"), "$1 seconds")
            .replace("/", " of ")
            .replace(Regex("[^A-Za-z0-9 .,']"), " ")
            .replace(Regex(" +"), " ")
            .replace(" .", ".")
            .replace(" ,", ",")
            .trim()
}
