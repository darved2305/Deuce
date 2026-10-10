package com.elevate.app.ui.setup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The part of `POST /v1/sessions` (contracts §2) that this flow produces. The caller adds
 * `source`, `rally_candidates` and `upload_mode`. Points are upright source pixels (§0).
 */
@Serializable
data class SetupResult(
    val sport: Sport,
    val format: Format,
    val ruleset: Ruleset,
    val mode: Mode,
    @SerialName("calibration_input") val calibrationInput: CalibrationInput?,
    @SerialName("who_is_me") val whoIsMe: TimedPoint,
    @SerialName("free_play_seed") val freePlaySeed: TimedPoint?,
) {
    /** Keys always present; `calibration_input` and `free_play_seed` are JSON null when absent (both required). */
    fun toJson(): JsonObject = JSON.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        private val JSON = Json { encodeDefaults = true; explicitNulls = true }
    }
}

@Serializable
data class CalibrationInput(
    val method: String,
    val plane: String,
    val template: String,
    @SerialName("points_px") val pointsPx: PointsPx,
    @SerialName("frame_ms") val frameMs: Long,
)

/** `[x, y]` per corner, contract order near_left, near_right, far_right, far_left. */
@Serializable
data class PointsPx(
    @SerialName("near_left") val nearLeft: List<Double>,
    @SerialName("near_right") val nearRight: List<Double>,
    @SerialName("far_right") val farRight: List<Double>,
    @SerialName("far_left") val farLeft: List<Double>,
)

/** `who_is_me` and `free_play_seed`: a tap `[x, y]` on the frame at [tMs]. */
@Serializable
data class TimedPoint(
    @SerialName("image_xy") val imageXy: List<Double>,
    @SerialName("t_ms") val tMs: Long,
)
