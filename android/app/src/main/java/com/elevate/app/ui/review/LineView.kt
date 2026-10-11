package com.elevate.app.ui.review

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevate.app.domain.review.BoundaryBox
import com.elevate.app.domain.review.LineCall
import com.elevate.app.domain.review.LineFrame
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

private val Outside = Color(0xFF3A3F4B)
private val Inside = Color(0xFF173B32)
private val Paint = Color(0xFFFFFFFF)
private val Band = Color(0xFFFFD866)
private val Dot = Color(0xFFFF7A3D)
private val RulerBg = Color(0xE6101319)

/**
 * The magnified line view, drawn from the [call] and nothing else: the court from above, turned so the
 * line runs left to right and the inside is below it. The painted line is drawn at its real 40 mm, the
 * landing is a point, the oval is the 95% margin of error, and a centimetre ruler runs down the left edge.
 *
 * [zoom] 0..1 moves the camera from a wide look at the floor to the magnified slice (an exponential zoom,
 * so it feels even); [reveal] 0..1 grows the oval and draws the gap. Both are 1 for a still picture.
 * [compact] drops the ruler and the words, for the small "how we know" picture.
 */
@Composable
fun LineView(call: LineCall, zoom: Float, reveal: Float, modifier: Modifier = Modifier, compact: Boolean = false) {
    val frame = remember(call) { LineFrame.of(call) }
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        if (frame == null) return@Canvas
        drawLineView(frame, call, zoom, reveal, compact, measurer)
    }
}

private fun DrawScope.drawLineView(f: LineFrame, call: LineCall, zoomRaw: Float, reveal: Float, compact: Boolean, tm: TextMeasurer) {
    val dp = 1.dp.toPx()
    val w = size.width
    val h = size.height
    val gutter = if (compact) 0f else 46f * dp

    // Camera: wide look -> magnified slice. Spans are in metres of v (distance across the line).
    val (zlo, zhi) = f.zoomedRange()
    val s1 = zhi - zlo
    val c1 = (zlo + zhi) / 2
    val lo0 = minOf(-0.8, f.landingV - 0.5)
    val hi0 = maxOf(2.2, f.landingV + 0.5)
    val s0 = max(hi0 - lo0, s1 * 1.8)
    val c0 = (lo0 + hi0) / 2
    val z = FastOutSlowInEasing.transform(zoomRaw.coerceIn(0f, 1f)).toDouble()
    val span = s0.pow(1 - z) * s1.pow(z)
    val centre = c0 + (c1 - c0) * z
    val top = centre - span / 2
    val pxPerM = h / span
    fun y(v: Double) = ((v - top) * pxPerM).toFloat()
    val cx = gutter + (w - gutter) / 2
    fun x(u: Double) = (cx + u * pxPerM).toFloat()

    // Floor: outside above the line, the court below it.
    drawRect(Outside, size = Size(w, h))
    val yLine = y(0.0)
    if (yLine < h) drawRect(Inside, topLeft = Offset(0f, yLine.coerceAtLeast(0f)), size = Size(w, h - yLine.coerceAtLeast(0f)))

    // The painted line: 40 mm wide, inside the boundary (the boundary is its outer edge), never thinner than a hairline.
    val paintTop = yLine
    val paintH = max((BoundaryBox.LINE_WIDTH_M * pxPerM).toFloat(), 1.5f * dp)
    drawRect(Paint, topLeft = Offset(0f, paintTop), size = Size(w, paintH))

    val r = reveal.coerceIn(0f, 1f)
    val d = f.landingV
    val px = x(0.0)
    val py = y(d)

    // Margin of error: the 95% oval, growing out of the landing point.
    if (r > 0f) {
        val path = Path()
        f.ellipse().forEachIndexed { i, (u, v) ->
            val ex = x(u * r)
            val ey = y(d + (v - d) * r)
            if (i == 0) path.moveTo(ex, ey) else path.lineTo(ex, ey)
        }
        path.close()
        drawPath(path, Band.copy(alpha = 0.30f))
        drawPath(path, Band.copy(alpha = 0.95f), style = Stroke(width = 1.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * dp, 4f * dp))))
    }

    // The gap: a plumb line from the line's outer edge to the landing, with its length in words.
    if (r > 0f && abs(d) > 0.002) {
        val gapEnd = y(d * r)
        drawLine(Color.White, Offset(px, yLine), Offset(px, gapEnd), strokeWidth = 2f * dp, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(px - 7 * dp, yLine), Offset(px + 7 * dp, yLine), strokeWidth = 2f * dp, cap = StrokeCap.Round)
        if (!compact && r > 0.85f) {
            val label = "${call.gapCm} cm"
            val layout = tm.measure(label, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1F45)))
            val midY = (yLine + py) / 2
            val lx = px + 10 * dp
            val ly = midY - layout.size.height / 2f
            drawRoundRect(Color.White, topLeft = Offset(lx - 6 * dp, ly - 2 * dp), size = Size(layout.size.width + 12 * dp, layout.size.height + 4 * dp), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10 * dp))
            drawText(layout, topLeft = Offset(lx, ly))
        }
    }

    // The landing point.
    val dotR = if (compact) 3.5f * dp else 6f * dp
    drawCircle(Color.White, dotR + 2.5f * dp, Offset(px, py))
    drawCircle(Dot, dotR, Offset(px, py))

    if (compact) return

    // Words on the floor.
    val faint = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
    val outsideText = tm.measure("outside the court", faint)
    val insideText = tm.measure("inside the court", faint)
    val labelX = w - outsideText.size.width - 10 * dp
    if (yLine - outsideText.size.height - 8 * dp > 0f) drawText(outsideText, topLeft = Offset(labelX, yLine - outsideText.size.height - 8 * dp))
    if (yLine + paintH + 8 * dp + insideText.size.height < h) drawText(insideText, topLeft = Offset(w - insideText.size.width - 10 * dp, yLine + paintH + 8 * dp))

    // Ruler: centimetres from the line's outer edge, + inside (down).
    drawRect(RulerBg, size = Size(gutter, h))
    val pxPerCm = pxPerM / 100.0
    val step = LineFrame.rulerStepCm(pxPerCm)
    val loCm = floor(top * 100 / step).toInt() * step
    val hiCm = ceil((top + span) * 100 / step).toInt() * step
    val tick = TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium)
    var cm = loCm
    while (cm <= hiCm) {
        val ty = y(cm / 100.0)
        if (ty in -2f..h + 2f) {
            val major = (cm / step) % 5 == 0
            val len = if (major) 16f * dp else 8f * dp
            drawLine(Color.White, Offset(gutter - len, ty), Offset(gutter, ty), strokeWidth = (if (major) 1.5f else 1f) * dp)
            if (major && ty > 8 * dp && ty < h - 8 * dp) {
                val t = tm.measure(cm.toString(), tick)
                drawText(t, topLeft = Offset(gutter - len - t.size.width - 3 * dp, ty - t.size.height / 2f))
            }
        }
        cm += step
    }
    // The line itself on the ruler (0) and the margin band beside the landing.
    drawLine(Color.White, Offset(0f, yLine), Offset(gutter, yLine), strokeWidth = 2.5f * dp)
    if (r > 0f) {
        val b0 = y(d - f.bandV * r)
        val b1 = y(d + f.bandV * r)
        drawRect(Band.copy(alpha = 0.85f), topLeft = Offset(gutter - 5 * dp, b0), size = Size(5 * dp, max(2f * dp, b1 - b0)))
    }
    val cmLabel = tm.measure("cm", tick.copy(color = Color.White.copy(alpha = 0.7f)))
    drawText(cmLabel, topLeft = Offset(4 * dp, 4 * dp))
}
