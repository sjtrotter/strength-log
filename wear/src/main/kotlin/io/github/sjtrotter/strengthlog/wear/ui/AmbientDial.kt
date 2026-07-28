package io.github.sjtrotter.strengthlog.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.wear.theme.AmbientBackground
import io.github.sjtrotter.strengthlog.wear.theme.AmbientClock
import io.github.sjtrotter.strengthlog.wear.theme.AmbientDim
import io.github.sjtrotter.strengthlog.wear.theme.dialTypography
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.min

/**
 * The dial in ambient mode (brief §7): the same geometry, and everything the
 * screen can do to stay off an OLED — true black behind, outline arcs only, no
 * accent anywhere, no filled shape, no motion, dim gray type.
 *
 * It repaints only when [ambientTick] changes, which
 * [io.github.sjtrotter.strengthlog.wear.MainActivity] bumps from the system's own
 * once-a-minute `onUpdateAmbient()` — a free-running coroutine wouldn't fire
 * reliably with the CPU suspended anyway. The one thing that *must* be punctual in
 * ambient, the buzz at the end of a rest, isn't this composable's job at all:
 * [RestTimerController] holds a wake lock for it, above the ambient swap.
 */
@Composable
fun AmbientDial(
    snapshot: WatchSnapshot,
    ambientTick: Int,
    rest: RestTimerController.ActiveRest? = null,
) {
    val state = remember(ambientTick, snapshot, rest) {
        val remaining = rest?.let {
            RestTimer.remainingSeconds(it.deadlineMillis, SystemClock.elapsedRealtime())
        }
        ambientDialState(
            snapshot = snapshot,
            timeText = LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            restRemainingSeconds = remaining,
            restTotalSeconds = rest?.totalSeconds ?: 0,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(AmbientBackground)) {
        val density = LocalDensity.current
        val diameterDp = min(maxWidth.value, maxHeight.value).dp
        val diameterPx = with(density) { diameterDp.toPx() }
        val type = remember(diameterPx, density) {
            dialTypography(DialGeometry.scale(diameterPx), density)
        }

        Canvas(Modifier.fillMaxSize()) {
            // Progress only — the gray track a lit dial draws would be pixels burned
            // for something the lifter already knows (the ring is a whole circle).
            if (state.dayProgress > 0f) {
                val dayRing = DialGeometry.dayRing(diameterPx)
                drawRingArc(
                    color = AmbientDim,
                    arc = DialGeometry.proportionArc(state.dayProgress),
                    radiusPx = dayRing.radiusPx,
                    strokePx = dayRing.strokePx,
                    cap = StrokeCap.Butt,
                )
            }
            state.restFraction?.let { fraction ->
                val exerciseRing = DialGeometry.exerciseRing(diameterPx)
                drawRingArc(
                    color = AmbientDim,
                    arc = DialGeometry.proportionArc(fraction),
                    radiusPx = exerciseRing.radiusPx,
                    strokePx = exerciseRing.strokePx,
                    cap = StrokeCap.Butt,
                )
            }
        }

        val sideInset = with(density) { DialGeometry.px(DialGeometry.BAND_SIDE_INSET, diameterPx).toDp() }
        Box(Modifier.fillMaxSize().padding(horizontal = sideInset)) {
            Text(
                text = state.topText,
                style = type.band,
                color = AmbientDim,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(density) { DialGeometry.px(DialGeometry.TOP_BAND_INSET, diameterPx).toDp() }),
            )
            Text(
                text = state.centerText,
                style = type.numeral,
                color = AmbientClock,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
            state.bottomText?.let { bottom ->
                Text(
                    text = bottom,
                    style = type.band,
                    color = AmbientDim,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = with(density) {
                                DialGeometry.px(DialGeometry.BOTTOM_BAND_INSET, diameterPx).toDp()
                            },
                        ),
                )
            }
        }
    }
}
