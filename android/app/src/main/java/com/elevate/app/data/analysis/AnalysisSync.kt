package com.elevate.app.data.analysis

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.AnalysisGetResponse
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.data.api.WindowResult
import com.elevate.app.data.events.EventLog
import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.analysis.ArtifactProblem
import com.elevate.app.domain.analysis.BallTrack
import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.WindowResultStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * Poll timing. Polling runs only while the analysis is on screen (contracts §3). While observed the
 * cursor is read every [activeMs], so a new stage shows within about that long; only after
 * [idleGraceMs] without any new event does the delay back off (doubling up to [idleMaxMs]).
 */
data class PollPolicy(
    /** Delay after a pass that brought new events, and while idle for less than [idleGraceMs]. */
    val activeMs: Long = 1_500,
    /** Time without new events before the idle delay starts to grow. */
    val idleGraceMs: Long = 60_000,
    /** Idle delay doubles from [activeMs] up to this, once [idleGraceMs] has passed. */
    val idleMaxMs: Long = 10_000,
    /** Delay after a failed request doubles from this up to [errorMaxMs]. */
    val errorBaseMs: Long = 2_000,
    val errorMaxMs: Long = 30_000,
    /** Refetches of the same unfilled gap before it is reported as unresolved (polling continues). */
    val gapRetriesBeforeReport: Int = 2,
    /** Download attempts per window artifact before it is reported as unavailable. */
    val artifactAttempts: Int = 3,
    /** After `GET /v1/artifacts/{id}` answered 404, wait this long before asking again (no request loop). */
    val artifactRecheckMs: Long = 60_000,
)

/**
 * Whether the server hands out window results (`GET /v1/artifacts/{id}`, contracts §4). The route is
 * not deployed yet (404): then the detections exist on the server but cannot be drawn here, and the
 * screen says exactly that instead of retrying every window.
 */
sealed interface ArtifactAccess {
    /** Not asked yet (no window completed with an artifact). */
    data object Unknown : ArtifactAccess
    /** At least one signed URL was issued. */
    data object Serving : ArtifactAccess
    /** The artifact route answered 404 at [checkedAtMs]; asked again after [PollPolicy.artifactRecheckMs]. */
    data class NotServed(val reason: String, val checkedAtMs: Long) : ArtifactAccess
}

/**
 * What part of the shown analysis came from this phone's cache ([WindowCache], [CursorStore])
 * rather than from the network in this session.
 */
data class CacheInfo(
    /** The shown events/windows were restored from disk and nothing newer has been fetched since. */
    val fromCache: Boolean = false,
    /** Latest time (wall-clock ms) anything restored was written to the cache; null when nothing was. */
    val cachedAtMs: Long? = null,
    /** Windows restored from the cache (never downloaded again). */
    val windowsCached: Int = 0,
    /** `match.v1` was served from the cache. */
    val matchCached: Boolean = false,
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
    /** Window results and match.v1 kept on the phone; null = no offline copy (tests, recorded runs). */
    private val windowCache: WindowCache? = null,
    /** Where cache file IO runs (tests pass an empty context to stay on the test scheduler). */
    private val io: CoroutineContext = Dispatchers.IO,
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
    /** window_seq -> artifact_id of cached windows; these are never downloaded again. */
    private val cachedWindows = HashMap<Int, String?>()

    private val _state = MutableStateFlow(
        // Nothing is current until the first cursor pass succeeds.
        AnalysisUiState(source = source, state = null, totalUs = sourceDurationUs, isCatchingUp = true),
    )
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    private val _ballTrack = MutableStateFlow(BallTrack.EMPTY)
    /** Merged ball rows of every downloaded window (engine output; never hand labels). */
    val ballTrack: StateFlow<BallTrack> = _ballTrack.asStateFlow()

    private val _hits = MutableStateFlow<List<HitCandidate>>(emptyList())
    /**
     * Engine hit candidates (`type: "hit"` events) of every downloaded window, by time; updated
     * whenever a window is stored. Candidates only, never confirmed hits.
     */
    val hits: StateFlow<List<HitCandidate>> = _hits.asStateFlow()

    private val _artifactAccess = MutableStateFlow<ArtifactAccess>(ArtifactAccess.Unknown)
    /** Whether window results can be fetched from the server at all (see [ArtifactAccess]). */
    val artifactAccess: StateFlow<ArtifactAccess> = _artifactAccess.asStateFlow()

    private val _cache = MutableStateFlow(CacheInfo())
    /** Whether what is shown came from the phone's cache (for "Analysed earlier"). */
    val cache: StateFlow<CacheInfo> = _cache.asStateFlow()

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
        mutex.withLock { restoreOnce() }
        var idleDelay = policy.activeMs
        var idleForMs = 0L
        var errorDelay = policy.errorBaseMs
        while (true) {
            val outcome = syncOnce()
            val delayMs = when (outcome) {
                Outcome.NEW_EVENTS -> { idleDelay = policy.activeMs; idleForMs = 0; errorDelay = policy.errorBaseMs; policy.activeMs }
                Outcome.NO_CHANGE -> {
                    errorDelay = policy.errorBaseMs
                    if (idleForMs < policy.idleGraceMs) policy.activeMs
                    else idleDelay.also { idleDelay = minOf(idleDelay * 2, policy.idleMaxMs) }
                }
                Outcome.GAP, Outcome.ERROR -> errorDelay.also { errorDelay = minOf(errorDelay * 2, policy.errorMaxMs) }
                Outcome.DONE -> return
            }
            if (outcome == Outcome.NO_CHANGE) idleForMs += delayMs
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
            terminal && (pendingArtifacts().isEmpty() || _artifactAccess.value is ArtifactAccess.NotServed) -> Outcome.DONE
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
            if (progressed) {
                persist()
                _cache.update { it.copy(fromCache = false) }
            }
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

    /**
     * Shows what this phone saved, before any request: the event cursor ([CursorStore]) and every
     * cached window ([WindowCache]), so the trail, hits and overview appear with no network.
     * Called under [mutex] by run() before the first request and by syncOnce().
     */
    private suspend fun restoreOnce() {
        if (restored) return
        restored = true
        val saved = cursorStore?.load(analysisId)
        val cachedAt = mutableListOf<Long>()
        if (saved != null && saved.events.isNotEmpty()) {
            log = EventLog.EMPTY.plus(saved.events)
            cachedAt += saved.savedAtMs
            publish(catchingUp = true, lastSyncAtMs = null)
        }
        val windows = windowCache?.let { c -> withContext(io) { runCatching { c.windows() }.getOrDefault(emptyList()) } }.orEmpty()
        var restoredWindows = 0
        for (w in windows) {
            if (store.put(w.result, w.engineVersion) is WindowResultStore.PutResult.Rejected) continue
            cachedWindows[w.windowSeq] = w.artifactId
            w.artifactId?.let { downloaded += it }
            cachedAt += w.cachedAtMs
            restoredWindows++
        }
        if (restoredWindows > 0) {
            _ballTrack.value = store.track()
            _hits.value = store.hits()
            _state.update { it.copy(windowsDownloaded = downloaded.size) }
        }
        if (cachedAt.isNotEmpty()) {
            _cache.value = CacheInfo(fromCache = true, cachedAtMs = cachedAt.max(), windowsCached = restoredWindows)
        }
    }

    /**
     * Shows the saved state now (cursor + cached windows), without any request. Optional: [run] and
     * [syncOnce] do it first anyway; call it to fill the screen before observing starts.
     */
    suspend fun restore() {
        mutex.withLock { restoreOnce() }
    }

    /**
     * `match.v1` for [matchId]: the cached copy first (no request), else `GET /v1/matches/{id}`,
     * then kept for next time. [refresh] skips the cache. Throws [AnalysisApiException] like the client.
     */
    suspend fun match(matchId: String, refresh: Boolean = false): MatchRecord {
        val c = windowCache
        if (c != null && !refresh) {
            val hit = withContext(io) { c.match() }
            if (hit != null && hit.first.matchId == matchId) {
                _cache.update { it.copy(matchCached = true, cachedAtMs = maxOf(it.cachedAtMs ?: 0, hit.second)) }
                return hit.first
            }
        }
        val m = client.getMatch(matchId)
        if (c != null) withContext(io) { runCatching { c.putMatch(m) } }
        _cache.update { it.copy(matchCached = false) }
        return m
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
        // A cached window is never fetched again (unless the server names a new artifact for that window_seq).
        val seq = e.windowSeq
        val cached = seq != null && cachedWindows.containsKey(seq) && cachedWindows[seq].let { it == null || it == id }
        !cached && id !in downloaded && (artifactAttempts[id] ?: 0) < policy.artifactAttempts
    }

    private suspend fun downloadArtifacts() {
        for (e in log.applied) if (e.type == "window.failed" && e.windowSeq != null) store.markFailed(e.windowSeq, e.reason ?: "no reason given")
        val access = _artifactAccess.value
        // The route answered 404: ask again only after a pause, never once per window per poll.
        if (access is ArtifactAccess.NotServed && clock() - access.checkedAtMs < policy.artifactRecheckMs) return
        var changed = false
        for (e in pendingArtifacts()) {
            val id = e.artifactId!!
            try {
                val url = try {
                    client.artifactUrl(id)
                } catch (x: AnalysisApiException) {
                    if (x.httpStatus == 404) {
                        _artifactAccess.value = ArtifactAccess.NotServed(x.message ?: "HTTP 404", clock())
                        break
                    }
                    throw x
                }
                _artifactAccess.value = ArtifactAccess.Serving
                val w = client.downloadWindowResult(url)
                if (e.windowSeq != null && w.windowSeq != e.windowSeq) {
                    fail(id, "window_seq ${w.windowSeq} in the file, ${e.windowSeq} in the event")
                    continue
                }
                val engine = e.engineVersion?.ifBlank { null } ?: _state.value.engineVersion
                when (val r = store.put(w, engine)) {
                    is WindowResultStore.PutResult.Rejected -> fail(id, r.reason)
                    else -> {
                        downloaded += id
                        artifactReasons.remove(id)
                        changed = true
                        if (r is WindowResultStore.PutResult.Stored) saveWindow(w, id, engine)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (x: AnalysisApiException) {
                fail(id, x.message ?: x.kind.name)
                if (x.isNetwork) { _state.update { it.copy(offline = true) }; break }
            }
        }
        if (changed) {
            _ballTrack.value = store.track()
            _hits.value = store.hits()
            _cache.update { it.copy(fromCache = false) }
        }
        _state.update { it.copy(windowsDownloaded = downloaded.size, artifactProblems = problems()) }
    }

    /** Keeps a downloaded window for offline reopening. Not fatal: it is downloaded again next time. */
    private suspend fun saveWindow(w: WindowResult, artifactId: String, engine: String?) {
        val c = windowCache ?: return
        try {
            withContext(io) { c.put(w, artifactId, engine) }
            cachedWindows[w.windowSeq] = artifactId
        } catch (e: java.io.IOException) {
            _state.update { it.copy(syncError = "could not keep window ${w.windowSeq} on this phone: ${e.message}") }
        }
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
