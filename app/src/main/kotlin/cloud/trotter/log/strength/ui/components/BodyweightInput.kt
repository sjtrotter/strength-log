package cloud.trotter.log.strength.ui.components

import cloud.trotter.log.strength.domain.units.Bodyweight
import cloud.trotter.log.strength.domain.units.WeightUnit

/** Shared conversion and presentation path for every bodyweight editor. */
object BodyweightInput {
    fun display(canonicalLb: Double, unit: WeightUnit): Double = Bodyweight.display(canonicalLb, unit)

    fun canonicalLb(displayValue: Double, unit: WeightUnit): Double = Bodyweight.fromDisplay(displayValue, unit)
}
