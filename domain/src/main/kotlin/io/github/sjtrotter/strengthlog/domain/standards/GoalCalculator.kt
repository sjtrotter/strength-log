package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.StandardLift

/**
 * The one display-target shape every surface formats a GOAL from (§2.2). Which
 * variant an entry yields is decided solely by [GoalCalculator.targetFor]'s
 * exhaustive `when`, so no surface can invent its own "0 lb × 60" rendering.
 */
sealed interface GoalTarget {
    data class Weight(val lb: Double, val perHand: Boolean) : GoalTarget
    data class Reps(val reps: Int) : GoalTarget
    data class Time(val seconds: Int, val addedLb: Double) : GoalTarget
}

object GoalCalculator {
    fun round5(w: Double) = maxOf(5.0, Math.round(w / 5.0) * 5.0)

    fun goalForMain(
        std: StandardLift,
        perHand: Boolean,
        cfg: LifterConfig,
        tune: Double = StrengthStandards.liftTune[std] ?: 1.0,
    ): Double {
        val ratio = StrengthStandards.ratios[cfg.level]!![std]!!
        val oneRm = cfg.bodyweightLb * ratio * tune * StrengthStandards.ageDerate(cfg.age)
        var top = oneRm * StrengthStandards.maintenanceFactor(cfg.emphasis)
        if (perHand) top /= 2.0
        return round5(top)
    }

    /** Read-only GOAL for a program slot, resolved via the exercise library. */
    fun goalFor(pe: ProgramExercise, cfg: LifterConfig): Double =
        goalFor(ExerciseLibrary.get(pe.exerciseId), cfg)

    fun goalFor(entry: ExerciseEntry, cfg: LifterConfig): Double =
        when (val src = entry.goal) {
            // perHand asymmetry: Std halves the total (pinned by incline_db = 75/hand),
            // but a FracOfStd fraction is already calibrated per hand — db_bench's
            // 0.4×BENCH is exactly half of incline_bb's 0.8×BENCH total. Never halve twice.
            is GoalSource.Std ->
                if (src.tune != null) goalForMain(src.lift, entry.perHand, cfg, src.tune)
                else goalForMain(src.lift, entry.perHand, cfg)
            is GoalSource.FracOfStd ->
                round5(src.fraction * goalForMain(src.lift, perHand = false, cfg))
            is GoalSource.Flat -> src.weightLb
            // targetFor is the router for these; goalFor is never called on them.
            is GoalSource.Reps -> error("goalFor called on a REPS entry: ${entry.id}")
            is GoalSource.Time -> error("goalFor called on a TIMED entry: ${entry.id}")
        }

    /**
     * The read-only display target for any entry — the single SSOT router that
     * turns each [GoalSource] variant into a [GoalTarget]. Weighted sources reuse
     * [goalFor] verbatim; REPS/TIMED read their declared targets. Every GOAL
     * chip, collapsed summary, and watch label formats from this.
     */
    fun targetFor(entry: ExerciseEntry, cfg: LifterConfig): GoalTarget =
        when (val src = entry.goal) {
            is GoalSource.Std, is GoalSource.FracOfStd, is GoalSource.Flat ->
                GoalTarget.Weight(goalFor(entry, cfg), entry.perHand)
            is GoalSource.Reps -> GoalTarget.Reps(src.targetReps)
            is GoalSource.Time -> GoalTarget.Time(src.targetSeconds, src.addedWeightLb)
        }
}
