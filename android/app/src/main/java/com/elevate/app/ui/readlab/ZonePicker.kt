package com.elevate.app.ui.readlab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.ElevateRadius
import com.elevate.app.ui.components.rememberHaptics
import com.elevate.app.ui.components.rememberReduceMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.elevate.app.ui.theme.Elevate
import kotlin.math.abs

/** Where a court point sits on the picker, as fractions (u across from the hitter's left, v from the net). */
object ZoneView {
    const val HALF_M = CourtModel.LENGTH_M - CourtModel.NET_Y_M

    fun point(xM: Double, yM: Double, side: CourtSide, widthM: Double): Pair<Float, Float> {
        val fromLeft = if (side == CourtSide.NEAR) xM else widthM - xM
        return (fromLeft / widthM).toFloat() to (abs(yM - CourtModel.NET_Y_M) / HALF_M).toFloat()
    }

    /** Row heights in metres, so the cells keep the real proportions of the half court. */
    fun rowMetres(row: ZoneRow): Float = when (row) {
        ZoneRow.FRONT -> CourtModel.SHORT_SERVICE_M
        ZoneRow.MIDDLE -> HALF_M - CourtModel.SHORT_SERVICE_M - ZoneGrid.BACK_DEPTH_M
        ZoneRow.BACK -> ZoneGrid.BACK_DEPTH_M
    }.toFloat()
}

/**
 * The target half of the court, as the hitter sees it (net at the top, left to right from the
 * hitter's view), cut into the 3 x 3 zones. Each zone is one tap target. Once [locked], taps do nothing.
 */
@Composable
fun ZonePicker(
    template: String,
    side: CourtSide,
    picked: ZoneId?,
    locked: Boolean,
    /** The zone the real (or candidate) landing falls in, shown after the answer is locked. */
    truthZone: ZoneId?,
    /** Landing point in court metres, drawn after the answer is locked. */
    landingXyM: Pair<Double, Double>?,
    landingTrusted: Boolean,
    onPick: (ZoneId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Elevate.colors
    val reduce = rememberReduceMotion()
    val haptics = rememberHaptics()
    val widthM = ZoneGrid.forTemplate(template)?.widthM ?: CourtModel.WIDTH_SINGLES_M
    val fade = tween<Color>(if (reduce) 0 else 260)
    Box(
        modifier
            .aspectRatio((widthM / ZoneView.HALF_M).toFloat())
            .clip(RoundedCornerShape(ElevateRadius.Inner))
            .background(c.court),
    ) {
        Column(Modifier.fillMaxSize()) {
            for (row in ZoneRow.entries) {
                Row(Modifier.fillMaxWidth().weight(ZoneView.rowMetres(row))) {
                    for (col in 1..3) {
                        val zone = ZoneId(row, col)
                        val isPick = picked == zone
                        val isTruth = locked && truthZone == zone
                        // Right pick: green. Wrong pick: red. The real zone is always green once locked.
                        val target = when {
                            isTruth -> c.mintInk.copy(alpha = 0.82f)
                            isPick && locked && truthZone != null -> c.error.copy(alpha = 0.82f)
                            isPick -> c.orange.copy(alpha = 0.78f)
                            else -> Color.Transparent
                        }
                        val fill by animateColorAsState(target, fade, label = "zone-fill")
                        val desc = buildString {
                            append("Zone ${zone.id}, ${ReadLabText.zoneName(zone).substringBefore(" (")}")
                            if (isPick) append(", your pick")
                            if (isTruth) append(", where the shuttle landed")
                        }
                        ZoneCell(
                            zone = zone,
                            fill = fill,
                            enabled = !locked,
                            mark = when {
                                isTruth -> ElevateIcons.Check
                                isPick && locked && truthZone != null -> ElevateIcons.Close
                                else -> null
                            },
                            desc = desc,
                            selected = isPick,
                            onPick = { haptics.tick(); onPick(zone) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        // Court lines: outer boundary, the short-service and back lines, the centre line, dashed zone seams.
        Canvas(Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = 0.85f)
            val seam = Color.White.copy(alpha = 0.32f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            val w = size.width
            val h = size.height
            val total = ZoneRow.entries.sumOf { ZoneView.rowMetres(it).toDouble() }.toFloat()
            var y = 0f
            ZoneRow.entries.dropLast(1).forEach { row ->
                y += ZoneView.rowMetres(row) / total * h
                drawLine(line, Offset(0f, y), Offset(w, y), strokeWidth = 1.5.dp.toPx())
            }
            for (i in 1..2) drawLine(seam, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawLine(line, Offset(w / 2f, 0f), Offset(w / 2f, ZoneView.rowMetres(ZoneRow.FRONT) / total * h), strokeWidth = 1.5.dp.toPx())
            drawRect(line, style = Stroke(2.dp.toPx()))
        }
        if (locked && landingXyM != null) {
            Canvas(Modifier.fillMaxSize()) {
                val (u, v) = ZoneView.point(landingXyM.first, landingXyM.second, side, widthM)
                if (u in 0f..1f && v in 0f..1f) {
                    val p = Offset(u * size.width, v * size.height)
                    val r = 9.dp.toPx()
                    if (landingTrusted) {
                        drawCircle(Color.White, r + 2.dp.toPx(), p)
                        drawCircle(c.orange, r, p)
                    } else {
                        // Candidates are a ring, never a filled mark: not shown as certain.
                        drawCircle(Color.White, r, p, style = Stroke(3.dp.toPx()))
                    }
                }
            }
        }
        // The net is the top edge.
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White).align(Alignment.TopCenter))
    }
}

/** One zone: lights up while pressed, names itself on a long press only, shows a mark once the answer is in. */
@Composable
private fun ZoneCell(
    zone: ZoneId,
    fill: Color,
    enabled: Boolean,
    mark: ImageVector?,
    desc: String,
    selected: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    var named by remember { mutableStateOf(false) }
    val currentPick by rememberUpdatedState(onPick)
    Box(
        modifier
            .background(fill)
            .background(if (pressed && enabled) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                role = Role.Button
                this.selected = selected
                if (enabled) onClick("Pick zone ${zone.id}") { currentPick(); true }
            }
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        named = false
                    },
                    onLongPress = { named = true },
                    onTap = { if (enabled) currentPick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (mark != null) Icon(mark, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        if (named) {
            Text(
                ReadLabText.zoneName(zone).substringBefore(" ("),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(ElevateRadius.Tile / 2)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
