package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.TextSecondary

/** Shared conversion and presentation path for every bodyweight editor. */
object BodyweightInput {
    fun display(canonicalLb: Int, unit: WeightUnit): Double = unit.fromLb(canonicalLb.toDouble())

    fun canonicalLb(displayValue: Double, unit: WeightUnit): Int =
        Math.round(unit.toLb(displayValue)).toInt()
}

@Composable
fun BodyweightStepper(
    canonicalLb: Int,
    unit: WeightUnit,
    label: String,
    decreaseDescription: String,
    increaseDescription: String,
    onCanonicalLbChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(8.dp))
        Stepper(
            value = BodyweightInput.display(canonicalLb, unit),
            onValueChange = { onCanonicalLbChange(BodyweightInput.canonicalLb(it, unit)) },
            step = { WeightStepper.increment(it, unit) },
            minValue = 1.0,
            format = WeightStepper::format,
            round = { WeightStepper.round(it, unit) },
            decreaseDescription = decreaseDescription,
            increaseDescription = increaseDescription,
        )
    }
}
