package com.elevate.app.data.history

import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisUiState

/**
 * Writes the history as an analysis goes: one entry when it starts, then its state as events are
 * applied. Only real changes are written: a new state, window count or engine version is written at
 * once; a coverage-only change at most every [minIntervalMs] (kept pending until the next call or
 * [flush]). A call that carries no server information yet (state unknown, nothing covered) is ignored,
 * so a screen that is still loading never wipes what was recorded. Blocking file IO: call off the main thread.
 */
class MatchHistoryRecorder(
    private val history: MatchHistory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private data class Pending(val state: String?, val coverageMs: Long, val windowsDone: Int, val engineVersion: String?)

    private val lastWriteAt = HashMap<String, Long>()
    private val pending = HashMap<String, Pending>()

    /** The analysis exists on the server (ids returned by `POST /v1/analyses`). Upserts the entry. */
    @Synchronized
    fun onStarted(
        analysisId: String,
        sessionId: String,
        matchId: String?,
        sha256: String,
        title: String,
        sourceUri: String,
        stagedPath: String?,
        sport: String?,
        durationMs: Long?,
        width: Int?,
        height: Int?,
        state: String?,
        /** App-private copy kept for history ([com.elevate.app.data.upload.LocalVideoStore]), if any. */
        localCopyPath: String? = null,
    ) {
        val now = clock()
        val old = history.get(analysisId)
        history.upsert(
            MatchHistoryEntry(
                analysisId = analysisId,
                sessionId = sessionId,
                matchId = matchId,
                sha256 = sha256,
                title = title,
                sourceUri = sourceUri,
                stagedPath = stagedPath,
                sport = sport,
                durationMs = durationMs?.takeIf { it > 0 },
                width = width?.takeIf { it > 0 },
                height = height?.takeIf { it > 0 },
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
                // A restart re-sends start for the same analysis: keep what the events already said.
                lastState = old?.lastState ?: state,
                windowsDone = old?.windowsDone ?: 0,
                coverageMs = old?.coverageMs ?: 0,
                engineVersion = old?.engineVersion,
                completedAt = old?.completedAt ?: now.takeIf { state == "completed" },
                localCopyPath = localCopyPath ?: old?.localCopyPath,
            ),
        )
        lastWriteAt[analysisId] = now
    }

    /**
     * The latest applied state of [analysisId]. Returns true when the file was written.
     * [state] is the exact contract string; [coverageMs] the merged window coverage.
     */
    @Synchronized
    fun onAnalysisState(analysisId: String, state: String?, coverageMs: Long, windowsDone: Int, engineVersion: String?): Boolean {
        if (state == null && windowsDone == 0 && coverageMs <= 0) return false
        val entry = history.get(analysisId) ?: return false
        val next = Pending(state ?: entry.lastState, coverageMs.coerceAtLeast(0), windowsDone.coerceAtLeast(0), engineVersion ?: entry.engineVersion)
        val current = Pending(entry.lastState, entry.coverageMs, entry.windowsDone, entry.engineVersion)
        if (next == current) {
            pending.remove(analysisId)
            return false
        }
        val now = clock()
        val discrete = next.state != current.state || next.windowsDone != current.windowsDone || next.engineVersion != current.engineVersion
        val last = lastWriteAt[analysisId]
        if (!discrete && last != null && now - last < minIntervalMs) {
            pending[analysisId] = next
            return false
        }
        return write(analysisId, next, now)
    }

    /** Convenience for the analysis screen's state ([AnalysisSource.Live] only; recorded replays are never history). */
    fun onAnalysisState(ui: AnalysisUiState): Boolean {
        val live = ui.source as? AnalysisSource.Live ?: return false
        return onAnalysisState(live.analysisId, ui.state?.wire ?: ui.unknownState, ui.coveredUs / 1000, ui.windowsDone, ui.engineVersion)
    }

    /** Writes any throttled coverage change now (e.g. when the analysis screen stops). */
    @Synchronized
    fun flush() {
        val now = clock()
        for ((id, p) in pending.toMap()) write(id, p, now)
    }

    private fun write(analysisId: String, p: Pending, now: Long): Boolean {
        pending.remove(analysisId)
        val written = history.update(analysisId) {
            val next = it.copy(lastState = p.state, coverageMs = p.coverageMs, windowsDone = p.windowsDone, engineVersion = p.engineVersion, updatedAt = now)
            // The first time it is finished (completed, or every window in) is the analysis time; never moved.
            val finished = next.status() == HistoryStatus.Ready || next.status() == HistoryStatus.ReadyFromWindows
            if (finished && next.completedAt == null) next.copy(completedAt = now) else next
        } != null
        if (written) lastWriteAt[analysisId] = now
        return written
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 5_000L
    }
}
