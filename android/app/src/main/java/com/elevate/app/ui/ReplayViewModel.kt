package com.elevate.app.ui

import com.elevate.app.data.history.status
import android.os.Build
import com.elevate.app.data.analysis.AnalysisClient
import com.elevate.app.data.analysis.HttpAnalysisClient
import com.elevate.app.data.session.Ruleset
import com.elevate.app.data.session.SessionCreateRequest
import com.elevate.app.data.session.SessionSource
import com.elevate.app.data.upload.SourceAccess
import com.elevate.app.data.upload.UploadPhase
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.data.upload.UploadRuntime
import com.elevate.app.data.upload.UploadScheduler
import com.elevate.app.data.upload.UriAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import androidx.core.net.toUri
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.elevate.app.data.upload.UploadState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.ui.overview.AnalysingFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.delay
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.elevate.app.BuildConfig
import com.elevate.app.data.DefaultMatchRepository
import com.elevate.app.data.api.ApiSource
import com.elevate.app.data.fixture.FixtureSource
import com.elevate.app.data.media.MediaProbe
import com.elevate.app.domain.calibration.CourtCalibration
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.calibration.Homography
import com.elevate.app.domain.calibration.ImagePoint
import com.elevate.app.domain.media.Preflight
import com.elevate.app.domain.media.PreflightState
import com.elevate.app.ui.setup.PointsPx
import com.elevate.app.ui.setup.SetupResult
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.domain.repository.BackendStatus
import com.elevate.app.domain.repository.MatchRepository
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import com.elevate.app.playback.CourtOverlay
import com.elevate.app.playback.PlayerController
import com.elevate.app.playback.VideoInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface Screen {
    data class Import(val opening: Boolean = false, val error: String? = null) : Screen
    data class Replay(
        val uri: Uri,
        val title: String,
        val info: VideoInspector.Info,
        val annotations: AnnotationState = AnnotationState.Checking,
        val overlayVisible: Boolean = true,
        val preflight: PreflightState = PreflightState.Checking,
        /** Setup flow open on the frame at this PTS (µs); null = closed. */
        val setupAtUs: Long? = null,
        /** What the user chose in the setup flow (contracts §2 session fields), once done. */
        val setup: SetupResult? = null,
        /** Recorded engine output for this exact file, if Person 1 recorded a run on it. */
        val engine: EngineOffer? = null,
        /** Live engine output for THIS file from its own analysis, as windows arrive. */
        val live: LiveOffer? = null,
        /** sha256 of the file's bytes (also `source.sha256` in the session request), once measured. */
        val sha256: String? = null,
        /** Whole-court view from the setup's four corner taps, once a homography exists. */
        val court: CourtCalibration? = null,
        /** The "Court" toggle: whether the court lines are drawn on the video. */
        val courtVisible: Boolean = true,
    ) : Screen

    enum class Panel { None, Analysing, Overview }

    /** Real states only: each step changes on a server answer, never on a timer. */
    sealed interface Pipeline {
        data object Idle : Pipeline
        data object Uploading : Pipeline
        data class StartingAnalysis(val sessionId: String) : Pipeline
        data class Started(val analysisId: String, val sessionId: String, val matchId: String?, val dispatched: Boolean, val state: String) : Pipeline
        data class Failed(val stage: String, val reason: String) : Pipeline
    }

    /** A recorded engine run offered on the open file; [shown] = drawn instead of the hand labels. */
    /** [track]: observed rows when the engine sends any, else its candidates (drawn as candidates). */
    data class LiveOffer(
        val track: com.elevate.app.domain.model.TrailTrack,
        val framesWithBall: Int,
        val engineVersion: String,
        val shown: Boolean = true,
    )

    data class EngineOffer(
        val run: com.elevate.app.data.engine.RecordedEngineRun,
        val trail: com.elevate.app.data.engine.RecordedEngineRun.CandidateTrail,
        val shown: Boolean = false,
    )
}

@UnstableApi
class ReplayViewModel(app: Application) : AndroidViewModel(app) {

    val controller = PlayerController(app)
    /** Court lines through the corner-tap homography; sits beside the trail in the effect list. */
    private val courtOverlay = CourtOverlay()
    /** Whether the open player has the overlay pipeline (SDR only; HDR drops it, see [open]). */
    private var playerHasOverlays = false
    private val inspector = VideoInspector(app.contentResolver)
    private val probe = MediaProbe(app.contentResolver)
    private val engineRuns: List<com.elevate.app.data.engine.RecordedEngineRun> by lazy {
        val assets = app.assets
        val runs = assets.list(ENGINE_DIR).orEmpty()
        com.elevate.app.data.engine.RecordedEngineRun.load(runs.flatMap { run ->
            assets.list("$ENGINE_DIR/$run").orEmpty().map { f ->
                "$run/$f" to assets.open("$ENGINE_DIR/$run/$f").bufferedReader().use { it.readText() }
            }
        })
    }
    private val repository: MatchRepository = DefaultMatchRepository(
        fixtures = FixtureSource {
            val assets = app.assets
            (assets.list(FIXTURE_DIR) ?: emptyArray()).filter { it.endsWith(".json") }.sorted().map { name ->
                name to assets.open("$FIXTURE_DIR/$name").bufferedReader().use { it.readText() }
            }
        },
        api = ApiSource(BuildConfig.API_BASE_URL),
        io = Dispatchers.IO,
    )

    private val _screen = MutableStateFlow<Screen>(Screen.Import())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var openJob: Job? = null

    private val _backend = MutableStateFlow<BackendStatus?>(null)
    /** null while the first health check runs. */
    val backend: StateFlow<BackendStatus?> = _backend.asStateFlow()

    fun refreshBackend() {
        viewModelScope.launch { _backend.value = repository.backendStatus() }
    }

    /** Picker returned [uri]; null means the user cancelled, which changes nothing. */
    fun onPicked(uri: Uri?) {
        if (uri == null) return
        openJob?.cancel()
        clearLiveHits()
        _screen.value = Screen.Import(opening = true)
        openJob = viewModelScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { inspector.info(uri) }
            } catch (e: IOException) {
                _screen.value = Screen.Import(error = "This file can't be opened as a video. ${e.message ?: ""}".trim())
                return@launch
            } catch (e: SecurityException) {
                _screen.value = Screen.Import(error = "Access to this file was not granted. Pick it again.")
                return@launch
            }
            controller.open(uri, withOverlay = !info.isHdr)
            pendingSeekMs?.let { controller.pause(); controller.seekTo(it); pendingSeekMs = null }
            // Opened from history with the Analysing/Overview panel on top: don't play sound behind it.
            if (_panel.value != Screen.Panel.None) controller.pause()
            // open() may rebuild the player (SDR↔HDR), which resets the effect list; add the court
            // overlay beside the trail on every overlay-capable player (setVideoEffects may run
            // after prepare — media3 1.11.1 ExoPlayer doc). On HDR the pipeline has no overlays.
            playerHasOverlays = !info.isHdr
            attachCourtOverlay()
            courtOverlay.setCalibration(null)
            controller.setOverlayVisible(true)
            _screen.value = Screen.Replay(uri, info.displayName ?: "Your video", info)
            // Preflight runs beside the fingerprint below: its sample walk can take longer than the sha256.
            launch {
                val preflight = try {
                    val facts = withContext(Dispatchers.IO) { probe.probe(uri) }
                    PreflightState.Done(facts, Preflight.assess(facts))
                } catch (e: IOException) {
                    PreflightState.Failed(e.message ?: "could not read the file")
                } catch (e: SecurityException) {
                    PreflightState.Failed("access to the file was revoked")
                }
                _screen.update { s -> if (s is Screen.Replay && s.uri == uri) s.copy(preflight = preflight) else s }
            }
            if (info.isHdr) {
                _screen.update { s -> if (s is Screen.Replay && s.uri == uri) s.copy(annotations = AnnotationState.UnsupportedHdr) else s }
                return@launch
            }

            val annotations = try {
                val fp = withContext(Dispatchers.IO) { inspector.fingerprint(uri) }
                _screen.update { s -> if (s is Screen.Replay && s.uri == uri) s.copy(sha256 = fp.sha256) else s }
                repository.annotationsFor(fp)
            } catch (e: IOException) {
                AnnotationState.Failed("could not read the file to identify it: ${e.message}")
            } catch (e: SecurityException) {
                AnnotationState.Failed("access to the file was revoked while identifying it")
            }
            val current = _screen.value
            if (current is Screen.Replay && current.uri == uri) {
                val hand = (annotations as? AnnotationState.Available)?.track
                // A live engine trail that already arrived for this file stays on screen.
                val live = (_screen.value as? Screen.Replay)?.live?.takeIf { it.shown }
                controller.setTrack(live?.track ?: hand)
                // A recorded engine run is offered only on a file whose bytes matched the fixture
                // for the same clip id (the run's manifest has no file hash of its own).
                val offer = hand?.let { h ->
                    withContext(Dispatchers.Default) {
                        engineRuns.firstOrNull { it.clipId == h.clip.clipId }?.let { run ->
                            run.candidateTrack(h.clip)?.let { Screen.EngineOffer(run, it) }
                        }
                    }
                }
                _screen.update { s -> if (s is Screen.Replay && s.uri == uri) s.copy(annotations = annotations, engine = offer) else s }
            }
        }
    }

    /** Opens the setup flow on the paused frame. Only offered when preflight is done and not blocked. */
    fun startSetup() {
        val s = _screen.value as? Screen.Replay ?: return
        val p = s.preflight as? PreflightState.Done ?: return
        if (p.blocked) return
        controller.pause()
        _screen.value = s.copy(setupAtUs = controller.positionMs() * 1000)
    }

    fun cancelSetup() = _screen.update { s -> if (s is Screen.Replay) s.copy(setupAtUs = null) else s }

    /** Setup confirmed == start: the upload and analysis begin at once and the Analysing screen opens. */
    fun finishSetup(result: SetupResult) {
        val court = (_screen.value as? Screen.Replay)?.let { courtCalibration(it, result) }
        courtOverlay.setCalibration(court)
        // The screen's toggle is reset to on below; an earlier "off" must not leave the lines hidden.
        courtOverlay.visible = court != null
        _screen.update { s ->
            if (s is Screen.Replay) {
                s.copy(setupAtUs = null, setup = result, court = court, courtVisible = court != null)
            } else s
        }
        // Repaint the paused frame so the lines appear without pressing play.
        redrawOverlay()
        analyse()
    }

    /** The court geometry from the setup's corner taps, or null without four corners + a solve. */
    private fun courtCalibration(s: Screen.Replay, r: SetupResult): CourtCalibration? {
        val cal = r.calibrationInput ?: return null
        val corners = CourtModel.outerCorners(cal.template) ?: return null
        val taps = cal.pointsPx.inContractOrder() ?: return null
        val h = Homography.fromFourPoints(corners, taps) ?: return null
        // The image space the taps were made in (contracts §0): the probe's display size, with the
        // decoded frame size as the fallback.
        val facts = (s.preflight as? PreflightState.Done)?.facts
        val w = facts?.displayWidth ?: controller.state.value.videoWidth
        val ht = facts?.displayHeight ?: controller.state.value.videoHeight
        if (w <= 0 || ht <= 0) return null
        return CourtCalibration(cal.template, h, w, ht)
    }

    /** near_left, near_right, far_right, far_left as image points; null on a malformed point. */
    private fun PointsPx.inContractOrder(): List<ImagePoint>? {
        val c = listOf(nearLeft, nearRight, farRight, farLeft)
        if (c.any { it.size < 2 }) return null
        return c.map { ImagePoint(it[0], it[1]) }
    }

    /** The "Court" toggle: the lines draw or hide; the state lives on the screen. */
    fun setCourtVisible(visible: Boolean) {
        courtOverlay.visible = visible
        _screen.update { s -> if (s is Screen.Replay) s.copy(courtVisible = visible) else s }
        redrawOverlay()
    }

    /** The court overlay joins the trail's effect; the last overlay in the list draws on top. */
    private fun attachCourtOverlay() {
        if (!playerHasOverlays) return
        // Court lines under the trail and hit markers; kept across SDR/HDR player rebuilds.
        controller.setUnderlays(listOf(courtOverlay))
    }

    /**
     * Re-render the held frame while paused so overlay changes are visible without playback — the
     * same seek mechanism the trail toggle uses inside the controller.
     */
    private fun redrawOverlay() {
        val s = _screen.value as? Screen.Replay ?: return
        controller.setOverlayVisible(s.overlayVisible)
    }

    /** Draws the recorded engine candidates instead of the hand labels, or back. One trail at a time. */
    /**
     * A new live window arrived. Drawn only on the very file that was analysed (sha256 of the open
     * file = `source.sha256` of the upload). The first arrival switches the video to the live trail.
     */
    fun onLiveTrail(trail: com.elevate.app.playback.WindowTrailAdapter.EngineTrail?, engineVersion: String?) {
        val s = _screen.value as? Screen.Replay ?: return
        if (trail == null || engineVersion == null) return
        val sha = s.sha256 ?: return
        if (pipelineSha256() != sha) return
        val useObserved = trail.observedRows > 0
        val raw = if (useObserved) trail.observed else trail.candidate
        // Denominator = real frames of this file inside the analysed range (the adapter alone only
        // knows frames that have rows, which read "101/101").
        val times = (s.preflight as? PreflightState.Done)?.facts?.frameTimesUs
        val framesInRange = times?.count { it in raw.rangeStartUs..raw.rangeEndUs }?.takeIf { it >= raw.labelledFrames }
        val track = if (framesInRange == null) raw else com.elevate.app.domain.model.TrailTrack(
            raw.clip, raw.provenance, raw.rangeStartUs, raw.rangeEndUs, framesInRange, raw.tUs, raw.x, raw.y,
        )
        val rows = if (useObserved) trail.observedRows else trail.candidateRows
        if (rows == 0) return
        val first = s.live == null
        val shown = first || s.live?.shown == true
        val offer = Screen.LiveOffer(track, rows, engineVersion, shown)
        if (shown) {
            controller.setTrack(track)
            controller.setOverlayVisible(true)
        }
        _screen.value = s.copy(
            live = offer,
            engine = if (shown) s.engine?.copy(shown = false) else s.engine,
            overlayVisible = if (shown) true else s.overlayVisible,
        )
    }

    fun showLive(show: Boolean) {
        val s = _screen.value as? Screen.Replay ?: return
        val live = s.live ?: return
        val hand = (s.annotations as? AnnotationState.Available)?.track
        controller.setTrack(if (show) live.track else hand)
        controller.setOverlayVisible(true)
        _screen.value = s.copy(live = live.copy(shown = show), engine = s.engine?.copy(shown = false), overlayVisible = true)
    }

    /** sha256 of the uploaded source (from the saved session request), to match live output to a file. */
    /** [pipelineSha256], refreshed on IO whenever the pipeline changes: safe to read in composition. */
    private val _analysedSha = MutableStateFlow<String?>(null)
    val analysedSha: StateFlow<String?> = _analysedSha.asStateFlow()

    fun pipelineSha256(): String? = runCatching {
        adopted?.let { return it.sha256 }
        val st = uploadRuntime.coordinator.current() ?: return null
        val src = kotlinx.serialization.json.Json.parseToJsonElement(st.createRequestJson).jsonObject.getValue("source").jsonObject
        (src["sha256"] as? JsonPrimitive)?.content
    }.getOrNull()

    fun showEngine(show: Boolean) {
        val s = _screen.value as? Screen.Replay ?: return
        val offer = s.engine ?: return
        val hand = (s.annotations as? AnnotationState.Available)?.track
        controller.setTrack(if (show) offer.trail.track else hand)
        controller.setOverlayVisible(true)
        _screen.value = s.copy(engine = offer.copy(shown = show), live = s.live?.copy(shown = false), overlayVisible = true)
    }

    val uploadRuntime: UploadRuntime by lazy { UploadRuntime.get(getApplication()) }

    /** Upload progress; a fixed Idle on a build without cloud config, where [uploadRuntime] throws. */
    val uploadProgress: StateFlow<UploadProgress> by lazy {
        if (UploadRuntime.isConfigured) uploadRuntime.coordinator.progress else MutableStateFlow(UploadProgress.Idle).asStateFlow()
    }

    val analysisClient: AnalysisClient by lazy {
        HttpAnalysisClient(BuildConfig.API_BASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, tokenProvider = { uploadRuntime.auth.accessToken() })
    }

    private var pipelineJob: Job? = null

    private val _pipeline = MutableStateFlow<Screen.Pipeline>(Screen.Pipeline.Idle)
    /** Upload → analysis, app-wide: it survives leaving the video and process death (state on disk). */
    val pipeline: StateFlow<Screen.Pipeline> = _pipeline.asStateFlow()

    private val _panel = MutableStateFlow(Screen.Panel.None)
    val panel: StateFlow<Screen.Panel> = _panel.asStateFlow()

    private val _pipelineStep = MutableStateFlow<String?>(null)
    /** Local step before the upload exists ("Identifying the file", "Preparing"); null once the upload runs. */
    val pipelineStep: StateFlow<String?> = _pipelineStep.asStateFlow()

    /** What the analysis screens show about the video the pipeline belongs to (not the one open now). */
    data class PipelineHero(val eyebrow: String?, val sourceUri: Uri?, val durationMs: Long?, val fileName: String?)

    private val _hero = MutableStateFlow(PipelineHero(null, null, null, null))
    val pipelineHero: StateFlow<PipelineHero> = _hero.asStateFlow()

    private val _lastAnalysis = MutableStateFlow<AnalysisUiState?>(null)

    /** The analysis screen's latest state, so the video screen's pill can show it after Back. */
    fun onAnalysisState(state: AnalysisUiState) {
        _lastAnalysis.value = state
        viewModelScope.launch(Dispatchers.IO) {
            if (runCatching { history.recorder.onAnalysisState(state) }.getOrDefault(false)) refreshMatches()
        }
    }

    // ---- Match history (data/history): every analysis on this phone, reopened instantly ----
    private val history by lazy { com.elevate.app.data.history.MatchHistoryRuntime.get(getApplication()) }
    private val _matches = MutableStateFlow<List<com.elevate.app.data.history.MatchHistoryEntry>>(emptyList())
    val matches: StateFlow<List<com.elevate.app.data.history.MatchHistoryEntry>> = _matches.asStateFlow()

    // After _matches exists (init blocks run in declaration order).
    init { refreshMatches() }

    fun refreshMatches() {
        viewModelScope.launch(Dispatchers.IO) { _matches.value = runCatching { history.history.list() }.getOrDefault(emptyList()) }
    }

    /** A history entry being viewed (not the latest upload): source facts and sha come from it. */
    private var adopted: com.elevate.app.data.history.MatchHistoryEntry? = null

    /** Opens a remembered analysis: its Analysing/Overview panel at once, and its video if still readable. */
    fun openHistory(analysisId: String) {
        val p = _pipeline.value
        if (p == Screen.Pipeline.Uploading || p is Screen.Pipeline.StartingAnalysis) { _panel.value = Screen.Panel.Analysing; return }
        // A restore still finishing in the background must not switch the screen to its own analysis
        // after the user picked this one (QA: restore/openHistory race).
        pipelineJob?.cancel()
        viewModelScope.launch {
            val e = withContext(Dispatchers.IO) { history.history.get(analysisId) } ?: return@launch
            val uri = withContext(Dispatchers.IO) { com.elevate.app.data.history.MatchHistoryRuntime.readableSource(getApplication(), e) }
            adopted = e
            pipelineTitle = e.title
            _lastAnalysis.value = null
            _hero.value = PipelineHero(
                com.elevate.app.ui.overview.AnalysingFlow.heroLine(e.sport, e.durationMs, e.width, e.height, null),
                uri, e.durationMs, e.title,
            )
            _pipeline.value = Screen.Pipeline.Started(e.analysisId, e.sessionId, e.matchId, dispatched = true, state = e.lastState ?: "queued")
            uri?.let { onPicked(it) }
            showPanel(if (e.status() == com.elevate.app.data.history.HistoryStatus.Analysing) Screen.Panel.Analysing else Screen.Panel.Overview)
        }
    }

    /** Live hit candidates for the open file (same sha256 as the analysed one), drawn on the video. */
    private val _liveHitsMs = MutableStateFlow<List<Long>>(emptyList())
    val liveHitsMs: StateFlow<List<Long>> = _liveHitsMs.asStateFlow()

    private fun clearLiveHits() {
        controller.setHits(emptyList())
        _liveHitsMs.value = emptyList()
    }

    fun onLiveHits(hits: List<com.elevate.app.domain.analysis.HitCandidate>) {
        val s = _screen.value as? Screen.Replay ?: return
        val facts = (s.preflight as? PreflightState.Done)?.facts
        val live = com.elevate.app.domain.analysis.LiveHits.forFile(
            hits, pipelineSha256(), s.sha256, facts?.durationMs, facts?.displayWidth, facts?.displayHeight,
        )
        controller.setHits(live, facts?.displayWidth, facts?.displayHeight)
        _liveHitsMs.value = live.map { it.tMs }
    }

    /**
     * Short line for a pill on the video / import screen ("Analysing · 18/485 s"); null when nothing
     * runs. Tapping it should call `showPanel(Screen.Panel.Analysing)` (= PipelineUi.onOpenAnalysis).
     */
    val pipelinePill: StateFlow<String?> by lazy {
        combine(_pipeline, _pipelineStep, uploadProgress, _lastAnalysis) { p, step, up, a ->
            AnalysingFlow.pill(p, step, up, a)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    private fun heroFrom(s: Screen.Replay, facts: SourceFacts, setup: SetupResult): PipelineHero {
        val sport = (setup.toJson()["sport"] as? JsonPrimitive)?.content
        return PipelineHero(
            AnalysingFlow.heroLine(sport, facts.durationMs, facts.displayWidth, facts.displayHeight, facts.fpsNominal),
            s.uri, facts.durationMs.takeIf { it > 0 }, s.title,
        )
    }

    /** After a restart: the same line from the saved session request (real probe values). */
    private fun heroFromSaved(st: UploadState): PipelineHero = runCatching {
        val req = Json.parseToJsonElement(st.createRequestJson).jsonObject
        val src = req.getValue("source").jsonObject
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content
        val duration = str(src, "duration_ms")?.toDoubleOrNull()?.toLong()
        PipelineHero(
            AnalysingFlow.heroLine(
                str(req, "sport"), duration, str(src, "width")?.toIntOrNull(), str(src, "height")?.toIntOrNull(),
                str(src, "fps_nominal")?.toDoubleOrNull(),
            ),
            runCatching { st.sourceUri.toUri() }.getOrNull(), duration?.takeIf { it > 0 }, null,
        )
    }.getOrElse { PipelineHero(null, null, null, null) }

    /**
     * Facts of the uploaded source, read back from the saved session request (real probe values), so
     * the analysis screens keep their denominators after a restart: duration µs, width, height.
     */
    fun pipelineSource(): Triple<Long?, Int?, Int?>? = runCatching {
        adopted?.let { return Triple(it.durationMs?.times(1000), it.width, it.height) }
        val st = uploadRuntime.coordinator.current() ?: return null
        val src = kotlinx.serialization.json.Json.parseToJsonElement(st.createRequestJson).jsonObject.getValue("source").jsonObject
        fun num(k: String) = (src[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        Triple(num("duration_ms")?.toLong()?.takeIf { it > 0 }?.let { it * 1000 }, num("width")?.toInt(), num("height")?.toInt())
    }.getOrNull()

    /** Title of the video the pipeline belongs to, for the analysis screens. */
    var pipelineTitle: String = "Your match"
        private set

    /** On launch: an unfinished upload saved on disk resumes and goes on to the analysis. */
    private fun restorePipeline() {
        if (!UploadRuntime.isConfigured) return
        viewModelScope.launch {
            val cur = withContext(Dispatchers.IO) { runCatching { uploadRuntime.coordinator.current() }.getOrNull() } ?: return@launch
            _hero.value = heroFromSaved(cur)
            when (cur.phase) {
                UploadPhase.Creating, UploadPhase.Uploading, UploadPhase.Completing -> {
                    _pipeline.value = Screen.Pipeline.Uploading
                    UploadScheduler.start(getApplication(), cur.bytes)
                    pipelineJob = viewModelScope.launch { finishPipeline() }
                }
                UploadPhase.Completed -> pipelineJob = viewModelScope.launch { finishPipeline() }
                else -> _pipeline.value = Screen.Pipeline.Failed("upload", cur.failure?.message ?: "upload stopped; tap to resume")
            }
        }
    }

    /**
     * Uploads the original file to R2 through the deployed API and starts the real analysis.
     * The session request is built only from measured facts (probe + sha256) and the user's setup.
     * The Analysing screen opens at once, before any session or analysis id exists; the upload job is
     * started as soon as the file is identified. Starting again while an upload runs only reopens it.
     */
    fun analyse() {
        adopted = null
        val s = _screen.value as? Screen.Replay ?: return
        val facts = (s.preflight as? PreflightState.Done)?.takeIf { !it.blocked }?.facts ?: return
        val setup = s.setup ?: return
        val running = _pipeline.value
        if (running == Screen.Pipeline.Uploading || running is Screen.Pipeline.StartingAnalysis) {
            _panel.value = Screen.Panel.Analysing
            return
        }
        pipelineJob?.cancel()
        pipelineTitle = s.title
        _hero.value = heroFrom(s, facts, setup)
        _lastAnalysis.value = null
        showPanel(Screen.Panel.Analysing)
        if (!UploadRuntime.isConfigured) {
            _pipeline.value = Screen.Pipeline.Failed("setup", "Elevate cloud is not configured in this build")
            return
        }
        _pipelineStep.value = if (s.sha256 == null) "Identifying the file" else "Preparing"
        _pipeline.value = Screen.Pipeline.Uploading
        pipelineJob = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val sha = s.sha256 ?: awaitSha(s.uri)
                if (sha == null) {
                    _pipelineStep.value = null
                    _pipeline.value = Screen.Pipeline.Failed("upload", "This video could not be identified, so it cannot be uploaded")
                    return@launch
                }
                _pipelineStep.value = "Preparing"
                val j = setup.toJson()
                fun str(k: String) = (j.getValue(k) as JsonPrimitive).content
                val request = SessionCreateRequest(
                    sport = str("sport"),
                    format = str("format"),
                    ruleset = Ruleset(setup.ruleset.id, setup.ruleset.version),
                    mode = str("mode"),
                    source = SessionSource.from(facts, sha, Build.MODEL),
                    calibrationInput = j.getValue("calibration_input"),
                    whoIsMe = j.getValue("who_is_me"),
                    freePlaySeed = j.getValue("free_play_seed"),
                    // Sound candidates are off (SOUND_RALLIES.md): none are sent.
                    rallyCandidates = JsonArray(emptyList()),
                    uploadMode = "multipart",
                )
                val existing = withContext(Dispatchers.IO) { runCatching { uploadRuntime.coordinator.current() }.getOrNull() }
                if (existing?.phase == UploadPhase.Completed && existing.createRequestJson == request.toJson()) {
                    // Same file and setup already stored: no second upload; the start is idempotent per upload.
                    _pipelineStep.value = null
                    finishPipeline()
                    return@launch
                }
                if (existing != null && existing.sha256 == sha && existing.phase != UploadPhase.Completed && existing.phase != UploadPhase.Aborted) {
                    // This file's upload stopped part way: resume it (accepted parts are kept) instead of failing.
                    _pipelineStep.value = null
                    UploadScheduler.start(app, existing.bytes)
                    finishPipeline()
                    return@launch
                }
                // Staging/hashing and the state file are disk work: off the main thread.
                withContext(Dispatchers.IO) {
                    val access = UriAccess.prepare(app, s.uri, facts.bytes, sha)
                    uploadRuntime.coordinator.prepare(request, sourceUri = s.uri.toString(), stagedPath = (access as? SourceAccess.Staged)?.file?.path)
                }
                _pipelineStep.value = null
                if (!UploadScheduler.start(app, facts.bytes)) {
                    _pipeline.value = Screen.Pipeline.Failed("upload", "The system refused to start the upload")
                    return@launch
                }
                finishPipeline()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pipelineStep.value = null
                _pipeline.value = Screen.Pipeline.Failed("upload", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The file's sha256 once the fingerprint started on open finishes; null if it cannot be measured. */
    private suspend fun awaitSha(uri: Uri): String? {
        val r = _screen.first { cur ->
            cur !is Screen.Replay || cur.uri != uri || cur.sha256 != null ||
                cur.annotations is AnnotationState.Failed || cur.annotations is AnnotationState.UnsupportedHdr
        }
        return (r as? Screen.Replay)?.takeIf { it.uri == uri }?.sha256
    }

    /** Waits for the server to confirm the upload, then starts the analysis (idempotent per upload). */
    private suspend fun finishPipeline() {
        val rt = uploadRuntime
        var stage = "upload"
        try {
            // Watch the saved phase on disk, not the progress stream: after a failure the stream still
            // holds the old Failed value, which ended a retry at once (seen on the A55). A failure only
            // counts once the job has been active again; a job that never starts is reported after 30 s.
            var active = false
            var waitedMs = 0L
            while (true) {
                val cur = withContext(Dispatchers.IO) { rt.coordinator.current() } ?: error("upload state missing")
                when (cur.phase) {
                    UploadPhase.Completed -> break
                    UploadPhase.Failed, UploadPhase.Aborted -> if (active || waitedMs >= 30_000) {
                        _pipeline.value = Screen.Pipeline.Failed("upload", cur.failure?.let { "${it.code}: ${it.message}" } ?: "upload stopped")
                        return
                    }
                    else -> active = true
                }
                delay(1_000)
                waitedMs += 1_000
            }
            val st = withContext(Dispatchers.IO) { rt.coordinator.current() } ?: error("upload state missing after completion")
            val sessionId = st.sessionId ?: error("no session id after completion")
            stage = "analysis"
            _pipeline.value = Screen.Pipeline.StartingAnalysis(sessionId)
            // One key per upload: a start retried after process death returns the same analysis (contracts §3).
            val started = analysisClient.startAnalysis(sessionId, "analyse-${st.localId}")
            _pipeline.value = Screen.Pipeline.Started(started.response.analysisId, sessionId, st.matchId, started.dispatched, started.response.state)
            adopted = null
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = kotlinx.serialization.json.Json.parseToJsonElement(st.createRequestJson).jsonObject
                    val src = req.getValue("source").jsonObject
                    fun num(k: String) = (src[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
                    history.recorder.onStarted(
                        started.response.analysisId, sessionId, st.matchId, st.sha256,
                        _hero.value.fileName ?: pipelineTitle, st.sourceUri, st.stagedPath,
                        (req["sport"] as? JsonPrimitive)?.content, num("duration_ms")?.toLong(), num("width")?.toInt(), num("height")?.toInt(),
                        started.response.state,
                    )
                }
            }
            refreshMatches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _pipeline.value = Screen.Pipeline.Failed(stage, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Retry after a failure: resumes the saved upload (accepted parts are kept), or redoes the analysis start. */
    fun retryPipeline() {
        // A cancel that failed (offline) is retried as a cancel, never turned into a resumed upload.
        if ((_pipeline.value as? Screen.Pipeline.Failed)?.stage == "cancel") { cancelUpload(); return }
        val cur = runCatching { uploadRuntime.coordinator.current() }.getOrNull()
        if (cur == null || cur.phase == UploadPhase.Aborted) {
            // Nothing saved to resume (e.g. it failed before the upload existed): start from the top.
            pipelineJob?.cancel()
            _pipeline.value = Screen.Pipeline.Idle
            analyse()
            return
        }
        pipelineJob?.cancel()
        _pipeline.value = Screen.Pipeline.Uploading
        if (cur.phase != UploadPhase.Completed) UploadScheduler.start(getApplication(), cur.bytes)
        pipelineJob = viewModelScope.launch { finishPipeline() }
    }

    fun cancelUpload() {
        pipelineJob?.cancel()
        viewModelScope.launch {
            UploadScheduler.stop(getApplication())
            _pipelineStep.value = null
            try {
                uploadRuntime.coordinator.cancel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or a server error on abort: the saved upload is kept (retry resumes it).
                // Uncaught here, this exception crashed the app from viewModelScope.
                _pipeline.value = Screen.Pipeline.Failed("cancel", "The upload could not be cancelled: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            _pipeline.value = Screen.Pipeline.Idle
            _panel.value = Screen.Panel.None
        }
    }

    fun showPanel(panel: Screen.Panel) {
        // The Analysing / Overview panels replace the video screen: no sound from a video nobody sees.
        if (panel != Screen.Panel.None) controller.pause()
        _panel.value = panel
    }

    /** From the overview: back to the player at the rally start. */
    /** Seek target for a video that is still opening (rally tapped while another screen was showing). */
    private var pendingSeekMs: Long? = null

    /**
     * From the overview: the analysed video at the rally start. If that video is not the one open,
     * it is opened first (same file = same sha256 later, so the live trail and hits draw on it); if the
     * phone no longer grants access to it, the home screen asks to pick it again.
     */
    fun openRally(startMs: Long) {
        showPanel(Screen.Panel.None)
        val s = _screen.value as? Screen.Replay
        val analysedSha = pipelineSha256()
        if (s != null && (analysedSha == null || (s.sha256 != null && s.sha256 == analysedSha))) {
            controller.pause()
            controller.seekTo(startMs)
            return
        }
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { readableAnalysedSource() }
            if (uri == null) {
                _screen.value = Screen.Import(error = "Pick this match's video again to watch the rally with its trail.")
                return@launch
            }
            pendingSeekMs = startMs
            onPicked(uri)
        }
    }

    /** The analysed video if this app can still read it (staged copy or a held read grant). */
    private fun readableAnalysedSource(): Uri? {
        adopted?.let { return com.elevate.app.data.history.MatchHistoryRuntime.readableSource(getApplication(), it) }
        val uri = _hero.value.sourceUri ?: return null
        return runCatching { getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { uri } }.getOrNull()
    }

    fun setOverlayVisible(visible: Boolean) {
        controller.setOverlayVisible(visible)
        _screen.update { s -> if (s is Screen.Replay) s.copy(overlayVisible = visible) else s }
    }

    fun back() {
        openJob?.cancel()
        controller.player.stop()
        controller.player.clearMediaItems()
        controller.setTrack(null)
        clearLiveHits()
        pendingSeekMs = null
        _screen.value = Screen.Import()
    }

    // Last in the class body: restorePipeline's IO block reads `uploadRuntime` from another thread,
    // and its lazy delegate (like every property below the old first init block) is only assigned
    // once the constructor reaches its declaration. Started earlier, the read raced the constructor,
    // hit an NPE inside runCatching and silently skipped restoring the saved upload.
    init {
        refreshBackend()
        restorePipeline()
    }

    override fun onCleared() {
        runCatching { history.recorder.flush() }
        controller.release()
    }

    companion object {
        const val FIXTURE_DIR = "fixtures"
        const val ENGINE_DIR = "engine_runs"
    }

    // Last in the class: it reads _pipeline, uploadRuntime and adopted, which must exist first
    // (init blocks and property initializers run in declaration order).
    init {
        viewModelScope.launch {
            _pipeline.collect { _analysedSha.value = withContext(Dispatchers.IO) { pipelineSha256() } }
        }
    }
}
