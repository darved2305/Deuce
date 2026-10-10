package com.elevate.app.data.session

import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.ErrorEnvelope
import com.elevate.app.data.auth.AccessTokenProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** What the client can do about an API error. */
enum class ApiErrorKind { Unauthorized, Forbidden, NotFound, Conflict, TooLarge, Invalid, RateLimited, Paused, Server, Other }

/**
 * An `error.envelope.v1` answer (contracts §1), or a non-envelope HTTP error ([code] = `http_<status>`).
 * The message is the server's own and never contains tokens or presigned URLs.
 */
class ApiException(
    val status: Int,
    val code: String,
    message: String,
    val details: JsonElement? = null,
    val retryAfterS: Double? = null,
) : Exception("HTTP $status $code: $message") {
    val kind: ApiErrorKind = when {
        status == 401 -> ApiErrorKind.Unauthorized
        status == 403 -> ApiErrorKind.Forbidden
        status == 404 -> ApiErrorKind.NotFound
        status == 409 -> ApiErrorKind.Conflict
        status == 413 -> ApiErrorKind.TooLarge
        status == 400 || status == 422 -> ApiErrorKind.Invalid
        status == 429 -> ApiErrorKind.RateLimited
        status == 503 && code == "uploads_paused" -> ApiErrorKind.Paused
        status >= 500 -> ApiErrorKind.Server
        else -> ApiErrorKind.Other
    }

    /** Worth retrying the same request later without user action. */
    val transient: Boolean get() = kind == ApiErrorKind.Server || (kind == ApiErrorKind.RateLimited && code != "daily_upload_limit" && code != "too_many_part_requests" && code != "storage_budget_reached")

    companion object {
        fun from(status: Int, body: String): ApiException {
            val env = runCatching { Contracts.json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()
            return if (env != null) ApiException(status, env.error.code, env.error.message, env.error.details, env.retryAfterS)
            else ApiException(status, "http_$status", "no error envelope")
        }
    }
}

/**
 * Authenticated JSON calls to `{apiBaseUrl}/v1/...` (contracts §1): `Authorization: Bearer <jwt>` +
 * `apikey` on every call, `Idempotency-Key` when given.
 *
 * - 401: refresh the token once (single-flight in [AccessTokenProvider]) and retry once.
 * - 5xx, transient 429 (with `retry_after_s` ≤ [maxRetryAfterS]) and transport failures: retried up
 *   to [maxAttempts] in total with exponential backoff. Retrying is safe: creates carry an
 *   Idempotency-Key, complete/abort are idempotent, and part-URL requests are bounded server-side (G4).
 * - Every other status throws [ApiException] at once.
 */
class ElevateHttp(
    apiBaseUrl: String,
    private val publishableKey: String,
    private val auth: AccessTokenProvider,
    private val transport: HttpTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 3,
    private val maxRetryAfterS: Double = 30.0,
) {
    private val base = apiBaseUrl.trimEnd('/')

    init {
        require(base.isNotBlank()) { "API base URL is not configured (local.properties elevate.apiBaseUrl)" }
    }

    /** Returns the 2xx body text. [path] starts with `/v1/`. */
    suspend fun call(method: String, path: String, jsonBody: String? = null, idempotencyKey: String? = null): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return callOnce(method, path, jsonBody, idempotencyKey)
            } catch (e: ApiException) {
                val retryAfter = e.retryAfterS
                val waitable = retryAfter == null || retryAfter <= maxRetryAfterS
                if (!e.transient || !waitable || attempt >= maxAttempts) throw e
                sleep(maxOf(backoffMs(attempt), ((retryAfter ?: 0.0) * 1000).toLong()))
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                sleep(backoffMs(attempt))
            }
        }
    }

    private fun backoffMs(attempt: Int) = 500L shl (attempt - 1).coerceAtMost(6)

    private suspend fun callOnce(method: String, path: String, jsonBody: String?, idempotencyKey: String?): String {
        var token = auth.accessToken()
        var res = send(method, path, jsonBody, idempotencyKey, token)
        if (res.status == 401) {
            token = auth.refreshAfterUnauthorized(token)
            res = send(method, path, jsonBody, idempotencyKey, token)
        }
        if (res.status in 200..299) return res.text()
        throw ApiException.from(res.status, res.text())
    }

    private suspend fun send(method: String, path: String, jsonBody: String?, idempotencyKey: String?, token: String) =
        withContext(io) {
            val headers = buildMap {
                put("Authorization", "Bearer $token")
                put("apikey", publishableKey)
                put("Accept", "application/json")
                if (idempotencyKey != null) put("Idempotency-Key", idempotencyKey)
            }
            transport.execute(HttpRequest(method, base + path, headers, jsonBody?.let { BytesBody(it.toByteArray()) }))
        }
}
