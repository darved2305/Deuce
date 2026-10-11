package com.elevate.app.data.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/** Why a recording ended. */
enum class EndReason { User, Budget, MaxDuration, Storage, Gate, SourceLost, Error }

/**
 * Rolling pieces of about 6 s with CameraX: one `Recording` at a time, a new one started the moment
 * the previous one is finalised, so the camera keeps going and the pieces go out while it records.
 * The recorded frames inside each piece are the camera's own: this class never touches pixels.
 *
 * Each piece finishes with a keyframe-first mp4 that plays alone. Real frame times are read from
 * the finished file ([PieceFiles.probe]); the pieces are put on one continuous timeline.
 * Must be created and used on the main thread; [onPiece] and [onEnded] run on a worker thread.
 */
class SegmentedRecorder(
    private val context: Context,
    private val videoCapture: VideoCapture<Recorder>,
    private val dir: File,
    private val params: RecordingParams,
    private val maxSegmentBytes: Long,
    private val onPiece: (RecordedSegment) -> Unit,
    private val onEnded: (EndReason) -> Unit,
    private val onProgress: (elapsedUs: Long, bytes: Long) -> Unit,
) {
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor()

    private var current: Recording? = null
    private var fileIndex = 0
    private var rolling = false
    private var stopReason: EndReason? = null

    // Main thread: the running totals used for the limits and the progress line.
    private var bytesFinished = 0L
    private var elapsedFinishedUs = 0L

    // Worker thread: the exact timeline of pieces that were kept.
    private var pieceSeq = 0
    private var timelineUs = 0L

    fun start() {
        dir.mkdirs()
        startNext()
    }

    /** Ends the recording cleanly: the open piece is finalised, kept, and sent as the last one. */
    fun stop(reason: EndReason = EndReason.User) {
        if (stopReason == null) stopReason = reason
        current?.stop()
    }

    private fun startNext() {
        val limit = SegmentPlanner.nextFileSizeLimit(bytesFinished, maxSegmentBytes)
        if (limit <= 0) {
            finish(EndReason.Budget)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Audio is part of the recording (hit detection), so there is no silent recording.
            finish(EndReason.Error)
            return
        }
        val file = File(dir, "piece_%05d.mp4".format(fileIndex++))
        val options = FileOutputOptions.Builder(file).setFileSizeLimit(limit).build()
        rolling = false
        current = videoCapture.output.prepareRecording(context, options)
            // Audio on: hit detection uses the sound of the shot.
            .withAudioEnabled()
            .start(main) { event -> onEvent(file, event) }
    }

    private fun onEvent(file: File, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                val inPieceUs = stats.recordedDurationNanos / 1000
                val elapsed = elapsedFinishedUs + inPieceUs
                val bytes = bytesFinished + stats.numBytesRecorded
                onProgress(elapsed, bytes)
                if (stopReason == null && !rolling) {
                    val why = SegmentPlanner.stopReason(bytes, elapsed, params)
                    if (why != null) {
                        stop(if (why == StopReason.Budget) EndReason.Budget else EndReason.MaxDuration)
                    } else if (SegmentPlanner.shouldRoll(inPieceUs)) {
                        rolling = true
                        current?.stop()
                    }
                }
            }
            is VideoRecordEvent.Finalize -> onFinalize(file, event)
            else -> Unit
        }
    }

    private fun onFinalize(file: File, event: VideoRecordEvent.Finalize) {
        val err = event.error
        // These end a piece without damaging it; anything else may have left a broken file.
        val usable = err == VideoRecordEvent.Finalize.ERROR_NONE ||
            err == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
            err == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
            err == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ||
            err == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
        bytesFinished += event.recordingStats.numBytesRecorded
        elapsedFinishedUs += event.recordingStats.recordedDurationNanos / 1000
        val endBecause: EndReason? = stopReason ?: when (err) {
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> EndReason.Budget
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> EndReason.Storage
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> EndReason.SourceLost
            else -> if (usable) null else EndReason.Error
        }
        worker.execute { keep(file, usable) }
        if (endBecause == null && rolling) startNext() else finish(endBecause ?: EndReason.Error)
    }

    private fun finish(reason: EndReason) {
        current = null
        worker.execute { onEnded(reason) }
        worker.shutdown()
    }

    /** Worker: reads the finished piece, puts it on the timeline and hands it on. An empty piece is dropped. */
    private fun keep(file: File, usable: Boolean) {
        val facts = if (usable) PieceFiles.probe(file) else null
        if (facts == null || facts.durationUs < SegmentPlanner.MIN_USEFUL_SEGMENT_US && facts.frames < 2) {
            file.delete()
            return
        }
        val start = timelineUs
        timelineUs += facts.durationUs
        onPiece(
            RecordedSegment(
                seq = pieceSeq++, file = file, bytes = file.length(), sha256 = PieceFiles.sha256(file),
                ptsStartUs = start, ptsEndUs = timelineUs, startsOnSyncSample = facts.startsOnSyncSample,
            ),
        )
    }
}
