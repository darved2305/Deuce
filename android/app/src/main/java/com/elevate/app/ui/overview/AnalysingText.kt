package com.elevate.app.ui.overview

import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.analysis.coverageLabel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Words for the analysing screen, derived only from [AnalysisUiState]. Pure, so every sentence the
 * screen can say is unit-tested. No percentages: progress is covered seconds of a known clip length.
 */
object AnalysingText {

    fun title(s: AnalysisUiState): String = when (s.state) {
        null -> s.unknownState?.let { "Server state: $it" } ?: "Waiting for the server"
        AnalysisState.AWAITING_UPLOAD -> "Waiting for the upload"
        AnalysisState.UPLOADED -> "Uploaded"
        AnalysisState.QUEUED -> "Queued for the engine"
        AnalysisState.PROCESSING -> "Analysing"
        AnalysisState.PARTIAL -> "Analysing · first results ready"
        AnalysisState.FINALIZING -> "Finalizing the match record"
        AnalysisState.COMPLETED -> "Analysis complete"
        AnalysisState.FAILED -> "Analysis failed"
    }

    /** "state partial · event 13 · engine 0.3.0": the raw facts behind the title. */
    fun facts(s: AnalysisUiState): String = listOfNotNull(
        (s.state?.wire ?: s.unknownState)?.let { "state $it" },
        "event ${s.lastSeq}".takeIf { s.lastSeq > 0 },
        s.engineVersion?.let { "engine $it" },
    ).joinToString(" · ").ifEmpty { "no events yet" }

    /** Source label; null for a live analysis. Anything recorded says so. */
    fun sourceBadge(s: AnalysisUiState): String? = when (val src = s.source) {
        is AnalysisSource.Live -> null
        is AnalysisSource.Recorded -> "Recorded event file (${src.document}) · not a live analysis"
    }

    fun coverage(s: AnalysisUiState): String = coverageLabel(s.coveredUs, s.totalUs)

    /** "3 windows done · 1 failed"; nothing when no window has reported. */
    fun windows(s: AnalysisUiState): String? {
        if (s.windowsDone == 0 && s.windowsFailed == 0) return null
        val done = "${s.windowsDone} window${if (s.windowsDone == 1) "" else "s"} done"
        return if (s.windowsFailed > 0) "$done · ${s.windowsFailed} failed" else done
    }

    /** "2 of 3 window results downloaded"; nothing before any window completes. */
    fun downloads(s: AnalysisUiState): String? =
        if (s.windowsDone == 0) null else "${s.windowsDownloaded} of ${s.windowsDone} window results downloaded"

    fun failureLine(f: com.elevate.app.domain.analysis.WindowFailure): String {
        val range = f.emitRangeUs?.let { String.format(Locale.ROOT, " (%.1f–%.1f s)", it.first / 1e6, it.last / 1e6) }.orEmpty()
        return "Window ${f.windowSeq ?: "?"}$range: ${f.reason}"
    }

    fun stageLabel(type: String): String = when (type) {
        "analysis.queued" -> "Queued"
        "analysis.started" -> "Engine started"
        "window.completed" -> "First window analysed"
        "analysis.partial" -> "First results"
        "analysis.finalizing" -> "Finalizing"
        "analysis.completed" -> "Completed"
        "analysis.failed" -> "Failed"
        else -> type
    }

    /**
     * Stage time: local clock time of the event's `created_at`, plus the time since the analysis was
     * requested (or since the first stage) when both parse. Unparseable times are shown verbatim.
     */
    fun stageTime(createdAt: String, since: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return createdAt
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(zone).format(t)
        val s0 = since?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return clock
        val d = Duration.between(s0, t)
        if (d.isNegative) return clock
        val sec = d.seconds
        val rel = if (sec < 60) "+$sec s" else "+${sec / 60} min ${sec % 60} s"
        return "$clock · $rel"
    }

    /** What the screen offers as the main action, if anything. */
    enum class Action { OPEN_RESULTS, OPEN_PARTIAL, RETRY, NONE }

    fun primaryAction(s: AnalysisUiState): Action = when {
        s.state == AnalysisState.COMPLETED -> Action.OPEN_RESULTS
        s.state == AnalysisState.FAILED -> Action.RETRY
        s.hasPartialResults -> Action.OPEN_PARTIAL
        else -> Action.NONE
    }

    /** Connection notice: offline, catching up, or a sync problem; null when nothing to say. */
    fun connection(s: AnalysisUiState): Pair<String, String>? = when {
        s.offline -> "Waiting for connection" to
            "Showing the last state received${if (s.lastSeq > 0) " (event ${s.lastSeq})" else ""}. It resumes on its own."
        s.syncError != null -> "Could not update" to s.syncError
        s.unresolvedGapSeq != null -> "Event ${s.unresolvedGapSeq} is missing" to
            "Later events are held back until the server sends it; nothing is skipped."
        s.isCatchingUp && s.lastSeq > 0 -> "Catching up" to
            "Fetching events after ${s.lastSeq}. What you see may be behind."
        s.isCatchingUp -> "Connecting" to "Asking the server for this analysis."
        else -> null
    }
}
