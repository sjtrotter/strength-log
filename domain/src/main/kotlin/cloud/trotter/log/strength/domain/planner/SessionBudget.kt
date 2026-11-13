package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.standards.RestPolicy

/** Design §3.1 invariant 1: "5 min warm-up, then per set 45 s plus its rest; a superset counts once".
 *  A cardio block counts its planned time. The rest is the timer's own resolver with no user overrides, so the
 *  estimate and the timer agree. A block that opens with its own WARMUP set (a sprint day's 10 min build-up,
 *  §3.1.3) is the day's warm-up. */
object SessionBudget {
    const val WARM_UP_SECONDS = 300
    const val SET_SECONDS = 45

    fun seconds(day: ProgramDay, goal: LiftGoal): Int {
        if (day.kind == ProgramDayKind.REST) return 0
        val warmUp = if (day.cardioBlock?.sets?.first()?.role == CardioRole.WARMUP) 0 else WARM_UP_SECONDS
        return warmUp + day.exercises.sumOf { slot ->
            val tracking = ExerciseLibrary.find(slot.exerciseId)?.tracking ?: TrackingType.WEIGHTED
            setKinds(slot).sumOf { kind ->
                SET_SECONDS + RestPolicy.effectiveRestSeconds(kind, tracking, emptyMap(), slot.rule, goal)
            }
        } + (day.cardioBlock?.let(::plannedSeconds) ?: 0)
    }
}

internal fun setKinds(slot: ProgramExercise): List<SetKind> {
    if (slot.structure == SetStructure.STRAIGHT) return List(slot.targetSets) { SetKind.WORK }
    val scheme = slot.rule?.scheme ?: Scheme.PROTOTYPE
    return List(scheme.ramps.size) { SetKind.RAMP } + SetKind.TOP +
        if (slot.structure == SetStructure.RAMP_TOP_BACKOFF) List(scheme.backoffs) { SetKind.BACKOFF } else emptyList()
}

/** 6:00 per km, practice; only for a distance set with no time aim. */
private const val ESTIMATE_SECONDS_PER_METRE = 0.36

internal fun plannedSeconds(block: CardioBlock): Int = Math.round(block.sets.sumOf { set ->
    when (val end = set.end) {
        is Measure.Time -> end.seconds.toDouble()
        is Measure.Distance -> (set.aim as? Measure.Time)?.seconds?.toDouble()
            ?: end.metres * ESTIMATE_SECONDS_PER_METRE
    }
}).toInt()
