package com.elevate.app.ui.screens

import com.elevate.app.domain.media.PreflightState
import com.elevate.app.domain.media.Severity
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.domain.media.codecLabel
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.playback.PlaybackUiState
import com.elevate.app.playback.PlayerController
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.theme.Elevate
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun ReplayScreen(
    screen: Screen.Replay,
    state: PlaybackUiState,
    controller: PlayerController,
    onBack: () -> Unit,
    onChooseAnother: () -> Unit,
    onOverlayVisible: (Boolean) -> Unit,
    onSetUp: () -> Unit = {},
) {
    val c = Elevate.colors
    BackHandler(onBack = onBack)
    // Leaving the app pauses playback; the player and its surface come back on return.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }

    // UI clock for the scrubber text only. The trail never reads this: it is keyed to frame PTS.
    var positionMs by remember { mutableLongStateOf(0L) }
    val playingNow by rememberUpdatedState(state.isPlaying)
    LaunchedEffect(controller) {
        while (true) {
            positionMs = controller.positionMs()
            delay(if (playingNow) 50 else 200)
        }
    }
    val debuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    LaunchedEffect(debuggable) { controller.setPresentationTimeStamp(debuggable) }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.court)) {
        val landscape = maxWidth > maxHeight
        val video: @Composable (Modifier) -> Unit = { m ->
            VideoArea(screen, state, controller, onChooseAnother, m)
        }
        val controls: @Composable (Modifier) -> Unit = { m ->
            ControlsSheet(screen, state, controller, positionMs, onOverlayVisible, onSetUp, m)
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(screen.title, onBack)
                    video(Modifier.weight(1f).fillMaxWidth())
                }
                controls(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(screen.title, onBack)
                video(Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClickLabel = "Back to import", role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(ElevateIcons.Back, contentDescription = "Back", tint = Color.White) }
        Spacer(Modifier.size(12.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@UnstableApi
@Composable
private fun VideoArea(
    screen: Screen.Replay,
    state: PlaybackUiState,
    controller: PlayerController,
    onChooseAnother: () -> Unit,
    modifier: Modifier,
) {
    val c = Elevate.colors
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val player by controller.playerFlow.collectAsState()
        ContentFrame(player = player, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Video: ${screen.title}" })
        if (state.error != null) {
            Column(
                Modifier.padding(24.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This video can't be played", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(state.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onChooseAnother, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = c.orange)) {
                    Text("Choose another video")
                }
            }
        }
        Text(
            "Your video · original frames",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@UnstableApi
@Composable
private fun ControlsSheet(
    screen: Screen.Replay,
    state: PlaybackUiState,
    controller: PlayerController,
    positionMs: Long,
    onOverlayVisible: (Boolean) -> Unit,
    onSetUp: () -> Unit,
    modifier: Modifier,
) {
    val c = Elevate.colors
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shownMs = if (dragging) dragMs.toLong() else positionMs.coerceIn(0, duration)

    Column(
        modifier
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Slider(
            value = shownMs.toFloat(),
            onValueChange = { dragging = true; dragMs = it; controller.seekTo(it.toLong()) },
            onValueChangeFinished = { controller.seekTo(dragMs.toLong()); dragging = false },
            valueRange = 0f..duration.toFloat(),
            enabled = state.durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = c.orange, activeTrackColor = c.orange, inactiveTrackColor = c.line),
            modifier = Modifier.semantics { contentDescription = "Timeline" },
        )
        Text(
            "${formatTime(shownMs)} / ${formatTime(state.durationMs)}",
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(ElevateIcons.Replay, "Replay from start", filled = false) { controller.replay() }
            val playing = state.playWhenReady && !state.ended
            RoundButton(if (playing) ElevateIcons.Pause else ElevateIcons.Play, if (playing) "Pause" else "Play", filled = true) { controller.togglePlay() }
            Spacer(Modifier.weight(1f))
            SpeedSelector(state.speed) { controller.setSpeed(it) }
        }
        Spacer(Modifier.height(16.dp))
        AnnotationRow(screen, onOverlayVisible)
        Spacer(Modifier.height(8.dp))
        PreflightRow(screen.preflight)
        SetupRow(screen, onSetUp)
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val c = Elevate.colors
    Box(
        Modifier
            .size(if (filled) 64.dp else 52.dp)
            .clip(CircleShape)
            .background(if (filled) c.orange else c.cream)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (filled) Color.White else c.ink) }
}

private val SPEEDS = listOf(0.25f to "¼×", 0.5f to "½×", 1f to "1×")

@Composable
private fun SpeedSelector(speed: Float, onSpeed: (Float) -> Unit) {
    val c = Elevate.colors
    Row(
        Modifier.clip(CircleShape).background(c.cream).padding(4.dp).selectableGroup(),
    ) {
        SPEEDS.forEach { (value, label) ->
            val selected = speed == value
            Box(
                Modifier
                    .size(width = 52.dp, height = 44.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSpeed(value) })
                    .semantics { contentDescription = "Speed $value times" },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) c.orange else c.inkMuted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun AnnotationRow(screen: Screen.Replay, onOverlayVisible: (Boolean) -> Unit) {
    val c = Elevate.colors
    val a = screen.annotations
    val (title, detail, bg) = when (a) {
        AnnotationState.Checking -> Triple("Checking for annotations…", "Matching this file's bytes to known recordings", c.cream)
        AnnotationState.NoneForThisVideo -> Triple("No annotations for this video", "Only the original footage is shown", c.cream)
        is AnnotationState.Failed -> Triple("Annotations unavailable", a.reason, c.errorSurface)
        AnnotationState.UnsupportedHdr -> Triple("Trail not available for HDR video yet", "This recording is HDR; it plays without overlays", c.cream)
        is AnnotationState.Available -> {
            val t = a.track
            val who = if (t.provenance.isHandLabelled) "Hand-labelled${t.provenance.labelledBy?.let { " by $it" } ?: ""}" else "Engine ${t.provenance.engineVersion}"
            Triple(
                "$who · shuttle trail",
                annotationDetail(t),
                c.mint,
            )
        }
    }
    val available = a is AnnotationState.Available
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .then(if (available) Modifier.toggleable(value = screen.overlayVisible, role = Role.Switch, onValueChange = onOverlayVisible) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ElevateIcons.Trail, contentDescription = null, tint = if (available) c.mintInk else c.inkMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
            Text(detail, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        if (available) {
            Switch(
                checked = screen.overlayVisible,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = c.orange, checkedThumbColor = Color.White, uncheckedBorderColor = c.line),
            )
        }
    }
}

/** What the file itself says: measured facts, then any finding. Nothing here is a model output. */
@Composable
private fun PreflightRow(p: PreflightState) {
    val c = Elevate.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val (title, detail) = when (p) {
        PreflightState.Checking -> "Checking the clip…" to "Reading its tracks and frame timing"
        is PreflightState.Failed -> "Clip check failed" to p.reason
        is PreflightState.Done -> {
            val n = p.findings.size
            (if (p.blocked) "Can't be analysed" else if (n == 0) "Clip check · no issues" else "Clip check · $n to note") to
                preflightSummary(p.facts)
        }
    }
    val findings = (p as? PreflightState.Done)?.findings.orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (p is PreflightState.Done && p.blocked || p is PreflightState.Failed) c.errorSurface else c.cream)
            .then(if (findings.isNotEmpty()) Modifier.toggleable(value = open, role = Role.Button, onValueChange = { open = it }) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
        Text(detail, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        if (open) findings.forEach { f ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val dot = when (f.severity) { Severity.Block -> c.error; Severity.Warn -> c.orange; Severity.Info -> c.lavenderInk }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.size(10.dp))
                Text(f.text, style = MaterialTheme.typography.labelMedium, color = c.ink)
            }
        }
    }
}

/** Entry to the setup flow once the clip can be analysed, then a factual summary of what was chosen. */
@Composable
private fun SetupRow(screen: Screen.Replay, onSetUp: () -> Unit) {
    val c = Elevate.colors
    val p = screen.preflight as? PreflightState.Done ?: return
    if (p.blocked) return
    Spacer(Modifier.height(8.dp))
    val r = screen.setup
    if (r == null) {
        Button(
            onClick = onSetUp,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = c.orange, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Set up analysis", style = MaterialTheme.typography.labelLarge) }
    } else {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.mint)
                .clickable(onClickLabel = "Change setup", role = Role.Button, onClick = onSetUp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Set up · ${r.summary()}", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
            Text("Tap to change", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
    }
}

/** e.g. "Badminton singles · 4 court corners · you at 0:41.20". Only what the user chose. */
private fun com.elevate.app.ui.setup.SetupResult.summary(): String {
    val corners = if (calibrationInput != null) "4 court corners" else "no court"
    return "${sport.label} ${format.label.lowercase()} · $corners · you at ${formatTime(whoIsMe.tMs)}"
}

/** Display size, codec, measured frame spacing, audio: e.g. "1920×1080 · H.264 · 20.0 ms frames (50 fps) · audio 44.1 kHz". */
internal fun preflightSummary(f: SourceFacts): String {
    if (f.mime == null) return "No video track in this file"
    // Measured from the timestamps, never the container's stated rate.
    val timing = f.pts?.let { p ->
        val fps = p.fpsMeasured?.let { if (it % 1.0 == 0.0) "${it.toInt()} fps" else String.format(Locale.ROOT, "%.2f fps", it) }
        String.format(Locale.ROOT, "%.1f ms frames", p.meanIntervalUs / 1000.0) + (fps?.let { " ($it measured)" } ?: "")
    }
    val audio = if (f.hasAudio) f.audioRateHz?.let { String.format(Locale.ROOT, "audio %.1f kHz", it / 1000.0) } ?: "audio" else "no audio"
    return listOfNotNull("${f.displayWidth}×${f.displayHeight}", codecLabel(f.codec), timing, audio).joinToString(" · ")
}

/** Every count shows its real denominator (D-007): labelled frames, then what was never labelled. */
internal fun annotationDetail(t: com.elevate.app.domain.model.TrailTrack): String {
    val range = "${"%.2f".format(t.rangeStartUs / 1e6)}–${"%.2f".format(t.rangeEndUs / 1e6)} s"
    val seen = "shuttle seen in ${t.observedFrames} of ${t.labelledFrames} labelled frames"
    val unlabelled = t.framesInRange - t.labelledFrames
    return if (unlabelled > 0) "$range · $seen · $unlabelled of ${t.framesInRange} not labelled" else "$range · $seen"
}

internal fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    val m = total / 60_000
    val s = (total % 60_000) / 1000
    val cs = (total % 1000) / 10
    return "%d:%02d.%02d".format(m, s, cs)
}
