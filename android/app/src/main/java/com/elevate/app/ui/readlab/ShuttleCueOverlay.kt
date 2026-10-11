package com.elevate.app.ui.readlab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.analysis.BallTrack
import com.elevate.app.domain.training.FitTransform
import com.elevate.app.domain.training.ShuttleCue
import com.elevate.app.ui.components.PillChip

/** What the cue needs: the real ball track and the image size its pixels are in (the open file's display size). */
class CueSource(val ball: BallTrack, val imageW: Int, val imageH: Int) {
    /** The cue for the frame frozen at [freezeUs] (source time), or null: nothing is drawn then. */
    fun cueAt(freezeUs: Long): ShuttleCue.Cue? = ShuttleCue.at(ReadLabSession.seekMs(freezeUs), ball)
}

/** What a screen needs to show the cue: the cue itself (null draws nothing), the image size, and the toggle. */
class CueUi(val cue: ShuttleCue.Cue?, val imageW: Int, val imageH: Int, val on: Boolean, val onToggle: () -> Unit)

/** The small "Shuttle cue" on/off chip; the cue defaults to on. */
@Composable
fun ShuttleCueToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    PillChip("Shuttle cue", on, onToggle, modifier)
}

private val Glow = Color(0xFFFFE9A0)
private val Dark = Color(0xB3000000)

/**
 * Draws [cue] over a video shown with ContentScale.Fit, filling the same box: a soft ring on the
 * shuttle, a short arrow along its travel and a faint tail of its last real positions. Image
 * pixels are mapped with the same uniform fit as the video itself ([FitTransform]); the arrow keeps
 * its image direction and has a fixed on-screen length, so it shows direction, never a landing.
 */
@Composable
fun ShuttleCueOverlay(cue: ShuttleCue.Cue?, imageW: Int, imageH: Int, modifier: Modifier = Modifier) {
    if (cue == null) return
    Canvas(modifier.fillMaxSize()) {
        val fit = FitTransform.of(imageW, imageH, size.width, size.height) ?: return@Canvas
        fun map(x: Float, y: Float) = Offset(fit.mapX(x), fit.mapY(y))
        val c = map(cue.x, cue.y)
        val dp = 1.dp.toPx()

        // Comet tail: faint dots, older ones fainter and smaller.
        val n = cue.tail.size
        cue.tail.forEachIndexed { i, p ->
            val f = (i + 1f) / (n + 1f)
            drawCircle(Glow.copy(alpha = 0.18f + 0.30f * f), radius = (2f + 1.5f * f) * dp, center = map(p.x, p.y))
        }

        // Soft glow, then a thin ring.
        val r = 14f * dp
        drawCircle(Brush.radialGradient(listOf(Glow.copy(alpha = 0.55f), Glow.copy(alpha = 0f)), center = c, radius = r * 2.1f), radius = r * 2.1f, center = c)
        drawCircle(Dark, radius = r, center = c, style = Stroke(width = 3.5f * dp))
        drawCircle(Color.White.copy(alpha = 0.92f), radius = r, center = c, style = Stroke(width = 2f * dp))

        // Direction: a short arrow from the ring's edge to a fixed 60 dp from the shuttle.
        if (cue.hasDirection) {
            val d = Offset(cue.dirX, cue.dirY)
            val start = c + d * r
            val tip = c + d * (ARROW_DP * dp)
            val perp = Offset(-d.y, d.x)
            val head = 9f * dp
            val back = tip - d * head
            for ((color, w) in listOf(Dark to 5f * dp, Color.White.copy(alpha = 0.92f) to 2.5f * dp)) {
                drawLine(color, start, tip, strokeWidth = w, cap = StrokeCap.Round)
                drawLine(color, tip, back + perp * (head * 0.55f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(color, tip, back - perp * (head * 0.55f), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}

private const val ARROW_DP = 60f
