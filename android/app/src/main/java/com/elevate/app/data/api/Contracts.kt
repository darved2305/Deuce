package com.elevate.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Kotlin readers for `packages/contracts/schemas` (@elevate/contracts 1.4.0, D-261010-HW2, D-261011-HW6).
 * Readers ignore unknown fields so additive contract changes never break the app; field names and
 * nullability are checked against the generated JSON Schemas by `ContractSchemaTest`.
 */
object Contracts {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
}

/** `error.envelope.v1` (contracts §1). */
@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
    @SerialName("retry_after_s") val retryAfterS: Double? = null,
)

@Serializable
data class ErrorBody(val code: String, val message: String, val details: JsonElement? = null)

/** `GET /v1/health` response (supabase/functions/api/routes/health.ts). */
@Serializable
data class HealthResponse(val status: String)

/** `realtime.hint.v1`: Broadcast payload on `analysis:{id}`, event `analysis_seq` (contracts §3). */
@Serializable
data class RealtimeHint(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("analysis_id") val analysisId: String,
    @SerialName("latest_seq") val latestSeq: Long,
)

/**
 * One row of `analysis.event.v1` (contracts §3). Optional fields depend on `type`. Every §3 type can
 * appear; `engine_version` is absent or null on events the API emits itself (contracts 1.4.0).
 */
@Serializable
data class AnalysisEvent(
    @SerialName("event_id") val eventId: String,
    @SerialName("analysis_id") val analysisId: String,
    @SerialName("match_id") val matchId: String,
    val seq: Long,
    @SerialName("schema_version") val schemaVersion: Int,
    val type: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("engine_version") val engineVersion: String? = null,
    @SerialName("window_seq") val windowSeq: Int? = null,
    @SerialName("decode_window_us") val decodeWindowUs: List<Long>? = null,
    @SerialName("emit_range_us") val emitRangeUs: List<Long>? = null,
    @SerialName("artifact_id") val artifactId: String? = null,
    val reason: String? = null,
    /** Total windows the engine will emit, on `analysis.started` (contracts 1.8.0); the "N" of "n of N". */
    @SerialName("windows_expected") val windowsExpected: Int? = null,
)

/** `analysis.events.page.v1`: `GET /v1/analyses/{id}/events?after_seq=` (contracts §3). */
@Serializable
data class AnalysisEventsPage(
    val events: List<AnalysisEvent>,
    @SerialName("next_after_seq") val nextAfterSeq: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val state: String,
)

/** `window_result.v1` (contracts §5): one engine window, stored in R2. */
@Serializable
data class WindowResult(
    val schema: String,
    @SerialName("analysis_id") val analysisId: String,
    @SerialName("window_seq") val windowSeq: Int,
    @SerialName("decode_window_us") val decodeWindowUs: List<Long>,
    @SerialName("emit_range_us") val emitRangeUs: List<Long>,
    val models: List<String>,
    val ball: List<JsonArray>,
    val players: List<JsonArray>,
    val onsets: List<JsonArray>,
    val events: List<WindowEvent>,
    val quality: WindowQuality,
) {
    /** Ball rows `[t_ms, x_px, y_px, confidence|null, state]` in image space (contracts §5). */
    fun ballRows(): List<BallRow> = ball.map { r ->
        require(r.size == 5) { "ball row must have 5 items: $r" }
        BallRow(
            tMs = r[0].jsonPrimitive.long,
            xPx = r[1].jsonPrimitive.double,
            yPx = r[2].jsonPrimitive.double,
            confidence = r[3].takeUnless { it is JsonNull }?.jsonPrimitive?.double,
            state = requireNotNull(r[4].jsonPrimitive.contentOrNull) { "ball row state missing" },
        )
    }
}

data class BallRow(val tMs: Long, val xPx: Double, val yPx: Double, val confidence: Double?, val state: String)

@Serializable
data class WindowEvent(
    @SerialName("event_id") val eventId: String,
    val type: String,
    @SerialName("t_ms") val tMs: Long,
    @SerialName("t_sigma_ms") val tSigmaMs: Long,
    @SerialName("track_id") val trackId: String,
    @SerialName("image_xy") val imageXy: List<Double>,
    val cues: List<String>,
    val state: String,
)

@Serializable
data class WindowQuality(
    @SerialName("ball_coverage") val ballCoverage: Double,
    @SerialName("camera_motion_px") val cameraMotionPx: Double,
    @SerialName("blur_index") val blurIndex: Double,
)

/** `analysis.create.response.v1`: `POST /v1/analyses` → 202 (dispatched) or 200 (existing) (contracts §3). */
@Serializable
data class AnalysisCreateResponse(
    @SerialName("analysis_id") val analysisId: String,
    val state: String,
)

/** `analysis.get.response.v1`: `GET /v1/analyses/{id}` (contracts §3, 1.4.0). Nullable fields are sent as null. */
@Serializable
data class AnalysisGetResponse(
    @SerialName("analysis_id") val analysisId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("match_id") val matchId: String,
    val state: String,
    @SerialName("engine_version") val engineVersion: String?,
    @SerialName("last_seq") val lastSeq: Long,
    @SerialName("failure_reason") val failureReason: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("finalized_at") val finalizedAt: String?,
)

/**
 * The subset of `match.v1` (contracts §7) the overview reads. Every field present here is checked
 * against `match.v1.schema.json` by `ContractSchemaTest` (names exist, required ⇒ non-null); fields
 * not read by the app are ignored, never defaulted.
 */
@Serializable
data class MatchRecord(
    val schema: String,
    @SerialName("match_id") val matchId: String,
    @SerialName("session_ids") val sessionIds: List<String>,
    val sport: String,
    val format: String,
    val ruleset: MatchRuleset,
    val players: List<MatchPlayer>,
    /** Null when the match is not calibrated (contracts 1.8.0). */
    val calibration: MatchCalibration?,
    val analysis: MatchAnalysis,
    val capabilities: MatchCapabilities,
    val rallies: List<MatchRally>,
    val shots: List<MatchShot>,
)

@Serializable
data class MatchRuleset(val id: String, val version: String)

@Serializable
data class MatchPlayer(
    @SerialName("player_id") val playerId: String,
    @SerialName("track_ids") val trackIds: List<String>,
    val label: String,
    val identity: String,
)

@Serializable
data class MatchCalibration(
    @SerialName("calibration_id") val calibrationId: String,
    val plane: String,
    val template: String,
    val method: String,
    @SerialName("H") val h: List<Double>,
    /** Any JSON or null (schema `anyOf [{}, null]`); JSON null decodes as JsonNull. */
    @SerialName("H_cov") val hCov: JsonElement,
    @SerialName("residual_px") val residualPx: Double,
    @SerialName("visible_fraction") val visibleFraction: Double,
    val tier: String,
)

@Serializable
data class MatchAnalysis(
    @SerialName("analysis_id") val analysisId: String,
    @SerialName("engine_version") val engineVersion: String,
    val state: String,
    val models: List<String>,
    val artifacts: MatchArtifacts,
)

@Serializable
data class MatchArtifacts(
    @SerialName("ball_track") val ballTrack: String,
    @SerialName("player_tracks") val playerTracks: String,
)

/** Capability tier (target) and status (verified), contracts §0. */
@Serializable
data class MatchCapability(val tier: String, val status: String)

@Serializable
data class MatchCapabilities(
    val court: MatchCapability,
    val ball: MatchCapability,
    val reviews: MatchCapability,
    val score: MatchCapability,
)

@Serializable
data class MatchRally(
    @SerialName("rally_id") val rallyId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("shot_ids") val shotIds: List<String>,
    val server: String,
    val winner: String,
    @SerialName("winner_state") val winnerState: String,
    @SerialName("end_reason") val endReason: String,
    /** Fraction of the rally's frames with an observed ball (0..1). */
    @SerialName("ball_coverage") val ballCoverage: Double,
    @SerialName("clip_id") val clipId: String,
    val state: String,
)

/** Subset of a `match.v1` shot: what the mini-court and rally list read. */
@Serializable
data class MatchShot(
    @SerialName("shot_id") val shotId: String,
    @SerialName("rally_id") val rallyId: String,
    val index: Int,
    @SerialName("player_id") val playerId: String,
    @SerialName("contact_ms") val contactMs: Long,
    val chosen: MatchShotChosen,
    @SerialName("flight_time_ms") val flightTimeMs: Long,
    @SerialName("flight_time_state") val flightTimeState: String,
)

@Serializable
data class MatchShotChosen(
    @SerialName("target_zone") val targetZone: String,
    /** Court plane metres: x across from the camera's left sideline, y along from the near baseline. */
    @SerialName("landing_xy_m") val landingXyM: List<Double>,
    @SerialName("landing_state") val landingState: String,
    val family: String,
)
