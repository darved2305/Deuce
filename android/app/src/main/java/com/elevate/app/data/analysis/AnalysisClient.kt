package com.elevate.app.data.analysis

import com.elevate.app.data.api.AnalysisCreateResponse
import com.elevate.app.data.api.AnalysisEventsPage
import com.elevate.app.data.api.AnalysisGetResponse
import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.ErrorEnvelope
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.data.api.WindowResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID

/**
 * Person 3's analysis routes (contracts §3, §4, §7). Every method either returns what the server
 * sent, decoded by a contract reader, or throws [AnalysisApiException]. Nothing is filled in.
 */
interface AnalysisClient {
    /** `POST /v1/analyses {session_id}` + `Idempotency-Key`. */
    suspend fun startAnalysis(sessionId: String, idempotencyKey: String): StartedAnalysis

    /** `GET /v1/analyses/{id}` → `analysis.get.response.v1` (contracts §3, 1.4.0). */
    suspend fun getAnalysis(analysisId: String): AnalysisGetResponse

    /** `GET /v1/analyses/{id}/events?after_seq=&limit=` (`after_seq` exclusive, limit ≤ 500). */
    suspend fun events(analysisId: String, afterSeq: Long, limit: Int = 100): AnalysisEventsPage

    /** `GET /v1/artifacts/{id}` → short-lived signed URL (contracts §4; route not built yet). */
    suspend fun artifactUrl(artifactId: String): SignedUrl

    /** Downloads `window_result.v1` from a signed URL. The URL is never logged nor put in an error. */
    suspend fun downloadWindowResult(url: SignedUrl): WindowResult

    /** `GET /v1/matches/{id}` → `match.v1` (contracts §7; route not built yet). */
    suspend fun getMatch(matchId: String): MatchRecord
}

/** 202 = the server dispatched a new job; 200 = an existing or terminal analysis was returned. */
data class StartedAnalysis(val response: AnalysisCreateResponse, val dispatched: Boolean)

/** A signed URL. [toString] hides it so it cannot leak into logs or crash reports. */
class SignedUrl(val value: String, val expiresAt: String? = null) {
    override fun toString(): String = "SignedUrl(<redacted>${expiresAt?.let { ", expires $it" } ?: ""})"
}

/** UNVERIFIED shape: contracts §4 says "returns a short-lived signed URL" without a schema. */
@Serializable
private data class ArtifactUrlBody(val url: String, @SerialName("expires_at") val expiresAt: String? = null)

class AnalysisApiException(
    val kind: Kind,
    message: String,
    val httpStatus: Int? = null,
    /** `error.code` from the contract envelope, when the server sent one. */
    val code: String? = null,
    val retryAfterS: Double? = null,
) : Exception(message) {
    enum class Kind {
        /** No base URL or key configured: no request was made. */
        NOT_CONFIGURED,
        /** No network, DNS failure, timeout, connection reset. */
        NETWORK,
        /** The server answered with an error status. */
        HTTP,
        /** The body does not match the contract reader. */
        CONTRACT,
    }

    val isNetwork: Boolean get() = kind == Kind.NETWORK
}

/**
 * HttpURLConnection implementation (same style as [com.elevate.app.data.api.ApiSource]).
 * Auth (contracts §1): `Authorization: Bearer <JWT>` from [tokenProvider] + `apikey`.
 */
class HttpAnalysisClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val tokenProvider: suspend () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Int = 10_000,
    /** Window results are small JSON documents; anything larger is refused. */
    private val maxDownloadBytes: Int = 16 * 1024 * 1024,
    /** Signed URLs must be https; tests against a local server turn this off. */
    private val requireHttpsDownloads: Boolean = true,
) : AnalysisClient {

    private class Response(val status: Int, val body: String)

    override suspend fun startAnalysis(sessionId: String, idempotencyKey: String): StartedAnalysis {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 255) { "Idempotency-Key must be 1..255 chars" }
        val body = """{"session_id":${Contracts.json.encodeToString(kotlinx.serialization.serializer<String>(), sessionId)}}"""
        val r = call("POST", "/v1/analyses", body, mapOf("Idempotency-Key" to idempotencyKey))
        return when (r.status) {
            200, 202 -> StartedAnalysis(decode(AnalysisCreateResponse.serializer(), r.body, "analysis.create.response.v1"), dispatched = r.status == 202)
            else -> throw httpError(r)
        }
    }

    override suspend fun getAnalysis(analysisId: String): AnalysisGetResponse =
        ok(call("GET", "/v1/analyses/${seg(analysisId)}"), AnalysisGetResponse.serializer(), "analysis.get.response.v1")

    override suspend fun events(analysisId: String, afterSeq: Long, limit: Int): AnalysisEventsPage {
        require(afterSeq >= 0) { "after_seq must be >= 0" }
        val l = limit.coerceIn(1, 500)
        return ok(call("GET", "/v1/analyses/${seg(analysisId)}/events?after_seq=$afterSeq&limit=$l"), AnalysisEventsPage.serializer(), "analysis.events.page.v1")
    }

    override suspend fun artifactUrl(artifactId: String): SignedUrl {
        val b = ok(call("GET", "/v1/artifacts/${seg(artifactId)}"), ArtifactUrlBody.serializer(), "artifact url (UNVERIFIED shape)")
        return SignedUrl(b.url, b.expiresAt)
    }

    override suspend fun downloadWindowResult(url: SignedUrl): WindowResult = withContext(io) {
        val u = try { URL(url.value) } catch (e: java.net.MalformedURLException) {
            throw AnalysisApiException(AnalysisApiException.Kind.CONTRACT, "artifact URL is malformed")
        }
        if (requireHttpsDownloads && u.protocol != "https") throw AnalysisApiException(AnalysisApiException.Kind.CONTRACT, "artifact URL is not https")
        // No Authorization/apikey here: the signed URL carries its own grant and goes to R2, not the API.
        val conn = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                throw AnalysisApiException(AnalysisApiException.Kind.HTTP, "window result download failed: HTTP $status", httpStatus = status)
            }
            val text = readCapped(conn.inputStream)
            val w = decode(WindowResult.serializer(), text, "window_result.v1")
            if (w.schema != "window_result.v1") throw AnalysisApiException(AnalysisApiException.Kind.CONTRACT, "schema ${w.schema} is not window_result.v1")
            w
        } catch (e: IOException) {
            throw network(e)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun getMatch(matchId: String): MatchRecord {
        val m = ok(call("GET", "/v1/matches/${seg(matchId)}"), MatchRecord.serializer(), "match.v1")
        if (m.schema != "match.v1") throw AnalysisApiException(AnalysisApiException.Kind.CONTRACT, "schema ${m.schema} is not match.v1")
        return m
    }

    private fun <T> ok(r: Response, ser: KSerializer<T>, what: String): T =
        if (r.status == 200) decode(ser, r.body, what) else throw httpError(r)

    private fun <T> decode(ser: KSerializer<T>, body: String, what: String): T = try {
        Contracts.json.decodeFromString(ser, body)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException; its first line names the field.
        throw AnalysisApiException(AnalysisApiException.Kind.CONTRACT, "$what does not match the contract: ${e.message?.lineSequence()?.firstOrNull()}")
    }

    private fun httpError(r: Response): AnalysisApiException {
        val env = runCatching { Contracts.json.decodeFromString(ErrorEnvelope.serializer(), r.body) }.getOrNull()
        return if (env != null) {
            AnalysisApiException(AnalysisApiException.Kind.HTTP, "${env.error.code}: ${env.error.message}", r.status, env.error.code, env.retryAfterS)
        } else {
            AnalysisApiException(AnalysisApiException.Kind.HTTP, "HTTP ${r.status}", r.status)
        }
    }

    private suspend fun call(method: String, pathAndQuery: String, body: String? = null, headers: Map<String, String> = emptyMap()): Response {
        if (baseUrl.isBlank() || apiKey.isBlank()) throw AnalysisApiException(AnalysisApiException.Kind.NOT_CONFIGURED, "API not configured")
        // A sign-in refresh that fails (a slow network times out) is an ordinary API failure for the
        // caller to show and retry; escaping as AuthException crashed the app on the A55.
        val token = try {
            tokenProvider()
        } catch (e: com.elevate.app.data.auth.AuthException) {
            throw AnalysisApiException(
                if (e.status == 0) AnalysisApiException.Kind.NETWORK else AnalysisApiException.Kind.HTTP,
                e.message ?: "sign-in failed",
            )
        }
        return withContext(io) {
            val conn = (URL(baseUrl.trimEnd('/') + pathAndQuery).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("apikey", apiKey)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                Response(status, stream?.let(::readCapped).orEmpty())
            } catch (e: IOException) {
                throw network(e)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readCapped(input: InputStream): String = input.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxDownloadBytes) throw AnalysisApiException(AnalysisApiException.Kind.CONTRACT, "response larger than $maxDownloadBytes bytes")
        }
        out.toString(Charsets.UTF_8.name())
    }

    /** Network failure without the URL (it may be signed). */
    private fun network(e: IOException): AnalysisApiException = when (e) {
        is UnknownHostException -> AnalysisApiException(AnalysisApiException.Kind.NETWORK, "no network (host not resolved)")
        is SocketTimeoutException -> AnalysisApiException(AnalysisApiException.Kind.NETWORK, "timed out after $timeoutMs ms")
        else -> AnalysisApiException(AnalysisApiException.Kind.NETWORK, e.javaClass.simpleName)
    }

    private fun seg(id: String): String = URLEncoder.encode(id, "UTF-8")

    companion object {
        /** A fresh key for one logical "start analysis" action; persist it and reuse it on retries. */
        fun newIdempotencyKey(): String = UUID.randomUUID().toString()
    }
}
