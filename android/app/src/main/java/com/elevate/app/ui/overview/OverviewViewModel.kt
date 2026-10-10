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
    )

    private var knownMatchId: String? = matchId
    private val match = MutableStateFlow<MatchRecord?>(null)
    private val matchProblem = MutableStateFlow<String?>(null)
    private val thumbnail = MutableStateFlow<ThumbnailState>(if (thumbnailLoader == null) ThumbnailState.Unavailable("no video attached") else ThumbnailState.Loading)
    private val durationMs = sourceDurationUs?.let { it / 1000 }

    val analysis: StateFlow<AnalysisUiState> = this.sync.state

    val overview: StateFlow<MatchOverviewState> =
        combine(this.sync.state, match, matchProblem, thumbnail) { a, m, p, t ->
            OverviewDerivation.derive(
                title = title,
                match = m,
                analysis = a,
                fixture = fixture,
                matchProblem = p,
                sourceFrameTimesUs = sourceFrameTimesUs,
                sourceDurationMs = durationMs,
                thumbnail = t,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            OverviewDerivation.derive(title, null, this.sync.state.value, fixture, sourceFrameTimesUs = sourceFrameTimesUs, sourceDurationMs = durationMs, thumbnail = thumbnail.value),
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
        sync.retryNow()
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
            matchProblem.value = "no match id known for this analysis yet"
            return
        }
        matchJob = viewModelScope.launch {
            try {
                val m = client.getMatch(id)
                match.value = m
                matchProblem.value = null
                loadThumbnail(m)
            } catch (e: AnalysisApiException) {
                matchProblem.value = when {
                    e.httpStatus == 404 -> "GET /v1/matches/$id answered 404 (${e.code ?: "no code"}): the match record is not served yet"
                    e.isNetwork -> "no connection (${e.message})"
                    else -> e.message ?: e.kind.name
                }
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

    /** For the lead's `viewModel(factory = …)`. */
    class Factory(private val create: () -> OverviewViewModel) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OverviewViewModel::class.java)) { "not an OverviewViewModel" }
            return create() as T
        }
    }
}
