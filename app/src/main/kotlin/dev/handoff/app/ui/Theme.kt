package dev.handoff.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.handoff.core.handoff.Platforms

/** Brand colours from the logo (see docs/brand). Shared look with the Windows app. */
object Brand {
    val Indigo = Color(0xFF4F46E5)
    val DeepIndigo = Color(0xFF312E81)
    val Lavender = Color(0xFFA5B4FC)
    val Success = Color(0xFF1E9E61)
    val gradient = Brush.linearGradient(listOf(Indigo, DeepIndigo))
}

/** The stage and status colours from the token table in docs/DESIGN.md. */
@Immutable
data class HandoffColors(
    val stage: Color,
    val tile: Color,
    val tileOutline: Color,
    val dot: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val warning: Color,
    val online: Color,
)

private val LightTokens = HandoffColors(
    stage = Color(0xFFEEF0F6),
    tile = Color(0xFFFFFFFF),
    tileOutline = Color(0xFFD3D6E2),
    dot = Color(0xFF1D1F27),
    accent = Color(0xFF4F46E5),
    accentSoft = Color(0xFFE7E5FF),
    onAccentSoft = Color(0xFF4338CA),
    warning = Color(0xFFA14F00),
    online = Color(0xFF1E9E61),
)

private val DarkTokens = HandoffColors(
    stage = Color(0xFF23253A),
    tile = Color(0xFF2F3250),
    tileOutline = Color(0xFF4A4E70),
    dot = Color(0xFFFFFFFF),
    accent = Color(0xFF8B85FF),
    accentSoft = Color(0xFF2E2B66),
    onAccentSoft = Color(0xFFC7C4FF),
    warning = Color(0xFFFFB366),
    online = Color(0xFF4FD18B),
)

val LocalHandoffColors = staticCompositionLocalOf { LightTokens }

/** The design tokens for the current light or dark theme. */
val tokens: HandoffColors @Composable get() = LocalHandoffColors.current

private val Light = lightColorScheme(
    primary = LightTokens.accent,
    onPrimary = Color.White,
    primaryContainer = LightTokens.accentSoft,
    onPrimaryContainer = LightTokens.onAccentSoft,
    secondary = Color(0xFF5B5BD6),
    secondaryContainer = LightTokens.accentSoft,
    onSecondaryContainer = LightTokens.onAccentSoft,
    tertiary = LightTokens.warning,
    tertiaryContainer = Color(0xFFFFE9CC),
    onTertiaryContainer = Color(0xFF4A2400),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF1D1F27),
    surface = Color(0xFFF7F8FB),
    onSurface = Color(0xFF1D1F27),
    onSurfaceVariant = Color(0xFF5D6275),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F2F7),
    surfaceContainerHighest = Color(0xFFE8EAF1),
    outline = LightTokens.accent,
    outlineVariant = Color(0xFFD3D6E2),
)

private val Dark = darkColorScheme(
    primary = DarkTokens.accent,
    onPrimary = Color(0xFF14123A),
    primaryContainer = DarkTokens.accentSoft,
    onPrimaryContainer = DarkTokens.onAccentSoft,
    secondary = Color(0xFFA5B4FC),
    secondaryContainer = DarkTokens.accentSoft,
    onSecondaryContainer = DarkTokens.onAccentSoft,
    tertiary = DarkTokens.warning,
    tertiaryContainer = Color(0xFF4A2D0A),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE8E9F2),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE8E9F2),
    onSurfaceVariant = Color(0xFFA3A7BD),
    surfaceContainerLowest = Color(0xFF1B1C24),
    surfaceContainerLow = Color(0xFF1B1C24),
    surfaceContainer = Color(0xFF1B1C24),
    surfaceContainerHigh = Color(0xFF23253A),
    surfaceContainerHighest = Color(0xFF2F3250),
    outline = DarkTokens.accent,
    outlineVariant = Color(0xFF3A3D57),
)

@Composable
fun HandoffTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalHandoffColors provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/** How this Android device names itself and which platform it reports. */
object ThisDevice {
    private fun tablet(context: Context) = context.resources.configuration.smallestScreenWidthDp >= 600

    fun platform(context: Context): String = if (tablet(context)) Platforms.ANDROID_TABLET else Platforms.ANDROID_PHONE

    fun label(context: Context): String = if (tablet(context)) "This tablet" else "This phone"
}
