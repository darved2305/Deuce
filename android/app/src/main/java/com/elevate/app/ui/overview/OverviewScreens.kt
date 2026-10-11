package com.elevate.app.ui.overview

import com.elevate.app.ui.components.Explanation
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.ExplainBox
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.elevate.app.domain.coach.LandingEvidence
import com.elevate.app.ui.components.SkeletonList
import com.elevate.app.ui.components.StateMessage
import com.elevate.app.ui.components.rememberHaptics
import com.elevate.app.ui.components.rememberReduceMotion
import com.elevate.app.ui.components.tabTransition
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
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
import com.elevate.app.ui.components.ElevateRadius
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

/** The hold-to-explain text for one line of the analysing checklist. */
private fun stageExplanation(stage: FlowStage): Explanation = when (stage.key) {
    FlowStage.Key.SEND -> if (stage.value == "preparing") Explanations.StageVideo else Explanations.StageUpload
    FlowStage.Key.QUEUE -> Explanations.Analysing
    FlowStage.Key.TRACK -> Explanations.InPieces
    FlowStage.Key.MATCH -> Explanations.StageAnalyse
}

@Composable
private fun ColumnScope.AnalysingBody(flow: AnalysingFlowState, onOpenResults: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    val c = Elevate.colors
    val stopped = flow.stages.any { it.state == StageState.Failed }
    flow.send?.let { SendCardView(it) }
    Column(Modifier.fillMaxWidth()) {
        flow.stages.forEach { stage ->
            ExplainBox(stageExplanation(stage), Modifier.fillMaxWidth()) { StageRow(stage.label, stage.state, stage.value) }
        }
    }
    flow.notice?.let { line ->
        if (stopped) {
            StateMessage(ElevateIcons.Warning, line, isError = true)
        } else {
            ElevateCard(tint = c.lavender) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
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

/** The user's real video frame as a rounded card; hidden when no frame can be read. [compact] is the small tappable thumbnail. */
@Composable
private fun HeroVideo(hero: AnalysingHero, onPlay: (() -> Unit)?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val t = hero.thumbnail ?: return
    if (t is ThumbnailState.Unavailable) return
    val tap = if (compact && onPlay != null) Modifier.clickable(onClickLabel = "Play the first rally", role = Role.Button, onClick = onPlay) else Modifier
    VideoCard(modifier.aspectRatio(16f / 9f).then(tap), corner = if (compact) ElevateRadius.Inner else ElevateRadius.Card) {
        when (t) {
            is ThumbnailState.Ready -> Image(
                t.image,
                contentDescription = "Frame at ${clock(t.atMs)} of your video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
        }
        if (compact) {
            if (onPlay != null) {
                Box(Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                    Icon(ElevateIcons.Play, contentDescription = null, tint = Elevate.colors.ink, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            VideoChip(hero.fileName ?: "Your video", Modifier.align(Alignment.TopStart).padding(12.dp), icon = ElevateIcons.Film)
            if (onPlay != null) {
                CircleButton(ElevateIcons.Play, "Play", onPlay, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
            }
        }
    }
}

private enum class OverviewTab(val label: String, val explain: Explanation) {
    Coach("Coach", Explanations.CoachReport),
    Rallies("Rallies", Explanations.Rally),
    Court("Court", Explanations.Landing),
}

/**
 * Match overview (reference 18-overview), laid out so nothing important is below the fold: a short
 * hero and the two training buttons stay on screen, and the coach report (the default tab), the
 * rallies and the court each scroll on their own. In landscape the hero is a left pane and the
 * tabs are the right pane. Stateless apart from the remembered tab.
 */
@Composable
fun MatchOverviewScreen(
    state: MatchOverviewState,
    eyebrow: String?,
    onOpenRally: (rallyId: String, startMs: Long, endMs: Long) -> Unit,
    onBack: () -> Unit,
    readLabDrills: Int = 0,
    onTrainRead: () -> Unit = {},
    reflexRounds: Int = 0,
    onReflex: () -> Unit = {},
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
    var tabIndex by rememberSaveable { mutableIntStateOf(OverviewTab.Coach.ordinal) }
    // The coach suggestions stagger in once per visit to the overview, not on every tab switch.
    val coachIntroPlayed = rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val tabs: @Composable () -> Unit = { OverviewTabRow(tabIndex) { haptics.tick(); tabIndex = it } }
    val body: @Composable (Modifier) -> Unit = { m -> OverviewTabBody(OverviewTab.entries[tabIndex], state, onOpenRally, coachIntroPlayed, m) }
    val ctas: @Composable () -> Unit = { TrainingButtons(readLabDrills, onTrainRead, reflexRounds, onReflex) }
    PeachPage {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                ElevateHeader("Match", onBack = onBack)
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OverviewHero(state, hero, play, compact = false)
                            ctas()
                        }
                        Column(Modifier.weight(1.2f).fillMaxHeight()) {
                            tabs()
                            body(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(horizontal = ScreenGutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OverviewHero(state, hero, play, compact = true)
                            ctas()
                        }
                        Spacer(Modifier.height(4.dp))
                        tabs()
                        body(Modifier.weight(1f).fillMaxWidth().padding(horizontal = ScreenGutter))
                    }
                }
            }
        }
    }
}

/** "Train your read" and "Reflex challenge" side by side; each appears only when it has something to play. */
@Composable
private fun TrainingButtons(readLabDrills: Int, onTrainRead: () -> Unit, reflexRounds: Int, onReflex: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        com.elevate.app.ui.readlab.ReadLabEntryButton(readLabDrills, onTrainRead, Modifier.weight(1f))
        com.elevate.app.ui.readlab.ReflexEntryButton(reflexRounds, onReflex, Modifier.weight(1f))
    }
}

@Composable
private fun OverviewTabRow(selected: Int, onSelect: (Int) -> Unit) {
    val c = Elevate.colors
    PrimaryTabRow(selectedTabIndex = selected, containerColor = Color.Transparent, contentColor = c.ink) {
        OverviewTab.entries.forEachIndexed { i, tab ->
            Tab(
                selected = selected == i,
                onClick = { onSelect(i) },
                selectedContentColor = c.orangeText,
                unselectedContentColor = c.inkMuted,
                text = { ExplainBox(tab.explain) { Text(tab.label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)) } },
            )
        }
    }
}

/** One tab's content, scrolling on its own so the hero and the tab row never move. */
@Composable
private fun OverviewTabBody(tab: OverviewTab, s: MatchOverviewState, onOpenRally: (String, Long, Long) -> Unit, coachIntroPlayed: MutableState<Boolean>, modifier: Modifier) {
    val reduce = rememberReduceMotion()
    AnimatedContent(targetState = tab, transitionSpec = { tabTransition(reduce) }, modifier = modifier, label = "overview-tab") { current ->
        // Nothing has arrived yet: placeholders in the shape of the report, not a blank page.
        val loading = s.analysis == null && s.live == null && s.matchProblem == null &&
            s.hitTimeline.hits.isEmpty() && s.landings == LandingEvidence.NONE &&
            s.referenceDetail == null && s.rallies is RalliesState.NotAvailable
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = ScreenSpacing,
        ) {
            if (loading) {
                SkeletonList(cards = 3)
            } else when (current) {
                OverviewTab.Coach -> {
                    OverviewProgress(s)
                    CoachSection(s, coachIntroPlayed)
                }
                OverviewTab.Rallies -> {
                    OverviewRallies(s, onOpenRally)
                    OverviewHits(s, onOpenRally)
                }
                OverviewTab.Court -> OverviewCourt(s)
            }
        }
    }
}

/** Where the analysis stands: live windows or the analysed-seconds stat, and why anything is still pending. */
@Composable
private fun OverviewProgress(s: MatchOverviewState) {
    val live = s.live
    val analysis = s.analysis
    val showLive = live != null && live.shuttleLine != null
    if (showLive || analysis != null || live != null) {
        ElevateCard(tint = Elevate.colors.peach) {
            if (live != null && live.shuttleLine != null) {
                LiveShuttleStat(live)
                Spacer(Modifier.height(10.dp))
                WindowStrip(live)
            } else {
                analysis?.let { AnalysedStat(it) }
                if (live != null) {
                    if (analysis != null) Spacer(Modifier.height(10.dp))
                    WindowStrip(live)
                }
            }
        }
    }
    live?.pendingLine?.let { PendingLine(it) }
    s.matchProblem?.let { PendingLine(it) }
}

/** Short hero: the video frame and the facts that fit on a few lines (what, how much, who, score). */
@Composable
private fun OverviewHero(s: MatchOverviewState, hero: AnalysingHero, play: (() -> Unit)?, compact: Boolean) {
    if (compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val hasThumb = hero.thumbnail != null && hero.thumbnail !is ThumbnailState.Unavailable
            if (hasThumb) HeroVideo(hero, play, Modifier.width(132.dp), compact = true)
            HeroInfo(s, hero, Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroVideo(hero, play)
            HeroInfo(s, hero, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HeroInfo(s: MatchOverviewState, hero: AnalysingHero, modifier: Modifier) {
    val c = Elevate.colors
    val live = s.live
    // capabilities come only from match.v1: non-empty means the record was read.
    val matchKnown = s.capabilities.isNotEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val badge = s.source.badge ?: if (live != null) "From the engine windows · candidates" else null
        badge?.let { Pill(it, c.lavender, c.ink) }
        Text("Your match", style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
        val line = if (matchKnown) s.sourceLine else hero.eyebrow ?: s.sourceLine
        Text(line, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 2)
        s.analysis?.let { a -> Text(AnalysingText.coverage(a), style = MaterialTheme.typography.labelMedium, color = c.inkMuted) }
        if (matchKnown) {
            Text(s.selectedPlayer, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            s.opponent?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
            s.score?.takeIf { it.confirmed }?.let { sc ->
                Text(sc.headline, style = MaterialTheme.typography.titleMedium, color = c.ink)
                sc.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
            }
        }
    }
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
            ct.chip?.let { chip -> ExplainBox(Explanations.CourtQuality) { Pill(chip, Elevate.colors.lavender, Elevate.colors.ink) } }
            ct.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted, modifier = Modifier.padding(top = 6.dp)) }
            if (ct.chip != null) Spacer(Modifier.height(10.dp))
            MiniCourt(ct, Modifier.fillMaxWidth())
        }
        is CourtState.NotApplicable -> StateMessage(ElevateIcons.Empty, ct.reason)
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
                StateMessage(ElevateIcons.Empty, empty)
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
