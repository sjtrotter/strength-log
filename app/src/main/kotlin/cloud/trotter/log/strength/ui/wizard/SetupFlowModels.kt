package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.text.UiText

/** Screens in canonical order; [SetupFlowStateBuilder.steps] picks a draft's subset. Stored by name. */
enum class SetupStep { GOAL, ORDER, SUB_GOAL, RUN_GOAL, WEEK, EQUIPMENT, ABOUT, LIFTS, RECENT, EVENT, WHERE, TOGETHER, PLAN }
enum class StepRow { BAR, DUMBBELL, STACK }
data class StepRowState(val row: StepRow, val optionsLb: List<Double>, val selected: Int)

/** [row] indexes Planner.mainChoices; a null [selectedId] means the lift was dropped on Your plan. */
data class LiftRowState(val row: Int, val choices: List<String>, val selectedId: String?)
data class RecentRowState(val exerciseId: String, val name: String, val perHand: Boolean,
    val weightLb: Double?, val reps: Int?, val effort: Effort)
data class PlanDayRow(val dayId: String, val title: String, val rest: Boolean, val mainName: String?,
    val mainLoadLb: Double?, val lifts: Int, val minutes: Int, val overMinutes: Int?, val cardio: CardioBlock? = null)

sealed interface PlanWhy {
    data class Floor(val dayId: String, val minutes: Int) : PlanWhy
    data class Trimmed(val dayId: String, val minutes: Int) : PlanWhy
    data class OneSet(val dayId: String, val minutes: Int) : PlanWhy
    data class Over(val dayId: String, val minutes: Int) : PlanWhy
    data object BalanceDays : PlanWhy
    data class BalanceOffered(val liftingDays: Int) : PlanWhy
    data object RepsLeft : PlanWhy
    data object Calibrating : PlanWhy
    data class LightWeek(val everyCycles: Int) : PlanWhy
    data class RunCapped(val dayId: String) : PlanWhy
    data object DateTooClose : PlanWhy
    data class GoalCheck(val predictedSeconds: Int) : PlanWhy
    data object Ladder : PlanWhy
    data class NoRun(val minutes: Int) : PlanWhy
    data object Minutes : PlanWhy
}

sealed interface PlanPreviewUi {
    data object Loading : PlanPreviewUi
    data object Failed : PlanPreviewUi
    data object Empty : PlanPreviewUi
    data class Ready(
        val name: String,
        val rows: List<PlanDayRow>,
        val droppable: List<String>,
        val longerAvailable: Boolean,
        val over: Boolean,
        val balanceOffer: Int?,
        val balanceAdded: Int?,
        val why: List<PlanWhy>,
    ) : PlanPreviewUi
}

data class SetupRestoreState(val offered: Boolean = false, val inFlight: Boolean = false, val error: UiText? = null)

data class SetupFlowUiState(
    val initialized: Boolean = false,
    val firstRun: Boolean = false,
    val draft: SetupDraft = SetupDraft(),
    val step: SetupStep = SetupStep.GOAL,
    val stepNumber: Int = 1,
    val stepCount: Int = 6,
    val preset: EquipmentPreset? = EquipmentPreset.FULL_GYM,
    val showEquipmentList: Boolean = false,
    val stepRows: List<StepRowState> = emptyList(),
    val lifts: List<LiftRowState> = emptyList(),
    val recent: List<RecentRowState> = emptyList(),
    val plan: PlanPreviewUi = PlanPreviewUi.Loading,
    val restore: SetupRestoreState = SetupRestoreState(),
    val finishInFlight: Boolean = false,
    val finishError: UiText? = null,
    val isComplete: Boolean = false,
) {
    val isFirstStep: Boolean get() = stepNumber == 1
    val canStart: Boolean get() = initialized && plan is PlanPreviewUi.Ready && !finishInFlight && !restore.inFlight
}

/** Callbacks forwarded to [SetupFlowViewModel]. */
data class SetupFlowActions(
    val onBranch: (PlanBranch) -> Unit = {}, val onCardioFirst: (Boolean) -> Unit = {},
    val onRunGoal: (RunGoal) -> Unit = {}, val onRunExperience: (RunExperience) -> Unit = {},
    val onRunningInjury: (Boolean) -> Unit = {}, val onRunEvent: (RunEvent) -> Unit = {},
    val onEventDate: (Long?) -> Unit = {}, val onGoalSeconds: (Int?) -> Unit = {},
    val onTest: (FitnessTest) -> Unit = {}, val onTestScore: (Int?) -> Unit = {},
    val onRecent: (Int?, Int?) -> Unit = { _, _ -> }, val onLongestByDistance: (Boolean) -> Unit = {},
    val onLongest: (Int?) -> Unit = {}, val onActivity: (CardioActivity) -> Unit = {},
    val onMarathonBest: (Int?) -> Unit = {}, val onTogether: (BothAnswers.Together) -> Unit = {},
    val onStrengthBlocks: (Int) -> Unit = {},
    val onNext: () -> Unit = {}, val onBack: () -> Unit = {}, val onExit: () -> Unit = {},
    val onSubGoal: (LiftGoal) -> Unit = {}, val onDays: (Int) -> Unit = {}, val onMinutes: (Int) -> Unit = {},
    val onPreset: (EquipmentPreset) -> Unit = {}, val onChooseExactly: () -> Unit = {},
    val onToggleEquipment: (Equipment) -> Unit = {}, val onUnit: (WeightUnit) -> Unit = {},
    val onStepOption: (StepRow, Int) -> Unit = { _, _ -> }, val onExperience: (LiftExperience) -> Unit = {},
    val onOlderAdult: (Boolean) -> Unit = {}, val onMain: (Int, String) -> Unit = { _, _ -> },
    val onRecentWeight: (String, Double?) -> Unit = { _, _ -> }, val onRecentReps: (String, Int?) -> Unit = { _, _ -> },
    val onRecentEffort: (String, Effort) -> Unit = { _, _ -> }, val onBalanceDays: (Boolean) -> Unit = {},
    val onDropLift: (String) -> Unit = {}, val onLongerSessions: () -> Unit = {}, val onRetryPreview: () -> Unit = {},
    val onReviewAnswers: () -> Unit = {}, val onRestoreFromBackup: () -> Unit = {},
)
