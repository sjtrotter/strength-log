package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.standards.StrengthStandards

/** Seeds the initial ACTUAL log rows from a GOAL (spec §4). */
object SetSeeder {

    /** For reps in time, the window lives in the prescription; the log holds the count. */
    fun seedPrescribed(sets: Int, p: Prescription, structure: SetStructure = SetStructure.STRAIGHT): List<LoggedSet> =
        when (val target = p.target) {
            is PrescribedTarget.LoadReps -> shape(structure, target.lb, target.reps, sets)
            is PrescribedTarget.Reps -> List(sets) { LoggedSet(0.0, target.reps, SetKind.WORK) }
            is PrescribedTarget.Hold -> List(sets) { LoggedSet(target.addedLb, 0, SetKind.WORK, seconds = target.seconds) }
            is PrescribedTarget.RepsInTime -> List(sets) { LoggedSet(0.0, 0, SetKind.WORK) }
        }

    private const val ACCESSORY_REPS = 10

    /**
     * Type-aware seeding routed by the resolved [GoalTarget] (§2.2). WEIGHTED
     * reuses the exact weighted sequence; REPS/TIMED seed `targetSets` all-WORK
     * rows at their target, so the TOP-keyed cascade is unreachable for them by
     * construction.
     */
    fun seed(pe: ProgramExercise, target: GoalTarget, cfg: LifterConfig): List<LoggedSet> =
        when (target) {
            is GoalTarget.Weight -> seed(pe, target.lb, cfg)
            is GoalTarget.Reps ->
                List(pe.targetSets) { LoggedSet(0.0, target.reps, SetKind.WORK) }
            is GoalTarget.Time ->
                List(pe.targetSets) { LoggedSet(target.addedLb, 0, SetKind.WORK, seconds = target.seconds) }
        }

    fun seed(pe: ProgramExercise, goal: Double, cfg: LifterConfig): List<LoggedSet> =
        shape(
            pe.structure,
            goal,
            if (pe.structure.hasTopSet) StrengthStandards.topSetReps(cfg.emphasis) else ACCESSORY_REPS,
            pe.targetSets,
        )

    fun shape(structure: SetStructure, topLb: Double, topReps: Int, targetSets: Int): List<LoggedSet> =
        when (structure) {
            SetStructure.STRAIGHT -> List(targetSets) { LoggedSet(topLb, topReps, SetKind.WORK) }
            SetStructure.RAMP_TOP_BACKOFF -> rampsAndTop(topLb, topReps) + LoggedSet(
                GoalCalculator.round5(topLb * StrengthStandards.BACKOFF),
                StrengthStandards.BACKOFF_REPS,
                SetKind.BACKOFF,
            )
            SetStructure.RAMP_TOP -> rampsAndTop(topLb, topReps)
        }

    private fun rampsAndTop(topLb: Double, topReps: Int): List<LoggedSet> {
        val ramps = StrengthStandards.RAMP_PCTS.mapIndexed { i, p ->
            LoggedSet(
                GoalCalculator.round5(topLb * p),
                if (i == StrengthStandards.RAMP_PCTS.lastIndex) 3 else 5,
                SetKind.RAMP,
            )
        }
        return ramps + LoggedSet(topLb, topReps, SetKind.TOP)
    }

    /**
     * Superset partner track: same row count as the primary, seeded from the
     * partner's own [GoalTarget], all [SetKind.WORK] (spec §4). Type-aware so a
     * reclassified REPS/TIMED partner never routes through the weighted path.
     */
    fun seedPartner(primaryCount: Int, target: GoalTarget): List<LoggedSet> =
        when (target) {
            is GoalTarget.Weight -> seedPartner(primaryCount, target.lb)
            is GoalTarget.Reps ->
                List(primaryCount) { LoggedSet(0.0, target.reps, SetKind.WORK) }
            is GoalTarget.Time ->
                List(primaryCount) { LoggedSet(target.addedLb, 0, SetKind.WORK, seconds = target.seconds) }
        }

    /**
     * Superset partner track: same row count as the primary, seeded from the
     * partner's own GOAL, all [SetKind.WORK] (spec §4).
     */
    fun seedPartner(primaryCount: Int, partnerGoal: Double): List<LoggedSet> =
        List(primaryCount) { LoggedSet(partnerGoal, ACCESSORY_REPS, SetKind.WORK) }
}
