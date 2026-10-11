package com.elevate.app.ui.record

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.elevate.app.BuildConfig
import com.elevate.app.data.analysis.HttpAnalysisClient
import com.elevate.app.data.history.MatchHistoryRuntime
import com.elevate.app.data.record.CameraSession
import com.elevate.app.data.record.DeviceCaptureCaps
import com.elevate.app.data.record.DeviceSignals
import com.elevate.app.data.record.EndReason
import com.elevate.app.data.record.Gate
import com.elevate.app.data.record.HttpSegmentPutter
import com.elevate.app.data.record.NetworkState
import com.elevate.app.data.record.PieceFiles
import com.elevate.app.data.record.RecordGates
import com.elevate.app.data.record.RecordedSegment
import com.elevate.app.data.record.RecordingParams
import com.elevate.app.data.record.RecordingParamsChooser
import com.elevate.app.data.record.SegmentClient
import com.elevate.app.data.record.SegmentPlanner
import com.elevate.app.data.record.SegmentSessionRequest
import com.elevate.app.data.record.SegmentSessionTicket
import com.elevate.app.data.record.SegmentUploadStatus
import com.elevate.app.data.record.SegmentUploader
import com.elevate.app.data.record.SegmentedRecorder
import com.elevate.app.data.record.UploadNetwork
import com.elevate.app.data.record.UploadNetworkPolicy
import com.elevate.app.data.session.ElevateHttp
import com.elevate.app.data.session.Ruleset
import com.elevate.app.data.session.SessionSource
import com.elevate.app.data.session.SourcePts
import com.elevate.app.data.session.UrlConnectionTransport
import com.elevate.app.data.upload.UploadRuntime
import com.elevate.app.ui.setup.SetupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class RecordPhase {
    /** Reading what the camera can do. */
    Checking,
    /** No back camera, or the cloud is not configured: nothing can be recorded. */
    Unavailable,
    /** Live preview with the framing guide. */
    Framing,
    /** The setup screen on the captured still (court corners, who you are). */
    Setup,
    /** Court marked: Record is available. */
    Ready,
    Recording,
    /** Stopped: the last pieces are still going out and the local copy is being made. */
    Finishing,
    /** The analysis exists and the local copy is ready: the Analysing screen takes over. */
    Done,
    Failed,
}

data class RecordUi(
    val phase: RecordPhase = RecordPhase.Checking,
    val message: String? = null,
    val params: RecordingParams? = null,
    val gate: Gate = Gate.Ok,
    val policy: UploadNetworkPolicy = UploadNetworkPolicy.WifiOnly,
    val network: NetworkState = NetworkState(online = true, unmetered = true),
    val rollDeg: Double? = null,
    val courtMarked: Boolean = false,
    val focusLocked: Boolean = false,
    val still: Bitmap? = null,
    val stillKey: Long = 0,
    val elapsedUs: Long = 0,
    val bytes: Long = 0,
    val upload: SegmentUploadStatus = SegmentUploadStatus(),
    val analysisId: String? = null,
    val endReason: EndReason? = null,
)

/**
 * Record live. Owns the camera session, the rolling recorder, the segment uploader and the local
 * copy. Lives in the activity's store, so the last pieces keep going out and the analysis starts even
 * after the user leaves the screen.
 */
class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val signals = DeviceSignals(app)
    private val camera = CameraSession(app)
    private val baseDir = File(app.filesDir, "recordings")

    private val _ui = MutableStateFlow(RecordUi())
    val ui: StateFlow<RecordUi> = _ui.asStateFlow()

    private var caps: DeviceCaptureCaps? = null
    private var setup: SetupResult? = null
    private var ticker: Job? = null
    private var recorder: SegmentedRecorder? = null
    private var uploader: SegmentUploader? = null
    private var ticket: SegmentSessionTicket? = null
    private var sessionKey: String = ""
    private var startedAnalysis: Pair<String, String>? = null // analysisId to state
    private val pieces = ArrayList<RecordedSegment>()
    private var piecesDir: File? = null
    private var recordingStartedAt = 0L
    private val network = signals.watchNetwork()

    init {
        _ui.update { it.copy(network = network.first.value) }
        viewModelScope.launch {
            network.first.collect { n ->
                _ui.update { it.copy(network = n) }
                val up = uploader
                if (up != null && networkAllowed() && up.status.value.failure?.retryable == true) up.resume()
            }
        }
    }

    private fun networkAllowed() = UploadNetwork.allowed(_ui.value.policy, _ui.value.network)

    // ---- before recording ----

    /** Call once the camera and microphone permissions are granted. */
    fun prepare() {
        if (_ui.value.phase != RecordPhase.Checking && _ui.value.phase != RecordPhase.Unavailable) return
        if (!UploadRuntime.isConfigured) {
            _ui.update { it.copy(phase = RecordPhase.Unavailable, message = "Upload isn't available in this build, so there is nowhere to send a recording.") }
            return
        }
        viewModelScope.launch {
            try {
                val c = camera.probe()
                caps = c
                val params = RecordingParamsChooser.choose(c)
                _ui.update { it.copy(phase = RecordPhase.Framing, params = params, message = null) }
                refreshGate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "camera probe failed", e)
                _ui.update { it.copy(phase = RecordPhase.Unavailable, message = "This phone has no usable back camera.") }
            }
        }
    }

    /** Binds the preview (call again after the view is recreated). */
    fun bind(owner: LifecycleOwner, view: PreviewView, displayRotation: Int) {
        val params = _ui.value.params ?: return
        if (_ui.value.phase == RecordPhase.Recording) return
        viewModelScope.launch {
            try {
                camera.bind(owner, view, params, displayRotation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "camera bind failed", e)
                _ui.update { it.copy(phase = RecordPhase.Unavailable, message = "The camera could not be opened.") }
            }
        }
    }

    fun onRoll(deg: Double?) = _ui.update { it.copy(rollDeg = deg) }

    fun setPolicy(p: UploadNetworkPolicy) {
        _ui.update { it.copy(policy = p) }
        val up = uploader
        if (up != null && networkAllowed() && up.status.value.failure?.retryable == true) up.resume()
    }

    fun refreshGate() {
        val params = _ui.value.params ?: return
        val snap = signals.snapshot(baseDir)
        _ui.update { it.copy(gate = RecordGates.beforeStart(snap, params.estimatedBytes(params.maxDurationS))) }
    }

    /**
     * Holds focus and exposure still, then captures the picture the court is marked on. The still is
     * scaled to exactly the recording's size, so the corner taps are in recorded-video pixels.
     */
    fun captureStill(view: PreviewView) {
        val params = _ui.value.params ?: return
        camera.lockExposureAndFocus(view) { ok ->
            _ui.update { it.copy(focusLocked = true, message = if (ok) null else "Focus could not be confirmed. Check the picture is sharp.") }
            val bmp = view.bitmap
            if (bmp == null) {
                _ui.update { it.copy(message = "The camera picture is not ready yet. Try again.") }
                return@lockExposureAndFocus
            }
            val scaled = Bitmap.createScaledBitmap(bmp, params.quality.width, params.quality.height, true)
            _ui.update { it.copy(phase = RecordPhase.Setup, still = scaled, stillKey = System.nanoTime(), courtMarked = false) }
        }
    }

    fun onSetupDone(result: SetupResult) {
        setup = result
        _ui.update { it.copy(phase = RecordPhase.Ready, courtMarked = true, message = null) }
    }

    fun onSetupCancelled() {
        _ui.update { it.copy(phase = RecordPhase.Framing, still = null) }
    }

    // ---- recording ----

    fun startRecording(owner: LifecycleOwner) {
        val params = _ui.value.params ?: return
        val result = setup ?: return
        val capture = camera.videoCapture ?: return
        if (_ui.value.phase != RecordPhase.Ready) return
        val snap = signals.snapshot(baseDir)
        val gate = RecordGates.beforeStart(snap, params.estimatedBytes(params.maxDurationS))
        if (gate is Gate.Block) {
            _ui.update { it.copy(gate = gate) }
            return
        }
        _ui.update { it.copy(phase = RecordPhase.Finishing, message = "Starting…") } // blocks a double tap
        viewModelScope.launch {
            try {
                val t = createSession(params, result)
                ticket = t
                recordingStartedAt = System.currentTimeMillis()
                val dir = File(baseDir, "pieces_$recordingStartedAt")
                piecesDir = dir
                pieces.clear()
                startedAnalysis = null
                val analysis = HttpAnalysisClient(BuildConfig.API_BASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, tokenProvider = { UploadRuntime.get(getApplication()).auth.accessToken() })
                val up = SegmentUploader(
                    api = SegmentClient(http()),
                    putter = HttpSegmentPutter(UrlConnectionTransport(readTimeoutMs = 120_000)),
                    sessionId = t.sessionId,
                    maxConcurrency = t.segments.maxConcurrency,
                    scope = viewModelScope,
                    networkAllowed = { networkAllowed() },
                    startAnalysis = {
                        val s = analysis.startAnalysis(t.sessionId, "analyse-live-$sessionKey")
                        startedAnalysis = s.response.analysisId to s.response.state
                        _ui.update { it.copy(analysisId = s.response.analysisId) }
                    },
                )
                uploader = up
                up.start()
                viewModelScope.launch { up.status.collect { s -> _ui.update { it.copy(upload = s) }; afterStatus(s) } }
                val rec = SegmentedRecorder(
                    context = getApplication(),
                    videoCapture = capture,
                    dir = dir,
                    params = params,
                    maxSegmentBytes = t.segments.maxSegmentBytes,
                    onPiece = { piece -> synchronized(pieces) { pieces += piece }; up.onSegmentRecorded(piece) },
                    onEnded = { reason -> onRecorderEnded(reason) },
                    onProgress = { us, bytes -> _ui.update { it.copy(elapsedUs = us, bytes = bytes) } },
                )
                recorder = rec
                _ui.update { it.copy(phase = RecordPhase.Recording, message = null, elapsedUs = 0, bytes = 0, endReason = null, analysisId = null) }
                rec.start()
                startTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not start recording", e)
                _ui.update { it.copy(phase = RecordPhase.Ready, message = "Could not start: check your connection and try again.") }
            }
        }
    }

    fun stopRecording() {
        recorder?.stop(EndReason.User)
        _ui.update { if (it.phase == RecordPhase.Recording) it.copy(phase = RecordPhase.Finishing, message = "Saving your recording…") else it }
    }

    fun retryUpload() {
        uploader?.resume()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val snap = signals.snapshot(baseDir)
                when (val g = RecordGates.whileRecording(snap)) {
                    is Gate.Stop -> {
                        _ui.update { it.copy(gate = g, message = g.message) }
                        recorder?.stop(EndReason.Gate)
                        return@launch
                    }
                    else -> _ui.update { it.copy(gate = g) }
                }
            }
        }
    }

    private fun http(): ElevateHttp = ElevateHttp(
        BuildConfig.API_BASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, UploadRuntime.get(getApplication()).auth, UrlConnectionTransport(),
        // One layer of retries: SegmentUploader owns them (G11).
        maxAttempts = 1,
    )

    /**
     * The session is created before the first frame because the analysis needs the court corners the
     * moment piece 0 is stored. `source` is therefore the recording PLAN (size, rate and codec we asked
     * the camera for, the length and bytes it may use at most); the true per-piece times and sha256 go
     * on each segment. The session-level sha256 is a fingerprint of the plan, not of a file.
     */
    private suspend fun createSession(params: RecordingParams, r: SetupResult): SegmentSessionTicket {
        sessionKey = UUID.randomUUID().toString()
        val j = r.toJson()
        fun str(k: String) = (j.getValue(k) as JsonPrimitive).content
        val maxBytes = params.estimatedBytes(params.maxDurationS).coerceIn(1, RecordingParamsChooser.VIDEO_BYTE_CAP)
        val interval = 1_000_000L / params.fps
        val source = SessionSource(
            width = params.quality.width,
            height = params.quality.height,
            rotation = 0,
            fpsNominal = params.fps.toDouble(),
            durationMs = params.maxDurationS * 1000,
            pts = SourcePts(medianIntervalUs = interval, minUs = interval, maxUs = interval, gaps = 0, vfr = false),
            codec = "avc1",
            hasAudio = true,
            audioRateHz = AUDIO_RATE_HZ,
            device = Build.MODEL,
            bytes = maxBytes,
            sha256 = planFingerprint(sessionKey, params),
        )
        val request = SegmentSessionRequest(
            sport = str("sport"),
            format = str("format"),
            ruleset = Ruleset(r.ruleset.id, r.ruleset.version),
            mode = str("mode"),
            source = source,
            calibrationInput = j.getValue("calibration_input"),
            whoIsMe = j.getValue("who_is_me"),
            freePlaySeed = j.getValue("free_play_seed"),
            rallyCandidates = JsonArray(emptyList()),
        )
        return withContext(Dispatchers.IO) { SegmentClient(http()).createSession(request, "record-$sessionKey") }
    }

    private fun planFingerprint(key: String, p: RecordingParams): String =
        MessageDigest.getInstance("SHA-256")
            .digest("elevate-live:$key:${p.quality.width}x${p.quality.height}@${p.fps}:${p.videoBitrateBps}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- stopping ----

    /** Worker thread: the camera is done and every piece has been handed to the uploader. */
    private fun onRecorderEnded(reason: EndReason) {
        ticker?.cancel()
        uploader?.onStopped()
        _ui.update { it.copy(endReason = reason, phase = RecordPhase.Finishing, message = endMessage(reason, it.message)) }
        viewModelScope.launch(Dispatchers.IO) { makeLocalCopy() }
    }

    private fun endMessage(reason: EndReason, current: String?): String? = when (reason) {
        EndReason.User -> "Saving your recording…"
        EndReason.Budget, EndReason.MaxDuration -> "Reached the 300 MB limit, so the recording was stopped and saved."
        EndReason.Storage -> "The phone ran out of space, so the recording was stopped and saved."
        EndReason.SourceLost -> "The camera was interrupted, so the recording was stopped and saved."
        EndReason.Gate -> current
        EndReason.Error -> "The camera stopped unexpectedly. What was recorded is saved."
    }

    private val localCopy = MutableStateFlow<File?>(null)

    /** Joins the pieces into one file (no re-encoding), the copy replay overlays are drawn on. */
    private suspend fun makeLocalCopy() {
        val list = synchronized(pieces) { pieces.toList() }
        if (list.isEmpty()) {
            _ui.update { it.copy(phase = RecordPhase.Failed, message = "Nothing was recorded.") }
            return
        }
        val out = File(baseDir, "rec_$recordingStartedAt.mp4")
        try {
            PieceFiles.join(list, out)
            localCopy.value = out
            maybeFinish()
        } catch (e: Exception) {
            Log.w(TAG, "local copy failed", e)
            _ui.update { it.copy(phase = RecordPhase.Failed, message = "The recording could not be saved on this phone.") }
        }
    }

    private fun afterStatus(s: SegmentUploadStatus) {
        if (s.analysisStarted) viewModelScope.launch(Dispatchers.IO) { maybeFinish() }
        if (s.finished) piecesDir?.let { dir -> viewModelScope.launch(Dispatchers.IO) { runCatching { dir.deleteRecursively() } } }
    }

    private val finishLock = Any()
    private var finished = false

    /** Done once the local copy exists and the analysis has started: records the match and hands over. */
    private suspend fun maybeFinish() {
        val copy = localCopy.value ?: return
        val (analysisId, state) = startedAnalysis ?: return
        val t = ticket ?: return
        val list = synchronized(pieces) { pieces.toList() }
        synchronized(finishLock) { if (finished) return; finished = true }
        val duration = list.last().ptsEndUs / 1000
        val params = _ui.value.params
        val sha = PieceFiles.sha256(copy)
        runCatching {
            val rt = MatchHistoryRuntime.get(getApplication())
            rt.recorder.onStarted(
                analysisId, t.sessionId, t.matchId, sha, "Recorded match",
                android.net.Uri.fromFile(copy).toString(), null,
                (setup?.toJson()?.get("sport") as? JsonPrimitive)?.content, duration,
                params?.quality?.width, params?.quality?.height, state, copy.path,
            )
        }.onFailure { Log.w(TAG, "history entry failed", it) }
        _ui.update { it.copy(phase = RecordPhase.Done, analysisId = analysisId, message = null) }
    }

    /** After the Analysing screen took over: ready for another recording. */
    fun reset() {
        recorder = null
        uploader = null
        ticket = null
        setup = null
        startedAnalysis = null
        localCopy.value = null
        synchronized(finishLock) { finished = false }
        synchronized(pieces) { pieces.clear() }
        _ui.update { RecordUi(phase = if (it.params != null) RecordPhase.Framing else RecordPhase.Checking, params = it.params, policy = it.policy, network = it.network) }
        refreshGate()
    }

    override fun onCleared() {
        ticker?.cancel()
        runCatching { network.second.close() }
        camera.unbind()
    }

    companion object {
        private const val TAG = "RecordViewModel"
        private const val TICK_MS = 2_000L
        /** Sample rate declared for the microphone track (CameraX picks the device's own; see RECORDING.md). */
        private const val AUDIO_RATE_HZ = 48_000
    }
}
