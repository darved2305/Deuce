package com.elevate.app.ui.screens

import com.elevate.app.ui.components.ElevateRadius
import com.elevate.app.data.history.cardTimeMs
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import com.elevate.app.data.history.HistoryStatus
import com.elevate.app.data.history.MatchHistoryEntry
import com.elevate.app.data.history.status
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.repository.BackendStatus
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.ExplainHintBanner
import com.elevate.app.ui.components.Explanation
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.Headline
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.SectionTitle
import com.elevate.app.ui.components.StateMessage
import com.elevate.app.ui.theme.Elevate

@Composable
fun ImportScreen(
    opening: Boolean,
    error: String?,
    onChoose: () -> Unit,
    backend: BackendStatus? = null,
    /** Matches remembered on this phone (data/history), newest first. Empty: the first-run home. */
    matches: List<MatchCard> = emptyList(),
    /** Called with the analysisId of the tapped match. */
    onOpenMatch: (String) -> Unit = {},
    /** Opens "Your game" (the profile); null hides the avatar. */
    onOpenProfile: (() -> Unit)? = null,
    /** Upload / analysis status of an earlier pick, shown under the main card. Last, so callers can pass it as a trailing lambda. */
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
            ElevateHeader(title = "Elevate", trailing = onOpenProfile?.let { open -> { com.elevate.app.ui.profile.ProfileAvatarButton(open) } })
            Column(Modifier.padding(horizontal = ScreenGutter)) {
                Spacer(Modifier.height(16.dp))
                if (matches.isEmpty()) {
                    Headline("Add your\nfirst match")
                } else {
                    Headline("Your matches", eyebrow = matchesEyebrow(matches.size))
                }
                Spacer(Modifier.height(16.dp))
                ExplainHintBanner()
                Spacer(Modifier.height(16.dp))

                ElevateCard(tint = c.peachDeep.copy(alpha = 0.6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(
                            Modifier.size(60.dp).clip(RoundedCornerShape(ElevateRadius.Inner)).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) { Icon(ElevateIcons.Film, contentDescription = null, tint = c.orange) }
                        Spacer(Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("From your gallery", style = MaterialTheme.typography.titleMedium, color = c.ink)
                            Text("It plays right here. You choose when to upload.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    ExplainBox(Explanations.AddMatch, Modifier.fillMaxWidth()) {
                        PrimaryButton(
                            text = if (opening) "Opening…" else "Add a match",
                            onClick = onChoose,
                            busy = opening,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                status()

                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    matches.forEach { card ->
                        MatchRow(card, onOpen = { onOpenMatch(card.analysisId) })
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(16.dp))
                    StateMessage(
                        ElevateIcons.Warning, error, isError = true,
                        actionLabel = "Choose another video", onAction = onChoose,
                    )
                }

                // Filming tips are for the first match only: with matches on the list the page stays short.
                if (matches.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    SectionTitle("Filming tips")
                    Tip(ElevateIcons.Target, "Both baselines in view", "Stand the phone behind one baseline, a little high.")
                    HorizontalDivider(color = c.line)
                    Tip(ElevateIcons.Bolt, "60 fps if you can", "More frames give the shuttle tracker more to work with.")
                    HorizontalDivider(color = c.line)
                    Tip(ElevateIcons.Eye, "Keep it still", "A fence, bag or tripod. No hand-holding.")
                    HorizontalDivider(color = c.line)
                }

                Spacer(Modifier.height(24.dp))
                BackendLine(backend)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** One remembered match for the home list. Built from a [MatchHistoryEntry] with [matchCardOf]. */
data class MatchCard(
    val analysisId: String,
    val title: String,
    val sport: String?,
    val durationMs: Long?,
    val coverageMs: Long,
    val windowsDone: Int,
    val status: HistoryStatus,
    /** Wall-clock ms of the last recorded change (the analysis time once ready). */
    val updatedAt: Long,
    val createdAt: Long,
    val engineVersion: String?,
    /** A real decoded frame of the video, when one could be read; otherwise a plain film tile. */
    val thumbnail: ImageBitmap? = null,
)

fun matchCardOf(entry: MatchHistoryEntry, thumbnail: ImageBitmap? = null): MatchCard = MatchCard(
    analysisId = entry.analysisId,
    title = entry.title,
    sport = entry.sport,
    durationMs = entry.durationMs,
    coverageMs = entry.coverageMs,
    windowsDone = entry.windowsDone,
    status = entry.status(),
    updatedAt = entry.cardTimeMs(),
    createdAt = entry.createdAt,
    engineVersion = entry.engineVersion,
    thumbnail = thumbnail,
)

/** "1 match on this phone" (history is local to the phone until an identity is linked). */
internal fun matchesEyebrow(count: Int): String = if (count == 1) "1 match on this phone" else "$count matches on this phone"

/** "Badminton · 0:25 · Fully analysed" or "... · 12.0 s of 25.6 s analysed". Unknown parts are left out; no denominator is invented. */
internal fun matchCardLine(card: MatchCard): String {
    val sport = card.sport?.replace('_', ' ')?.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val length = card.durationMs?.takeIf { it > 0 }?.let { formatClock(it) }
    val covered = seconds(card.coverageMs)
    val analysed = when {
        card.status == HistoryStatus.Failed && card.windowsDone == 0 -> null
        card.durationMs != null && card.durationMs > 0 && card.coverageMs + 1000 >= card.durationMs -> "Fully analysed"
        card.durationMs != null && card.durationMs > 0 -> "$covered s of ${seconds(card.durationMs)} s analysed"
        else -> "$covered s analysed"
    }
    return listOfNotNull(sport, length, analysed).joinToString(" · ")
}

/** "Analysed 05:30" (ready), "Started 05:30" (analysing), "Stopped 05:30" (failed). */
internal fun matchCardFooter(card: MatchCard, zone: ZoneId = ZoneId.systemDefault(), nowMs: Long = System.currentTimeMillis()): String {
    val (verb, at) = when (card.status) {
        HistoryStatus.Ready, HistoryStatus.ReadyFromWindows -> "Analysed" to card.updatedAt
        HistoryStatus.Analysing -> "Started" to card.createdAt
        HistoryStatus.Failed -> "Stopped" to card.updatedAt
    }
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val pattern = if (time.toLocalDate() == today) "HH:mm" else "EEE d MMM HH:mm"
    val whenText = time.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    return "$verb $whenText"
}

/** Chip text: what the recorded state allows, nothing more. */
internal fun matchStatusLabel(status: HistoryStatus): String = when (status) {
    HistoryStatus.Analysing -> "Analysing"
    HistoryStatus.ReadyFromWindows -> "Ready"
    HistoryStatus.Ready -> "Ready"
    HistoryStatus.Failed -> "Failed"
}

private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms.coerceAtLeast(0) / 1000.0)

@Composable
private fun MatchRow(card: MatchCard, onOpen: () -> Unit) {
    val c = Elevate.colors
    val line = matchCardLine(card)
    val footer = matchCardFooter(card)
    val chip = matchStatusLabel(card.status)
    ElevateCard(
        tint = if (card.status == HistoryStatus.Analysing) Color.White else c.peachDeep.copy(alpha = 0.45f),
        onClick = onOpen,
        onClickLabel = "Open match",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(ElevateRadius.Inner)).background(c.court),
                contentAlignment = Alignment.Center,
            ) {
                val image = card.thumbnail
                if (image != null) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(ElevateIcons.Film, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                ExplainBox(
                    when (card.status) {
                        HistoryStatus.Analysing -> Explanations.InPieces
                        HistoryStatus.Failed -> Explanations.UploadResumes
                        else -> Explanations.CoachReport
                    },
                ) { StatusChip(chip, card.status) }
                Spacer(Modifier.height(4.dp))
                Text(card.title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(footer, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, status: HistoryStatus) {
    val c = Elevate.colors
    val (bg, fg) = when (status) {
        HistoryStatus.Ready, HistoryStatus.ReadyFromWindows -> c.mint to c.mintInk
        HistoryStatus.Analysing -> c.lavender to c.lavenderInk
        HistoryStatus.Failed -> c.errorSurface to c.error
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun Tip(icon: ImageVector, title: String, body: String) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(ElevateRadius.Inner)).background(c.lavender),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = c.lavenderInk, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
    }
}

/** Measured from a real `GET /v1/health`. The latency stays in the spoken description, off the screen. */
@Composable
private fun BackendLine(status: BackendStatus?) {
    val c = Elevate.colors
    // "Cloud not set up" is a developer note: people on a release build see nothing.
    val debuggable = (LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (status == BackendStatus.NotConfigured && !debuggable) return
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
        ExplainBox(Explanations.Cloud) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.size(8.dp))
                Text(text, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
        }
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
