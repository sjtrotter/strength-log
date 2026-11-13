package cloud.trotter.log.strength.domain.library

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.StandardLift
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription

/** Where an exercise's read-only GOAL comes from (spec §5). The variant also
 *  decides how the exercise is tracked — see [ExerciseEntry.tracking]. */
sealed interface GoalSource {
    /** Main-capable lift derived from a bodyweight-ratio standard.
     *  [tune] overrides the global [StrengthStandards.liftTune] when non-null
     *  (e.g. conventional deadlift takes the untrimmed 1.0 vs trap-bar's 0.82). */
    data class Std(val lift: StandardLift, val tune: Double? = null) : GoalSource

    /** Fraction of a named standard's main goal (e.g. RDL = 0.72 × squat). */
    data class FracOfStd(val fraction: Double, val lift: StandardLift) : GoalSource

    /** Fixed starting weight in lb (0 = bodyweight). */
    data class Flat(val weightLb: Double) : GoalSource

    /** Pure bodyweight movement tracked by reps. [targetReps] is the read-only
     *  "GOAL n reps" anchor the rows seed at. */
    data class Reps(val targetReps: Int) : GoalSource

    /** Timed hold/carry tracked by seconds. [addedWeightLb] > 0 surfaces an
     *  optional load control (e.g. weighted plank). */
    data class Time(val targetSeconds: Int, val addedWeightLb: Double = 0.0) : GoalSource
}

/** How a set is logged and displayed — derived from a slot's [Prescription]
 *  or its catalog [GoalSource], never stored separately. */
enum class TrackingType { WEIGHTED, REPS, TIMED }

/** The tracking type implied by this entry's [goal] (§2.1). */
val ExerciseEntry.tracking: TrackingType
    get() = when (goal) {
        is GoalSource.Std, is GoalSource.FracOfStd, is GoalSource.Flat -> TrackingType.WEIGHTED
        is GoalSource.Reps -> TrackingType.REPS
        is GoalSource.Time -> TrackingType.TIMED
    }

/** The authored target takes precedence over the catalog entry's tracking. */
val Prescription.tracking: TrackingType
    get() = when (target) {
        is PrescribedTarget.LoadReps -> TrackingType.WEIGHTED
        is PrescribedTarget.Reps, is PrescribedTarget.RepsInTime -> TrackingType.REPS
        is PrescribedTarget.Hold -> TrackingType.TIMED
    }

data class ExerciseEntry(
    val id: String,
    val name: String,
    val pattern: MovementPattern,
    val equipment: List<Equipment>,
    val perHand: Boolean,
    val goal: GoalSource,
    val subRank: Int,
    /** Used only when this entry's own pattern has no substitution candidates. */
    val fallbackPattern: MovementPattern? = null,
    /** Weighted counterpart of this bodyweight/lighter movement, declared only on
     *  the unloaded side (e.g. pullup → weighted_pullup). The reverse lookup is
     *  derived at library init — see [buildWeightedPairIndex]. */
    val weightedPairId: String? = null,
    /**
     * What a lifter calls this at the gym, when that differs from [name]: a
     * conventional deadlift is "deadlift", a Romanian one is "RDL". Set only
     * where the short form is what people actually say — most entries leave it
     * null, and nothing here is an abbreviation someone invented for the app.
     *
     * Display only, and only on the watch, where a label band is an arc a few
     * words wide. The phone, history, exports and Health Connect all keep [name].
     */
    val shortName: String? = null,
    /** May anchor a plan as a main lift: conversion makes a straight slot of this entry MAIN (design §4.4), and
     *  setup's Your lifts lists these (PR 6). Custom exercises never are. */
    val mainCapable: Boolean = false,
    /** A primary credits 1 per working set, a secondary 0.5 (design §3.1 invariant 5). */
    val primary: Set<Muscle> = emptySet(),
    val secondary: Set<Muscle> = emptySet(),
    /** One set covers both sides; the set timer runs the target once per side (PR 6). */
    val eachSide: Boolean = false,
    /** The owner's set count and rest for this exercise wherever the planner places it outside a main slot. */
    val dose: Dose? = null,
)

data class Dose(val sets: Int, val restSeconds: Int)

fun ExerciseEntry.availableWith(equipment: Set<Equipment>, excluded: Set<String> = emptySet()): Boolean =
    id !in excluded && this.equipment.all { it in equipment }

/** What [sets] working sets of this entry credit each muscle: a primary 1 per set, a secondary 0.5
 *  (design §3.1 invariant 5). */
fun ExerciseEntry.muscleCredit(sets: Int): Map<Muscle, Double> = buildMap {
    for (muscle in primary) put(muscle, getOrDefault(muscle, 0.0) + sets)
    for (muscle in secondary) put(muscle, getOrDefault(muscle, 0.0) + sets * 0.5)
}
