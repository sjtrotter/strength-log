package cloud.trotter.log.strength.ui.plan

import androidx.compose.runtime.Immutable
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.RecentRace
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.SprintAttempt
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DaySelection

@Immutable
data class PlanUiState(
    val name: String? = null,
    val loading: Boolean = false,
    val hasProgram: Boolean = false,
    val days: List<PlanDay> = emptyList(),
    val planId: Long? = null,
    val menuDayId: String? = null,
    val dialog: String? = null,
    val dialogDayId: String? = null,
    val dayTitleDraft: String = "",
    val goals: List<PlanLiftGoal> = emptyList(),
    val offerStrengthIncreases: Boolean = true,
    val offerCardioIncreases: Boolean = false,
    val unit: WeightUnit = WeightUnit.LB,
    val goalDialog: PlanGoalDialog? = null,
    val goal: PlanGoalUi? = null,
    val goalDraft: PlanGoalDraft? = null,
    val shuttleEntry: String? = null,
    val autoApply: Boolean = false,
    val defaultsFor: LiftGoal = LiftGoal.STRENGTH,
    val lightWeek: Int? = null,
    val runCap: Boolean = true,
    val runPhase: RunPhase? = null,
    val taper: Boolean = false,
    val sprintGoal: PlanSprintGoalUi? = null,
    val sprintGoalDraft: SprintGoalDraft? = null,
)

data class PlanSprintGoalUi(val goal: PlanGoal.Sprint, val best: SprintAttempt?)

data class PlanGoalUi(val goal: PlanGoal, val best: Int?, val met: Boolean,
                      val predictedSeconds: Int? = null, val predictedFrom: RecentRace? = null)

/** One main lift on the Plan screen's Goals section and its typed goal; [display] is null when it has none. */
data class PlanLiftGoal(val exerciseId: String, val name: String, val perHand: Boolean, val goalLb: Double?,
    val display: String?)

data class PlanGoalDialog(
    val exerciseId: String,
    val name: String,
    val perHand: Boolean,
    val draftLb: Double?,
    val hasGoal: Boolean,
)

data class PlanDay(
    val dayId: String,
    val dayIndex: Int,
    val title: String,
    val liftCount: Int,
    val setCount: Int,
    val isNext: Boolean,
    val cardioActivity: CardioActivity? = null,
    val cardioSetCount: Int = 0,
    val kind: ProgramDayKind = ProgramDayKind.STRENGTH,
    val beforeSprint: Boolean = false,
)

data class PlanActions(
    val onOpenDay: (DaySelection) -> Unit,
    val onOpenPlans: () -> Unit,
    val onSetUpProgram: () -> Unit,
    val onOfferStrengthChange: (Boolean) -> Unit = {},
    val onOfferCardioChange: (Boolean) -> Unit = {},
    val onAddDay: () -> Unit = {},
    val onAddRest: () -> Unit = {},
    val onMoveDay: (String, Boolean) -> Unit = { _, _ -> },
    val onDayMenu: (String?) -> Unit = {},
    val onRenameDay: (String) -> Unit = {},
    val onRemoveDay: (String) -> Unit = {},
    val onDayTitleDraft: (String) -> Unit = {},
    val onSaveDayDialog: () -> Unit = {},
    val onDismissDayDialog: () -> Unit = {},
    val onOpenGoal: (String) -> Unit = {},
    val onGoalDraft: (Double?) -> Unit = {},
    val onSaveGoal: () -> Unit = {},
    val onRemoveLiftGoal: () -> Unit = {},
    val onDismissGoal: () -> Unit = {},
    val onOpenCardioGoal: () -> Unit = {},
    val onSprintGoalDraft: (SprintGoalDraft) -> Unit = {},
    val onSaveSprintGoal: () -> Unit = {},
    val onCardioGoalDraft: (PlanGoalDraft) -> Unit = {},
    val onRemoveGoal: () -> Unit = {},
    val onOpenShuttleEntry: () -> Unit = {},
    val onShuttleEntry: (String) -> Unit = {},
    val onSaveShuttleEntry: () -> Unit = {},
    val onDismissShuttleEntry: () -> Unit = {},
    val onAutoApplyChange: (Boolean) -> Unit = {},
    val onDefaultsForChange: (LiftGoal) -> Unit = {},
    val onLightWeekChange: (Int?) -> Unit = {},
    val onRunCapChange: (Boolean) -> Unit = {},
)
