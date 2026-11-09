package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.units.PlateMath
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.math.ceil
import kotlin.math.floor

/** The smallest load change per equipment family, stored in lb (a kg user's steps are their kg values converted),
 *  so a kg grid stays exact in canonical storage. The plan carries these; the lift's step is read, never stored. */
data class LoadSteps(val barLb: Double, val dumbbellLb: Double, val stackLb: Double) {
    fun stepLb(equipment: Collection<Equipment>): Double = when {
        Equipment.BARBELL in equipment || Equipment.TRAP_BAR in equipment || Equipment.EZ_BAR in equipment -> barLb
        Equipment.MACHINE in equipment || Equipment.CABLE in equipment -> stackLb
        else -> dumbbellLb
    }

    fun nearest(lb: Double, equipment: Collection<Equipment>, unit: WeightUnit): Double =
        rounded(lb, equipment, unit) { Math.round(it).toDouble() }

    fun down(lb: Double, equipment: Collection<Equipment>, unit: WeightUnit): Double =
        rounded(lb, equipment, unit, ::floor)

    fun up(lb: Double, equipment: Collection<Equipment>, unit: WeightUnit): Double =
        rounded(lb, equipment, unit, ::ceil)

    private fun rounded(
        lb: Double,
        equipment: Collection<Equipment>,
        unit: WeightUnit,
        round: (Double) -> Double,
    ): Double {
        val step = stepLb(equipment)
        val floor = if (Equipment.BARBELL in equipment || Equipment.TRAP_BAR in equipment) {
            maxOf(step, unit.toLb(PlateMath.barWeight(unit)))
        } else step
        return maxOf(floor, round(lb / step) * step)
    }

    companion object {
        /** The setup defaults: 5 / 5 / 10 lb, or 2.5 / 2 / 5 kg. */
        fun defaults(unit: WeightUnit): LoadSteps = when (unit) {
            WeightUnit.LB -> LoadSteps(5.0, 5.0, 10.0)
            WeightUnit.KG -> LoadSteps(unit.toLb(2.5), unit.toLb(2.0), unit.toLb(5.0))
        }
    }
}
