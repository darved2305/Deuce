package com.elevate.app.ui.readlab

import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.ExplainBox
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.readlab.ReadAttempt
import com.elevate.app.domain.readlab.ReadResult
import com.elevate.app.playback.PlayerController
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.PopIn
import com.elevate.app.ui.components.StateMessage
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.ScreenSpacing
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.components.VideoCard
import com.elevate.app.ui.components.VideoChip
import com.elevate.app.ui.theme.Elevate

/** Entry point button for the match overview: shown only when there is at least one drill. */
@Composable
fun ReadLabEntryButton(drills: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (drills < 1) return
    ExplainBox(Explanations.ReadLab, modifier) { PrimaryButton(ReadLabText.entry(drills), onClick, Modifier.fillMaxWidth().semantics { contentDescription = "Train your read, $drills drill${if (drills == 1) "" else "s"}" }) }
}

/**
 * ReadLab over the one player the app already has: the frame is frozen by seeking that player to a
 * real frame time before the contact and pausing it. No second player is created.
 */
@UnstableApi
@Composable
fun ReadLabRoute(vm: ReplayViewModel, drills: List<Drill>, cueSource: CueSource?, onBack: () -> Unit) {
    val state = vm.readLab
    val session by state.session.collectAsState()
    val controller = vm.controller
    LaunchedEffect(drills) { state.start(drills) }
    val s = session

    // Backgrounded: stop the video so no sound plays from a hidden screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }

    // The engine's trail and hit marks would draw the future on the frozen frame: off while training.
    DisposableEffect(vm) {
        val wasOn = (vm.screen.value as? Screen.Replay)?.overlayVisible ?: true
        vm.setOverlayVisible(false)
        controller.setHitsVisible(false)
        onDispose {
            controller.pause()
            controller.setHitsVisible(true)
            vm.setOverlayVisible(wasOn)
        }
    }
    // Freeze: seek to the ladder frame of the current drill and pause. Nothing later is shown until the answer is locked.
    val frameUs = s?.attempt?.takeIf { !it.locked }?.frameUs
    val drillKey = s?.drill?.hitEventId
    LaunchedEffect(drillKey, frameUs) {
        if (frameUs != null) {
            controller.pause()
            controller.seekTo(ReadLabSession.seekMs(frameUs))
        }
    }

    // Shuttle cue: only on the frozen, unanswered frame, from real observed rows up to that frame. Gone once locked.
    var cueOn by rememberSaveable { mutableStateOf(true) }
    val cue = remember(cueSource, frameUs) { if (frameUs != null) cueSource?.cueAt(frameUs) else null }
    val cueUi = cueSource?.let { CueUi(if (cueOn) cue else null, it.imageW, it.imageH, cueOn) { cueOn = !cueOn } }

    // Reveal: once the answer is locked, play the real shot slowly from just before contact to the
    // landing with the trail and the LANDING? mark on, then hold the landing frame.
    val locked = s?.attempt?.locked == true
    var replays by remember { mutableIntStateOf(0) }
    LaunchedEffect(drillKey, locked, replays) {
        val d = s?.drill ?: return@LaunchedEffect
        if (!locked) {
            vm.setOverlayVisible(false)
            controller.setHitsVisible(false)
            return@LaunchedEffect
        }
        vm.setOverlayVisible(true)
        controller.setHitsVisible(true)
        controller.pause()
        controller.seekTo((d.challenge.contactMs - REVEAL_LEAD_MS).coerceAtLeast(0))
        controller.setSpeed(REVEAL_SPEED)
        delay(150)
        controller.play()
        val stopAt = d.landingMs + REVEAL_TAIL_MS
        try {
            while (controller.positionMs() < stopAt) delay(16)
        } finally {
            controller.pause()
            controller.setSpeed(1f)
        }
    }

    BackHandler(onBack = onBack)
    ReadLabScreen(
        session = s,
        controller = controller,
        cueUi = cueUi,
        onPick = { z -> state.update { it.pick(z) } },
        onAnotherLook = { state.update { it.anotherLook() } },
        onNext = { controller.pause(); state.update { it.next() } },
        onWatch = { replays++ },
        onRestart = { state.restart() },
        onBack = onBack,
    )
}

private const val REVEAL_LEAD_MS = 400L
/** Inside HitOverlay.WINDOW_MS (150 ms), so the held frame still shows the LANDING? mark. */
private const val REVEAL_TAIL_MS = 80L
private const val REVEAL_SPEED = 0.5f

@UnstableApi
@Composable
fun ReadLabScreen(
    session: ReadLabSession?,
    controller: PlayerController,
    cueUi: CueUi?,
    onPick: (ZoneId) -> Unit,
    onAnotherLook: () -> Unit,
    onNext: () -> Unit,
    onWatch: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    PeachPage {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                ElevateHeader("ReadLab", onBack = onBack, subtitle = "Train your read on your own match")
                when {
                    session == null || session.drills.isEmpty() -> Column(Modifier.padding(horizontal = ScreenGutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(ReadLabText.HEADER, style = MaterialTheme.typography.titleMedium, color = Elevate.colors.ink)
                        StateMessage(ElevateIcons.Empty, ReadLabText.EMPTY, actionLabel = "Back to the match", onAction = onBack)
                    }
                    session.finished -> SummaryView(session, onRestart, onBack)
                    landscape -> Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            Frozen(session, controller, cueUi)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            Answer(session, onPick, onAnotherLook, onNext, onWatch)
                        }
                    }
                    else -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
                        verticalArrangement = ScreenSpacing,
                    ) {
                        Frozen(session, controller, cueUi)
                        Answer(session, onPick, onAnotherLook, onNext, onWatch)
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun Frozen(s: ReadLabSession, controller: PlayerController, cueUi: CueUi?) {
    val c = Elevate.colors
    Text(ReadLabText.HEADER, style = MaterialTheme.typography.titleMedium, color = c.ink)
    val attempt = s.attempt ?: return
    VideoCard(Modifier.aspectRatio(16f / 9f)) {
        val player by controller.playerFlow.collectAsState()
        ContentFrame(player = player, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Your match video, frozen just before a hit" })
        if (cueUi != null && !attempt.locked) ShuttleCueOverlay(cueUi.cue, cueUi.imageW, cueUi.imageH)
        VideoChip(
            if (attempt.locked) "The real shot · ½× · stops where it landed" else "Drill ${s.index + 1} of ${s.drills.size} · frozen before the hit",
            Modifier.align(Alignment.TopStart).padding(12.dp),
        )
    }
    ExplainBox(Explanations.ZonePicker, Modifier.fillMaxWidth()) {
        Text(
            if (attempt.locked) "Answer locked. Watch the shot land, then check the orange mark below." else "Where will the shuttle land? Tap a zone.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink,
        )
    }
    if (cueUi != null) ShuttleCueToggle(cueUi.on, cueUi.onToggle)
}

@Composable
private fun Answer(s: ReadLabSession, onPick: (ZoneId) -> Unit, onAnotherLook: () -> Unit, onNext: () -> Unit, onWatch: () -> Unit) {
    val c = Elevate.colors
    val drill = s.drill ?: return
    val attempt = s.attempt ?: return
    val locked = attempt.locked
    val grade = s.grade
    val trusted = grade != null && !grade.vsEstimate && grade.result !is ReadResult.Unscored
    ElevateCard {
        Text(
            "Target half, from the hitter's view (net at the top)",
            style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ZonePicker(
                template = drill.template,
                side = drill.viewSide,
                picked = attempt.pick,
                locked = locked,
                truthZone = ReadLabText.zoneOf(drill),
                landingXyM = drill.landingXyM,
                landingTrusted = trusted,
                onPick = onPick,
                modifier = Modifier.heightIn(max = 260.dp),
            )
        }
    }
    if (grade != null) key(drill.hitEventId) { PopIn { Reveal(drill, attempt, grade) } }
    if (!locked && attempt.canRevealMore) {
        SecondaryButton("Another look (a later frame)", onAnotherLook, Modifier.fillMaxWidth())
    }
    if (locked) {
        SecondaryButton("Watch it land again", onWatch, Modifier.fillMaxWidth())
        PrimaryButton(if (s.isLast) "See summary" else "Next", onNext)
    }
}

@Composable
private fun Reveal(drill: Drill, attempt: ReadAttempt, grade: Graded) {
    val c = Elevate.colors
    val text = ReadLabText.reveal(drill, grade)
    val tint = when (grade.result) {
        ReadResult.Exact -> c.mint
        ReadResult.Adjacent -> c.peach
        ReadResult.Wrong -> c.errorSurface
        is ReadResult.Unscored -> c.lavender
    }
    ElevateCard(tint = tint, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${text.verdict}. ${text.landing}" }) {
        Text(text.landing, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(4.dp))
        attempt.pick?.let { Text("Your pick: ${ReadLabText.zoneName(it)}", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
        Text(text.verdict, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        ReadLabText.margin(attempt)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.inkMuted) }
    }
}

@Composable
private fun SummaryView(s: ReadLabSession, onRestart: () -> Unit, onBack: () -> Unit) {
    val c = Elevate.colors
    val sum = s.summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
        verticalArrangement = ScreenSpacing,
    ) {
        Text(ReadLabText.HEADER, style = MaterialTheme.typography.titleMedium, color = c.ink)
        ElevateCard(tint = c.mint) {
            ReadLabText.summaryLines(sum).forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = c.ink) }
            sum.medianMarginMs?.let {
                Text("Median read margin: $it ms before contact", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
            if (sum.estScored > 0) {
                Text(
                    "Engine estimates are not confirmed landings, so those reads are measured against the engine, not the real shot.",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
            }
        }
        PrimaryButton("Train again", onRestart)
        SecondaryButton("Back to the match", onBack, Modifier.fillMaxWidth())
    }
}
