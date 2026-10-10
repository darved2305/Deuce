package com.elevate.app.data.audio

import android.content.ContentResolver
import android.net.Uri
import com.elevate.app.domain.audio.Onset
import com.elevate.app.domain.audio.OnsetDetector
import com.elevate.app.domain.audio.OnsetParams
import com.elevate.app.domain.audio.RallyCandidate
import com.elevate.app.domain.audio.RallyCandidates
import com.elevate.app.domain.audio.RallyParams
import com.elevate.app.domain.audio.SoundScanState
import java.io.IOException

/**
 * Decoder → onset detector → rally candidates, in one call. Blocking: run on Dispatchers.IO
 * in a coroutine the screen cancels when the user leaves or picks another video.
 *
 * Times: buffer presentation time minus [originUs]. Pass the source's first presented video
 * frame time as [originUs] when it is not 0 so times follow contracts §0 (alignment of audio and
 * video timelines on real files is UNVERIFIED until checked on the device).
 */
class SoundRallyScanner(
    resolver: ContentResolver,
    private val onsetParams: OnsetParams = OnsetParams(),
    private val rallyParams: RallyParams = RallyParams(),
) {
    private val decoder = AudioDecoder(resolver)

    sealed interface Outcome {
        data class Found(
            val onsets: List<Onset>,
            val candidates: List<RallyCandidate>,
            val decode: AudioDecoder.Result.Decoded,
        ) : Outcome {
            val state: SoundScanState get() = SoundScanState.Done(onsets.size, decode.decodedMs)
        }

        data object NoAudio : Outcome
    }

    /**
     * [onProgress] gets [SoundScanState.Analysing] about every 0.5 s of decoded audio, with the
     * audio track's container duration as the denominator ([durationMsHint] when it has none).
     * Throws [IOException] when the audio cannot be decoded.
     */
    suspend fun scan(
        uri: Uri,
        durationMsHint: Long? = null,
        originUs: Long = 0,
        onProgress: (SoundScanState.Analysing) -> Unit = {},
    ): Outcome {
        val totalMs = decoder.audioDurationUs(uri)?.div(1000) ?: durationMsHint
        var detector: OnsetDetector? = null
        var rate = 0
        var lastReportMs = Long.MIN_VALUE
        val result = decoder.decode(uri) { mono, count, ptsUs, sampleRateHz ->
            val d = detector ?: OnsetDetector(sampleRateHz, onsetParams).also { detector = it; rate = sampleRateHz }
            if (sampleRateHz != rate) throw IOException("audio sample rate changed mid-stream ($rate → $sampleRateHz Hz)")
            d.accept(mono, count, ptsUs - originUs)
            val atMs = (ptsUs - originUs) / 1000
            if (lastReportMs == Long.MIN_VALUE || atMs - lastReportMs >= PROGRESS_STEP_MS) {
                lastReportMs = atMs
                onProgress(SoundScanState.Analysing(atMs.coerceAtLeast(0), totalMs))
            }
        }
        return when (result) {
            AudioDecoder.Result.NoAudio -> Outcome.NoAudio
            is AudioDecoder.Result.Decoded -> {
                val onsets = detector?.finish().orEmpty()
                val durationMs = result.trackDurationUs?.div(1000) ?: durationMsHint
                Outcome.Found(onsets, RallyCandidates.group(onsets, durationMs, rallyParams), result)
            }
        }
    }

    private companion object {
        const val PROGRESS_STEP_MS = 500L
    }
}
