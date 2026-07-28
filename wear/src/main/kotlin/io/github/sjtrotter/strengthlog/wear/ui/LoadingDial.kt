package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent
import io.github.sjtrotter.strengthlog.wear.theme.dialTypography
import kotlin.math.min

/**
 * Waiting for the phone's first snapshot (brief §7): one accent segment sweeping
 * the day rim, the wordmark in the disc. No dots, no spinner, no vocabulary the
 * dial doesn't already have — the loading state is the dial with nothing in it yet.
 *
 * There is no day to take an accent from at this point, so day A's accent stands in
 * as the brand colour, exactly as the old loading screen's wordmark did.
 */
@Composable
fun LoadingDial() {
    BoxWithConstraints(Modifier.fillMaxSize().background(Background), Alignment.Center) {
        val density = LocalDensity.current
        val diameterDp = min(maxWidth.value, maxHeight.value).dp
        val diameterPx = with(density) { diameterDp.toPx() }
        val type = remember(diameterPx, density) {
            dialTypography(DialGeometry.scale(diameterPx), density)
        }
        val accent = dayAccent(0)

        val transition = rememberInfiniteTransition(label = "loadingSweep")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(SWEEP_MILLIS, easing = LinearEasing)),
            label = "loadingSweepAngle",
        )

        Canvas(Modifier.fillMaxSize()) {
            val dayRing = DialGeometry.dayRing(diameterPx)
            drawRingArc(
                color = accent,
                arc = DialArc(DialGeometry.TOP_ANGLE_DEG + angle, SWEEP_DEG),
                radiusPx = dayRing.radiusPx,
                strokePx = dayRing.strokePx,
                cap = StrokeCap.Round,
            )
        }

        Box(contentAlignment = Alignment.Center) {
            Row {
                Text("strength", style = type.band, color = TextSecondary, maxLines = 1)
                Text(".", style = type.band, color = accent, maxLines = 1)
                Text("log", style = type.band, color = TextSecondary, maxLines = 1)
            }
        }
    }
}

/** One turn of the rim, at a pace that reads as "working", not "stuck". */
private const val SWEEP_MILLIS = 1_200

/** The sweeping segment's length — a segment, not an arc of progress (§4). */
private const val SWEEP_DEG = 40f
