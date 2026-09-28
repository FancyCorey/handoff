package dev.handoff.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Brand colours from the logo gradient (see docs/brand). Shared look with the Windows app. */
object Brand {
    val Teal = Color(0xFF1FA2C4)
    val Indigo = Color(0xFF3D3BB7)
    val Amber = Color(0xFFFFC940)
    val Success = Color(0xFF1E9E61)
    val gradient = Brush.linearGradient(listOf(Teal, Indigo))
}

private val Light = lightColorScheme(
    primary = Color(0xFF3558C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0E1B5C),
    secondary = Color(0xFF16809B),
    secondaryContainer = Color(0xFFCDEFF8),
    onSecondaryContainer = Color(0xFF002A35),
    tertiary = Color(0xFF8A6100),
    tertiaryContainer = Color(0xFFFFE9B0),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFF7F8FC),
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEDEFF6),
    surfaceContainerHigh = Color(0xFFE6E9F2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB7C4FF),
    onPrimary = Color(0xFF0E1B5C),
    primaryContainer = Color(0xFF2C3F9E),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF7FD3EA),
    secondaryContainer = Color(0xFF004E60),
    onSecondaryContainer = Color(0xFFCDEFF8),
    tertiary = Color(0xFFFFC940),
    tertiaryContainer = Color(0xFF5E4300),
    background = Color(0xFF12131A),
    surface = Color(0xFF12131A),
    surfaceContainerLow = Color(0xFF181A22),
    surfaceContainer = Color(0xFF1C1E27),
    surfaceContainerHigh = Color(0xFF262833),
)

@Composable
fun HandoffTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
