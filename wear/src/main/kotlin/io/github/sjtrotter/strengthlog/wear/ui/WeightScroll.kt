package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.math.abs

/**
 * Applies the unit-aware [WeightStepper] increment [detents] times off a
 * running display value and returns the final target in canonical lb — one
 * cumulative result for a whole rotary flick, so a single delta carries the
 * lifter to where the crown landed.
 *
 * Why a running value and not `start ± N × step`: the increment itself changes
 * with the weight (2.5 lb at/under the light-isolation threshold, 5 lb above),
 * so N detents are re-evaluated one step at a time as the value crosses that
 * boundary — exactly what N separate button taps would do. The on-screen ±
 * buttons call this with `detents = +1 / -1`, so buttons and crown share one
 * stepping rule (never a literal 5). [detents] == 0 is a no-op.
 */
fun scrolledWeightLb(currentLb: Double, unit: WeightUnit, detents: Int): Double {
    if (detents == 0) return currentLb
    val up = detents > 0
    var display = unit.fromLb(currentLb)
    repeat(abs(detents)) {
        val step = WeightStepper.increment(display, unit)
        display = WeightStepper.round(if (up) display + step else display - step, unit)
    }
    return unit.toLb(display)
}
