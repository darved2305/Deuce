package com.elevate.app.ui.overview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.RallyCandidate
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.coach.LandingEvidence
import com.elevate.app.ui.components.rememberReduceMotion
import com.elevate.app.ui.theme.Elevate
import java.util.Locale

/** 0 → 1 once on first show; instant with reduced motion. */
@Composable
private fun rememberReveal(key: Any?, durationMs: Int = 900): Float {
    val reduce = rememberReduceMotion()
    val a = remember(key) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(key) { if (!reduce) a.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
    return a.value
}

/** A dark rounded panel the charts sit on. */
@Composable
private fun ChartPanel(desc: String, content: @Composable () -> Unit) {
    val c = Elevate.colors
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.ink, Color(0xFF2A2E63))))
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
    ) { content() }
}

@Composable
private fun BigStat(value: String, label: String, accent: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = accent)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
    }
}

/**
 * The whole court from above with every real landing as a glowing dot. Net across the middle,
 * true proportions; points outside the court by more than 1 m are left out (counted in the caption).
 */
@Composable
internal fun LandingCourtHero(e: LandingEvidence) {
    val c = Elevate.colors
    val template = e.template ?: "badminton_singles"
    val bounds = CourtModel.courtBounds(template) ?: return
    val lines = remember(template) { CourtModel.lines(template) }
    val (l, r) = bounds
    val shown = e.points.filter { it.xM in l - 1.0..r + 1.0 && it.yM in -1.0..CourtModel.LENGTH_M + 1.0 }
    val reveal = rememberReveal(shown.size, 1200)
    ChartPanel("Court from above with ${shown.size} landings of ${e.totalEvents}, engine candidates") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BigStat("${shown.size}", "landings", c.orange)
                val near = shown.count { it.yM < CourtModel.NET_Y_M }
                BigStat("$near", "on the near half", Color(0xFFFFC9A8))
                BigStat("${shown.size - near}", "on the far half", Color(0xFFB9B2FF))
            }
            Canvas(Modifier.width(150.dp).aspectRatio(0.5f)) {
                val wM = (r - l).toFloat()
                val lM = CourtModel.LENGTH_M.toFloat()
                val pad = 0.6f
                val s = minOf(size.width / (wM + 2 * pad), size.height / (lM + 2 * pad))
                val ox = (size.width - wM * s) / 2
                val oy = (size.height - lM * s) / 2
                fun px(x: Double) = ox + (x - l).toFloat() * s
                fun py(y: Double) = oy + (lM - y.toFloat()) * s
                drawRoundRect(c.court, Offset(ox, oy), Size(wM * s, lM * s), CornerRadius(6f))
                for (seg in lines) {
                    drawLine(
                        Color.White.copy(alpha = if (seg.name == "net") 0.95f else 0.55f),
                        Offset(px(seg.start.first), py(seg.start.second)), Offset(px(seg.end.first), py(seg.end.second)),
                        strokeWidth = if (seg.name == "net") 4f else 1.6f,
                    )
                }
                shown.forEachIndexed { i, p ->
                    // Stagger each dot in by its index.
                    val k = ((reveal * shown.size) - i).coerceIn(0f, 1f)
                    if (k <= 0f) return@forEachIndexed
                    val o = Offset(px(p.xM), py(p.yM))
                    drawCircle(Brush.radialGradient(listOf(c.orange.copy(alpha = 0.55f * k), Color.Transparent), o, 26f), 26f, o)
                    drawCircle(Color(0xFFFFB27A).copy(alpha = k), 5f * k + 1f, o)
                }
            }
        }
    }
}

/**
 * The match as a waveform: one bar per rally candidate at its real time, height = its hits. The
 * longest rally glows and carries its count.
 */
@Composable
internal fun RallyPulse(rallies: List<RallyCandidate>) {
    val c = Elevate.colors
    if (rallies.isEmpty()) return
    val end = rallies.maxOf { it.endMs }.coerceAtLeast(1)
    val maxHits = rallies.maxOf { it.hitCount }.coerceAtLeast(1)
    val longest = rallies.maxBy { it.hitCount }
    val reveal = rememberReveal(rallies.size)
    val measurer = rememberTextMeasurer()
    ChartPanel("${rallies.size} rallies across the match; the longest has ${longest.hitCount} hits at ${mmss(longest.startMs)}") {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                BigStat("${rallies.size}", "rallies", Color.White)
                Spacer(Modifier.width(24.dp))
                BigStat("${longest.hitCount}", "hits in the longest", c.orange)
            }
            Spacer(Modifier.height(12.dp))
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val base = size.height - 16f
                val barW = (size.width / rallies.size * 0.6f).coerceIn(3f, 14f)
                rallies.forEach { rc ->
                    val x = rc.startMs.toFloat() / end * (size.width - barW)
                    val h = (rc.hitCount.toFloat() / maxHits) * (base - 24f) * reveal
                    val isLong = rc === longest
                    val brush = if (isLong) Brush.verticalGradient(listOf(Color(0xFFFFD0A8), c.orange), startY = base - h, endY = base)
                    else Brush.verticalGradient(listOf(Color(0xFFB9B2FF), Color(0xFF6656D2)), startY = base - h, endY = base)
                    drawRoundRect(brush, Offset(x, base - h), Size(barW, h), CornerRadius(barW / 2))
                    if (isLong && reveal > 0.95f) {
                        drawCircle(c.orange.copy(alpha = 0.35f), barW * 1.6f, Offset(x + barW / 2, base - h))
                    }
                }
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, base), Offset(size.width, base), strokeWidth = 2f)
                val st = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                drawText(measurer, "0:00", Offset(0f, base + 2f), style = st)
                val endText = mmss(end)
                val w = measurer.measure(endText, st).size.width
                drawText(measurer, endText, Offset(size.width - w, base + 2f), style = st)
            }
        }
    }
}

/**
 * Tempo as a ring: the share of gaps between consecutive hits (inside rallies) that were fast
 * (< 0.8 s), steady, or slow (> 1.4 s). The median sits in the middle.
 */
@Composable
internal fun TempoRing(hits: List<HitCandidate>, rallies: List<RallyCandidate>) {
    val c = Elevate.colors
    val gaps = remember(hits, rallies) {
        val times = hits.map { it.tMs }.sorted()
        rallies.flatMap { r -> times.filter { it in r.startMs..r.endMs }.zipWithNext { a, b -> (b - a) / 1000.0 } }
    }
    if (gaps.size < 3) return
    val fast = gaps.count { it < 0.8 }
    val slow = gaps.count { it > 1.4 }
    val steady = gaps.size - fast - slow
    val median = gaps.sorted()[gaps.size / 2]
    val reveal = rememberReveal(gaps.size)
    val measurer = rememberTextMeasurer()
    val fastC = c.orange
    val steadyC = Color(0xFFB9B2FF)
    val slowC = Color(0xFF5FD1A8)
    ChartPanel(String.format(Locale.ROOT, "Tempo: median %.1f s between hits; %d fast, %d steady, %d slow of %d gaps", median, fast, steady, slow, gaps.size)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(132.dp)) {
                val stroke = 22f
                val inset = stroke / 2 + 4f
                val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                val tl = Offset(inset, inset)
                drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, tl, arcSize, style = Stroke(stroke))
                var start = -90f
                listOf(fast to fastC, steady to steadyC, slow to slowC).forEach { (n, col) ->
                    val sweep = 360f * n / gaps.size * reveal
                    if (sweep > 0.5f) drawArc(col, start + 1.5f, (sweep - 3f).coerceAtLeast(0.5f), false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    start += 360f * n / gaps.size
                }
                val big = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val t = String.format(Locale.ROOT, "%.1fs", median)
                val m = measurer.measure(t, big)
                drawText(m, topLeft = Offset((size.width - m.size.width) / 2, (size.height - m.size.height) / 2 - 8f))
                val small = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                val m2 = measurer.measure("between hits", small)
                drawText(m2, topLeft = Offset((size.width - m2.size.width) / 2, (size.height + m.size.height) / 2 - 6f))
            }
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(fastC, "Fast", fast, gaps.size)
                LegendDot(steadyC, "Steady", steady, gaps.size)
                LegendDot(slowC, "Slow", slow, gaps.size)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, n: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text("$label ", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text("${n * 100 / total}%", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

/** How long rallies run: four buckets of hits per rally, each bar with its count. */
@Composable
internal fun RallyLengthBars(rallies: List<RallyCandidate>) {
    val c = Elevate.colors
    if (rallies.size < 3) return
    val buckets = listOf("2–3" to 2..3, "4–6" to 4..6, "7–10" to 7..10, "11+" to 11..Int.MAX_VALUE)
    val counts = buckets.map { (_, range) -> rallies.count { it.hitCount in range } }
    val max = counts.max().coerceAtLeast(1)
    val reveal = rememberReveal(rallies.size)
    val measurer = rememberTextMeasurer()
    ChartPanel("Rally length: " + buckets.indices.joinToString(", ") { "${counts[it]} rallies of ${buckets[it].first} hits" }) {
        Column {
            Text("Hits per rally", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                val labelH = 18f
                val base = size.height - labelH - 4f
                val slot = size.width / buckets.size
                val bw = slot * 0.56f
                buckets.forEachIndexed { i, (label, _) ->
                    val h = counts[i].toFloat() / max * (base - 22f) * reveal
                    val x = i * slot + (slot - bw) / 2
                    val top = counts[i] == max
                    drawRoundRect(
                        Brush.verticalGradient(if (top) listOf(Color(0xFFFFD0A8), c.orange) else listOf(Color(0xFFB9B2FF), Color(0xFF4B45A8)), startY = base - h, endY = base),
                        Offset(x, base - h), Size(bw, h), CornerRadius(10f),
                    )
                    val n = measurer.measure("${counts[i]}", TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    drawText(n, topLeft = Offset(x + (bw - n.size.width) / 2, base - h - n.size.height - 2f))
                    val lb = measurer.measure(label, TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp))
                    drawText(lb, topLeft = Offset(x + (bw - lb.size.width) / 2, base + 4f))
                }
            }
        }
    }
}

private fun mmss(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms / 60_000, (ms / 1000) % 60)
