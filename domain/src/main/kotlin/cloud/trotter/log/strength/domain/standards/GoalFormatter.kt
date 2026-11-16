package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit

/** The single place a [GoalTarget] becomes a GOAL display string (SSOT): the bare unit-converted number;
 *  the per-hand suffix stays a per-surface concern. */
object GoalFormatter {
    fun label(target: GoalTarget, unit: WeightUnit): String = WeightStepper.format(unit.fromLb(target.lb))
}
