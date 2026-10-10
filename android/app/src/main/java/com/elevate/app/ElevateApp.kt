package com.elevate.app

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

    when (val s = screen) {
        is Screen.Import -> ImportScreen(opening = s.opening, error = s.error, onChoose = pickVideo)
        is Screen.Replay -> ReplayScreen(
            screen = s,
            state = playback,
            controller = vm.controller,
            onBack = vm::back,
            onChooseAnother = pickVideo,
            onOverlayVisible = vm::setOverlayVisible,
        )
    }
}
