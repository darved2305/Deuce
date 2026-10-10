package com.elevate.app.data.session

import com.elevate.app.domain.media.SourceFacts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Encoder for requests: every required key is written, nullable ones as explicit `null`. */
internal val requestJson = Json { encodeDefaults = true; explicitNulls = true }

/** Reader for responses: ignores additive fields (contracts may grow). */
internal val responseJson = Json { ignoreUnknownKeys = true }

/**
 * `session.create.request.v1` (contracts §2). The setup step's parts ([calibrationInput], [whoIsMe],
 * [freePlaySeed], [rallyCandidates]) arrive already built as JSON: their shapes belong to the setup
 * screens, and `ContractSchemaTest`-style checks in `SessionRequestSchemaTest` hold the keys.
 */
@Serializable
data class SessionCreateRequest(
    val sport: String,
    val format: String,
    val ruleset: Ruleset,
    val mode: String,
    val source: SessionSource,
    /** Court/floor taps object, or [JsonNull]. */
    @SerialName("calibration_input") val calibrationInput: JsonElement,
    /** `{image_xy, t_ms}`: required, an object. */
    @SerialName("who_is_me") val whoIsMe: JsonElement,
    /** `{t_ms, image_xy}` or [JsonNull]. */
    @SerialName("free_play_seed") val freePlaySeed: JsonElement,
    /** `[{start_ms, end_ms, onsets, state}]`; empty when the app found none. */
    @SerialName("rally_candidates") val rallyCandidates: JsonArray,
    /** Only `"multipart"` is implemented. */
    @SerialName("upload_mode") val uploadMode: String,
) {
    init {
        require(sport in setOf("badminton", "table_tennis", "tennis", "free_play")) { "sport $sport" }
        require(format in setOf("singles", "doubles", "none")) { "format $format" }
        require(mode in setOf("match", "practice", "free_play")) { "mode $mode" }
        require(uploadMode == "multipart") { "only multipart uploads are implemented" }
        require(calibrationInput is JsonObject || calibrationInput is JsonNull) { "calibration_input must be an object or null" }
        require(whoIsMe is JsonObject) { "who_is_me must be an object" }
        require(freePlaySeed is JsonObject || freePlaySeed is JsonNull) { "free_play_seed must be an object or null" }
    }

    fun toJson(): String = requestJson.encodeToString(serializer(), this)
}

@Serializable
data class Ruleset(val id: String, val version: String)

/** `source` of `session.create.request.v1`. */
@Serializable
data class SessionSource(
    val width: Int,
    val height: Int,
    val rotation: Int,
    @SerialName("fps_nominal") val fpsNominal: Double,
    @SerialName("duration_ms") val durationMs: Long,
    val pts: SourcePts,
    val codec: String,
    @SerialName("has_audio") val hasAudio: Boolean,
    @SerialName("audio_rate_hz") val audioRateHz: Int,
    val device: String,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(sha256.matches(Regex("^[A-Fa-f0-9]{64}$"))) { "sha256 must be 64 hex characters" }
        require(bytes >= 1) { "bytes must be at least 1" }
    }

    companion object {
        /**
         * Builds `source` from the probe ([SourceFacts]) and the file's sha256
         * (`playback/VideoInspector.fingerprint`). Throws when a required field is unknown rather than
         * inventing it. [audioRateHzWithoutAudio] is the lead's answer to the open contract question
         * (PHASE2_DEPENDENCIES.md Q4: `audio_rate_hz` is required but a silent clip has no rate);
         * left null, a clip without an audio rate is refused here.
         */
        fun from(facts: SourceFacts, sha256: String, device: String, audioRateHzWithoutAudio: Int? = null): SessionSource {
            val pts = requireNotNull(facts.pts) { "no frame timing: source.pts unknown" }
            val fps = requireNotNull(facts.fpsNominal) { "frame rate unknown: source.fps_nominal" }
            val codec = requireNotNull(facts.codec) { "codec unknown" }
            val audioRate = facts.audioRateHz ?: requireNotNull(audioRateHzWithoutAudio) {
                "audio_rate_hz unknown (open contract question 4 in android/PHASE2_DEPENDENCIES.md)"
            }
            return SessionSource(
                width = facts.width,
                height = facts.height,
                rotation = facts.rotation,
                fpsNominal = fps,
                durationMs = facts.durationMs,
                pts = SourcePts(pts.medianIntervalUs, pts.minUs, pts.maxUs, pts.gaps, pts.vfr),
                codec = codec,
                hasAudio = facts.hasAudio,
                audioRateHz = audioRate,
                device = device,
                bytes = facts.bytes,
                sha256 = sha256.lowercase(),
            )
        }
    }
}

@Serializable
data class SourcePts(
    @SerialName("median_interval_us") val medianIntervalUs: Long,
    @SerialName("min_us") val minUs: Long,
    @SerialName("max_us") val maxUs: Long,
    val gaps: Int,
    val vfr: Boolean,
)

/** `session.create.response.v1`. */
@Serializable
data class SessionCreateResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("match_id") val matchId: String,
    val upload: UploadTicket,
)

@Serializable
data class UploadTicket(
    @SerialName("upload_id") val uploadId: String,
    @SerialName("r2_key") val r2Key: String,
    @SerialName("part_size") val partSize: Long,
    @SerialName("max_concurrency") val maxConcurrency: Int,
)

/** `upload.parts.request.v1`. */
@Serializable
data class UploadPartsRequest(@SerialName("part_numbers") val partNumbers: List<Int>)

/** `upload.complete.request.v1`. */
@Serializable
data class UploadCompleteRequest(val parts: List<CompletedPart>)

@Serializable
data class CompletedPart(val n: Int, val etag: String)

/** One entry of `upload.parts.response.v1` (contracts 1.4.0): a presigned PUT URL and when it expires. */
@Serializable
data class PartUrl(
    val n: Int,
    val url: String,
    /** RFC 3339 date-time. */
    @SerialName("expires_at") val expiresAt: String,
) {
    /** Epoch ms of [expiresAt]; null when it cannot be parsed (then the URL is used once, never cached). */
    val expiresAtMs: Long? get() = runCatching { java.time.OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli() }.getOrNull()

    override fun toString() = "PartUrl(n=$n, url=${redactUrl(url)}, expiresAt=$expiresAt)"
}

/** `upload.parts.response.v1` (contracts 1.4.0). */
@Serializable
data class UploadPartsResponse(val parts: List<PartUrl>)

/** `upload.complete.response.v1`: `status` is always `"completed"`; [bytes] is the stored object's size. */
@Serializable
data class UploadCompleteResponse(val status: String, val bytes: Long)

/** `upload.abort.response.v1`: `{status: "aborted"}`. */
@Serializable
data class UploadAbortResponse(val status: String)

/** `session.get.response.v1` (contracts 1.4.0). */
@Serializable
data class SessionView(
    @SerialName("session_id") val sessionId: String,
    @SerialName("match_id") val matchId: String,
    val mode: String,
    @SerialName("upload_mode") val uploadMode: String,
    val source: SessionSource,
    @SerialName("created_at") val createdAt: String,
    /** Required; null when the session has no multipart upload. */
    val upload: SessionUploadView?,
)

/** `session.get.response.v1.upload`. */
@Serializable
data class SessionUploadView(
    /** `initiated` | `completed` | `aborted`. */
    val status: String,
    @SerialName("part_size") val partSize: Long,
    /** Declared size until completed; the stored object's size after. */
    val bytes: Long,
    @SerialName("part_count") val partCount: Int,
    /** Required; null until completed. */
    @SerialName("completed_at") val completedAt: String?,
)
