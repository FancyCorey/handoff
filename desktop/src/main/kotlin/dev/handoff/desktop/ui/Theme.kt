package dev.handoff.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image as SkiaImage

/** Brand colours, taken from the logo gradient (see docs/brand). */
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
    surfaceContainer = Color(0xFF1C1E27),
    surfaceContainerHigh = Color(0xFF262833),
)

@Composable
fun HandoffTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

object Resources {
    fun image(name: String): ImageBitmap {
        val bytes = Resources::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() }
        return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }

    val logo: ImageBitmap by lazy { image("handoff.png") }
    val logoPainter by lazy { BitmapPainter(logo) }
    val trayPainter by lazy { BitmapPainter(image("tray.png")) }
}

@Composable
fun Logo(size: Dp) {
    Image(Resources.logo, contentDescription = "Handoff", modifier = Modifier.size(size))
}

enum class Tone { POSITIVE, ACTIVE, NEUTRAL, WARNING }

/** Small rounded status label. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        Tone.POSITIVE -> Brand.Success.copy(alpha = 0.16f) to Brand.Success
        Tone.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Tone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onSurface
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** Round tonal container for an icon. */
@Composable
fun IconBadge(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: @Composable () -> Unit) {
    Surface(modifier.size(44.dp), shape = CircleShape, color = container) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
