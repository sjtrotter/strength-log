package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.plannedSeconds
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.standards.RestPolicy

/** Audit 2026-11-13, row 13: 15 s setup plus reps × 3 s (Wilk 2021: volitional tempo 2–5 s/rep),
 *  or the planned seconds for a timed set. Only the reps or hold double for eachSide. A superset includes
 *  both lifts' work with one rest from the timer's resolver. Setup is practice: transitions are unmeasured.
 *  The optional cool-down (#574) is outside the session minutes.
 *  Cardio counts its planned time. The 300 s warm-up stays, unless the block opens with its own WARMUP. */
object SessionBudget {
    const val WARM_UP_SECONDS = 300
    const val SET_SETUP_SECONDS = 15
    const val SECONDS_PER_REP = 3

    fun seconds(day: ProgramDay, goal: LiftGoal): Int {
        if (day.kind == ProgramDayKind.REST) return 0
        val warmUp = if (day.cardioBlock?.sets?.first()?.role == CardioRole.WARMUP) 0 else WARM_UP_SECONDS
        return warmUp + day.exercises.sumOf { slot ->
            val tracking = ExerciseLibrary.find(slot.exerciseId)?.tracking ?: TrackingType.WEIGHTED
            val work = workSeconds(slot)
            val partner = slot.superset?.let { workSeconds(ProgramExercise(it.exerciseId, targetSets = 1, rule = it.rule)).single() } ?: 0
            setKinds(slot).mapIndexed { index, kind ->
                work[index] + partner + RestPolicy.effectiveRestSeconds(kind, tracking, emptyMap(), slot.rule, goal)
            }.sum()
        } + (day.cardioBlock?.let(::plannedSeconds) ?: 0)
    }

    /** What a day's cardio block may plan within [minutes] (4b-ii, design §3.1.2): the minutes less the rest of
     *  the day's estimate (its lifts, and the 300 s warm-up unless the block opens with its own WARMUP).
     *  Null without a block. */
    fun cardioBudget(day: ProgramDay, minutes: Int, goal: LiftGoal): Int? =
        day.cardioBlock?.let { minutes * 60 - (seconds(day, goal) - plannedSeconds(it)) }

    private fun workSeconds(slot: ProgramExercise): List<Int> {
        val kinds = setKinds(slot)
        val entry = ExerciseLibrary.find(slot.exerciseId)
        val sides = if (entry?.eachSide == true || slot.prescription?.eachSide == true) 2 else 1
        fun timed(seconds: Int) = SET_SETUP_SECONDS + seconds * sides
        fun reps(count: Int) = timed(count * SECONDS_PER_REP)
        slot.prescription?.let { prescription ->
            val seconds = when (val target = prescription.target) {
                is PrescribedTarget.LoadReps -> reps(target.reps)
                is PrescribedTarget.Reps -> reps(target.reps)
                is PrescribedTarget.Hold -> timed(target.seconds)
                is PrescribedTarget.RepsInTime -> timed(target.seconds)
            }
            return kinds.map { seconds }
        }
        (entry?.goal as? GoalSource.Time)?.let { time -> return kinds.map { timed(time.targetSeconds) } }
        val target = slot.rule?.target?.reps ?: (entry?.goal as? GoalSource.Reps)?.targetReps ?: 10
        val scheme = slot.rule?.scheme ?: Scheme.PROTOTYPE
        return kinds.mapIndexed { index, kind ->
            reps(when (kind) {
                SetKind.RAMP -> scheme.ramps[index].reps
                SetKind.BACKOFF -> scheme.backoffReps
                else -> target
            })
        }
    }
}

internal fun setKinds(slot: ProgramExercise): List<SetKind> {
    if (slot.structure == SetStructure.STRAIGHT) return List(slot.targetSets) { SetKind.WORK }
    val scheme = slot.rule?.scheme ?: Scheme.PROTOTYPE
    return List(scheme.ramps.size) { SetKind.RAMP } + SetKind.TOP +
        if (slot.structure == SetStructure.RAMP_TOP_BACKOFF) List(scheme.backoffs) { SetKind.BACKOFF } else emptyList<SetKind>()
}
