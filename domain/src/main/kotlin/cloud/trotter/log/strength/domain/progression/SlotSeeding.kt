package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.GeneratedLoad
import cloud.trotter.log.strength.domain.units.PlateMath
import cloud.trotter.log.strength.domain.units.WeightUnit

/** What the log already says: each exercise's last working set, and per cardio family the 30-day maxima the
 *  planner's first run cap reads. */
data class KnownLoads(
    val lastWorking: Map<String, LoggedSet>,
    val longestRecent: Map<CardioFamily, RecentMaxima> = emptyMap(),
    /** Per family, [CardioHistory.longestWeek]; the half build's week gate reads it. */
    val longestWeek: Map<CardioFamily, RecentMaxima> = emptyMap(),
) {
    companion object { val NONE = KnownLoads(emptyMap()) }
}

/** The one seeding function for a ruled slot with no stored rows. Loads, in order: [planLb], the weight the plan
 *  carries on another slot, copied at equal reps or converted through Estimate and rounded down at different reps;
 *  else known.lastWorking through Estimate (null rir reads as 2); else the equipment floor with calibrating on.
 *  A loaded hold takes the plan's or last working load in place of its catalog load. Never a formula. Returns the
 *  rule too, because the calibrating flag lives only in the rule's state. */
object SlotSeeding {
    data class Seeded(val rows: List<LoggedSet>, val rule: SlotRule)

    fun rows(
        slot: ProgramExercise,
        rule: SlotRule,
        known: KnownLoads,
        steps: LoadSteps,
        unit: WeightUnit,
        entry: ExerciseEntry? = ExerciseLibrary.find(slot.exerciseId),
        planLb: Double? = null,
        planReps: Int = rule.target.reps,
        planRir: Int = Effort.GOOD.rir,
    ): Seeded {
        val equipment = entry?.equipment.orEmpty()
        val reps = rule.target.reps
        slot.prescription?.let {
            return Seeded(SetSeeder.seedPrescribed(slot.targetSets, it, slot.structure, unit, equipment), rule)
        }
        val planned = planLb?.takeIf { it > 0.0 }
        val last = known.lastWorking[slot.exerciseId]?.takeIf { it.weightLb > 0 }
        when (entry?.tracking ?: TrackingType.WEIGHTED) {
            TrackingType.REPS -> return Seeded(List(slot.targetSets) { LoggedSet(0.0, reps, SetKind.WORK) }, rule)
            TrackingType.TIMED -> {
                val time = entry?.goal as GoalSource.Time
                val load = (planned ?: last?.weightLb)?.takeIf { time.addedWeightLb > 0.0 }
                    ?: GeneratedLoad.snap(time.addedWeightLb, unit)
                return Seeded(List(slot.targetSets) {
                    LoggedSet(load, 0, SetKind.WORK, seconds = time.targetSeconds)
                }, rule)
            }
            TrackingType.WEIGHTED -> Unit
        }
        val plannedEstimate = if (planned != null && planReps != reps) Estimate.e1rm(planned, planReps, planRir) else null
        val plannedTop = planned?.let { weight ->
            if (planReps == reps) weight else {
                steps.down(plannedEstimate?.let { Estimate.loadFor(it, reps, rule.rir) } ?: weight, equipment, unit)
            }
        }
        val estimate = if (planned == null) last?.let { Estimate.e1rm(it.weightLb, it.reps, it.rir ?: Effort.GOOD.rir) } else null
        val top = plannedTop
            ?: estimate?.let { steps.nearest(Estimate.loadFor(it, reps, rule.rir), equipment, unit) }
            ?: last?.let { steps.nearest(it.weightLb, equipment, unit) }
            ?: floor(equipment, unit)
        val calibrating = if (planned != null) planReps != reps && plannedEstimate == null else estimate == null
        return Seeded(
            SetSeeder.shape(slot.structure, top, reps, slot.targetSets, unit, equipment, rule.scheme),
            rule.copy(state = rule.state.copy(calibrating = calibrating)),
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
