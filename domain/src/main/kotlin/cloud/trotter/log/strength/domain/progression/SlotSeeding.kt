package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.GeneratedLoad
import cloud.trotter.log.strength.domain.units.PlateMath
import cloud.trotter.log.strength.domain.units.WeightUnit

/** What the log already says about loads. The planner adds cardio maxima in PR 1b. */
data class KnownLoads(val lastWorking: Map<String, LoggedSet>) {
    companion object { val NONE = KnownLoads(emptyMap()) }
}

/** The one seeding function for a ruled slot with no stored rows. Loads: known.lastWorking for this exercise through
 *  Estimate (null rir reads as 2), else the equipment floor with calibrating on. Never a formula. Returns the rule
 *  too, because the calibrating flag lives only in the rule's state. */
object SlotSeeding {
    data class Seeded(val rows: List<LoggedSet>, val rule: SlotRule)

    fun rows(slot: ProgramExercise, rule: SlotRule, known: KnownLoads, steps: LoadSteps, unit: WeightUnit): Seeded {
        val entry = ExerciseLibrary.find(slot.exerciseId)
        val equipment = entry?.equipment.orEmpty()
        val reps = rule.target.reps
        slot.prescription?.let {
            return Seeded(SetSeeder.seedPrescribed(slot.targetSets, it, slot.structure, unit, equipment), rule)
        }
        when (entry?.tracking ?: TrackingType.WEIGHTED) {
            TrackingType.REPS -> return Seeded(List(slot.targetSets) { LoggedSet(0.0, reps, SetKind.WORK) }, rule)
            TrackingType.TIMED -> {
                val time = entry?.goal as GoalSource.Time
                return Seeded(List(slot.targetSets) {
                    LoggedSet(GeneratedLoad.snap(time.addedWeightLb, unit), 0, SetKind.WORK, seconds = time.targetSeconds)
                }, rule)
            }
            TrackingType.WEIGHTED -> Unit
        }
        val last = known.lastWorking[slot.exerciseId]?.takeIf { it.weightLb > 0 }
        val estimate = last?.let { Estimate.e1rm(it.weightLb, it.reps, it.rir ?: Effort.GOOD.rir) }
        val top = if (estimate != null) steps.nearest(Estimate.loadFor(estimate, reps, rule.rir), equipment, unit)
            else last?.weightLb ?: floor(equipment, unit)
        return Seeded(
            SetSeeder.shape(slot.structure, top, reps, slot.targetSets, unit, equipment, rule.scheme),
            rule.copy(state = rule.state.copy(calibrating = estimate == null)),
        )
    }

    private fun floor(equipment: Collection<Equipment>, unit: WeightUnit): Double = when {
        Equipment.BARBELL in equipment || Equipment.TRAP_BAR in equipment -> unit.toLb(PlateMath.barWeight(unit))
        Equipment.EZ_BAR in equipment || Equipment.MACHINE in equipment || Equipment.CABLE in equipment ->
            if (unit == WeightUnit.LB) 20.0 else unit.toLb(10.0)
        Equipment.KETTLEBELL in equipment -> if (unit == WeightUnit.LB) 18.0 else unit.toLb(8.0)
        else -> if (unit == WeightUnit.LB) 10.0 else unit.toLb(5.0)
    }
}
