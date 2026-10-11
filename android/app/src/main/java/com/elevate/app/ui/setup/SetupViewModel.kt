package com.elevate.app.ui.setup

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elevate.app.data.media.FrameGrab
import com.elevate.app.data.media.FrameGrabber
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.playback.TimeMapping
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The still frame the user taps on. There is never a blank canvas: no frame means [Failed]. */
sealed interface SetupFrame {
    data object Loading : SetupFrame
    data class Ready(val image: ImageBitmap, val srcW: Int, val srcH: Int, val frameMs: Long, val exact: Boolean) : SetupFrame
    data class Failed(val code: String, val message: String) : SetupFrame
}

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val grabber = FrameGrabber(app.contentResolver)

    private val _frame = MutableStateFlow<SetupFrame>(SetupFrame.Loading)
    val frame: StateFlow<SetupFrame> = _frame.asStateFlow()

    private val _state = MutableStateFlow<SetupState?>(null)
    /** Null until the frame is ready: taps need its size and time. */
    val state: StateFlow<SetupState?> = _state.asStateFlow()

    private var loaded: Pair<Uri, Long>? = null
    private var job: Job? = null

    /** Grabs the frame at [frameUs] once per (uri, time); repeated calls (recomposition, rotation) do nothing. */
    fun load(uri: Uri, facts: SourceFacts, frameUs: Long) {
        val key = uri to frameUs
        if (loaded == key) return
        loaded = key
        job?.cancel()
        _frame.value = SetupFrame.Loading
        _state.value = null
        val atUs = if (facts.durationMs > 0) frameUs.coerceIn(0, facts.durationMs * 1000) else frameUs.coerceAtLeast(0)
        job = viewModelScope.launch {
            when (val g = grabber.grab(uri, atUs)) {
                is FrameGrab.Failed -> {
                    loaded = null // so reopening retries
                    _frame.value = SetupFrame.Failed(g.code, g.message)
                }
                is FrameGrab.Ok -> {
                    val f = g.frame
                    // The probe's display size is the image space of every other record (contracts §0).
                    val w = facts.displayWidth.takeIf { it > 0 } ?: f.srcWidth
                    val h = facts.displayHeight.takeIf { it > 0 } ?: f.srcHeight
                    val ms = TimeMapping.usToMs(f.framePtsUs ?: atUs)
                    _frame.value = SetupFrame.Ready(f.bitmap.asImageBitmap(), w, h, ms, f.exact)
                    _state.value = SetupState(srcW = w.toDouble(), srcH = h.toDouble(), frameMs = ms)
                }
            }
        }
    }

    fun dispatch(a: SetupAction) {
        _state.update { s -> s?.let { reduce(it, a) } }
    }
}
