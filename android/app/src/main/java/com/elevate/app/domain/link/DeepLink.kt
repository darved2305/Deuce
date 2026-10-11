package com.elevate.app.domain.link

import java.net.URI
import java.net.URISyntaxException

/** Where a link points: a match, optionally a rally of it, optionally a moment in the video. */
data class DeepLinkTarget(val matchId: String, val rallyId: String? = null, val tMs: Long? = null)

/**
 * `elevate://m/{match_id}` and `elevate://m/{match_id}/r/{rally_id}?t_ms={ms}`, the links the AI
 * assistant hands out. Anything else is null. A `t_ms` that is not a whole number of milliseconds
 * (zero or more) is ignored, so the match still opens.
 */
object DeepLink {
    private const val SCHEME = "elevate"
    private const val HOST = "m"
    private val ID = Regex("[A-Za-z0-9._~-]{1,128}")

    fun parse(uri: String?): DeepLinkTarget? {
        if (uri.isNullOrBlank()) return null
        val u = try { URI(uri.trim()) } catch (_: URISyntaxException) { return null }
        if (!SCHEME.equals(u.scheme, ignoreCase = true) || u.host != HOST) return null
        val parts = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val tMs = u.rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("t_ms=") }
            ?.removePrefix("t_ms=")
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
        return when {
            parts.size == 1 && ID.matches(parts[0]) -> DeepLinkTarget(parts[0], null, tMs)
            parts.size == 3 && parts[1] == "r" && ID.matches(parts[0]) && ID.matches(parts[2]) ->
                DeepLinkTarget(parts[0], parts[2], tMs)
            else -> null
        }
    }
}
