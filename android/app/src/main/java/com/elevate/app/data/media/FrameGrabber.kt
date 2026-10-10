package com.elevate.app.data.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One upright still frame of the picked video, for tapping court corners and players on.
 *
 * [bitmap] may be smaller than the source (long side ≤ the requested cap); taps are mapped with
 * [srcWidth] × [srcHeight], the upright source size the bitmap represents (contracts §0 image
 * space), never with the bitmap's own size.
 */
class GrabbedFrame(
    val bitmap: Bitmap,
    val srcWidth: Int,
    val srcHeight: Int,
    /** Presentation time of the frame shown, read from the sample table; null if it could not be read. */
    val framePtsUs: Long?,
    /** False when the exact frame failed and the previous sync frame was used instead. */
    val exact: Boolean,
)

sealed interface FrameGrab {
    data class Ok(val frame: GrabbedFrame) : FrameGrab
    /** [code]: `no_frame` (the decoder returned nothing, seen on Dolby Vision), `unreadable`, `no_access`. */
    data class Failed(val code: String, val message: String) : FrameGrab
}

/**
 * Grabs a frame with [MediaMetadataRetriever]. On the A55 its bitmaps come back upright (rotation
 * applied), checked by `FrameGrabRotationTest`. It returned null for a Dolby Vision clip, so null
 * is a typed failure here, never a blank picture.
 */
class FrameGrabber(private val resolver: ContentResolver) {

    suspend fun grab(uri: Uri, atUs: Long, maxLongSide: Int = 1920): FrameGrab = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            val opened = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                r.setDataSource(pfd.fileDescriptor)
                true
            } ?: false
            if (!opened) return@withContext FrameGrab.Failed("unreadable", "The video could not be opened")

            val codedW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val codedH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (codedW <= 0 || codedH <= 0) return@withContext FrameGrab.Failed("unreadable", "No video size in the file")
            val srcW = if (rotation % 180 == 0) codedW else codedH
            val srcH = if (rotation % 180 == 0) codedH else codedW

            val at = atUs.coerceAtLeast(0)
            var exact = true
            var bmp = frame(r, at, MediaMetadataRetriever.OPTION_CLOSEST, maxLongSide)
            if (bmp == null) {
                exact = false
                bmp = frame(r, at, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, maxLongSide)
            }
            if (bmp == null) return@withContext FrameGrab.Failed("no_frame", "Couldn't read a still frame from this video")
            val scaled = capLongSide(bmp, maxLongSide)
            val pts = runCatching { framePts(uri, at, exact) }.getOrNull()
            FrameGrab.Ok(GrabbedFrame(scaled, srcW, srcH, pts, exact))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            FrameGrab.Failed("no_access", "Access to this video was not granted")
        } catch (e: RuntimeException) {
            FrameGrab.Failed("unreadable", "Couldn't read a still frame from this video")
        } catch (e: java.io.IOException) {
            FrameGrab.Failed("unreadable", "Couldn't read a still frame from this video")
        } finally {
            runCatching { r.release() }
        }
    }

    private fun frame(r: MediaMetadataRetriever, atUs: Long, option: Int, maxLongSide: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // A square box: the long side fits whatever orientation the retriever applies the box in.
            r.getScaledFrameAtTime(atUs, option, maxLongSide, maxLongSide)
        } else {
            r.getFrameAtTime(atUs, option)
        }

    private fun capLongSide(b: Bitmap, maxLongSide: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= maxLongSide) return b
        val s = maxLongSide.toDouble() / long
        val out = b.scale((b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1))
        if (out !== b) b.recycle()
        return out
    }

    /**
     * PTS of the frame the retriever returned, from the video track's sample table: for an exact
     * grab the sample nearest [atUs] between the sync frame before it and the next one; otherwise
     * the sync frame at or before [atUs]. Null when the track cannot be read.
     */
    private fun framePts(uri: Uri, atUs: Long, exact: Boolean): Long? {
        val ex = MediaExtractor()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { ex.setDataSource(it.fileDescriptor) } ?: return null
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            ex.selectTrack(track)
            ex.seekTo(atUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val first = ex.sampleTime
            if (first < 0) return null
            if (!exact) return first
            var best = first
            var n = 0
            var pastTarget = false
            // Decode order: B-frames arrive out of order, so scan to the next sync frame past the target.
            while (ex.advance() && n++ < MAX_SCAN) {
                val t = ex.sampleTime
                if (t < 0) break
                val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                if (sync && pastTarget) break
                if (abs(t - atUs) < abs(best - atUs)) best = t
                if (t >= atUs) pastTarget = true
            }
            return best
        } finally {
            ex.release()
        }
    }

    private companion object {
        /** Bounds the scan on files with very long GOPs. */
        const val MAX_SCAN = 2_000
    }
}
