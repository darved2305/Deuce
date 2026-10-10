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
}
