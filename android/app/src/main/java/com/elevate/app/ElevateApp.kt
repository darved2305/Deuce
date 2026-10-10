package com.elevate.app

import androidx.compose.runtime.remember
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elevate.app.data.analysis.FileCursorStore
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.ui.overview.AnalysingScreen
import com.elevate.app.ui.overview.MatchOverviewScreen
import com.elevate.app.ui.overview.OverviewViewModel
import com.elevate.app.ui.screens.PipelineUi
import com.elevate.app.ui.screens.PipelineStatus
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.screens.ImportScreen
import com.elevate.app.ui.screens.ReplayScreen
import com.elevate.app.ui.setup.SetupFlowScreen
import com.elevate.app.domain.media.PreflightState

/** App root: Import → Replay. One ViewModel owns the player, so it survives recomposition and rotation. */
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
    // Upload progress notification (API 33+). Without it the upload still runs.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    val askNotifications = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val pipelineUi = PipelineUi(
        state = pipeline,
        upload = upload,
        onAnalyse = { askNotifications(); vm.analyse() },
        onRetryUpload = vm::retryPipeline,
        onCancelUpload = vm::cancelUpload,
        onOpenAnalysis = { vm.showPanel(Screen.Panel.Analysing) },
    )
    val started = pipeline as? Screen.Pipeline.Started
    if (started != null && panel != Screen.Panel.None) {
        AnalysisPanels(vm, panel, started, (screen as? Screen.Replay)?.let { (it.preflight as? PreflightState.Done)?.facts })
        return
    }

    when (val s = screen) {
        is Screen.Import -> ImportScreen(opening = s.opening, error = s.error, onChoose = pickVideo, backend = backend) {
            if (pipeline != Screen.Pipeline.Idle) PipelineStatus(pipelineUi)
        }
        is Screen.Replay -> {
            val facts = (s.preflight as? PreflightState.Done)?.facts
            if (s.setupAtUs != null && facts != null) {
                SetupFlowScreen(uri = s.uri, facts = facts, frameUs = s.setupAtUs, onCancel = vm::cancelSetup, onDone = vm::finishSetup)
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

/** Analysing → Match Overview for the analysis this file started, polled from the real event cursor. */
@UnstableApi
@Composable
private fun AnalysisPanels(vm: ReplayViewModel, panel: Screen.Panel, started: Screen.Pipeline.Started, facts: SourceFacts?) {
    val context = LocalContext.current
    // Prefer the open video's probe; after a restart, the source facts saved with the upload.
    val saved = remember(started.analysisId) { vm.pipelineSource() }
    val ovm: OverviewViewModel = viewModel(
        key = "analysis:${started.analysisId}",
        factory = OverviewViewModel.Factory {
            OverviewViewModel(
                client = vm.analysisClient,
                analysisId = started.analysisId,
                matchId = started.matchId,
                title = vm.pipelineTitle,
                cursorStore = FileCursorStore(File(context.filesDir, "analysis")),
                sourceDurationUs = facts?.durationMs?.takeIf { it > 0 }?.let { it * 1000 } ?: saved?.first,
                sourceWidth = facts?.displayWidth ?: saved?.second,
                sourceHeight = facts?.displayHeight ?: saved?.third,
            )
        },
    )
    LifecycleEventEffect(Lifecycle.Event.ON_START) { ovm.startObserving() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { ovm.stopObserving() }
    when (panel) {
        Screen.Panel.Overview -> {
            val overview by ovm.overview.collectAsStateWithLifecycle()
            MatchOverviewScreen(
                state = overview,
                onOpenRally = { _, startMs, _ -> vm.openRally(startMs) },
                onBack = { vm.showPanel(Screen.Panel.Analysing) },
            )
        }
        else -> {
            val analysis by ovm.analysis.collectAsStateWithLifecycle()
            AnalysingScreen(
                state = analysis,
                onOpenResults = { vm.showPanel(Screen.Panel.Overview) },
                onRetry = ovm::retryNow,
                onBack = { vm.showPanel(Screen.Panel.None) },
            )
        }
    }
}
