package com.elevate.app.ui.overview

import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.ExplainBox
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.elevate.app.domain.analysis.HitRallies
import com.elevate.app.ui.theme.Elevate

/** Text of [HitTimeline]; pure, so the wording and every denominator are unit-tested. */
internal object HitTimelineText {

    const val EMPTY = "No hit candidates yet"

    /** "12 hits · 3 rallies" (+ " so far" while windows are still coming in). */
    fun caption(s: HitTimelineState): String {
        val soFar = s.recordSource == null && (!s.totalIsExpected || s.windowsRead < s.windowsTotal)
        return "${s.hits.size} ${if (s.hits.size == 1) "hit" else "hits"} · " +
            "${s.rallies.size} ${if (s.rallies.size == 1) "rally" else "rallies"}" + if (soFar) " so far" else ""
    }

    /** "4 of 5 windows". Without the engine's expected total, the total is the windows reported so far. */
    fun windows(s: HitTimelineState): String =
        "${s.windowsRead} of ${s.windowsTotal} window${if (s.windowsTotal == 1) "" else "s"}"

    /** Shown instead of the caption when there are no hits; keeps the window denominator when known. */
    fun emptyDetail(s: HitTimelineState): String? =
        if (s.windowsTotal > 0) "${windows(s)} analysed so far, none with a hit candidate" else null

    fun rallyLabel(index: Int, startMs: Long, endMs: Long, hits: Int): String =
        "Rally candidate ${index + 1} from engine hit candidates, ${clock(startMs)} to ${clock(endMs)}, " +
            "$hits hit candidates, not confirmed. Plays from ${clock(startMs)}"

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
}

/** `onOpenRally` id prefix for a rally candidate grouped from hits (not a `match.v1` rally). */
const val HIT_RALLY_ID_PREFIX = "hit_rally_candidate_"

private val TrackHeight = 56.dp
private val MinTouch = 24.dp

/**
 * Track T: a tick per engine hit candidate on the clip's timeline, a dotted span per rally
 * candidate grouped from them, and a caption with the counts and their denominators. Everything is
 * a candidate; nothing here is a confirmed hit or rally. Tap a span to play from its start.
 */
@Composable
internal fun HitTimeline(
    state: HitTimelineState,
    onOpenRally: (rallyId: String, startMs: Long, endMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Elevate.colors
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (state.hits.isEmpty()) {
            Text(HitTimelineText.EMPTY, style = MaterialTheme.typography.titleMedium, color = c.ink)
            HitTimelineText.emptyDetail(state)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.inkMuted) }
            return@Column
        }
        Text(HitTimelineText.caption(state), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(8.dp))
        if (state.timelineMs > 0) ExplainBox(Explanations.Estimate, Modifier.fillMaxWidth()) { Track(state, onOpenRally) }
    }
}

@Composable
private fun Track(state: HitTimelineState, onOpenRally: (String, Long, Long) -> Unit) {
    val c = Elevate.colors
    val durationMs = state.timelineMs
    BoxWithConstraints(Modifier.fillMaxWidth().height(TrackHeight)) {
        val w = maxWidth
        fun x(ms: Long): Dp = w * (ms.coerceIn(0, durationMs).toFloat() / durationMs)
        Canvas(
            Modifier.fillMaxSize().clearAndSetSemantics {
                contentDescription = "Hit candidate timeline: ${state.hits.size} hit candidates, ${state.rallies.size} rally candidates"
            },
        ) {
            fun px(ms: Long) = size.width * (ms.coerceIn(0, durationMs).toFloat() / durationMs)
            val mid = size.height / 2
            // Windows read: the only stretches where a missing tick means "no candidate found".
            for (r in state.coveredMs) {
                val x0 = px(r.first)
                drawRect(c.line, Offset(x0, mid - 4.dp.toPx()), Size((px(r.last) - x0).coerceAtLeast(1f), 8.dp.toPx()))
            }
            drawLine(c.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            val barH = 36.dp.toPx()
            for (r in state.rallies) {
                val x0 = px(r.startMs)
                val bw = maxOf(px(r.endMs) - x0, 3.dp.toPx())
                val tl = Offset(x0, mid - barH / 2)
                drawRoundRect(c.peach, tl, Size(bw, barH), CornerRadius(4.dp.toPx()))
                drawRoundRect(c.orange, tl, Size(bw, barH), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
            }
            val tickH = 24.dp.toPx()
            for (h in state.hits) {
                val tx = px(h.tMs)
                drawLine(c.ink, Offset(tx, mid - tickH / 2), Offset(tx, mid + tickH / 2), strokeWidth = 2.dp.toPx())
            }
        }
        // One accessible tap target per rally candidate (at least 24 dp wide, centred on the span).
        for ((i, r) in state.rallies.withIndex()) {
            val start = x(r.startMs)
            val bw = x(r.endMs) - start
            val tw = max(bw, MinTouch)
            val left = min(max(start - (tw - bw) / 2, 0.dp), w - tw)
            Box(
                Modifier
                    .offset(x = left)
                    .width(tw)
                    .fillMaxHeight()
                    .clickable(onClickLabel = "Play from ${clock(r.startMs)}", role = Role.Button) {
                        onOpenRally("$HIT_RALLY_ID_PREFIX${i + 1}", r.startMs, r.endMs)
                    }
                    .semantics { contentDescription = HitTimelineText.rallyLabel(i, r.startMs, r.endMs, r.hitCount) },
            )
        }
    }
    Text(
        "${clock(0)} … ${clock(durationMs)}",
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = c.inkMuted,
    )
}
