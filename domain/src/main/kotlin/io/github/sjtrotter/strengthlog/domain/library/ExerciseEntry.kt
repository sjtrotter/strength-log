package io.github.sjtrotter.strengthlog.domain.library

import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.StandardLift

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

/** How a set is logged and displayed — derived from [GoalSource], never stored
 *  separately (SSOT: one declaration decides both progression and display). */
enum class TrackingType { WEIGHTED, REPS, TIMED }

/** The tracking type implied by this entry's [goal] (§2.1). */
val ExerciseEntry.tracking: TrackingType
    get() = when (goal) {
        is GoalSource.Std, is GoalSource.FracOfStd, is GoalSource.Flat -> TrackingType.WEIGHTED
        is GoalSource.Reps -> TrackingType.REPS
        is GoalSource.Time -> TrackingType.TIMED
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
)
