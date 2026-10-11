package com.elevate.app.ui.overview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.ui.components.CircleButton
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ElevateIcons
import com.elevate.app.ui.components.Headline
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.PrimaryButton
import com.elevate.app.ui.components.ProgressBar
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.components.ScreenSpacing
import com.elevate.app.ui.components.SecondaryButton
import com.elevate.app.ui.components.SectionTitle
import com.elevate.app.ui.components.StageRow
import com.elevate.app.ui.components.StageState
import com.elevate.app.ui.components.VideoCard
import com.elevate.app.ui.components.VideoChip
import com.elevate.app.ui.theme.Elevate
import java.util.Locale

/** Hero of the analysing / overview screens: the user's own video, never a stand-in picture. */
data class AnalysingHero(
    /** "Badminton · 8:04 · 1080p50" from the real probe; null when nothing is known. */
    val eyebrow: String?,
    /** A real decoded frame of the source; null = no video attached (hero hidden). */
    val thumbnail: ThumbnailState?,
    /** File name, shown on the video chip. */
    val fileName: String? = null,
)

/**
 * Analysing (reference 15-analysing): the user's video on top, the "Sending your video" card with
 * confirmed bytes, then the stage checklist fed by real server answers. Shown from the moment the
 * user starts, before any session or analysis id exists. Stateless: the caller owns [flow].
 */
@Composable
fun AnalysingScreen(
    flow: AnalysingFlowState,
    hero: AnalysingHero,
    onOpenResults: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    PeachPage {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                ElevateHeader("Analysing", onBack = onBack)
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            Headline("Your match", hero.eyebrow)
                            HeroVideo(hero, null)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            AnalysingBody(flow, onOpenResults, onRetry, onCancel)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
                        verticalArrangement = ScreenSpacing,
                    ) {
                        Headline("Your match", hero.eyebrow)
                        HeroVideo(hero, null)
                        AnalysingBody(flow, onOpenResults, onRetry, onCancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AnalysingBody(flow: AnalysingFlowState, onOpenResults: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    val c = Elevate.colors
    val stopped = flow.stages.any { it.state == StageState.Failed }
    flow.send?.let { SendCardView(it) }
    Column(Modifier.fillMaxWidth()) {
        flow.stages.forEach { StageRow(it.label, it.state, it.value) }
    }
    flow.notice?.let { line ->
        ElevateCard(tint = if (stopped) c.errorSurface else c.lavender) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
    }
    when (flow.action) {
        AnalysingFlowState.Action.OPEN_RESULTS -> PrimaryButton("See your match", onOpenResults)
        AnalysingFlowState.Action.OPEN_PARTIAL -> SecondaryButton("See the match so far", onOpenResults, Modifier.fillMaxWidth())
        AnalysingFlowState.Action.RETRY_UPLOAD -> PrimaryButton("Try again", onRetry)
        AnalysingFlowState.Action.CHECK_AGAIN -> SecondaryButton("Check again", onRetry, Modifier.fillMaxWidth())
        AnalysingFlowState.Action.NONE -> Unit
    }
    if (!stopped && flow.stages.last().state != StageState.Done) {
        Text(
            AnalysingFlow.LEAVE_LINE,
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (flow.canCancel) {
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text("Cancel upload", color = c.inkMuted, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SendCardView(card: SendCard) {
    val c = Elevate.colors
    ElevateCard(tint = c.peach) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sending your video", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            card.amount?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = c.orangeText) }
        }
        card.fraction?.let {
            Spacer(Modifier.height(12.dp))
            ProgressBar(it, Modifier.semantics { contentDescription = card.amount ?: "upload progress" })
        }
        if (card.rate != null || card.eta != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(card.rate.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.weight(1f))
                card.eta?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
            }
        }
        card.note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
    }
}

/** The user's real video frame as a rounded card; hidden when no frame can be read. */
@Composable
private fun HeroVideo(hero: AnalysingHero, onPlay: (() -> Unit)?) {
    val t = hero.thumbnail ?: return
    if (t is ThumbnailState.Unavailable) return
    VideoCard(Modifier.aspectRatio(16f / 9f)) {
        when (t) {
            is ThumbnailState.Ready -> Image(
                t.image,
                contentDescription = "Frame at ${clock(t.atMs)} of your video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
        }
        VideoChip(hero.fileName ?: "Your video", Modifier.align(Alignment.TopStart).padding(12.dp), icon = ElevateIcons.Film)
        if (onPlay != null) {
            CircleButton(ElevateIcons.Play, "Play", onPlay, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
}

/**
 * Match overview (reference 18-overview): the real video, how much of it is analysed, the court
 * with real landings only, and the rallies from `match.v1` (one line until they exist). Stateless.
 */
@Composable
fun MatchOverviewScreen(
    state: MatchOverviewState,
    eyebrow: String?,
    onOpenRally: (rallyId: String, startMs: Long, endMs: Long) -> Unit,
    onBack: () -> Unit,
    readLabDrills: Int = 0,
    onTrainRead: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val hero = AnalysingHero(eyebrow, state.thumbnail, state.title)
    val play: (() -> Unit)? = when (val r = state.rallies) {
        is RalliesState.Available -> r.rows.firstOrNull()?.let { first -> { onOpenRally(first.rallyId, first.startMs, first.endMs) } }
        is RalliesState.FromHits -> r.rows.firstOrNull()?.let { first -> { onOpenRally(first.rallyId, first.startMs, first.endMs) } }
        is RalliesState.NotAvailable ->
            state.live?.rallies?.firstOrNull()?.let { first -> { onOpenRally(first.rallyId, first.startMs, first.endMs) } }
                ?: state.referenceRange?.let { ref -> { onOpenRally(REFERENCE_RALLY_ID, ref.first, ref.last) } }
    }
    PeachPage {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                ElevateHeader("Match", onBack = onBack)
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            OverviewTop(state, hero, play)
                            com.elevate.app.ui.readlab.ReadLabEntryButton(readLabDrills, onTrainRead)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = ScreenSpacing) {
                            OverviewCourt(state)
                            OverviewRallies(state, onOpenRally)
                            OverviewHits(state, onOpenRally)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 24.dp),
                        verticalArrangement = ScreenSpacing,
                    ) {
                        OverviewTop(state, hero, play)
                        com.elevate.app.ui.readlab.ReadLabEntryButton(readLabDrills, onTrainRead)
                        OverviewCourt(state)
                        OverviewRallies(state, onOpenRally)
                        OverviewHits(state, onOpenRally)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewTop(s: MatchOverviewState, hero: AnalysingHero, play: (() -> Unit)?) {
    val c = Elevate.colors
    s.source.badge?.let { Pill(it, c.lavender, c.ink) }
    val live = s.live
    if (live != null && s.source.badge == null) Pill("From the engine windows · candidates", c.lavender, c.ink)
    // capabilities come only from match.v1: non-empty means the record was read.
    val matchKnown = s.capabilities.isNotEmpty()
    Headline("Your match", if (matchKnown) s.sourceLine else hero.eyebrow ?: s.sourceLine)
    ElevateCard(tint = c.peach) {
        HeroVideo(hero, play)
        if (live?.shuttleLine != null) {
            Spacer(Modifier.height(14.dp))
            LiveShuttleStat(live)
            Spacer(Modifier.height(10.dp))
            WindowStrip(live)
        } else {
            s.analysis?.let { a ->
                Spacer(Modifier.height(14.dp))
                AnalysedStat(a)
            }
            if (live != null) {
                Spacer(Modifier.height(10.dp))
                WindowStrip(live)
            }
        }
        if (matchKnown) {
            Spacer(Modifier.height(10.dp))
            Text(s.selectedPlayer, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            s.opponent?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
            s.score?.takeIf { it.confirmed }?.let { sc ->
                Spacer(Modifier.height(10.dp))
                Text(sc.headline, style = MaterialTheme.typography.titleMedium, color = c.ink)
                sc.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
            }
        }
    }
    live?.pendingLine?.let { PendingLine(it) }
    s.matchProblem?.let { PendingLine(it) }
    CoachSection(s)
}

/** "18/485 seconds of your video analysed": covered seconds over the known clip length, never a percentage. */
@Composable
private fun AnalysedStat(a: AnalysisUiState) {
    val c = Elevate.colors
    val label = AnalysingText.coverage(a)
    val total = a.totalUs?.takeIf { it > 0 }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append(String.format(Locale.ROOT, "%.0f", a.coveredUs / 1e6))
                if (total != null) withStyle(SpanStyle(color = c.orangeText)) { append(String.format(Locale.ROOT, "/%.0f", total / 1e6)) }
            },
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp),
            color = c.ink,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (total != null) "seconds of your video analysed" else "seconds analysed · length unknown", style = MaterialTheme.typography.titleMedium, color = c.ink)
            AnalysingText.windows(a)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
        }
    }
}

@Composable
private fun OverviewCourt(s: MatchOverviewState) {
    SectionTitle("Court")
    when (val ct = s.court) {
        is CourtState.Badminton -> ElevateCard {
            ct.chip?.let { Pill(it, Elevate.colors.lavender, Elevate.colors.ink) }
            ct.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted, modifier = Modifier.padding(top = 6.dp)) }
            if (ct.chip != null) Spacer(Modifier.height(10.dp))
            MiniCourt(ct, Modifier.fillMaxWidth())
        }
        is CourtState.NotApplicable -> Text(ct.reason, style = MaterialTheme.typography.bodyMedium)
    }
}


/** Track T: engine hit candidates and the rally candidates grouped from them (candidates only). */
@Composable
private fun OverviewHits(s: MatchOverviewState, onOpenRally: (String, Long, Long) -> Unit) {
    SectionTitle("Hit candidates")
    HitTimeline(s.hitTimeline, onOpenRally)
}

@Composable
private fun OverviewRallies(s: MatchOverviewState, onOpenRally: (String, Long, Long) -> Unit) {
    val c = Elevate.colors
    when (val r = s.rallies) {
        is RalliesState.NotAvailable -> {
            val live = s.live
            if (live != null && live.rallies.isNotEmpty()) {
                SectionTitle("${live.rallies.size} rally candidate${if (live.rallies.size == 1) "" else "s"}")
                Text(
                    "Grouped from engine hit candidates · not confirmed rallies",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkMuted,
                )
                LiveRallyList(live.rallies, onOpenRally)
            } else {
                SectionTitle("Rallies")
                val empty = if (live?.windowsComplete == true) "No rally candidates in the windows" else AnalysingFlow.ralliesEmpty(s.analysis)
                Text(empty, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
        }
        is RalliesState.FromHits -> {
            SectionTitle("${r.rows.size} rally candidate${if (r.rows.size == 1) "" else "s"}")
            Text(r.sourceLabel, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            LiveRallyList(r.rows, onOpenRally)
        }
        is RalliesState.Available -> {
            SectionTitle("${r.rows.size} rall${if (r.rows.size == 1) "y" else "ies"} · height = shots")
            RallyBarcode(r.rows, r.timelineMs)
            Text(r.sourceLabel, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            RallyList(r.rows, onOpenRally)
        }
    }
    val ref = s.referenceRange
    if (s.referenceDetail != null && ref != null) {
        ElevateCard(tint = c.lavender) {
            Text("Hand-labelled reference", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(s.referenceDetail, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Play the hand-labelled rally", { onOpenRally(REFERENCE_RALLY_ID, ref.first, ref.last) }, Modifier.fillMaxWidth())
        }
    }
}

/** `onOpenRally` id used for the hand-labelled reference range (not a `match.v1` rally). */
const val REFERENCE_RALLY_ID = "hand_labelled_reference"
