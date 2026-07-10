package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.StepperGlyph
import io.github.sjtrotter.strengthlog.ui.theme.StepperRepsValue
import io.github.sjtrotter.strengthlog.ui.theme.StepperValue
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.Surface3
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private val CapsuleShape = RoundedCornerShape(8.dp)

/** Long-press auto-repeat timing (A7): a deliberate pause before the first
 *  repeat so a normal tap never double-fires, then a steady interval that
 *  reads as "held down", not stuttery. */
private const val LONG_PRESS_INITIAL_DELAY_MS = 400L
private const val LONG_PRESS_REPEAT_INTERVAL_MS = 90L

/**
 * The ± stepper capsule used for both set weight and set reps (design-pass
 * restyle: docs/design-handoff, `.stp` in the reference — a single pill, not
 * two separate buttons). This composable only knows about layout; every
 * caller supplies [step] (how big a tap is), [round] (snap-to-grid, applied
 * to every stepped value before it is clamped to [minValue] and emitted), and
 * [format] (how the value reads). Weight callers must pass
 * [WeightStepper.increment]/[WeightStepper.round]/[WeightStepper.format] —
 * see the previews below — so unit-aware rounding stays defined once, in
 * `:domain`. Note [WeightStepper.round] never returns below one increment, so
 * for weight callers the default minValue of 0.0 is effectively unreachable —
 * the domain floor wins, by design.
 *
 * [valueTextStyle]/[valueMinWidth] default to the weight-numeral presentation
 * (`display2`/52dp, the row's hero number); reps callers pass the smaller
 * `display3`/36dp pair. Both default so every pre-restyle call site (which
 * only named `value`/`onValueChange`/`step`/`minValue`/`format`/`round`)
 * keeps compiling unchanged. [valueColor] defaults to [TextPrimary]; the day
 * screen's cascade flash animates it to the day accent and back (see
 * `SetRow`), driving the number-level half of the flash while the row
 * background drives the other half.
 *
 * [decreaseDescription]/[increaseDescription] are the TalkBack accessible
 * names for the − / + segments (A7); they default to the bare verbs but
 * callers that know what they're stepping (weight vs. reps, see `SetRow`)
 * should pass something more specific, e.g. "Decrease weight".
 *
 * Long-press auto-repeat (A7): holding either segment repeats [onValueChange]
 * — the very call a single tap makes, so min/round clamp identically — after
 * [LONG_PRESS_INITIAL_DELAY_MS], then every [LONG_PRESS_REPEAT_INTERVAL_MS].
 * A quick tap never reaches the initial delay, so it fires exactly once, via
 * the ordinary click path; holding suppresses that path's own click at
 * release so a long press doesn't tack on one extra step (see [StepSegment]).
 * No new persisted state — the repeat is entirely transient press-driven UI
 * state, gone the moment the finger lifts.
 */
@Composable
fun Stepper(
    value: Double,
    onValueChange: (Double) -> Unit,
    step: (Double) -> Double,
    modifier: Modifier = Modifier,
    minValue: Double = 0.0,
    format: (Double) -> String = { it.toString() },
    round: (Double) -> Double = { it },
    valueTextStyle: TextStyle = StepperValue,
    valueMinWidth: Dp = 52.dp,
    valueColor: Color = TextPrimary,
    decreaseDescription: String = "decrease",
    increaseDescription: String = "increase",
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(CapsuleShape)
            .background(Surface2)
            .border(1.dp, Border, CapsuleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepSegment(symbol = "−", contentDescription = decreaseDescription) {
            onValueChange(maxOf(minValue, round(value - step(value))))
        }
        Text(
            text = format(value),
            modifier = Modifier.widthIn(min = valueMinWidth).padding(horizontal = 2.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            color = valueColor,
            style = valueTextStyle,
        )
        StepSegment(symbol = "+", contentDescription = increaseDescription) {
            onValueChange(maxOf(minValue, round(value + step(value))))
        }
    }
}

/**
 * One 32dp-wide capsule segment (`.sb` in the reference); the clickable area
 * expands to Material's >= 48dp touch minimum via [minimumInteractiveComponentSize],
 * overlapping into the neighboring segment/value the same way the old
 * per-button implementation did. Press flashes [Surface3] for 120ms
 * (design tokens: `--dur-fast`), matching `.sb:active`.
 *
 * [contentDescription] is the accessible name (TalkBack never reads the raw
 * −/+ glyph — [clearAndSetSemantics] silences the inner [Text] and the
 * outer [Modifier.semantics] carries the real description instead).
 *
 * The [LaunchedEffect] below drives long-press auto-repeat: it starts timing
 * the moment [pressed] goes true and is cancelled (its delay/loop torn down)
 * the moment it goes false, so releasing always stops the repeat immediately.
 * [repeated] latches once the first repeat fires so the release-triggered
 * `clickable` click — which still occurs, since holding-without-dragging is
 * still a valid tap in Compose's gesture detector — is skipped instead of
 * appending one extra, unrepeatable step.
 */
@Composable
private fun StepSegment(symbol: String, contentDescription: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) Surface3 else Color.Transparent,
        animationSpec = tween(120),
        label = "stepSegmentPress",
    )
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        repeated = false
        delay(LONG_PRESS_INITIAL_DELAY_MS)
        while (true) {
            repeated = true
            onClick()
            delay(LONG_PRESS_REPEAT_INTERVAL_MS)
        }
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = contentDescription,
                onClick = { if (!repeated) onClick() },
            )
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.width(32.dp).fillMaxHeight().background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = symbol, color = TextSecondary, style = StepperGlyph, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WeightStepperPreview() {
    AppTheme {
        var weightLb by remember { mutableDoubleStateOf(135.0) }
        Stepper(
            value = weightLb,
            onValueChange = { weightLb = it },
            step = { WeightStepper.increment(it, WeightUnit.LB) },
            format = WeightStepper::format,
            round = { WeightStepper.round(it, WeightUnit.LB) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RepsStepperPreview() {
    AppTheme {
        var reps by remember { mutableDoubleStateOf(8.0) }
        Stepper(
            value = reps,
            onValueChange = { reps = it },
            step = { 1.0 },
            minValue = 1.0,
            format = { it.toInt().toString() },
            valueTextStyle = StepperRepsValue,
            valueMinWidth = 36.dp,
        )
    }
}
