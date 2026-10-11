package com.elevate.app.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.theme.Elevate

/** Big stat of the live overview: "101/767 frames with the shuttle seen", denominator only when exact. */
@Composable
internal fun LiveShuttleStat(live: LiveOverviewState) {
    val c = Elevate.colors
    val frames = live.framesInWindows?.takeIf { it > 0 && live.shuttleFrames <= it }
    val line = live.shuttleLine ?: return
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$line · engine candidates" }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append("${live.shuttleFrames}")
                if (frames != null) withStyle(SpanStyle(color = c.orangeText)) { append("/$frames") }
            },
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp),
            color = c.ink,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (frames != null) "frames with the shuttle seen" else line,
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
            )
            Text(
                "engine detections" + (live.engineVersion?.let { " · $it" } ?: "") + " · not confirmed",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
            )
        }
    }
}

/** Per-window strip: read windows filled, reported-but-unread lighter, failed in red, gaps empty. */
@Composable
internal fun WindowStrip(live: LiveOverviewState, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    val total = live.timelineMs
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(live.coverageLine, style = MaterialTheme.typography.labelLarge, color = c.ink)
        if (total > 0 && live.segments.isNotEmpty()) {
            Canvas(Modifier.fillMaxWidth().height(14.dp).clearAndSetSemantics { contentDescription = "Engine windows: ${live.coverageLine}" }) {
                fun x(ms: Long) = size.width * (ms.coerceIn(0, total).toFloat() / total)
                val r = CornerRadius(4.dp.toPx())
                drawRoundRect(Color.White, Offset.Zero, size, r)
                val gap = 2.dp.toPx()
                for (s in live.segments) {
                    val color = when (s.status) {
                        WindowSegment.Status.READ -> c.orange
                        WindowSegment.Status.REPORTED -> c.peachDeep
                        WindowSegment.Status.FAILED -> c.error
                    }
                    val x0 = x(s.startMs)
                    val w = (x(s.endMs) - x0 - gap).coerceAtLeast(1f)
                    drawRoundRect(color, Offset(x0, 0f), Size(w, size.height), r)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(clock(0), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = c.inkMuted, modifier = Modifier.weight(1f))
                Text(clock(total), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = c.inkMuted)
            }
            val perWindow = live.segments.filter { it.status == WindowSegment.Status.READ && it.shuttleFrames != null }
            if (perWindow.isNotEmpty()) {
                Text(
                    "Shuttle frames per window: " + perWindow.joinToString(" · ") { "${it.shuttleFrames}" },
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkMuted,
                )
            }
        }
    }
}

/** "Match summary (score, landings) pending the engine" in one plain card. */
@Composable
internal fun PendingLine(text: String) {
    val c = Elevate.colors
    ElevateCard(tint = c.lavender) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink)
    }
}

/** Rally candidates from engine hit candidates (reference 19-rallies look), each with a play button. */
@Composable
internal fun LiveRallyList(rows: List<LiveRallyRow>, onOpenRally: (rallyId: String, startMs: Long, endMs: Long) -> Unit) {
    val c = Elevate.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
                    .clickable(role = Role.Button, onClickLabel = "Play from ${clock(r.startMs)}") { onOpenRally(r.rallyId, r.startMs, r.endMs) }
                    .semantics(mergeDescendants = true) { contentDescription = "${r.label}, engine candidates, not confirmed" }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.label.substringBefore(" · "), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(r.label.substringAfter(" · "), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                }
                Pill("candidate", c.lavender, c.lavenderInk)
                Icon(
                    ElevateIcons.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(c.orange).padding(10.dp),
                )
            }
        }
    }
}
