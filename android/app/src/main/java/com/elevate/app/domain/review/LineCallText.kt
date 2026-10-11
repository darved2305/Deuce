package com.elevate.app.domain.review

/**
 * The plain words for a [LineCall]. Pure strings, no UI: the screen renders `**bold**` markers as bold.
 * No IN / OUT jargon in the sentence: it says how far, which line, and how sure we are.
 */
object LineCallText {
    const val SMALL_PRINT = "A model estimate. You make the final call."
    const val MOMENT_CAPTION = "The shuttle touches the floor here"
    const val NOT_SEEN = "We didn't see this one land clearly, so no call."

    fun badge(v: Verdict): String = when (v) {
        Verdict.Inside -> "In"
        Verdict.Outside -> "Out"
        Verdict.TooClose -> "Too close"
        Verdict.NotSeen -> "Not seen"
    }

    private fun cm(n: Int) = if (n < 1) "under 1 cm" else "$n cm"

    /** The result sentence with `**bold**` markers. */
    fun markup(call: LineCall): String {
        val line = call.line?.plainName ?: "line"
        return when (call.verdict) {
            Verdict.Inside -> "Landed **${cm(call.gapCm)} inside** the $line. Even with our margin of error (±${call.marginCm} cm) it stays in."
            Verdict.Outside -> "Landed **${cm(call.gapCm)} past** the $line. Clearly out."
            Verdict.TooClose -> "**Too close to call:** ${cm(call.gapCm)} from the line, and our margin is ±${call.marginCm} cm. We won't guess."
            Verdict.NotSeen -> NOT_SEEN
        }
    }

    /** [markup] without the markers, for screen readers and tests. */
    fun plain(call: LineCall): String = markup(call).replace("**", "")

    /** One honest extra line under the result, or null when there is nothing more to say. */
    fun note(call: LineCall): String? = when {
        call.verdict == Verdict.NotSeen -> call.notSeen?.let { "Why: ${it.text}." }
        call.usedFallbackSigma ->
            "No margin was recorded for this landing, so we used the typical error of a grade ${call.tier} court (±${call.marginCm} cm)."
        else -> null
    }
}
