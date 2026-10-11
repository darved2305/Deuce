package com.elevate.app.ui.overview

import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.ExplainBox
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.analysis.WindowFailure
import com.elevate.app.ui.theme.Elevate

/** A labelled pill on an opaque surface (contrast ≥ 4.5:1 with the theme tokens). */
@Composable
internal fun Pill(text: String, background: Color, ink: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 12.dp, vertical = 6.dp),
        color = ink,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** One-line notice band (offline, catching up, fixture label). */
@Composable
internal fun Notice(title: String, detail: String?, background: Color, ink: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = ink, style = MaterialTheme.typography.titleMedium)
        if (detail != null) Text(detail, color = Elevate.colors.inkMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Real coverage on the clip's timeline: covered `emit_range_us` filled, failed windows marked with
 * a dashed outline (gap stays empty). Drawn only when the clip length is known.
 */
@Composable
internal fun CoverageBar(coverageUs: List<LongRange>, failures: List<WindowFailure>, totalUs: Long, label: String, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    Canvas(
        modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = label },
    ) {
        val r = size.height / 2
        drawRoundRect(c.peach, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        fun x(us: Long) = (us.toDouble() / totalUs).coerceIn(0.0, 1.0).toFloat() * size.width
        for (range in coverageUs) {
            drawRect(c.mintInk, topLeft = Offset(x(range.first), 0f), size = Size((x(range.last) - x(range.first)).coerceAtLeast(1f), size.height))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
        for (f in failures) {
            val e = f.emitRangeUs ?: continue
            drawRect(c.error, topLeft = Offset(x(e.first), 1f), size = Size((x(e.last) - x(e.first)).coerceAtLeast(2f), size.height - 2f), style = Stroke(width = 2f, pathEffect = dash))
        }
    }
}

/**
 * Rally barcode on the match timeline: one bar per real rally interval; height ∝ shots, filled when
 * the winner is confirmed, outlined when it is only a candidate; you vs opponent by colour AND
 * position (your wins rise from the top edge, others from the bottom), never colour alone.
 */
@Composable
internal fun RallyBarcode(rows: List<RallyRow>, timelineMs: Long, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    val desc = "${rows.size} rallies on a ${clock(timelineMs)} timeline. " +
        rows.count { it.winnerConfirmed } + " with a confirmed winner."
    val maxShots = rows.maxOfOrNull { it.shots }?.coerceAtLeast(1) ?: 1
    Canvas(modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = desc }) {
        drawLine(c.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
        if (timelineMs <= 0) return@Canvas
        for (r in rows) {
            val x0 = r.startMs.toFloat() / timelineMs * size.width
            val w = ((r.endMs - r.startMs).toFloat() / timelineMs * size.width).coerceAtLeast(3f)
            val h = (0.3f + 0.7f * r.shots / maxShots) * (size.height / 2 - 2f)
            val mine = r.winnerSide == 0
            val color = if (mine) c.mintInk else if (r.winnerSide > 0) c.orange else c.inkMuted
            val top = if (mine) size.height / 2 - h else size.height / 2
            if (r.winnerConfirmed) drawRect(color, Offset(x0, top), Size(w, h))
            else drawRect(color, Offset(x0, top), Size(w, h), style = Stroke(width = 2f))
        }
    }
}

/**
 * Badminton court at true proportions (13.40 × 6.10 m; singles sidelines 0.46 m inside), near
 * baseline at the bottom. Draws only real landing marks: observed = filled dot, candidate/inferred
 * = ring, user = square. No marks means no dots, never placeholders.
 */
@Composable
internal fun MiniCourt(state: CourtState.Badminton, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    val m = OverviewDerivation.MARGIN_M.toFloat()
    val wM = OverviewDerivation.COURT_WIDTH_M.toFloat()
    val lM = OverviewDerivation.COURT_LENGTH_M.toFloat()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.heightIn(max = 320.dp).aspectRatio((wM + 2 * m) / (lM + 2 * m))
                .semantics { contentDescription = "Badminton court, ${if (state.singles) "singles" else "doubles"}. ${state.note}" },
        ) {
            val s = size.width / (wM + 2 * m)
            fun p(x: Float, y: Float) = Offset((x + m) * s, (lM - y + m) * s)
            drawRect(c.court, topLeft = p(0f, lM), size = Size(wM * s, lM * s))
            val line = Color.White
            val sw = 1.5f.dp.toPx()
            fun ln(x0: Float, y0: Float, x1: Float, y1: Float, wide: Float = sw, color: Color = line) = drawLine(color, p(x0, y0), p(x1, y1), strokeWidth = wide)
            courtLines(wM, lM, state.singles, ::ln, sw)
            for (mk in state.marks) {
                val o = p((mk.xM + state.frameOffsetM).toFloat(), mk.yM.toFloat())
                val r = 5.dp.toPx()
                when (mk.state) {
                    "observed" -> { drawCircle(c.orange, r, o); drawCircle(Color.White, r, o, style = Stroke(1.5f.dp.toPx())) }
                    "user" -> drawRect(c.orange, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r))
                    else -> drawCircle(Color.White, r, o, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Text(state.note, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        if (state.marks.isNotEmpty()) {
            Text(courtLegend(state.marks), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
    }
}

/** Shape key for the marks present (shape, not colour alone): "● observed · ○ candidate". */
internal fun courtLegend(marks: List<CourtMark>): String =
    marks.map { it.state }.distinct().sorted().joinToString(" · ") { st ->
        when (st) {
            "observed" -> "● observed"
            "user" -> "■ confirmed by you"
            else -> "○ $st"
        }
    }

private fun courtLines(
    w: Float,
    l: Float,
    singles: Boolean,
    ln: (Float, Float, Float, Float, Float, Color) -> Unit,
    sw: Float,
) {
    val white = Color.White
    val faint = Color.White.copy(alpha = 0.55f)
    val inset = ((OverviewDerivation.COURT_WIDTH_M - OverviewDerivation.SINGLES_WIDTH_M) / 2).toFloat()
    val net = l / 2
    val shortSvc = 1.98f
    val longSvcDoubles = 0.76f
    // Outer (doubles) boundary.
    ln(0f, 0f, w, 0f, sw, white); ln(0f, l, w, l, sw, white); ln(0f, 0f, 0f, l, sw, if (singles) faint else white); ln(w, 0f, w, l, sw, if (singles) faint else white)
    // Singles sidelines.
    ln(inset, 0f, inset, l, sw, if (singles) white else faint); ln(w - inset, 0f, w - inset, l, sw, if (singles) white else faint)
    // Short service lines and centre lines.
    ln(0f, net - shortSvc, w, net - shortSvc, sw, white); ln(0f, net + shortSvc, w, net + shortSvc, sw, white)
    ln(w / 2, 0f, w / 2, net - shortSvc, sw, white); ln(w / 2, net + shortSvc, w / 2, l, sw, white)
    // Doubles long service lines.
    ln(0f, longSvcDoubles, w, longSvcDoubles, sw, faint); ln(0f, l - longSvcDoubles, w, l - longSvcDoubles, sw, faint)
    // Net.
    ln(-0.3f, net, w + 0.3f, net, sw * 2.5f, white)
}

/** Rally rows; tapping one hands its real interval to the player ([onOpenRally]). */
@Composable
internal fun RallyList(rows: List<RallyRow>, onOpenRally: (rallyId: String, startMs: Long, endMs: Long) -> Unit, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, r ->
            ExplainBox(Explanations.Rally, Modifier.fillMaxWidth()) { Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
                    .clickable(role = Role.Button, onClickLabel = "Play rally ${i + 1}") { onOpenRally(r.rallyId, r.startMs, r.endMs) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Column(Modifier.weight(1f)) {
                    Text(
                        "${clock(r.startMs)}–${clock(r.endMs)} · ${r.shots} shot${if (r.shots == 1) "" else "s"}" +
                            if (r.state != "observed" && r.state != "user") " · ${r.state} rally" else "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(listOfNotNull(r.ballCoverage, r.winner).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
                Text("Play", color = c.orange, style = MaterialTheme.typography.labelLarge)
            } }
        }
    }
}
