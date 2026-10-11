package com.elevate.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.repository.BackendStatus
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.Headline
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.SectionTitle
import com.elevate.app.ui.theme.Elevate

@Composable
fun ImportScreen(
    opening: Boolean,
    error: String?,
    onChoose: () -> Unit,
    backend: BackendStatus? = null,
    /** Upload / analysis status of an earlier pick, shown under the main card. */
    status: @Composable () -> Unit = {},
) {
    val c = Elevate.colors
    PeachPage {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            ElevateHeader(title = "Elevate")
            Column(Modifier.padding(horizontal = ScreenGutter)) {
                Spacer(Modifier.height(16.dp))
                Headline("Add your\nfirst match")
                Spacer(Modifier.height(24.dp))

                ElevateCard(tint = c.peachDeep.copy(alpha = 0.6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(
                            Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) { Icon(ElevateIcons.Film, contentDescription = null, tint = c.orange) }
                        Spacer(Modifier.size(16.dp))
                        Column {
                            Text("Pick a match from your gallery", style = MaterialTheme.typography.titleMedium, color = c.ink)
                            Text("It plays right here. You choose when to upload.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        text = if (opening) "Opening…" else "Add a match",
                        onClick = onChoose,
                        busy = opening,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                status()

                if (error != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        error,
                        color = c.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(c.errorSurface)
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                Spacer(Modifier.height(24.dp))
                SectionTitle("Filming tips")
                Tip(ElevateIcons.Target, "Both baselines in view", "Stand the phone behind one baseline, a little high.")
                HorizontalDivider(color = c.line)
                Tip(ElevateIcons.Bolt, "60 fps if you can", "More frames give the shuttle tracker more to work with.")
                HorizontalDivider(color = c.line)
                Tip(ElevateIcons.Eye, "Keep it still", "A fence, bag or tripod. No hand-holding.")
                HorizontalDivider(color = c.line)

                Spacer(Modifier.height(28.dp))
                BackendLine(backend)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Tip(icon: ImageVector, title: String, body: String) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.lavender),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = c.lavenderInk, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
    }
}

/** Measured from a real `GET /v1/health`. The latency stays in the spoken description, off the screen. */
@Composable
private fun BackendLine(status: BackendStatus?) {
    val c = Elevate.colors
    val (dot, text) = when (status) {
        null -> c.inkMuted to "Checking cloud…"
        BackendStatus.NotConfigured -> c.inkMuted to "Cloud not set up"
        is BackendStatus.Online -> c.mintInk to "Cloud online"
        is BackendStatus.Offline -> c.error to "Cloud offline"
    }
    val spoken = backendDescription(status)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        Spacer(Modifier.weight(1f))
    }
}

/** Spoken form of the cloud status, with the real measured latency or failure reason. */
internal fun backendDescription(status: BackendStatus?): String = when (status) {
    null -> "Checking Elevate cloud"
    BackendStatus.NotConfigured -> "Elevate cloud not configured in this build"
    is BackendStatus.Online -> "Elevate cloud online, ${status.latencyMs} ms"
    is BackendStatus.Offline -> "Elevate cloud offline: ${status.reason}"
}
