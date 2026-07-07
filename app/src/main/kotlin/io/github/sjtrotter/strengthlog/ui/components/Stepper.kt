package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * knows about layout; every caller supplies [step] (how big a tap is) and
 * [format] (how the value reads). Weight callers must pass
 * [WeightStepper.increment]/[WeightStepper.round]/[WeightStepper.format] —
 * see the previews below — so unit-aware rounding stays defined once, in
 * `:domain`.
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
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton(symbol = "-") {
            onValueChange(maxOf(minValue, value - step(value)))
        }
        Text(
            text = format(value),
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
            color = TextPrimary,
            style = MaterialTheme.typography.displayLarge,
        )
        StepButton(symbol = "+") {
            onValueChange(value + step(value))
        }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = symbol, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
    }
}

@Preview(showBackground = true)
@Composable
private fun WeightStepperPreview() {
    AppTheme {
        var weightLb by remember { mutableDoubleStateOf(135.0) }
        Stepper(
            value = weightLb,
            onValueChange = { weightLb = WeightStepper.round(it, WeightUnit.LB) },
            step = { WeightStepper.increment(it, WeightUnit.LB) },
            format = WeightStepper::format,
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
