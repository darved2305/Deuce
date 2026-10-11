package com.elevate.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens taken from the v2 reference screens (warm light surfaces, orange action, navy ink). */
@Immutable
data class ElevateColors(
    val cream: Color = Color(0xFFFFF8F2),
    val peach: Color = Color(0xFFFDE4D3),
    val peachDeep: Color = Color(0xFFFAD3BC),
    // Contrast ≥ 4.5:1 (docs/plan/05 §9): white on orange 4.78, orange text on cream 4.54.
    val orange: Color = Color(0xFFC8481A),
    /** Orange for text on peach surfaces (4.83 on peach). */
    val orangeText: Color = Color(0xFFB03F12),
    val orangeDeep: Color = Color(0xFFD4501C),
    val ink: Color = Color(0xFF1C1F45),
    // ≥ 4.64:1 on every light surface used (cream, white, mint, error, peach).
    val inkMuted: Color = Color(0xFF62648A),
    val line: Color = Color(0xFFEDE6E0),
    val surface: Color = Color(0xFFFFFFFF),
    val mint: Color = Color(0xFFE4F2DE),
    val mintInk: Color = Color(0xFF2F7D3A),
    val lavender: Color = Color(0xFFECE8FC),
    val lavenderInk: Color = Color(0xFF6656D2),
    val court: Color = Color(0xFF173B32),
    val error: Color = Color(0xFFB3261E),
    val errorSurface: Color = Color(0xFFFCE8E6),
)

val LocalElevateColors = staticCompositionLocalOf { ElevateColors() }

private val ElevateShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

private fun type(c: ElevateColors) = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, color = c.ink),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, color = c.ink),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, color = c.ink),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = c.ink),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp, color = c.ink),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp, color = c.inkMuted),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun ElevateTheme(content: @Composable () -> Unit) {
    val c = ElevateColors()
    val scheme = lightColorScheme(
        primary = c.orange,
        onPrimary = Color.White,
        background = c.cream,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        onSurfaceVariant = c.inkMuted,
        outline = c.line,
        error = c.error,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalElevateColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = type(c), shapes = ElevateShapes, content = content)
    }
}

object Elevate {
    val colors: ElevateColors
        @Composable get() = LocalElevateColors.current
}
