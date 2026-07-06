package io.github.sjtrotter.strengthlog.domain.library

import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.StandardLift

/** Where an exercise's read-only GOAL weight comes from (spec §5). */
sealed interface GoalSource {
    /** Main-capable lift derived from a bodyweight-ratio standard.
     *  [tune] overrides the global [StrengthStandards.liftTune] when non-null
     *  (e.g. conventional deadlift takes the untrimmed 1.0 vs trap-bar's 0.82). */
    data class Std(val lift: StandardLift, val tune: Double? = null) : GoalSource

    /** Fraction of a named standard's main goal (e.g. RDL = 0.72 × squat). */
    data class FracOfStd(val fraction: Double, val lift: StandardLift) : GoalSource

    /** Fixed starting weight in lb (0 = bodyweight). */
    data class Flat(val weightLb: Double) : GoalSource
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
)
