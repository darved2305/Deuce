package com.elevate.app.data.analysis

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.AnalysisGetResponse
import com.elevate.app.data.events.EventLog
import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.analysis.ArtifactProblem
import com.elevate.app.domain.analysis.BallTrack
import com.elevate.app.domain.analysis.WindowResultStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Poll timing. Polling runs only while the analysis screen is visible (contracts §3). */
data class PollPolicy(
    /** Delay after a pass that brought new events. */
    val activeMs: Long = 2_000,
    /** Idle delay doubles from [activeMs] up to this. */
    val idleMaxMs: Long = 15_000,
    /** Delay after a failed request doubles from this up to [errorMaxMs]. */
    val errorBaseMs: Long = 2_000,
    val errorMaxMs: Long = 30_000,
    /** Refetches of the same unfilled gap before it is reported as unresolved (polling continues). */
    val gapRetriesBeforeReport: Int = 2,
    /** Download attempts per window artifact before it is reported as unavailable. */
    val artifactAttempts: Int = 3,
)

/**
 * Drives [EventLog] from the events cursor of one analysis (contracts §3) and downloads each
 * completed window's `window_result.v1` into a [WindowResultStore].
 *
 *  - Pages with `after_seq` = last contiguous seq applied, until `has_more` is false.
 *  - A gap stops application (EventLog); the next request starts again from the last contiguous
 *    seq, so the server refills it. A gap that survives refetches is reported, never skipped.
 *  - On start, a saved cursor ([CursorStore]) is shown at once with [AnalysisUiState.isCatchingUp]
 *    true; it becomes false only after a full catch-up pass with no gap.
 *  - Network loss sets [AnalysisUiState.offline] and retries with backoff; the first success
 *    clears it and catches up.
 *  - A Realtime `analysis_seq` hint (later) only wakes the poll ([hint]); the cursor stays the truth.
 */
class AnalysisSync(
    val analysisId: String,
    private val client: AnalysisClient,
    private val cursorStore: CursorStore? = null,
    /** Clip length (µs) for the coverage denominator; null = unknown, no denominator shown. */
    private val sourceDurationUs: Long? = null,
    sourceWidth: Int? = null,
    sourceHeight: Int? = null,
    private val policy: PollPolicy = PollPolicy(),
    private val pageLimit: Int = 100,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val source = AnalysisSource.Live(analysisId)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var log = EventLog.EMPTY
    private var serverState: String? = null
    /** Last `GET /v1/analyses/{id}`; read once at start and again when the analysis ends. */
    private var run: AnalysisGetResponse? = null
    private var runCheckedFor: String? = null
    private var restored = false
    private var gapRefetches = 0
    private val store = WindowResultStore(analysisId, sourceWidth, sourceHeight)
    /** artifact_id → attempts so far / last reason. */
    private val artifactAttempts = HashMap<String, Int>()
    private val artifactReasons = HashMap<String, String>()
    private val downloaded = HashSet<String>()

    private val _state = MutableStateFlow(
        // Nothing is current until the first cursor pass succeeds.
        AnalysisUiState(source = source, state = null, totalUs = sourceDurationUs, isCatchingUp = true),
    )
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    private val _ballTrack = MutableStateFlow(BallTrack.EMPTY)
    /** Merged ball rows of every downloaded window (engine output; never hand labels). */
    val ballTrack: StateFlow<BallTrack> = _ballTrack.asStateFlow()

    /** Coverage of the downloaded windows (may lag [AnalysisUiState.coverageUs] while downloads run). */
    val downloadedCoverageUs: List<LongRange> get() = store.coverageUs

    /** `match_id` carried by the applied events (every event row has one), else from the analysis row. */
    val matchId: String? get() = log.applied.firstOrNull()?.matchId ?: run?.matchId

    /** engine_version of the analysis as reported by its events, for the trail's provenance. */
    val engineVersion: String? get() = _state.value.engineVersion

    /** Realtime hint (contracts §3): a nudge only; the cursor is fetched as usual. */
    fun hint(latestSeq: Long) {
        if (latestSeq > log.afterSeq) wake.trySend(Unit)
    }

    /** User tapped retry, or connectivity came back: poll now instead of waiting the backoff. */
    fun retryNow() {
        wake.trySend(Unit)
    }

    /**
     * Polls until the analysis is terminal and every completed window's artifact is downloaded or
     * given up on. Call while the analysis screen is visible; cancel when it is not (cancellation
     * is safe at any point: state is saved after every applied page).
     */
    suspend fun run() {
        restoreOnce()
        var idleDelay = policy.activeMs
        var errorDelay = policy.errorBaseMs
        while (true) {
            val outcome = syncOnce()
            val delayMs = when (outcome) {
                Outcome.NEW_EVENTS -> { idleDelay = policy.activeMs; errorDelay = policy.errorBaseMs; policy.activeMs }
                Outcome.NO_CHANGE -> { errorDelay = policy.errorBaseMs; idleDelay.also { idleDelay = minOf(idleDelay * 2, policy.idleMaxMs) } }
                Outcome.GAP, Outcome.ERROR -> errorDelay.also { errorDelay = minOf(errorDelay * 2, policy.errorMaxMs) }
                Outcome.DONE -> return
            }
            withTimeoutOrNull(delayMs) { wake.receive() }
        }
    }

    enum class Outcome { NEW_EVENTS, NO_CHANGE, GAP, ERROR, DONE }

    /** One catch-up pass (all pages) plus pending artifact downloads. Public for tests and one-shot refresh. */
    suspend fun syncOnce(): Outcome = mutex.withLock {
        restoreOnce()
        val before = log.afterSeq
        val fetched = try {
            catchUp()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AnalysisApiException) {
            _state.update { it.copy(offline = e.isNetwork, syncError = if (e.isNetwork) null else e.message) }
            return@withLock Outcome.ERROR
        } catch (e: IllegalArgumentException) {
            // EventLog: the same seq delivered twice with different content. Shown, never merged.
            _state.update { it.copy(offline = false, syncError = "event stream inconsistent: ${e.message}") }
            return@withLock Outcome.ERROR
        }
        val gap = log.missingSeq
        if (gap != null) gapRefetches++ else gapRefetches = 0
        val wire = log.state.takeIf { log.applied.isNotEmpty() } ?: serverState
        // One read at start, one when the analysis ends; a failed read is not retried every poll.
        if (runCheckedFor == null || (AnalysisState.fromWire(wire)?.isTerminal == true && runCheckedFor != wire)) {
            refreshRun()
            runCheckedFor = wire.orEmpty()
        }
        publish(
            catchingUp = gap != null,
            unresolvedGap = gap?.takeIf { gapRefetches > policy.gapRetriesBeforeReport },
            lastSyncAtMs = clock(),
        )
        if (fetched) _state.update { it.copy(offline = false, syncError = null) }
        downloadArtifacts()
        val terminal = AnalysisState.fromWire(log.state.takeIf { log.applied.isNotEmpty() } ?: serverState)?.isTerminal == true
        when {
            gap != null -> Outcome.GAP
            terminal && pendingArtifacts().isEmpty() -> Outcome.DONE
            log.afterSeq > before -> Outcome.NEW_EVENTS
            else -> Outcome.NO_CHANGE
        }
    }

    /** Pages until has_more is false or no progress is made (a gap). Returns true if every call succeeded. */
    private suspend fun catchUp(): Boolean {
        while (true) {
            val cursor = log.afterSeq
            val page = client.events(analysisId, cursor, pageLimit)
            val next = log.plus(page.events.filter { it.analysisId == analysisId })
            serverState = page.state
            val progressed = next.afterSeq > log.afterSeq
            log = next
            if (progressed) persist()
            publish(catchingUp = page.hasMore || log.missingSeq != null)
            if (!page.hasMore || !progressed) return true
        }
    }

    /** Non-fatal: the events are the truth; this row only fills engine_version / failure_reason / created_at. */
    private suspend fun refreshRun() {
        run = try {
            client.getAnalysis(analysisId)
        } catch (e: AnalysisApiException) {
            run
        }
    }

    private fun restoreOnce() {
        // Called by run() before the first request (saved state shows at once) and by syncOnce().
        if (!restored) {
            val saved = cursorStore?.load(analysisId)
            restored = true
            if (saved != null && saved.events.isNotEmpty()) {
                log = EventLog.EMPTY.plus(saved.events)
                publish(catchingUp = true, lastSyncAtMs = null)
            }
        }
    }

    private fun persist() {
        val cs = cursorStore ?: return
        try {
            cs.save(CursorStore.Saved(analysisId, log.afterSeq, log.applied, clock()))
        } catch (e: java.io.IOException) {
            // Not fatal: the next start fetches from seq 0 instead.
            _state.update { it.copy(syncError = "could not save progress on this phone: ${e.message}") }
        }
    }

    private fun publish(catchingUp: Boolean, unresolvedGap: Long? = _state.value.unresolvedGapSeq, lastSyncAtMs: Long? = _state.value.lastSyncAtMs) {
        _state.update { prev ->
            AnalysisUiState.derive(source, log, serverState, sourceDurationUs, prev, run).copy(
                isCatchingUp = catchingUp,
                unresolvedGapSeq = unresolvedGap,
                lastSyncAtMs = lastSyncAtMs,
                windowsDownloaded = downloaded.size,
                artifactProblems = problems(),
            )
        }
    }

    private fun completedWithArtifacts(): List<AnalysisEvent> =
        log.applied.filter { it.type == "window.completed" && it.artifactId != null }

    private fun pendingArtifacts(): List<AnalysisEvent> = completedWithArtifacts().filter { e ->
        val id = e.artifactId!!
        id !in downloaded && (artifactAttempts[id] ?: 0) < policy.artifactAttempts
    }

    private suspend fun downloadArtifacts() {
        for (e in log.applied) if (e.type == "window.failed" && e.windowSeq != null) store.markFailed(e.windowSeq, e.reason ?: "no reason given")
        var changed = false
        for (e in pendingArtifacts()) {
            val id = e.artifactId!!
            try {
                val url = client.artifactUrl(id)
                val w = client.downloadWindowResult(url)
                if (e.windowSeq != null && w.windowSeq != e.windowSeq) {
                    fail(id, "window_seq ${w.windowSeq} in the file, ${e.windowSeq} in the event")
                    continue
                }
                when (val r = store.put(w, e.engineVersion?.ifBlank { null } ?: _state.value.engineVersion)) {
                    is WindowResultStore.PutResult.Rejected -> fail(id, r.reason)
                    else -> { downloaded += id; artifactReasons.remove(id); changed = true }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (x: AnalysisApiException) {
                fail(id, x.message ?: x.kind.name)
                if (x.isNetwork) { _state.update { it.copy(offline = true) }; break }
            }
        }
        if (changed) _ballTrack.value = store.track()
        _state.update { it.copy(windowsDownloaded = downloaded.size, artifactProblems = problems()) }
    }

    private fun fail(id: String, reason: String) {
        artifactAttempts[id] = (artifactAttempts[id] ?: 0) + 1
        artifactReasons[id] = reason
    }

    private fun problems(): List<ArtifactProblem> = completedWithArtifacts().mapNotNull { e ->
        val id = e.artifactId!!
        val reason = artifactReasons[id] ?: return@mapNotNull null
        val n = artifactAttempts[id] ?: 0
        ArtifactProblem(e.windowSeq, id, reason, n, gaveUp = n >= policy.artifactAttempts)
    }

    /** The store, for the trail adapter and tests. Read on the same coroutine context as [run]. */
    fun windowStore(): WindowResultStore = store
}
