package com.elevate.app.ui.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.ui.theme.Elevate

/**
 * The upload's state from [com.elevate.app.data.upload.UploadCoordinator.progress]. The bar fills
 * from bytes actually written and parts the server accepted; the rate and ETA are measured; a
 * completed upload shows the size the server confirmed. Shows nothing for [UploadProgress.Idle].
 *
 * [onRetry] resumes from the parts already accepted (start the upload job again);
 * [onCancel] aborts the upload on the server and deletes local state.
 */
@Composable
fun UploadProgressCard(
    progress: UploadProgress,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = UploadText.title(progress) ?: return
    val c = Elevate.colors
    val failed = progress is UploadProgress.Failed && !progress.failure.retryable
    val done = progress is UploadProgress.Completed
    val background = when {
        failed -> c.errorSurface
        done -> c.mint
        else -> c.surface
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(background, MaterialTheme.shapes.medium)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                failed -> c.error
                done -> c.mintInk
                else -> c.ink
            },
        )
        UploadText.fraction(progress)?.let { f ->
            LinearProgressIndicator(
                progress = { f },
                modifier = Modifier.fillMaxWidth(),
                color = c.orange,
                trackColor = c.peach,
            )
        }
        UploadText.detail(progress)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
        if (progress is UploadProgress.Uploading && progress.bytesPerSecond == null) {
            Text("Measuring speed", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        UploadText.retry(progress)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.orangeText) }
        val canRetry = progress is UploadProgress.PausedOffline || (progress is UploadProgress.Failed && progress.failure.retryable)
        val canCancel = progress is UploadProgress.Preparing || progress is UploadProgress.Uploading ||
            progress is UploadProgress.PausedOffline || progress is UploadProgress.Failed
        if (canRetry || canCancel) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRetry) {
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Retry", color = c.orangeText, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (canCancel) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Cancel upload", color = c.ink, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
