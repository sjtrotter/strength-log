package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.progression.LiftGoal

object SetupFlowFixtures {
    val reference = SetupDraft(goal = LiftGoal.STRENGTH, days = 4, experience = LiftExperience.A_YEAR_OR_MORE,
        recent = mapOf("bb_back_squat" to RecentDraft(275.0, 5)))
    val overBudget = SetupDraft(goal = LiftGoal.STRENGTH, days = 1, minutes = 30,
        experience = LiftExperience.A_YEAR_OR_MORE, olderAdult = true)

    fun state(draft: SetupDraft = SetupDraft(), firstRun: Boolean = false): SetupFlowUiState {
        val answers = SetupFlowStateBuilder.answers(draft)
        val plan = if (SetupFlowStateBuilder.currentStep(draft) == SetupStep.PLAN) {
            val preview = SetupFlowStateBuilder.planPreview(Planner.plan(answers), answers)
            val goal = when (draft.goal) { LiftGoal.STRENGTH -> "Strength"; LiftGoal.MUSCLE -> "Muscle"; LiftGoal.GENERAL -> "General fitness" }
            if (preview is PlanPreviewUi.Ready) preview.copy(name = "$goal, ${draft.days} ${if (draft.days == 1) "day" else "days"}") else preview
        } else PlanPreviewUi.Loading
        return SetupFlowStateBuilder.build(draft, true, firstRun, plan, SetupRestoreState(offered = firstRun), false, null, false)
    }
}
