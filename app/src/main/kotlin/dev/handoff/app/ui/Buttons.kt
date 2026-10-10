package dev.handoff.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.handoff.core.stage.StageAction

/**
 * The logo's curved arrow, used on Move here (docs/DESIGN.md: never a generic swap icon). It rises
 * from the left and lands on the right, like the arc on the stage.
 */
val MoveIcon: ImageVector by lazy {
    ImageVector.Builder("Move", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 17f)
            curveTo(5f, 10.5f, 10f, 7f, 18.5f, 9f)
            moveTo(14.2f, 5.6f)
            lineTo(18.8f, 9.1f)
            lineTo(15.2f, 13.4f)
        }
    }.build()
}

/** The one filled button on a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().height(48.dp), enabled = enabled) {
        ButtonContent(text, icon)
    }
}

/** A secondary action with a light indigo fill. [compact] is the 40 dp version inside small cards. */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, compact: Boolean = false) {
    FilledTonalButton(onClick = onClick, modifier = if (compact) modifier.height(40.dp) else modifier.fillMaxWidth().height(48.dp)) {
        ButtonContent(text, icon)
    }
}

/** A secondary action with an indigo border. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        border = BorderStroke(1.5.dp, tokens.accent),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.accent),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** The button under a stage: filled for moves and fixes, tonal for Use only here, outline for Cancel. */
@Composable
fun StageButton(action: StageAction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = action.label ?: return
    when (action) {
        StageAction.USE_ONLY_HERE -> TonalButton(label, onClick, modifier)
        StageAction.CANCEL -> OutlineButton(label, onClick, modifier)
        StageAction.MOVE_HERE, StageAction.CONNECT_HERE_TOO -> PrimaryButton(label, onClick, modifier, icon = MoveIcon)
        else -> PrimaryButton(label, onClick, modifier)
    }
}
