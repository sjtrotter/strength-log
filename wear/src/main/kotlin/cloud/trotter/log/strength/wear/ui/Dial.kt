package cloud.trotter.log.strength.wear.ui

import android.text.format.DateFormat
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedAlignment
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.angularSizeDp
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.radialSize
import androidx.wear.compose.foundation.sizeIn
import androidx.wear.compose.material.Text
import cloud.trotter.log.strength.wear.theme.Background
import cloud.trotter.log.strength.wear.theme.Border
import cloud.trotter.log.strength.wear.theme.DialTypography
import cloud.trotter.log.strength.wear.theme.Done
import cloud.trotter.log.strength.wear.theme.Surface
import cloud.trotter.log.strength.wear.theme.TextPrimary
import cloud.trotter.log.strength.wear.theme.TextSecondary
import cloud.trotter.log.strength.wear.theme.TextTertiary
import cloud.trotter.log.strength.wear.theme.accentBright
import cloud.trotter.log.strength.wear.theme.dayAccent
import cloud.trotter.log.strength.wear.theme.dialTypography
import cloud.trotter.log.strength.wear.theme.onDayAccent
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The dial: three concentric rings, two label bands and one centre disc, and with
 * them every screen of the workout (brief §9). There are two faces and no lists —
 * state changes re-render this in place.
 *
 * The rings nest by timescale and never transform into one another: the cycle ring
 * moves over days, the exercise ring over minutes, and the clock ring — on the
 * disc's own rim, drawn only while a clock runs — over seconds (v2 §3, v3 §1).
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
    onHoldComplete: (UndoTarget) -> Unit = {},
    timePillText: String? = null,
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
        CycleLabels(state.cycle, type, diameterPx)

        Band(state.topBand, BandPole.TOP, type, state.accentIndex, diameterPx)
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
        Band(state.bottomBand, BandPole.BOTTOM, type, state.accentIndex, diameterPx)
        if (state.showsTimePill) {
            if (timePillText == null) {
                TimePill(type, diameterPx, Modifier.align(Alignment.Center))
            } else {
                TimePill(type, diameterPx, Modifier.align(Alignment.Center), timePillText)
            }
        }
    }
}

/** Interactive-only wall clock. [AmbientDial] is a separate tree and never composes this fill. */
@Composable
internal fun TimePill(
    type: DialTypography,
    diameterPx: Float,
    modifier: Modifier = Modifier,
    timeText: String = rememberMinuteTimeText(
        DateFormat.is24HourFormat(LocalContext.current),
    ),
) {
    val density = LocalDensity.current
    with(density) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .offset(y = DialGeometry.px(DialGeometry.TIME_PILL_CENTER_RADIUS, diameterPx).toDp())
                .height(DialGeometry.px(DialGeometry.TIME_PILL_HEIGHT, diameterPx).toDp())
                .widthIn(max = DialGeometry.timePillMaxWidthPx(diameterPx).toDp())
                .clip(RoundedCornerShape(percent = 50))
                .background(Surface)
                .padding(horizontal = DialGeometry.px(DialGeometry.TIME_PILL_HORIZONTAL_PADDING, diameterPx).toDp())
                .testTag(TIME_PILL_TEST_TAG),
        ) {
            Text(
                text = timeText,
                style = type.style(DialTextRole.BAND),
                color = TextSecondary,
                maxLines = 1,
                textAlign = TextAlign.Center,
                // Keep the clock as a plain, independent TalkBack node; it has no action.
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(timeText) },
            )
        }
    }
}

@Composable
private fun rememberMinuteTimeText(is24Hour: Boolean): String {
    var minuteTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextMinute(System.currentTimeMillis()))
            minuteTick++
        }
    }
    return remember(minuteTick, is24Hour) { timePillTimeText(LocalTime.now(), is24Hour) }
}

internal const val TIME_PILL_TEST_TAG = "time-pill"

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
        drawCycleRing(state.cycle, motion.dayProgress, diameterPx)

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
                // The bloom belongs to the disc it blooms from, so it wears the
                // disc's colour, not the day's (v3 §2).
                color = TextPrimary.copy(alpha = 0.5f * motion.bloom.value),
                radius = DialGeometry.discRadiusPx(diameterPx) + bloomWidth / 2f,
                style = Stroke(width = bloomWidth),
            )
        }
    }
}

/**
 * The program cycle: one segment per day in program order, today's in its own
 * accent at full strength and every other day's dimmed to a hint of itself (v3
 * §1). Today's progress rides the inner edge of today's segment, so "which day"
 * and "how far through it" are one glance at one place — and when the day is
 * finished the segment simply reads green, progress and all.
 */
private fun DrawScope.drawCycleRing(cycle: List<CycleSegment>, dayProgress: Float, diameterPx: Float) {
    if (cycle.isEmpty()) return
    val ring = DialGeometry.cycleRing(diameterPx)
    val segments = DialGeometry.segments(cycle.size)
    val done = dayProgress >= 1f

    segments.forEachIndexed { index, arc ->
        val segment = cycle[index]
        val color = when (segment.mark) {
            CycleMark.TODAY -> if (done) Done else dayAccent(segment.accentIndex)
            CycleMark.BROWSED -> TextPrimary
            CycleMark.OTHER -> dayAccent(segment.accentIndex).copy(alpha = CYCLE_DIM_ALPHA)
        }
        drawRingArc(color, arc, ring.radiusPx, ring.strokePx, StrokeCap.Butt)
    }

    if (done || dayProgress <= 0f) return
    val today = cycle.indexOfFirst { it.mark == CycleMark.TODAY }.takeIf { it >= 0 } ?: return
    val progress = DialGeometry.cycleProgressRing(diameterPx)
    drawRingArc(
        color = Done,
        arc = DialGeometry.progressWithin(segments[today], dayProgress),
        radiusPx = progress.radiusPx,
        strokePx = progress.strokePx,
        cap = StrokeCap.Butt,
    )
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

/**
 * The cycle's labels, each on its own segment's arc (v3 §1). Which form a segment
 * gets — "DAY C", "C", or nothing — is decided by measuring both against the sweep
 * that segment actually has, so a 7-day program on a 41mm watch drops to letters
 * (or to colour alone) without anyone tuning a breakpoint per day count.
 *
 * Labels render at CYCLE_LABEL, 9sp on the reference face — below the v2 12sp
 * floor by deliberate owner waiver, not an oversight (on-wrist verdict, issue
 * #152): the segment's colour is the identification, the word only names it,
 * and 18 reference px is what actually fits inside the ring's 22px stroke.
 */
@Composable
private fun CycleLabels(cycle: List<CycleSegment>, type: DialTypography, diameterPx: Float) {
    if (cycle.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val band = DialGeometry.cycleLabelBand(diameterPx)
    val labelStyle = type.style(DialTextRole.CYCLE_LABEL)
    val segments = DialGeometry.segments(cycle.size)

    val labels = remember(cycle, labelStyle, band.radiusPx) {
        fun sweepOf(text: String) = DialGeometry.bandSweepDeg(
            arcLengthPx = measurer.measure(text, labelStyle).size.width.toFloat(),
            radiusPx = band.radiusPx,
        )
        cycle.mapIndexed { index, segment ->
            val full = "day ${segment.dayLabel}".uppercase()
            when (
                DialGeometry.cycleLabelFit(
                    segmentSweepDeg = segments[index].sweepAngleDeg,
                    fullSweepDeg = sweepOf(full),
                    shortSweepDeg = sweepOf(segment.dayLabel),
                )
            ) {
                CycleLabelFit.FULL -> full
                CycleLabelFit.SHORT -> segment.dayLabel
                CycleLabelFit.NONE -> null
            }
        }
    }

    labels.forEachIndexed { index, text ->
        if (text == null) return@forEachIndexed
        CycleLabel(
            text = text,
            style = type.curved(DialTextRole.CYCLE_LABEL, cycleLabelColor(cycle[index])),
            anchorDeg = DialGeometry.midAngleDeg(segments[index]),
            band = band,
        )
    }
}

/** One day's label, laid along its own segment and turned upright at the bottom. */
@Composable
private fun CycleLabel(text: String, style: CurvedTextStyle, anchorDeg: Float, band: DialBand) {
    val density = LocalDensity.current
    with(density) {
        CurvedLayout(
            modifier = Modifier.fillMaxSize().padding(band.insetPx.toDp()),
            anchor = anchorDeg,
            angularDirection = if (DialGeometry.isBottomHalf(anchorDeg)) {
                CurvedDirection.Angular.CounterClockwise
            } else {
                CurvedDirection.Angular.Clockwise
            },
        ) {
            curvedRow(
                modifier = CurvedModifier.radialSize(band.thicknessPx.toDp()),
                radialAlignment = CurvedAlignment.Radial.Center,
            ) {
                basicCurvedText(text) { style }
            }
        }
    }
}

/** Today's label reads on its own accent; every other day's is a quiet caption —
 *  the colour is doing the talking there, and the word is only naming it. */
private fun cycleLabelColor(segment: CycleSegment): Color = when (segment.mark) {
    CycleMark.TODAY -> onDayAccent(segment.accentIndex)
    CycleMark.BROWSED -> Background
    CycleMark.OTHER -> TextTertiary
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
    onHoldComplete: (UndoTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val discDp = with(density) { DialGeometry.px(DialGeometry.DISC_DIAMETER, diameterPx).toDp() }
    val borderPx = DialGeometry.px(2f, diameterPx)
    val holdFill = remember { Animatable(0f) }
    // While the hold fills, the disc says what it is about to do and nothing else.
    val disc = state.hold?.takeIf { holdFill.value > 0f }?.disc ?: state.disc
    // The disc is the machine's controls, and controls don't borrow the day's
    // colour (v3 §2) — the rings and bands carry identity, this carries action.
    val fill = when (disc.style) {
        DiscStyle.FILLED -> TextPrimary
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
                        color = TextPrimary,
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
                // an undo is offered only on READY, REST_OVER and DAY_DONE, none of
                // which carries an arc (v2 §3). It keeps the day accent with the
                // clock ring: time passing is the day's, not the control's (v3 §2).
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
    onHoldComplete: (UndoTarget) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    // The gesture is hand-rolled, so its accessibility actions are declared here.
    // Both actions must use the same callbacks as touch; they are alternate input
    // paths, not separate behavior.
    val accessibility = Modifier.semantics(mergeDescendants = true) {
        state.tap.accessibilityClickLabel?.let { label ->
            role = Role.Button
            onClick(label = label) { onTap(); true }
        }
        state.hold?.let { hold ->
            role = Role.Button
            onLongClick(label = "undo last set") {
                onHoldComplete(hold.target)
                true
            }
        }
    }
    return this.then(accessibility).pointerInput(state.tap, state.hold) {
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
                        onHoldComplete(it.target)
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

/**
 * The leftward half of the dial's two horizontal gestures (v3 §3). Rightward is
 * the platform's own dismiss and is never touched here: this detector watches a
 * press it may not have started (the disc consumes the down for its tap), and
 * bails the moment the finger travels right, leaving those events unconsumed for
 * the swipe-to-dismiss box above.
 *
 * Consuming the leftward drag is what cancels the disc's pending tap, which is the
 * behaviour we want — a swipe that starts on the disc is a swipe, not a tap.
 */
@Composable
internal fun Modifier.swipeLeft(enabled: Boolean, onSwipeLeft: () -> Unit): Modifier {
    // The handler is read through a holder so the detector is never restarted
    // mid-gesture just because the screen it reads recomposed.
    val latest by rememberUpdatedState(onSwipeLeft)
    if (!enabled) return this
    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var horizontal = 0f
            var vertical = 0f
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                if (!change.pressed) break
                horizontal += change.positionChange().x
                vertical += abs(change.positionChange().y)
                if (horizontal > slop) break
                if (-horizontal > slop && -horizontal > vertical) {
                    change.consume()
                    latest()
                    break
                }
            }
        }
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

/** One line of caps, never two (§2), on the arc its annulus describes. */
@Composable
private fun Band(
    content: BandContent?,
    pole: BandPole,
    type: DialTypography,
    accentIndex: Int,
    diameterPx: Float,
) {
    if (content == null) return
    CurvedBand(
        text = content.text,
        style = type.curved(content.role, bandToneColor(content.tone, accentIndex)),
        pole = pole,
        diameterPx = diameterPx,
        dot = content.dotTone?.let { tone ->
            { BandDot(bandToneColor(tone, accentIndex), it) }
        },
    )
}

/**
 * A label band as an arc through its annulus, concentric with the rings (§1).
 *
 * The top band reads clockwise over 12 o'clock and the bottom one
 * counter-clockwise under 6, which is what stands its glyphs upright rather than
 * on their heads. Shared with [AmbientDial] so a band doesn't move when the
 * screen dims — same radius, same sweep, different colours.
 */
@Composable
internal fun CurvedBand(
    text: String,
    style: CurvedTextStyle,
    pole: BandPole,
    diameterPx: Float,
    dot: (@Composable (Dp) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val band = DialGeometry.bandArc(diameterPx)
    val dotSlotPx = DialGeometry.px(DialGeometry.BAND_DOT_SLOT, diameterPx)
    val dotSlotDeg = if (dot == null) 0f else DialGeometry.bandSweepDeg(dotSlotPx, band.radiusPx)
    with(density) {
        CurvedLayout(
            modifier = Modifier.fillMaxSize().padding(band.insetPx.toDp()),
            anchor = pole.anchorDeg,
            angularDirection = pole.direction,
        ) {
            curvedRow(
                modifier = CurvedModifier.radialSize(band.thicknessPx.toDp()),
                radialAlignment = CurvedAlignment.Radial.Center,
            ) {
                if (dot != null) {
                    curvedComposable(modifier = CurvedModifier.angularSizeDp(dotSlotPx.toDp())) {
                        dot(DialGeometry.px(DialGeometry.BAND_DOT_DIAMETER, diameterPx).toDp())
                    }
                }
                // Overflow is measured against the sweep the row hands down, so the
                // cap has to sit on the text itself — the ellipsis is what keeps a
                // long name from running down the side of the face.
                basicCurvedText(
                    text = text,
                    modifier = CurvedModifier.sizeIn(
                        maxSweepDegrees = DialGeometry.BAND_MAX_SWEEP_DEG - dotSlotDeg,
                    ),
                    overflow = TextOverflow.Ellipsis,
                ) { style }
            }
        }
    }
}

/** Which pole a band hangs off, and which way it has to run to read upright. */
internal enum class BandPole(val anchorDeg: Float) {
    TOP(270f),
    BOTTOM(90f),
    ;

    val direction: CurvedDirection.Angular
        get() = if (this == TOP) CurvedDirection.Angular.Clockwise else CurvedDirection.Angular.CounterClockwise
}

@Composable
private fun BandDot(color: Color, size: Dp) {
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
    Box(Modifier.size(size).background(color.copy(alpha = alpha), CircleShape))
}

private fun bandToneColor(tone: DialTone, accentIndex: Int): Color = when (tone) {
    DialTone.PRIMARY -> TextPrimary
    DialTone.SECONDARY -> TextSecondary
    DialTone.TERTIARY -> TextTertiary
    DialTone.ACCENT_BRIGHT -> accentBright(accentIndex)
    DialTone.SUCCESS -> Done
    DialTone.ON_DISC -> TextPrimary
}

/**
 * [DialTone.ON_DISC] resolves against the disc's own fill — one description, six
 * fills. Since the fills stopped following the day (v3 §2) this resolves without
 * the accent: what reads on a control is decided by the control.
 */
private fun discToneColor(tone: DialTone, style: DiscStyle, accentIndex: Int): Color = when {
    tone != DialTone.ON_DISC -> bandToneColor(tone, accentIndex)
    style == DiscStyle.FILLED || style == DiscStyle.FILLED_GREEN -> Background
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

/** A day that isn't today, on the cycle ring: its own colour, said quietly (v3 §1). */
private const val CYCLE_DIM_ALPHA = 0.3f
