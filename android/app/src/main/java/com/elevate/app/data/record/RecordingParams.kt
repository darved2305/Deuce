package com.elevate.app.data.record

/** One frame-rate range a camera reports (inclusive), as in `CameraInfo.supportedFrameRateRanges`. */
data class FpsRange(val lower: Int, val upper: Int) {
    init { require(lower in 1..upper) { "bad fps range $lower..$upper" } }
}

/** What the camera and the recorder can do on this phone, read once by [CameraRecorder.probe]. */
data class DeviceCaptureCaps(
    /** Output heights the recorder offers (1080, 720, 480), whatever else the sensor can do. */
    val supportedHeights: Set<Int>,
    val fpsRanges: List<FpsRange>,
    /** The camera can run optical stabilisation (informational: it is requested on whenever present). */
    val hasOis: Boolean,
)

/** The three output sizes we ever record. 4K is never offered (see RECORDING.md). */
enum class RecordQuality(val height: Int, val width: Int) {
    FHD(1080, 1920), HD(720, 1280), SD(480, 720),
}

/**
 * The camera and encoder settings of one recording, chosen by [RecordingParamsChooser] from the
 * phone's abilities and the upload budget. Every field is explained in `ui/record/RECORDING.md`.
 */
data class RecordingParams(
    val quality: RecordQuality,
    /** The frame rate asked for, as a fixed range so the encoder does not drift to variable rate. */
    val fps: Int,
    /** The fixed range actually requested (lower == upper when the phone supports it exactly). */
    val fpsRange: FpsRange,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    /** How long the 300 MiB per-video limit (G1) allows at this bitrate, in seconds. */
    val maxDurationS: Long,
    /** False when the length the user asked for needs a bitrate below the quality floor. */
    val targetFits: Boolean,
) {
    val totalBitrateBps: Int get() = videoBitrateBps + audioBitrateBps

    /** Bytes expected for [seconds] of recording at these settings (container overhead included). */
    fun estimatedBytes(seconds: Long): Long = RecordingParamsChooser.estimateBytes(totalBitrateBps, seconds)

    /** The user-facing "about 8 min fits" figure. */
    val maxDurationMinutes: Int get() = (maxDurationS / 60).toInt()
}

/**
 * Picks the recording settings. Pure and deterministic, so the fallbacks are unit-tested.
 *
 * - Resolution: 1080p when offered, else 720p, else 480p. Never above 1080p.
 * - Frame rate: 60 when the phone has a range that holds exactly 60 (or 60 inside a wider range),
 *   else 30, else the highest supported rate below 30.
 * - Bitrate: from the 300 MiB budget and the length the user wants, kept between a floor (below it
 *   the shuttle smears) and a ceiling (above it only bandwidth and R2 budget are spent).
 */
object RecordingParamsChooser {
    /** G1: one video (all segments together) is at most 300 MiB (`docs/ops/cloudflare-budget.md`). */
    const val VIDEO_BYTE_CAP: Long = 300L * 1024 * 1024

    /** Share of the cap we plan to use: container overhead and encoder rate drift take the rest. */
    const val USABLE_SHARE = 0.90

    const val AUDIO_BITRATE_BPS = 128_000
    const val DEFAULT_TARGET_MINUTES = 8

    /** Share added to a bitrate for the mp4 container (about 1 %) and rate overshoot (a few %). */
    private const val OVERHEAD = 1.06

    fun chooseQuality(caps: DeviceCaptureCaps): RecordQuality =
        RecordQuality.entries.firstOrNull { it.height in caps.supportedHeights } ?: RecordQuality.SD

    /** The fixed range to request and the rate it gives. 60 when possible, else 30, else the best below 30. */
    fun chooseFps(caps: DeviceCaptureCaps): Pair<Int, FpsRange> {
        for (want in intArrayOf(60, 30)) {
            val exact = caps.fpsRanges.firstOrNull { it.lower == want && it.upper == want }
            if (exact != null) return want to exact
            // A wider range that still holds the rate: ask for the fixed range anyway (the camera
            // picks it); CameraX falls back to the nearest range when the exact one is refused.
            if (caps.fpsRanges.any { it.lower <= want && want <= it.upper }) return want to FpsRange(want, want)
        }
        val best = caps.fpsRanges.map { it.upper }.filter { it < 30 }.maxOrNull() ?: 30
        return best to FpsRange(best, best)
    }

    fun ceilingBps(q: RecordQuality, fps: Int): Int = when {
        q == RecordQuality.FHD && fps >= 60 -> 12_000_000
        q == RecordQuality.FHD -> 8_000_000
        q == RecordQuality.HD && fps >= 60 -> 7_000_000
        q == RecordQuality.HD -> 5_000_000
        else -> 2_500_000
    }

    fun floorBps(q: RecordQuality, fps: Int): Int = when {
        q == RecordQuality.FHD && fps >= 60 -> 4_000_000
        q == RecordQuality.FHD -> 3_000_000
        q == RecordQuality.HD && fps >= 60 -> 2_500_000
        q == RecordQuality.HD -> 2_000_000
        else -> 1_200_000
    }

    fun estimateBytes(totalBitrateBps: Int, seconds: Long): Long =
        (totalBitrateBps.toDouble() / 8.0 * seconds * OVERHEAD).toLong()

    fun choose(caps: DeviceCaptureCaps, targetMinutes: Int = DEFAULT_TARGET_MINUTES): RecordingParams {
        require(targetMinutes > 0) { "target length must be positive" }
        val quality = chooseQuality(caps)
        val (fps, range) = chooseFps(caps)
        val usableBits = VIDEO_BYTE_CAP * USABLE_SHARE * 8.0
        val wantedTotal = (usableBits / OVERHEAD) / (targetMinutes * 60.0)
        val wantedVideo = wantedTotal - AUDIO_BITRATE_BPS
        val floor = floorBps(quality, fps)
        val ceiling = ceilingBps(quality, fps)
        val video = wantedVideo.toInt().coerceIn(floor, ceiling)
        val total = video + AUDIO_BITRATE_BPS
        val maxS = (usableBits / OVERHEAD / total).toLong()
        return RecordingParams(
            quality = quality,
            fps = fps,
            fpsRange = range,
            videoBitrateBps = video,
            audioBitrateBps = AUDIO_BITRATE_BPS,
            maxDurationS = maxS,
            targetFits = wantedVideo >= floor,
        )
    }
}
