package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.StandardLift

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
            is GoalSource.Std ->
                if (src.tune != null) goalForMain(src.lift, entry.perHand, cfg, src.tune)
                else goalForMain(src.lift, entry.perHand, cfg)
            is GoalSource.FracOfStd -> {
                var v = src.fraction * goalForMain(src.lift, perHand = false, cfg)
                if (entry.perHand) v /= 2.0
                round5(v)
            }
            is GoalSource.Flat -> src.weightLb
        }
}
