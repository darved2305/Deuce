package com.elevate.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import com.elevate.app.ui.components.panelTransition
import com.elevate.app.ui.components.rememberReduceMotion
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.elevate.app.data.analysis.ArtifactAccess
import com.elevate.app.data.analysis.FileCursorStore
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.media.PreflightState
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.overview.AnalysingFlow
import com.elevate.app.ui.overview.AnalysingHero
import com.elevate.app.ui.overview.AnalysingScreen
import com.elevate.app.ui.overview.FrameThumbnail
import com.elevate.app.ui.overview.MatchOverviewScreen
import com.elevate.app.ui.overview.OverviewViewModel
import com.elevate.app.ui.overview.ThumbnailState
import com.elevate.app.ui.screens.ImportScreen
import com.elevate.app.ui.screens.PipelineStatus
import com.elevate.app.ui.screens.PipelineUi
import com.elevate.app.ui.screens.ReplayScreen
import com.elevate.app.ui.setup.SetupFlowScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** App root: Import → Replay, with the Analysing / Match panels over them. One ViewModel owns the player. */
@UnstableApi
@Composable
fun ElevateApp(vm: ReplayViewModel = viewModel()) {
    // System Photo Picker, videos only: no storage permission. The returned grant lasts while
    // this activity lives, which is all Phase 1 playback needs (no persistence yet).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.onPicked(uri) }
    val pickVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    val screen by vm.screen.collectAsStateWithLifecycle()
    val playback by vm.controller.state.collectAsStateWithLifecycle()
    val backend by vm.backend.collectAsStateWithLifecycle()
    // Not vm.uploadRuntime directly: building it throws on a build without cloud config.
    val upload by vm.uploadProgress.collectAsStateWithLifecycle()
    val pipeline by vm.pipeline.collectAsStateWithLifecycle()
    val panel by vm.panel.collectAsStateWithLifecycle()
    val step by vm.pipelineStep.collectAsStateWithLifecycle()
    val hero by vm.pipelineHero.collectAsStateWithLifecycle()
    // Upload progress notification (API 33+). Without it the upload still runs.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    val askNotifications = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val pill by vm.pipelinePill.collectAsStateWithLifecycle()
    val analysedSha by vm.analysedSha.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val thumbs by vm.matchThumbs.collectAsStateWithLifecycle()
    val liveHits by vm.liveHitsMs.collectAsStateWithLifecycle()
    // The analysis on record is of the open file (same sha256): don't offer to analyse it again.
    val openShaForCta = (screen as? Screen.Replay)?.sha256
    val analysedHere = pipeline != Screen.Pipeline.Idle && openShaForCta != null &&
        analysedSha == openShaForCta
    val openFacts = ((screen as? Screen.Replay)?.preflight as? PreflightState.Done)?.facts
    val started = pipeline as? Screen.Pipeline.Started
    // One OverviewViewModel per analysis, alive while the app is in the foreground, so the
    // Analysing screen and the video screen's pill (vm.pipelinePill) both show the latest server state.
    val ovm = if (started != null) rememberAnalysis(vm, started, hero, openFacts) else null

    val pipelineUi = PipelineUi(
        state = pipeline,
        upload = upload,
        onAnalyse = { askNotifications(); vm.analyse() },
        onRetryUpload = vm::retryPipeline,
        onCancelUpload = vm::cancelUpload,
        onOpenAnalysis = {
            // A finished (or every-window-in) analysis opens the overview; a running one its progress.
            val a = ovm?.analysis?.value
            val ready = a?.state == com.elevate.app.domain.analysis.AnalysisState.COMPLETED ||
                com.elevate.app.domain.analysis.WindowCoverage.readyFromWindows(a)
            vm.showPanel(if (ready) Screen.Panel.Overview else Screen.Panel.Analysing)
        },
        pill = pill,
        analysedHere = analysedHere,
    )

    // Each downloaded window redraws the live trail on the open video (when it is the analysed file).
    val openSha = (screen as? Screen.Replay)?.sha256
    val openW = openFacts?.displayWidth
    val openH = openFacts?.displayHeight
    if (ovm != null && openSha != null && openW != null && openH != null) {
        LaunchedEffect(ovm, openSha, openW, openH) {
            ovm.sync.ballTrack.collect {
                val clip = com.elevate.app.domain.model.ClipIdentity("live", setOf(openSha), openW, openH)
                vm.onLiveTrail(ovm.engineTrail(clip), ovm.sync.engineVersion)
            }
        }
        LaunchedEffect(ovm, openSha, openW, openH) {
            ovm.sync.hits.collect { vm.onLiveHits(it) }
        }
        LaunchedEffect(ovm, openSha, openW, openH) {
            ovm.matchRecord.collect { vm.onMatchRecord(it) }
        }
    }

    if (panel != Screen.Panel.None && pipeline != Screen.Pipeline.Idle) {
        AnalysisPanels(vm, panel, pipeline, step, upload, ovm, hero, if (analysedHere) openFacts else null)
        return
    }

    when (val s = screen) {
        is Screen.Import -> ImportScreen(
            opening = s.opening, error = s.error, onChoose = pickVideo, backend = backend,
            matches = matches.map { com.elevate.app.ui.screens.matchCardOf(it, thumbs[it.analysisId]) },
            onOpenMatch = vm::openHistory,
            onOpenProfile = vm::openProfile,
        ) {
            // The pill only while something is actually running; a finished run is the history card.
            val running = when (val p = pipeline) {
                Screen.Pipeline.Uploading, is Screen.Pipeline.StartingAnalysis -> true
                is Screen.Pipeline.Started ->
                    (ovm?.analysis?.value?.state ?: com.elevate.app.domain.analysis.AnalysisState.fromWire(p.state))?.isTerminal != true
                else -> false
            }
            if (running) PipelineStatus(pipelineUi)
        }
        is Screen.Profile -> com.elevate.app.ui.profile.ProfileRoute(onBack = vm::back)
        is Screen.Replay -> {
            if (s.setupAtUs != null && openFacts != null) {
                // Confirming the setup starts the upload + analysis and opens the Analysing screen at once.
                SetupFlowScreen(
                    uri = s.uri, facts = openFacts, frameUs = s.setupAtUs, onCancel = vm::cancelSetup,
                    onDone = { result -> askNotifications(); vm.finishSetup(result) },
                )
                return
            }
            ReplayScreen(
                screen = s,
                state = playback,
                controller = vm.controller,
                onBack = vm::back,
                onChooseAnother = pickVideo,
                onOverlayVisible = vm::setOverlayVisible,
                onSetUp = vm::startSetup,
                onShowEngine = vm::showEngine,
                onShowLive = vm::showLive,
            hitsMs = liveHits,
                pipeline = pipelineUi,
            )
        }
    }
}

/**
 * The analysis this upload started, polled from the real event cursor while the app is started
 * (contracts §3: the analysis is on screen, as the Analysing panel or the video screen's pill).
 */
@UnstableApi
@Composable
private fun rememberAnalysis(vm: ReplayViewModel, started: Screen.Pipeline.Started, hero: ReplayViewModel.PipelineHero, openFacts: SourceFacts?): OverviewViewModel {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val filesDir = LocalContext.current.applicationContext.filesDir
    // The pipeline's own source facts (saved with the upload) first: the open video may be another file.
    val saved = remember(started.analysisId) { vm.pipelineSource() }
    val ovm: OverviewViewModel = viewModel(
        key = "analysis:${started.analysisId}",
        factory = OverviewViewModel.Factory {
            val uri = hero.sourceUri
            OverviewViewModel(
                client = vm.analysisClient,
                analysisId = started.analysisId,
                matchId = started.matchId,
                title = hero.fileName ?: vm.pipelineTitle,
                cursorStore = FileCursorStore(File(filesDir, "analysis")),
                sourceDurationUs = saved?.first ?: hero.durationMs?.let { it * 1000 } ?: openFacts?.durationMs?.takeIf { it > 0 }?.let { it * 1000 },
                sourceWidth = saved?.second ?: openFacts?.displayWidth,
                sourceHeight = saved?.third ?: openFacts?.displayHeight,
                // Real frame times of the open file: rows snap to frames and counts get a frame denominator.
                sourceFrameTimesUs = openFacts?.frameTimesUs,
                windowCache = com.elevate.app.data.analysis.WindowCache.forAnalysis(filesDir, started.analysisId),
                thumbnailLoader = uri?.let { u -> { atMs -> withContext(Dispatchers.IO) { FrameThumbnail.load(resolver, u, atMs) } } },
            )
        },
    )
    val owner = LocalLifecycleOwner.current
    DisposableEffect(ovm, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> ovm.startObserving()
                Lifecycle.Event.ON_STOP -> ovm.stopObserving()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            ovm.stopObserving()
        }
    }
    val analysis by ovm.analysis.collectAsStateWithLifecycle()
    LaunchedEffect(ovm, analysis) { vm.onAnalysisState(analysis) }
    return ovm
}

/**
 * Analysing → Match overview. Analysing renders from the first tap: the upload stage from the
 * upload's measured progress, later stages from the server once the analysis exists.
 */
@UnstableApi
@Composable
private fun AnalysisPanels(
    vm: ReplayViewModel,
    panel: Screen.Panel,
    pipeline: Screen.Pipeline,
    step: String?,
    upload: UploadProgress,
    ovm: OverviewViewModel?,
    hero: ReplayViewModel.PipelineHero,
    analysedFacts: SourceFacts?,
) {
    // Analysing, Overview, ReadLab and Reflex cross-fade into each other (snap when motion is reduced).
    val reduce = rememberReduceMotion()
    AnimatedContent(targetState = panel, transitionSpec = { panelTransition(reduce) }, label = "analysis-panel") { shown ->
        AnalysisPanel(vm, shown, pipeline, step, upload, ovm, hero, analysedFacts)
    }
}

@UnstableApi
@Composable
private fun AnalysisPanel(
    vm: ReplayViewModel,
    panel: Screen.Panel,
    pipeline: Screen.Pipeline,
    step: String?,
    upload: UploadProgress,
    ovm: OverviewViewModel?,
    hero: ReplayViewModel.PipelineHero,
    /** Facts of the open video when it is the analysed file; ReadLab freezes frames of that file only. */
    analysedFacts: SourceFacts?,
) {
    if ((panel == Screen.Panel.Overview || panel == Screen.Panel.ReadLab || panel == Screen.Panel.Reflex || panel == Screen.Panel.Review || panel == Screen.Panel.Decide) && ovm != null) {
        val match by ovm.matchRecord.collectAsStateWithLifecycle()
        val drills = remember(match, analysedFacts) {
            com.elevate.app.ui.readlab.ReadLabDrills.fromMatch(match, analysedFacts?.frameTimesUs, analysedFacts?.displayWidth, analysedFacts?.displayHeight)
        }
        val reflexRounds = remember(drills) { com.elevate.app.ui.readlab.ReflexRounds.of(drills).size }
        val ball by ovm.sync.ballTrack.collectAsStateWithLifecycle()
        val w = analysedFacts?.displayWidth
        val h = analysedFacts?.displayHeight
        val cueSource = remember(ball, w, h) {
            if (w != null && h != null && w > 0 && h > 0) com.elevate.app.ui.readlab.CueSource(ball, w, h) else null
        }
        val reviewLandings = remember(match) { com.elevate.app.domain.review.ReviewLandings.from(match) }
        if (panel == Screen.Panel.Review) {
            com.elevate.app.ui.review.InstantReviewRoute(vm, reviewLandings, analysedFacts, ball, match?.matchId, onBack = { vm.showPanel(Screen.Panel.Overview) })
            return
        }
        if (panel == Screen.Panel.ReadLab) {
            com.elevate.app.ui.profile.RecordReadLabResult(vm.readLab, match?.matchId)
            com.elevate.app.ui.readlab.ReadLabRoute(vm, drills, cueSource, onBack = { vm.showPanel(Screen.Panel.Overview) })
            return
        }
        val feet by ovm.sync.feet.collectAsStateWithLifecycle()
        val freezes = remember(match, analysedFacts, feet) {
            com.elevate.app.ui.decide.DecideFreezes.fromMatch(match, analysedFacts?.frameTimesUs, analysedFacts?.displayWidth, analysedFacts?.displayHeight, feet)
        }
        if (panel == Screen.Panel.Decide) {
            com.elevate.app.ui.decide.DecideRoute(vm, freezes, onBack = { vm.showPanel(Screen.Panel.Overview) })
            return
        }
        if (panel == Screen.Panel.Reflex) {
            com.elevate.app.ui.readlab.ReflexRoute(vm, drills, match?.matchId.orEmpty(), cueSource, onBack = { vm.showPanel(Screen.Panel.Overview) })
            return
        }
        val overview by ovm.overview.collectAsStateWithLifecycle()
        MatchOverviewScreen(
            state = overview,
            eyebrow = hero.eyebrow,
            onOpenRally = { _, startMs, _ -> vm.openRally(startMs) },
            onBack = { vm.showPanel(Screen.Panel.None); vm.back() },
            readLabDrills = drills.size,
            onTrainRead = { vm.showPanel(Screen.Panel.ReadLab) },
            reflexRounds = reflexRounds,
            onReflex = { vm.showPanel(Screen.Panel.Reflex) },
            reviewLandings = reviewLandings,
            onReview = { id -> vm.review.select(id); vm.showPanel(Screen.Panel.Review) },
            decideFreezes = freezes.size,
            onDecide = { vm.showPanel(Screen.Panel.Decide) },
        )
        return
    }
    val analysis: AnalysisUiState?
    val artifacts: ArtifactAccess
    val cache: com.elevate.app.data.analysis.CacheInfo?
    if (ovm != null) {
        analysis = ovm.analysis.collectAsStateWithLifecycle().value
        artifacts = ovm.artifactAccess.collectAsStateWithLifecycle().value
        cache = ovm.sync.cache.collectAsStateWithLifecycle().value
    } else {
        analysis = null
        artifacts = ArtifactAccess.Unknown
        cache = null
    }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val uri = hero.sourceUri
    // A real decoded frame of the user's video, read off the main thread.
    val thumbnail by produceState<ThumbnailState?>(if (uri == null) null else ThumbnailState.Loading, uri) {
        value = uri?.let { u -> withContext(Dispatchers.IO) { FrameThumbnail.load(resolver, u, (hero.durationMs ?: 0) / 2) } }
    }
    val flow = remember(pipeline, step, upload, analysis, artifacts, cache) { AnalysingFlow.state(pipeline, step, upload, analysis, artifacts, cache) }
    AnalysingScreen(
        flow = flow,
        hero = AnalysingHero(hero.eyebrow, thumbnail, hero.fileName),
        onOpenResults = { vm.showPanel(Screen.Panel.Overview) },
        onRetry = { if (pipeline is Screen.Pipeline.Failed) vm.retryPipeline() else ovm?.retryNow() },
        onCancel = vm::cancelUpload,
        onBack = { vm.showPanel(Screen.Panel.None) },
    )
}
