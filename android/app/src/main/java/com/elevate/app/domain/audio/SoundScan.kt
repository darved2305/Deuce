package com.elevate.app.domain.audio

/** What the local sound scan is doing, for the timeline. Every number here is measured. */
sealed interface SoundScanState {
    /** Decoding: [decodedMs] of audio read so far out of [totalMs] (the track duration, null when the file has none). */
    data class Analysing(val decodedMs: Long, val totalMs: Long?) : SoundScanState {
        /** 0..1 progress, or null when there is no measured denominator. */
        val fraction: Float? get() = totalMs?.takeIf { it > 0 }?.let { (decodedMs.toFloat() / it).coerceIn(0f, 1f) }
    }

    /** The clip has no audio track. */
    data object NoAudio : SoundScanState

    /** Finished: [onsets] heard in [audioMs] of audio; candidates are the grouping of these onsets. */
    data class Done(val onsets: Int, val audioMs: Long) : SoundScanState

    /** Decoding failed (unsupported or damaged audio); [reason] is shown as is. */
    data class Failed(val reason: String) : SoundScanState
}
