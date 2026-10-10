package com.elevate.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
    val upload by vm.uploadRuntime.coordinator.progress.collectAsStateWithLifecycle()
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
    val pipelineUi = PipelineUi(
        state = pipeline,
        upload = upload,
        onAnalyse = { askNotifications(); vm.analyse() },
        onRetryUpload = vm::retryPipeline,
        onCancelUpload = vm::cancelUpload,
        onOpenAnalysis = { vm.showPanel(Screen.Panel.Analysing) },
        pill = pill,
    )
    val openFacts = ((screen as? Screen.Replay)?.preflight as? PreflightState.Done)?.facts
    val started = pipeline as? Screen.Pipeline.Started
    // One OverviewViewModel per analysis, alive while the app is in the foreground, so the
    // Analysing screen and the video screen's pill (vm.pipelinePill) both show the latest server state.
    val ovm = if (started != null) rememberAnalysis(vm, started, hero, openFacts) else null

    if (panel != Screen.Panel.None && pipeline != Screen.Pipeline.Idle) {
        AnalysisPanels(vm, panel, pipeline, step, upload, ovm, hero)
        return
    }

    when (val s = screen) {
        is Screen.Import -> ImportScreen(opening = s.opening, error = s.error, onChoose = pickVideo, backend = backend) {
            if (pipeline != Screen.Pipeline.Idle) PipelineStatus(pipelineUi)
        }
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
) {
    if (panel == Screen.Panel.Overview && ovm != null) {
        val overview by ovm.overview.collectAsStateWithLifecycle()
        MatchOverviewScreen(
            state = overview,
            eyebrow = hero.eyebrow,
            onOpenRally = { _, startMs, _ -> vm.openRally(startMs) },
            onBack = { vm.showPanel(Screen.Panel.Analysing) },
        )
        return
    }
    val analysis: AnalysisUiState?
    val artifacts: ArtifactAccess
    if (ovm != null) {
        analysis = ovm.analysis.collectAsStateWithLifecycle().value
        artifacts = ovm.artifactAccess.collectAsStateWithLifecycle().value
    } else {
        analysis = null
        artifacts = ArtifactAccess.Unknown
    }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val uri = hero.sourceUri
    // A real decoded frame of the user's video, read off the main thread.
    val thumbnail by produceState<ThumbnailState?>(if (uri == null) null else ThumbnailState.Loading, uri) {
        value = uri?.let { u -> withContext(Dispatchers.IO) { FrameThumbnail.load(resolver, u, (hero.durationMs ?: 0) / 2) } }
    }
    val flow = remember(pipeline, step, upload, analysis, artifacts) { AnalysingFlow.state(pipeline, step, upload, analysis, artifacts) }
    AnalysingScreen(
        flow = flow,
        hero = AnalysingHero(hero.eyebrow, thumbnail, hero.fileName),
        onOpenResults = { vm.showPanel(Screen.Panel.Overview) },
        onRetry = { if (pipeline is Screen.Pipeline.Failed) vm.retryPipeline() else ovm?.retryNow() },
        onCancel = vm::cancelUpload,
        onBack = { vm.showPanel(Screen.Panel.None) },
    )
}
