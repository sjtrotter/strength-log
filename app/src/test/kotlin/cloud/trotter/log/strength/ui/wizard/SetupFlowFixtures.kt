package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.progression.LiftGoal

object SetupFlowFixtures {
    val reference = SetupDraft(goal = LiftGoal.STRENGTH, days = 4, experience = LiftExperience.A_YEAR_OR_MORE,
        recent = mapOf("bb_back_squat" to RecentDraft(275.0, 5)))
    val overBudget = SetupDraft(goal = LiftGoal.STRENGTH, days = 1, minutes = 30,
        experience = LiftExperience.A_YEAR_OR_MORE, olderAdult = true)

    val run = SetupDraft(branch = PlanBranch.RUN)
    val both = SetupDraft(branch = PlanBranch.BOTH)
    val half = run.copy(runGoal = RunGoal.HALF_MARATHON, runExperience = RunExperience.SOME,
        days = 4, longestSeconds = 3600, goalSeconds = 7200, eventDate = 21000, recentMetres = 5000, recentSeconds = 1500)
    val test = run.copy(runGoal = RunGoal.FITNESS_TEST, goalSeconds = 960, eventDate = 21000)
    val ultra = half.copy(runGoal = RunGoal.ULTRA, activity = CardioActivity.TRAIL_RUN, marathonBestSeconds = 14400)
    val noRun = both.copy(runGoal = RunGoal.HALF_MARATHON, eventDate = 21000, minutes = 30)

    fun state(draft: SetupDraft = SetupDraft(), firstRun: Boolean = false): SetupFlowUiState {
        val answers = SetupFlowStateBuilder.answers(draft)
        val plan = if (SetupFlowStateBuilder.currentStep(draft) == SetupStep.PLAN) {
            val preview = SetupFlowStateBuilder.planPreview(Planner.plan(answers), answers)
            val liftGoal = when (draft.goal) { LiftGoal.STRENGTH -> "Strength"; LiftGoal.MUSCLE -> "Muscle"; LiftGoal.GENERAL -> "General fitness" }
            val runGoal = when (draft.runGoal) {
                RunGoal.FIRST_5K -> "First 5K"
                RunGoal.FIVE_TO_TEN_K -> "5K to 10K"
                RunGoal.HALF_MARATHON -> "Half marathon"
                RunGoal.MARATHON -> "Marathon"
                RunGoal.ULTRA -> "Ultra"
                RunGoal.MIDDLE_DISTANCE -> "Middle distance"
                RunGoal.FITNESS_TEST -> "Fitness test"
                RunGoal.CARDIO_FITNESS -> "Just cardio fitness"
            }
            val goal = when (draft.branch) {
                PlanBranch.LIFT -> liftGoal
                PlanBranch.RUN -> runGoal
                PlanBranch.BOTH -> "Lifting and $runGoal"
                PlanBranch.SPRINT -> error("Sprint setup is deferred")
            }
            if (preview is PlanPreviewUi.Ready) preview.copy(name = "$goal, ${draft.days} ${if (draft.days == 1) "day" else "days"}") else preview
        } else PlanPreviewUi.Loading
        return SetupFlowStateBuilder.build(draft, true, firstRun, plan, SetupRestoreState(offered = firstRun), false, null, false)
    }
}
