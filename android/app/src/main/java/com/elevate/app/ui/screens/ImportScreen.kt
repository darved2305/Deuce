package com.elevate.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.repository.BackendStatus
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.theme.Elevate

@Composable
fun ImportScreen(opening: Boolean, error: String?, onChoose: () -> Unit, backend: BackendStatus? = null) {
    val c = Elevate.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to c.peach, 0.45f to c.cream)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text("Elevate", style = MaterialTheme.typography.titleMedium, color = c.orangeText)
            Spacer(Modifier.height(28.dp))
            Text("Add your\nfirst match", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(c.peachDeep.copy(alpha = 0.55f))
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) { Icon(ElevateIcons.Film, contentDescription = null, tint = c.orange) }
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text("Pick a match from your gallery", style = MaterialTheme.typography.titleMedium)
                        Text("It plays right here. Nothing is uploaded.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onChoose,
                    enabled = !opening,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.orange, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    if (opening) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Opening…", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Text("Choose a match video", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    error,
                    color = c.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.errorSurface)
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Spacer(Modifier.height(32.dp))
            Text("Filming tips", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tip("Both baselines in view", "Stand the phone behind one baseline, a little high.")
                Tip("60 fps if you can", "More frames give the shuttle tracker more to work with.")
                Tip("Keep it still", "A fence, bag or tripod. No hand-holding.")
            }
            Spacer(Modifier.height(24.dp))
            BackendLine(backend)
        }
    }
}

@Composable
private fun Tip(title: String, body: String) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c.lavenderInk))
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Measured from a real `GET /v1/health`; the analysis itself arrives in Phase 2. */
@Composable
private fun BackendLine(status: BackendStatus?) {
    val c = Elevate.colors
    val (dot, text) = when (status) {
        null -> c.inkMuted to "Checking Elevate cloud…"
        BackendStatus.NotConfigured -> c.inkMuted to "Elevate cloud not configured in this build"
        is BackendStatus.Online -> c.mintInk to "Elevate cloud online · ${status.latencyMs} ms · analysis arrives in a later update"
        is BackendStatus.Offline -> c.error to "Elevate cloud unreachable · ${status.reason}"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}
