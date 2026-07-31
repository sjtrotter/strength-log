package io.github.sjtrotter.strengthlog.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
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
 * accent anywhere, no filled shape, no motion, dim gray type. The bands are the
 * lit dial's own [CurvedBand], so a label sits on the same arc awake or asleep;
 * only the centre numeral stays straight, because it lives in the disc's zone.
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
                // The lit dial's outer ring carries the cycle in segments and type;
                // ambient keeps v2's hairline arc in its place. Segments, labels and
                // accents are pixels burned for something the lifter can wake the
                // watch to read (v3 §1).
                val outer = DialGeometry.ambientRing(diameterPx)
                drawRingArc(
                    color = AmbientDim,
                    arc = DialGeometry.proportionArc(state.dayProgress),
                    radiusPx = outer.radiusPx,
                    strokePx = outer.strokePx,
                    cap = StrokeCap.Butt,
                )
            }
            // The clock nests where the lit dial nests it — on the disc's rim, as
            // an outline arc (v2 §3). Ambient draws no disc, but moving the ring
            // would make the same countdown mean a different radius asleep.
            state.restFraction?.let { fraction ->
                val clock = DialGeometry.clockRing(diameterPx)
                drawRingArc(
                    color = AmbientDim,
                    arc = DialGeometry.proportionArc(fraction),
                    radiusPx = clock.radiusPx,
                    strokePx = clock.strokePx,
                    cap = StrokeCap.Butt,
                )
            }
        }

        CurvedBand(
            text = state.topText,
            style = type.curved(DialTextRole.BAND, AmbientDim),
            pole = BandPole.TOP,
            diameterPx = diameterPx,
        )
        Text(
            text = state.centerText,
            style = type.numeral,
            color = AmbientClock,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                // The centre numeral gets the disc's zone, lit or asleep.
                .widthIn(max = with(density) { DialGeometry.px(DialGeometry.DISC_DIAMETER, diameterPx).toDp() }),
        )
        state.bottomText?.let { bottom ->
            CurvedBand(
                text = bottom,
                style = type.curved(DialTextRole.BAND, AmbientDim),
                pole = BandPole.BOTTOM,
                diameterPx = diameterPx,
            )
        }
    }
}
