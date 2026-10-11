package com.elevate.app.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.elevate.app.ui.theme.Elevate

/** Small line icons for the coach report actions, in the same stroke style as ElevateIcons. */
private object CoachIcons {
    private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit) =
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

    val Speaker = icon("speaker") {
        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
        moveTo(16.5f, 9f); curveTo(18f, 10.5f, 18f, 13.5f, 16.5f, 15f)
        moveTo(19f, 6.5f); curveTo(22f, 9.5f, 22f, 14.5f, 19f, 17.5f)
    }
    val Stop = icon("stop", filled = true) { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
    val Share = icon("share") {
        moveTo(12f, 4f); lineTo(12f, 15f)
        moveTo(8f, 8f); lineTo(12f, 4f); lineTo(16f, 8f)
        moveTo(5f, 12f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 12f)
    }
}

/** The Coach report header buttons: Listen (read the report aloud) and Share (the match card). */
@Composable
internal fun CoachActions(speaking: Boolean, onListen: () -> Unit, showShare: Boolean, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionPill(if (speaking) CoachIcons.Stop else CoachIcons.Speaker, if (speaking) "Stop" else "Listen", onListen)
        if (showShare) ActionPill(CoachIcons.Share, "Share", onShare)
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Elevate.colors
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = c.ink),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.orangeText)
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
    }
}
