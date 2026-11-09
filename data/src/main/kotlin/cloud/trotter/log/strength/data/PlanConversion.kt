package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule

/** The rule conversion gives an existing slot (design §4.4): Strength defaults, MAIN for a ramped slot or a
 *  main-capable entry, Fixed reps from the slot's first working row. [PlanRepository.convertPlans] reads the rows. */
internal object PlanConversion {
    fun rule(slot: ProgramExerciseEntity, mainRows: List<LoggedSet>?, entry: ExerciseEntry?): SlotRule {
        val structure = slot.toDomain().structure
        val role = if (structure.hasTopSet || entry?.mainCapable == true) SlotRole.MAIN else SlotRole.ACCESSORY
        val rowReps = mainRows?.let(::firstWorkingReps)?.takeIf { it in 1..999 }
        val prescribedReps = when (val target = PrescriptionJson.decode(slot.prescriptionJson)?.target) {
            is PrescribedTarget.LoadReps -> target.reps
            is PrescribedTarget.Reps -> target.reps
            is PrescribedTarget.RepsInTime -> target.repAim
            is PrescribedTarget.Hold, null -> null
        }?.takeIf { it in 1..999 }
        // A deleted custom exercise uses the upper-body increment through this fallback pattern.
        return SlotRule.defaults(role, LiftGoal.STRENGTH, structure, rowReps ?: prescribedReps,
            entry?.pattern ?: MovementPattern.CORE_FLEX)
    }

    /** The TOP row's reps, else the first non-RAMP row's; null when that row has none (a timed hold). */
    fun firstWorkingReps(rows: List<LoggedSet>): Int? =
        (rows.firstOrNull { it.kind == SetKind.TOP } ?: rows.firstOrNull { it.kind != SetKind.RAMP })?.reps?.takeIf { it > 0 }
}
