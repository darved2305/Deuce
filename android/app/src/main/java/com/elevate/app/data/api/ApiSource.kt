package com.elevate.app.data.api

import com.elevate.app.domain.repository.VideoFingerprint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Boundary to Person 3's API: `{SUPABASE_URL}/functions/v1/api`, routes under `/v1` (contracts §1).
 *
 * Phase 1 uses only the deployed public route `GET /v1/health`. Sessions and analyses need an
 * authenticated user and an upload (contracts §2–3, Phase 2), so [analysisFor] reports that no
 * analysis exists rather than calling routes that are not deployed. Nothing here fabricates a
 * response: every [Health] value comes from a real request or says why none was made.
 *
 * [baseUrl] comes from the untracked `local.properties` (`elevate.apiBaseUrl`); blank means not configured.
 */
class ApiSource(private val baseUrl: String, private val timeoutMs: Int = 5_000) {

    sealed interface Health {
        data object NotConfigured : Health
        data class Online(val latencyMs: Long) : Health
        /** The API answered with the contract error envelope. */
        data class ApiError(val httpStatus: Int, val code: String, val message: String) : Health
        data class Unreachable(val reason: String) : Health
    }

    sealed interface Result {
        /** No API configured, or no analysis exists for this recording yet (Phase 2 creates them). */
        data object NotConnected : Result
    }

    @Suppress("UNUSED_PARAMETER")
    fun analysisFor(video: VideoFingerprint): Result = Result.NotConnected

    /** Blocking network call: run on an IO dispatcher. */
    fun health(): Health {
        if (baseUrl.isBlank()) return Health.NotConfigured
        val url = URL(baseUrl.trimEnd('/') + "/v1/health")
        val start = System.nanoTime()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            if (status == 200) {
                val ok = runCatching { Contracts.json.decodeFromString(HealthResponse.serializer(), body) }.getOrNull()
                if (ok?.status == "ok") Health.Online(latencyMs) else Health.Unreachable("HTTP 200 with unexpected body")
            } else {
                val env = runCatching { Contracts.json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()
                if (env != null) Health.ApiError(status, env.error.code, env.error.message)
                else Health.Unreachable("HTTP $status")
            }
        } catch (e: java.net.UnknownHostException) {
            Health.Unreachable("no network (host not resolved)")
        } catch (e: java.net.SocketTimeoutException) {
            Health.Unreachable("timed out after $timeoutMs ms")
        } catch (e: IOException) {
            Health.Unreachable(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}
