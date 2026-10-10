package com.elevate.app.ui.setup

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevate.app.domain.calibration.Corner
import com.elevate.app.domain.calibration.CourtGeometry
import com.elevate.app.domain.calibration.FitTransform
import com.elevate.app.domain.calibration.ImagePoint
import com.elevate.app.domain.media.SourceFacts
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.theme.Elevate
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Handle colours: distinct hues, each ≥ 3:1 against white rings and the cream panel. */
private val CornerColors = mapOf(
    Corner.NearLeft to Color(0xFFC8481A),
    Corner.NearRight to Color(0xFF6656D2),
    Corner.FarRight to Color(0xFF2F7D3A),
    Corner.FarLeft to Color(0xFF0E6E8C),
)

/**
 * "Set up analysis": sport → court corners (or the ball, in free play) → who is you → review.
 * Everything is tapped on a real still frame of [uri] at [frameUs]; [onDone] receives exactly the
 * session fields this flow owns (contracts §2), in upright source pixels.
 */
@Composable
fun SetupFlowScreen(
    uri: Uri,
    facts: SourceFacts,
    frameUs: Long,
    onCancel: () -> Unit,
    onDone: (SetupResult) -> Unit,
) {
    val vm: SetupViewModel = viewModel(key = "setup:$uri@$frameUs")
    LaunchedEffect(uri, frameUs) { vm.load(uri, facts, frameUs) }
    val frame by vm.frame.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Elevate.colors

    val s = state
    BackHandler {
        if (s != null && s.steps.indexOf(s.step) > 0) vm.dispatch(SetupAction.Back) else onCancel()
    }

    Box(Modifier.fillMaxSize().background(c.cream)) {
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val picture: @Composable (Modifier) -> Unit = { m -> Picture(f, s, dispatch, m) }
        val panel: @Composable (Modifier) -> Unit = { m -> Panel(s, dispatch, onDone, m) }
        if (landscape) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(s, onCancel, dispatch)
                    picture(Modifier.weight(1f).fillMaxWidth())
                }
                panel(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(s, onCancel, dispatch)
                picture(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun TopBar(s: SetupState, onCancel: () -> Unit, dispatch: (SetupAction) -> Unit) {
    val c = Elevate.colors
    val i = s.steps.indexOf(s.step)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(c.peach)
                .clickable(
                    onClickLabel = if (i > 0) "Previous step" else "Cancel setup",
                    role = Role.Button,
                    onClick = { if (i > 0) dispatch(SetupAction.Back) else onCancel() },
                ),
            contentAlignment = Alignment.Center,
        ) { Icon(ElevateIcons.Back, contentDescription = if (i > 0) "Back" else "Cancel", tint = c.ink) }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Set up analysis", style = MaterialTheme.typography.titleMedium)
            Text("Step ${i + 1} of ${s.steps.size}", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
    }
}

/** The real frame with fit scaling, the tapped points, and a loupe while a corner is dragged. */
@Composable
private fun Picture(f: SetupFrame.Ready, s: SetupState, dispatch: (SetupAction) -> Unit, modifier: Modifier) {
    val c = Elevate.colors
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(c.court)) {
        val viewW = constraints.maxWidth.toDouble()
        val viewH = constraints.maxHeight.toDouble()
        // Free space around the picture, so a corner dragged just past its edge stays on the canvas.
        val padPx = with(density) { 40.dp.toPx() }.toDouble()
        val t = remember(viewW, viewH, f.srcW, f.srcH, padPx) { FitTransform(viewW, viewH, f.srcW.toDouble(), f.srcH.toDouble(), padPx) }
        var dragging by remember { mutableStateOf<Corner?>(null) }
        val current by rememberUpdatedState(s)
        val hitPx = with(density) { 32.dp.toPx() }

        val interactive = s.step == Step.Court || s.step == Step.Ball || s.step == Step.WhoIsMe
        val gestures = if (!interactive) Modifier else Modifier.pointerInput(t) {
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
                    dragging = hit
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
                        dragging = null
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

        Canvas(
            Modifier
                .fillMaxSize()
                .then(gestures)
                .semantics { contentDescription = "Frame at ${seconds(f.frameMs)} of your video. ${s.instruction()}" },
        ) {
            drawImage(
                f.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(f.image.width, f.image.height),
                dstOffset = IntOffset(t.offsetX.roundToInt(), t.offsetY.roundToInt()),
                dstSize = IntSize((f.srcW * t.scale).roundToInt(), (f.srcH * t.scale).roundToInt()),
            )
            if (s.sport != Sport.FreePlay) drawCourt(s, t, f.srcW.toDouble(), f.srcH.toDouble())
            s.ball?.let { if (s.sport == Sport.FreePlay) drawMarker(t.toView(it), Color(0xFFE0A100)) }
            s.me?.let { drawMarker(t.toView(it), c.orange) }
            dragging?.let { k -> s.corners[k]?.let { drawLoupe(f, t, it, CornerColors.getValue(k)) } }
        }

        // Labels as text, next to each point, so each handle is named on screen and to TalkBack.
        if (s.sport != Sport.FreePlay) {
            for ((k, p) in s.corners) {
                val (x, y) = t.toView(p)
                val off = CourtGeometry.isOffFrame(p, f.srcW.toDouble(), f.srcH.toDouble())
                PointLabel(k.label, CornerColors.getValue(k), x, y, if (off) "${k.label} corner, outside the picture" else "${k.label} corner")
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
    val dx = with(d) { 16.dp.toPx() }
    val dy = with(d) { 36.dp.toPx() }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .offset { IntOffset((x + dx).roundToInt(), (y - dy).roundToInt()) }
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp)
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

private fun DrawScope.drawCourt(s: SetupState, t: FitTransform, srcW: Double, srcH: Double) {
    val pts = Corner.entries.mapNotNull { k -> s.corners[k]?.let { k to t.toView(it) } }
    val line = 2.dp.toPx()
    for (i in 0 until pts.size - 1) segment(pts[i].second, pts[i + 1].second, line)
    if (pts.size == 4) segment(pts[3].second, pts[0].second, line)
    for ((k, p) in pts) {
        val o = Offset(p.first.toFloat(), p.second.toFloat())
        if (CourtGeometry.isOffFrame(s.corners.getValue(k), srcW, srcH)) {
            // Outside the picture: a dashed ring with a hollow centre, so it reads as an estimate.
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = 11.dp.toPx(), center = o)
            drawCircle(Color.White, radius = 10.dp.toPx(), center = o, style = Stroke(width = 4.dp.toPx(), pathEffect = dash))
            drawCircle(CornerColors.getValue(k), radius = 10.dp.toPx(), center = o, style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
            drawCircle(CornerColors.getValue(k), radius = 2.dp.toPx(), center = o)
        } else {
            drawCircle(Color.White, radius = 11.dp.toPx(), center = o)
            drawCircle(CornerColors.getValue(k), radius = 8.dp.toPx(), center = o)
            drawCircle(Color.White, radius = 2.dp.toPx(), center = o)
        }
    }
}

private fun DrawScope.segment(a: Pair<Double, Double>, b: Pair<Double, Double>, width: Float) {
    val oa = Offset(a.first.toFloat(), a.second.toFloat())
    val ob = Offset(b.first.toFloat(), b.second.toFloat())
    drawLine(Color.Black.copy(alpha = 0.5f), oa, ob, strokeWidth = width * 2)
    drawLine(Color.White, oa, ob, strokeWidth = width)
}

private fun DrawScope.drawMarker(p: Pair<Double, Double>, color: Color) {
    val o = Offset(p.first.toFloat(), p.second.toFloat())
    drawCircle(Color.White, radius = 14.dp.toPx(), center = o, style = Stroke(width = 5.dp.toPx()))
    drawCircle(color, radius = 14.dp.toPx(), center = o, style = Stroke(width = 3.dp.toPx()))
    drawCircle(color, radius = 3.dp.toPx(), center = o)
}

/**
 * A 3× loupe of the real frame around source point [p], in a circle above the finger (below it
 * near the top edge), with a crosshair on the exact point. Drawn from the frame bitmap itself.
 */
private fun DrawScope.drawLoupe(f: SetupFrame.Ready, t: FitTransform, p: ImagePoint, color: Color) {
    val zoom = 3.0
    val r = 56.dp.toPx()
    val (vx, vy) = t.toView(p)
    val gap = 96.dp.toPx()
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
    val arm = 14.dp.toPx()
    val w = 1.5.dp.toPx()
    drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = w)
    drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = w)
    drawCircle(Color.White, radius = r, center = center, style = Stroke(width = 3.dp.toPx()))
    drawCircle(color, radius = r + 2.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
}

@Composable
private fun Panel(s: SetupState, dispatch: (SetupAction) -> Unit, onDone: (SetupResult) -> Unit, modifier: Modifier) {
    val c = Elevate.colors
    Column(
        modifier
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(c.cream)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(s.instruction(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when (s.step) {
            Step.Sport -> SportStep(s, dispatch)
            Step.Court -> CourtStep(s, dispatch)
            Step.Ball, Step.WhoIsMe -> TapStep(s, dispatch)
            Step.Review -> ReviewStep(s)
        }
        s.blocker?.takeIf { s.step != Step.Sport || !s.isEnabled(s.sport) }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.step == Step.Court && s.nextCorner == null) c.error else c.inkMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val result = if (s.step == Step.Review) s.result() else null
        Button(
            onClick = { if (result != null) onDone(result) else dispatch(SetupAction.Next) },
            enabled = s.canNext,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = c.orange, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(if (s.step == Step.Review) "Use this setup" else "Next", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun SportStep(s: SetupState, dispatch: (SetupAction) -> Unit) {
    val c = Elevate.colors
    Pills(Sport.entries, s.sport, { it.label }, { s.isEnabled(it) }, { dispatch(SetupAction.SelectSport(it)) })
    val off = Sport.entries.filterNot { s.isEnabled(it) }
    if (off.isNotEmpty()) {
        Text("${off.joinToString { it.label }}: not in this build", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
    val formats = SetupCatalog.formats(s.sport)
    if (formats.size > 1) Pills(formats, s.format, { it.label }, { true }, { dispatch(SetupAction.SelectFormat(it)) })
    val modes = SetupCatalog.modes(s.sport)
    if (modes.size > 1) Pills(modes, s.mode, { it.label }, { true }, { dispatch(SetupAction.SelectMode(it)) })
}

@Composable
private fun <T> Pills(items: List<T>, selected: T, label: (T) -> String, enabled: (T) -> Boolean, onSelect: (T) -> Unit) {
    val c = Elevate.colors
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) {
            val on = item == selected
            val ok = enabled(item)
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (on) c.orange else if (ok) c.peach else c.line)
                    .selectable(selected = on, enabled = ok, role = Role.RadioButton, onClick = { onSelect(item) })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(item),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else if (ok) c.ink else c.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun CourtStep(s: SetupState, dispatch: (SetupAction) -> Unit) {
    val c = Elevate.colors
    s.courtHint()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in Corner.entries) {
            val placed = k in s.corners
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (placed) c.surface else c.line)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${k.label}: ${if (placed) "placed" else "not placed"}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (placed) CornerColors.getValue(k) else c.inkMuted))
                Spacer(Modifier.size(6.dp))
                Text(k.label, style = MaterialTheme.typography.labelMedium, color = c.ink)
            }
        }
    }
    Text("${s.corners.size} of 4 corners placed", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    UndoReset(s, dispatch, resetLabel = "Reset")
}

@Composable
private fun TapStep(s: SetupState, dispatch: (SetupAction) -> Unit) {
    val c = Elevate.colors
    val marked = if (s.step == Step.Ball) s.ball else s.me
    Text(
        if (marked == null) "On this frame, at ${seconds(s.frameMs)}" else "Marked at ${seconds(s.frameMs)}. Tap again to move it.",
        style = MaterialTheme.typography.bodyMedium,
        color = c.inkMuted,
    )
    UndoReset(s, dispatch, resetLabel = "Clear")
}

@Composable
private fun UndoReset(s: SetupState, dispatch: (SetupAction) -> Unit, resetLabel: String) {
    val c = Elevate.colors
    val hasAny = when (s.step) {
        Step.Court -> s.corners.isNotEmpty()
        Step.Ball -> s.ball != null
        Step.WhoIsMe -> s.me != null
        else -> false
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { dispatch(SetupAction.Undo) },
            enabled = s.canUndo,
            shape = CircleShape,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Undo", color = if (s.canUndo) c.ink else c.inkMuted) }
        OutlinedButton(
            onClick = { dispatch(SetupAction.Reset) },
            enabled = hasAny,
            shape = CircleShape,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(resetLabel, color = if (hasAny) c.ink else c.inkMuted) }
    }
}

@Composable
private fun ReviewStep(s: SetupState) {
    val c = Elevate.colors
    val r = s.result()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row2("Sport", listOf(s.sport.label, s.format.label.takeIf { s.format != Format.None }, s.mode.label).filterNotNull().joinToString(" · "))
        r?.ruleset?.let { Row2("Rules", "${it.id} (${it.version})") }
        if (s.sport == Sport.FreePlay) {
            Row2("Court", "None (free play)")
            Row2("Ball", if (s.ball != null) "marked at ${seconds(s.frameMs)}" else "not marked")
        } else {
            Row2("Court corners", "${s.corners.size} of 4 placed${s.template?.let { " · $it" } ?: ""}")
        }
        if (s.offFrameCorners > 0) {
            Text(
                "${s.offFrameCorners} ${if (s.offFrameCorners == 1) "corner" else "corners"} placed outside the picture (estimated)",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
            )
        }
        Row2("You", if (s.me != null) "marked at ${seconds(s.frameMs)}" else "not marked")
        Row2("Frame", seconds(s.frameMs))
    }
    Text("Nothing is uploaded yet.", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
}

@Composable
private fun Row2(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Text(v, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Message(text: String, busy: Boolean, onCancel: () -> Unit) {
    val c = Elevate.colors
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(24.dp), color = c.orange, strokeWidth = 2.dp)
            Spacer(Modifier.height(16.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Cancel", color = c.ink)
        }
    }
}

private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)
