package dev.handoff.desktop.ui

// Kept in step with the Android copy in app/src/main/kotlin/dev/handoff/app/ui (same drawing, same rules).

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.handoff.core.handoff.StepKind
import dev.handoff.core.handoff.TransferStep
import dev.handoff.core.stage.Arc
import dev.handoff.core.stage.Mark
import dev.handoff.core.stage.Stage
import dev.handoff.core.stage.StageTile
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stage (docs/DESIGN.md): device tiles in a row, the headphones as a dot above the device
 * that has them, and an arc to this device when they can move here. [mini] is the small picture
 * on compact cards; it has no labels and screen readers skip it.
 *
 * While a move runs, [steps] advance the dot along a solid arc. Motion follows the system
 * animation setting, so with animations removed the dot simply jumps to each step.
 */
@Composable
fun StageView(stage: Stage, modifier: Modifier = Modifier, mini: Boolean = false, steps: List<TransferStep> = emptyList()) {
    val c = tokens
    val tileSize = if (mini) 18.dp else 44.dp
    val dotArea = when {
        mini -> 16.dp
        stage.compact -> 22.dp
        else -> 52.dp
    }
    val pad = if (mini) 6.dp else 16.dp
    val labelArea = when {
        mini -> 0.dp
        stage.tiles.any { it.offline } -> 34.dp
        else -> 20.dp
    }
    val progress by animateFloatAsState(progressOf(steps), animationSpec = tween(700), label = "move")
    val measurer = rememberTextMeasurer()
    val markStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.dot)

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(if (mini) 10.dp else 24.dp))
            .background(c.stage)
            .alpha(if (stage.dimmed) 0.45f else 1f)
            .clearAndSetSemantics { if (!mini) contentDescription = stage.spoken },
    ) {
        val width = maxWidth
        val n = stage.tiles.size
        // Tiles sit at the ends and spread evenly between them; one tile sits in the middle.
        val centers: List<Dp> = stage.tiles.indices.map { i ->
            if (n == 1) width / 2 else pad + tileSize / 2 + (width - pad * 2 - tileSize) * (i.toFloat() / (n - 1))
        }
        val height = pad + dotArea + tileSize + labelArea + (if (mini) pad else 12.dp)
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val dotY = (pad + dotArea * 0.5f).toPx()
                val r = (if (mini) 3.5.dp else 9.dp).toPx()
                val thisX = centers.last().toPx()
                val moving = stage.arc == Arc.SOLID
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                val stroke = (if (mini) 1.5.dp else 2.5.dp).toPx()
                val lift = (if (mini) 10.dp else 30.dp).toPx()

                stage.tiles.forEachIndexed { i, tile ->
                    if (!tile.arcFrom || stage.arc == Arc.NONE) return@forEachIndexed
                    val from = Offset(centers[i].toPx(), dotY)
                    val to = Offset(thisX, dotY)
                    // Lift the curve more for longer arcs so converging arcs don't overlap.
                    val ctrl = Offset((from.x + to.x) / 2, dotY - lift * (0.6f + 0.4f * (to.x - from.x) / size.width) * 2)
                    val path = Path().apply {
                        moveTo(from.x + r * 1.2f, from.y - r * 0.4f)
                        quadraticTo(ctrl.x, ctrl.y, to.x - r * 1.4f, to.y - r * 0.6f)
                    }
                    if (moving) {
                        drawPath(path, c.accent.copy(alpha = 0.18f), style = Stroke(stroke, cap = StrokeCap.Round))
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val done = Path()
                        measure.getSegment(0f, measure.length * progress, done, true)
                        drawPath(done, c.accent, style = Stroke(stroke, cap = StrokeCap.Round))
                        // The headphones travel along the arc.
                        val at = measure.getPosition(measure.length * progress)
                        drawCircle(c.dot, r, at)
                    } else {
                        drawPath(path, c.accent, style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = dash))
                        arrowHead(path, c.accent, stroke, if (mini) 4.dp.toPx() else 7.dp.toPx())
                    }
                }

                stage.tiles.forEachIndexed { i, tile ->
                    val at = Offset(centers[i].toPx(), dotY)
                    when (tile.mark) {
                        Mark.HOLDS -> if (!(moving && tile.arcFrom)) drawCircle(c.dot, r, at)
                        Mark.MAYBE -> {
                            drawCircle(c.dot, r, at, style = Stroke(stroke * 0.7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                            if (!mini) {
                                val text = measurer.measure("?", markStyle)
                                drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
                            }
                        }
                        Mark.TARGET -> if (!(moving && progress >= 0.99f)) {
                            drawCircle(c.accent, r, at, style = Stroke(stroke * 0.7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))))
                        }
                        Mark.JOIN -> {
                            drawCircle(c.accent, r, at, style = Stroke(stroke * 0.8f))
                            val arm = r * 0.5f
                            drawLine(c.accent, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), stroke * 0.8f, StrokeCap.Round)
                            drawLine(c.accent, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), stroke * 0.8f, StrokeCap.Round)
                        }
                        Mark.NONE -> Unit
                    }
                }
            }

            stage.tiles.forEachIndexed { i, tile ->
                val labelWidth = if (n <= 2) 120.dp else (width - pad * 2) / n
                Column(
                    Modifier
                        .offset(x = centers[i] - (if (mini) tileSize else labelWidth) / 2, y = pad + dotArea)
                        .width(if (mini) tileSize else labelWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Tile(tile, tileSize, mini)
                    if (!mini) {
                        Text(
                            tile.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (tile.isThis) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (tile.isThis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (tile.offline) {
                            Text("Offline", style = MaterialTheme.typography.labelSmall, color = c.warning, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(tile: StageTile, size: Dp, mini: Boolean) {
    val c = tokens
    val shape = RoundedCornerShape(if (mini) 5.dp else 14.dp)
    val outline = if (tile.isThis) c.accent else c.tileOutline
    val base = Modifier.size(size).clip(shape)
    val drawn = when {
        tile.offline -> base.drawBehind { dashedOutline(c.tileOutline, (if (mini) 5.dp else 14.dp).toPx()) }
        else -> base.background(c.tile).border(if (tile.isThis) 2.dp else 1.dp, outline, shape)
    }
    Box(drawn, contentAlignment = Alignment.Center) {
        when {
            mini -> Unit
            tile.folded > 0 -> Text("+${tile.folded}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            else -> Icon(
                platformIcon(tile.platform),
                contentDescription = null,
                tint = if (tile.offline) c.tileOutline else if (tile.isThis) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

private fun DrawScope.dashedOutline(color: Color, radius: Float) {
    drawRoundRect(
        color,
        cornerRadius = CornerRadius(radius),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** A small open arrowhead at the end of [path], pointing along it. */
private fun DrawScope.arrowHead(path: Path, color: Color, stroke: Float, length: Float) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val end = measure.getPosition(measure.length)
    val before = measure.getPosition((measure.length - length).coerceAtLeast(0f))
    val angle = atan2(end.y - before.y, end.x - before.x)
    val spread = 0.55f
    val head = Path().apply {
        moveTo(end.x - length * cos(angle - spread), end.y - length * sin(angle - spread))
        lineTo(end.x, end.y)
        lineTo(end.x - length * cos(angle + spread), end.y - length * sin(angle + spread))
    }
    drawPath(head, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** How far along the arc the headphones are, from the steps a move has reached. */
private fun progressOf(steps: List<TransferStep>): Float = steps.fold(0.08f) { p, step ->
    maxOf(
        p,
        when (step.kind) {
            StepKind.REQUESTING_RELEASE -> 0.2f
            StepKind.RELEASED, StepKind.PEER_NOT_CONNECTED, StepKind.DIRECT_TAKEOVER -> 0.5f
            StepKind.CONNECTING -> 0.65f
            StepKind.VERIFYING -> 0.85f
            StepKind.CONNECTED, StepKind.ALREADY_CONNECTED -> 1f
            else -> p
        },
    )
}
