package cloud.trotter.log.strength.ui.components

import cloud.trotter.log.strength.domain.units.WeightUnit

/** Shared conversion and presentation path for every bodyweight editor. */
object BodyweightInput {
    fun display(canonicalLb: Int, unit: WeightUnit): Double = unit.fromLb(canonicalLb.toDouble())

    fun canonicalLb(displayValue: Double, unit: WeightUnit): Int =
        Math.round(unit.toLb(displayValue)).toInt()
}
