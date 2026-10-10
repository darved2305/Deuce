package com.elevate.app.ui

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
import com.elevate.app.domain.media.Preflight
import com.elevate.app.domain.media.PreflightState
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.domain.repository.BackendStatus
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
        val preflight: PreflightState = PreflightState.Checking,
    ) : Screen
}

@UnstableApi
class ReplayViewModel(app: Application) : AndroidViewModel(app) {

    val controller = PlayerController(app)
    private val inspector = VideoInspector(app.contentResolver)
    private val probe = MediaProbe(app.contentResolver)
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

    init { refreshBackend() }

    fun refreshBackend() {
        viewModelScope.launch { _backend.value = repository.backendStatus() }
    }

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
