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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * The dial: two concentric rings, two label bands and one centre disc, and with
 * them every screen of the workout (brief §9). There is no navigation and there
 * are no lists — state changes re-render this in place.
 *
 * Layout is layout only: what to say lives in [DialUiState], what a tap means
 * lives in [DialUiState.tap], and every position here is derived from the
 * measured face (§2) rather than tuned by padding. The brief's sketch spells the
 * ring/band/disc arguments out one by one; they are the fields of [DialUiState],
 * which keeps them describable — and testable — as one value.
 */
@Composable
fun Dial(state: DialUiState, onTap: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val diameterDp = min(maxWidth.value, maxHeight.value).dp
        val diameterPx = with(density) { diameterDp.toPx() }
        val scale = DialGeometry.scale(diameterPx)
        val type = remember(scale, density) { dialTypography(scale, density) }
        val accent = dayAccent(state.accentIndex)

        val motion = rememberDialMotion(state)

        DialRings(state, motion, accent, diameterPx)

        val sideInset = with(density) { DialGeometry.px(DialGeometry.BAND_SIDE_INSET, diameterPx).toDp() }
        Box(Modifier.fillMaxSize().padding(horizontal = sideInset)) {
            Band(
                content = state.topBand,
                type = type,
                accentIndex = state.accentIndex,
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
                modifier = Modifier.align(Alignment.Center),
            )
            Band(
                content = state.bottomBand,
                type = type,
                accentIndex = state.accentIndex,
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
    val melt: Float,
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
        melt = animateFloatAsState(
            targetValue = if (state.arc != null) 1f else 0f,
            animationSpec = tween(180, easing = LinearEasing),
            label = "melt",
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
            drawExerciseRing(state, motion, accent, diameterPx)
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
 * One code path draws both inner-ring forms. As [DialMotion.melt] runs, the gaps
 * close and the segment colours converge on the accent, so the segmented ring
 * *becomes* the continuous arc — the shape change is the signal that a clock is
 * running (§5.4), and there is no second shape to cross-fade with.
 */
private fun DrawScope.drawExerciseRing(
    state: DialUiState,
    motion: DialMotion,
    accent: Color,
    diameterPx: Float,
) {
    val ring = DialGeometry.exerciseRing(diameterPx)
    val melt = motion.melt
    val fraction = state.arc ?: 1f
    val newestDoneIndex = motion.rounds.indexOfLast { it == RoundState.DONE }
    val arcs = DialGeometry.segments(motion.rounds.size, DialGeometry.SEGMENT_GAP_DEG * (1f - melt))
    val cap = if (melt > 0.5f) StrokeCap.Butt else StrokeCap.Round

    arcs.forEachIndexed { index, arc ->
        val trimmed = if (melt > 0f) DialGeometry.trimToFraction(arc, fraction) else arc
        val swept = if (index == newestDoneIndex) {
            trimmed.copy(sweepAngleDeg = trimmed.sweepAngleDeg * motion.newestDoneSweep.value)
        } else {
            trimmed
        }
        if (swept.sweepAngleDeg <= 0f) return@forEachIndexed
        val color = lerp(roundColor(motion.rounds[index], accent), accent, melt)
        drawRingArc(
            color = color.copy(alpha = color.alpha * motion.innerScale),
            arc = swept,
            radiusPx = ring.radiusPx * motion.innerScale,
            strokePx = ring.strokePx,
            cap = cap,
        )
    }
}

private fun roundColor(round: RoundState, accent: Color): Color = when (round) {
    RoundState.DONE -> Done
    RoundState.CURRENT -> accent
    RoundState.UPCOMING -> Border
    RoundState.PEEKED -> TextPrimary
}

private fun DrawScope.drawRingArc(
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

/** The dial's one tap target: 176px across at the reference size, centred (§1). */
@Composable
private fun Disc(
    state: DialUiState,
    type: DialTypography,
    diameterPx: Float,
    scaleFactor: Float,
    accent: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val discDp = with(density) { DialGeometry.px(DialGeometry.DISC_DIAMETER, diameterPx).toDp() }
    val borderPx = DialGeometry.px(2f, diameterPx)
    val fill = when (state.disc.style) {
        DiscStyle.FILLED -> accent
        DiscStyle.FILLED_GREEN -> Done
        DiscStyle.DIMMED -> Surface.copy(alpha = 0.62f)
        DiscStyle.DASHED -> Background
        DiscStyle.OUTLINED, DiscStyle.FLAT -> Surface
    }

    Box(
        modifier = modifier
            .size(discDp)
            .graphicsLayer { scaleX = scaleFactor; scaleY = scaleFactor }
            .clip(CircleShape)
            .background(fill, CircleShape)
            .drawBehind {
                when (state.disc.style) {
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
            }
            .clickable(enabled = state.tap != DialTap.NONE, onClick = onTap)
            .padding(horizontal = discDp * DISC_TEXT_INSET),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            state.disc.lines.forEach { line -> DiscLineRow(line, state, type) }
        }
    }
}

@Composable
private fun DiscLineRow(line: DiscLine, state: DialUiState, type: DialTypography) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        line.spans.forEach { span ->
            Text(
                text = span.text,
                style = type.style(span.role),
                color = discToneColor(span.tone, state),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** One line of caps, never two (§2). */
@Composable
private fun Band(
    content: BandContent?,
    type: DialTypography,
    accentIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (content == null) return
    Row(
        modifier = modifier,
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
private fun discToneColor(tone: DialTone, state: DialUiState): Color = when {
    tone != DialTone.ON_DISC -> bandToneColor(tone, state.accentIndex)
    state.disc.style == DiscStyle.FILLED -> onDayAccent(state.accentIndex)
    state.disc.style == DiscStyle.FILLED_GREEN -> Background
    else -> TextPrimary
}

/** A last-value box that isn't snapshot state — see [rememberDialMotion]. */
private class Holder<T>(var value: T)

/** Horizontal breathing room inside the disc, as a fraction of its diameter. */
private const val DISC_TEXT_INSET = 0.08f
