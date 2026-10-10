package com.elevate.app.data.auth

import com.elevate.app.data.session.BytesBody
import com.elevate.app.data.session.HttpRequest
import com.elevate.app.data.session.HttpTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The signed-in Supabase user's tokens. Never logged, never in an exception message. */
@Serializable
data class AuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    /** Epoch seconds. */
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("user_id") val userId: String,
    @SerialName("is_anonymous") val isAnonymous: Boolean,
) {
    override fun toString() = "AuthTokens(user=$userId, anonymous=$isAnonymous, expiresAt=$expiresAt)"
}

/** Sign-in or refresh failed. [status] is the GoTrue HTTP status (0 = no response). */
class AuthException(val status: Int, message: String) : Exception(message)

/** Supplies the bearer token every API call sends. */
interface AccessTokenProvider {
    /** A token valid for at least [SupabaseAuth.REFRESH_MARGIN_S] more seconds; signs in anonymously if needed. */
    suspend fun accessToken(): String

    /** The API answered 401 to [rejected]: refresh once (single-flight) and return the new token. */
    suspend fun refreshAfterUnauthorized(rejected: String): String
}

/**
 * Anonymous sign-in and token refresh against Supabase Auth (GoTrue) REST, with the publishable key
 * only (contracts §1):
 * - `POST {supabaseUrl}/auth/v1/signup` body `{}` → `{access_token, refresh_token, expires_in, expires_at?, user}`
 * - `POST {supabaseUrl}/auth/v1/token?grant_type=refresh_token` body `{refresh_token}`
 *
 * One [Mutex] makes sign-in and refresh single-flight: concurrent callers wait for one request.
 * Tokens refresh [REFRESH_MARGIN_S] before expiry. A rejected refresh token is not replaced by a new
 * anonymous user silently (that would orphan the user's sessions): it throws [AuthException] and
 * the caller decides ([signOut] then sign in again).
 */
class SupabaseAuth(
    supabaseUrl: String,
    private val publishableKey: String,
    private val transport: HttpTransport,
    private val store: TokenStore,
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AccessTokenProvider {

    private val base = supabaseUrl.trimEnd('/')
    private val mutex = Mutex()
    private var cached: AuthTokens? = null

    init {
        require(base.isNotBlank()) { "Supabase URL is not configured (local.properties elevate.supabaseUrl)" }
        require(publishableKey.isNotBlank()) { "Supabase publishable key is not configured" }
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val t = current()
        val fresh = when {
            t == null -> signUp()
            t.expiresAt - nowS() <= REFRESH_MARGIN_S -> refresh(t)
            else -> t
        }
        fresh.accessToken
    }

    override suspend fun refreshAfterUnauthorized(rejected: String): String = mutex.withLock {
        val t = current() ?: return@withLock signUp().accessToken
        // Another caller refreshed while this request was in flight: use its token.
        if (t.accessToken != rejected) return@withLock t.accessToken
        refresh(t).accessToken
    }

    /** The current user, if signed in (no network). */
    suspend fun currentUser(): AuthTokens? = mutex.withLock { current() }

    suspend fun signOut() = mutex.withLock {
        cached = null
        store.clear()
    }

    private fun current(): AuthTokens? = cached ?: store.load()?.also { cached = it }

    private suspend fun signUp(): AuthTokens =
        post("$base/auth/v1/signup", "{}", what = "anonymous sign-in")

    private suspend fun refresh(t: AuthTokens): AuthTokens {
        val body = buildJsonObject { put("refresh_token", t.refreshToken) }.toString()
        return post("$base/auth/v1/token?grant_type=refresh_token", body, what = "token refresh")
    }

    private suspend fun post(url: String, json: String, what: String): AuthTokens = withContext(io) {
        val res = try {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = url,
                    headers = mapOf("apikey" to publishableKey, "Accept" to "application/json"),
                    body = BytesBody(json.toByteArray()),
                ),
            )
        } catch (e: java.io.IOException) {
            throw AuthException(0, "$what failed: ${e.message}")
        }
        if (res.status !in 200..299) {
            // GoTrue errors are `{code|error_code, msg|error_description}`; only the code is kept.
            val code = runCatching {
                val o = parser.parseToJsonElement(res.text()) as kotlinx.serialization.json.JsonObject
                (o["error_code"] ?: o["error"] ?: o["code"])?.toString()?.trim('"')
            }.getOrNull()
            throw AuthException(res.status, "$what failed: HTTP ${res.status}${code?.let { " ($it)" } ?: ""}")
        }
        val r = runCatching { parser.decodeFromString(GoTrueSession.serializer(), res.text()) }
            .getOrElse { throw AuthException(res.status, "$what: unexpected response") }
        val tokens = AuthTokens(
            accessToken = r.accessToken,
            refreshToken = r.refreshToken,
            expiresAt = r.expiresAt ?: (nowS() + r.expiresIn),
            userId = r.user.id,
            isAnonymous = r.user.isAnonymous ?: false,
        )
        store.save(tokens)
        cached = tokens
        tokens
    }

    @Serializable
    private data class GoTrueSession(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("expires_at") val expiresAt: Long? = null,
        val user: GoTrueUser,
    )

    @Serializable
    private data class GoTrueUser(val id: String, @SerialName("is_anonymous") val isAnonymous: Boolean? = null)

    companion object {
        const val REFRESH_MARGIN_S = 60L
        private val parser = Json { ignoreUnknownKeys = true }
    }
}
