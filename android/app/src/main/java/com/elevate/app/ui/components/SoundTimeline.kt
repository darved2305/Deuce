package com.elevate.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.elevate.app.domain.audio.RallyCandidate
import com.elevate.app.domain.audio.RallyParams
import com.elevate.app.domain.audio.SoundScanState
import com.elevate.app.ui.theme.Elevate
import java.util.Locale

/**
 * The "timeline from sound" (docs/plan/05 §3.2): rally candidates from local audio onsets as
 * DOTTED bars labelled "from sound", with their count and basis. Tap a bar to play from its start.
 * Candidates are provisional; they never look solid here (solid is for cloud-confirmed rallies).
 */
@Composable
fun SoundTimeline(
    durationMs: Long,
    candidates: List<RallyCandidate>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    state: SoundScanState,
    modifier: Modifier = Modifier,
    minOnsets: Int = RallyParams().minOnsets,
) {
    val c = Elevate.colors
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val (title, detail) = when (state) {
            is SoundScanState.Analysing -> {
                val f = state.fraction
                val of = state.totalMs?.let { " of ${clock(it)}" } ?: ""
                (if (f != null) "Listening for sound onsets · ${(f * 100).toInt()}%" else "Listening for sound onsets") to
                    "${clock(state.decodedMs)}$of of sound decoded on this phone"
            }
            SoundScanState.NoAudio -> "No audio: no sound-based rally hints" to null
            is SoundScanState.Failed -> "Sound scan failed" to state.reason
            is SoundScanState.Done -> if (candidates.isEmpty()) {
                "No rally candidates from sound" to "${state.onsets} sound ${plural(state.onsets, "onset")} in ${clock(state.audioMs)}, none grouped like a rally"
            } else {
                "${candidates.size} rally ${plural(candidates.size, "candidate")} from sound" to
                    "Provisional, not confirmed rallies · $minOnsets+ sound onsets each, from ${state.onsets} heard"
            }
        }
        Text(title, style = MaterialTheme.typography.labelMedium, color = c.ink)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        Spacer(Modifier.height(6.dp))
        when (state) {
            is SoundScanState.Analysing -> state.fraction?.let { ProgressLine(it) }
            is SoundScanState.Done -> if (durationMs > 0) Bars(durationMs, candidates, positionMs, onSeek)
            else -> Unit
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    val c = Elevate.colors
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.orange))
    }
}

private val TrackHeight = 48.dp
private val MinTouch = 24.dp

@Composable
private fun Bars(durationMs: Long, candidates: List<RallyCandidate>, positionMs: Long, onSeek: (Long) -> Unit) {
    val c = Elevate.colors
    BoxWithConstraints(Modifier.fillMaxWidth().height(TrackHeight)) {
        val w = maxWidth
        fun x(ms: Long): Dp = w * (ms.coerceIn(0, durationMs).toFloat() / durationMs)
        // Baseline, dotted candidate outlines and the playhead, drawn once.
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.height / 2
            drawLine(c.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            val barH = 20.dp.toPx()
            for (r in candidates) {
                val x0 = size.width * (r.startMs.coerceIn(0, durationMs).toFloat() / durationMs)
                val x1 = size.width * (r.endMs.coerceIn(0, durationMs).toFloat() / durationMs)
                val bw = maxOf(x1 - x0, 3.dp.toPx())
                val tl = Offset(x0, mid - barH / 2)
                drawRoundRect(c.peach, tl, Size(bw, barH), CornerRadius(4.dp.toPx()))
                drawRoundRect(c.orange, tl, Size(bw, barH), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
            }
            val px = size.width * (positionMs.coerceIn(0, durationMs).toFloat() / durationMs)
            drawLine(c.ink, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
        }
        // One accessible tap target per candidate (at least 24 dp wide, centred on the bar).
        for ((i, r) in candidates.withIndex()) {
            val start = x(r.startMs)
            val bw = x(r.endMs) - start
            val tw = max(bw, MinTouch)
            val left = min(max(start - (tw - bw) / 2, 0.dp), w - tw)
            val label = "Rally candidate ${i + 1} from sound, ${clock(r.startMs)} to ${clock(r.endMs)}, " +
                "${r.onsets} sound ${plural(r.onsets, "onset")}, not confirmed. Plays from ${clock(r.startMs)}"
            Box(
                Modifier
                    .offset(x = left)
                    .width(tw)
                    .fillMaxHeight()
                    .clickable(onClickLabel = "Play from ${clock(r.startMs)}", role = Role.Button) { onSeek(r.startMs) }
                    .semantics { contentDescription = label },
            )
        }
    }
    Text(
        "${clock(0)} … ${clock(durationMs)}",
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = c.inkMuted,
    )
}

private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

/** m:ss (h:mm:ss past an hour), truncating to whole seconds. */
internal fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
