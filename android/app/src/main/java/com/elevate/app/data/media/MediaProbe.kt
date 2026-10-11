package com.elevate.app.data.media

import android.content.ContentResolver
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.elevate.app.domain.media.PtsStats
import com.elevate.app.domain.media.SourceFacts
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads [SourceFacts] with [MediaExtractor]: track formats plus every video sample's timestamp.
 * Walks the sample table only (no sample data is copied), so memory stays flat for 4K files.
 * Blocking I/O: call on Dispatchers.IO. The sample walk takes seconds on long files (7 s for
 * 24k frames of 1080p H.264, 21 s for 30k frames of VP9 WebM on the A55) and stops on cancellation.
 */
class MediaProbe(private val resolver: ContentResolver) {

    /** Throws [IOException] when the file cannot be opened or parsed. */
    suspend fun probe(uri: Uri): SourceFacts {
        val bytes = sizeOf(uri)
        val ex = MediaExtractor()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { ex.setDataSource(it.fileDescriptor) }
                ?: throw IOException("cannot open $uri")
        } catch (e: SecurityException) {
            ex.release()
            throw e
        } catch (e: RuntimeException) {
            ex.release()
            throw IOException("not a readable video: ${e.message}", e)
        }
        try {
            return read(ex, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            // Damaged files can open, then throw from getTrackFormat/advance (IllegalArgument/State).
            throw IOException("damaged video: ${e.message}", e)
        } finally {
            ex.release()
        }
    }

    private suspend fun read(ex: MediaExtractor, bytes: Long): SourceFacts {
        var video: Pair<Int, MediaFormat>? = null
        var audio: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (video == null && mime.startsWith("video/")) video = i to f
            if (audio == null && mime.startsWith("audio/")) audio = f
        }
        val audioRate = audio?.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
        val (track, vf) = video ?: return SourceFacts(
            bytes, null, null, 0, 0, 0, 0, null, null, audio != null, audioRate, false, false, null,
        )
        val mime = vf.getString(MediaFormat.KEY_MIME)!!
        val times = sampleTimes(ex, track)
        val stats = PtsStats.from(times)
        val trackMs = vf.longOrNull(MediaFormat.KEY_DURATION)?.takeIf { it > 0 }?.div(1000)
        // No duration in the file (e.g. some WebM recordings): first to last frame plus one interval.
        val measuredMs = if (stats == null) 0L else (times.max() - times.min() + stats.medianIntervalUs) / 1000
        return SourceFacts(
            bytes = bytes,
            mime = mime,
            codec = CODECS[mime] ?: mime,
            width = vf.intOrNull(MediaFormat.KEY_WIDTH) ?: 0,
            height = vf.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0,
            rotation = vf.intOrNull(MediaFormat.KEY_ROTATION) ?: 0,
            durationMs = trackMs ?: measuredMs,
            containerFps = vf.numberOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat(),
            pts = stats,
            hasAudio = audio != null,
            audioRateHz = audioRate,
            isHdr = vf.intOrNull(MediaFormat.KEY_COLOR_TRANSFER) in HDR_TRANSFERS,
            decodable = decoderFor(vf),
            lastSampleUs = times.maxOrNull(),
            durationMeasured = trackMs == null && stats != null,
            samplesCapped = times.size >= MAX_SAMPLES,
            frameTimesUs = times.sortedArray(),
        )
    }

    private fun sizeOf(uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: throw IOException("cannot open $uri")
    }

    private suspend fun sampleTimes(ex: MediaExtractor, track: Int): LongArray {
        ex.selectTrack(track)
        ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var out = LongArray(4096)
        var n = 0
        while (n < MAX_SAMPLES) {
            val t = ex.sampleTime
            if (t == -1L) break // -1 = no more samples; other negative PTS are real (early B-frames)
            if (n == out.size) out = out.copyOf(n * 2)
            if (n % 1024 == 0) currentCoroutineContext().ensureActive()
            out[n++] = t
            if (!ex.advance()) break
        }
        return out.copyOf(n)
    }

    private fun decoderFor(f: MediaFormat): Boolean {
        // A minimal format (mime, size, profile): container extras such as frame rate can make
        // findDecoderForFormat reject a format a decoder would actually play.
        val w = f.intOrNull(MediaFormat.KEY_WIDTH) ?: return false
        val h = f.intOrNull(MediaFormat.KEY_HEIGHT) ?: return false
        val probe = MediaFormat.createVideoFormat(f.getString(MediaFormat.KEY_MIME)!!, w, h)
        f.intOrNull(MediaFormat.KEY_PROFILE)?.let { probe.setInteger(MediaFormat.KEY_PROFILE, it) }
        return runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) != null }.getOrDefault(false)
    }

    private companion object {
        /** About 3 h at 60 fps: bounds the scan of a damaged sample table. */
        const val MAX_SAMPLES = 700_000
        val HDR_TRANSFERS = setOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)
        val CODECS = mapOf(
            MediaFormat.MIMETYPE_VIDEO_AVC to "avc1",
            MediaFormat.MIMETYPE_VIDEO_HEVC to "hvc1",
            MediaFormat.MIMETYPE_VIDEO_VP9 to "vp09",
            MediaFormat.MIMETYPE_VIDEO_VP8 to "vp08",
            "video/av01" to "av01",
        )
    }
}

private fun MediaFormat.intOrNull(key: String) = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
private fun MediaFormat.longOrNull(key: String) = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

/** KEY_FRAME_RATE is an int in some containers and a float in others. */
private fun MediaFormat.numberOrNull(key: String): Number? = if (!containsKey(key)) null
else runCatching<Number> { getInteger(key) }.getOrElse { runCatching<Number> { getFloat(key) }.getOrNull() }
