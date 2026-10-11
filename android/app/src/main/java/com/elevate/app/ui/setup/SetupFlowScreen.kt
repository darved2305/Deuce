package com.elevate.app.ui.setup

import com.elevate.app.ui.components.ElevateRadius
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.elevate.app.domain.calibration.CourtGeometry
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevate.app.domain.calibration.Corner
import com.elevate.app.domain.calibration.CourtCheck
import com.elevate.app.domain.calibration.FitTransform
import com.elevate.app.domain.calibration.ImagePoint
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.Explanation
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PillChip
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.components.SectionTitle
import com.elevate.app.ui.components.StepCount
import com.elevate.app.ui.components.VideoCard
import com.elevate.app.ui.components.VideoChip
import com.elevate.app.ui.theme.Elevate
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Set up analysis" in two screens, tapped on a real still frame of [uri] at [frameUs]:
 * 1/2 the court (corners, sport, format; free play: the ball), 2/2 who are you.
 * "Analyse match" on the second screen calls [onDone] with exactly the session fields this flow
 * owns (contracts §2), in upright source pixels. Back on the first screen calls [onCancel].
 */
@Composable
fun SetupFlowScreen(
    uri: Uri,
    facts: SourceFacts,
    frameUs: Long,
    onCancel: () -> Unit,
    onDone: (SetupResult) -> Unit,
) {
    // One setup ViewModel per activity: load() resets it for a new (uri, frame). A key per frame kept
    // every grabbed frame bitmap (up to 1920 px, ~8 MB) alive until the activity died.
    val vm: SetupViewModel = viewModel(key = "setup")
    LaunchedEffect(uri, frameUs) { vm.load(uri, facts, frameUs) }
    SetupFlowBody(vm, onCancel, onDone)
}

/**
 * The same flow on a still that is not part of a stored video: the live camera view captured by
 * Record live ([bitmap] is already the recording's size, [srcW] x [srcH]). [stillKey] changes when
 * a new still is captured.
 */
@Composable
fun SetupFlowScreen(
    bitmap: android.graphics.Bitmap,
    srcW: Int,
    srcH: Int,
    stillKey: Long,
    onCancel: () -> Unit,
    onDone: (SetupResult) -> Unit,
) {
    val vm: SetupViewModel = viewModel(key = "setup")
    LaunchedEffect(stillKey) { vm.loadStill(bitmap, srcW, srcH, stillKey) }
    SetupFlowBody(vm, onCancel, onDone)
}

@Composable
private fun SetupFlowBody(
    vm: SetupViewModel,
    onCancel: () -> Unit,
    onDone: (SetupResult) -> Unit,
) {
    val frame by vm.frame.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    val s = state
    BackHandler {
        if (s != null && s.steps.indexOf(s.step) > 0) vm.dispatch(SetupAction.Back) else onCancel()
    }

    PeachPage {
        when (val f = frame) {
            SetupFrame.Loading -> Message("Reading a frame of your video…", busy = true, onCancel = onCancel)
            is SetupFrame.Failed -> Message(
                if (f.code == "no_access") f.message else "Couldn't read a still frame from this video",
                busy = false,
                onCancel = onCancel,
            )
            is SetupFrame.Ready -> if (s == null) {
                Message("Reading a frame of your video…", busy = true, onCancel = onCancel)
            } else {
                Flow(f, s, vm::dispatch, onCancel, onDone)
            }
        }
    }
}

@Composable
private fun Flow(
    f: SetupFrame.Ready,
    s: SetupState,
    dispatch: (SetupAction) -> Unit,
    onCancel: () -> Unit,
    onDone: (SetupResult) -> Unit,
) {
    // Which corner is under the finger: drives the loupe and the highlighted corner chip.
    val dragging = remember { mutableStateOf<Corner?>(null) }
    val i = s.steps.indexOf(s.step)
    val header: @Composable () -> Unit = {
        ElevateHeader(
            title = s.title(),
            onBack = { if (i > 0) dispatch(SetupAction.Back) else onCancel() },
            trailing = { StepCount(i + 1, s.steps.size) },
        )
    }
    val ratio = (f.srcW.toFloat() / f.srcH.toFloat()).takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    header()
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(start = ScreenGutter, end = 8.dp, bottom = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Hero(f, s, dispatch, dragging, Modifier.aspectRatio(ratio)) }
                }
                Column(Modifier.width(360.dp).fillMaxHeight().padding(end = ScreenGutter, top = 8.dp, bottom = 16.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { Controls(s, dispatch, dragging.value) }
                    Bottom(s, dispatch, onDone, Modifier.padding(start = 12.dp, top = 12.dp))
                }
            }
        } else {
            val heroMax = maxHeight * 0.46f
            Column(Modifier.fillMaxSize()) {
                header()
                // The frame stays pinned: taps and drags land on it while only the controls scroll
                // (a scrolling frame slid under the header mid-tap on the A55).
                Box(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
                    Hero(f, s, dispatch, dragging, Modifier.heightIn(max = heroMax).aspectRatio(ratio))
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Controls(s, dispatch, dragging.value)
                }
                Bottom(s, dispatch, onDone, Modifier.padding(horizontal = ScreenGutter, vertical = 12.dp))
            }
        }
    }
}

private fun SetupState.title() = when (step) {
    Step.Court -> "Set up the court"
    Step.Ball -> "Mark the ball"
    Step.WhoIsMe -> "Who are you?"
}

/** The real frame as a rounded hero card, with the tapped points and a plain-language chip. */
@Composable
private fun Hero(
    f: SetupFrame.Ready,
    s: SetupState,
    dispatch: (SetupAction) -> Unit,
    dragging: MutableState<Corner?>,
    modifier: Modifier,
) {
    VideoCard(modifier) {
        Picture(f, s, dispatch, dragging, Modifier.fillMaxSize())
        ExplainBox(
            when (s.step) {
                Step.Court -> Explanations.CourtCorners
                Step.Ball -> Explanations.Ball
                Step.WhoIsMe -> Explanations.ThisIsMe
            },
            Modifier
                .align(if (s.step == Step.Court) Alignment.BottomStart else Alignment.TopStart)
                .padding(12.dp),
        ) {
            VideoChip(s.instruction(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** The real frame with fit scaling, the tapped points, and a loupe while a corner is dragged. */
@Composable
private fun Picture(
    f: SetupFrame.Ready,
    s: SetupState,
    dispatch: (SetupAction) -> Unit,
    dragging: MutableState<Corner?>,
    modifier: Modifier,
) {
    val c = Elevate.colors
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val viewW = constraints.maxWidth.toDouble()
        val viewH = constraints.maxHeight.toDouble()
        // Free space around the picture, so a corner dragged just past its edge stays on the canvas.
        val padPx = with(density) { 40.dp.toPx() }.toDouble()
        val t = remember(viewW, viewH, f.srcW, f.srcH, padPx) { FitTransform(viewW, viewH, f.srcW.toDouble(), f.srcH.toDouble(), padPx) }
        val current by rememberUpdatedState(s)
        val hitPx = with(density) { 32.dp.toPx() }

        val gestures = Modifier.pointerInput(t) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val st = current
                // Grab the nearest placed corner within reach; anything else is a tap.
                val hit = if (st.step != Step.Court) null else st.corners.entries
                    .map { (k, p) -> val (x, y) = t.toView(p); k to hypot(x - down.position.x, y - down.position.y) }
                    .filter { it.second <= hitPx }
                    .minByOrNull { it.second }?.first
                if (hit != null) {
                    down.consume()
                    val (hx, hy) = t.toView(st.corners.getValue(hit))
                    // Keep the finger's offset from the handle so it does not jump under the finger.
                    val dx = hx - down.position.x
                    val dy = hy - down.position.y
                    dispatch(SetupAction.DragStart(hit))
                    dragging.value = hit
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            // Not pinned to the frame: near corners are often really below or beside it.
                            // The handle itself stays on the canvas so it can always be grabbed again.
                            val vx = (ch.position.x + dx).toDouble().coerceIn(0.0, viewW)
                            val vy = (ch.position.y + dy).toDouble().coerceIn(0.0, viewH)
                            dispatch(SetupAction.Drag(hit, t.toSourceExtended(vx, vy)))
                            ch.consume()
                        }
                    } finally {
                        dragging.value = null
                    }
                } else {
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        // Taps in the letterbox bars are outside the picture and ignored.
                        t.toSource(up.position.x.toDouble(), up.position.y.toDouble())?.let { dispatch(SetupAction.Tap(it)) }
                    }
                }
            }
        }

        val drag = dragging.value
        Canvas(
            Modifier
                .fillMaxSize()
                .then(gestures)
                .semantics { contentDescription = "Frame at ${clock(f.frameMs)} of your video. ${s.instruction()}" },
        ) {
            drawImage(
                f.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(f.image.width, f.image.height),
                dstOffset = IntOffset(t.offsetX.roundToInt(), t.offsetY.roundToInt()),
                dstSize = IntSize((f.srcW * t.scale).roundToInt(), (f.srcH * t.scale).roundToInt()),
            )
            if (s.sport != Sport.FreePlay) drawCourt(s, t, c.orange, active = s.step == Step.Court, f.srcW.toDouble(), f.srcH.toDouble())
            s.ball?.let { if (s.sport == Sport.FreePlay) drawMarker(t.toView(it), Color(0xFFE0A100)) }
            s.me?.let { drawMarker(t.toView(it), c.orange) }
            drag?.let { k -> s.corners[k]?.let { drawLoupe(f, t, it, c.orange) } }
        }

        // Each handle is named on screen (and to TalkBack) while the corners are being set.
        if (s.sport != Sport.FreePlay && s.step == Step.Court) {
            for ((k, p) in s.corners) {
                val (x, y) = t.toView(p)
                val off = CourtGeometry.isOffFrame(p, f.srcW.toDouble(), f.srcH.toDouble())
                PointLabel(k.label, Color(0xCC1C1F45), x, y, if (off) "${k.label} corner, outside the picture" else "${k.label} corner")
                if (off) OffFrameTag(x, y)
            }
        }
        s.ball?.takeIf { s.sport == Sport.FreePlay }?.let { val (x, y) = t.toView(it); PointLabel("Ball", Color(0xFF8A5A00), x, y, "Ball") }
        s.me?.let { val (x, y) = t.toView(it); PointLabel("You", c.orange, x, y, "You") }
    }
}

@Composable
private fun PointLabel(text: String, color: Color, x: Double, y: Double, description: String) {
    val d = LocalDensity.current
    val dx = with(d) { 14.dp.toPx() }
    val dy = with(d) { 38.dp.toPx() }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier
            .offset { IntOffset((x + dx).roundToInt(), (y - dy).roundToInt()) }
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .semantics { contentDescription = description },
    )
}

/** Small "off-frame" tag under a handle that sits outside the picture. */
@Composable
private fun OffFrameTag(x: Double, y: Double) {
    val d = LocalDensity.current
    val dx = with(d) { 16.dp.toPx() }
    val dy = with(d) { 14.dp.toPx() }
    Text(
        "off-frame",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .offset { IntOffset((x + dx).roundToInt(), (y + dy).roundToInt()) }
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clearAndSetSemantics { },
    )
}

/**
 * Orange quad joining the placed corners, with orange-ringed white handles. A corner outside the
 * picture gets a dashed ring with a hollow centre, so it reads as an estimate.
 */
private fun DrawScope.drawCourt(s: SetupState, t: FitTransform, orange: Color, active: Boolean, srcW: Double, srcH: Double) {
    val placed = Corner.entries.mapNotNull { k -> s.corners[k]?.let { k to t.toView(it) } }
    val pts = placed.map { it.second }
    val line = 2.5.dp.toPx()
    for (i in 0 until pts.size - 1) segment(pts[i], pts[i + 1], line, orange)
    if (pts.size == 4) segment(pts[3], pts[0], line, orange)
    if (!active) return
    for ((k, p) in placed) {
        val o = Offset(p.first.toFloat(), p.second.toFloat())
        if (CourtGeometry.isOffFrame(s.corners.getValue(k), srcW, srcH)) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 13.dp.toPx(), center = o)
            drawCircle(Color.White, radius = 11.dp.toPx(), center = o, style = Stroke(width = 4.dp.toPx(), pathEffect = dash))
            drawCircle(orange, radius = 11.dp.toPx(), center = o, style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
            drawCircle(orange, radius = 3.dp.toPx(), center = o)
        } else {
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 13.dp.toPx(), center = o)
            drawCircle(orange, radius = 11.dp.toPx(), center = o)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = o)
            drawCircle(orange, radius = 3.dp.toPx(), center = o)
        }
    }
}

private fun DrawScope.segment(a: Pair<Double, Double>, b: Pair<Double, Double>, width: Float, color: Color) {
    val oa = Offset(a.first.toFloat(), a.second.toFloat())
    val ob = Offset(b.first.toFloat(), b.second.toFloat())
    drawLine(Color.Black.copy(alpha = 0.3f), oa, ob, strokeWidth = width * 2)
    drawLine(color, oa, ob, strokeWidth = width)
}

private fun DrawScope.drawMarker(p: Pair<Double, Double>, color: Color) {
    val o = Offset(p.first.toFloat(), p.second.toFloat())
    drawCircle(Color.White, radius = 18.dp.toPx(), center = o, style = Stroke(width = 6.dp.toPx()))
    drawCircle(color, radius = 18.dp.toPx(), center = o, style = Stroke(width = 3.5.dp.toPx()))
    drawCircle(color, radius = 3.dp.toPx(), center = o)
}

/**
 * A 3× loupe of the real frame around source point [p], in a white ring above the finger (below
 * it near the top edge), with a crosshair on the exact point. Drawn from the frame bitmap itself.
 */
private fun DrawScope.drawLoupe(f: SetupFrame.Ready, t: FitTransform, p: ImagePoint, color: Color) {
    val zoom = 3.0
    val r = 52.dp.toPx()
    val (vx, vy) = t.toView(p)
    val gap = 92.dp.toPx()
    val cy = if (vy - gap - r >= 0) vy - gap else vy + gap
    val cx = vx.coerceIn(r.toDouble(), size.width - r.toDouble())
    val center = Offset(cx.toFloat(), cy.toFloat())
    val bw = f.image.width.toDouble()
    val bh = f.image.height.toDouble()
    // Bitmap px per view px at fit scale; the loupe shows (2r / zoom) view px of picture.
    val bmpPerView = bw / (f.srcW * t.scale)
    val half = r / zoom * bmpPerView
    val bx = p.x * bw / f.srcW
    val by = p.y * bh / f.srcH
    val sx0 = max(0.0, bx - half); val sx1 = min(bw, bx + half)
    val sy0 = max(0.0, by - half); val sy1 = min(bh, by + half)
    val dstPerBmp = r / half // view px per bitmap px inside the loupe
    val circle = Path().apply { addOval(Rect(center, r)) }
    drawCircle(Color.Black.copy(alpha = 0.3f), radius = r + 4.dp.toPx(), center = center)
    drawCircle(Color.Black, radius = r, center = center)
    if (sx1 > sx0 && sy1 > sy0) {
        clipPath(circle) {
            drawImage(
                f.image,
                srcOffset = IntOffset(sx0.toInt(), sy0.toInt()),
                srcSize = IntSize(max(1, (sx1 - sx0).toInt()), max(1, (sy1 - sy0).toInt())),
                dstOffset = IntOffset((cx + (sx0 - bx) * dstPerBmp).roundToInt(), (cy + (sy0 - by) * dstPerBmp).roundToInt()),
                dstSize = IntSize(((sx1 - sx0) * dstPerBmp).roundToInt(), ((sy1 - sy0) * dstPerBmp).roundToInt()),
            )
        }
    }
    val arm = 16.dp.toPx()
    val gapIn = 5.dp.toPx()
    val w = 1.5.dp.toPx()
    drawLine(Color.White, center - Offset(arm, 0f), center - Offset(gapIn, 0f), strokeWidth = w)
    drawLine(Color.White, center + Offset(gapIn, 0f), center + Offset(arm, 0f), strokeWidth = w)
    drawLine(Color.White, center - Offset(0f, arm), center - Offset(0f, gapIn), strokeWidth = w)
    drawLine(Color.White, center + Offset(0f, gapIn), center + Offset(0f, arm), strokeWidth = w)
    drawCircle(color, radius = 3.dp.toPx(), center = center)
    drawCircle(Color.White, radius = r, center = center, style = Stroke(width = 4.dp.toPx()))
}

/** Everything under the frame, per step. */
@Composable
private fun ColumnScope.Controls(s: SetupState, dispatch: (SetupAction) -> Unit, dragging: Corner?) {
    when (s.step) {
        Step.Court -> {
            (s.courtCheck as? CourtCheck.Problem)?.takeIf { it.code != "incomplete" }?.let { Warning(it.text) }
            CornersSection(s, dispatch, dragging)
            SportSection(s, dispatch)
        }
        Step.Ball -> {
            SportSection(s, dispatch)
            MarkCard("Ball", s.ball != null, s.frameMs, onClear = { dispatch(SetupAction.Reset) })
        }
        Step.WhoIsMe -> MarkCard("You", s.me != null, s.frameMs, onClear = { dispatch(SetupAction.Reset) })
    }
}

/** Soft-peach card for a court problem, in the check's own words. */
@Composable
private fun Warning(text: String) {
    val c = Elevate.colors
    ElevateCard(tint = c.peach) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(c.orange), contentAlignment = Alignment.Center) {
                Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.orangeText)
        }
    }
}

@Composable
private fun CornersSection(s: SetupState, dispatch: (SetupAction) -> Unit, dragging: Corner?) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ExplainBox(Explanations.CourtCorners, Modifier.weight(1f)) {
            Text("Corners", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).semantics { heading() })
        }
        SmallPill("Undo", enabled = s.canUndo) { dispatch(SetupAction.Undo) }
        Spacer(Modifier.width(8.dp))
        SmallPill("Reset", enabled = s.corners.isNotEmpty()) { dispatch(SetupAction.Reset) }
    }
    val active = dragging ?: s.nextCorner
    // Laid out as seen from behind the near baseline: far corners on top.
    for (row in listOf(listOf(Corner.FarLeft, Corner.FarRight), listOf(Corner.NearLeft, Corner.NearRight))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (k in row) ExplainBox(Explanations.CourtCorners, Modifier.weight(1f)) { CornerChip(k, placed = k in s.corners, active = k == active) }
        }
    }
    s.courtHint()?.let { ExplainBox(Explanations.Magnifier, Modifier.fillMaxWidth()) { Text(it, style = MaterialTheme.typography.labelMedium, color = c.inkMuted) } }
    if (s.offFrameCorners > 0) {
        ExplainBox(Explanations.OffFrameCorner, Modifier.fillMaxWidth()) {
            Text(
                "${s.offFrameCorners} ${if (s.offFrameCorners == 1) "corner" else "corners"} out of view (estimated)",
                style = MaterialTheme.typography.labelMedium,
                color = c.inkMuted,
            )
        }
    }
}

@Composable
private fun CornerChip(k: Corner, placed: Boolean, active: Boolean) {
    val c = Elevate.colors
    val shape = RoundedCornerShape(ElevateRadius.Inner)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (active) c.peach else Color.White)
            .border(if (active) 1.5.dp else 1.dp, if (active) c.orange else c.line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${k.label} corner: ${if (placed) "placed" else if (active) "tap it next" else "not placed"}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (placed) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(c.orange), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
            }
        } else {
            Box(Modifier.size(16.dp).border(2.dp, if (active) c.orange else c.inkMuted.copy(alpha = 0.5f), CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Text(k.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
    }
}

@Composable
private fun SportSection(s: SetupState, dispatch: (SetupAction) -> Unit) {
    val c = Elevate.colors
    ExplainBox(Explanations.Sport, Modifier.fillMaxWidth()) { SectionTitle("Sport") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Only sports that can be analysed are offered: no "soon" pills.
        for (sport in Sport.entries.filter { s.isEnabled(it) }) {
            PillChip(
                text = sport.label,
                selected = sport == s.sport,
                enabled = true,
                onClick = { dispatch(SetupAction.SelectSport(sport)) },
            )
        }
    }
    val formats = SetupCatalog.formats(s.sport)
    if (formats.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ExplainBox(Explanations.Format, Modifier.weight(1f)) { Text("Format", style = MaterialTheme.typography.titleMedium, color = c.ink) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (fmt in formats) PillChip(fmt.label, selected = fmt == s.format, onClick = { dispatch(SetupAction.SelectFormat(fmt)) })
            }
        }
    }
}

/** One tapped point ("You", "Ball"): marked or not, on which frame, and a way to clear it. */
@Composable
private fun MarkCard(name: String, marked: Boolean, frameMs: Long, onClear: () -> Unit) {
    val c = Elevate.colors
    ExplainBox(if (name == "You") Explanations.ThisIsMe else Explanations.Ball, Modifier.fillMaxWidth()) {
    ElevateCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (marked) c.orange else c.line),
                contentAlignment = Alignment.Center,
            ) { Text(name.take(1), color = if (marked) Color.White else c.inkMuted, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(
                    if (marked) "Marked at ${clock(frameMs)}" else "Not marked yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (marked) c.orangeText else c.inkMuted,
                )
            }
            SmallPill("Clear", enabled = marked, onClick = onClear)
        }
    }
    }
}

/** A compact white pill with a 48 dp touch target. */
@Composable
private fun SmallPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Elevate.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) c.ink else c.inkMuted.copy(alpha = 0.6f),
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, c.line, CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Summary (last step) and the one main action. A blocked action reads what is missing. */
@Composable
private fun Bottom(s: SetupState, dispatch: (SetupAction) -> Unit, onDone: (SetupResult) -> Unit, modifier: Modifier) {
    val c = Elevate.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.step == Step.WhoIsMe) {
            Text(
                s.summary(),
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            val result = s.result()
            ExplainBox(Explanations.Analyse, Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = if (result != null) "Analyse match" else s.blocker ?: "Tap yourself",
                    onClick = { result?.let(onDone) },
                    enabled = result != null,
                )
            }
        } else {
            ExplainBox(if (s.step == Step.Court) Explanations.CourtCorners else Explanations.Ball, Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = if (s.canNext) (if (s.step == Step.Court) "Looks right" else "Next") else s.shortBlocker(),
                    onClick = { dispatch(SetupAction.Next) },
                    enabled = s.canNext,
                )
            }
        }
    }
}

/** "Badminton singles · 4 corners · you at 0:41.26": only what was actually chosen and tapped. */
private fun SetupState.summary(): String = buildList {
    add(if (format == Format.None) sport.label else "${sport.label} ${format.label.lowercase()}")
    if (sport == Sport.FreePlay) {
        ball?.let { add("ball at ${clock(frameMs)}") }
    } else {
        add("${corners.size} corner${if (corners.size == 1) "" else "s"}")
    }
    me?.let { add("you at ${clock(frameMs)}") }
}.joinToString(" · ")

/** The court check's problem, short enough for the button. */
private fun SetupState.shortBlocker(): String {
    val p = courtCheck as? CourtCheck.Problem
    if (step != Step.Court || p == null) return blocker ?: "Next"
    return when (p.code) {
        "incomplete" -> (4 - corners.size).let { n -> if (n == 4) "Tap the 4 corners" else "Tap $n more corner${if (n == 1) "" else "s"}" }
        "too_close" -> "Two corners overlap"
        "crossed" -> "Corners cross over"
        "too_small" -> "Court too small"
        "left_right" -> "Left and right swapped"
        "near_far" -> "Near and far swapped"
        else -> p.text
    }
}

@Composable
private fun Message(text: String, busy: Boolean, onCancel: () -> Unit) {
    val c = Elevate.colors
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ElevateHeader(title = "Set up the court", onBack = onCancel)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.orange, strokeWidth = 2.5.dp)
                Spacer(Modifier.height(20.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!busy) {
                Spacer(Modifier.height(24.dp))
                SecondaryButton("Back", onClick = onCancel)
            }
        }
    }
}

/** 41260 ms → "0:41.26". */
private fun clock(ms: Long) = String.format(Locale.ROOT, "%d:%05.2f", ms / 60_000, (ms % 60_000) / 1000.0)
