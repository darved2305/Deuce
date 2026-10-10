package com.elevate.app.domain.analysis

import com.elevate.app.data.api.AnalysisGetResponse
import com.elevate.app.data.events.EventLog

/** Analysis states, exact contract strings (contracts §3). */
enum class AnalysisState(val wire: String) {
    AWAITING_UPLOAD("awaiting_upload"),
    UPLOADED("uploaded"),
    QUEUED("queued"),
    PROCESSING("processing"),
    PARTIAL("partial"),
    FINALIZING("finalizing"),
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED

    companion object {
        fun fromWire(s: String?): AnalysisState? = entries.firstOrNull { it.wire == s }
    }
}

/** Where the state shown came from. Anything but [Live] must be labelled as such on screen. */
sealed interface AnalysisSource {
    data class Live(val analysisId: String) : AnalysisSource
    /** A recorded event file (`fixture_events*.jsonl`) replayed through [com.elevate.app.data.events.EventReplay]. */
    data class Recorded(val document: String) : AnalysisSource
}

/** One server stage, timed by its own event's `created_at` (ISO-8601, verbatim). */
data class AnalysisStage(val type: String, val seq: Long, val createdAt: String)

data class WindowFailure(val windowSeq: Int?, val reason: String, val emitRangeUs: LongRange?, val createdAt: String)

/** Download problem for one `window.completed` artifact (route missing, expired URL, bad JSON…). */
data class ArtifactProblem(val windowSeq: Int?, val artifactId: String, val reason: String, val attempts: Int, val gaveUp: Boolean)

/**
 * Everything the analysing screen may say. Every number is derived from applied events; nothing
 * is estimated. [coveredUs] / [totalUs] is the only progress figure, and only when [totalUs] is known.
 */
data class AnalysisUiState(
    val source: AnalysisSource,
    /** null before any event or server answer: the screen says "Waiting for the server". */
    val state: AnalysisState?,
    /** Raw state string when it is not one of the contract's (shown verbatim, never mapped). */
    val unknownState: String? = null,
    val lastSeq: Long = 0,
    /** Merged `emit_range_us` of applied `window.completed` events (contracts §3). */
    val coverageUs: List<LongRange> = emptyList(),
    val coveredUs: Long = 0,
    /** Source duration in µs, null when the clip length is unknown (then no denominator is invented). */
    val totalUs: Long? = null,
    val windowsDone: Int = 0,
    val windowFailures: List<WindowFailure> = emptyList(),
    val windowsDownloaded: Int = 0,
    val artifactProblems: List<ArtifactProblem> = emptyList(),
    val stages: List<AnalysisStage> = emptyList(),
    val failureReason: String? = null,
    val engineVersion: String? = null,
    /** `created_at` of the analysis row (`GET /v1/analyses/{id}`), when read: when it was requested. */
    val requestedAt: String? = null,
    /** Restored from disk or reconnected: missing events are still being fetched. */
    val isCatchingUp: Boolean = false,
    /** The last request failed for lack of network; the state shown is the last one received. */
    val offline: Boolean = false,
    /** Last sync problem in words (HTTP error, contract mismatch, protocol violation), null when fine. */
    val syncError: String? = null,
    /** A missing seq the server has not filled after a refetch. */
    val unresolvedGapSeq: Long? = null,
    /** Wall-clock ms of the last successful cursor call (null = none yet this process). */
    val lastSyncAtMs: Long? = null,
) {
    val windowsFailed: Int get() = windowFailures.size
    val hasPartialResults: Boolean get() = windowsDone > 0
    val isLive: Boolean get() = source is AnalysisSource.Live

    companion object {
        /**
         * Derives the state from the applied events. [serverState] (the cursor's `state`) is used only
         * before any event is applied, since the events are what the screen can justify.
         */
        fun derive(
            source: AnalysisSource,
            log: EventLog,
            serverState: String?,
            totalUs: Long?,
            previous: AnalysisUiState? = null,
            /** `analysis.get.response.v1`, when read: fallback for engine_version and failure_reason only. */
            run: AnalysisGetResponse? = null,
        ): AnalysisUiState {
            val applied = log.applied
            val stateWire = if (applied.isNotEmpty()) log.state else serverState
            val state = AnalysisState.fromWire(stateWire)
            val coverage = log.coverageUs
            val completedSeqs = applied.filter { it.type == "window.completed" }.mapNotNull { it.windowSeq }.toSet()
            val failures = applied.filter { it.type == "window.failed" }
                // A window that later completed (engine retry) is no longer a failure.
                .filter { it.windowSeq == null || it.windowSeq !in completedSeqs }
                .map { e -> WindowFailure(e.windowSeq, e.reason ?: "no reason given", e.emitRangeUs.toRange(), e.createdAt) }
            val stages = applied.filter { it.type in STAGE_TYPES }
                .distinctBy { it.type }
                .map { AnalysisStage(it.type, it.seq, it.createdAt) }
            val failed = applied.lastOrNull { it.type == "analysis.failed" }
            val engine = applied.lastOrNull { !it.engineVersion.isNullOrBlank() }?.engineVersion
                ?: run?.engineVersion?.takeIf { it.isNotBlank() }
            val base = previous ?: AnalysisUiState(source = source, state = null)
            return base.copy(
                source = source,
                state = state,
                unknownState = if (state == null) stateWire else null,
                lastSeq = log.afterSeq,
                coverageUs = coverage,
                coveredUs = coveredUs(coverage),
                totalUs = totalUs,
                windowsDone = completedSeqs.size + applied.count { it.type == "window.completed" && it.windowSeq == null },
                windowFailures = failures,
                stages = stages,
                failureReason = failed?.let { it.reason ?: run?.failureReason ?: "no reason given" }
                    ?: run?.failureReason?.takeIf { state == AnalysisState.FAILED },
                engineVersion = engine,
                requestedAt = run?.createdAt ?: base.requestedAt,
            )
        }

        /** Stage events in the order the screen lists them; first occurrence of each. */
        val STAGE_TYPES = listOf(
            "analysis.queued", "analysis.started", "window.completed", "analysis.partial",
            "analysis.finalizing", "analysis.completed", "analysis.failed",
        )

        /** Total covered duration of merged inclusive ranges, µs. */
        fun coveredUs(merged: List<LongRange>): Long = merged.sumOf { it.last - it.first }

        private fun List<Long>?.toRange(): LongRange? = this?.takeIf { it.size == 2 && it[0] <= it[1] }?.let { it[0]..it[1] }
    }
}

/** Shared by the analysing screen and tests: "12.0 of 48.5 s analysed", never a bare percentage. */
fun coverageLabel(coveredUs: Long, totalUs: Long?): String {
    val covered = String.format(java.util.Locale.ROOT, "%.1f", coveredUs / 1e6)
    return if (totalUs != null && totalUs > 0) {
        "$covered of ${String.format(java.util.Locale.ROOT, "%.1f", totalUs / 1e6)} s analysed"
    } else {
        "$covered s analysed · clip length unknown"
    }
}
