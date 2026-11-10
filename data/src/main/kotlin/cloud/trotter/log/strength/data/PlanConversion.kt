package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule

/** The rule conversion gives an existing slot (design §4.4): the plan goal's defaults, MAIN for a ramped slot or a
 *  main-capable entry, Fixed reps from the slot's first working row. The repository reads the rows. */
internal object PlanConversion {
    /** Fixed reps from the slot's first working row, else its prescription, else a bodyweight entry's rep target,
     *  else [goal]'s default. */
    fun rule(
        exercise: ProgramExercise,
        mainRows: List<LoggedSet>?,
        entry: ExerciseEntry?,
        goal: LiftGoal = LiftGoal.STRENGTH,
    ): SlotRule {
        val structure = exercise.structure
        val role = if (structure.hasTopSet || entry?.mainCapable == true) SlotRole.MAIN else SlotRole.ACCESSORY
        val rowReps = mainRows?.let(::firstWorkingReps)?.takeIf { it in 1..999 }
        val prescribedReps = when (val target = exercise.prescription?.target) {
            is PrescribedTarget.LoadReps -> target.reps
            is PrescribedTarget.Reps -> target.reps
            is PrescribedTarget.RepsInTime -> target.repAim
            is PrescribedTarget.Hold, null -> null
        }?.takeIf { it in 1..999 }
        // A deleted custom exercise uses the upper-body increment through this fallback pattern.
        return SlotRule.defaults(role, goal, structure, rowReps ?: prescribedReps ?: libraryReps(entry),
            entry?.pattern ?: MovementPattern.CORE_FLEX)
    }

    /** A superset partner's rule (`partnerRuleJson`): ACCESSORY straight sets, Fixed reps from [rows]' first working
     *  row, else a bodyweight entry's rep target, else [goal]'s default. An unknown entry uses the fallback pattern. */
    fun partnerRule(entry: ExerciseEntry?, goal: LiftGoal, rows: List<LoggedSet>? = null): SlotRule =
        // A deleted custom exercise uses the upper-body increment through this fallback pattern.
        SlotRule.defaults(SlotRole.ACCESSORY, goal, SetStructure.STRAIGHT,
            rows?.let(::firstWorkingReps)?.takeIf { it in 1..999 } ?: libraryReps(entry),
            entry?.pattern ?: MovementPattern.CORE_FLEX)

    private fun libraryReps(entry: ExerciseEntry?): Int? =
        (entry?.goal as? GoalSource.Reps)?.targetReps?.takeIf { it in 1..999 }

    /** The TOP row's reps, else the first non-RAMP row's; null when that row has none (a timed hold). */
    fun firstWorkingReps(rows: List<LoggedSet>): Int? =
        (rows.firstOrNull { it.kind == SetKind.TOP } ?: rows.firstOrNull { it.kind != SetKind.RAMP })?.reps?.takeIf { it > 0 }
}
