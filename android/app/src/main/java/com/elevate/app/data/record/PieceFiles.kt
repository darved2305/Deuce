package com.elevate.app.data.record

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/** What was read from a finished piece. */
data class PieceFacts(
    /** Length on its own timeline: last frame time plus one frame interval, minus the first frame time. */
    val durationUs: Long,
    val frames: Int,
    val startsOnSyncSample: Boolean,
)

/** Reads a finished piece's real frame times (never the clock), and joins pieces into one local file. */
object PieceFiles {

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Null when the file has no readable video frames (an empty tail, or a failed write). */
    fun probe(file: File): PieceFacts? {
        if (!file.isFile || file.length() == 0L) return null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { videoOf(ex.getTrackFormat(it)) } ?: return null
            ex.selectTrack(track)
            val times = ArrayList<Long>()
            var firstSync = false
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (times.isEmpty()) firstSync = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                times += t
                if (!ex.advance()) break
            }
            if (times.isEmpty()) return null
            times.sort()
            val intervals = times.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
            val interval = intervals.getOrNull(intervals.size / 2) ?: 33_333L
            return PieceFacts((times.last() - times.first()) + interval, times.size, firstSync)
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        } finally {
            ex.release()
        }
    }

    /**
     * Copies [pieces] one after another into [out] without re-encoding (a muxer pass over the
     * encoded samples). Each piece's samples are moved onto the continuous timeline of
     * [SegmentPlanner.timeline], so the local copy and the server's `pts_start_us` / `pts_end_us`
     * agree. Throws [IOException] when a piece cannot be read.
     */
    fun join(pieces: List<RecordedSegment>, out: File) {
        require(pieces.isNotEmpty()) { "nothing to join" }
        out.parentFile?.mkdirs()
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pieces.first().file.path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { muxer.setOrientationHint(it) }
            } finally {
                retriever.release()
            }
            // Tracks come from the first piece; every piece of one recording has the same formats.
            val trackMap = HashMap<String, Int>() // "video" / "audio" -> muxer track index
            run {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(pieces.first().file.path)
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val kind = when {
                            videoOf(f) -> "video"
                            f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true -> "audio"
                            else -> continue
                        }
                        if (kind !in trackMap) trackMap[kind] = muxer.addTrack(f)
                    }
                } finally {
                    ex.release()
                }
            }
            if ("video" !in trackMap) throw IOException("no video track in the first piece")
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
            val info = MediaCodec.BufferInfo()
            for (piece in pieces) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(piece.file.path)
                    val selected = HashMap<Int, Int>() // extractor track -> muxer track
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val kind = if (videoOf(f)) "video" else if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) "audio" else null
                        val muxTrack = kind?.let { trackMap[it] } ?: continue
                        ex.selectTrack(i)
                        selected[i] = muxTrack
                    }
                    // The piece's own timeline starts at its first sample of any selected track.
                    val base = ex.sampleTime.coerceAtLeast(0)
                    while (true) {
                        val t = ex.sampleTime
                        if (t < 0) break
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.set(0, size, piece.ptsStartUs + (t - base).coerceAtLeast(0), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(selected.getValue(ex.sampleTrackIndex), buffer, info)
                        if (!ex.advance()) break
                    }
                } finally {
                    ex.release()
                }
            }
        } catch (e: RuntimeException) {
            throw IOException("could not join the pieces: ${e.javaClass.simpleName}", e)
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun videoOf(f: MediaFormat) = f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true

    private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
}
