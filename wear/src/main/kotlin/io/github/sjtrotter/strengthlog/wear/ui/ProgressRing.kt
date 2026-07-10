package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.wear.theme.Border
import io.github.sjtrotter.strengthlog.wear.theme.Done

/**
 * The exercise-stream progress ring (design digest §1.2): one segment per
 * round in *this* exercise, overlaid on the whole circular face, decorative
 * and non-interactive. Geometry comes from [ringSegments]; this composable
 * only maps [RingSegmentState] to a color and draws.
 */
@Composable
fun ProgressRingCanvas(segments: List<RingSegment>, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidthPx = 5.dp.toPx()
        val diameter = kotlin.math.min(size.width, size.height) - strokeWidthPx
        val topLeft = Offset(
            x = (size.width - diameter) / 2f,
            y = (size.height - diameter) / 2f,
        )
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

        segments.forEach { segment ->
            val color = when (segment.state) {
                RingSegmentState.DONE -> Done
                RingSegmentState.CURRENT -> accent
                RingSegmentState.TRACK -> Border
            }
            drawArc(
                color = color,
                startAngle = segment.startAngleDeg,
                sweepAngle = segment.sweepAngleDeg,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
