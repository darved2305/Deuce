package com.elevate.app.ui.review

import android.content.Context
import androidx.core.content.edit
import com.elevate.app.domain.review.LineCallText
import com.elevate.app.ui.components.Explanation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which landing Instant Review shows. Lives in the ViewModel, so it survives rotation and a trip back to the overview. */
class ReviewSession {
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()
    fun select(eventId: String?) { _selected.value = eventId }
}

/** The player's own answer to a call. Never an official decision, and never sent anywhere. */
enum class YourCall(val wire: String) {
    Agree("agree"), Disagree("disagree");

    companion object {
        fun decode(raw: String?): YourCall? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * Agree / Disagree, kept on this phone (SharedPreferences `elevate_review`, key `call:<matchId>:<eventId>`).
 * The review-confirm route of the API confirms a point WINNER, which is a different thing, so this stays local.
 */
object ReviewCalls {
    private const val FILE = "elevate_review"
    fun key(matchId: String?, eventId: String) = "call:${matchId ?: "-"}:$eventId"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, matchId: String?, eventId: String): YourCall? =
        YourCall.decode(prefs(context).getString(key(matchId, eventId), null))

    fun save(context: Context, matchId: String?, eventId: String, call: YourCall) {
        prefs(context).edit { putString(key(matchId, eventId), call.wire) }
    }
}

/** What each part of the proof means, shown on a long press. */
object ReviewExplanations {
    val Corners = Explanation(
        "Measured court",
        "You tapped the four corners of the court. That lets the app turn pixels into real metres on the floor. The grade says how well the lines fit.",
    )
    val Flight = Explanation(
        "Following the shuttle",
        "The shuttle is found in every video frame. Its path just before the floor shows where it came down. Only frames where it was really seen are drawn.",
    )
    val Measure = Explanation(
        "Measuring the gap",
        "The landing spot is placed on the court and its distance to the line is measured. The oval is the margin of error: the real spot is very likely inside it.",
    )
    val Margin = Explanation(
        "Margin of error",
        "Our measurement is never exact. This is how far off it could be, with about 95 percent confidence. If it reaches the line, we say it is too close to call.",
    )
    val YourCall = Explanation(
        "Your call",
        "Say whether you agree. It is saved on this phone only. It is your note, not an official decision.",
    )
    val Steps = Explanation(
        "Moment, line, call",
        "Three beats: the frozen moment, a zoom to the line, then the result. Tap one to jump to it.",
    )

    val all: List<Explanation> = listOf(Corners, Flight, Measure, Margin, YourCall, Steps)
}

/** The words a person reads under the step pictures; the grade is filled in only when the record has one. */
object ReviewSteps {
    fun corners(tier: String?): String =
        "Your 4 corner taps turned the video into a measured court" + (tier?.let { " (quality grade $it)" } ?: "") + "."
    const val FLIGHT = "We followed the shuttle's flight frame by frame to where it stopped."
    const val MEASURE = "We mapped that spot onto the court and measured the distance to the line, with an honest margin of error."
    const val TITLE = "How we know"
    const val CALL_SAVED = "Saved on this phone only. It is your note, not an official decision."
    const val CALL_QUESTION = "Do you agree with this?"
    val SMALL_PRINT = LineCallText.SMALL_PRINT

    /** "0:27.3" */
    fun clock(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return java.lang.String.format(java.util.Locale.ROOT, "%d:%04.1f", t / 60_000, (t % 60_000) / 1000.0)
    }

    /** Splits `**bold**` markers into (text, bold) runs. An unmatched marker is kept as plain text. */
    fun runs(markup: String): List<Pair<String, Boolean>> {
        val parts = markup.split("**")
        if (parts.size % 2 == 0) return listOf(markup to false)
        return parts.mapIndexedNotNull { i, s -> if (s.isEmpty()) null else s to (i % 2 == 1) }
    }
}
