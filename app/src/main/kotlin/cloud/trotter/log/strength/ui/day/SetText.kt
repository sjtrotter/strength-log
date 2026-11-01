package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.text.UiText

internal fun setLabel(row: SetRowState): UiText.SetLabel =
    UiText.SetLabel(row.kind, if (row.kind == SetKind.RAMP) row.rampIndex else row.index + 1)

@Composable
internal fun setValue(
    weight: Double,
    reps: Int,
    seconds: Int,
    tracking: TrackingType,
    timedWeight: Boolean,
): String = when (tracking) {
    TrackingType.WEIGHTED -> "${WeightStepper.format(weight)} × $reps"
    TrackingType.REPS -> "$reps ${stringResource(R.string.unit_reps)}"
    TrackingType.TIMED -> "$seconds ${stringResource(R.string.unit_seconds)}" +
        if (timedWeight && weight > 0) " ${stringResource(R.string.unit_at, WeightStepper.format(weight))}" else ""
}
