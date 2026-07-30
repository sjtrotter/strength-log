package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.Border
import io.github.sjtrotter.strengthlog.wear.theme.DialTypography
import io.github.sjtrotter.strengthlog.wear.theme.Done
import io.github.sjtrotter.strengthlog.wear.theme.Surface
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary
import io.github.sjtrotter.strengthlog.wear.theme.TextTertiary
import io.github.sjtrotter.strengthlog.wear.theme.accentBright
import io.github.sjtrotter.strengthlog.wear.theme.dayAccent
import io.github.sjtrotter.strengthlog.wear.theme.dialTypography
import io.github.sjtrotter.strengthlog.wear.theme.onDayAccent
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * The dial: three concentric rings, two label bands and one centre disc, and with
 * them every screen of the workout (brief §9). There is no navigation and there
 * are no lists — state changes re-render this in place.
 *
 * The rings nest by timescale and never transform into one another: the day ring
 * moves over an hour, the exercise ring over minutes, and the clock ring — on the
 * disc's own rim, drawn only while a clock runs — over seconds (v2 §3).
 *
 * Layout is layout only: what to say lives in [DialUiState], what a tap means
 * lives in [DialUiState.tap], and every position here is derived from the
 * measured face (§2) rather than tuned by padding. The brief's sketch spells the
 * ring/band/disc arguments out one by one; they are the fields of [DialUiState],
 * which keeps them describable — and testable — as one value.
 */
@Composable
fun Dial(
    state: DialUiState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onHoldComplete: (Int) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val diameterDp = min(maxWidth.value, maxHeight.value).dp
        val diameterPx = with(density) { diameterDp.toPx() }
        val scale = DialGeometry.scale(diameterPx)
        val type = remember(scale, density) { dialTypography(scale, density) }
        val accent = dayAccent(state.accentIndex)

        val motion = rememberDialMotion(state)

        DialRings(state, motion, accent, diameterPx)

        Band(
            content = state.topBand,
            type = type,
            accentIndex = state.accentIndex,
            maxWidth = bandMaxWidthDp(DialGeometry.TOP_BAND_INSET, diameterPx, density),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = with(density) { DialGeometry.px(DialGeometry.TOP_BAND_INSET, diameterPx).toDp() }),
        )
        Disc(
            state = state,
            type = type,
            diameterPx = diameterPx,
            scaleFactor = motion.discScale.value,
            accent = accent,
            onTap = onTap,
            onHoldComplete = onHoldComplete,
            modifier = Modifier.align(Alignment.Center),
        )
        Band(
            content = state.bottomBand,
            type = type,
            accentIndex = state.accentIndex,
            maxWidth = bandMaxWidthDp(DialGeometry.BOTTOM_BAND_INSET, diameterPx, density),
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

/**
 * A band row's width limit: the chord at the row's outer edge, which is exactly
 * [bandInset] from the pole it hangs off. Text ellipsizes inside it, so a long
 * name is cut rather than clipped by the bezel (v2 §2).
 */
internal fun bandMaxWidthDp(bandInset: Float, diameterPx: Float, density: Density): Dp = with(density) {
    DialGeometry.bandMaxWidthPx(diameterPx, DialGeometry.px(bandInset, diameterPx)).toDp()
}

/**
 * Every animation the brief allows (§8) and nothing else: no slide transitions,
 * no cross-fades between screens, no spring overshoot. They are driven by state
 * *transitions* rather than by the tap handlers, so a restore after process
 * death re-renders the current state without replaying its entrance.
 */
private class DialMotion(
    val discScale: Animatable<Float, *>,
    val newestDoneSweep: Animatable<Float, *>,
    val bloom: Animatable<Float, *>,
    /** 0..1 as the clock ring sweeps in; it is the ring's *presence*, not its value. */
    val clockSweep: Float,
    val innerScale: Float,
    val dayProgress: Float,
    val rounds: List<RoundState>,
)

@Composable
private fun rememberDialMotion(state: DialUiState): DialMotion {
    val discScale = remember { Animatable(1f) }
    val newestDoneSweep = remember { Animatable(1f) }
    val bloom = remember { Animatable(0f) }
    val doneCount = state.rounds.count { it == RoundState.DONE }
    // Plain holders, not snapshot state: these are read only inside effects and
    // while drawing, and a snapshot write during composition would invalidate the
    // very composition that wrote it.
    val previousScreen = remember { Holder(state.screen) }
    val previousDoneCount = remember { Holder(doneCount) }

    // START hollows the disc out with a 140ms pulse; the tick collapses it toward
    // the ring as the round's segment snaps green (220ms).
    LaunchedEffect(state.screen) {
        val from = previousScreen.value
        previousScreen.value = state.screen
        if (from == state.screen) return@LaunchedEffect
        val lifting = state.screen == DialScreen.LIFTING || state.screen == DialScreen.TIMED_HOLD
        val wasLifting = from == DialScreen.LIFTING || from == DialScreen.TIMED_HOLD
        when {
            lifting -> {
                discScale.animateTo(0.94f, tween(70, easing = LinearEasing))
                discScale.animateTo(1f, tween(70, easing = LinearEasing))
            }
            wasLifting -> {
                discScale.animateTo(0.35f, tween(110, easing = LinearOutSlowInEasing))
                discScale.animateTo(1f, tween(110, easing = LinearOutSlowInEasing))
            }
        }
    }

    LaunchedEffect(doneCount) {
        val grew = doneCount > previousDoneCount.value
        previousDoneCount.value = doneCount
        if (!grew) return@LaunchedEffect
        newestDoneSweep.snapTo(0f)
        newestDoneSweep.animateTo(1f, tween(220, easing = LinearOutSlowInEasing))
    }

    LaunchedEffect(state.bloom) {
        if (!state.bloom) return@LaunchedEffect
        bloom.snapTo(1f)
        bloom.animateTo(0f, tween(400, easing = LinearEasing))
    }

    // The inner ring keeps its last shape while it retracts on day-done, so the
    // segments have something to retract *from*.
    val lastRounds = remember { Holder(state.rounds) }
    if (state.rounds.isNotEmpty()) lastRounds.value = state.rounds

    return DialMotion(
        discScale = discScale,
        newestDoneSweep = newestDoneSweep,
        bloom = bloom,
        clockSweep = animateFloatAsState(
            targetValue = if (state.arc != null) 1f else 0f,
            animationSpec = tween(180, easing = LinearEasing),
            label = "clockSweep",
        ).value,
        innerScale = animateFloatAsState(
            targetValue = if (state.rounds.isEmpty()) 0f else 1f,
            animationSpec = tween(300, easing = LinearOutSlowInEasing),
            label = "innerScale",
        ).value,
        dayProgress = animateFloatAsState(
            targetValue = state.dayProgress,
            animationSpec = tween(220, easing = LinearOutSlowInEasing),
            label = "dayProgress",
        ).value,
        rounds = lastRounds.value,
    )
}

@Composable
private fun DialRings(state: DialUiState, motion: DialMotion, accent: Color, diameterPx: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val dayRing = DialGeometry.dayRing(diameterPx)
        drawRingArc(Border, DialGeometry.proportionArc(1f), dayRing.radiusPx, dayRing.strokePx, StrokeCap.Butt)
        if (motion.dayProgress > 0f) {
            drawRingArc(
                color = Done,
                arc = DialGeometry.proportionArc(motion.dayProgress),
                radiusPx = dayRing.radiusPx,
                strokePx = dayRing.strokePx,
                cap = StrokeCap.Round,
            )
        }

        if (motion.innerScale > 0f && motion.rounds.isNotEmpty()) {
            drawExerciseRing(motion, accent, diameterPx)
        }

        val clockFraction = state.arc
        if (clockFraction != null && motion.clockSweep > 0f) {
            val clock = DialGeometry.clockRing(diameterPx)
            drawRingArc(
                // A peek dims the whole centre as one object, clock included (v2 §3).
                color = accent.copy(alpha = if (state.disc.style == DiscStyle.DIMMED) DIMMED_ALPHA else 1f),
                arc = DialGeometry.proportionArc(clockFraction * motion.clockSweep),
                radiusPx = clock.radiusPx,
                strokePx = clock.strokePx,
                cap = StrokeCap.Butt,
            )
        }

        if (motion.bloom.value > 0f) {
            val bloomWidth = DialGeometry.px(DialGeometry.BLOOM_WIDTH, diameterPx)
            drawCircle(
                color = accent.copy(alpha = 0.5f * motion.bloom.value),
                radius = DialGeometry.discRadiusPx(diameterPx) + bloomWidth / 2f,
                style = Stroke(width = bloomWidth),
            )
        }
    }
}

/**
 * The rounds, always as segments. A running clock never reshapes this ring — it
 * has its own on the disc rim (v2 §3) — so the count stays readable through a
 * rest, which is exactly when the lifter wants to know what's left. The only
 * motion here is the newest DONE segment sweeping green.
 */
private fun DrawScope.drawExerciseRing(motion: DialMotion, accent: Color, diameterPx: Float) {
    val ring = DialGeometry.exerciseRing(diameterPx)
    val newestDoneIndex = motion.rounds.indexOfLast { it == RoundState.DONE }

    DialGeometry.segments(motion.rounds.size).forEachIndexed { index, arc ->
        val swept = if (index == newestDoneIndex) {
            arc.copy(sweepAngleDeg = arc.sweepAngleDeg * motion.newestDoneSweep.value)
        } else {
            arc
        }
        if (swept.sweepAngleDeg <= 0f) return@forEachIndexed
        val color = roundColor(motion.rounds[index], accent)
        drawRingArc(
            color = color.copy(alpha = color.alpha * motion.innerScale),
            arc = swept,
            radiusPx = ring.radiusPx * motion.innerScale,
            strokePx = ring.strokePx,
            cap = StrokeCap.Round,
        )
    }
}

private fun roundColor(round: RoundState, accent: Color): Color = when (round) {
    RoundState.DONE -> Done
    RoundState.CURRENT -> accent
    RoundState.UPCOMING -> Border
    RoundState.PEEKED -> TextPrimary
}

/** One ring arc, centred on the face — shared with the ambient and loading dials. */
internal fun DrawScope.drawRingArc(
    color: Color,
    arc: DialArc,
    radiusPx: Float,
    strokePx: Float,
    cap: StrokeCap,
) {
    drawArc(
        color = color,
        startAngle = arc.startAngleDeg,
        sweepAngle = arc.sweepAngleDeg,
        useCenter = false,
        topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
        size = Size(radiusPx * 2f, radiusPx * 2f),
        style = Stroke(width = strokePx, cap = cap),
    )
}

/** The dial's one tap target: 204px across at the reference size, centred (§1). */
@Composable
private fun Disc(
    state: DialUiState,
    type: DialTypography,
    diameterPx: Float,
    scaleFactor: Float,
    accent: Color,
    onTap: () -> Unit,
    onHoldComplete: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val discDp = with(density) { DialGeometry.px(DialGeometry.DISC_DIAMETER, diameterPx).toDp() }
    val borderPx = DialGeometry.px(2f, diameterPx)
    val holdFill = remember { Animatable(0f) }
    // While the hold fills, the disc says what it is about to do and nothing else.
    val disc = state.hold?.takeIf { holdFill.value > 0f }?.disc ?: state.disc
    val fill = when (disc.style) {
        DiscStyle.FILLED -> accent
        DiscStyle.FILLED_GREEN -> Done
        DiscStyle.DASHED -> Background
        DiscStyle.DIMMED, DiscStyle.OUTLINED, DiscStyle.FLAT -> Surface
    }

    Box(
        modifier = modifier
            .size(discDp)
            .graphicsLayer {
                scaleX = scaleFactor
                scaleY = scaleFactor
                // Read-only browsing reads as one dimmed object, type included (§4).
                alpha = if (disc.style == DiscStyle.DIMMED) DIMMED_ALPHA else 1f
            }
            .clip(CircleShape)
            .background(fill, CircleShape)
            .drawBehind {
                when (disc.style) {
                    DiscStyle.OUTLINED -> drawCircle(
                        color = accent,
                        radius = size.minDimension / 2f - borderPx / 2f,
                        style = Stroke(width = borderPx),
                    )
                    DiscStyle.DASHED -> drawCircle(
                        color = Border,
                        radius = size.minDimension / 2f - borderPx / 2f,
                        style = Stroke(
                            width = borderPx,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(borderPx * 4f, borderPx * 4f),
                            ),
                        ),
                    )
                    else -> Unit
                }
                // The hold is a clock, so it is the clock ring — same radius, same
                // stroke as a rest or a hold, and it can never collide with one:
                // an undo is offered only on READY and REST_OVER, which carry no
                // arc (v2 §3).
                if (holdFill.value > 0f) {
                    val clock = DialGeometry.clockRing(diameterPx)
                    drawArc(
                        color = accent,
                        startAngle = DialGeometry.TOP_ANGLE_DEG,
                        sweepAngle = 360f * holdFill.value,
                        useCenter = false,
                        topLeft = Offset(center.x - clock.radiusPx, center.y - clock.radiusPx),
                        size = Size(clock.radiusPx * 2f, clock.radiusPx * 2f),
                        style = Stroke(width = clock.strokePx, cap = StrokeCap.Butt),
                    )
                }
            }
            .discGestures(state, holdFill, onTap, onHoldComplete)
            .padding(horizontal = discDp * DISC_TEXT_INSET),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            disc.lines.forEach { line -> DiscLineRow(line, disc.style, state.accentIndex, type) }
        }
    }
}

/**
 * The disc's two gestures in one handler, deliberately: a tap and a hold on the
 * same 204px target can't be split across two independent detectors without the
 * completed hold *also* arriving as a tap on release — which would undo a set and
 * immediately start the next one.
 *
 * So the press is followed through by hand: the fill runs while the finger is
 * down, completing it fires the undo there and then (the fill reaching the top is
 * the confirmation), and any release before that is the ordinary tap. A cancelled
 * press — finger dragged off the disc — is neither.
 */
@Composable
private fun Modifier.discGestures(
    state: DialUiState,
    holdFill: Animatable<Float, *>,
    onTap: () -> Unit,
    onHoldComplete: (Int) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    // The gesture is hand-rolled, so the button semantics `clickable` would have
    // given TalkBack are declared explicitly rather than lost.
    val accessibleTap = if (state.tap == DialTap.NONE) {
        Modifier
    } else {
        Modifier.semantics {
            role = Role.Button
            onClick { onTap(); true }
        }
    }
    return this.then(accessibleTap).pointerInput(state.tap, state.hold) {
        if (state.tap == DialTap.NONE && state.hold == null) return@pointerInput
        detectTapGestures(
            onPress = {
                val hold = state.hold
                var completed = false
                val filling = hold?.let {
                    scope.launch {
                        holdFill.snapTo(0f)
                        holdFill.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                        completed = true
                        onHoldComplete(it.roundIndex)
                    }
                }
                val released = tryAwaitRelease()
                // Releasing early cancels: the fill drops away at once and the
                // disc it was drawn over is back, untouched.
                filling?.cancel()
                if (holdFill.value != 0f) scope.launch { holdFill.snapTo(0f) }
                if (released && !completed && state.tap != DialTap.NONE) onTap()
            },
        )
    }
}

@Composable
private fun DiscLineRow(line: DiscLine, style: DiscStyle, accentIndex: Int, type: DialTypography) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        line.spans.forEach { span ->
            Text(
                text = span.text,
                style = type.style(span.role),
                color = discToneColor(span.tone, style, accentIndex),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** One line of caps, never two (§2), inside the chord it can reach (v2 §2). */
@Composable
private fun Band(
    content: BandContent?,
    type: DialTypography,
    accentIndex: Int,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    if (content == null) return
    Row(
        modifier = modifier.widthIn(max = maxWidth),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content.dotTone?.let { BandDot(bandToneColor(it, accentIndex)) }
        Text(
            text = content.text,
            style = type.style(content.role),
            color = bandToneColor(content.tone, accentIndex),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BandDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "bandDot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bandDotAlpha",
    )
    Box(Modifier.size(5.dp).background(color.copy(alpha = alpha), CircleShape))
}

private fun bandToneColor(tone: DialTone, accentIndex: Int): Color = when (tone) {
    DialTone.PRIMARY -> TextPrimary
    DialTone.SECONDARY -> TextSecondary
    DialTone.TERTIARY -> TextTertiary
    DialTone.ACCENT_BRIGHT -> accentBright(accentIndex)
    DialTone.SUCCESS -> Done
    DialTone.ON_DISC -> TextPrimary
}

/** [DialTone.ON_DISC] resolves against the disc's own fill — one description, six fills. */
private fun discToneColor(tone: DialTone, style: DiscStyle, accentIndex: Int): Color = when {
    tone != DialTone.ON_DISC -> bandToneColor(tone, accentIndex)
    style == DiscStyle.FILLED -> onDayAccent(accentIndex)
    style == DiscStyle.FILLED_GREEN -> Background
    else -> TextPrimary
}

/** A last-value box that isn't snapshot state — see [rememberDialMotion]. */
private class Holder<T>(var value: T)

/** Horizontal breathing room inside the disc, as a fraction of its diameter. */
private const val DISC_TEXT_INSET = 0.08f

/** The deliberate length of an undo (§6) — long enough that nothing about it is accidental. */
private const val HOLD_MILLIS = 700

/** Read-only browsing, at the brief's 62% (§4). */
private const val DIMMED_ALPHA = 0.62f
