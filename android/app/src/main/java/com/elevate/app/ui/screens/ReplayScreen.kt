package com.elevate.app.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.domain.media.PreflightState
import com.elevate.app.domain.media.Severity
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.domain.media.codecLabel
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.playback.PlaybackUiState
import com.elevate.app.playback.PlayerController
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.Explanation
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.MiniCourt
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.VideoChip
import com.elevate.app.ui.theme.Elevate
import com.elevate.app.ui.upload.UploadProgressCard
import java.util.Locale
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
    onShowEngine: (Boolean) -> Unit = {},
    onShowLive: (Boolean) -> Unit = {},
    /** Live hit candidates (ms) for this file, shown as ticks under the timeline. */
    hitsMs: List<Long> = emptyList(),
    pipeline: PipelineUi = PipelineUi(),
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
    // The frame time stamp is a developer aid: never drawn for people.
    LaunchedEffect(Unit) { controller.setPresentationTimeStamp(com.elevate.app.playback.TrailOverlay.SHOW_PTS) }

    val title = displayTitle(screen.title)
    val facts = (screen.preflight as? PreflightState.Done)?.facts
    val subtitle = videoSubtitle(
        width = facts?.displayWidth ?: screen.info.width,
        height = facts?.displayHeight ?: screen.info.height,
        fps = facts?.let { it.pts?.fpsMeasured ?: it.containerFps?.toDouble() },
        durationMs = facts?.durationMs ?: screen.info.durationMs,
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(c.court)) {
        val landscape = maxWidth > maxHeight
        val sheetMax = maxHeight * 0.55f
        val video: @Composable (Modifier) -> Unit = { m ->
            VideoArea(title, state, controller, onChooseAnother, m)
        }
        val controls: @Composable (Modifier) -> Unit = { m ->
            ControlsSheet(screen, state, controller, positionMs, onOverlayVisible, onSetUp, onShowEngine, onShowLive, pipeline, m, hitsMs)
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ElevateHeader(title, onBack = onBack, subtitle = subtitle, dark = true)
                    video(Modifier.weight(1f).fillMaxWidth())
                }
                controls(Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                ElevateHeader(title, onBack = onBack, subtitle = subtitle, dark = true)
                video(Modifier.weight(1f).fillMaxWidth())
                // The video stays the hero: the sheet takes at most 55 % of the height and scrolls inside it.
                controls(Modifier.fillMaxWidth().heightIn(max = sheetMax))
            }
        }
    }
}

@UnstableApi
@Composable
private fun VideoArea(
    title: String,
    state: PlaybackUiState,
    controller: PlayerController,
    onChooseAnother: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val player by controller.playerFlow.collectAsState()
        ContentFrame(player = player, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Video: $title" })
        if (state.error != null) {
            ElevateCard(Modifier.padding(24.dp)) {
                Text("This video can't be played", style = MaterialTheme.typography.titleMedium, color = Elevate.colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(state.error, style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Choose another video", onClick = onChooseAnother)
            }
        }
        VideoChip("Your video · original frames", Modifier.align(Alignment.BottomStart).padding(12.dp), icon = ElevateIcons.Film)
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
    onShowEngine: (Boolean) -> Unit,
    onShowLive: (Boolean) -> Unit,
    pipeline: PipelineUi,
    modifier: Modifier,
    hitsMs: List<Long> = emptyList(),
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
            .navigationBarsPadding(),
    ) {
    // The scrolling part of the sheet; the primary action stays pinned below it.
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
        if (hitsMs.isNotEmpty()) {
            ExplainBox(Explanations.Hit, Modifier.fillMaxWidth()) {
                com.elevate.app.ui.live.HitStrip(durationMs = state.durationMs, hitsMs = hitsMs, positionMs = shownMs, onSeek = { controller.seekTo(it) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tnum = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", fontSize = 16.sp)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(formatTime(shownMs), style = tnum, color = c.ink, maxLines = 1)
                Text(
                    "/ ${formatTime(state.durationMs)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = c.inkMuted,
                    maxLines = 1,
                )
            }
            val playing = state.playWhenReady && !state.ended
            PlayButton(
                icon = when { state.ended -> ElevateIcons.Replay; playing -> ElevateIcons.Pause; else -> ElevateIcons.Play },
                label = when { state.ended -> "Replay from start"; playing -> "Pause"; else -> "Play" },
            ) { controller.togglePlay() }
            ExplainBox(Explanations.Speed) { SpeedSelector(state.speed) { controller.setSpeed(it) } }
        }
        if (screen.live == null) AnnotationRow(screen, onOverlayVisible)
        LiveRow(screen.live, onShowLive)
        EngineRow(screen.engine, onShowEngine)
        CourtToggleRow(screen)
        PreflightRow(screen.preflight)
        SetupRow(screen, onSetUp)
        PipelineRow(screen, pipeline)
        // The big top-down court is the only tall item: last, so the common actions above never need scrolling.
        CourtMap(screen)
        Spacer(Modifier.height(4.dp))
    }
    if (!pipeline.analysedHere) {
        Box(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp)) { AnalyseButton(screen, onSetUp) }
    }
    }
}

@Composable
private fun PlayButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Elevate.colors
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(c.orange)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White) }
}

private val SPEEDS = listOf(0.25f to "¼×", 0.5f to "½×", 1f to "1×")

@Composable
private fun SpeedSelector(speed: Float, onSpeed: (Float) -> Unit) {
    val c = Elevate.colors
    Row(Modifier.clip(CircleShape).background(c.cream).padding(4.dp).selectableGroup()) {
        SPEEDS.forEach { (value, label) ->
            val selected = speed == value
            Box(
                Modifier
                    .size(width = 46.dp, height = 44.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSpeed(value) })
                    .semantics { contentDescription = "Speed $value times" },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) c.orangeText else c.inkMuted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * One compact single-line card: icon tile · title · short value (a real count with its denominator) ·
 * optional switch. Tapping the card expands its [detail]; the switch toggles on its own.
 */
@Composable
private fun CompactRow(
    icon: ImageVector,
    title: String,
    bg: Color,
    iconTint: Color,
    value: String? = null,
    valueDescription: String? = null,
    toggle: Pair<Boolean, (Boolean) -> Unit>? = null,
    toggleLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    explain: Explanation? = null,
    detail: (@Composable () -> Unit)? = null,
) {
    if (explain == null) {
        CompactRowBody(icon, title, bg, iconTint, value, valueDescription, toggle, toggleLabel, onClick, onClickLabel, detail)
    } else {
        ExplainBox(explain, Modifier.fillMaxWidth()) {
            CompactRowBody(icon, title, bg, iconTint, value, valueDescription, toggle, toggleLabel, onClick, onClickLabel, detail)
        }
    }
}

@Composable
private fun CompactRowBody(
    icon: ImageVector,
    title: String,
    bg: Color,
    iconTint: Color,
    value: String? = null,
    valueDescription: String? = null,
    toggle: Pair<Boolean, (Boolean) -> Unit>? = null,
    toggleLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    detail: (@Composable () -> Unit)? = null,
) {
    val c = Elevate.colors
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val tap: (() -> Unit)? = onClick ?: detail?.let { { open = !open } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .then(
                if (tap != null) {
                    Modifier.clickable(onClickLabel = onClickLabel ?: if (open) "Hide details" else "Show details", role = Role.Button, onClick = tap)
                } else Modifier,
            )
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.size(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = c.inkMuted,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .then(if (valueDescription != null) Modifier.semantics { contentDescription = valueDescription } else Modifier),
                )
            }
            if (detail != null && onClick == null) {
                Icon(
                    ElevateIcons.ChevronDown,
                    contentDescription = null,
                    tint = c.inkMuted,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp).rotate(if (open) 180f else 0f),
                )
            }
            if (toggle != null) {
                Spacer(Modifier.size(8.dp))
                Switch(
                    checked = toggle.first,
                    onCheckedChange = toggle.second,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.orange, checkedThumbColor = Color.White, uncheckedBorderColor = c.line),
                    modifier = Modifier.semantics {
                        if (toggleLabel != null) contentDescription = toggleLabel
                        stateDescription = if (toggle.first) "Shown" else "Hidden"
                    },
                )
            }
        }
        if (open && detail != null) {
            Column(Modifier.padding(start = 44.dp, top = 4.dp, bottom = 4.dp)) { detail() }
        }
    }
}

@Composable
private fun DetailText(text: String, color: Color = Elevate.colors.inkMuted) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun AnnotationRow(screen: Screen.Replay, onOverlayVisible: (Boolean) -> Unit) {
    val c = Elevate.colors
    when (val a = screen.annotations) {
        AnnotationState.Checking -> CompactRow(ElevateIcons.Trail, "Reading the video…", c.cream, c.inkMuted)
        AnnotationState.NoneForThisVideo -> CompactRow(ElevateIcons.Trail, "Not analysed yet", c.cream, c.inkMuted) {
            DetailText("Set up the court and analyse: the shuttle trail, hits and court positions are drawn on this video as each part finishes.")
        }
        is AnnotationState.Failed -> CompactRow(ElevateIcons.Trail, "Trail unavailable", c.errorSurface, c.error) { DetailText(a.reason, c.ink) }
        AnnotationState.UnsupportedHdr -> CompactRow(ElevateIcons.Trail, "No trail on HDR video yet", c.cream, c.inkMuted) {
            DetailText("This recording is HDR; it plays without overlays.")
        }
        is AnnotationState.Available -> {
            val t = a.track
            val s = trailSummary(t)
            CompactRow(
                icon = ElevateIcons.Trail,
                title = s.title,
                bg = c.mint,
                iconTint = c.mintInk,
                value = s.value,
                valueDescription = s.spoken,
                toggle = screen.overlayVisible to onOverlayVisible,
                toggleLabel = "Shuttle trail",
                explain = Explanations.TrailToggle,
            ) {
                t.provenance.labelledBy?.let { DetailText("Labelled by $it") }
                DetailText(annotationDetail(t))
            }
        }
    }
}

/**
 * Person 1's recorded TrackNetV3 run on this exact clip: real model output, shown as candidates
 * (cyan hollow rings, dashed joins) and labelled as a recorded run, never as analysis of an upload.
 */
@Composable
private fun EngineRow(offer: Screen.EngineOffer?, onShowEngine: (Boolean) -> Unit) {
    offer ?: return
    val c = Elevate.colors
    val t = offer.trail.track
    val range = "${"%.2f".format(Locale.ROOT, t.rangeStartUs / 1e6)}–${"%.2f".format(Locale.ROOT, t.rangeEndUs / 1e6)} s"
    CompactRow(
        icon = ElevateIcons.Bolt,
        title = "Sample analysis",
        bg = c.lavender,
        iconTint = c.lavenderInk,
        value = "${offer.trail.framesWithCandidate}/${t.framesInRange}",
        valueDescription = "a candidate on ${offer.trail.framesWithCandidate} of ${t.framesInRange} frames",
        toggle = offer.shown to onShowEngine,
        toggleLabel = "Engine candidates",
        explain = Explanations.Estimate,
    ) {
        DetailText("$range · a candidate on ${offer.trail.framesWithCandidate} of ${t.framesInRange} frames")
        DetailText("A recorded run of the engine on this clip, not your upload")
    }
}

/** This file's own analysis, drawn as its windows arrive from the server. */
@Composable
private fun LiveRow(live: Screen.LiveOffer?, onShowLive: (Boolean) -> Unit) {
    live ?: return
    val c = Elevate.colors
    val t = live.track
    val range = "${"%.1f".format(Locale.ROOT, t.rangeStartUs / 1e6)}–${"%.1f".format(Locale.ROOT, t.rangeEndUs / 1e6)} s"
    val text = liveRowText(t.provenance.state == "candidate", live.framesWithBall, t.framesInRange)
    CompactRow(
        icon = ElevateIcons.Bolt,
        title = text.title,
        bg = c.mint,
        iconTint = c.mintInk,
        value = "${live.framesWithBall} of ${t.framesInRange} frames tracked",
        valueDescription = text.spoken,
        toggle = live.shown to onShowLive,
        toggleLabel = "Live engine trail",
        explain = Explanations.LiveTrail,
    ) {
        DetailText("$range so far")
        DetailText(text.detail)
    }
}

/**
 * Live row wording. Candidate rows are not observations (D-007): they read "a candidate on N of M
 * frames", never "shuttle on", so a trail of unconfirmed candidates does not claim the shuttle was seen.
 */
internal data class LiveRowText(val title: String, val spoken: String, val detail: String)

internal fun liveRowText(candidates: Boolean, frames: Int, framesInRange: Int): LiveRowText {
    val count = if (candidates) "a candidate on $frames of $framesInRange frames" else "shuttle on $frames of $framesInRange frames"
    return LiveRowText(
        title = "Shuttle trail",
        spoken = count,
        detail = "From your upload · more appears as the analysis runs",
    )
}

/** What the file itself says: measured facts, then any finding. Nothing here is a model output. */
@Composable
private fun PreflightRow(p: PreflightState) {
    val c = Elevate.colors
    when (p) {
        PreflightState.Checking -> CompactRow(ElevateIcons.Check, "Checking the clip…", c.cream, c.inkMuted)
        is PreflightState.Failed -> CompactRow(ElevateIcons.Check, "Clip check failed", c.errorSurface, c.error) { DetailText(p.reason, c.ink) }
        is PreflightState.Done -> CompactRow(
            icon = ElevateIcons.Check,
            title = preflightTitle(p.blocked, p.findings.size),
            bg = if (p.blocked) c.errorSurface else c.cream,
            iconTint = if (p.blocked) c.error else if (p.findings.isEmpty()) c.mintInk else c.orange,
        ) {
            DetailText(preflightSummary(p.facts))
            p.findings.forEach { f ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val dot = when (f.severity) { Severity.Block -> c.error; Severity.Warn -> c.orange; Severity.Info -> c.lavenderInk }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.size(10.dp))
                    DetailText(f.text, c.ink)
                }
            }
        }
    }
}

/**
 * "See the whole court": a Court toggle for the lines drawn on the footage ([CourtToggleRow]), then
 * the top-down card ([CourtMap]). Both exist only when setup's four corner taps produced a
 * homography ([screen].court); on HDR the effect pipeline draws nothing, so the card stays but the
 * toggle is not offered rather than switching something that cannot draw.
 *
 * The toggle reaches the same activity-scoped [ReplayViewModel] the screen runs on - the call
 * site's parameters are fixed, and this matches how SetupFlowScreen gets its own ViewModel.
 */
@UnstableApi
@Composable
private fun CourtToggleRow(screen: Screen.Replay) {
    if (screen.court == null) return
    val c = Elevate.colors
    if (!screen.info.isHdr) {
        val vm: ReplayViewModel = viewModel()
        ExplainBox(Explanations.CourtToggle, Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.cream)
                .heightIn(min = 48.dp)
                .toggleable(value = screen.courtVisible, role = Role.Switch, onValueChange = vm::setCourtVisible)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Court lines", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
                Text(if (screen.setup != null) "Every court line, from your corner taps" else "Every court line, from the match's court calibration", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
            Switch(
                checked = screen.courtVisible,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = c.orange, checkedThumbColor = Color.White, uncheckedBorderColor = c.line),
            )
        }
        }
    }
}

@UnstableApi
@Composable
private fun CourtMap(screen: Screen.Replay) {
    val cal = screen.court ?: return
    val vm: ReplayViewModel = viewModel()
    val landings by vm.courtLandings.collectAsStateWithLifecycle()
    MiniCourt(cal, landings = landings.map { Triple(it.xM, it.yM, it.state) })
}

/** What the replay screen needs to drive upload → analysis (state lives in the ViewModel). */
class PipelineUi(
    val state: Screen.Pipeline = Screen.Pipeline.Idle,
    val upload: UploadProgress = UploadProgress.Idle,
    val onAnalyse: () -> Unit = {},
    val onRetryUpload: () -> Unit = {},
    val onCancelUpload: () -> Unit = {},
    val onOpenAnalysis: () -> Unit = {},
    /** One-line live status ("Sending · 12.0/60.7 MiB", "Analysing · 18/485 s"); null when idle. */
    val pill: String? = null,
    /** The running/finished analysis is of the open file: no second "Analyse" offer. */
    val analysedHere: Boolean = false,
)

/** After setup: start the real upload + analysis, then show real progress and the server's answers. */
@Composable
private fun PipelineRow(screen: Screen.Replay, ui: PipelineUi) {
    if (screen.setup == null && ui.state == Screen.Pipeline.Idle) return
    // A previous video's analysis must not show on this one: only the Analyse button then.
    if (ui.state != Screen.Pipeline.Idle && !ui.analysedHere) return
    PipelineStatus(ui, ready = screen.sha256 != null)
}

/** Upload → analysis status: real bytes, then the server's answers. Shared by the import and replay screens. */
@Composable
fun PipelineStatus(ui: PipelineUi, ready: Boolean = true) {
    val c = Elevate.colors
    // While anything runs, the full status lives on the Analysing screen: here, one tappable line.
    val pill = ui.pill
    if (pill != null && ui.state !is Screen.Pipeline.Failed && ui.state != Screen.Pipeline.Idle) {
        ExplainBox(Explanations.InPieces, Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(c.mint)
                    .clickable(onClickLabel = "Open analysis", role = Role.Button, onClick = ui.onOpenAnalysis)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.mintInk))
                Spacer(Modifier.size(12.dp))
                Text(pill, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Open", style = MaterialTheme.typography.labelLarge, color = c.orangeText)
            }
        }
        return
    }
    when (val p = ui.state) {
        Screen.Pipeline.Idle ->
            if (ready) ExplainBox(Explanations.Analyse, Modifier.fillMaxWidth()) { PrimaryButton("Upload & analyse", onClick = ui.onAnalyse, modifier = Modifier.padding(top = 4.dp)) }
            else StatusCard("Preparing the file…", "Preparing your video", c.cream)
        Screen.Pipeline.Uploading -> UploadProgressCard(ui.upload, onRetry = ui.onRetryUpload, onCancel = ui.onCancelUpload)
        is Screen.Pipeline.StartingAnalysis -> StatusCard("Uploaded · starting analysis…", "Waiting for the server", c.cream)
        is Screen.Pipeline.Started -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.mint)
                .clickable(onClickLabel = "Open analysis progress", role = Role.Button, onClick = ui.onOpenAnalysis)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                when (com.elevate.app.domain.analysis.AnalysisState.fromWire(p.state)) {
                    null, com.elevate.app.domain.analysis.AnalysisState.AWAITING_UPLOAD,
                    com.elevate.app.domain.analysis.AnalysisState.UPLOADED, com.elevate.app.domain.analysis.AnalysisState.QUEUED -> "Starting"
                    com.elevate.app.domain.analysis.AnalysisState.COMPLETED -> "Ready"
                    else -> "Analysing"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = c.ink,
            )
            Text("Tap for live progress", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        is Screen.Pipeline.Failed -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.errorSurface)
                .clickable(onClickLabel = "Try again", role = Role.Button, onClick = ui.onRetryUpload)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "${p.stage.replaceFirstChar { it.uppercase() }} failed · tap to try again",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = c.ink,
            )
            Text(p.reason, style = MaterialTheme.typography.labelMedium, color = c.ink)
        }
    }
}

@Composable
private fun StatusCard(title: String, detail: String, bg: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = Elevate.colors.ink)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = Elevate.colors.inkMuted)
    }
}

/** Once set up: a one-line factual summary of what the user chose; tapping it reopens setup. */
@Composable
private fun SetupRow(screen: Screen.Replay, onSetUp: () -> Unit) {
    val c = Elevate.colors
    val r = screen.setup ?: return
    val p = screen.preflight as? PreflightState.Done ?: return
    if (p.blocked) return
    CompactRow(
        icon = ElevateIcons.Sliders,
        title = r.shortSummary(),
        bg = c.mint,
        iconTint = c.mintInk,
        value = "Edit",
        valueDescription = r.summary(),
        onClick = onSetUp,
        onClickLabel = "Change setup",
        explain = Explanations.CourtCorners,
    )
}

/** The screen's main action before setup: open the setup flow for a clip that can be analysed. */
@Composable
private fun AnalyseButton(screen: Screen.Replay, onSetUp: () -> Unit) {
    if (screen.setup != null) return
    val p = screen.preflight as? PreflightState.Done ?: return
    if (p.blocked) return
    ExplainBox(Explanations.Analyse, Modifier.fillMaxWidth()) { PrimaryButton("Analyse this match", onClick = onSetUp) }
}

/** e.g. "Badminton singles · 4 corners". Only what the user chose. */
private fun com.elevate.app.ui.setup.SetupResult.shortSummary(): String =
    "${sport.label} ${format.label.lowercase()} · ${if (calibrationInput != null) "4 corners" else "no court"}"

/** e.g. "Badminton singles · 4 court corners · you at 0:41.20". Only what the user chose. */
private fun com.elevate.app.ui.setup.SetupResult.summary(): String {
    val corners = if (calibrationInput != null) "4 court corners" else "no court"
    return "${sport.label} ${format.label.lowercase()} · $corners · you at ${formatTime(whoIsMe.tMs)}"
}

/** Short trail line: who made it, then its count with the denominator ("153/196" labelled frames). */
internal data class TrailSummary(val title: String, val value: String, val spoken: String)

internal fun trailSummary(t: com.elevate.app.domain.model.TrailTrack): TrailSummary = TrailSummary(
    title = if (t.provenance.isHandLabelled) "Hand-labelled trail" else "Shuttle trail",
    value = "${t.observedFrames}/${t.labelledFrames}",
    spoken = "shuttle seen in ${t.observedFrames} of ${t.labelledFrames} labelled frames",
)

internal fun preflightTitle(blocked: Boolean, findings: Int): String = when {
    blocked -> "Can't be analysed"
    findings == 0 -> "Clip check · no issues"
    else -> "Clip check · $findings to note"
}

/**
 * Header title: the file's display name without its extension. Gallery pickers often hand back the
 * MediaStore id as the name ("1000219389.mp4"), which means nothing to a player: show "Your match".
 */
internal fun displayTitle(name: String?): String {
    val trimmed = name?.trim().orEmpty()
    val base = trimmed.substringBeforeLast('.', missingDelimiterValue = trimmed)
    return if (base.isBlank() || base.all { it.isDigit() }) "Your match" else base
}

/** Header subtitle from measured facts only, e.g. "1080p50 · 8:04". Unknown parts are left out. */
internal fun videoSubtitle(width: Int?, height: Int?, fps: Double?, durationMs: Long?): String? {
    val short = if (width != null && height != null && width > 0 && height > 0) minOf(width, height) else null
    val res = short?.let { s -> "${s}p" + (fps?.takeIf { it > 0 }?.let { Math.round(it).toString() } ?: "") }
    val dur = durationMs?.takeIf { it > 0 }?.let { formatClock(it) }
    return listOfNotNull(res, dur).joinToString(" · ").ifEmpty { null }
}

/** Whole-second clock: "8:04", "1:02:09". */
internal fun formatClock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
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
