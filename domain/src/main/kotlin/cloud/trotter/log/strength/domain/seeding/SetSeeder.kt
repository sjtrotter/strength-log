package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.units.GeneratedLoad
import cloud.trotter.log.strength.domain.units.WeightUnit

/** Stored rows from an authored prescription or a TOP load and a [Scheme]. */
object SetSeeder {

    /** For reps in time, the window lives in the prescription; the log holds the count. */
    fun seedPrescribed(
        sets: Int,
        p: Prescription,
        structure: SetStructure = SetStructure.STRAIGHT,
        unit: WeightUnit = WeightUnit.LB,
        equipment: Collection<Equipment> = emptyList(),
    ): List<LoggedSet> =
        when (val target = p.target) {
            is PrescribedTarget.LoadReps -> shape(structure, target.lb, target.reps, sets, unit, equipment)
            is PrescribedTarget.Reps -> List(sets) { LoggedSet(0.0, target.reps, SetKind.WORK) }
            is PrescribedTarget.Hold -> List(sets) { LoggedSet(target.addedLb, 0, SetKind.WORK, seconds = target.seconds) }
            is PrescribedTarget.RepsInTime -> List(sets) { LoggedSet(0.0, 0, SetKind.WORK) }
        }

    fun shape(
        structure: SetStructure,
        topLb: Double,
        topReps: Int,
        targetSets: Int,
        unit: WeightUnit = WeightUnit.LB,
        equipment: Collection<Equipment> = emptyList(),
        scheme: Scheme = Scheme.PROTOTYPE,
    ): List<LoggedSet> =
        when (structure) {
            SetStructure.STRAIGHT -> List(targetSets) { LoggedSet(topLb, topReps, SetKind.WORK) }
            SetStructure.RAMP_TOP_BACKOFF -> rampsAndTop(topLb, topReps, unit, equipment, scheme) +
                List(scheme.backoffs) {
                    LoggedSet(
                        GeneratedLoad.ramp(topLb * scheme.backoffPct, unit, equipment),
                        scheme.backoffReps,
                        SetKind.BACKOFF,
                    )
                }
            SetStructure.RAMP_TOP -> rampsAndTop(topLb, topReps, unit, equipment, scheme)
        }

    private fun rampsAndTop(
        topLb: Double,
        topReps: Int,
        unit: WeightUnit,
        equipment: Collection<Equipment>,
        scheme: Scheme,
    ): List<LoggedSet> {
        val ramps = scheme.ramps.map { ramp ->
            LoggedSet(GeneratedLoad.ramp(topLb * ramp.pct, unit, equipment), ramp.reps, SetKind.RAMP)
        }
        return ramps + LoggedSet(topLb, topReps, SetKind.TOP)
    }

}
