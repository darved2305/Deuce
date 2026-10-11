package com.elevate.app.ui.overview

import com.elevate.app.data.analysis.ArtifactAccess
import com.elevate.app.data.analysis.CacheInfo
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.analysis.WindowCoverage
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.StageState
import com.elevate.app.ui.upload.UploadText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One line of the analysing checklist. [value] always carries its denominator when it is a number. */
data class FlowStage(val key: FlowStage.Key, val label: String, val state: StageState, val value: String?) {
    enum class Key { SEND, QUEUE, TRACK, MATCH }
}

/** The "Sending your video" card: confirmed bytes only, rate and ETA only once measured. */
data class SendCard(
    /** "12.0 of 60.7 MiB" (server-confirmed parts), null before any byte count exists. */
    val amount: String?,
    /** Confirmed / total, null when there is nothing measured to draw. */
    val fraction: Float?,
    /** "2.4 MiB/s measured", or "Measuring speed" until the first part is confirmed. */
    val rate: String?,
    /** "about 30 s left", only from the measured rate. */
    val eta: String?,
    /** Preparing step, offline pause, retry or failure in one short line. */
    val note: String?,
)

data class AnalysingFlowState(
    val stages: List<FlowStage>,
    /** Shown while the video is being sent; null afterwards. */
    val send: SendCard?,
    /** At most one short line: why it stopped, offline, or what the server cannot hand out yet. */
    val notice: String?,
    val action: Action,
    /** The upload can still be cancelled from this screen. */
    val canCancel: Boolean,
) {
    enum class Action { NONE, OPEN_RESULTS, OPEN_PARTIAL, RETRY_UPLOAD, CHECK_AGAIN }
}

/**
 * Pure mapping from the real inputs to the analysing screen: the local pipeline step, the upload's
 * measured progress, the server's analysis state and whether window results are served. Nothing
 * advances on a timer and nothing is shown as a percentage; every number has its denominator.
 */
object AnalysingFlow {

    /** Shown when windows are done but `GET /v1/artifacts/{id}` is not deployed (404). */
    const val DETECTIONS_NOT_SERVED = "Detections ready on the server · drawing them needs a server update"
    const val LEAVE_LINE = "You can leave. We'll keep going."

    /** "Your match" value when every window covering the source is in but the engine has not finalized. */
    const val READY_FROM_WINDOWS = "ready · from the windows"

    /** Said whenever the overview is built from the windows alone: nothing of the match record is shown. */
    const val SUMMARY_PENDING = "Match summary (score, landings) pending the engine"

    fun state(
        pipeline: Screen.Pipeline,
        /** Local step before the upload exists ("Identifying the file", "Preparing"), else null. */
        step: String?,
        upload: UploadProgress,
        analysis: AnalysisUiState?,
        artifacts: ArtifactAccess = ArtifactAccess.Unknown,
    ): AnalysingFlowState = state(pipeline, step, upload, analysis, artifacts, cache = null)

    /**
     * As above, plus [cache] ([com.elevate.app.data.analysis.AnalysisSync.cache]): a finished match
     * restored from this phone says "Analysed earlier · engine <v> · <date time>" instead of the
     * offline line (see [analysedEarlier]).
     */
    fun state(
        pipeline: Screen.Pipeline,
        step: String?,
        upload: UploadProgress,
        analysis: AnalysisUiState?,
        artifacts: ArtifactAccess,
        cache: CacheInfo?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AnalysingFlowState {
        val serverState = analysisState(pipeline, analysis)
        val send = sendStage(pipeline, step, upload)
        val queue = queueStage(pipeline, serverState, analysis)
        val track = trackStage(serverState, analysis)
        val match = matchStage(serverState, analysis)
        val sending = send.state == StageState.Active || (send.state == StageState.Failed && pipeline is Screen.Pipeline.Failed)
        return AnalysingFlowState(
            stages = listOf(send, queue, track, match),
            send = if (sending) sendCard(pipeline, step, upload) else null,
            notice = notice(pipeline, analysis, artifacts, cache, zone),
            action = action(pipeline, serverState, analysis),
            canCancel = pipeline == Screen.Pipeline.Uploading,
        )
    }

    /** Server state: the events when any arrived, else the answer of `POST /v1/analyses`. */
    private fun analysisState(pipeline: Screen.Pipeline, analysis: AnalysisUiState?): AnalysisState? =
        analysis?.state ?: (pipeline as? Screen.Pipeline.Started)?.let { AnalysisState.fromWire(it.state) }

    private fun sendStage(pipeline: Screen.Pipeline, step: String?, upload: UploadProgress): FlowStage {
        fun st(state: StageState, value: String?) = FlowStage(FlowStage.Key.SEND, "Sending your video", state, value)
        return when (pipeline) {
            Screen.Pipeline.Idle -> st(StageState.Pending, null)
            is Screen.Pipeline.StartingAnalysis, is Screen.Pipeline.Started -> st(StageState.Done, sentValue(upload))
            is Screen.Pipeline.Failed ->
                when (pipeline.stage) {
                    "analysis" -> st(StageState.Done, sentValue(upload))
                    CANCEL_STAGE -> st(StageState.Failed, "cancel failed")
                    else -> st(StageState.Failed, "stopped")
                }
            Screen.Pipeline.Uploading -> when {
                step != null -> st(StageState.Active, "preparing")
                else -> when (upload) {
                    is UploadProgress.Uploading -> st(StageState.Active, "${upload.partsDone}/${upload.partCount} parts")
                    is UploadProgress.PausedOffline -> st(StageState.Active, "paused")
                    UploadProgress.Completing -> st(StageState.Active, "finishing")
                    is UploadProgress.Completed -> st(StageState.Done, sentValue(upload))
                    is UploadProgress.Failed -> st(StageState.Active, "retrying")
                    else -> st(StageState.Active, "preparing")
                }
            }
        }
    }

    private fun sentValue(upload: UploadProgress): String =
        (upload as? UploadProgress.Completed)?.let { "${UploadText.mib(it.serverBytes)} MiB" } ?: "done"

    private fun sendCard(pipeline: Screen.Pipeline, step: String?, upload: UploadProgress): SendCard {
        if (pipeline is Screen.Pipeline.Failed) {
            val kept = upload as? UploadProgress.Failed
            val note = if (pipeline.stage == CANCEL_STAGE) "$CANCEL_FAILED · ${pipeline.reason}" else pipeline.reason
            return SendCard(kept?.let { UploadText.ofTotal(it.acceptedBytes, it.totalBytes) }, kept?.let { UploadText.fraction(it) }, null, null, note)
        }
        if (step != null) return SendCard(null, null, null, null, step)
        return when (upload) {
            is UploadProgress.Uploading -> SendCard(
                amount = UploadText.ofTotal(upload.acceptedBytes, upload.totalBytes),
                fraction = UploadText.fraction(upload),
                rate = UploadText.rate(upload.bytesPerSecond)?.let { "$it measured" } ?: "Measuring speed",
                eta = eta(upload.etaSeconds),
                note = UploadText.retry(upload),
            )
            is UploadProgress.PausedOffline -> SendCard(
                UploadText.ofTotal(upload.acceptedBytes, upload.totalBytes), UploadText.fraction(upload), null, null, "Offline · resumes on its own",
            )
            is UploadProgress.Failed -> SendCard(
                UploadText.ofTotal(upload.acceptedBytes, upload.totalBytes), UploadText.fraction(upload), null, null, upload.failure.message,
            )
            is UploadProgress.Preparing -> SendCard(null, null, null, null, upload.step)
            UploadProgress.Completing -> SendCard(null, null, null, null, "The server is joining the parts")
            else -> SendCard(null, null, null, null, "Waiting to start")
        }
    }

    /** "about 30 s left" / "about 3 min left"; null without a measured rate. */
    fun eta(seconds: Long?): String? = when {
        seconds == null || seconds < 0 -> null
        seconds < 60 -> "about ${seconds.coerceAtLeast(1)} s left"
        seconds < 3600 -> "about ${ceil(seconds / 60.0).toInt()} min left"
        else -> String.format(Locale.ROOT, "about %d h %d min left", seconds / 3600, (seconds % 3600) / 60)
    }

    private fun queueStage(pipeline: Screen.Pipeline, s: AnalysisState?, a: AnalysisUiState?): FlowStage {
        fun st(state: StageState, value: String?) = FlowStage(FlowStage.Key.QUEUE, "Queued for the engine", state, value)
        return when (pipeline) {
            Screen.Pipeline.Idle, Screen.Pipeline.Uploading -> st(StageState.Pending, null)
            is Screen.Pipeline.StartingAnalysis -> st(StageState.Active, "starting")
            is Screen.Pipeline.Failed -> if (pipeline.stage == "analysis") st(StageState.Failed, "not started") else st(StageState.Pending, null)
            is Screen.Pipeline.Started -> when (s) {
                null, AnalysisState.AWAITING_UPLOAD, AnalysisState.UPLOADED, AnalysisState.QUEUED -> st(StageState.Active, "waiting")
                AnalysisState.FAILED -> if (engineStarted(a)) st(StageState.Done, "done") else st(StageState.Failed, "stopped")
                else -> st(StageState.Done, "done")
            }
        }
    }

    private fun engineStarted(a: AnalysisUiState?): Boolean =
        a?.stages?.any { it.type == "analysis.started" || it.type == "window.completed" } == true || (a?.windowsDone ?: 0) > 0

    private fun trackStage(s: AnalysisState?, a: AnalysisUiState?): FlowStage {
        fun st(state: StageState, value: String?) = FlowStage(FlowStage.Key.TRACK, "Tracking the shuttle", state, value)
        if (WindowCoverage.readyFromWindows(a)) return st(StageState.Done, a?.let(::tracking))
        return when (s) {
            AnalysisState.PROCESSING, AnalysisState.PARTIAL -> st(StageState.Active, a?.let(::tracking) ?: "starting")
            AnalysisState.FINALIZING, AnalysisState.COMPLETED -> st(StageState.Done, a?.let(::tracking))
            AnalysisState.FAILED -> if (engineStarted(a)) st(StageState.Failed, a?.let(::tracking)) else st(StageState.Pending, null)
            else -> st(StageState.Pending, null)
        }
    }

    /** "3 windows · 18.0 of 484.5 s" (+ " · 1 failed"); covered seconds of the known clip length only. */
    fun tracking(a: AnalysisUiState): String {
        val covered = String.format(Locale.ROOT, "%.1f", a.coveredUs / 1e6)
        val total = a.totalUs?.takeIf { it > 0 }
        val time = if (total != null) "$covered of ${String.format(Locale.ROOT, "%.1f", total / 1e6)} s" else "$covered s · length unknown"
        val windows = when (a.windowsDone) {
            0 -> null
            1 -> "1 window"
            else -> "${a.windowsDone} windows"
        }
        val failed = a.windowsFailed.takeIf { it > 0 }?.let { "$it failed" }
        return listOfNotNull(windows, time, failed).joinToString(" · ")
    }

    private fun matchStage(s: AnalysisState?, a: AnalysisUiState?): FlowStage {
        fun st(state: StageState, value: String?) = FlowStage(FlowStage.Key.MATCH, "Your match", state, value)
        return when {
            WindowCoverage.readyFromWindows(a) -> st(StageState.Done, READY_FROM_WINDOWS)
            else -> when (s) {
            AnalysisState.COMPLETED -> st(StageState.Done, "ready")
            AnalysisState.FINALIZING -> st(StageState.Active, "finishing")
            else -> st(StageState.Pending, "next")
            }
        }
    }

    /**
     * "Analysed earlier · engine 0.3.0 · 11 Oct 2026 14:05" when the shown result was restored from
     * this phone's cache, the match is finished (server `completed`, or every window in), and either
     * the network is unreachable or the analysis is `completed` (nothing more will arrive). The time is
     * the server's `analysis.completed` event, else when the cache was written; unknown parts are left
     * out. Null otherwise (an unfinished analysis offline keeps "Offline · showing the last update").
     */
    fun analysedEarlier(a: AnalysisUiState?, cache: CacheInfo?, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (a == null || cache == null || !cache.fromCache) return null
        val completed = a.state == AnalysisState.COMPLETED
        val finished = completed || WindowCoverage.readyFromWindows(a)
        if (!finished || !(a.offline || completed)) return null
        val engine = a.engineVersion?.takeIf { it.isNotBlank() }?.let { "engine $it" }
        val atMs = a.stages.lastOrNull { it.type == "analysis.completed" }?.createdAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: cache.cachedAtMs
        val time = atMs?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)) }
        return listOfNotNull(ANALYSED_EARLIER, engine, time).joinToString(" · ")
    }

    const val ANALYSED_EARLIER = "Analysed earlier"

    /**
     * `Screen.Pipeline.Failed.stage` when cancelling the upload failed (e.g. the abort request could
     * not reach the server). Its retry action cancels again; it never restarts the upload.
     */
    const val CANCEL_STAGE = "cancel"
    const val CANCEL_FAILED = "Could not cancel the upload · Try again cancels it"

    private fun notice(pipeline: Screen.Pipeline, a: AnalysisUiState?, artifacts: ArtifactAccess, cache: CacheInfo?, zone: ZoneId): String? = when {
        pipeline is Screen.Pipeline.Failed && pipeline.stage == "analysis" -> "Could not start the analysis · ${pipeline.reason}"
        pipeline is Screen.Pipeline.Failed -> null // the send card carries the reason
        a == null -> null
        a.failureReason != null -> "Stopped · ${a.failureReason}"
        analysedEarlier(a, cache, zone) != null -> analysedEarlier(a, cache, zone)
        a.offline -> "Offline · showing the last update"
        a.syncError != null -> "Could not update · ${a.syncError}"
        a.windowsDone > 0 && a.windowsDownloaded < a.windowsDone && artifacts is ArtifactAccess.NotServed -> DETECTIONS_NOT_SERVED
        WindowCoverage.readyFromWindows(a) -> SUMMARY_PENDING
        else -> null
    }

    private fun action(pipeline: Screen.Pipeline, s: AnalysisState?, a: AnalysisUiState?): AnalysingFlowState.Action = when {
        pipeline is Screen.Pipeline.Failed -> AnalysingFlowState.Action.RETRY_UPLOAD
        pipeline !is Screen.Pipeline.Started -> AnalysingFlowState.Action.NONE
        s == AnalysisState.COMPLETED -> AnalysingFlowState.Action.OPEN_RESULTS
        s == AnalysisState.FAILED -> AnalysingFlowState.Action.CHECK_AGAIN
        WindowCoverage.readyFromWindows(a) -> AnalysingFlowState.Action.OPEN_RESULTS
        a?.hasPartialResults == true -> AnalysingFlowState.Action.OPEN_PARTIAL
        a != null && (a.offline || a.syncError != null) -> AnalysingFlowState.Action.CHECK_AGAIN
        else -> AnalysingFlowState.Action.NONE
    }

    /**
     * Short status for the pill on the video screen ("Analysing · 18/485 s"); null when nothing runs.
     * Same sources as [state]: confirmed bytes, server state, covered seconds of the known length.
     */
    fun pill(pipeline: Screen.Pipeline, step: String?, upload: UploadProgress, analysis: AnalysisUiState?): String? = when (pipeline) {
        Screen.Pipeline.Idle -> null
        Screen.Pipeline.Uploading -> when {
            step != null -> "Sending · preparing"
            upload is UploadProgress.Uploading ->
                "Sending · ${UploadText.mib(upload.acceptedBytes)}/${UploadText.mib(upload.totalBytes)} MiB"
            upload is UploadProgress.PausedOffline -> "Sending · paused offline"
            else -> "Sending your video"
        }
        is Screen.Pipeline.StartingAnalysis -> "Starting analysis"
        is Screen.Pipeline.Failed -> when (pipeline.stage) {
            "analysis" -> "Analysis not started · retry"
            CANCEL_STAGE -> "Cancel failed · tap to cancel again"
            else -> "Upload stopped · retry"
        }
        is Screen.Pipeline.Started -> when (analysisState(pipeline, analysis)) {
            AnalysisState.COMPLETED -> "Your match is ready"
            AnalysisState.FAILED -> "Analysis stopped"
            AnalysisState.FINALIZING -> "Finishing your match"
            AnalysisState.PROCESSING, AnalysisState.PARTIAL -> if (WindowCoverage.readyFromWindows(analysis)) "Windows done · summary pending" else {
                val total = analysis?.totalUs?.takeIf { it > 0 }
                val covered = ((analysis?.coveredUs ?: 0) / 1e6).roundToInt()
                if (total != null) "Analysing · $covered/${(total / 1e6).roundToInt()} s" else "Analysing · $covered s"
            }
            else -> "Queued for the engine"
        }
    }

    /**
     * Whether a running pipeline belongs to the file being started: same source uri, or the same
     * sha256 (ignoring case). Only then does starting again reopen the running Analysing screen.
     */
    fun sameFile(runningUri: String?, runningSha: String?, newUri: String?, newSha: String?): Boolean =
        (runningUri != null && runningUri == newUri) ||
            (runningSha != null && newSha != null && runningSha.equals(newSha, ignoreCase = true))

    /** "Badminton · 8:04 · 1080p50" from the real probe (short side + nominal fps); unknown parts left out. */
    fun heroLine(sport: String?, durationMs: Long?, width: Int?, height: Int?, fps: Double?): String? {
        val sportLabel = sport?.replace('_', ' ')?.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val length = durationMs?.takeIf { it > 0 }?.let { ms ->
            val s = ms / 1000
            if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
        val short = if (width != null && height != null && width > 0 && height > 0) minOf(width, height) else null
        val format = short?.let { "${it}p" + (fps?.takeIf { f -> f > 0 && f.isFinite() }?.roundToInt()?.toString() ?: "") }
        return listOfNotNull(sportLabel, length, format).joinToString(" · ").ifEmpty { null }
    }

    /** One short line when there are no rallies to list. */
    fun ralliesEmpty(analysis: AnalysisUiState?): String =
        if (analysis?.state == AnalysisState.COMPLETED) "Rallies appear when the match record is served"
        else "Rallies appear when the engine finishes"
}
