package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.units.SecondsStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * The single place a [GoalTarget] becomes a GOAL display string (§2.2, SSOT).
 * Every surface — the day card's GOAL chip, the setup preview, the collapsed
 * summary, the watch snapshot label — formats here, so no surface can invent a
 * "0 lb × 60" rendering for a REPS/TIMED entry (design risk #3).
 *
 * WEIGHTED output is the bare unit-converted number, byte-identical to the old
 * `WeightStepper.format(unit.fromLb(goalLb))` — the per-hand "/hand" suffix stays
 * a per-surface concern (a separately styled line), not part of this string.
 */
object GoalFormatter {

    /** The GOAL string for [target], with any weight converted to [unit]. */
    fun label(target: GoalTarget, unit: WeightUnit): String = when (target) {
        is GoalTarget.Weight -> WeightStepper.format(unit.fromLb(target.lb))
        is GoalTarget.Reps -> "${target.reps} reps"
        is GoalTarget.Time ->
            if (target.addedLb > 0.0) {
                "${SecondsStepper.format(target.seconds)} +${WeightStepper.format(unit.fromLb(target.addedLb))}"
            } else {
                SecondsStepper.format(target.seconds)
            }
    }
}
