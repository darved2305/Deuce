package com.elevate.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevate.app.ui.theme.Elevate

/*
 * The app's shared building blocks, taken from the v2 reference screens (android/elevate-ui-v2):
 * peach → cream page, circular back button with a centred title, the real video as a rounded hero
 * card, orange pill actions, white rounded cards, small pill chips, a stage checklist.
 * Screens compose these; they do not restyle them.
 */

/** Page background: warm peach at the top fading to cream. */
@Composable
fun PeachPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Elevate.colors
    Box(
        modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.peach, 0.35f to c.cream, 1f to c.cream)),
        content = content,
    )
}

/** Circular back button · centred title (+ optional subtitle) · optional trailing (step "1/3", action). */
@Composable
fun ElevateHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    dark: Boolean = false,
) {
    val c = Elevate.colors
    val fg = if (dark) Color.White else c.ink
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            if (onBack != null) {
                CircleButton(ElevateIcons.Back, "Back", onBack, dark = dark)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = if (dark) Color.White.copy(alpha = 0.75f) else c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { trailing?.invoke() }
    }
}

/** 48 dp round icon button: white on light pages, translucent on video/dark. */
@Composable
fun CircleButton(icon: ImageVector, label: String, onClick: () -> Unit, dark: Boolean = false, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    Box(
        modifier
            .size(48.dp)
            .shadow(if (dark) 0.dp else 2.dp, CircleShape)
            .clip(CircleShape)
            .background(if (dark) Color.White.copy(alpha = 0.14f) else Color.White)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (dark) Color.White else c.ink) }
}

/** Step counter for the header's trailing slot, e.g. "1/3". */
@Composable
fun StepCount(step: Int, of: Int) {
    Text("$step/$of", style = MaterialTheme.typography.labelLarge, color = Elevate.colors.inkMuted)
}

/** Big navy headline with an optional small line above it ("Badminton · 8:04 · 1080p50"). */
@Composable
fun Headline(text: String, eyebrow: String? = null, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    Column(modifier) {
        if (eyebrow != null) Text(eyebrow, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        Text(text, style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, lineHeight = 36.sp), color = c.ink)
    }
}

/** The real video as a rounded hero card. Put the player surface and chips inside. */
@Composable
fun VideoCard(modifier: Modifier = Modifier, corner: Dp = ElevateRadius.Card, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(corner))
            .clip(RoundedCornerShape(corner))
            .background(Color.Black),
        content = content,
    )
}

/** Dark translucent pill over video ("Your video · original frames", "0:12 / 0:31"). */
@Composable
fun VideoChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(CircleShape).background(Color(0xCC1C1F45)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** The one main action of a screen: full-width orange pill. Shows a spinner when [busy]. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val c = Elevate.colors
    val haptics = rememberHaptics()
    Button(
        onClick = { haptics.confirm(); onClick() },
        enabled = enabled && !busy,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.orange, contentColor = Color.White,
            disabledContainerColor = c.line, disabledContentColor = c.inkMuted,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp).shadow(if (enabled) 6.dp else 0.dp, CircleShape),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Secondary action: white pill with a hairline. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Elevate.colors
    val haptics = rememberHaptics()
    OutlinedButton(
        onClick = { haptics.tick(); onClick() },
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = c.ink),
        modifier = modifier.heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
}

/** White rounded card. [tint] for the peach / mint / lavender variants. */
@Composable
fun ElevateCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Elevate.colors
    val reduce = rememberReduceMotion()
    // A tint change (a verdict arriving) fades instead of jumping.
    val fill by animateColorAsState(tint, tween(if (reduce) 0 else ElevateMotion.Short), label = "card-tint")
    val shape = RoundedCornerShape(ElevateRadius.Card)
    Column(
        modifier
            .fillMaxWidth()
            // One card style: soft 1 dp lift on white, a hairline on every card.
            .shadow(if (tint == Color.White) 1.dp else 0.dp, shape)
            .clip(shape)
            .background(fill)
            .border(1.dp, c.line.copy(alpha = 0.7f), shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        content = content,
    )
}

/** Selectable pill (sport, format, speed, trail source). */
@Composable
fun PillChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Elevate.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) c.peach else Color.White)
            .border(1.dp, if (selected) c.orange else c.line, CircleShape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = when {
                !enabled -> c.inkMuted.copy(alpha = 0.6f)
                selected -> c.orangeText
                else -> c.ink
            },
        )
    }
}

/** Small section title ("Corners", "Sport"), with an optional orange action on the right ("See all ›"). */
@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null && onAction != null) {
            Text(
                action, style = MaterialTheme.typography.labelLarge, color = c.orangeText,
                modifier = Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

enum class StageState { Done, Active, Pending, Failed }

/** One line of a stage checklist: ✓ / ● / ○ · label · real value on the right ("41/114", "done"). */
@Composable
fun StageRow(label: String, state: StageState, value: String? = null) {
    val c = Elevate.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val (bg, ring) = when (state) {
            StageState.Done -> c.mintInk to c.mintInk
            StageState.Active -> Color.White to c.orange
            StageState.Pending -> Color.White to c.line
            StageState.Failed -> c.error to c.error
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(bg).border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                StageState.Done -> Icon(ElevateIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                StageState.Active -> Box(Modifier.size(10.dp).clip(CircleShape).background(c.orange))
                StageState.Failed -> Icon(ElevateIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                StageState.Pending -> Unit
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (state == StageState.Pending) c.inkMuted else c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = c.inkMuted, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Real progress bar (fraction from a measured numerator/denominator only). */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    val reduce = rememberReduceMotion()
    val smooth by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), tween(if (reduce) 0 else 300), label = "progress")
    Box(modifier.fillMaxWidth().heightIn(min = 10.dp).clip(CircleShape).background(Color.White)) {
        Box(Modifier.fillMaxWidth(smooth).heightIn(min = 10.dp).clip(CircleShape).background(c.orange))
    }
}

/** Vertical rhythm for screens: 20 dp side gutter, content spaced 16 dp. */
val ScreenGutter = 20.dp
val ScreenSpacing = Arrangement.spacedBy(16.dp)
