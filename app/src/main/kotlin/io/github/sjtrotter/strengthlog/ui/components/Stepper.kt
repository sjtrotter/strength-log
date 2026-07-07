package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Surface
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary

/**
 * The ± stepper used for both set weight and set reps. This composable only
 * knows about layout; every caller supplies [step] (how big a tap is),
 * [round] (snap-to-grid, applied to every stepped value before it is clamped
 * to [minValue] and emitted), and [format] (how the value reads). Weight
 * callers must pass [WeightStepper.increment]/[WeightStepper.round]/
 * [WeightStepper.format] — see the previews below — so unit-aware rounding
 * stays defined once, in `:domain`. Note [WeightStepper.round] never returns
 * below one increment, so for weight callers the default minValue of 0.0 is
 * effectively unreachable — the domain floor wins, by design.
 *
 * No long-press auto-repeat: the spec's sets are small (single-digit reps,
 * a handful of weight taps to the next plate), so the +/- taps a lifter
 * actually needs are few enough that repeat-on-hold isn't worth the added
 * state and complexity here.
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
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton(symbol = "-") {
            onValueChange(maxOf(minValue, round(value - step(value))))
        }
        Text(
            text = format(value),
            modifier = Modifier.widthIn(min = 64.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            color = TextPrimary,
            style = MaterialTheme.typography.displayLarge,
        )
        StepButton(symbol = "+") {
            onValueChange(maxOf(minValue, round(value + step(value))))
        }
    }
}

/** Visually 40dp; the clickable sits on a >= 48dp box for Material's touch minimum. */
@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Surface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = symbol, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
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
        )
    }
}
