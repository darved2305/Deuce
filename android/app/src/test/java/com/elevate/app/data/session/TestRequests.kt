package com.elevate.app.data.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest

/** Builders shared by the session/upload tests. */
object TestRequests {

    val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "packages/contracts/schemas").isDirectory }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * `source` of `eurogames_bern_1.webm` (android/proof/video-manifest.md, ffprobe 2026-10-10:
     * VP8 848×464, rot 0, 767 frames, 33000/33000/67000 µs, 1 gap, 30 fps stated; Matroska header:
     * Duration 25603 ms, Vorbis 44100 Hz). [bytes] and [sha256] are the caller's.
     */
    fun eurogamesSource(bytes: Long, sha256: String, device: String = "jvm-test") = SessionSource(
        width = 848,
        height = 464,
        rotation = 0,
        fpsNominal = 30.0,
        durationMs = 25_603,
        pts = SourcePts(medianIntervalUs = 33_000, minUs = 33_000, maxUs = 67_000, gaps = 1, vfr = false),
        codec = "vp08",
        hasAudio = true,
        audioRateHz = 44_100,
        device = device,
        bytes = bytes,
        sha256 = sha256,
    )

    /** A request from caller-supplied pieces, as the setup screens will hand them over. */
    fun request(
        source: SessionSource,
        calibration: kotlinx.serialization.json.JsonElement = JsonNull,
        rallyCandidates: JsonArray = JsonArray(emptyList()),
    ) = SessionCreateRequest(
        sport = "badminton",
        format = "singles",
        ruleset = Ruleset("bwf_3x21", "2025-04"),
        mode = "practice",
        source = source,
        calibrationInput = calibration,
        whoIsMe = buildJsonObject {
            putJsonArray("image_xy") { add(JsonPrimitive(424)); add(JsonPrimitive(232)) }
            put("t_ms", 0)
        },
        freePlaySeed = JsonNull,
        rallyCandidates = rallyCandidates,
        uploadMode = "multipart",
    )

    /** Contracts §2's example calibration (court taps), for shape checks only. */
    val exampleCalibration: JsonObject = buildJsonObject {
        put("method", "tap")
        put("plane", "court")
        put("template", "badminton_singles")
        putJsonObject("points_px") {
            putJsonArray("near_left") { add(JsonPrimitive(16)); add(JsonPrimitive(196)) }
            putJsonArray("near_right") { add(JsonPrimitive(334)); add(JsonPrimitive(196)) }
            putJsonArray("far_right") { add(JsonPrimitive(248)); add(JsonPrimitive(34)) }
            putJsonArray("far_left") { add(JsonPrimitive(102)); add(JsonPrimitive(34)) }
        }
        put("frame_ms", 1200)
    }

    val exampleRallies: JsonArray = buildJsonArray {
        add(buildJsonObject { put("start_ms", 4200); put("end_ms", 11800); put("onsets", 9); put("state", "candidate") })
    }
}
