package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule

internal fun liftSlot(role: SlotRole, entry: ExerciseEntry, answers: SetupAnswers): ProgramExercise {
    val experienced = answers.experience == LiftExperience.A_YEAR_OR_MORE
    val main = role == SlotRole.MAIN
    val secondary = role == SlotRole.SECONDARY
    val ramped = main && !answers.olderAdult && experienced && answers.goal == LiftGoal.STRENGTH &&
        entry.tracking == TrackingType.WEIGHTED
    val structure = if (ramped) SetStructure.RAMP_TOP_BACKOFF else SetStructure.STRAIGHT
    var sets = when {
        ramped -> Scheme.STRENGTH.ramps.size + 1 + Scheme.STRENGTH.backoffs
        role == SlotRole.CORE || role == SlotRole.BALANCE || secondary || answers.olderAdult -> 2
        answers.goal == LiftGoal.GENERAL -> 2
        else -> 3
    }
    var target: RepTarget = when {
        main && answers.olderAdult -> RepTarget.Range(6, 10, 6)
        (main || secondary) && answers.goal == LiftGoal.STRENGTH -> RepTarget.Fixed(5)
        main && answers.goal == LiftGoal.MUSCLE && experienced -> RepTarget.Range(6, 10, 6)
        (main || secondary) && answers.goal == LiftGoal.MUSCLE -> RepTarget.Range(8, 12, 8)
        main -> RepTarget.Range(8, 15, 8)
        answers.olderAdult || answers.goal == LiftGoal.STRENGTH -> RepTarget.Range(8, 12, 8)
        else -> RepTarget.Range(10, 15, 10)
    }
    val defaults = SlotRule.defaults(role, answers.goal, structure, reps = null, entry.pattern)
    if (role == SlotRole.BALANCE) target = defaults.target
    (entry.goal as? GoalSource.Reps)?.let { target = RepTarget.Fixed(it.targetReps) }
    val dose = entry.dose?.takeUnless { main || secondary }
    if (dose != null) sets = dose.sets
    val rir = maxOf(
        defaults.rir,
        if (main && answers.goal != LiftGoal.GENERAL && !experienced) 3 else 0,
        if (answers.experience == LiftExperience.NEW) 2 else 0,
        if (answers.olderAdult) 3 else 0,
    )
    val rule = defaults.copy(
        target = target,
        rir = rir,
        incrementPct = defaults.incrementPct * if (experienced) 1 else 2,
        everyExposures = if (experienced) 2 else 1,
        scheme = Scheme.STRENGTH,
        restSeconds = if (main && answers.olderAdult) 120 else dose?.restSeconds,
    )
    return ProgramExercise(entry.id, structure, sets, rule = rule)
}

internal fun accessoryRole(pattern: MovementPattern): SlotRole = when (pattern) {
    MovementPattern.CORE_ANTI_EXT, MovementPattern.CORE_ANTI_ROT, MovementPattern.CORE_FLEX -> SlotRole.CORE
    MovementPattern.KNEE_FLEXION, MovementPattern.KNEE_EXTENSION, MovementPattern.SIDE_DELT,
    MovementPattern.REAR_DELT, MovementPattern.BICEPS, MovementPattern.TRICEPS,
    MovementPattern.CALF_GASTROC, MovementPattern.CALF_SOLEUS -> SlotRole.ISOLATION
    else -> SlotRole.ACCESSORY
}
