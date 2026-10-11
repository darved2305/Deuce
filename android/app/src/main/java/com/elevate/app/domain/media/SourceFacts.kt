package com.elevate.app.domain.media

import java.util.Locale

/**
 * Facts about a picked recording, read from its container and sample table (never from its name).
 * Maps onto `source` in `POST /v1/sessions` (contracts §2) once [sha256] is known.
 */
data class SourceFacts(
    val bytes: Long,
    val mime: String?,
    /** Contract codec id (`avc1`, `hvc1`, `vp09`, `av01`, …) or the raw MIME when unknown. */
    val codec: String?,
    /** Coded size before rotation. */
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    /** Frame rate written in the container, if any. Not the same thing as the measured PTS spacing. */
    val containerFps: Float?,
    val pts: PtsStats?,
    val hasAudio: Boolean,
    val audioRateHz: Int?,
    val isHdr: Boolean,
    /** A decoder on this phone accepts the video format. */
    val decodable: Boolean,
    /** PTS of the last video sample, to spot files cut short. */
    val lastSampleUs: Long?,
    /** The track states no duration; [durationMs] was measured from the first to the last frame. */
    val durationMeasured: Boolean = false,
    /** The sample walk stopped at its cap, so timing covers only the first [PtsStats.frames] frames. */
    val samplesCapped: Boolean = false,
    /** Sorted presentation times (µs) of every video frame read: snaps engine rows to real frames. */
    val frameTimesUs: LongArray? = null,
) {
    /** Size as displayed: width and height swap for 90/270 rotation. */
    val displayWidth get() = if (rotation % 180 == 0) width else height
    val displayHeight get() = if (rotation % 180 == 0) height else width

    /**
     * `fps_nominal`: the container's frame rate when it states one, otherwise [PtsStats.fpsMeasured]
     * (contracts §2 has no separate "measured" field).
     */
    val fpsNominal: Double?
        get() = containerFps?.toDouble()?.takeIf { it > 0 } ?: pts?.fpsMeasured
}

/**
 * Frame timing measured from the video track's sample timestamps (contracts §2 `source.pts`).
 * [gaps] = intervals longer than 1.5 × the median (dropped or missing frames).
 * [vfr] = more than 5 % of the non-gap intervals differ from the median by more than 10 %.
 * The median is taken over positive intervals (duplicate timestamps would make it 0).
 * Gaps are checked against ffprobe on real clips; the VFR rule is not yet checked on real VFR footage.
 */
data class PtsStats(
    val medianIntervalUs: Long,
    val minUs: Long,
    val maxUs: Long,
    val gaps: Int,
    val vfr: Boolean,
    val frames: Int,
    /** Mean of the non-gap intervals: millisecond-rounded timestamps (WebM) alternate 16/17 ms at 60 fps. */
    val meanIntervalUs: Double,
) {
    /** Frame rate from the measured spacing, 2 decimals. */
    val fpsMeasured: Double? get() = meanIntervalUs.takeIf { it > 0 }?.let { Math.round(1e8 / it) / 100.0 }

    companion object {
        /** [timesUs] in any order (decode order is not presentation order). Null below 2 frames. */
        fun from(timesUs: LongArray): PtsStats? {
            if (timesUs.size < 2) return null
            val t = timesUs.sortedArray()
            val d = LongArray(t.size - 1) { t[it + 1] - t[it] }
            val sorted = d.sortedArray()
            val positive = sorted.filter { it > 0 }
            if (positive.isEmpty()) return null
            val median = positive[positive.size / 2]
            val gapLimit = median + median / 2
            val gaps = d.count { it > gapLimit }
            val regular = d.filter { it <= gapLimit }
            val off = regular.count { kotlin.math.abs(it - median) * 10 > median }
            return PtsStats(
                medianIntervalUs = median,
                minUs = sorted.first(),
                maxUs = sorted.last(),
                gaps = gaps,
                vfr = regular.isNotEmpty() && off * 20 > regular.size,
                frames = t.size,
                meanIntervalUs = if (regular.isEmpty()) 0.0 else regular.sum().toDouble() / regular.size,
            )
        }
    }
}

/** One preflight fact shown to the user. [Severity.Block] stops analysis; the video still plays. */
data class Finding(val severity: Severity, val code: String, val text: String)

enum class Severity { Block, Warn, Info }

object Preflight {
    /** G1 in docs/ops/cloudflare-budget.md: the API refuses larger videos with 413 `upload_too_large`. */
    const val MAX_UPLOAD_BYTES = 300L * 1024 * 1024
    private const val TRUNCATION_SLACK_US = 1_000_000L

    fun assess(f: SourceFacts): List<Finding> = buildList {
        if (f.mime == null) {
            add(Finding(Severity.Block, "no_video", "No video track in this file"))
            return@buildList
        }
        // Analysis decodes in the cloud, so a missing phone decoder stops playback here, not analysis.
        if (!f.decodable) add(Finding(Severity.Warn, "no_decoder", "This phone can't play ${codecLabel(f.codec)}"))
        if (f.pts == null) add(Finding(Severity.Block, "no_frames", "No readable frames"))
        if (f.durationMeasured) add(Finding(Severity.Info, "no_duration", "No duration in the file: measured from the frames"))
        if (f.samplesCapped) add(Finding(Severity.Info, "timing_capped", "Frame timing checked on the first ${f.pts?.frames} frames"))
        val last = f.lastSampleUs
        if (last != null && !f.samplesCapped && f.durationMs > 0 && last + TRUNCATION_SLACK_US < f.durationMs * 1000) {
            add(Finding(Severity.Warn, "truncated", "Frames stop at ${secs(last / 1000)} of ${secs(f.durationMs)}: the file may be cut short"))
        }
        if (f.bytes > MAX_UPLOAD_BYTES) {
            add(Finding(Severity.Block, "too_large", "${mib(f.bytes)} MiB: uploads are limited to 300 MiB"))
        }
        if (f.isHdr) add(Finding(Severity.Warn, "hdr", "HDR video: trails can't be drawn on HDR frames yet"))
        if (minOf(f.displayWidth, f.displayHeight) in 1 until 720) {
            add(Finding(Severity.Warn, "low_res", "${f.displayWidth}×${f.displayHeight}: below 720p"))
        }
        f.pts?.let { p ->
            if (p.gaps > 0) add(Finding(Severity.Warn, "pts_gaps", "${p.gaps} ${if (p.gaps == 1) "gap" else "gaps"} in frame timing (dropped frames)"))
            if (p.vfr) add(Finding(Severity.Info, "vfr", "Variable frame rate"))
        }
        if (!f.hasAudio) add(Finding(Severity.Info, "no_audio", "No audio: no sound-based rally hints"))
    }

    private fun secs(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
    /** One decimal, rounded up, so a file just over the cap never reads as exactly 300. */
    private fun mib(b: Long) = String.format(Locale.ROOT, "%.1f", Math.ceil(b * 10.0 / (1024 * 1024)) / 10)
}

/** Preflight of the picked recording as the replay screen shows it. */
sealed interface PreflightState {
    data object Checking : PreflightState
    data class Done(val facts: SourceFacts, val findings: List<Finding>) : PreflightState {
        val blocked get() = findings.any { it.severity == Severity.Block }
    }
    data class Failed(val reason: String) : PreflightState
}

/** Short codec name for display. */
fun codecLabel(codec: String?): String = when (codec) {
    "avc1" -> "H.264"
    "hvc1" -> "HEVC"
    "vp09" -> "VP9"
    "vp08" -> "VP8"
    "av01" -> "AV1"
    "video/dolby-vision" -> "Dolby Vision"
    null -> "unknown codec"
    else -> codec
}
