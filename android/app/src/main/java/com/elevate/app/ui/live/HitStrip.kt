package com.elevate.app.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevate.app.ui.theme.Elevate
import kotlin.math.abs

/** Pure parts of [HitStrip] (wording and tap hit-testing), unit-tested. */
internal object HitStripLogic {

    /** "3 hit candidates · engine" — the real count, always called candidates. */
    fun caption(count: Int): String = "$count hit ${if (count == 1) "candidate" else "candidates"} · engine"

    /** x of [tMs] on a bar [widthPx] wide for a clip [durationMs] long. */
    fun xOf(tMs: Long, durationMs: Long, widthPx: Float): Float =
        if (durationMs <= 0) 0f else widthPx * (tMs.coerceIn(0, durationMs).toFloat() / durationMs)

    /**
     * The hit time whose tick is nearest to a tap at [tapX], if within [slopPx]; else null (a tap
     * between ticks does nothing).
     */
    fun tickAt(tapX: Float, hitsMs: List<Long>, durationMs: Long, widthPx: Float, slopPx: Float): Long? {
        if (durationMs <= 0 || widthPx <= 0f) return null
        var best: Long? = null
        var bestD = Float.MAX_VALUE
        for (t in hitsMs) {
            if (t < 0 || t > durationMs) continue
            val d = abs(xOf(t, durationMs, widthPx) - tapX)
            if (d < bestD) { bestD = d; best = t }
        }
        return best?.takeIf { bestD <= slopPx }
    }
}

/**
 * A thin bar under the video: one tick per engine hit candidate at its time, a playhead at
 * [positionMs], and the caption "N hit candidates · engine". Tapping a tick seeks there.
 * Nothing on it is a confirmed hit.
 */
@Composable
fun HitStrip(
    durationMs: Long,
    hitsMs: List<Long>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Elevate.colors
    val currentHits = rememberUpdatedState(hitsMs)
    val currentDuration = rememberUpdatedState(durationMs)
    val currentSeek = rememberUpdatedState(onSeek)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (durationMs > 0) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics { contentDescription = HitStripLogic.caption(hitsMs.size) + ". Tap a tick to play from it." }
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            HitStripLogic.tickAt(
                                tap.x, currentHits.value, currentDuration.value, size.width.toFloat(), 16.dp.toPx(),
                            )?.let { currentSeek.value(it) }
                        }
                    },
            ) {
                val mid = size.height / 2
                drawLine(c.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 3.dp.toPx())
                val tickH = 16.dp.toPx()
                for (t in hitsMs) {
                    if (t < 0 || t > durationMs) continue
                    val x = HitStripLogic.xOf(t, durationMs, size.width)
                    drawLine(c.ink, Offset(x, mid - tickH / 2), Offset(x, mid + tickH / 2), strokeWidth = 2.dp.toPx())
                }
                val px = HitStripLogic.xOf(positionMs, durationMs, size.width)
                drawLine(c.orange, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        Text(HitStripLogic.caption(hitsMs.size), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}
