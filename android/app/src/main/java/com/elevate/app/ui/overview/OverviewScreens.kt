package com.elevate.app.ui.overview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.theme.Elevate

/**
 * Upload-and-analysis screen (docs/plan/05 §3.3): real server stages with their own times, coverage
 * as "12.0 of 48.5 s analysed" (never a percentage), windows done/failed with reasons, partial
 * results, failure + retry, catching up and offline. Stateless: the caller owns [state].
 */
@Composable
fun AnalysingScreen(
    state: AnalysisUiState,
    onOpenResults: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Elevate.colors
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.cream).safeDrawingPadding()) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            TopBar("Analysis", onBack)
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AnalysisHeader(state)
                        AnalysisActions(state, onOpenResults, onRetry)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AnalysisDetails(state)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AnalysisHeader(state)
                    AnalysisDetails(state)
                    AnalysisActions(state, onOpenResults, onRetry)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AnalysisHeader(s: AnalysisUiState) {
    val c = Elevate.colors
    AnalysingText.sourceBadge(s)?.let { Pill(it, c.lavender, c.ink) }
    AnalysingText.connection(s)?.let { (title, detail) ->
        Notice(title, detail, if (s.syncError != null) c.errorSurface else c.peach, c.ink)
    }
    Text(AnalysingText.title(s), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(AnalysingText.facts(s), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    val total = s.totalUs
    if (total != null && total > 0) {
        CoverageBar(s.coverageUs, s.windowFailures, total, AnalysingText.coverage(s))
    }
    Text(AnalysingText.coverage(s), style = MaterialTheme.typography.titleMedium)
    AnalysingText.windows(s)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ColumnScope.AnalysisDetails(s: AnalysisUiState) {
    val c = Elevate.colors
    if (s.failureReason != null) {
        Notice("Why it failed", s.failureReason, c.errorSurface, c.error)
    }
    if (s.windowFailures.isNotEmpty()) {
        Section("Windows that failed (left as gaps)") {
            s.windowFailures.forEach { Text(AnalysingText.failureLine(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
    AnalysingText.downloads(s)?.let { line ->
        Section("Results on this phone") {
            Text(line, style = MaterialTheme.typography.bodyMedium)
            s.artifactProblems.forEach { p ->
                Text(
                    "Window ${p.windowSeq ?: "?"}: ${p.reason}" + if (p.gaveUp) " · gave up after ${p.attempts} tries" else " · will retry",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.error,
                )
            }
        }
    }
    if (s.stages.isNotEmpty() || s.requestedAt != null) {
        Section("Server stages") {
            val since = s.requestedAt ?: s.stages.firstOrNull()?.createdAt
            s.requestedAt?.let { StageRow("Requested", AnalysingText.stageTime(it, null)) }
            s.stages.forEach { st -> StageRow(AnalysingText.stageLabel(st.type), AnalysingText.stageTime(st.createdAt, since)) }
        }
    }
}

@Composable
private fun StageRow(label: String, time: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(time, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AnalysisActions(s: AnalysisUiState, onOpenResults: () -> Unit, onRetry: () -> Unit) {
    val c = Elevate.colors
    val colors = ButtonDefaults.buttonColors(containerColor = c.orange, contentColor = androidx.compose.ui.graphics.Color.White)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (AnalysingText.primaryAction(s)) {
            AnalysingText.Action.OPEN_RESULTS ->
                Button(onOpenResults, Modifier.fillMaxWidth().heightIn(min = 56.dp), colors = colors) { Text("Open results") }
            AnalysingText.Action.OPEN_PARTIAL ->
                Button(onOpenResults, Modifier.fillMaxWidth().heightIn(min = 56.dp), colors = colors) {
                    Text("See partial results (${AnalysingText.coverage(s)})")
                }
            AnalysingText.Action.RETRY ->
                Button(onRetry, Modifier.fillMaxWidth().heightIn(min = 56.dp), colors = colors) { Text("Retry analysis") }
            AnalysingText.Action.NONE -> Unit
        }
        if ((s.offline || s.syncError != null) && s.state?.isTerminal != true) {
            OutlinedButton(onRetry, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Try again now", color = c.orange) }
        }
    }
}

/**
 * Match overview (docs/plan/05 §3.4): real frame, sport/format/source, status, selected player,
 * rallies with their source (or "Rallies not available yet"), rally barcode from real intervals,
 * per-rally ball coverage with its denominator, and a true-proportion badminton court drawing only
 * real landings. Stateless.
 */
@Composable
fun MatchOverviewScreen(
    state: MatchOverviewState,
    onOpenRally: (rallyId: String, startMs: Long, endMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val c = Elevate.colors
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.cream).safeDrawingPadding()) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            TopBar(state.title, onBack)
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OverviewHero(state)
                        OverviewCourt(state)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OverviewRallies(state, onOpenRally)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OverviewHero(state)
                    OverviewRallies(state, onOpenRally)
                    OverviewCourt(state)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.OverviewHero(s: MatchOverviewState) {
    val c = Elevate.colors
    s.source.badge?.let { Pill(it, c.lavender, c.ink) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(c.court),
        contentAlignment = Alignment.Center,
    ) {
        when (val t = s.thumbnail) {
            is ThumbnailState.Ready -> Image(
                t.image,
                contentDescription = "Frame at ${clock(t.atMs)} of your video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            ThumbnailState.Loading -> Text("Reading a frame of your video…", color = androidx.compose.ui.graphics.Color.White)
            is ThumbnailState.Unavailable -> Text("No frame: ${t.reason}", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(16.dp))
        }
    }
    Text(s.sourceLine, style = MaterialTheme.typography.titleMedium)
    s.analysis?.let { a ->
        Text("${AnalysingText.title(a)} · ${AnalysingText.coverage(a)}", style = MaterialTheme.typography.bodyMedium)
    }
    Text(s.selectedPlayer, style = MaterialTheme.typography.bodyMedium)
    if (s.capabilities.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.capabilities.forEach { (name, cap) -> Pill("$name · ${cap.tier} · ${cap.status}", c.surface, c.ink) }
        }
    }
    s.matchProblem?.let { Notice("Match record not read", it, c.errorSurface, c.ink) }
}

@Composable
private fun ColumnScope.OverviewRallies(s: MatchOverviewState, onOpenRally: (String, Long, Long) -> Unit) {
    val c = Elevate.colors
    Text("Rallies", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    when (val r = s.rallies) {
        is RalliesState.NotAvailable -> Text(r.reason, style = MaterialTheme.typography.bodyMedium)
        is RalliesState.Available -> {
            Text(r.sourceLabel, style = MaterialTheme.typography.bodyMedium)
            RallyBarcode(r.rows, r.timelineMs)
            RallyList(r.rows, onOpenRally)
        }
    }
    val ref = s.referenceRange
    if (s.referenceDetail != null && ref != null) {
        Notice("Hand-labelled reference", s.referenceDetail, c.lavender, c.ink)
        OutlinedButton(
            { onOpenRally(REFERENCE_RALLY_ID, ref.first, ref.last) },
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Play the hand-labelled rally", color = c.orange) }
    }
}

@Composable
private fun ColumnScope.OverviewCourt(s: MatchOverviewState) {
    Text("Landings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    when (val ct = s.court) {
        is CourtState.Badminton -> MiniCourt(ct, Modifier.fillMaxWidth())
        is CourtState.NotApplicable -> Text(ct.reason, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Elevate.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp)) { Icon(ElevateIcons.Back, contentDescription = "Back", tint = Elevate.colors.ink) }
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.semantics { heading() })
    }
}

/** `onOpenRally` id used for the hand-labelled reference range (not a `match.v1` rally). */
const val REFERENCE_RALLY_ID = "hand_labelled_reference"
