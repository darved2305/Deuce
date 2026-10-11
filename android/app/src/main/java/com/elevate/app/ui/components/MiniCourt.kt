package com.elevate.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevate.app.domain.calibration.CourtCalibration
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.ui.theme.Elevate

/** The frame's edge is at the horizon here; beyond it the homography has nothing to say. */
private const val EPS_W = 1e-9

/**
 * "See the whole court" beside the footage: the real court at true proportions, the part the
 * camera can actually see shaded, the rest hatched. The visible region is the frame's four
 * corners projected onto the court through the corner-tap homography — geometry only.
 */
@Composable
fun MiniCourt(
    calibration: CourtCalibration,
    modifier: Modifier = Modifier,
    /** Landing candidates (x, y in the template's court metres, provenance state) from `match.v1`. */
    landings: List<Triple<Double, Double, String>> = emptyList(),
) {
    val c = Elevate.colors
    val bounds = CourtModel.courtBounds(calibration.template) ?: return
    val lines = remember(calibration.template) { CourtModel.lines(calibration.template) }
    val visible = remember(calibration) { visibleRegion(calibration) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ElevateRadius.Card))
            .background(c.cream)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(if (landings.isEmpty()) "The whole court" else "The whole court · ${landings.size} landing candidate${if (landings.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clipToBounds()
                .semantics {
                    contentDescription =
                        "Badminton court from above. The shaded part is what your camera can see; the hatched part is outside the camera view."
                },
        ) {
            val (cl, cr) = bounds
            val wM = (cr - cl).toFloat()
            val lM = CourtModel.LENGTH_M.toFloat()
            val margin = 0.4f
            val s = minOf(size.width / (wM + 2 * margin), size.height / (lM + 2 * margin))
            val ox = (size.width - wM * s) / 2
            val oy = (size.height - lM * s) / 2
            // Near baseline at the bottom, matching the overview's court drawing.
            fun px(x: Double) = ox + (x - cl).toFloat() * s
            fun py(y: Double) = oy + (lM - y.toFloat()) * s
            val left = px(cl)
            val right = px(cr)
            val top = py(lM.toDouble())
            val bottom = py(0.0)
            drawRect(c.court, topLeft = Offset(left, top), size = Size(right - left, bottom - top))
            // Hatch everywhere, then the visible part covers it — what stays hatched is outside.
            clipRect(left, top, right, bottom) {
                val step = 6.dp.toPx()
                var x = left - (bottom - top)
                while (x < right) {
                    drawLine(
                        Color.White.copy(alpha = 0.10f),
                        Offset(x, bottom),
                        Offset(x + (bottom - top), top),
                        strokeWidth = 1.dp.toPx(),
                    )
                    x += step
                }
            }
            visible?.let { poly ->
                val path = Path().apply {
                    moveTo(px(poly[0].first), py(poly[0].second))
                    for (i in 1 until poly.size) lineTo(px(poly[i].first), py(poly[i].second))
                    close()
                }
                drawPath(path, c.mintInk)
                drawPath(path, c.orange, style = Stroke(1.5.dp.toPx()))
            }
            val sw = 1.5f.dp.toPx()
            for (seg in lines) {
                drawLine(
                    Color.White,
                    Offset(px(seg.start.first), py(seg.start.second)),
                    Offset(px(seg.end.first), py(seg.end.second)),
                    strokeWidth = if (seg.name == "net") sw * 2.5f else sw,
                )
            }
            // Observed = filled; candidate/inferred = hollow ring (never shown as certain).
            val r = 3.5.dp.toPx()
            for ((x, y, state) in landings) {
                val p = Offset(px(x), py(y))
                if (p.x !in 0f..size.width || p.y !in 0f..size.height) continue
                if (state == "observed" || state == "user") drawCircle(c.orange, r, p)
                else drawCircle(c.orange, r, p, style = Stroke(1.5.dp.toPx()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (landings.isEmpty()) "Outside the camera view · from your corner taps"
            else "Rings: where the shuttle likely landed",
            style = MaterialTheme.typography.labelMedium,
            color = c.inkMuted,
        )
    }
}

/**
 * The image frame's footprint on the court plane: the four frame corners projected through the
 * homography, cut at the horizon (w ≤ 0 has no court point in front of the camera), then clipped
 * to the court rectangle grown by 2 m. Null when nothing valid is left to shade.
 */
private fun visibleRegion(calibration: CourtCalibration): List<Pair<Double, Double>>? {
    val (cl, cr) = CourtModel.courtBounds(calibration.template) ?: return null
    val w = calibration.imageW.toDouble()
    val h = calibration.imageH.toDouble()
    if (w <= 0 || h <= 0) return null
    val proj = listOf(
        calibration.homography.projectToCourt(0.0, 0.0),
        calibration.homography.projectToCourt(w, 0.0),
        calibration.homography.projectToCourt(w, h),
        calibration.homography.projectToCourt(0.0, h),
    )
    val front = clipInFront(proj)
    if (front.size < 3) return null
    val onCourt = front.map { p -> Pair(p[0] / maxOf(p[2], EPS_W), p[1] / maxOf(p[2], EPS_W)) }
    val clipped = clipToRect(onCourt, cl - 2.0, -2.0, cr + 2.0, CourtModel.LENGTH_M + 2.0)
    if (clipped.size < 3) return null
    return clipped.takeIf { it.all { (x, y) -> x.isFinite() && y.isFinite() } }
}

/** Keeps the part of the quad in front of the camera (w ≥ 0), intersecting edges at the horizon. */
private fun clipInFront(proj: List<DoubleArray>): List<DoubleArray> {
    val out = ArrayList<DoubleArray>(proj.size + 1)
    var prev = proj.last()
    for (cur in proj) {
        val inC = cur[2] >= 0.0
        val inP = prev[2] >= 0.0
        if (inC != inP) {
            val t = prev[2] / (prev[2] - cur[2])
            out.add(
                doubleArrayOf(
                    prev[0] + (cur[0] - prev[0]) * t,
                    prev[1] + (cur[1] - prev[1]) * t,
                    prev[2] + (cur[2] - prev[2]) * t,
                ),
            )
        }
        if (inC) out.add(cur)
        prev = cur
    }
    return out
}

/** Sutherland–Hodgman against the four sides of an axis-aligned rectangle. */
private fun clipToRect(
    poly: List<Pair<Double, Double>>,
    left: Double,
    near: Double,
    right: Double,
    far: Double,
): List<Pair<Double, Double>> {
    var p = clipHalf(poly, left) { it.first }
    p = clipHalf(p, -right) { -it.first }
    p = clipHalf(p, near) { it.second }
    p = clipHalf(p, -far) { -it.second }
    return p
}

/** Keeps vertices with coord ≥ bound and inserts edge crossings on the boundary. */
private fun clipHalf(
    poly: List<Pair<Double, Double>>,
    bound: Double,
    coord: (Pair<Double, Double>) -> Double,
): List<Pair<Double, Double>> {
    if (poly.isEmpty()) return poly
    val out = ArrayList<Pair<Double, Double>>(poly.size + 1)
    var prev = poly.last()
    var pv = coord(prev)
    for (cur in poly) {
        val cv = coord(cur)
        val inC = cv >= bound
        val inP = pv >= bound
        if (inC != inP) {
            val t = (bound - pv) / (cv - pv)
            out.add(Pair(prev.first + (cur.first - prev.first) * t, prev.second + (cur.second - prev.second) * t))
        }
        if (inC) out.add(cur)
        prev = cur
        pv = cv
    }
    return out
}
