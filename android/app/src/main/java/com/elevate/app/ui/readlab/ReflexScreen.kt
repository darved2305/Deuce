package com.elevate.app.ui.readlab

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.playback.PlayerController
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PillChip
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.ScreenSpacing
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.components.VideoCard
import com.elevate.app.ui.components.VideoChip
import com.elevate.app.ui.theme.Elevate
import kotlinx.coroutines.delay

/** Per-device best score, one number per match, in the app's own SharedPreferences file. */
class SharedPrefsReflexBest(context: Context) : ReflexBestStore {
    private val prefs = context.applicationContext.getSharedPreferences("elevate_reflex", Context.MODE_PRIVATE)
    private fun key(matchKey: String) = "best:$matchKey"
    override fun best(matchKey: String): Int? = prefs.getInt(key(matchKey), -1).takeIf { it >= 0 }
    override fun save(matchKey: String, score: Int) { prefs.edit { putInt(key(matchKey), score) } }
}

/** Entry point button for the match overview: shown only with at least [ReflexRounds.MIN_ROUNDS] playable rounds. */
@Composable
fun ReflexEntryButton(rounds: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (rounds < ReflexRounds.MIN_ROUNDS) return
    SecondaryButton(ReflexText.entry(rounds), onClick, modifier.fillMaxWidth())
}

/** A stopwatch that can be paused; the reaction time is read from it at the moment of the tap. */
private class Stopwatch {
    private var accMs = 0L
    private var since: Long? = null
    fun restart() { accMs = 0; since = SystemClock.elapsedRealtime() }
    fun pause() { since?.let { accMs += SystemClock.elapsedRealtime() - it; since = null } }
    fun resume() { if (since == null) since = SystemClock.elapsedRealtime() }
    fun elapsedMs(): Long = accMs + (since?.let { SystemClock.elapsedRealtime() - it } ?: 0L)
}

private enum class Phase { Run, Armed, Reveal }

/** Poll step of the round loop; small enough that the freeze lands within about a frame of its time. */
private const val STEP_MS = 8L

/**
 * Reflex mode over the one player the app already has. Each round plays the real clip from
 * [ReflexRules.RUN_UP_MS] before the freeze frame (the first real frame of the drill's ReadLab ladder,
 * strictly before the contact), pauses there, runs the countdown, then plays on through the shot.
 */
@UnstableApi
@Composable
fun ReflexRoute(vm: ReplayViewModel, drills: List<Drill>, matchKey: String, cueSource: CueSource?, onBack: () -> Unit) {
    val state = vm.reflex
    val session by state.session.collectAsState()
    val settlement by state.settlement.collectAsState()
    val controller = vm.controller
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val rounds = remember(drills) { ReflexRounds.of(drills) }
    LaunchedEffect(rounds) { state.start(rounds) }
    val s = session

    // The engine's trail and hit marks would draw the future on the clip: off while playing.
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

    var phase by remember { mutableStateOf(Phase.Run) }
    var paused by remember { mutableStateOf(false) }
    var runId by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<ZoneId, Long>?>(null) }
    var timeLeft by remember { mutableFloatStateOf(1f) }
    val stopwatch = remember { Stopwatch() }
    val started = s?.started == true

    // Pause and resume: the player and the timers stop together.
    LaunchedEffect(paused) {
        if (paused) { controller.pause(); stopwatch.pause() }
        else {
            stopwatch.resume()
            if (started && phase != Phase.Armed && !s.finished) controller.playerFlow.value.play()
        }
    }

    LaunchedEffect(started, runId) {
        if (!started) { controller.pause(); return@LaunchedEffect }
        while (true) {
            val cur = state.session.value ?: break
            val round = cur.round ?: break
            val freezeUs = round.drill.challenge.ladderUs.first()
            val freezeMs = ReadLabSession.seekMs(freezeUs)
            val timer = cur.timerMs

            // 1. Run-up: the real clip at normal speed, up to the freeze frame.
            phase = Phase.Run
            pending = null
            timeLeft = 1f
            controller.seekTo(ReflexRules.startUs(freezeUs) / 1000)
            if (!paused) controller.playerFlow.value.play()
            while (controller.positionMs() < freezeMs || paused) delay(STEP_MS)

            // 2. Freeze on the exact real frame; the countdown and the reaction clock start together.
            controller.pause()
            controller.seekTo(freezeMs)
            stopwatch.restart()
            if (paused) stopwatch.pause()
            phase = Phase.Armed
            while (pending == null && stopwatch.elapsedMs() < timer) {
                timeLeft = (1f - stopwatch.elapsedMs().toFloat() / timer).coerceIn(0f, 1f)
                delay(STEP_MS)
            }
            val tap = pending
            state.update { it.answer(tap?.first, tap?.second) }
            if (tap == null) timeLeft = 0f

            // 3. Reveal: play on through the real shot, then the next round.
            phase = Phase.Reveal
            val good = state.session.value?.results?.lastOrNull()?.verdict?.keepsStreak == true
            haptic.performHapticFeedback(if (good) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
            stopwatch.restart()
            if (paused) stopwatch.pause() else controller.playerFlow.value.play()
            while (stopwatch.elapsedMs() < ReflexRules.REVEAL_MS) delay(STEP_MS)
            controller.pause()
        }
        phase = Phase.Run
    }

    // Shuttle cue: only while frozen and the answer is open (Armed), from real observed rows up to the freeze frame.
    var cueOn by rememberSaveable { mutableStateOf(true) }
    val freezeUs = s?.round?.drill?.challenge?.ladderUs?.firstOrNull()
    val cue = remember(cueSource, freezeUs) { if (freezeUs != null) cueSource?.cueAt(freezeUs) else null }
    val cueUi = cueSource?.let { CueUi(if (cueOn && phase == Phase.Armed) cue else null, it.imageW, it.imageH, cueOn) { cueOn = !cueOn } }

    // A finished run stores its best once.
    LaunchedEffect(s?.finished) { if (s?.finished == true) state.settle(SharedPrefsReflexBest(context), matchKey) }

    BackHandler(onBack = onBack)
    ReflexScreen(
        session = s,
        settlement = settlement,
        phase = phase,
        paused = paused,
        timeLeft = timeLeft,
        controller = controller,
        cueUi = cueUi,
        onPick = { z ->
            if (phase == Phase.Armed && !paused && pending == null) {
                pending = z to stopwatch.elapsedMs()
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        },
        onRelaxed = { on -> state.update { it.withRelaxed(on) } },
        onStart = { state.update { it.begin() } },
        onPause = { paused = !paused },
        onPlayAgain = { paused = false; state.restart(); state.update { it.begin() }; runId++ },
        onBack = onBack,
    )
}

@UnstableApi
@Composable
private fun ReflexScreen(
    session: ReflexSession?,
    settlement: ReflexSettlement?,
    phase: Phase,
    paused: Boolean,
    timeLeft: Float,
    controller: PlayerController,
    cueUi: CueUi?,
    onPick: (ZoneId) -> Unit,
    onRelaxed: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onPlayAgain: () -> Unit,
    onBack: () -> Unit,
) {
    PeachPage {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                ElevateHeader("Reflex", onBack = onBack, subtitle = "Read the shot before it lands")
                when {
                    session == null || session.rounds.size < ReflexRounds.MIN_ROUNDS -> Column(Modifier.padding(horizontal = ScreenGutter)) {
                        Text("Reflex needs at least ${ReflexRounds.MIN_ROUNDS} shots with a known landing in this match.", style = MaterialTheme.typography.bodyMedium)
                    }
                    !session.started -> Intro(session, settlement, cueUi, onRelaxed, onStart, onBack)
                    session.finished && phase != Phase.Reveal -> End(session, settlement, onPlayAgain, onBack)
                    landscape -> Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            Hud(session, phase, paused, onPause, onBack)
                            Clip(session, phase, controller, cueUi)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            Play(session, phase, paused, timeLeft, onPick)
                        }
                    }
                    else -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
                        verticalArrangement = ScreenSpacing,
                    ) {
                        Hud(session, phase, paused, onPause, onBack)
                        Clip(session, phase, controller, cueUi)
                        Play(session, phase, paused, timeLeft, onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro(s: ReflexSession, settlement: ReflexSettlement?, cueUi: CueUi?, onRelaxed: (Boolean) -> Unit, onStart: () -> Unit, onBack: () -> Unit) {
    val c = Elevate.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
        verticalArrangement = ScreenSpacing,
    ) {
        ElevateCard(tint = c.lavender) {
            Text("${s.rounds.size} real shots from your match", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "The clip plays, then freezes just before the hit. Tap the zone where you think the shuttle lands before the bar runs out. " +
                    "Faster reads and longer streaks score more.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink,
            )
            settlement?.best?.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(8.dp))
                Text("${ReflexText.BEST_LABEL}: $it", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillChip(ReflexText.RELAXED_LABEL, s.relaxed, { onRelaxed(!s.relaxed) })
            Text(if (s.relaxed) "3 s to answer" else "1.5 s to answer", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        if (cueUi != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShuttleCueToggle(cueUi.on, cueUi.onToggle)
                Text("Shows where the shuttle is and which way it moves at the freeze", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
        }
        PrimaryButton("Start", onStart, Modifier.fillMaxWidth())
        SecondaryButton("Back to the match", onBack, Modifier.fillMaxWidth())
    }
}

/** Round, score and streak, with Pause and Quit always in reach. */
@Composable
private fun Hud(s: ReflexSession, phase: Phase, paused: Boolean, onPause: () -> Unit, onQuit: () -> Unit) {
    val c = Elevate.colors
    val n = if (phase == Phase.Reveal) s.index else minOf(s.index + 1, s.rounds.size)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Round $n of ${s.rounds.size}", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            Text(
                "Score ${s.score}" + if (s.streak >= 2) " · streak ${s.streak}" else "",
                style = MaterialTheme.typography.titleMedium, color = c.ink,
            )
        }
        SecondaryButton(if (paused) "Resume" else "Pause", onPause)
        SecondaryButton("Quit", onQuit)
    }
}

@UnstableApi
@Composable
private fun Clip(s: ReflexSession, phase: Phase, controller: PlayerController, cueUi: CueUi?) {
    VideoCard(Modifier.aspectRatio(16f / 9f)) {
        val player by controller.playerFlow.collectAsState()
        ContentFrame(player = player, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Your match video" })
        if (cueUi != null && phase == Phase.Armed) ShuttleCueOverlay(cueUi.cue, cueUi.imageW, cueUi.imageH)
        VideoChip(
            when (phase) {
                Phase.Run -> "Watch the rally"
                Phase.Armed -> "Frozen before the hit"
                Phase.Reveal -> "The real shot"
            },
            Modifier.align(Alignment.TopStart).padding(12.dp),
        )
    }
}

@Composable
private fun Play(s: ReflexSession, phase: Phase, paused: Boolean, timeLeft: Float, onPick: (ZoneId) -> Unit) {
    val c = Elevate.colors
    // In the reveal, the round just answered is the last result; otherwise the current one.
    val revealIx = s.results.lastIndex
    val round = if (phase == Phase.Reveal && revealIx >= 0) s.rounds[revealIx] else s.round ?: return
    val result = if (phase == Phase.Reveal) s.results.lastOrNull() else null
    val drill = round.drill
    val tint = when (result?.verdict) {
        ReflexVerdict.EXACT -> c.mint
        ReflexVerdict.ADJACENT -> c.peach
        ReflexVerdict.WRONG -> c.errorSurface
        ReflexVerdict.TOO_SLOW -> c.lavender
        null -> Color.White
    }
    ElevateCard(tint = tint) {
        when {
            phase == Phase.Reveal && result != null -> Verdict(round, result)
            phase == Phase.Armed -> Countdown(timeLeft, s.timerMs)
            else -> Text("Get ready. Watch the rally.", style = MaterialTheme.typography.titleMedium, color = c.ink)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val reveal = phase == Phase.Reveal
            ZonePicker(
                template = drill.template,
                side = drill.viewSide,
                picked = if (reveal) result?.pick else null,
                locked = phase != Phase.Armed || paused,
                // The landing is shown only in the reveal, never while the answer can still change.
                truthZone = if (reveal) ReadLabText.zoneOf(drill) else null,
                landingXyM = if (reveal) drill.landingXyM else null,
                landingTrusted = !round.vsEstimate,
                onPick = onPick,
                modifier = Modifier.heightIn(max = 320.dp).alpha(if (phase == Phase.Run) 0.55f else 1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Target half, from the hitter's view (net at the top)" + if (paused) ". Paused." else "",
            style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
        )
    }
}

@Composable
private fun Countdown(timeLeft: Float, timerMs: Long) {
    val c = Elevate.colors
    val color = if (timeLeft > 0.5f) lerp(c.orange, c.mintInk, (timeLeft - 0.5f) * 2f) else lerp(c.error, c.orange, timeLeft * 2f)
    val secs = timeLeft * timerMs / 1000f
    Text("Tap where it lands", style = MaterialTheme.typography.titleMedium, color = c.ink)
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(c.line)
            .semantics { contentDescription = "Time left"; stateDescription = "%.1f seconds".format(secs) },
    ) {
        Box(Modifier.fillMaxWidth(timeLeft).fillMaxHeight().clip(CircleShape).background(color))
    }
}

@Composable
private fun Verdict(round: ReflexRound, r: ReflexRoundResult) {
    val c = Elevate.colors
    val landing = ReadLabText.zoneOf(round.drill)?.let { ReadLabText.zoneName(it) }
    val where = if (round.vsEstimate) "Engine estimate: $landing" else "Real landing: $landing"
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${ReflexText.verdictLine(r)}. $where. ${r.points} points" }) {
        Text(ReflexText.verdictLine(r), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(
            if (r.points > 0) "+${r.points} points" + if (r.streak >= 2) " · streak ${r.streak}" else "" else "No points",
            style = MaterialTheme.typography.bodyMedium, color = c.ink,
        )
        Text(where, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}

@Composable
private fun End(s: ReflexSession, settlement: ReflexSettlement?, onPlayAgain: () -> Unit, onBack: () -> Unit) {
    val c = Elevate.colors
    val sum = s.summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
        verticalArrangement = ScreenSpacing,
    ) {
        ElevateCard(tint = c.mint) {
            Text("Score ${sum.score}", style = MaterialTheme.typography.displaySmall, color = c.ink)
            settlement?.let {
                Text(
                    if (it.newBest) "New best! ${ReflexText.BEST_LABEL}: ${it.best}" else "${ReflexText.BEST_LABEL}: ${it.best}",
                    style = MaterialTheme.typography.titleMedium, color = c.ink,
                )
            }
        }
        ElevateCard {
            Text("${sum.exact} exact · ${sum.adjacent} adjacent · ${sum.wrong} wrong · ${sum.tooSlow} too slow", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text("Accuracy ${sum.accuracyPct}% of ${sum.rounds} rounds", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            Text(
                sum.medianReactionMs?.let { "Median reaction $it ms" } ?: "No round answered in time",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            Text("Best streak ${sum.bestStreak}", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            ReflexText.estimateNote(sum)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
        }
        PrimaryButton("Play again", onPlayAgain, Modifier.fillMaxWidth())
        SecondaryButton("Back to the match", onBack, Modifier.fillMaxWidth())
    }
}
