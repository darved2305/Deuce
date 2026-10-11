package com.elevate.app.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.elevate.app.domain.tactics.Autopsy
import com.elevate.app.domain.tactics.GhostMoment
import com.elevate.app.domain.tactics.Pattern
import com.elevate.app.domain.tactics.Readability
import com.elevate.app.domain.tactics.TacticsView
import com.elevate.app.domain.tactics.YouSource
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.SectionTitle
import com.elevate.app.ui.theme.Elevate
import java.util.Locale

/** Plain words for a cell, from that player's own view. */
internal fun zoneWords(z: ZoneId): String {
    val row = when (z.row) { ZoneRow.FRONT -> "front"; ZoneRow.MIDDLE -> "mid"; ZoneRow.BACK -> "back" }
    val col = when (z.col) { 1 -> "left"; 2 -> "centre"; else -> "right" }
    return "$row $col"
}

private fun tacClock(ms: Long): String = String.format(Locale.ROOT, "%d:%04.1f", ms / 60_000, (ms % 60_000) / 1000.0)

/**
 * Tactics from the real engine output: how predictable you are, your opponent's habits, where you
 * stood against the ghost base, and where lost points turned. Every line opens the real video.
 */
@Composable
internal fun TacticsSection(view: TacticsView?, onOpenAt: (Long) -> Unit, onSwapYou: () -> Unit) {
    val c = Elevate.colors
    if (view == null) {
        ElevateCard { Text("Tactics appear once the court is set up and the first part is analysed.", color = c.inkMuted) }
        return
    }
    val s = view.state
    Row(verticalAlignment = Alignment.CenterVertically) {
        val who = when (view.youSource) {
            YouSource.MATCH_RECORD -> "You: the player you picked"
            YouSource.YOUR_TAP -> "You: the player you tapped"
            YouSource.CAMERA_SIDE -> "You: the player on the camera's side"
            YouSource.SWAPPED -> "You: the other player (swapped)"
            null -> "Players not identified yet"
        }
        Text(
            (if (view.live) "Live · " else "") + who,
            style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.weight(1f),
        )
        if (view.youSource != null) TextButton(onClick = onSwapYou) { Text("Swap") }
    }
    s.limits.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = c.inkMuted) }

    SectionTitle("How readable are you")
    ReadabilityCard(s.you, mine = true, onOpenAt)

    SectionTitle("Scout: your opponent")
    ReadabilityCard(s.opponent, mine = false, onOpenAt)

    SectionTitle("Ghost base")
    GhostCard(s.ghosts, onOpenAt)

    SectionTitle("Point autopsy")
    AutopsyCard(s.autopsies, s.rallies, onOpenAt)
}

@Composable
private fun ReadabilityCard(r: Readability?, mine: Boolean, onOpenAt: (Long) -> Unit) {
    val c = Elevate.colors
    ElevateCard {
        if (r == null || r.shots == 0) {
            Text("No shots with a known destination yet.", color = c.inkMuted)
            return@ElevateCard
        }
        if (r.patterns.isEmpty()) {
            Text("No spot repeated often enough yet (${r.shots} shots, a pattern needs 4 from one spot).", color = c.inkMuted)
            return@ElevateCard
        }
        val pct = if (r.repeatedShots > 0) r.usualTarget * 100 / r.repeatedShots else 0
        Text(
            if (mine) "$pct% predictable: ${r.usualTarget} of ${r.repeatedShots} shots from your usual spots went to your usual target"
            else "${r.usualTarget} of ${r.repeatedShots} of their shots from repeated spots went to their usual target",
            style = MaterialTheme.typography.titleMedium, color = c.ink,
        )
        Text("${r.shots} shots with a known destination · engine candidates", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        Spacer(Modifier.height(8.dp))
        r.patterns.take(3).forEach { p -> PatternRow(p, mine, onOpenAt) }
    }
}

@Composable
private fun PatternRow(p: Pattern, mine: Boolean, onOpenAt: (Long) -> Unit) {
    val c = Elevate.colors
    val who = if (mine) "you" else "they"
    val line = "From ${zoneWords(p.from)} $who sent it to the ${zoneWords(p.to)} ${p.count} of ${p.total} times"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = "$line. Watch the first one." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoneDuo(p.from, p.to, Modifier.size(44.dp, 64.dp))
        Spacer(Modifier.width(10.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
        TextButton(onClick = { onOpenAt(p.hitTimesMs.first()) }) { Text("Watch") }
    }
}

/** Two halves, net in the middle: the hitter's cell (bottom) and the target cell (top), both from the hitter's view. */
@Composable
private fun ZoneDuo(from: ZoneId, to: ZoneId, modifier: Modifier) {
    val c = Elevate.colors
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(c.court, size = Size(w, h))
        drawLine(Color.White, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 3f)
        fun cell(z: ZoneId, top: Boolean): Pair<Offset, Size> {
            val cw = w / 3
            val ch = h / 2 / 3
            val col = if (top) z.col - 1 else 3 - z.col // the hitter faces the net: their left is on the right at the top
            // Top half: rows run from the net (bottom of the half) back to the top edge.
            val rowFromNet = z.row.ordinal
            val y = if (top) h / 2 - (rowFromNet + 1) * ch else h / 2 + rowFromNet * ch
            val x = if (top) col * cw else (2 - col) * cw
            return Offset(x, y) to Size(cw, ch)
        }
        val (fo, fs) = cell(from, top = false)
        drawRect(c.lavenderInk.copy(alpha = 0.85f), fo, fs)
        val (to2, ts) = cell(to, top = true)
        drawRect(c.orange, to2, ts)
    }
}

@Composable
private fun GhostCard(ghosts: List<GhostMoment>, onOpenAt: (Long) -> Unit) {
    val c = Elevate.colors
    ElevateCard {
        if (ghosts.isEmpty()) {
            Text("Needs your position at their contacts and at least 3 of their shots with a destination.", color = c.inkMuted)
            return@ElevateCard
        }
        val sorted = ghosts.map { it.offM }.sorted()
        val median = sorted[sorted.size / 2]
        val far = ghosts.count { it.offM >= 1.5 }
        Text(
            String.format(Locale.ROOT, "When they hit, you stood a median %.1f m from your ghost base", median),
            style = MaterialTheme.typography.titleMedium, color = c.ink,
        )
        Text(
            "$far of ${ghosts.size} of their contacts you were 1.5 m or more away · ghost = where this opponent actually sent it from that spot",
            style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
        )
        Spacer(Modifier.height(8.dp))
        val worst = ghosts.sortedByDescending { it.offM }.take(3)
        worst.forEach { g ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostCourt(g, Modifier.size(48.dp, 96.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(String.format(Locale.ROOT, "%s · %.1f m off base", tacClock(g.tMs), g.offM), color = c.ink)
                    Text(
                        if (g.basis > 0) "Ghost from ${g.basis} of their shots from ${zoneWords(g.opponentZone)}" else "Ghost from all their shots",
                        style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
                    )
                }
                TextButton(onClick = { onOpenAt((g.tMs - 600).coerceAtLeast(0)) }) { Text("Watch") }
            }
        }
    }
}

/** Your half from above: you (orange) and the ghost base (dashed ring), true proportions. */
@Composable
private fun GhostCourt(g: GhostMoment, modifier: Modifier) {
    val c = Elevate.colors
    Canvas(modifier.semantics { contentDescription = "You and the ghost base on your half" }) {
        val wM = CourtModel.WIDTH_DOUBLES_M.toFloat()
        val lM = CourtModel.LENGTH_M.toFloat()
        val s = minOf(size.width / wM, size.height / lM)
        val ox = (size.width - wM * s) / 2
        val oy = (size.height - lM * s) / 2
        fun px(x: Double) = ox + (x.toFloat() + (CourtModel.WIDTH_DOUBLES_M - CourtModel.WIDTH_SINGLES_M).toFloat() / 2) * s
        fun py(y: Double) = oy + (lM - y.toFloat()) * s
        drawRect(c.court, Offset(ox, oy), Size(wM * s, lM * s))
        drawLine(Color.White, Offset(ox, py(CourtModel.NET_Y_M)), Offset(ox + wM * s, py(CourtModel.NET_Y_M)), strokeWidth = 3f)
        val you = Offset(px(g.you.xM), py(g.you.yM))
        val ghost = Offset(px(g.ghost.xM), py(g.ghost.yM))
        drawLine(Color.White.copy(alpha = 0.7f), you, ghost, strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        drawCircle(c.lavender, 7f, ghost, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
        drawCircle(c.orange, 6f, you)
    }
}

@Composable
private fun AutopsyCard(list: List<Autopsy>, rallies: Int, onOpenAt: (Long) -> Unit) {
    val c = Elevate.colors
    ElevateCard {
        if (list.isEmpty()) {
            Text("No lost rally could be estimated yet ($rallies rallies; a winner needs a clear last landing).", color = c.inkMuted)
            return@ElevateCard
        }
        Text("${list.size} of $rallies rallies lost (model estimate from the last landing)", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(6.dp))
        list.sortedByDescending { it.offM ?: 0.0 }.take(5).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Rally at ${tacClock(a.startMs)}", color = c.ink)
                    Text(
                        if (a.turnMs != null) "${a.reason} · ${a.shotsAfterTurn} shot${if (a.shotsAfterTurn == 1) "" else "s"} before the end" else a.reason,
                        style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
                    )
                }
                TextButton(onClick = { onOpenAt(((a.turnMs ?: a.startMs) - 1_000).coerceAtLeast(0)) }) { Text(if (a.turnMs != null) "See the turn" else "Watch") }
            }
        }
    }
}
