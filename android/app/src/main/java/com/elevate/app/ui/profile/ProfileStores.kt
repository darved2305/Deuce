package com.elevate.app.ui.profile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.elevate.app.domain.profile.ReadLabStat
import com.elevate.app.ui.readlab.ReadLabState

/** The name shown on "Your game", kept in this app's own preferences on the phone. Never sent anywhere. */
object ProfileName {
    private const val FILE = "elevate_profile"
    private const val KEY = "name"
    const val DEFAULT = "You"
    const val MAX_LENGTH = 24

    fun get(context: Context): String =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT

    fun save(context: Context, name: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY, name.take(MAX_LENGTH)) }
    }

    /** The letter on the Home avatar. */
    fun initial(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "Y"
}

/**
 * ReadLab's first finished run on each match, saved on the phone (SharedPreferences `elevate_readlab`,
 * key `first:<matchId>`, value `exact,adjacent,scored`). Confirmed landings only, and only the first run
 * is kept: a repeat of the same drills measures memory, not reading. ReadLab did not persist results
 * before this, so matches played earlier have none.
 */
object ReadLabResults {
    private const val FILE = "elevate_readlab"

    private fun key(matchId: String) = "first:$matchId"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, matchId: String): ReadLabStat? = decode(prefs(context).getString(key(matchId), null))

    /** Stores [stat] unless a first run is already stored. Returns true when it was written. */
    fun saveFirst(context: Context, matchId: String, stat: ReadLabStat): Boolean {
        val p = prefs(context)
        if (p.contains(key(matchId))) return false
        p.edit { putString(key(matchId), encode(stat)) }
        return true
    }

    fun encode(s: ReadLabStat) = "${s.exact},${s.adjacent},${s.scored}"

    fun decode(raw: String?): ReadLabStat? {
        val p = raw?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        if (p.size != 3 || p.any { it < 0 } || p[0] + p[1] > p[2]) return null
        return ReadLabStat(p[0], p[1], p[2])
    }
}

/**
 * Saves the first finished ReadLab run of [matchId] for the profile. Draws nothing. Only confirmed
 * landings count (engine estimates are left out), and a run with no scored read is not saved.
 */
@Composable
fun RecordReadLabResult(state: ReadLabState, matchId: String?) {
    val session by state.session.collectAsState()
    val context = LocalContext.current
    val finished = session?.finished == true
    LaunchedEffect(finished, matchId) {
        val s = session
        if (finished && s != null && !matchId.isNullOrBlank()) {
            val c = s.summary.confirmed
            if (c.scored > 0) ReadLabResults.saveFirst(context, matchId, ReadLabStat(c.exact, c.adjacent, c.scored))
        }
    }
}
