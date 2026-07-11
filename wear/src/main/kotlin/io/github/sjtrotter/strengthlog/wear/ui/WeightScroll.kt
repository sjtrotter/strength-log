package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.units.SecondsStepper
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

/**
 * The crown's cumulative reps for a REPS track (and the reps ± buttons, via a
 * single detent): ±1 per detent, floored at 1 to match the phone's reps stepper
 * minimum. Unit-free — reps carry no unit.
 */
fun scrolledReps(current: Int, detents: Int): Int = (current + detents).coerceAtLeast(1)

/**
 * The crown's cumulative hold for a TIMED track (and the seconds ± buttons, via a
 * single detent): [SecondsStepper.increment] (±5s) per detent, floored at 0. The
 * step comes from the domain stepper so the watch never hard-codes a second value.
 */
fun scrolledSeconds(current: Int, detents: Int): Int =
    (current + detents * SecondsStepper.increment(current)).coerceAtLeast(0)
