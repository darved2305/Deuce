package com.elevate.app.data.record

import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.ElevateHttp
import com.elevate.app.data.session.Ruleset
import com.elevate.app.data.session.SessionSource
import com.elevate.app.data.session.requestJson
import com.elevate.app.data.session.responseJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** `session.create.request.v1` with `upload_mode: "segments"` (contracts §2, D-261011-HW14). */
@Serializable
data class SegmentSessionRequest(
    val sport: String,
    val format: String,
    val ruleset: Ruleset,
    val mode: String,
    val source: SessionSource,
    @SerialName("calibration_input") val calibrationInput: JsonElement,
    @SerialName("who_is_me") val whoIsMe: JsonElement,
    @SerialName("free_play_seed") val freePlaySeed: JsonElement,
    @SerialName("rally_candidates") val rallyCandidates: JsonArray,
    @SerialName("upload_mode") val uploadMode: String = "segments",
) {
    init {
        require(uploadMode == "segments") { "this request is for segment sessions" }
        require(calibrationInput is JsonObject || calibrationInput is JsonNull) { "calibration_input must be an object or null" }
        require(whoIsMe is JsonObject) { "who_is_me must be an object" }
    }

    fun toJson(): String = requestJson.encodeToString(serializer(), this)
}

/** `session.create.response.v1`, segments variant. */
@Serializable
data class SegmentSessionTicket(
    @SerialName("session_id") val sessionId: String,
    @SerialName("match_id") val matchId: String,
    @SerialName("upload_mode") val uploadMode: String,
    val segments: SegmentLimits,
)

@Serializable
data class SegmentLimits(
    @SerialName("max_segment_bytes") val maxSegmentBytes: Long,
    @SerialName("max_concurrency") val maxConcurrency: Int,
    @SerialName("url_ttl_s") val urlTtlS: Int,
)

/** `segment.register.request.v1` (the `segmentFields` of the contract). */
@Serializable
data class SegmentRegisterRequest(
    val seq: Int,
    @SerialName("pts_start_us") val ptsStartUs: Long,
    @SerialName("pts_end_us") val ptsEndUs: Long,
    @SerialName("starts_on_sync_sample") val startsOnSyncSample: Boolean,
    val bytes: Long,
    val sha256: String,
    val final: Boolean,
)

/** `segment.register.response.v1`. */
@Serializable
data class SegmentRegisterResponse(
    val seq: Int,
    @SerialName("r2_key") val r2Key: String,
    @SerialName("put_url") val putUrl: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    val expiresAtMs: Long? get() = runCatching { java.time.OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli() }.getOrNull()

    override fun toString() = "SegmentRegisterResponse(seq=$seq, r2Key=$r2Key, putUrl=<redacted>, expiresAt=$expiresAt)"
}

/** `segment.complete.response.v1` (the manifest plus `completed_at`). */
@Serializable
data class SegmentCompleteResponse(
    val seq: Int,
    @SerialName("pts_start_us") val ptsStartUs: Long,
    @SerialName("pts_end_us") val ptsEndUs: Long,
    @SerialName("starts_on_sync_sample") val startsOnSyncSample: Boolean,
    val bytes: Long,
    val sha256: String,
    val final: Boolean,
    @SerialName("r2_key") val r2Key: String,
    @SerialName("completed_at") val completedAt: String,
)

/** The segment routes, as the uploader needs them. */
interface SegmentApi {
    suspend fun createSession(request: SegmentSessionRequest, idempotencyKey: String): SegmentSessionTicket
    suspend fun register(sessionId: String, request: SegmentRegisterRequest): SegmentRegisterResponse
    suspend fun complete(sessionId: String, seq: Int): SegmentCompleteResponse
}

/** [SegmentApi] over [ElevateHttp] (build it with `maxAttempts = 1`: [SegmentUploader] owns the retries). */
class SegmentClient(private val http: ElevateHttp) : SegmentApi {

    override suspend fun createSession(request: SegmentSessionRequest, idempotencyKey: String): SegmentSessionTicket {
        require(idempotencyKey.length in 1..255) { "Idempotency-Key must be 1..255 characters" }
        val t = decode(SegmentSessionTicket.serializer(), http.call("POST", "/v1/sessions", request.toJson(), idempotencyKey))
        if (t.uploadMode != "segments") throw ApiException(200, "unexpected_response", "session was not created in segments mode")
        return t
    }

    override suspend fun register(sessionId: String, request: SegmentRegisterRequest): SegmentRegisterResponse {
        val body = requestJson.encodeToString(SegmentRegisterRequest.serializer(), request)
        return decode(SegmentRegisterResponse.serializer(), http.call("POST", "/v1/sessions/${checkId(sessionId)}/segments", body))
    }

    override suspend fun complete(sessionId: String, seq: Int): SegmentCompleteResponse {
        require(seq >= 0) { "seq must be non-negative" }
        return decode(SegmentCompleteResponse.serializer(), http.call("POST", "/v1/sessions/${checkId(sessionId)}/segments/$seq/complete", "{}"))
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        responseJson.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw ApiException(200, "unexpected_response", "response does not match ${serializer.descriptor.serialName.substringAfterLast('.')}")
    } catch (e: IllegalArgumentException) {
        throw ApiException(200, "unexpected_response", "response does not match ${serializer.descriptor.serialName.substringAfterLast('.')}")
    }

    private fun checkId(id: String): String {
        require(id.matches(Regex("^s_[A-Za-z0-9_-]+$"))) { "not a session id" }
        return id
    }
}
