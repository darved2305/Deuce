package com.elevate.app.ui.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elevate.app.data.record.Gate
import com.elevate.app.data.record.Level
import com.elevate.app.data.record.UploadNetworkPolicy
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.setup.SetupFlowScreen
import com.elevate.app.ui.theme.Elevate

private val REQUIRED = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/**
 * Record a match with the phone's camera: framing guide, court marking on a still, then rolling
 * pieces that upload while recording. [onOpenAnalysis] is called once the analysis has started and
 * the local copy is saved; the caller then shows the Analysing screen for that analysis.
 */
@Composable
fun RecordRoute(vm: RecordViewModel, onClose: () -> Unit, onOpenAnalysis: (String) -> Unit) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()

    // Landscape, locked, only on this screen: the picture is recorded wide and the guide is drawn wide.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (activity != null && previous != null) activity.requestedOrientation = previous }
    }

    var granted by remember { mutableStateOf(REQUIRED.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = REQUIRED.all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    LaunchedEffect(granted) { if (granted) vm.prepare() }
    LaunchedEffect(ui.phase, ui.analysisId) {
        if (ui.phase == RecordPhase.Done) ui.analysisId?.let { id -> onOpenAnalysis(id); vm.reset() }
    }

    val recording = ui.phase == RecordPhase.Recording || ui.phase == RecordPhase.Finishing
    BackHandler(enabled = ui.phase != RecordPhase.Setup) {
        // A running recording is only ended by Stop, never by a stray back gesture.
        if (!recording) onClose()
    }

    PeachPage {
        if (!granted) {
            PermissionCard(onAsk = { ask.launch(REQUIRED) }, onClose = onClose)
            return@PeachPage
        }
        RecordContent(vm, ui, onClose)
    }
}

@Composable
private fun RecordContent(vm: RecordViewModel, ui: RecordUi, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    // The level: the accelerometer needs no permission.
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothed: Double? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val roll = Level.rollDegrees(e.values[0], e.values[1], e.values[2])
                smoothed = if (roll == null) null else Level.smooth(smoothed, roll)
                vm.onRoll(smoothed)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    LaunchedEffect(ui.params, ui.phase == RecordPhase.Unavailable) {
        if (ui.params != null) vm.bind(owner, preview, preview.display?.rotation ?: android.view.Surface.ROTATION_90)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        if (ui.phase == RecordPhase.Framing || ui.phase == RecordPhase.Ready) FramingOverlay(ui.rollDeg)

        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.width(300.dp).fillMaxHeight().padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) { Controls(vm, ui, preview, owner, onClose) }
        }

        // The setup screen sits over the preview (the camera stays bound underneath).
        val still = ui.still
        val params = ui.params
        if (ui.phase == RecordPhase.Setup && still != null && params != null) {
            SetupFlowScreen(
                bitmap = still, srcW = params.quality.width, srcH = params.quality.height, stillKey = ui.stillKey,
                onCancel = vm::onSetupCancelled, onDone = vm::onSetupDone,
            )
        }
    }
}

@Composable
private fun Controls(vm: RecordViewModel, ui: RecordUi, preview: PreviewView, owner: androidx.lifecycle.LifecycleOwner, onClose: () -> Unit) {
    val c = Elevate.colors
    ElevateCard {
        when (ui.phase) {
            RecordPhase.Checking -> Text("Opening the camera…", style = MaterialTheme.typography.bodyLarge, color = c.ink)
            RecordPhase.Unavailable, RecordPhase.Failed -> {
                Text(ui.message ?: "Recording is not available.", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Close", onClose, Modifier.fillMaxWidth())
            }
            RecordPhase.Framing, RecordPhase.Setup -> Framing(vm, ui, preview, onClose)
            RecordPhase.Ready -> Ready(vm, ui, owner, preview)
            RecordPhase.Recording, RecordPhase.Finishing -> Recording(vm, ui)
            RecordPhase.Done -> Text("Starting the analysis…", style = MaterialTheme.typography.bodyLarge, color = c.ink)
        }
    }
}

@Composable
private fun Framing(vm: RecordViewModel, ui: RecordUi, preview: PreviewView, onClose: () -> Unit) {
    val c = Elevate.colors
    Text("Frame the court", style = MaterialTheme.typography.titleMedium, color = c.ink)
    Text(com.elevate.app.data.record.FramingGuide.INSTRUCTION, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    Spacer(Modifier.height(8.dp))
    val tilt = ui.rollDeg
    Text(
        when {
            tilt == null -> "Hold the phone upright, sideways"
            Level.quality(tilt) == Level.Quality.Level -> "Level"
            else -> "Tilted ${"%.1f".format(kotlin.math.abs(tilt))} degrees"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (tilt != null && Level.quality(tilt) == Level.Quality.Level) c.mintInk else c.ink,
    )
    ui.params?.let { Text(RecordText.paramsLine(it), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
    GateText(ui.gate)
    ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error) }
    Spacer(Modifier.height(8.dp))
    PolicyRow(vm, ui)
    Spacer(Modifier.height(12.dp))
    PrimaryButton("Mark the court", { vm.refreshGate(); vm.captureStill(preview) }, enabled = ui.params != null && ui.gate !is Gate.Block)
    Spacer(Modifier.height(8.dp))
    SecondaryButton("Close", onClose, Modifier.fillMaxWidth())
}

@Composable
private fun Ready(vm: RecordViewModel, ui: RecordUi, owner: androidx.lifecycle.LifecycleOwner, preview: PreviewView) {
    val c = Elevate.colors
    Text("Court marked", style = MaterialTheme.typography.titleMedium, color = c.ink)
    Text("Focus and exposure are held. Do not move the phone.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    ui.params?.let { Text(RecordText.paramsLine(it), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
    GateText(ui.gate)
    ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error) }
    Spacer(Modifier.height(8.dp))
    PolicyRow(vm, ui)
    Spacer(Modifier.height(12.dp))
    PrimaryButton("Record", { vm.startRecording(owner) }, enabled = ui.gate !is Gate.Block)
    Spacer(Modifier.height(8.dp))
    SecondaryButton("Mark the court again", { vm.refreshGate(); vm.captureStill(preview) }, Modifier.fillMaxWidth())
}

@Composable
private fun Recording(vm: RecordViewModel, ui: RecordUi) {
    val c = Elevate.colors
    val finishing = ui.phase == RecordPhase.Finishing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (finishing) c.inkMuted else Color(0xFFE5392B)))
        Spacer(Modifier.width(8.dp))
        Text(if (finishing) "Saving" else "Recording", style = MaterialTheme.typography.titleMedium, color = c.ink)
    }
    Text(RecordText.elapsed(ui.elapsedUs), style = MaterialTheme.typography.headlineMedium, color = c.ink)
    Text(RecordText.sizeLine(ui.bytes), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    Spacer(Modifier.height(8.dp))
    Text(
        RecordText.uploadLine(ui.upload, ui.policy, ui.network),
        style = MaterialTheme.typography.bodyLarge,
        color = c.ink,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    GateText(ui.gate)
    ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
    Spacer(Modifier.height(8.dp))
    if (ui.upload.failure?.retryable == true || ui.upload.analysisError != null) {
        SecondaryButton("Try sending again", vm::retryUpload, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
    if (!finishing) PrimaryButton("Stop", vm::stopRecording)
}

@Composable
private fun GateText(g: Gate) {
    val c = Elevate.colors
    when (g) {
        is Gate.Warn -> Text(g.message, style = MaterialTheme.typography.bodyMedium, color = c.orangeText)
        is Gate.Block -> Text(g.message, style = MaterialTheme.typography.bodyMedium, color = c.error)
        is Gate.Stop -> Text(g.message, style = MaterialTheme.typography.bodyMedium, color = c.error)
        Gate.Ok -> Unit
    }
}

@Composable
private fun PolicyRow(vm: RecordViewModel, ui: RecordUi) {
    val c = Elevate.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Send over mobile data too", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
        Switch(
            checked = ui.policy == UploadNetworkPolicy.WifiOrMobile,
            onCheckedChange = { vm.setPolicy(if (it) UploadNetworkPolicy.WifiOrMobile else UploadNetworkPolicy.WifiOnly) },
        )
    }
}

@Composable
private fun PermissionCard(onAsk: () -> Unit, onClose: () -> Unit) {
    val c = Elevate.colors
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        ElevateCard {
            Text("Camera and microphone", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text(
                "Elevate films the match with your camera. The microphone is needed too: the sound of each shot helps find the hits.",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Allow camera and microphone", onAsk)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Not now", onClose, Modifier.fillMaxWidth())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
