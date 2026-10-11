package com.elevate.app.ui.components

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.elevate.app.ui.theme.Elevate

/** One corner-radius scale for the whole app: chips and tiles, inner cards, cards and hero video, pills. */
object ElevateRadius {
    val Tile = 12.dp
    val Inner = 16.dp
    val Card = 24.dp
}

/** Short motion durations (ms). Everything here snaps to its end state when motion is reduced. */
object ElevateMotion {
    const val Quick = 120
    const val Short = 220
    const val Stagger = 70
}

/** True when the user turned system animations off (Developer options / Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Cross-fade with a hint of scale for whole-screen panel changes (Analysing, Overview, ReadLab, Reflex). */
fun panelTransition(reduce: Boolean): ContentTransform =
    if (reduce) EnterTransition.None togetherWith ExitTransition.None
    else (fadeIn(tween(ElevateMotion.Short, delayMillis = 60, easing = FastOutSlowInEasing)) +
        scaleIn(tween(ElevateMotion.Short, delayMillis = 60, easing = FastOutSlowInEasing), initialScale = 0.98f)) togetherWith
        fadeOut(tween(ElevateMotion.Quick))

/** Quick cross-fade for tab bodies. */
fun tabTransition(reduce: Boolean): ContentTransform =
    if (reduce) EnterTransition.None togetherWith ExitTransition.None
    else fadeIn(tween(ElevateMotion.Short, delayMillis = 40)) togetherWith fadeOut(tween(ElevateMotion.Quick))

/** A verdict card popping in: fade plus a small overshoot-free scale. */
fun popIn(reduce: Boolean): EnterTransition =
    if (reduce) EnterTransition.None
    else fadeIn(tween(ElevateMotion.Short)) + scaleIn(tween(ElevateMotion.Short, easing = FastOutSlowInEasing), initialScale = 0.92f)

/** Haptics that stay subtle: one light tick for taps, one confirm for the primary action. */
class Haptics(private val feedback: HapticFeedback) {
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

/** A soft pulsing placeholder block (static when motion is reduced). */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 14.dp, corner: Dp = ElevateRadius.Tile / 2) {
    val reduce = rememberReduceMotion()
    val alpha: State<Float> = if (reduce) {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.7f) }
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.45f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "skeleton-alpha",
        )
    }
    Box(modifier.height(height).alpha(alpha.value).clip(RoundedCornerShape(corner)).background(Elevate.colors.peachDeep))
}

/** A card-shaped skeleton: a title bar and a few text lines. Not announced to screen readers as content. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, lines: Int = 3) {
    ElevateCard(modifier) {
        SkeletonBlock(Modifier.width(120.dp), height = 16.dp)
        Spacer(Modifier.height(12.dp))
        repeat(lines) { i ->
            SkeletonBlock(Modifier.fillMaxWidth(if (i == lines - 1) 0.6f else 1f))
            if (i < lines - 1) Spacer(Modifier.height(8.dp))
        }
    }
}

/** Skeleton for a loading list or report: [cards] placeholder cards. */
@Composable
fun SkeletonList(cards: Int = 3, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { repeat(cards) { SkeletonCard() } }
}

/** A designed empty or error state: an icon, one line, and an optional action. */
@Composable
fun StateMessage(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Elevate.colors
    ElevateCard(modifier, tint = if (isError) c.errorSurface else Color.White) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { if (isError) liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (isError) Color.White else c.peach),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (isError) c.error else c.orangeText, modifier = Modifier.size(24.dp)) }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(actionLabel, onAction, Modifier.fillMaxWidth())
        }
    }
}

/** A streak badge made of an icon and a number only. */
@Composable
fun StreakBadge(streak: Int, modifier: Modifier = Modifier) {
    val c = Elevate.colors
    Row(
        modifier.clip(CircleShape).background(c.peach).padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Streak of $streak" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ElevateIcons.Flame, contentDescription = null, tint = c.orangeText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("$streak", style = MaterialTheme.typography.labelMedium, color = c.orangeText, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** The value, counting up from the previous one (snaps when motion is reduced). */
@Composable
fun animatedCount(target: Int): Int {
    val reduce = rememberReduceMotion()
    val v by animateIntAsState(target, if (reduce) tween(0) else tween(600, easing = FastOutSlowInEasing), label = "count-up")
    return v
}

/** Content that pops in once when it enters the composition (a verdict arriving). */
@Composable
fun PopIn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = rememberReduceMotion()
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(state, modifier, enter = popIn(reduce), exit = ExitTransition.None) { content() }
}
