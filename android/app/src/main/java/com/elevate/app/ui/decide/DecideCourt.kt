package com.elevate.app.ui.decide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.elevate.app.domain.decide.Freeze
import com.elevate.app.domain.decide.Outcome
import com.elevate.app.domain.shotrewind.Decision
import com.elevate.app.ui.components.rememberReduceMotion
import com.elevate.app.ui.theme.Elevate

/** Metres <-> pixels for the hitter's view: the hitter's half at the bottom, the target half on top. */
class CourtMap(val side: CourtSide, val widthM: Double, val scale: Float) {
    private val m = MARGIN_M.toFloat()
    private val l = CourtModel.LENGTH_M.toFloat()
    private val w = widthM.toFloat()

    fun toPx(x: Double, y: Double): Offset {
        val a = if (side == CourtSide.NEAR) x.toFloat() else w - x.toFloat()
        val b = if (side == CourtSide.NEAR) l - y.toFloat() else y.toFloat()
        return Offset((m + a) * scale, (m + b) * scale)
    }

    fun toMetres(p: Offset): Pair<Double, Double> {
        val a = (p.x / scale - m).coerceIn(-m + 0.15f, w + m - 0.15f)
        val b = (p.y / scale - m).coerceIn(-m + 0.15f, l + m - 0.15f)
        val x = if (side == CourtSide.NEAR) a else w - a
        val y = if (side == CourtSide.NEAR) l - b else b
        return x.toDouble() to y.toDouble()
    }

    /** Screen rectangle of a target-half zone (hitter's view: net below, far baseline on top). */
    fun zoneRect(z: ZoneId): Pair<Offset, Size> {
        val half = l / 2
        val (d0, d1) = when (z.row) {
            ZoneRow.FRONT -> 0f to CourtModel.SHORT_SERVICE_M.toFloat()
            ZoneRow.MIDDLE -> CourtModel.SHORT_SERVICE_M.toFloat() to half - ZoneGrid.BACK_DEPTH_M.toFloat()
            ZoneRow.BACK -> half - ZoneGrid.BACK_DEPTH_M.toFloat() to half
        }
        val third = w / 3
        val left = (m + (z.col - 1) * third) * scale
        val top = (m + (half - d1)) * scale
        return Offset(left, top) to Size(third * scale, (d1 - d0) * scale)
    }

    companion object {
        const val MARGIN_M = 0.9
        fun aspect(widthM: Double) = ((widthM + 2 * MARGIN_M) / (CourtModel.LENGTH_M + 2 * MARGIN_M)).toFloat()
    }
}

/** Reach of a defender drawn on the court. A drawing aid, not a measurement. */
const val REACH_RING_M = 1.5

/**
 * The big top-down court. Drag anywhere to aim (the reticle sits above the finger so it stays
 * visible), release to commit. After a commit it draws the in/out stamp, the defender's reach and
 * the SIMULATED arcs to the aim and to the real landing.
 */
@Composable
fun DecideCourt(
    freeze: Freeze,
    aim: Pair<Double, Double>?,
    outcome: Outcome?,
    enabled: Boolean,
    onAim: (Pair<Double, Double>) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Elevate.colors
    val reduce = rememberReduceMotion()
    val widthM = ZoneGrid.forTemplate(freeze.template)?.widthM ?: CourtModel.WIDTH_SINGLES_M
    val measurer = rememberTextMeasurer()
    val shown = outcome?.let { it.aimXM to it.aimYM } ?: aim
    // 0 -> 1 after a commit: the arcs fly, then the in/out stamp lands.
    val t = remember(freeze.hitEventId) { Animatable(if (outcome != null) 1f else 0f) }
    LaunchedEffect(freeze.hitEventId, outcome != null) {
        if (outcome == null) t.snapTo(0f)
        else if (reduce) t.snapTo(1f)
        else t.animateTo(1f, tween(1100, easing = LinearEasing))
    }
    val onAimNow by rememberUpdatedState(onAim)
    val onCommitNow by rememberUpdatedState(onCommit)
    val desc = when {
        outcome != null -> "Court. Your aim is committed."
        else -> "Court. Drag to aim the shot, release to commit."
    }
    Box(modifier.aspectRatio(CourtMap.aspect(widthM)).clip(RoundedCornerShape(20.dp)).semantics { contentDescription = desc }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(enabled, freeze.hitEventId) {
                if (!enabled) return@pointerInput
                val lift = 52.dp.toPx()
                val map = CourtMap(freeze.side, widthM, size.width / (widthM + 2 * CourtMap.MARGIN_M).toFloat())
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onAimNow(map.toMetres(down.position - Offset(0f, lift)))
                    drag(down.id) { ch ->
                        ch.consume()
                        onAimNow(map.toMetres(ch.position - Offset(0f, lift)))
                    }
                    onCommitNow()
                }
            },
        ) {
            val map = CourtMap(freeze.side, widthM, size.width / (widthM + 2 * CourtMap.MARGIN_M).toFloat())
            drawCourt(map, widthM, c.court)
            // Live zone under the reticle.
            if (outcome == null && shown != null) {
                val d = com.elevate.app.domain.shotrewind.ShotRewind.decide(freeze.template, freeze.side, shown.first, shown.second, emptyList())
                if (d is Decision.Legal) {
                    val (o, s) = map.zoneRect(d.zone)
                    drawRect(Color(0x66FFB38A), o, s)
                }
            }
            // Defenders: real feet at the frozen frame, with a rough reach ring.
            freeze.defenders.forEach { p ->
                val at = map.toPx(p.xM.coerceIn(0.0, widthM), p.yM.coerceIn(0.0, CourtModel.LENGTH_M))
                val r = (REACH_RING_M * map.scale).toFloat()
                drawCircle(c.lavender.copy(alpha = 0.22f), r, at)
                drawCircle(c.lavender.copy(alpha = 0.9f), r, at, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                drawCircle(Color.White, 11.dp.toPx(), at)
                drawCircle(c.lavenderInk, 8.dp.toPx(), at)
                label(measurer, "Defender", at + Offset(0f, 14.dp.toPx()), Color.White)
            }
            // The hitter, only when their foot was seen.
            val hitterAt = freeze.hitterXyM?.let { map.toPx(it.first.coerceIn(0.0, widthM), it.second.coerceIn(0.0, CourtModel.LENGTH_M)) }
            hitterAt?.let {
                drawCircle(Color.White, 11.dp.toPx(), it)
                drawCircle(c.orange, 8.dp.toPx(), it)
                label(measurer, if (freeze.yours) "You" else "Hitter", it + Offset(0f, 14.dp.toPx()), Color.White)
            }
            val origin = hitterAt ?: map.toPx(widthM / 2, if (freeze.side == CourtSide.NEAR) 1.2 else CourtModel.LENGTH_M - 1.2)
            // Aim line while dragging.
            if (outcome == null && shown != null) {
                val a = map.toPx(shown.first, shown.second)
                drawLine(Color.White.copy(alpha = 0.45f), origin, a, 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)))
                reticle(a, Color.White, c.orange)
            }
            if (outcome != null && shown != null) {
                val a = map.toPx(shown.first, shown.second)
                val real = freeze.real?.let { map.toPx(it.xM.coerceIn(-MARGIN_CLAMP, widthM + MARGIN_CLAMP), it.yM.coerceIn(-MARGIN_CLAMP, CourtModel.LENGTH_M + MARGIN_CLAMP)) }
                val p1 = (t.value / 0.55f).coerceIn(0f, 1f)
                val p2 = ((t.value - 0.15f) / 0.55f).coerceIn(0f, 1f)
                val stamp = ((t.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                val good = outcome.legal
                val mine = if (good) c.orange else c.error
                if (real != null) arc(origin, real, p2, Color.White)
                arc(origin, a, p1, mine)
                if (p2 >= 1f && real != null) {
                    drawCircle(Color.White, 9.dp.toPx(), real)
                    drawCircle(Color(0xFF3B6FD4), 6.dp.toPx(), real)
                    label(measurer, "What you played", real + Offset(0f, 16.dp.toPx()), Color.White)
                }
                if (p1 >= 1f) {
                    // In: a green ring expands out of the aim point. Out: a red ring and a quick shake cross.
                    val ring = (18.dp.toPx() + 30.dp.toPx() * stamp)
                    val col = if (good) c.mintInk else c.error
                    drawCircle(col.copy(alpha = 0.35f * (1f - stamp * 0.6f)), ring, a)
                    drawCircle(col, 14.dp.toPx(), a, style = Stroke(4.dp.toPx()))
                    if (!good) {
                        val k = 7.dp.toPx() * stamp
                        drawLine(col, a + Offset(-k, -k), a + Offset(k, k), 4.dp.toPx(), StrokeCap.Round)
                        drawLine(col, a + Offset(-k, k), a + Offset(k, -k), 4.dp.toPx(), StrokeCap.Round)
                    } else {
                        drawCircle(col, 5.dp.toPx() * stamp, a)
                    }
                    label(measurer, "Your choice", a + Offset(0f, 30.dp.toPx()), Color.White)
                }
            }
        }
    }
}

private const val MARGIN_CLAMP = CourtMap.MARGIN_M - 0.1

private fun DrawScope.reticle(at: Offset, ring: Color, accent: Color) {
    val r = 17.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.25f), r + 2.dp.toPx(), at)
    drawCircle(ring, r, at, style = Stroke(3.dp.toPx()))
    drawCircle(accent, 5.dp.toPx(), at)
    val tick = 8.dp.toPx()
    listOf(Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f)).forEach {
        drawLine(ring, at + it * r, at + it * (r + tick), 3.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, centre: Offset, color: Color) {
    val layout = measurer.measure(text, TextStyle(fontSize = 11.sp, color = color))
    val tl = Offset((centre.x - layout.size.width / 2f).coerceIn(2f, size.width - layout.size.width - 2f), centre.y.coerceIn(2f, size.height - layout.size.height - 2f))
    drawRoundRect(Color.Black.copy(alpha = 0.55f), tl - Offset(4.dp.toPx(), 1.dp.toPx()), Size(layout.size.width + 8.dp.toPx(), layout.size.height + 2.dp.toPx()), CornerRadius(6.dp.toPx()))
    drawText(layout, topLeft = tl)
}

/** A dashed arc that lifts above the straight line; [progress] draws it from the start. */
private fun DrawScope.arc(from: Offset, to: Offset, progress: Float, color: Color) {
    if (progress <= 0f) return
    val mid = Offset((from.x + to.x) / 2, (from.y + to.y) / 2)
    val dist = (to - from).getDistance()
    val ctrl = Offset(mid.x, mid.y - dist * 0.32f)
    val path = Path().apply { moveTo(from.x, from.y); quadraticTo(ctrl.x, ctrl.y, to.x, to.y) }
    val pm = PathMeasure().apply { setPath(path, false) }
    val seg = Path()
    pm.getSegment(0f, pm.length * progress, seg, true)
    drawPath(seg, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))))
    val head = pm.getPosition(pm.length * progress)
    drawCircle(color, 5.dp.toPx(), head)
}

private fun DrawScope.drawCourt(map: CourtMap, widthM: Double, grass: Color) {
    val w = widthM
    val l = CourtModel.LENGTH_M
    drawRect(grass.copy(alpha = 0.78f))
    val tl = map.toPx(0.0, if (map.side == CourtSide.NEAR) l else 0.0)
    val br = map.toPx(w, if (map.side == CourtSide.NEAR) 0.0 else l)
    drawRect(grass, tl, Size(br.x - tl.x, br.y - tl.y))
    val line = Color.White.copy(alpha = 0.9f)
    val seam = Color.White.copy(alpha = 0.3f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    val sw = 1.6.dp.toPx()
    drawRect(line, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(sw * 1.4f))
    // Short service lines and centre lines on both halves.
    for (y in listOf(CourtModel.NET_Y_M - CourtModel.SHORT_SERVICE_M, CourtModel.NET_Y_M + CourtModel.SHORT_SERVICE_M)) {
        drawLine(line, map.toPx(0.0, y), map.toPx(w, y), sw)
    }
    drawLine(line, map.toPx(w / 2, CourtModel.SHORT_SERVICE_M.let { CourtModel.NET_Y_M + it }), map.toPx(w / 2, l), sw)
    drawLine(line, map.toPx(w / 2, 0.0), map.toPx(w / 2, CourtModel.NET_Y_M - CourtModel.SHORT_SERVICE_M), sw)
    // Zone seams on the target half (back row starts 1.5 m from the baseline).
    val back = CourtModel.LENGTH_M - CourtModel.NET_Y_M - ZoneGrid.BACK_DEPTH_M
    val yBack = if (map.side == CourtSide.NEAR) CourtModel.NET_Y_M + back else CourtModel.NET_Y_M - back
    drawLine(seam, map.toPx(0.0, yBack), map.toPx(w, yBack), sw, pathEffect = dash)
    for (i in 1..2) {
        val x = w * i / 3
        val yEnd = if (map.side == CourtSide.NEAR) l else 0.0
        drawLine(seam, map.toPx(x, CourtModel.NET_Y_M), map.toPx(x, yEnd), sw, pathEffect = dash)
    }
    // The net.
    val a = map.toPx(-CourtModel.NET_OVERHANG_M, CourtModel.NET_Y_M)
    val b = map.toPx(w + CourtModel.NET_OVERHANG_M, CourtModel.NET_Y_M)
    drawLine(Color.White, a, b, 4.dp.toPx(), StrokeCap.Round)
}
