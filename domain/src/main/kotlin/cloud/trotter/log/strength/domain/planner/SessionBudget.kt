package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.standards.RestPolicy

/** Audit 2026-11-13, row 13: 15 s setup plus reps × 3 s (Wilk 2021: volitional tempo 2–5 s/rep),
 *  or the planned seconds for a timed set. Only the reps or hold double for eachSide. A superset includes
 *  both lifts' work with one rest from the timer's resolver. Setup is practice: transitions are unmeasured.
 *  Strength days include a 240 s cool-down when enabled (practice: the middle of #574's 3–5 minutes).
 *  Cardio counts its planned time. The 300 s warm-up stays, unless the block opens with its own WARMUP. */
object SessionBudget {
    const val WARM_UP_SECONDS = 300
    const val SET_SETUP_SECONDS = 15
    const val SECONDS_PER_REP = 3
    const val COOL_DOWN_SECONDS = 240

    fun seconds(day: ProgramDay, goal: LiftGoal, coolDown: Boolean = true): Int {
        if (day.kind == ProgramDayKind.REST) return 0
        val warmUp = if (day.cardioBlock?.sets?.first()?.role == CardioRole.WARMUP) 0 else WARM_UP_SECONDS
        val coolDownSeconds = if (coolDown && day.kind == ProgramDayKind.STRENGTH) COOL_DOWN_SECONDS else 0
        return warmUp + coolDownSeconds + day.exercises.sumOf { slot ->
            val tracking = ExerciseLibrary.find(slot.exerciseId)?.tracking ?: TrackingType.WEIGHTED
            val work = workSeconds(slot)
            val partner = slot.superset?.let { workSeconds(ProgramExercise(it.exerciseId, targetSets = 1, rule = it.rule)).single() } ?: 0
            setKinds(slot).mapIndexed { index, kind ->
                work[index] + partner + RestPolicy.effectiveRestSeconds(kind, tracking, emptyMap(), slot.rule, goal)
            }.sum()
        } + (day.cardioBlock?.let(::plannedSeconds) ?: 0)
    }

    private fun workSeconds(slot: ProgramExercise): List<Int> {
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
            return List(slot.targetSets) { seconds }
        }
        (entry?.goal as? GoalSource.Time)?.let { time -> return List(slot.targetSets) { timed(time.targetSeconds) } }
        val target = slot.rule?.target?.reps ?: (entry?.goal as? GoalSource.Reps)?.targetReps ?: 10
        val scheme = slot.rule?.scheme ?: Scheme.PROTOTYPE
        return when (slot.structure) {
            SetStructure.STRAIGHT -> List(slot.targetSets) { reps(target) }
            SetStructure.RAMP_TOP -> scheme.ramps.map { reps(it.reps) } + reps(target)
            SetStructure.RAMP_TOP_BACKOFF -> scheme.ramps.map { reps(it.reps) } + reps(target) +
                List(scheme.backoffs) { reps(scheme.backoffReps) }
        }
    }
}

internal fun setKinds(slot: ProgramExercise): List<SetKind> {
    if (slot.structure == SetStructure.STRAIGHT) return List(slot.targetSets) { SetKind.WORK }
    val scheme = slot.rule?.scheme ?: Scheme.PROTOTYPE
    return List(scheme.ramps.size) { SetKind.RAMP } + SetKind.TOP +
        if (slot.structure == SetStructure.RAMP_TOP_BACKOFF) List(scheme.backoffs) { SetKind.BACKOFF } else emptyList<SetKind>()
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
