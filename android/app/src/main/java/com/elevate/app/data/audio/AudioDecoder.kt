package com.elevate.app.data.audio

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Receives decoded audio: [count] mono samples, the first presented at [ptsUs]. The array is reused after return. */
fun interface PcmSink {
    fun onPcm(mono: FloatArray, count: Int, ptsUs: Long, sampleRateHz: Int)
}

/**
 * Decodes the first audio track of a video with [MediaExtractor] + [MediaCodec] (synchronous
 * buffer loop) into mono float PCM, streamed chunk by chunk to a [PcmSink]; the track is never
 * held in memory. Time base: each output buffer's `presentationTimeUs` (source presentation
 * time). Blocking: call on Dispatchers.IO. Stops on coroutine cancellation and always releases
 * the codec and extractor.
 *
 * Verified in the MediaCodec / MediaFormat reference (developer.android.com, read 2026-10-11):
 * raw audio buffers hold whole interleaved frames, one sample per channel in channel order,
 * 16-bit signed or float in native byte order; float only when the output format's
 * KEY_PCM_ENCODING says ENCODING_PCM_FLOAT, and a missing key means signed 16-bit; since API 21
 * a dequeued output buffer's position/limit are the valid data range; end of stream is
 * BUFFER_FLAG_END_OF_STREAM on input and comes back on (possibly empty) last output.
 */
class AudioDecoder(private val resolver: ContentResolver) {

    sealed interface Result {
        /**
         * Decoding finished. [firstPtsUs]..[endUs] is the decoded span, [trackDurationUs] the
         * container's duration (null if absent), [wallMs] the wall-clock decode time.
         */
        data class Decoded(
            val mime: String,
            val sampleRateHz: Int,
            val channels: Int,
            val pcmEncoding: Int,
            val firstPtsUs: Long,
            val endUs: Long,
            val trackDurationUs: Long?,
            val samples: Long,
            val wallMs: Long,
        ) : Result {
            val decodedMs: Long get() = ((endUs - firstPtsUs).coerceAtLeast(0)) / 1000

            /** Seconds of audio decoded per wall-clock second (e.g. 40 = 40× real time). */
            val speedX: Double get() = if (wallMs <= 0) 0.0 else decodedMs.toDouble() / wallMs
        }

        /** The file has no audio track. */
        data object NoAudio : Result
    }

    /** First audio track's container duration in µs, null when there is none or no duration. Throws [IOException]. */
    fun audioDurationUs(uri: Uri): Long? = withExtractor(uri) { ex ->
        firstAudioTrack(ex)?.second?.let { f -> if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null }
    }

    /** Throws [IOException] when the file or its audio cannot be read or decoded. */
    suspend fun decode(uri: Uri, sink: PcmSink): Result {
        val ex = open(uri)
        try {
            val (track, format) = firstAudioTrack(ex) ?: return Result.NoAudio
            return decodeTrack(ex, track, format, sink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            // Damaged files and codec failures (CodecException is an IllegalStateException).
            throw IOException("audio could not be decoded: ${e.message}", e)
        } finally {
            ex.release()
        }
    }

    private suspend fun decodeTrack(ex: MediaExtractor, track: Int, format: MediaFormat, sink: PcmSink): Result {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null
        ex.selectTrack(track)
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: IllegalArgumentException) {
            throw IOException("no decoder for $mime", e)
        }
        val t0 = System.nanoTime()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var out: Out? = null
            var mono = FloatArray(0)
            var inputDone = false
            var firstPts = Long.MIN_VALUE
            var endUs = 0L
            var samples = 0L
            var idle = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i) ?: throw IOException("no input buffer")
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                        progressed = true
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    out = Out.from(codec.outputFormat)
                    progressed = true
                } else if (o >= 0) {
                    progressed = true
                    val fmt = out ?: Out.from(codec.outputFormat).also { out = it }
                    val buf = codec.getOutputBuffer(o)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frames = info.size / (fmt.bytesPerSample * fmt.channels)
                        if (mono.size < frames) mono = FloatArray(frames)
                        downMix(buf.order(ByteOrder.nativeOrder()), fmt, frames, mono)
                        if (firstPts == Long.MIN_VALUE) firstPts = info.presentationTimeUs
                        sink.onPcm(mono, frames, info.presentationTimeUs, fmt.sampleRate)
                        samples += frames
                        endUs = maxOf(endUs, info.presentationTimeUs + frames * 1_000_000L / fmt.sampleRate)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        val f = out!!
                        return Result.Decoded(
                            mime, f.sampleRate, f.channels, f.encoding,
                            if (firstPts == Long.MIN_VALUE) 0 else firstPts, endUs, trackDurationUs, samples,
                            (System.nanoTime() - t0) / 1_000_000,
                        )
                    }
                }
                idle = if (progressed) 0 else idle + 1
                // ~10 s without any input or output means a stuck codec, not a slow one.
                if (idle > STALL_LIMIT) throw IOException("audio decoder stalled")
            }
        } finally {
            try { codec.stop() } catch (_: IllegalStateException) { /* never started or already failed */ }
            codec.release()
        }
    }

    /** Output PCM layout, read from the codec's output format. */
    private class Out(val sampleRate: Int, val channels: Int, val encoding: Int) {
        val bytesPerSample: Int get() = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }

        companion object {
            fun from(f: MediaFormat): Out {
                val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                if (enc != AudioFormat.ENCODING_PCM_16BIT && enc != AudioFormat.ENCODING_PCM_FLOAT && enc != AudioFormat.ENCODING_PCM_8BIT) {
                    throw IOException("unsupported PCM encoding $enc")
                }
                val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (rate <= 0 || ch <= 0) throw IOException("bad audio format: $rate Hz, $ch channels")
                return Out(rate, ch, enc)
            }
        }
    }

    /** Averages interleaved channels into [dst] (−1..1). */
    private fun downMix(buf: ByteBuffer, f: Out, frames: Int, dst: FloatArray) {
        val ch = f.channels
        val inv = 1f / ch
        when (f.encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += fb.get()
                    dst[i] = s * inv
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                // 8-bit PCM is unsigned (offset 128).
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += ((buf.get().toInt() and 0xFF) - 128) / 128f
                    dst[i] = s * inv
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += sb.get() / 32768f
                    dst[i] = s * inv
                }
            }
        }
    }

    private fun open(uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { ex.setDataSource(it.fileDescriptor) }
                ?: throw IOException("cannot open $uri")
        } catch (e: SecurityException) {
            ex.release()
            throw e
        } catch (e: IOException) {
            ex.release()
            throw e
        } catch (e: RuntimeException) {
            ex.release()
            throw IOException("not a readable media file: ${e.message}", e)
        }
        return ex
    }

    private fun <T> withExtractor(uri: Uri, block: (MediaExtractor) -> T): T {
        val ex = open(uri)
        try {
            return block(ex)
        } catch (e: RuntimeException) {
            if (e is SecurityException) throw e
            throw IOException("damaged media file: ${e.message}", e)
        } finally {
            ex.release()
        }
    }

    private fun firstAudioTrack(ex: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i to f
        }
        return null
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val STALL_LIMIT = 1_000
    }
}
