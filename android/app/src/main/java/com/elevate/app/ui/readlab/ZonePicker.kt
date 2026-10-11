package com.elevate.app.ui.readlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    val widthM = ZoneGrid.forTemplate(template)?.widthM ?: CourtModel.WIDTH_SINGLES_M
    Box(
        modifier
            .aspectRatio((widthM / ZoneView.HALF_M).toFloat())
            .background(c.court),
    ) {
        Column(Modifier.fillMaxSize()) {
            for (row in ZoneRow.entries) {
                Row(Modifier.fillMaxWidth().weight(ZoneView.rowMetres(row))) {
                    for (col in 1..3) {
                        val zone = ZoneId(row, col)
                        val isPick = picked == zone
                        val isTruth = locked && truthZone == zone
                        val fill = when {
                            isTruth -> c.mintInk.copy(alpha = 0.75f)
                            isPick -> c.orange.copy(alpha = 0.75f)
                            else -> Color.Transparent
                        }
                        val desc = buildString {
                            append("Zone ${zone.id}, ${ReadLabText.zoneName(zone).substringBefore(" (")}")
                            if (isPick) append(", your pick")
                            if (isTruth) append(", where the shuttle landed")
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)))
                                .background(fill)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = desc
                                    role = Role.Button
                                    selected = isPick
                                }
                                .clickable(enabled = !locked, onClickLabel = "Pick zone ${zone.id}") { onPick(zone) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(zone.id, style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                }
            }
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
