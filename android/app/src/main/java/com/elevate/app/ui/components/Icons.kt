package com.elevate.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The handful of line icons Phase 1 needs, drawn on a 24-unit grid in one stroke style. */
object ElevateIcons {
    private fun icon(name: String, filled: Boolean = false, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    val Play = icon("play", filled = true) { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
    val Pause = icon("pause", filled = true) {
        moveTo(7f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(7f, 19f); close()
        moveTo(14f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(14f, 19f); close()
    }
    val Replay = icon("replay") {
        moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, false, 6.3f, 6.3f)
        moveTo(4f, 4f); lineTo(4f, 8f); lineTo(8f, 8f)
    }
    val Back = icon("back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    val Film = icon("film") {
        moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close()
        moveTo(9f, 4f); lineTo(9f, 20f); moveTo(15f, 4f); lineTo(15f, 20f)
        moveTo(5f, 9f); lineTo(9f, 9f); moveTo(5f, 15f); lineTo(9f, 15f)
        moveTo(15f, 9f); lineTo(19f, 9f); moveTo(15f, 15f); lineTo(19f, 15f)
    }
    val Trail = icon("trail") {
        moveTo(4f, 18f); curveTo(8f, 6f, 14f, 4f, 20f, 8f)
        moveTo(20f, 8f); lineTo(20.01f, 8f)
    }
    /** Filming tip: aim at the court (two rings). */
    val Target = icon("target") {
        moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f); close()
        moveTo(12f, 8f); arcTo(4f, 4f, 0f, true, true, 11.99f, 8f); close()
    }
    /** Filming tip: frame rate. */
    val Bolt = icon("bolt") { moveTo(13f, 2f); lineTo(4f, 14f); lineTo(12f, 14f); lineTo(11f, 22f); lineTo(20f, 10f); lineTo(12f, 10f); close() }
    /** Filming tip: keep the camera still. */
    val Eye = icon("eye") {
        moveTo(2f, 12f); curveTo(5f, 6f, 19f, 6f, 22f, 12f); curveTo(19f, 18f, 5f, 18f, 2f, 12f); close()
        moveTo(12f, 9f); arcTo(3f, 3f, 0f, true, true, 11.99f, 9f); close()
    }
    /** Expand / collapse a row. */
    val ChevronDown = icon("chevron_down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    /** Clip check. */
    val Check = icon("check") { moveTo(5f, 12.5f); lineTo(10f, 17f); lineTo(19f, 7f) }
    /** Setup (sliders). */
    val Sliders = icon("sliders") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 4f); lineTo(9f, 10f); moveTo(15f, 14f); lineTo(15f, 20f)
    }
}
