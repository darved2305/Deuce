package com.elevate.app.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.elevate.app.domain.analysis.BallTrack
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.domain.review.FlightTrail
import com.elevate.app.domain.review.LineCall
import com.elevate.app.domain.review.LineCallText
import com.elevate.app.domain.review.ReviewLanding
import com.elevate.app.domain.review.ReviewLandings
import com.elevate.app.domain.review.Verdict
import com.elevate.app.domain.training.FitTransform
import com.elevate.app.ui.ReplayViewModel
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PillChip
import com.elevate.app.ui.components.PopIn
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.ScreenSpacing
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.components.StateMessage
import com.elevate.app.ui.components.VideoCard
import com.elevate.app.ui.components.VideoChip
import com.elevate.app.ui.components.rememberHaptics
import com.elevate.app.ui.components.rememberReduceMotion
import com.elevate.app.ui.theme.Elevate

/** The three beats of a review: the frozen moment, the zoom to the line, the result. */
enum class Beat(val label: String) { Moment("1 Moment"), Line("2 The line"), Result("3 The call") }

/** Which beats a landing has: no video means no moment; a landing nobody could place has no line to zoom to. */
fun beatsFor(verdict: Verdict, hasVideo: Boolean): List<Beat> = buildList {
    if (hasVideo) add(Beat.Moment)
    if (verdict != Verdict.NotSeen) add(Beat.Line)
    add(Beat.Result)
}

/**
 * Instant Review over the one player the app already has: seek to the landing frame and pause (like ReadLab),
 * then the three beats. Only real landing events are used; nothing here is an official decision.
 */
@UnstableApi
@Composable
fun InstantReviewRoute(
    vm: ReplayViewModel,
    landings: List<ReviewLanding>,
    facts: SourceFacts?,
    ball: BallTrack?,
    matchId: String?,
    onBack: () -> Unit,
) {
    val controller = vm.controller
    val selectedId by vm.review.selected.collectAsState()
    val index = landings.indexOfFirst { it.eventId == selectedId }.coerceAtLeast(0)
    val landing = landings.getOrNull(index)
    val hasVideo = facts != null && facts.displayWidth > 0 && facts.displayHeight > 0

    // Backgrounded: stop the video so no sound plays from a hidden screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }

    // The engine's own trail and landing marks would draw over the frozen frame (and give the answer away): off while reviewing.
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
    // Beat 1: seek to the real frame of the landing and hold it.
    LaunchedEffect(landing?.eventId, hasVideo) {
        if (hasVideo && landing != null) {
            controller.pause()
            controller.seekTo(ReviewLandings.frameMs(facts?.frameTimesUs, landing.tMs))
        }
    }

    BackHandler(onBack = onBack)
    if (landing == null) {
        PeachPage {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                ElevateHeader("Instant Review", onBack = onBack)
                Column(Modifier.padding(horizontal = ScreenGutter)) {
                    StateMessage(ElevateIcons.Empty, "No landings with a court position in this match, so there is nothing to review.", actionLabel = "Back to the match", onAction = onBack)
                }
            }
        }
        return
    }
    val flight = remember(ball, landing.eventId) { FlightTrail.normalised(FlightTrail.before(ball, landing.tMs)) }
    InstantReviewScreen(
        landings = landings,
        index = index,
        matchId = matchId,
        hasVideo = hasVideo,
        imageW = facts?.displayWidth ?: 0,
        imageH = facts?.displayHeight ?: 0,
        flight = flight,
        video = { m -> VideoSurface(vm, m) },
        onSelect = { vm.review.select(landings[it].eventId) },
        onBack = onBack,
    )
}

@UnstableApi
@Composable
private fun VideoSurface(vm: ReplayViewModel, modifier: Modifier) {
    val player by vm.controller.playerFlow.collectAsState()
    ContentFrame(player = player, modifier = modifier.semantics { contentDescription = "Your match video, frozen on the landing" })
}

/** Everything on screen. [video] draws the shared player into the box it is given; null content is never passed when [hasVideo] is false. */
@Composable
fun InstantReviewScreen(
    landings: List<ReviewLanding>,
    index: Int,
    matchId: String?,
    hasVideo: Boolean,
    imageW: Int,
    imageH: Int,
    flight: List<Pair<Float, Float>>,
    video: @Composable (Modifier) -> Unit,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val landing = landings[index]
    val call = landing.call
    val beats = remember(call.verdict, hasVideo) { beatsFor(call.verdict, hasVideo) }
    // The first beat is where a review starts; a new landing starts over.
    var beatIdx by rememberSaveable(landing.eventId, hasVideo) { mutableIntStateOf(0) }
    val beat = beats[beatIdx.coerceIn(0, beats.lastIndex)]
    val goTo = { b: Beat -> beatIdx = beats.indexOf(b).coerceAtLeast(0) }

    val stage: @Composable (Modifier) -> Unit = { m ->
        Stage(landing, beat, hasVideo, imageW, imageH, video, m)
    }
    val story: @Composable () -> Unit = {
        BeatStory(landing, beat, beats, matchId, flight, onNext = { goTo(beats[(beats.indexOf(beat) + 1).coerceAtMost(beats.lastIndex)]) }, onReplay = { goTo(beats.first()) }, onJump = goTo)
        if (landings.size > 1) LandingSwitcher(index, landings.size, onSelect)
    }
    val showStage = hasVideo || call.verdict != Verdict.NotSeen

    PeachPage {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            val paneMaxHeight = (maxHeight - 72.dp).coerceAtLeast(160.dp)
            Column(Modifier.fillMaxSize()) {
                ElevateHeader("Instant Review", onBack = onBack, subtitle = "Landing at ${ReviewSteps.clock(landing.tMs)}")
                if (landscape && showStage) {
                    Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            stage(Modifier.fillMaxWidth().heightIn(max = paneMaxHeight))
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = ScreenSpacing) { story() }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
                        verticalArrangement = ScreenSpacing,
                    ) {
                        if (showStage) stage(Modifier.fillMaxWidth())
                        story()
                    }
                }
            }
        }
    }
}

// ---- the stage: the frozen frame that dives into the line view ---------------------------------------

@Composable
private fun Stage(
    landing: ReviewLanding,
    beat: Beat,
    hasVideo: Boolean,
    imageW: Int,
    imageH: Int,
    video: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    val call = landing.call
    val reduce = rememberReduceMotion()
    val showsLine = call.verdict != Verdict.NotSeen
    // 0 = the frozen frame, 1 = the magnified line. One number drives the whole dive.
    val target = if (!hasVideo || (showsLine && beat != Beat.Moment)) 1f else 0f
    val dive by animateFloatAsState(target, tween(if (reduce) 0 else 1500, easing = LinearEasing), label = "review-dive")
    val ring = showsLine && landing.imageXy != null && imageW > 0

    val desc = when (beat) {
        Beat.Moment -> "The video, frozen on the frame where the shuttle touches the floor"
        Beat.Line -> "A magnified top-down view of the line with the landing spot, its margin of error and a centimetre ruler"
        Beat.Result -> if (showsLine) LineCallText.plain(call) else "The video, frozen on the landing"
    }
    VideoCard(modifier.aspectRatio(3f / 2f).semantics { contentDescription = desc }) {
        if (hasVideo && dive < 0.999f) {
            // The frame zooms toward the real image point as the dive starts, then gives way to the line view.
            val fx = if (landing.imageXy != null && imageW > 0) (landing.imageXy.first / imageW).toFloat().coerceIn(0f, 1f) else 0.5f
            val fy = if (landing.imageXy != null && imageH > 0) (landing.imageXy.second / imageH).toFloat().coerceIn(0f, 1f) else 0.5f
            val k = (dive / 0.45f).coerceIn(0f, 1f)
            val fade = ((dive - 0.2f) / 0.3f).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1f + 3f * k * k
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(fx, fy)
                    alpha = 1f - fade
                },
            ) {
                video(Modifier.fillMaxSize())
                if (ring) LandingRing(landing.imageXy!!.first.toFloat(), landing.imageXy.second.toFloat(), imageW, imageH)
            }
        }
        if (showsLine && dive > 0.01f) {
            val appear = ((dive - 0.2f) / 0.3f).coerceIn(0f, 1f)
            val zoom = ((dive - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val reveal = ((dive - 0.7f) / 0.3f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize().alpha(appear)) { LineView(call, zoom, reveal) }
        }
        val chip = when {
            beat == Beat.Moment && hasVideo -> "Frozen on the landing · ${ReviewSteps.clock(landing.tMs)}"
            showsLine && beat != Beat.Moment -> "Top-down view · line shown at its real 4 cm width"
            else -> null
        }
        if (chip != null) VideoChip(chip, Modifier.align(Alignment.TopStart).padding(12.dp), icon = if (beat == Beat.Moment) ElevateIcons.Film else null)
    }
}

/** A pulsing ring on the real image point of the landing (a still ring when motion is reduced). */
@Composable
private fun LandingRing(x: Float, y: Float, imageW: Int, imageH: Int) {
    val reduce = rememberReduceMotion()
    val pulse = if (reduce) 0.5f else {
        val t = rememberInfiniteTransition(label = "ring")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "ring-pulse")
        v
    }
    Canvas(Modifier.fillMaxSize()) {
        val fit = FitTransform.of(imageW, imageH, size.width, size.height) ?: return@Canvas
        val c = Offset(fit.mapX(x), fit.mapY(y))
        val dp = 1.dp.toPx()
        val glow = Color(0xFFFFE9A0)
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.5f), glow.copy(alpha = 0f)), center = c, radius = 34 * dp), radius = 34 * dp, center = c)
        // The expanding wave.
        val r = (12f + 22f * pulse) * dp
        drawCircle(Color.White.copy(alpha = 0.9f * (1f - pulse)), radius = r, center = c, style = Stroke(width = 2.5f * dp))
        // The steady ring on the point itself.
        drawCircle(Color(0xB3000000), radius = 11 * dp, center = c, style = Stroke(width = 4.5f * dp))
        drawCircle(Color.White, radius = 11 * dp, center = c, style = Stroke(width = 2.5f * dp))
        drawCircle(Color(0xFFFF7A3D), radius = 2.5f * dp, center = c)
    }
}

// ---- the story under the stage ----------------------------------------------------------------------

@Composable
private fun BeatStory(
    landing: ReviewLanding,
    beat: Beat,
    beats: List<Beat>,
    matchId: String?,
    flight: List<Pair<Float, Float>>,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onJump: (Beat) -> Unit,
) {
    val c = Elevate.colors
    val call = landing.call
    if (beats.size > 1) {
        ExplainBox(ReviewExplanations.Steps, Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                beats.forEach { b -> PillChip(b.label, selected = b == beat, onClick = { onJump(b) }, modifier = Modifier.weight(1f)) }
            }
        }
    }
    when (beat) {
        Beat.Moment -> {
            Text(
                if (call.verdict == Verdict.NotSeen) "Frozen on the moment the engine thinks the shuttle landed." else LineCallText.MOMENT_CAPTION,
                style = MaterialTheme.typography.headlineSmall,
                color = c.ink,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            Text(
                "This is your own video, stopped on the frame closest to the landing. " +
                    if (landing.state == "candidate") "The engine marked it as a candidate landing, so treat it as an estimate." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
            )
            PrimaryButton(if (call.verdict == Verdict.NotSeen) "See the call" else "Zoom to the line", onNext)
        }
        Beat.Line -> {
            Text("Zoomed in on the ${call.line?.plainName ?: "line"}", style = MaterialTheme.typography.headlineSmall, color = c.ink,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(
                "The line is drawn at its real width, 4 cm. The ruler counts centimetres from its outer edge, and a shuttle touching the line counts as in. " +
                    "The shaded oval is our margin of error: the landing is very likely somewhere inside it.",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            PrimaryButton("See the call", onNext)
        }
        Beat.Result -> ResultStory(landing, matchId, flight, onReplay)
    }
}

@Composable
private fun ResultStory(landing: ReviewLanding, matchId: String?, flight: List<Pair<Float, Float>>, onReplay: () -> Unit) {
    val c = Elevate.colors
    val call = landing.call
    val haptics = rememberHaptics()
    // One buzz when the verdict lands.
    LaunchedEffect(landing.eventId) { if (call.verdict == Verdict.TooClose || call.verdict == Verdict.NotSeen) haptics.tick() else haptics.confirm() }

    PopIn(Modifier.fillMaxWidth()) { VerdictCard(call) }
    if (call.verdict != Verdict.NotSeen) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Gap to the line", "${call.gapCm} cm", Modifier.weight(1f), ReviewExplanations.Measure)
            StatTile("Margin of error", "±${call.marginCm} cm", Modifier.weight(1f), ReviewExplanations.Margin)
        }
    }
    LineCallText.note(call)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
    HowWeKnow(call, flight)
    if (call.verdict != Verdict.NotSeen) YourCallCard(matchId, landing.eventId)
    Text(
        LineCallText.SMALL_PRINT,
        style = MaterialTheme.typography.labelMedium,
        color = c.inkMuted,
        modifier = Modifier.fillMaxWidth(),
    )
    SecondaryButton("Replay the zoom", onReplay, Modifier.fillMaxWidth())
}

@Composable
private fun VerdictCard(call: LineCall) {
    val colors = VerdictPalette.of(call.verdict)
    val c = Elevate.colors
    val bold = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, color = c.ink)
    val text = buildAnnotatedString {
        for ((s, isBold) in ReviewSteps.runs(LineCallText.markup(call))) {
            if (isBold) withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = colors.solid)) { append(s) } else append(s)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.soft).padding(20.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${LineCallText.badge(call.verdict)}. ${LineCallText.plain(call)}"; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(colors.solid), contentAlignment = Alignment.Center) {
                Icon(ReviewIcons.forVerdict(call.verdict), contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Column {
                Text(LineCallText.badge(call.verdict), style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp, lineHeight = 40.sp), color = colors.solid)
                Text("Instant Review", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
        }
        Text(text, style = bold)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, explain: com.elevate.app.ui.components.Explanation) {
    val c = Elevate.colors
    ExplainBox(explain, modifier) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = c.ink)
        }
    }
}

// ---- how we know ------------------------------------------------------------------------------------

@Composable
private fun HowWeKnow(call: LineCall, flight: List<Pair<Float, Float>>) {
    val c = Elevate.colors
    val reduce = rememberReduceMotion()
    var open by rememberSaveable { mutableStateOf(true) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(if (reduce) 0 else 200), label = "chevron")
    ElevateCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Hide how we know" else "Show how we know", role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ReviewSteps.TITLE, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                Text("3 steps, from your taps to the line", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
            Icon(ElevateIcons.ChevronDown, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(
            open,
            enter = if (reduce) fadeIn(tween(0)) else expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = if (reduce) fadeOut(tween(0)) else shrinkVertically(tween(200)) + fadeOut(tween(200)),
        ) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProofStep(1, ReviewSteps.corners(call.tier), ReviewExplanations.Corners) { CornersPicture(call) }
                ProofStep(2, ReviewSteps.FLIGHT, ReviewExplanations.Flight) { FlightPicture(flight) }
                ProofStep(3, ReviewSteps.MEASURE, ReviewExplanations.Measure) { if (call.line != null) LineView(call, 1f, 1f, compact = true) }
            }
        }
    }
}

@Composable
private fun ProofStep(n: Int, text: String, explain: com.elevate.app.ui.components.Explanation, picture: @Composable () -> Unit) {
    val c = Elevate.colors
    ExplainBox(explain, Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics(mergeDescendants = true) { contentDescription = "Step $n. $text" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(84.dp, 72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF173B32)), contentAlignment = Alignment.Center) { picture() }
            Column(Modifier.weight(1f)) {
                Text("Step $n", style = MaterialTheme.typography.labelMedium, color = c.orangeText)
                Text(text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
        }
    }
}

/** The court from the template, the four tapped corners as dots, and the landing spot. Real template, real landing. */
@Composable
private fun CornersPicture(call: LineCall) {
    val xy = call.xyM
    val box = call.box
    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val x0 = box?.minX ?: 0.0
        val x1 = box?.maxX ?: 6.10
        val courtW = (x1 - x0).toFloat()
        val courtH = 13.40f
        val s = minOf(size.width / courtW, size.height / courtH)
        val ox = (size.width - courtW * s) / 2
        val oy = (size.height - courtH * s) / 2
        val dp = 1.dp.toPx()
        // The tapped court in the template's frame, far end up.
        fun p(x: Double, y: Double) = Offset(ox + (x - x0).toFloat() * s, oy + (courtH - y.toFloat()) * s)
        val line = Color.White.copy(alpha = 0.85f)
        val corners = listOf(p(x0, 0.0), p(x1, 0.0), p(x1, 13.4), p(x0, 13.4))
        for (i in 0 until 4) drawLine(line, corners[i], corners[(i + 1) % 4], strokeWidth = 1.2f * dp)
        drawLine(line.copy(alpha = 0.5f), p(x0, 6.7), p(x1, 6.7), strokeWidth = 1f * dp)
        corners.forEach { drawCircle(Color(0xFFFF7A3D), 3f * dp, it); drawCircle(Color.White, 3f * dp, it, style = Stroke(1f * dp)) }
        if (xy != null) drawCircle(Color(0xFFFFD866), 2.5f * dp, p(xy.first, xy.second))
    }
}

/** The real last observed positions of the shuttle, as a path ending where it stopped. Nothing is drawn invented. */
@Composable
private fun FlightPicture(flight: List<Pair<Float, Float>>) {
    if (flight.isEmpty()) {
        Icon(ElevateIcons.Trail, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(32.dp))
        return
    }
    Canvas(Modifier.fillMaxSize().padding(10.dp)) {
        val s = minOf(size.width, size.height)
        val ox = (size.width - s) / 2
        val oy = (size.height - s) / 2
        val dp = 1.dp.toPx()
        val pts = flight.map { Offset(ox + it.first * s, oy + it.second * s) }
        for (i in 1 until pts.size) drawLine(Color.White.copy(alpha = 0.35f + 0.5f * i / pts.size), pts[i - 1], pts[i], strokeWidth = 1.5f * dp)
        pts.forEachIndexed { i, o -> if (i < pts.lastIndex) drawCircle(Color.White.copy(alpha = 0.9f), 2f * dp, o) }
        drawCircle(Color(0xFFFF7A3D), 4f * dp, pts.last())
        drawCircle(Color.White, 4f * dp, pts.last(), style = Stroke(1.2f * dp))
    }
}

// ---- your call --------------------------------------------------------------------------------------

@Composable
private fun YourCallCard(matchId: String?, eventId: String) {
    val c = Elevate.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var saved by remember(matchId, eventId) { mutableStateOf(ReviewCalls.get(context, matchId, eventId)) }
    ExplainBox(ReviewExplanations.YourCall, Modifier.fillMaxWidth()) {
        ElevateCard {
            Text("Your call", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(ReviewSteps.CALL_QUESTION, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YourCall.entries.forEach { choice ->
                    PillChip(
                        if (choice == YourCall.Agree) "Agree" else "Disagree",
                        selected = saved == choice,
                        onClick = {
                            haptics.confirm()
                            saved = choice
                            ReviewCalls.save(context, matchId, eventId, choice)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (saved != null) {
                Spacer(Modifier.height(8.dp))
                Text(ReviewSteps.CALL_SAVED, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
private fun LandingSwitcher(index: Int, total: Int, onSelect: (Int) -> Unit) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton("Previous", { onSelect(index - 1) }, Modifier.weight(1f), enabled = index > 0)
        Text("Landing ${index + 1} of $total", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        SecondaryButton("Next", { onSelect(index + 1) }, Modifier.weight(1f), enabled = index < total - 1)
    }
}

// ---- entry from the Court tab ------------------------------------------------------------------------

/**
 * The Court tab's way in: one "Review a landing" button and a row per landing. Hidden when the match has no
 * landing with a court position. A row shows only the time: the call is shown by the review, not spoiled here.
 */
@Composable
fun ReviewEntry(landings: List<ReviewLanding>, onReview: (eventId: String) -> Unit, modifier: Modifier = Modifier) {
    if (landings.isEmpty()) return
    val c = Elevate.colors
    ElevateCard(modifier, tint = c.lavender) {
        Text("Line calls", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(
            "Pick a landing and watch the evidence: the frozen moment, a zoom to the line, and a measured gap.",
            style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        // Start on the most interesting one: a close call if there is one.
        val first = landings.firstOrNull { it.call.verdict == Verdict.TooClose } ?: landings.first()
        PrimaryButton("Review a landing", { onReview(first.eventId) })
        Spacer(Modifier.height(8.dp))
        landings.forEachIndexed { i, l ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Review landing ${i + 1}", role = Role.Button) { onReview(l.eventId) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Landing ${i + 1} · ${ReviewSteps.clock(l.tMs)}", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
                Text("Review", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.orangeText)
            }
        }
    }
}
