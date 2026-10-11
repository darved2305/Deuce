package com.elevate.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevate.app.domain.court.ZoneId
import com.elevate.app.domain.court.ZoneRow
import com.elevate.app.domain.profile.FocusItem
import com.elevate.app.domain.profile.PlayerProfile
import com.elevate.app.domain.profile.Placement
import com.elevate.app.domain.profile.StyleTagId
import com.elevate.app.domain.profile.TrainingStats
import com.elevate.app.domain.profile.Trend
import com.elevate.app.ui.components.ElevateCard
import com.elevate.app.ui.components.ElevateHeader
import com.elevate.app.ui.components.ExplainBox
import com.elevate.app.ui.components.Explanations
import com.elevate.app.ui.components.PeachPage
import com.elevate.app.ui.components.ScreenGutter
import com.elevate.app.ui.theme.Elevate
import java.util.Locale

/** The round "Your game" button in Home's header: the first letter of the saved name. Hold to explain. */
@Composable
fun ProfileAvatarButton(onClick: () -> Unit) {
    val c = Elevate.colors
    val context = LocalContext.current
    val initial = remember { ProfileName.initial(ProfileName.get(context)) }
    ExplainBox(Explanations.YourGame) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(c.peachDeep)
                .clickable(onClickLabel = "Open your game", role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(initial, style = MaterialTheme.typography.titleMedium, color = c.orangeText) }
    }
}

/** Loads the profile off the main thread and shows it. */
@Composable
fun ProfileRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val load by produceState<ProfileLoad?>(null) { value = ProfileLoader.load(context) }
    BackHandler(onBack = onBack)
    ProfileScreen(load = load, onBack = onBack)
}

private val TAG_EXPLANATION = mapOf(
    StyleTagId.DEEP_HITTER to Explanations.StyleDeepHitter,
    StyleTagId.NET_PLAYER to Explanations.StyleNetPlayer,
    StyleTagId.RALLY_BUILDER to Explanations.StyleRallyBuilder,
    StyleTagId.SHORT_RALLIES to Explanations.StyleShortRallies,
    StyleTagId.FAST_EXCHANGES to Explanations.StyleFastExchanges,
)

@Composable
fun ProfileScreen(load: ProfileLoad?, onBack: () -> Unit) {
    PeachPage {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ElevateHeader(title = "Your game", onBack = onBack)
            when {
                load == null -> Text(
                    "Reading your matches",
                    style = MaterialTheme.typography.bodyMedium, color = Elevate.colors.inkMuted,
                    modifier = Modifier.padding(ScreenGutter),
                )
                else -> ProfileBody(load)
            }
        }
    }
}

@Composable
private fun ProfileBody(load: ProfileLoad) {
    val p = load.profile
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.height(4.dp))
                    NameCard()
                    if (p.isEmpty) EmptyCard(load.notYetAnalysed) else {
                        StatTiles(p, load.notYetAnalysed)
                        StyleCard(p)
                        PlacementCard(p.placement)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (!p.isEmpty) {
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spacer(Modifier.height(4.dp))
                        TrendsCard(p)
                        TrainingCard(p.training)
                        FocusCard(p)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                NameCard()
                if (p.isEmpty) EmptyCard(load.notYetAnalysed) else {
                    StatTiles(p, load.notYetAnalysed)
                    StyleCard(p)
                    PlacementCard(p.placement)
                    TrendsCard(p)
                    TrainingCard(p.training)
                    FocusCard(p)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun NameCard() {
    val c = Elevate.colors
    val context = LocalContext.current
    var name by remember { mutableStateOf(ProfileName.get(context)) }
    ElevateCard {
        Text("Your name", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(ProfileName.MAX_LENGTH)
                ProfileName.save(context, name.trim())
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.titleMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = c.orange, unfocusedIndicatorColor = c.line,
            ),
            placeholder = { Text(ProfileName.DEFAULT, color = c.inkMuted) },
        )
        Text("Saved on this phone only.", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}

@Composable
private fun EmptyCard(notYet: Int) {
    val c = Elevate.colors
    ElevateCard(tint = c.peachDeep.copy(alpha = 0.6f)) {
        Text(PlayerProfile.EMPTY_TITLE, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your totals, shot placement, style and trends are built from matches analysed on this phone.",
            style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
        )
        if (notYet > 0) {
            Spacer(Modifier.height(8.dp))
            Text(notYetText(notYet), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
    }
}

private fun notYetText(n: Int) = "$n not yet analysed"

@Composable
private fun StatTiles(p: PlayerProfile, notYet: Int) {
    val t = p.totals
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("${t.matches}", if (t.matches == 1) "match analysed" else "matches analysed", Modifier.weight(1f))
            StatTile("${t.minutes}", "min of video", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("${t.hits}", "hits found", Modifier.weight(1f))
            StatTile("${t.rallies}", "rallies found", Modifier.weight(1f))
        }
        val notes = buildList {
            if (notYet > 0) add(notYetText(notYet))
            if (t.matchesWithoutDuration > 0) add("${t.matchesWithoutDuration} without a known video length")
            add("Hits and rallies are the engine's candidates, from both players.")
        }
        Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Elevate.colors.inkMuted)
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$value $label" },
    ) {
        Text(value, style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp), color = c.ink)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleCard(p: PlayerProfile) {
    val c = Elevate.colors
    ElevateCard {
        Text("Your style", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(8.dp))
        if (p.tags.isEmpty()) {
            Text(p.tagsHint.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                p.tags.forEach { tag ->
                    ExplainBox(TAG_EXPLANATION.getValue(tag.id)) {
                        Text(
                            tag.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                            color = c.orangeText,
                            modifier = Modifier
                                .clip(CircleShape).background(c.peach)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            p.tags.forEach { tag ->
                Text("${tag.label}: ${tag.evidence}", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
            Spacer(Modifier.height(4.dp))
            Text("Hold a label to see its rule.", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
    }
}

@Composable
private fun PlacementCard(pl: Placement) {
    val c = Elevate.colors
    ElevateCard {
        ExplainBox(Explanations.PlacementHeat) { Text("Where shots land", style = MaterialTheme.typography.titleMedium, color = c.ink) }
        Spacer(Modifier.height(8.dp))
        if (pl.isEmpty) {
            Text("No landings with court positions yet. Set up the court before you analyse to see this.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            return@ElevateCard
        }
        Text("Net", style = MaterialTheme.typography.labelMedium, color = c.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(12.dp)).background(c.court).padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ZoneRow.entries.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (1..3).forEach { col -> HeatCell(pl, ZoneId(row, col), Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Back of the court", style = MaterialTheme.typography.labelMedium, color = c.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val extra = if (pl.unzoned > 0) " · ${pl.unzoned} out or on the net line, not counted" else ""
        Text(
            "${pl.total} landings from ${pl.matches} match${if (pl.matches == 1) "" else "es"}$extra. Left and right are from the hitter's view.",
            style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
        )
    }
}

@Composable
private fun HeatCell(pl: Placement, zone: ZoneId, modifier: Modifier) {
    val c = Elevate.colors
    val s = pl.cell(zone)
    val alpha = if (s.n == 0) 0.06f else 0.2f + 0.8f * s.share.toFloat().coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxSize()
            .background(c.orange.copy(alpha = alpha))
            .semantics(mergeDescendants = true) { contentDescription = "${zone.id}: ${s.n} of ${s.total} landings, ${s.percent} percent" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${s.percent}%", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("${s.n} of ${s.total}", style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp), color = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun TrendsCard(p: PlayerProfile) {
    val c = Elevate.colors
    val rows = p.trends.filter { it.points.isNotEmpty() }
    ElevateCard {
        ExplainBox(Explanations.Trends) { Text("Trends", style = MaterialTheme.typography.titleMedium, color = c.ink) }
        Spacer(Modifier.height(8.dp))
        if (p.totals.matches < Trend.MIN_POINTS) {
            Text(Trend.TREND_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            return@ElevateCard
        }
        if (rows.isEmpty()) Text("No trend data yet.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        rows.forEach { TrendRow(it); Spacer(Modifier.height(10.dp)) }
        Text("Oldest match on the left, newest on the right.", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}

private fun fmt(v: Double, decimals: Int): String = when {
    decimals == 0 -> String.format(Locale.ROOT, "%.0f", v)
    v == Math.rint(v) -> String.format(Locale.ROOT, "%.0f", v)
    else -> String.format(Locale.ROOT, "%.${decimals}f", v)
}

@Composable
private fun TrendRow(t: Trend) {
    val c = Elevate.colors
    val unit = if (t.metric.unit.isEmpty()) "" else if (t.metric.unit == "%" || t.metric.unit == "s") t.metric.unit else " ${t.metric.unit}"
    val latest = t.latest?.let { fmt(it, t.metric.decimals) + unit }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.metric.label, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            if (t.shown) {
                Text("Latest $latest · ${t.points.size} matches", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            } else {
                Text("$latest · ${t.message}", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
        }
        if (t.shown) {
            Sparkline(
                values = t.points.map { it.value },
                description = "${t.metric.label}: " + t.points.joinToString(", ") { fmt(it.value, t.metric.decimals) },
                modifier = Modifier.width(120.dp).height(40.dp),
            )
        }
    }
}

/** A line through the values with a dot on each, the last one filled. Drawn on a Canvas, no library. */
@Composable
fun Sparkline(values: List<Double>, description: String, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    Canvas(modifier.semantics { contentDescription = description }) {
        if (values.size < 2) return@Canvas
        val pad = 5.dp.toPx()
        val lo = values.min()
        val hi = values.max()
        val span = hi - lo
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun pt(i: Int): Offset {
            val x = pad + w * i / (values.size - 1)
            val y = if (span == 0.0) pad + h / 2 else pad + h * (1 - ((values[i] - lo) / span)).toFloat()
            return Offset(x, y)
        }
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(path, c.orange, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (i in values.indices) {
            val last = i == values.lastIndex
            drawCircle(if (last) c.orange else Color.White, if (last) 4.dp.toPx() else 3.dp.toPx(), pt(i))
            if (!last) drawCircle(c.orange, 3.dp.toPx(), pt(i), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun TrainingCard(t: TrainingStats) {
    val c = Elevate.colors
    ElevateCard {
        ExplainBox(Explanations.TrainingStats) { Text("Training", style = MaterialTheme.typography.titleMedium, color = c.ink) }
        Spacer(Modifier.height(8.dp))
        if (t.isEmpty) {
            Text("No training saved yet. Play Reflex or ReadLab from a match overview.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        Text("Reflex", style = MaterialTheme.typography.labelLarge, color = c.orangeText)
        if (t.reflexMax == null) {
            Text("Not played yet", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        } else {
            Text("Best ${t.reflexMax} · latest match ${t.reflexLatest}", style = MaterialTheme.typography.bodyMedium, color = c.ink)
            Text("Best score on each of ${t.reflexMatches} match${if (t.reflexMatches == 1) "" else "es"}.", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        Spacer(Modifier.height(10.dp))
        Text("ReadLab", style = MaterialTheme.typography.labelLarge, color = c.orangeText)
        val pct = t.readLabExactPercent
        if (pct == null) {
            Text("No scored reads saved yet", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        } else {
            Text("Exact reads ${t.readLabExact} of ${t.readLabScored} ($pct%)", style = MaterialTheme.typography.bodyMedium, color = c.ink)
            Text(
                "${t.readLabAdjacent} adjacent · ${t.readLabMatches} match${if (t.readLabMatches == 1) "" else "es"} · confirmed landings only.",
                style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Saved on this phone. ReadLab keeps your first run on each match, and only from this version on.",
            style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
        )
    }
}

@Composable
private fun FocusCard(p: PlayerProfile) {
    val c = Elevate.colors
    ElevateCard(tint = c.lavender) {
        ExplainBox(Explanations.FocusAreas) { Text("Strength and focus", style = MaterialTheme.typography.titleMedium, color = c.ink) }
        Spacer(Modifier.height(8.dp))
        val f = p.focus
        if (f.strength == null && f.focus == null) {
            Text(
                "A pattern shows here once it repeats in 2 matches with enough data.",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            return@ElevateCard
        }
        f.strength?.let { FocusLine("Strength", it) }
        if (f.strength != null && f.focus != null) Spacer(Modifier.height(10.dp))
        f.focus?.let { FocusLine("Focus", it) }
    }
}

@Composable
private fun FocusLine(kind: String, item: FocusItem) {
    val c = Elevate.colors
    Text(kind, style = MaterialTheme.typography.labelLarge, color = c.lavenderInk)
    Text(item.title, style = MaterialTheme.typography.titleMedium, color = c.ink)
    Text(item.advice, style = MaterialTheme.typography.bodyMedium, color = c.ink)
    Text("${item.evidence} with enough data", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
}
