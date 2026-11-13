package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.standards.RestPolicy

/** Design §3.1 invariant 1: "5 min warm-up, then per set 45 s plus its rest; a superset counts once".
 *  The rest is the timer's own resolver with no user overrides, so the estimate and the timer agree. */
object SessionBudget {
    const val WARM_UP_SECONDS = 300
    const val SET_SECONDS = 45

    fun seconds(day: ProgramDay, goal: LiftGoal): Int {
        if (day.kind == ProgramDayKind.REST) return 0
        return WARM_UP_SECONDS + day.exercises.sumOf { slot ->
            val tracking = ExerciseLibrary.find(slot.exerciseId)?.tracking ?: TrackingType.WEIGHTED
            setKinds(slot).sumOf { kind ->
                SET_SECONDS + RestPolicy.effectiveRestSeconds(kind, tracking, emptyMap(), slot.rule, goal)
            }
        }
    }
}

internal fun setKinds(slot: ProgramExercise): List<SetKind> {
    if (slot.structure == SetStructure.STRAIGHT) return List(slot.targetSets) { SetKind.WORK }
    val scheme = slot.rule?.scheme ?: Scheme.PROTOTYPE
    return List(scheme.ramps.size) { SetKind.RAMP } + SetKind.TOP +
        if (slot.structure == SetStructure.RAMP_TOP_BACKOFF) List(scheme.backoffs) { SetKind.BACKOFF } else emptyList()
}
