package com.elevate.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.elevate.app.data.DefaultMatchRepository
import com.elevate.app.data.api.ApiSource
import com.elevate.app.data.fixture.FixtureSource
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.domain.repository.MatchRepository
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
    ) : Screen
}

@UnstableApi
class ReplayViewModel(app: Application) : AndroidViewModel(app) {

    val controller = PlayerController(app)
    private val inspector = VideoInspector(app.contentResolver)
    private val repository: MatchRepository = DefaultMatchRepository(
        fixtures = FixtureSource {
            val assets = app.assets
            (assets.list(FIXTURE_DIR) ?: emptyArray()).filter { it.endsWith(".json") }.sorted().map { name ->
                name to assets.open("$FIXTURE_DIR/$name").bufferedReader().use { it.readText() }
            }
        },
        api = ApiSource(),
        io = Dispatchers.IO,
    )

    private val _screen = MutableStateFlow<Screen>(Screen.Import())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var openJob: Job? = null

    /** Picker returned [uri]; null means the user cancelled, which changes nothing. */
    fun onPicked(uri: Uri?) {
        if (uri == null) return
        openJob?.cancel()
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
            controller.setOverlayVisible(true)
            _screen.value = Screen.Replay(uri, info.displayName ?: "Your video", info)
            if (info.isHdr) {
                _screen.value = Screen.Replay(uri, info.displayName ?: "Your video", info, AnnotationState.UnsupportedHdr)
                return@launch
            }

            val annotations = try {
                val fp = withContext(Dispatchers.IO) { inspector.fingerprint(uri) }
                repository.annotationsFor(fp)
            } catch (e: IOException) {
                AnnotationState.Failed("could not read the file to identify it: ${e.message}")
            } catch (e: SecurityException) {
                AnnotationState.Failed("access to the file was revoked while identifying it")
            }
            val current = _screen.value
            if (current is Screen.Replay && current.uri == uri) {
                controller.setTrack((annotations as? AnnotationState.Available)?.track)
                _screen.value = current.copy(annotations = annotations)
            }
        }
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
        _screen.value = Screen.Import()
    }

    override fun onCleared() {
        controller.release()
    }

    companion object {
        const val FIXTURE_DIR = "fixtures"
    }
}
