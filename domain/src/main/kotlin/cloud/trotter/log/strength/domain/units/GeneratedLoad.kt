package cloud.trotter.log.strength.domain.units

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.standards.GoalCalculator

/** Generated loads stay in pounds in storage, on the lifter's unit grid. */
object GeneratedLoad {
    fun snap(lb: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.LB || lb == 0.0) lb
        else unit.toLb(WeightStepper.round(unit.fromLb(lb), unit))

    fun round(lb: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.LB) GoalCalculator.round5(lb) else snap(lb, unit)

    fun ramp(lb: Double, unit: WeightUnit, equipment: Collection<Equipment>): Double {
        val rounded = round(lb, unit)
        return if (Equipment.BARBELL in equipment) maxOf(rounded, unit.toLb(PlateMath.barWeight(unit))) else rounded
    }
}
