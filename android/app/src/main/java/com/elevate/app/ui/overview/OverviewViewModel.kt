package com.elevate.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elevate.app.data.analysis.AnalysisApiException
import com.elevate.app.data.analysis.AnalysisClient
import com.elevate.app.data.analysis.AnalysisSync
import com.elevate.app.data.analysis.CursorStore
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.model.ClipIdentity
import com.elevate.app.domain.model.TrailTrack
import com.elevate.app.playback.WindowTrailAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives [AnalysingScreen] and [MatchOverviewScreen] for one analysis. Constructed by the lead with
 * a real [AnalysisClient] and the ids from `POST /v1/analyses`; nothing here invents data.
 *
 *  - [startObserving] while the analysing/overview screen is visible (contracts §3: poll only then),
 *    [stopObserving] when it is not. The saved cursor makes a restart catch up instead of refetching.
 *  - When the analysis reaches `completed`, `match.v1` is read once (`GET /v1/matches/{id}`); until
 *    that route exists the overview says why rallies are not available.
 *  - [fixture]: the bundled hand-labelled trail for this exact file (FixtureSource.trailFor), shown
 *    and replayable as "Hand-labelled reference", never as engine output.
 */
class OverviewViewModel(
    private val client: AnalysisClient,
    val analysisId: String,
    /** From the events (`match_id`) when null. */
    matchId: String? = null,
    private val title: String,
    cursorStore: CursorStore? = null,
    /** Source length (µs) for the coverage denominator; null = unknown. */
    sourceDurationUs: Long? = null,
    sourceWidth: Int? = null,
    sourceHeight: Int? = null,
    /** Sorted real frame times of the source, when probed: frame-count denominators per rally. */
    private val sourceFrameTimesUs: LongArray? = null,
    private val fixture: Pair<String, TrailTrack>? = null,
    /** Decodes a real frame (e.g. [FrameThumbnail.load] on Dispatchers.IO); null = no hero frame. */
    /** Per-analysis cache of window results and match.v1: reopening works offline ("analysed earlier"). */
    windowCache: com.elevate.app.data.analysis.WindowCache? = null,
    private val thumbnailLoader: (suspend (atMs: Long) -> ThumbnailState)? = null,
    /** Built by the lead or a test; defaults to one over [client]. */
    sync: AnalysisSync? = null,
) : ViewModel() {

    val sync: AnalysisSync = sync ?: AnalysisSync(
        analysisId = analysisId,
        client = client,
        cursorStore = cursorStore,
        sourceDurationUs = sourceDurationUs,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        windowCache = windowCache,
    )

    private var knownMatchId: String? = matchId
    private val match = MutableStateFlow<MatchRecord?>(null)
    private val matchProblem = MutableStateFlow<String?>(null)
    private val thumbnail = MutableStateFlow<ThumbnailState>(if (thumbnailLoader == null) ThumbnailState.Unavailable("no video attached") else ThumbnailState.Loading)
    private val durationMs = sourceDurationUs?.let { it / 1000 }

    val analysis: StateFlow<AnalysisUiState> = this.sync.state

    /** The served `match.v1` once loaded (landings and calibration for the replay screen). */
    val matchRecord: StateFlow<MatchRecord?> = match.asStateFlow()

    /** Whether the server hands out window results yet (404 = route not deployed). */
    val artifactAccess: StateFlow<com.elevate.app.data.analysis.ArtifactAccess> = this.sync.artifactAccess

    val overview: StateFlow<MatchOverviewState> =
        combine(this.sync.state, match, matchProblem, thumbnail, this.sync.ballTrack) { a, m, p, t, ball ->
            val hits = hitTimeline(a)
            OverviewDerivation.derive(
                title = title,
                match = m,
                analysis = a,
                fixture = fixture,
                matchProblem = p,
                sourceFrameTimesUs = sourceFrameTimesUs,
                sourceDurationMs = durationMs,
                thumbnail = t,
                hitTimeline = hits,
                live = if (m == null && a.isLive) liveOverview(a, ball, hits) else null,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            OverviewDerivation.derive(title, null, this.sync.state.value, fixture, sourceFrameTimesUs = sourceFrameTimesUs, sourceDurationMs = durationMs, thumbnail = thumbnail.value, hitTimeline = hitTimeline(this.sync.state.value)),
        )

    private var pollJob: Job? = null
    private var matchJob: Job? = null
    private var thumbJob: Job? = null

    init {
        loadThumbnail(null)
    }

    /** Start polling (idempotent). Call when the screen becomes visible. */
    fun startObserving() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            launch { this@OverviewViewModel.sync.state.collect { onState(it) } }
            this@OverviewViewModel.sync.run()
        }
    }

    /** Stop polling; state is kept (and saved) for the next [startObserving]. */
    fun stopObserving() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Retry button / connectivity back: poll now; re-read the match if that failed. */
    fun retryNow() {
        // A finished run (terminal state) has stopped polling: start one more pass instead of waking nothing.
        if (pollJob?.isActive == true) sync.retryNow() else startObserving()
        if (matchProblem.value != null) loadMatch()
    }

    /**
     * Engine trail for the rally replay (lead: `controller.setTrack(it?.observed)`). Null when no
     * window result is downloaded yet or the engine version is unknown (provenance must name it).
     */
    fun engineTrail(clip: ClipIdentity): WindowTrailAdapter.EngineTrail? {
        val engine = sync.engineVersion ?: return null
        return WindowTrailAdapter.toTrail(sync.ballTrack.value, clip, engine, sync.downloadedCoverageUs, sourceFrameTimesUs)
    }

    /**
     * Track T from the stored windows. [AnalysisSync.ballTrack] is the change signal: it is rebuilt
     * whenever a window result is stored, which is also when hits change.
     */
    private fun hitTimeline(a: AnalysisUiState): HitTimelineState {
        val store = sync.windowStore()
        return OverviewDerivation.hitTimeline(
            hits = store.hits(),
            windowsRead = store.stored.size,
            windowsReported = a.windowsDone + a.windowsFailed,
            windowsExpected = a.windowsExpected,
            coverageUs = store.coverageUs,
            sourceDurationMs = durationMs,
        )
    }

    /** The overview from the windows while `match.v1` is missing (engine not finalized / route not served). */
    private fun liveOverview(a: AnalysisUiState, ball: com.elevate.app.domain.analysis.BallTrack, hits: HitTimelineState): LiveOverviewState {
        val store = sync.windowStore()
        return LiveOverview.derive(
            analysis = a,
            ball = ball,
            windows = store.stored,
            downloadedCoverageUs = store.coverageUs,
            hitTimeline = hits,
            sourceFrameTimesUs = sourceFrameTimesUs,
        )
    }

    private fun onState(s: AnalysisUiState) {
        if (s.state == AnalysisState.COMPLETED && match.value == null && matchJob?.isActive != true && matchProblem.value == null) {
            loadMatch()
        }
    }

    /** Called by the lead when the match id is learned outside the events (e.g. GET /v1/analyses). */
    fun setMatchId(id: String) {
        knownMatchId = id
    }

    private fun loadMatch() {
        val id = knownMatchId ?: sync.matchId ?: run {
            android.util.Log.w("OverviewViewModel", "no match id known for this analysis yet")
            matchProblem.value = MATCH_LOAD_FAILED
            return
        }
        matchJob = viewModelScope.launch {
            try {
                val m = this@OverviewViewModel.sync.match(id)
                match.value = m
                matchProblem.value = null
                loadThumbnail(m)
            } catch (e: AnalysisApiException) {
                android.util.Log.w("OverviewViewModel", "match $id load failed: ${e.httpStatus} ${e.code} ${e.message}")
                matchProblem.value = MATCH_LOAD_FAILED
            }
        }
    }

    private fun loadThumbnail(m: MatchRecord?) {
        val loader = thumbnailLoader ?: return
        val at = OverviewDerivation.thumbnailTimeMs(m, fixture?.second, durationMs)
        val current = thumbnail.value
        if (current is ThumbnailState.Ready && current.atMs == at) return
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch { thumbnail.value = loader(at) }
    }

    companion object {
        const val MATCH_LOAD_FAILED = "Couldn't load the match summary. Pull to retry."
    }

    /** For the lead's `viewModel(factory = …)`. */
    class Factory(private val create: () -> OverviewViewModel) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OverviewViewModel::class.java)) { "not an OverviewViewModel" }
            return create() as T
        }
    }
}
