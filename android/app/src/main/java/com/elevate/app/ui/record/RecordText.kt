package com.elevate.app.ui.record

import com.elevate.app.data.record.NetworkState
import com.elevate.app.data.record.RecordingParams
import com.elevate.app.data.record.RecordingParamsChooser
import com.elevate.app.data.record.SegmentUploadStatus
import com.elevate.app.data.record.UploadNetwork
import com.elevate.app.data.record.UploadNetworkPolicy
import java.util.Locale

/** The words on the Record screen, as pure functions of real counts. */
object RecordText {
    fun elapsed(us: Long): String {
        val s = (us / 1_000_000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun megabytes(bytes: Long): String = String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))

    /** "1080p, 60 fps, up to 9 min": what is being recorded and how long the 300 MB limit allows. */
    fun paramsLine(p: RecordingParams): String =
        "${p.quality.height}p, ${p.fps} fps, up to ${p.maxDurationMinutes} min"

    /** "About 126 MB of 300 MB": estimated while recording, from the bytes the recorder wrote. */
    fun sizeLine(bytes: Long): String = "${megabytes(bytes)} of ${megabytes(RecordingParamsChooser.VIDEO_BYTE_CAP)}"

    /**
     * The live line: pieces confirmed by the server and the analysis state, e.g.
     * "3 pieces sent, analysis started". Counts only; never a guess.
     */
    fun uploadLine(s: SegmentUploadStatus, policy: UploadNetworkPolicy, net: NetworkState): String {
        UploadNetwork.waitingMessage(policy, net)?.let { if (s.recorded > s.sent) return it }
        s.failure?.let { return it.message }
        val pieces = when (s.sent) {
            0 -> "No pieces sent yet"
            1 -> "1 piece sent"
            else -> "${s.sent} pieces sent"
        }
        val analysis = when {
            s.analysisStarted -> ", analysis started"
            s.analysisError != null -> ", analysis not started yet"
            else -> ""
        }
        return pieces + analysis
    }
}
