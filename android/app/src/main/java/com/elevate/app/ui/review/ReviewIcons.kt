package com.elevate.app.ui.review

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.review.Verdict

/** The two verdict icons the shared set does not have, in the same 24-unit line style. The check and the cross are the shared ones. */
object ReviewIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** Balance scales: too close to call. */
    val Scale = icon("scale") {
        moveTo(12f, 4f); lineTo(12f, 20f)
        moveTo(7f, 20f); lineTo(17f, 20f)
        moveTo(4f, 8f); lineTo(20f, 8f)
        moveTo(4f, 8f); lineTo(1.8f, 14f); lineTo(6.2f, 14f); close()
        moveTo(20f, 8f); lineTo(17.8f, 14f); lineTo(22.2f, 14f); close()
    }

    /** An eye with a slash: not seen. */
    val EyeOff = icon("eye_off") {
        moveTo(2f, 12f); curveTo(5f, 6f, 19f, 6f, 22f, 12f); curveTo(19f, 18f, 5f, 18f, 2f, 12f); close()
        moveTo(12f, 9f); arcTo(3f, 3f, 0f, true, true, 11.99f, 9f); close()
        moveTo(4f, 4f); lineTo(20f, 20f)
    }

    fun forVerdict(v: Verdict): ImageVector = when (v) {
        Verdict.Inside -> com.elevate.app.ui.components.ElevateIcons.Check
        Verdict.Outside -> com.elevate.app.ui.components.ElevateIcons.Close
        Verdict.TooClose -> Scale
        Verdict.NotSeen -> EyeOff
    }
}

/** Verdict colours: solid badge colour (white icon on it is at least 4.5:1) and the soft card tint behind the words. */
data class VerdictColors(val solid: Color, val soft: Color)

object VerdictPalette {
    fun of(v: Verdict): VerdictColors = when (v) {
        Verdict.Inside -> VerdictColors(Color(0xFF1E7A45), Color(0xFFE4F2DE))
        Verdict.Outside -> VerdictColors(Color(0xFFB3261E), Color(0xFFFCE8E6))
        Verdict.TooClose -> VerdictColors(Color(0xFF9A5B00), Color(0xFFFFF0D1))
        Verdict.NotSeen -> VerdictColors(Color(0xFF5B6275), Color(0xFFECEEF2))
    }
}
