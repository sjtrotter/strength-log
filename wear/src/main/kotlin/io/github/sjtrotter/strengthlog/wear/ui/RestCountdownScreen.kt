package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import android.os.SystemClock
import io.github.sjtrotter.strengthlog.domain.units.SecondsStepper
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.Border
import io.github.sjtrotter.strengthlog.wear.theme.Condensed
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.TextTertiary
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent
import kotlinx.coroutines.delay

/** How often the interactive countdown repaints — smooth enough for the draining
 *  arc without a per-frame animation (the real signal is the buzz, not motion). */
private const val REFRESH_MILLIS = 200L

/**
 * The rest countdown that takes over the stream face while resting (redesign
 * §2.3 / R5): a draining accent arc, the big condensed `m:ss` remaining numeral
 * (via [SecondsStepper.format] — SSOT, no second formatter), a "NEXT · …" line
 * naming the upcoming round, and tap-anywhere-to-skip.
 *
 * Deadline-anchored and stateless as to duration: [deadlineMillis] is the captured
 * `elapsedRealtime()` instant (held in `rememberSaveable` by the caller, so it
 * survives recomposition, ambient, rotation, and process death). This composable
 * only reads the current clock against it. The **buzz** is not here — it is owned
 * by [RestTimerController] above the ambient swap so it fires even if this screen
 * is disposed into ambient; [onComplete] just advances the UI to the next round,
 * guarded by [RestTimer.shouldFire] so it runs exactly once.
 */
@Composable
fun RestCountdownScreen(
    deadlineMillis: Long,
    totalSeconds: Int,
    accentIndex: Int,
    nextLabel: String,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
) {
    val accent = dayAccent(accentIndex)
    var nowMillis by remember(deadlineMillis) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var completed by remember(deadlineMillis) { mutableStateOf(false) }

    LaunchedEffect(deadlineMillis) {
        while (true) {
            nowMillis = SystemClock.elapsedRealtime()
            if (RestTimer.shouldFire(deadlineMillis, nowMillis, completed)) {
                completed = true
                onComplete()
                return@LaunchedEffect
            }
            delay(REFRESH_MILLIS)
        }
    }

    val remainingSeconds = RestTimer.remainingSeconds(deadlineMillis, nowMillis)
    val fraction = RestTimer.remainingFraction(deadlineMillis, nowMillis, totalSeconds)

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .clickable(onClick = onSkip),
        contentAlignment = Alignment.Center,
    ) {
        DrainingArc(fraction = fraction, accent = accent, modifier = Modifier.fillMaxSize().padding(6.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "rest".uppercase(),
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
            Text(
                text = SecondsStepper.format(remainingSeconds),
                color = TextPrimary,
                fontFamily = Condensed,
                fontWeight = FontWeight.Bold,
                fontSize = 54.sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (nextLabel.isNotBlank()) {
                Text(
                    text = "next · $nextLabel".uppercase(),
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                text = "tap to skip".uppercase(),
                color = TextTertiary,
                fontWeight = FontWeight.Medium,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A single accent arc that shrinks from a full ring to nothing as the rest
 *  drains, over a dim full-circle track — the reused §8.5 progress-ring language,
 *  one continuous sweep rather than per-round segments. */
@Composable
private fun DrainingArc(fraction: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidthPx = 5.dp.toPx()
        val diameter = kotlin.math.min(size.width, size.height) - strokeWidthPx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

        drawArc(
            color = Border,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        if (fraction > 0f) {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
