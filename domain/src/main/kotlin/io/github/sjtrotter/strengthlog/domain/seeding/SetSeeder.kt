package io.github.sjtrotter.strengthlog.domain.seeding

import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.standards.StrengthStandards

/** Seeds the initial ACTUAL log rows from a GOAL (spec §4). */
object SetSeeder {

    private const val ACCESSORY_REPS = 10

    fun seed(pe: ProgramExercise, goal: Double, cfg: LifterConfig): List<LoggedSet> {
        if (!pe.isMain) {
            return List(pe.targetSets) { LoggedSet(goal, ACCESSORY_REPS, SetKind.WORK) }
        }
        val topReps = StrengthStandards.topSetReps(cfg.emphasis)
        val ramps = StrengthStandards.RAMP_PCTS.mapIndexed { i, p ->
            LoggedSet(
                GoalCalculator.round5(goal * p),
                if (i == StrengthStandards.RAMP_PCTS.lastIndex) 3 else 5, // last ramp is a lighter triple
                SetKind.RAMP,
            )
        }
        return ramps +
            LoggedSet(goal, topReps, SetKind.TOP) +
            LoggedSet(
                GoalCalculator.round5(goal * StrengthStandards.BACKOFF),
                StrengthStandards.BACKOFF_REPS,
                SetKind.BACKOFF,
            )
    }

    /**
     * Superset partner track: same row count as the primary, seeded from the
     * partner's own GOAL, all [SetKind.WORK] (spec §4).
     */
    fun seedPartner(primaryCount: Int, partnerGoal: Double): List<LoggedSet> =
        List(primaryCount) { LoggedSet(partnerGoal, ACCESSORY_REPS, SetKind.WORK) }
}
