package io.github.sjtrotter.strengthlog.domain.units

import java.util.Locale

/**
 * Unit-aware weight-stepper increment, rounding, and display formatting.
 * Everything here operates on values already expressed in a [WeightUnit] —
 * canonical lb storage and conversion live in [WeightUnit]; this is purely
 * about how a display value steps and reads on screen.
 */
object WeightStepper {

    /**
     * Stepper increment for a working weight already expressed in [unit].
     * Spec §5: 2.5 lb when the working weight is <= 20 lb (light isolation),
     * otherwise 5 lb. The kg side uses the equivalent <= 9 kg boundary so the
     * light/standard split lands in the same place regardless of unit.
     */
    fun increment(displayWeight: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.LB -> if (displayWeight <= 20.0) 2.5 else 5.0
        WeightUnit.KG -> if (displayWeight <= 9.0) 1.25 else 2.5
    }

    /**
     * Rounds [displayWeight] to the nearest multiple of its unit increment,
     * mirroring the spirit of the spec's round5 — never below one increment.
     */
    fun round(displayWeight: Double, unit: WeightUnit): Double {
        val step = increment(displayWeight, unit)
        return maxOf(step, Math.round(displayWeight / step) * step)
    }

    /**
     * Formats a display weight for the UI: trims a trailing ".0" so whole
     * numbers read as "60" rather than "60.0", but keeps meaningful decimals
     * like "27.5" or "6.25".
     */
    fun format(displayWeight: Double): String {
        if (displayWeight == displayWeight.toLong().toDouble()) {
            return displayWeight.toLong().toString()
        }
        val text = String.format(Locale.ROOT, "%.2f", displayWeight)
        return text.trimEnd('0').trimEnd('.')
    }
}
