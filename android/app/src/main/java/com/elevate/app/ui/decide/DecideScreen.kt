package com.elevate.app.ui.decide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.decide.Decide
import com.elevate.app.domain.decide.DecideSession
import com.elevate.app.domain.decide.Freeze
import com.elevate.app.domain.decide.Outcome
import com.elevate.app.domain.decide.Reach
import com.elevate.app.domain.decide.zoneKey
import com.elevate.app.domain.shotrewind.Decision
import com.elevate.app.playback.PlayerController
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.ScreenSpacing
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.components.StateMessage
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.VideoCard
import com.elevate.app.ui.components.VideoChip
import com.elevate.app.ui.components.animatedCount
import com.elevate.app.ui.components.popIn
import com.elevate.app.ui.components.rememberHaptics
import com.elevate.app.ui.components.rememberReduceMotion
import com.elevate.app.ui.readlab.ReadLabSession
import com.elevate.app.ui.readlab.ReadLabText
import com.elevate.app.ui.theme.Elevate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** Decide mode's run; lives in the view model so it survives rotation and leaving to the overview. */
class DecideState {
    private val _session = MutableStateFlow<DecideSession?>(null)
    val session: StateFlow<DecideSession?> = _session.asStateFlow()
    private var source: List<String> = emptyList()
    private var seed = 1L

    /** Starts a run unless one over this same set of moments is already going. */
    fun start(freezes: List<Freeze>) {
        val ids = freezes.map { it.hitEventId }
        if (ids == source && _session.value != null) return
        source = ids
        seed = 1L
        _session.value = DecideSession(Decide.pick(freezes, seed))
    }

    fun playAgain(freezes: List<Freeze>) {
        seed++
        _session.value = DecideSession(Decide.pick(freezes, seed), started = true)
    }

    fun update(f: (DecideSession) -> DecideSession) = _session.update { it?.let(f) }
}

/** Overview button; only with at least [Decide.MIN_FREEZES] usable moments. */
@Composable
fun DecideEntryButton(freezes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (freezes < Decide.MIN_FREEZES) return
    ExplainBox(Explanations.Decide, modifier.fillMaxWidth()) { SecondaryButton("Decide", onClick, Modifier.fillMaxWidth()) }
}

/** How long the real continuation plays after a commit. */
private const val CLIP_MS = 2_800L
private const val SEEK_WAIT_MS = 600L

@UnstableApi
@Composable
fun DecideRoute(vm: ReplayViewModel, freezes: List<Freeze>, onBack: () -> Unit) {
    val state = vm.decide
    val session by state.session.collectAsState()
    val controller = vm.controller
    val haptics = rememberHaptics()
    LaunchedEffect(freezes) { state.start(freezes) }
    val s = session

    // The engine's trail and hit marks would draw the future on the clip: off while deciding.
    DisposableEffect(vm) {
        val wasOn = (vm.screen.value as? Screen.Replay)?.overlayVisible ?: true
        val wasSpeed = controller.state.value.speed
        vm.setOverlayVisible(false)
        controller.setHitsVisible(false)
        controller.setSpeed(1f)
        controller.pause()
        onDispose {
            controller.pause()
            controller.setSpeed(wasSpeed)
            controller.setHitsVisible(true)
            vm.setOverlayVisible(wasOn)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }

    val index = s?.index ?: 0
    var aim by remember(index) { mutableStateOf<Pair<Double, Double>?>(null) }
    var clipRuns by remember(index) { mutableIntStateOf(0) }
    val freeze = s?.current
    val committed = s?.committed == true

    // Freeze on the real frame, then (after a commit) play the real continuation.
    LaunchedEffect(index, committed, clipRuns, s?.started) {
        if (s == null || !s.started || freeze == null) { controller.pause(); return@LaunchedEffect }
        val ms = ReadLabSession.seekMs(freeze.freezeUs)
        if (!committed || clipRuns > 0) {
            controller.pause()
            controller.seekTo(ms)
            withTimeoutOrNull(SEEK_WAIT_MS) {
                while (controller.playerFlow.value.playbackState != Player.STATE_READY) delay(16)
            }
        }
        if (committed) {
            controller.playerFlow.value.play()
            delay(CLIP_MS)
            controller.pause()
        }
    }

    // Snap haptics: one tick each time the aim crosses into another zone (or out of the court).
    val key = if (freeze != null && aim != null && !committed) zoneKey(freeze, aim!!.first, aim!!.second) else null
    LaunchedEffect(key) { if (key != null) haptics.tick() }

    BackHandler(onBack = onBack)
    DecideScreen(
        session = s,
        controller = controller,
        aim = aim,
        onAim = { aim = it },
        onCommit = {
            val a = aim
            if (a != null && freeze != null && !committed) { haptics.confirm(); state.update { it.commit(a.first, a.second) } }
        },
        onStart = { state.update { it.begin() } },
        onNext = { state.update { it.next() } },
        onReplayClip = { clipRuns++ },
        onPlayAgain = { state.playAgain(freezes) },
        onBack = onBack,
    )
}

@UnstableApi
@Composable
private fun DecideScreen(
    session: DecideSession?,
    controller: PlayerController,
    aim: Pair<Double, Double>?,
    onAim: (Pair<Double, Double>) -> Unit,
    onCommit: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onReplayClip: () -> Unit,
    onPlayAgain: () -> Unit,
    onBack: () -> Unit,
) {
    PeachPage {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                ElevateHeader("Decide", onBack = onBack, subtitle = "Freeze, aim, then see what happened")
                val f = session?.current
                when {
                    session == null || session.freezes.size < Decide.MIN_FREEZES -> Column(Modifier.padding(horizontal = ScreenGutter)) {
                        StateMessage(
                            ElevateIcons.Empty,
                            "Decide needs at least ${Decide.MIN_FREEZES} shots with a usable frame in this match.",
                            actionLabel = "Back to the match", onAction = onBack,
                        )
                    }
                    !session.started -> Intro(session, onStart, onBack)
                    session.finished || f == null -> End(session, onPlayAgain, onBack)
                    landscape -> Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Hud(session, f, onBack)
                            Clip(session, controller, Modifier.fillMaxWidth())
                            Panel(session, f, onNext, onReplayClip)
                        }
                        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                            DecideCourt(f, aim, session.outcome, !session.committed, onAim, onCommit, Modifier.fillMaxHeight())
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().padding(horizontal = ScreenGutter).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hud(session, f, onBack)
                        Clip(session, controller, Modifier.fillMaxWidth(0.7f).align(Alignment.CenterHorizontally))
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DecideCourt(f, aim, session.outcome, !session.committed, onAim, onCommit, Modifier.fillMaxHeight())
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Panel(session, f, onNext, onReplayClip)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro(s: DecideSession, onStart: () -> Unit, onBack: () -> Unit) {
    val c = Elevate.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
        verticalArrangement = ScreenSpacing,
    ) {
        ElevateCard(tint = c.lavender) {
            Text("${s.freezes.size} real moments from your match", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "The clip freezes just before a hit. Drag the aim point onto the court and let go. " +
                    "You see if it is in, how much room the defender left you, and what you really played.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.freezes.all { it.yours }) "Every moment is one of your own shots."
                else "Your own shots could not be told apart here, so each moment is labelled a shot from this rally.",
                style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
            )
        }
        PrimaryButton("Start", onStart, Modifier.fillMaxWidth())
        SecondaryButton("Back to the match", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Hud(s: DecideSession, f: Freeze, onQuit: () -> Unit) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Decision ${s.index + 1} of ${s.freezes.size}", style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1)
            Text(f.caption, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, maxLines = 1)
        }
        SecondaryButton("Quit", onQuit)
    }
}

@UnstableApi
@Composable
private fun Clip(s: DecideSession, controller: PlayerController, modifier: Modifier) {
    VideoCard(modifier.aspectRatio(16f / 9f)) {
        val player by controller.playerFlow.collectAsState()
        ContentFrame(player = player, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Your match video" })
        VideoChip(
            if (s.committed) "The real clip" else "Frozen before the hit",
            Modifier.align(Alignment.TopStart).padding(8.dp),
        )
    }
}

private fun zoneLabel(z: ZoneId) = ReadLabText.zoneName(z).substringBefore(" (")

private fun reachNote(r: Reach) = when (r) {
    Reach.OPEN_COURT -> "The defender is 2 or more zones away"
    Reach.STRETCH -> "The defender is one zone away"
    Reach.RIGHT_AT_THEM -> "The defender is in that zone"
}

@Composable
private fun Panel(s: DecideSession, f: Freeze, onNext: () -> Unit, onReplayClip: () -> Unit) {
    val c = Elevate.colors
    val reduce = rememberReduceMotion()
    val o = s.outcome
    if (o == null) {
        ExplainBox(Explanations.DecideReticle, Modifier.fillMaxWidth()) {
            Column {
                Text("Where would you hit it?", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("Drag the aim point onto the court. Let go to commit.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
        }
        ExplainBox(Explanations.DecideDefender, Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch(c.lavenderInk)
                Text(
                    if (f.defenderSeen) "Defender, where they really stood" else "Defender position not seen",
                    style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
                )
            }
        }
        return
    }
    androidx.compose.animation.AnimatedVisibility(visible = true, enter = popIn(reduce)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Legality.
            val (legalText, legalTint) = when (o.decision) {
                is Decision.Legal -> "Lands in" to c.mint
                Decision.Out -> "Goes out" to c.errorSurface
                Decision.OwnHalf -> "Your own side" to c.errorSurface
                Decision.NoCourt -> "NO COURT" to c.errorSurface
            }
            ExplainBox(Explanations.DecideLegal, Modifier.fillMaxWidth()) {
                ElevateCard(tint = legalTint) {
                    Text(legalText, style = MaterialTheme.typography.headlineSmall, color = if (o.legal) c.mintInk else c.error)
                    Text(
                        when (val d = o.decision) {
                            is Decision.Legal -> zoneLabel(d.zone)
                            Decision.Out -> "Outside the lines"
                            Decision.OwnHalf -> "Did not cross the net"
                            Decision.NoCourt -> "No court grid"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = c.ink,
                    )
                }
            }
            // Defender reach.
            if (o.legal) {
                ExplainBox(Explanations.DecideReach, Modifier.fillMaxWidth()) {
                    ElevateCard {
                        val r = o.reach
                        if (r != null) {
                            Text(r.label, style = MaterialTheme.typography.titleMedium, color = c.ink)
                            Text(reachNote(r), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                        } else {
                            Text("No court verdict", style = MaterialTheme.typography.titleMedium, color = c.ink)
                            Text("Defender position not seen at this frame", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                        }
                    }
                }
            }
            // Your aim vs what you really did.
            ExplainBox(Explanations.DecideCompare, Modifier.fillMaxWidth()) {
                ElevateCard {
                    CompareRow("Your choice", c.orange, choiceText(o))
                    Spacer(Modifier.height(6.dp))
                    CompareRow("What you played", Color(0xFF3B6FD4), realText(f))
                    if (o.beatReal) {
                        Spacer(Modifier.height(8.dp))
                        Text("You beat your shot: yours was more open", style = MaterialTheme.typography.labelLarge, color = c.mintInk)
                    }
                }
            }
            ExplainBox(Explanations.DecideSimulated, Modifier.fillMaxWidth()) {
                Text(
                    "SIMULATED arcs. The real clip is the truth.",
                    style = MaterialTheme.typography.labelMedium, color = c.ink,
                    modifier = Modifier.clip(CircleShape).background(c.lavender).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            SecondaryButton("Replay clip", onReplayClip, Modifier.fillMaxWidth())
            PrimaryButton(if (s.index == s.freezes.lastIndex) "See results" else "Next decision", onNext, Modifier.fillMaxWidth())
        }
    }
}

private fun choiceText(o: Outcome): String = when (val d = o.decision) {
    is Decision.Legal -> zoneLabel(d.zone) + (o.reach?.let { ", ${it.label}" } ?: ", no verdict")
    Decision.Out -> "Out of court"
    Decision.OwnHalf -> "Your own side"
    Decision.NoCourt -> "No court grid"
}

private fun realText(f: Freeze): String {
    val real = f.real ?: return "Landing not observed"
    return when (val d = real.decision) {
        is Decision.Legal -> zoneLabel(d.zone) + (real.reach?.let { ", ${it.label}" } ?: ", no verdict")
        Decision.Out -> "Landed out"
        Decision.OwnHalf -> "Did not cross the net"
        Decision.NoCourt -> "No court grid"
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun CompareRow(title: String, color: Color, text: String) {
    val c = Elevate.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp)) { Swatch(color) }
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = c.ink)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
    }
}

@Composable
private fun End(s: DecideSession, onPlayAgain: () -> Unit, onBack: () -> Unit) {
    val c = Elevate.colors
    val sum = s.summary
    val unseen = s.outcomes.count { it.legal && it.reach == null }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
        verticalArrangement = ScreenSpacing,
    ) {
        ExplainBox(Explanations.DecideResults, Modifier.fillMaxWidth()) {
            ElevateCard(tint = c.mint) {
                Text("Session done", style = MaterialTheme.typography.labelLarge, color = c.mintInk)
                Text(
                    "You beat your own shot ${animatedCount(sum.beats)} ${if (sum.beats == 1) "time" else "times"}",
                    style = MaterialTheme.typography.headlineSmall, color = c.ink,
                )
                Text(
                    "Counted when your aim left the defender more room than what you played, out of ${sum.comparable} you can compare.",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Open court", "${animatedCount(sum.openCourt)} of ${sum.decisions}", Modifier.weight(1f))
            StatTile("Legal", "${animatedCount(sum.legalPct)}%", Modifier.weight(1f))
        }
        ElevateCard {
            s.outcomes.forEachIndexed { i, o ->
                Text(
                    "${i + 1}. ${choiceText(o)}" + if (o.beatReal) " (beat your shot)" else "",
                    style = MaterialTheme.typography.bodyMedium, color = c.ink,
                )
            }
            if (unseen > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$unseen aim${if (unseen == 1) "" else "s"} had no defender position, so no court verdict was given.",
                    style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
                )
            }
        }
        PrimaryButton("Play again", onPlayAgain, Modifier.fillMaxWidth())
        SecondaryButton("Back to the match", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    val c = Elevate.colors
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = c.ink, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}
