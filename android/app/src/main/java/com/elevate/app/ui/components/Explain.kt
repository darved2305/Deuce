package com.elevate.app.ui.components

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.elevate.app.ui.theme.Elevate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hold to explain. Wrap any button, chip or label; a long press shows a short plain-language tooltip
 * (title plus a sentence or two). A normal tap is untouched. TalkBack gets an "Explain" long-click action.
 * Usage: `ExplainBox(Explanations.Hit) { PillChip(...) }`. Pass layout modifiers (weight, fillMaxWidth) to the box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainBox(explanation: Explanation, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    // A persistent tooltip stays until dismissed; close it by itself after a read.
    if (state.isVisible) {
        LaunchedEffect(state) {
            delay(6_000)
            state.dismiss()
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            val c = Elevate.colors
            RichTooltip(
                title = { Text(explanation.title, style = MaterialTheme.typography.titleMedium, color = c.ink) },
                colors = TooltipDefaults.richTooltipColors(containerColor = Color.White, contentColor = c.ink, titleContentColor = c.ink),
            ) {
                Text(explanation.text, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
        },
        state = state,
        modifier = modifier.semantics(mergeDescendants = false) {
            onLongClick(label = "Explain") {
                scope.launch { state.show() }
                true
            }
        },
        content = content,
    )
}

/** True once the first-run hint has been dismissed. Stored in this app's own preferences. */
object ExplainHint {
    private const val FILE = "elevate_ui"
    private const val KEY = "explain_hint_dismissed"
    fun isDismissed(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun dismiss(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

/** One small dismissible tip shown on Home until the first dismissal (remembered across launches). */
@Composable
fun ExplainHintBanner(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!ExplainHint.isDismissed(context)) }
    if (!shown) return
    val c = Elevate.colors
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(c.lavender)
            .padding(start = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            "Tip: hold any button to see what it does",
            style = MaterialTheme.typography.bodyMedium, color = c.ink,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        androidx.compose.foundation.layout.Box(
            Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClickLabel = "Dismiss tip", role = androidx.compose.ui.semantics.Role.Button) {
                ExplainHint.dismiss(context)
                shown = false
            },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text("Got it", style = MaterialTheme.typography.labelMedium, color = c.orangeText) }
    }
}
