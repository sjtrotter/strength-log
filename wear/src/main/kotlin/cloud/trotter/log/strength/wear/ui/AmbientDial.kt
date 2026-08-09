package cloud.trotter.log.strength.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.wear.theme.AmbientBackground
import cloud.trotter.log.strength.wear.theme.AmbientClock
import cloud.trotter.log.strength.wear.theme.AmbientDim
import cloud.trotter.log.strength.wear.theme.dialTypography
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.min

/**
 * The dial in ambient mode (brief §7): the same geometry, and everything the
 * screen can do to stay off an OLED — true black behind, outline arcs only, no
 * accent anywhere, no filled shape, no motion, dim gray type (pure white on
 * low-bit devices, which get no grays). The bands are the
 * lit dial's own [CurvedBand], so a label sits on the same arc awake or asleep;
 * only the centre numeral stays straight, because it lives in the disc's zone.
 *
 * It repaints only when [ambientTick] changes, which
 * [cloud.trotter.log.strength.wear.MainActivity] bumps from the system's own
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
    burnInProtectionRequired: Boolean = false,
    deviceHasLowBitAmbient: Boolean = false,
) {
    val state = remember(ambientTick, snapshot, rest) {
        val remaining = rest?.let {
            RestTimer.remainingSeconds(it.deadlineMillis, SystemClock.elapsedRealtime())
        }
        ambientDialState(
            snapshot = snapshot,
            timeText = LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            restRemainingSeconds = remaining,
        )
    }

    val shift = if (burnInProtectionRequired) ambientPixelOffset(ambientTick) else AmbientPixelOffset.ZERO
    val palette = ambientPalette(deviceHasLowBitAmbient)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .offset { IntOffset(shift.x, shift.y) },
    ) {
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
                    color = palette.secondary,
                    arc = DialGeometry.proportionArc(state.dayProgress),
                    radiusPx = outer.radiusPx,
                    strokePx = outer.strokePx,
                    cap = StrokeCap.Butt,
                )
            }
        }

        CurvedBand(
            text = state.topText,
            style = type.curved(DialTextRole.BAND, palette.secondary),
            pole = BandPole.TOP,
            diameterPx = diameterPx,
        )
        Text(
            text = state.centerText,
            style = type.numeral,
            color = palette.primary,
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
                style = type.curved(DialTextRole.BAND, palette.secondary),
                pole = BandPole.BOTTOM,
                diameterPx = diameterPx,
            )
        }
    }
}

/**
 * Ambient with no snapshot yet: the wall clock alone on the static black
 * field, palette- and burn-in-aware. The lit LoadingDial animates its sweep
 * every 1.2s — motion and accent that ambient must not show (issue #161's
 * relaunch-into-ambient path).
 */
@Composable
fun AmbientLoadingDial(
    ambientTick: Int,
    burnInProtectionRequired: Boolean = false,
    deviceHasLowBitAmbient: Boolean = false,
) {
    val timeText = remember(ambientTick) {
        LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    val shift = if (burnInProtectionRequired) ambientPixelOffset(ambientTick) else AmbientPixelOffset.ZERO
    val palette = ambientPalette(deviceHasLowBitAmbient)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .offset { IntOffset(shift.x, shift.y) },
    ) {
        val density = LocalDensity.current
        val diameterDp = min(maxWidth.value, maxHeight.value).dp
        val diameterPx = with(density) { diameterDp.toPx() }
        val type = remember(diameterPx, density) {
            dialTypography(DialGeometry.scale(diameterPx), density)
        }
        Text(
            text = timeText,
            style = type.numeral,
            color = palette.primary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = with(density) { DialGeometry.px(DialGeometry.DISC_DIAMETER, diameterPx).toDp() }),
        )
    }
}

internal data class AmbientPixelOffset(val x: Int, val y: Int) {
    companion object {
        val ZERO = AmbientPixelOffset(0, 0)
    }
}

/** A two-pixel envelope relocates lit pixels without consuming the dial's bezel margin. */
internal fun ambientPixelOffset(tick: Int): AmbientPixelOffset {
    val offsets = listOf(
        AmbientPixelOffset(-2, -2), AmbientPixelOffset(0, -2), AmbientPixelOffset(2, -2),
        AmbientPixelOffset(2, 0), AmbientPixelOffset(2, 2), AmbientPixelOffset(0, 2),
        AmbientPixelOffset(-2, 2), AmbientPixelOffset(-2, 0), AmbientPixelOffset(0, 0),
    )
    return offsets[Math.floorMod(tick, offsets.size)]
}

internal data class AmbientPalette(
    val background: Color,
    val primary: Color,
    val secondary: Color,
)

/** Low-bit ambient uses only fully off and fully on pixels. */
internal fun ambientPalette(lowBit: Boolean): AmbientPalette = if (lowBit) {
    AmbientPalette(AmbientBackground, Color.White, Color.White)
} else {
    AmbientPalette(AmbientBackground, AmbientClock, AmbientDim)
}
