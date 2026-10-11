package com.elevate.app.ui.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.elevate.app.data.record.FramingGuide
import com.elevate.app.data.record.Level

/**
 * The court framing guide over the live picture: a trapezoid where a whole court should sit, and a
 * level line that turns green when the phone is straight. Drawn only; it never touches the recording.
 */
@Composable
fun FramingOverlay(rollDeg: Double?, modifier: Modifier = Modifier) {
    val quad = FramingGuide.trapezoid
    val quality = rollDeg?.let { Level.quality(it) }
    val levelColor = when (quality) {
        Level.Quality.Level -> Color(0xFF59D36B)
        Level.Quality.Close -> Color(0xFFFFC857)
        Level.Quality.Tilted -> Color(0xFFFF6B5E)
        null -> Color.White.copy(alpha = 0.6f)
    }
    Box(modifier.fillMaxSize().clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            fun p(xy: Pair<Float, Float>) = Offset(xy.first * size.width, xy.second * size.height)
            val path = Path().apply {
                moveTo(p(quad.farLeft).x, p(quad.farLeft).y)
                lineTo(p(quad.farRight).x, p(quad.farRight).y)
                lineTo(p(quad.nearRight).x, p(quad.nearRight).y)
                lineTo(p(quad.nearLeft).x, p(quad.nearLeft).y)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.10f))
            drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 14f))))
            // Net: a line across the middle of the trapezoid.
            val nl = Offset((p(quad.farLeft).x + p(quad.nearLeft).x) / 2, (p(quad.farLeft).y + p(quad.nearLeft).y) / 2)
            val nr = Offset((p(quad.farRight).x + p(quad.nearRight).x) / 2, (p(quad.farRight).y + p(quad.nearRight).y) / 2)
            drawLine(Color.White.copy(alpha = 0.5f), nl, nr, strokeWidth = 2.dp.toPx())

            // Level line: horizontal when the phone is straight, rotated by the measured roll otherwise.
            val c = Offset(size.width / 2, size.height * 0.14f)
            val half = size.width * 0.12f
            rotate(degrees = (-(rollDeg ?: 0.0)).toFloat(), pivot = c) {
                drawLine(levelColor, Offset(c.x - half, c.y), Offset(c.x + half, c.y), strokeWidth = 4.dp.toPx())
            }
            drawLine(Color.White.copy(alpha = 0.5f), Offset(c.x - half * 1.3f, c.y), Offset(c.x - half * 1.1f, c.y), strokeWidth = 2.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), Offset(c.x + half * 1.1f, c.y), Offset(c.x + half * 1.3f, c.y), strokeWidth = 2.dp.toPx())
        }
    }
}
