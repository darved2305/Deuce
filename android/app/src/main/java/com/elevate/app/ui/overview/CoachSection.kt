package com.elevate.app.ui.overview

import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.ExplainBox
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import com.elevate.app.ui.components.ElevateMotion
import com.elevate.app.ui.components.rememberReduceMotion
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elevate.app.domain.coach.CoachReport
import com.elevate.app.domain.coach.CoachVoice
import com.elevate.app.domain.coach.ShareCard
import com.elevate.app.domain.coach.LandingHeat
import com.elevate.app.domain.coach.RallyRhythm
import com.elevate.app.domain.coach.Suggestion
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateRadius
import com.elevate.app.ui.theme.Elevate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every card in the report is engine output, so each carries this tag. */
private const val SOURCE_TAG = "from your video · candidates"

/**
 * "Coach report": where landings fell, the rhythm of the rally candidates, and three plain-language
 * suggestions. Derived only from the match record's landing events and the hit / rally candidates
 * already in [s]; where there is too little data it says so instead of filling in.
 */
@Composable
internal fun CoachSection(s: MatchOverviewState, introPlayed: MutableState<Boolean> = mutableStateOf(true)) {
    val report = remember(s.landings, s.hitTimeline) { CoachReport.build(s.landings, s.hitTimeline.hits, s.hitTimeline.rallies) }
    val c = Elevate.colors
    val voice = rememberVoiceCoach()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shareProblem by remember { mutableStateOf(false) }
    val hitCount = s.hitTimeline.hits.size
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Coach report", style = MaterialTheme.typography.titleMedium, color = c.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        CoachActions(
            speaking = voice.speaking,
            onListen = { voice.toggle(CoachVoice.script(report)) },
            showShare = ShareCard.hasData(report, hitCount),
            onShare = {
                shareProblem = false
                scope.launch {
                    val intent = withContext(Dispatchers.Default) {
                        runCatching {
                            val lines = ShareCard.lines(report, hitCount, ShareCard.format(s.landings.template))
                            val file = ShareCardRenderer.writePng(context, ShareCardRenderer.render(lines, s.landings))
                            ShareCardRenderer.shareIntent(context, file)
                        }.getOrNull()
                    }
                    if (intent == null || runCatching { context.startActivity(intent) }.isFailure) shareProblem = true
                }
            },
        )
    }
    if (voice.unavailable) Text("Voice not available on this phone", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    if (shareProblem) Text("Could not share the card", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    val reduce = rememberReduceMotion()
    val wasPlayed = remember { introPlayed.value || reduce }
    report.suggestions.forEachIndexed { i, sug -> StaggerIn(i, wasPlayed) { SuggestionCard(sug) } }
    LaunchedEffect(Unit) {
        if (!wasPlayed) delay(report.suggestions.size * ElevateMotion.Stagger.toLong() + ElevateMotion.Short)
        introPlayed.value = true
    }
    RhythmCard(report.rhythm)
    ElevateCard {
        Pill(SOURCE_TAG, c.lavender, c.ink)
        Spacer(Modifier.height(10.dp))
        Text("Where your landings fell", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(8.dp))
        ExplainBox(Explanations.Landing, Modifier.fillMaxWidth()) { HeatGrid(report.heat) }
        Spacer(Modifier.height(8.dp))
        Text(report.heat.caption, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    }
}

private val GRID_ROWS = listOf(ZoneRow.BACK to "Back", ZoneRow.MIDDLE to "Mid", ZoneRow.FRONT to "Front")
private val GRID_COLS = listOf("Left", "Centre", "Right")

/** 3x3 heat grid in the hitter's view (back row at the top, net at the bottom); opacity follows each cell's count. */
@Composable
private fun HeatGrid(heat: LandingHeat) {
    val c = Elevate.colors
    val max = ZoneId.ALL.maxOf(heat::count)
    val spoken = if (heat.zoned == 0) {
        CoachReport.NO_LANDINGS
    } else {
        "Landing grid from the hitter's view, ${heat.zoned} landing candidates. " + GRID_ROWS.joinToString(". ") { (row, name) ->
            "$name row: " + (1..3).joinToString(", ") { col -> "${GRID_COLS[col - 1]} ${heat.count(ZoneId(row, col))}" }
        }
    }
    Column(
        Modifier.fillMaxWidth().widthIn(max = 320.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GRID_ROWS.forEach { (row, name) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
                    modifier = Modifier.padding(end = 4.dp).widthIn(min = 40.dp).clearAndSetSemantics { },
                )
                for (col in 1..3) {
                    val n = heat.count(ZoneId(row, col))
                    val alpha = if (n == 0 || max == 0) 0f else 0.12f + 0.63f * n / max
                    Box(
                        Modifier.weight(1f).aspectRatio(1.6f).clip(RoundedCornerShape(ElevateRadius.Tile))
                            .background(c.cream).background(c.orange.copy(alpha = alpha)).clearAndSetSemantics { },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(n.toString(), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.widthIn(min = 44.dp))
            GRID_COLS.forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.inkMuted, textAlign = TextAlign.Center)
            }
        }
        Text(
            "Hitter's view, net at the bottom", style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun RhythmCard(r: RallyRhythm) {
    val c = Elevate.colors
    ElevateCard(tint = c.peach) {
        Pill(SOURCE_TAG, Color.White, c.ink)
        Spacer(Modifier.height(10.dp))
        Text("Rally rhythm", style = MaterialTheme.typography.titleMedium, color = c.ink)
        if (r.rallies == 0) {
            Text(
                "No rally candidates yet: fewer than 2 hits close together",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.padding(top = 6.dp),
            )
            return@ElevateCard
        }
        Spacer(Modifier.height(10.dp))
        val rallies = "${r.rallies} rally candidate${if (r.rallies == 1) "" else "s"}"
        val longest = "${r.longestHits} hits · ${seconds(r.longestSeconds)}"
        val median = r.medianHits?.let { "${CoachReport.num(it)} hits" } ?: "none"
        val tempo = r.medianGapSeconds?.let { "${seconds(it)} between hits (${r.gaps} gaps)" } ?: "none"
        val stats = listOf("Rallies" to rallies, "Longest" to longest, "Median length" to "$median per rally", "Tempo" to tempo)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.forEach { (label, value) ->
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                    Text(value, style = MaterialTheme.typography.titleMedium, color = c.ink)
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion) {
    val c = Elevate.colors
    ExplainBox(Explanations.CoachReport, Modifier.fillMaxWidth()) {
        ElevateCard(tint = if (s.enoughData) c.mint else c.lavender) {
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${s.text}. Evidence: ${s.evidence}. $SOURCE_TAG" }) {
                Pill(SOURCE_TAG, Color.White, c.ink)
                Spacer(Modifier.height(10.dp))
                Text(s.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text(s.evidence, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun seconds(v: Double?): String = if (v == null) "none" else String.format(Locale.ROOT, "%.1f s", v)

/** Slides and fades a card in after [index] x a short step; with [instant] (already played, or reduced motion) it simply shows. */
@Composable
private fun StaggerIn(index: Int, instant: Boolean, content: @Composable () -> Unit) {
    val visible = remember { MutableTransitionState(instant) }
    LaunchedEffect(Unit) {
        if (!instant) {
            delay(index * ElevateMotion.Stagger.toLong())
            visible.targetState = true
        }
    }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(ElevateMotion.Short)) + slideInVertically(tween(ElevateMotion.Short)) { it / 8 },
    ) { content() }
}
