package cloud.trotter.log.strength.ui.plan

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val plans: PlanRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val mutationLock = Mutex()
    private val dialog = savedState.getStateFlow<String?>(DIALOG, null)
    private val dialogDay = savedState.getStateFlow<String?>(DAY, null)
    private val title = savedState.getStateFlow(TITLE, "")
    private val menu = savedState.getStateFlow<String?>(MENU, null)
    private val dialogState = combine(dialog, dialogDay, title, menu) { kind, day, draft, menuDay ->
        PlanUiState(dialog = kind, dialogDayId = day, dayTitleDraft = draft, menuDayId = menuDay)
    }
    private val goal = savedState.getStateFlow<String?>(GOAL, null)
    private val goalDraft = savedState.getStateFlow<Double?>(GOAL_DRAFT, null)
    private val planId = checkNotNull(savedState.get<String>(Routes.PLAN_ID_ARG)).toLong()
    private val programState = plans.plansFlow.map { plans -> plans.firstOrNull { it.id == planId } }
        .flatMapLatest { plan -> combine(
            plans.programFor(planId), plans.wizardAnswersFor(planId), catalog.catalogFlow, settings.unitFlow,
        ) { program, answers, catalog, unit ->
            PlanScreenBuilder.buildUiState(plan, program, answers, catalog, unit)
        } }
    val uiState: StateFlow<PlanUiState> = combine(programState, dialogState, goal, goalDraft) { program, draft, goalId, goalLb ->
        program.copy(
            goalDialog = program.goals.firstOrNull { it.exerciseId == goalId }?.let { row ->
                PlanGoalDialog(row.exerciseId, row.name, row.perHand, goalLb,
                    row.calculated ?: row.display, row.calculated != null)
            },
            menuDayId = draft.menuDayId, dialog = draft.dialog,
            dialogDayId = draft.dialogDayId, dayTitleDraft = draft.dayTitleDraft,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlanUiState(loading = true))

    fun openGoal(exerciseId: String) {
        val row = uiState.value.goals.firstOrNull { it.exerciseId == exerciseId } ?: return
        savedState[GOAL] = exerciseId
        savedState[GOAL_DRAFT] = row.goalLb
    }

    fun setGoalDraft(lb: Double?) { savedState[GOAL_DRAFT] = lb }

    fun saveGoal() {
        val id = goal.value ?: return
        val draft = goalDraft.value?.takeIf { it.isFinite() && it > 0.0 && it <= PlanRecipeJson.MAX_GOAL_LB } ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                plans.setPlanGoal(planId, id, draft)
                dismissGoal()
            }
        }
    }

    fun useCalculatedGoal() {
        val id = goal.value ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                plans.setPlanGoal(planId, id, null)
                dismissGoal()
            }
        }
    }

    fun dismissGoal() {
        savedState[GOAL] = null
        savedState[GOAL_DRAFT] = null
    }

    fun setOfferStrengthIncreases(on: Boolean) {
        viewModelScope.launch { plans.setOfferStrengthIncreases(planId, on) }
    }

    fun setOfferCardioIncreases(on: Boolean) {
        viewModelScope.launch { plans.setOfferCardioIncreases(planId, on) }
    }

    fun setEmphasis(emphasis: GoalEmphasis) {
        viewModelScope.launch { plans.setPlanEmphasis(planId, emphasis) }
    }

    fun openDayMenu(dayId: String?) { savedState[MENU] = dayId }

    fun addDay() {
        val id = uiState.value.planId ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                val days = plans.programFor(id).first().days
                val letter = ('A'..'G').firstOrNull { candidate -> days.none { it.id == candidate.toString() } }
                    ?: return@withLock
                repo.addDay(id, context.getString(R.string.authored_day_title, letter.toString()))
            }
        }
    }

    fun renameDay(dayId: String) = openDialog("rename", dayId)
    fun removeDay(dayId: String) = openDialog("remove", dayId)

    private fun openDialog(kind: String, dayId: String) {
        val state = uiState.value
        val day = state.days.firstOrNull { it.dayId == dayId } ?: return
        savedState[PLAN] = state.planId
        savedState[DAY] = dayId
        savedState[TITLE] = day.title
        savedState[DIALOG] = kind
        savedState[MENU] = null
    }

    fun setDayTitleDraft(value: String) { savedState[TITLE] = value }

    fun dismissDayDialog() {
        savedState[DIALOG] = null
        savedState[DAY] = null
        savedState[PLAN] = null
        savedState[TITLE] = ""
    }

    fun saveDayDialog() {
        val id = savedState.get<Long>(PLAN) ?: return
        val dayId = dialogDay.value ?: return
        val kind = dialog.value ?: return
        val draft = title.value.trim()
        if (kind == "rename" && draft.length !in 1..30) return
        viewModelScope.launch {
            mutationLock.withLock {
                if (kind == "rename") repo.renameDay(id, dayId, draft) else repo.removeDay(id, dayId)
                dismissDayDialog()
            }
        }
    }

    private companion object {
        const val GOAL = "plan_goal_exercise"
        const val GOAL_DRAFT = "plan_goal_draft_lb"
        const val DIALOG = "plan_day_dialog"
        const val DAY = "plan_dialog_day"
        const val PLAN = "plan_dialog_plan"
        const val TITLE = "plan_day_title"
        const val MENU = "plan_day_menu"
    }
}
