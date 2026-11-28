package cloud.trotter.log.strength.ui.plan

import android.content.Context
import java.time.Clock
import java.time.LocalDate
import cloud.trotter.log.strength.domain.progression.LiftGoal
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.di.CivilDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.data.serialization.TestGoalJson
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
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
    private val historyRepository: HistoryRepository = repo.history,
    private val clock: Clock = Clock.systemDefaultZone(),
    @CivilDay private val today: Flow<LocalDate> = flowOf(LocalDate.now(clock)),
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
    private val cardioGoalDraft = savedState.getStateFlow<PlanGoalDraft?>(CARDIO_GOAL_DRAFT, null)
    private val sprintGoalDraft = savedState.getStateFlow<SprintGoalDraft?>(SPRINT_GOAL_DRAFT, null)
    private val testGoalDraft = savedState.getStateFlow<TestGoalDraft?>(TEST_GOAL_DRAFT, null)
    private val testHistory = historyRepository.testResultsFlow.map { results -> results.mapNotNull { it.toDomain() } }
    private val testContext = combine(today, testHistory, historyRepository.liftSetsFlow) { day, results, lifts -> Triple(day, results, lifts) }
    private val shuttleEntry = savedState.getStateFlow<String?>(SHUTTLE_ENTRY, null)
    private val cardioDialogs = combine(cardioGoalDraft, shuttleEntry, sprintGoalDraft) { draft, entry, sprint -> Triple(draft, entry, sprint) }
    private val cardioHistory = combine(historyRepository.cardioSessionsFlow, historyRepository.cardioSetResultsFlow) { sessions, sets ->
        sessions.map { session ->
            CardioActivity.entries.firstOrNull { it.name == session.activity } to sets[session.id].orEmpty()
        }
    }
    private val planId = checkNotNull(savedState.get<String>(Routes.PLAN_ID_ARG)).toLong()
    private val programState = plans.plansFlow.map { plans -> plans.firstOrNull { it.id == planId } }
        .flatMapLatest { plan -> combine(
            plans.programFor(planId), catalog.catalogFlow, settings.unitFlow, cardioHistory, testContext,
        ) { program, catalog, unit, cardio, (day, results, lifts) ->
            PlanScreenBuilder.buildUiState(plan, program, catalog, unit, cardio, day.toEpochDay(), results, lifts)
        } }
    val uiState: StateFlow<PlanUiState> = combine(programState, dialogState, goal, goalDraft, cardioDialogs) { program, draft, goalId, goalLb, cardio ->
        program.copy(
            goalDraft = cardio.first, shuttleEntry = cardio.second, sprintGoalDraft = cardio.third,
            goalDialog = goalId?.let { program.liftGoalDialog(it)?.copy(draftLb = goalLb) },
            menuDayId = draft.menuDayId, dialog = draft.dialog,
            dialogDayId = draft.dialogDayId, dayTitleDraft = draft.dayTitleDraft,
        )
    }.combine(testGoalDraft) { state, draft -> state.copy(testGoalDraft = draft) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlanUiState(loading = true))

    fun openTestGoal() {
        val ui = uiState.value.testGoal ?: return
        savedState[TEST_GOAL_DRAFT] = TestGoalDraft(ui.rows.associate {
            it.event.ref.eventId to if (it.timed) ClockText.minutesSeconds(it.event.required)
                else it.event.required.toString()
        }, ui.goal.byEpochDay)
    }

    fun changeTestGoalDraft(draft: TestGoalDraft) { savedState[TEST_GOAL_DRAFT] = draft }

    fun saveTestGoal() {
        val draft = testGoalDraft.value ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                val stored = TestGoalJson.decode(plans.plansFlow.first().firstOrNull { it.id == planId }?.testGoalJson)
                    ?: return@withLock
                val rows = uiState.value.testGoal?.rows ?: return@withLock
                val goal = draft.goal(stored, rows) ?: return@withLock
                plans.setTestGoal(planId, goal)
                plans.setGoalDate(planId, goal.byEpochDay)
                dismissTestGoal()
            }
        }
    }

    fun removeTestGoal() {
        viewModelScope.launch {
            mutationLock.withLock {
                plans.setTestGoal(planId, null)
                dismissTestGoal()
            }
        }
    }

    fun dismissTestGoal() { savedState[TEST_GOAL_DRAFT] = null }

    fun openGoal() {
        val sprint = uiState.value.sprintGoal?.goal
        savedState[SPRINT_GOAL_DRAFT] = sprint?.let {
            SprintGoalDraft(it.targetCentis?.let(ClockText::centis).orEmpty(), it.byEpochDay)
        }
        savedState[CARDIO_GOAL_DRAFT] = if (sprint == null) PlanGoalDraft.from(uiState.value.goal?.goal) else null
        savedState[GOAL] = null
        savedState[GOAL_DRAFT] = null
    }

    fun changeSprintGoalDraft(draft: SprintGoalDraft) { savedState[SPRINT_GOAL_DRAFT] = draft }

    fun saveSprintGoal() {
        val draft = sprintGoalDraft.value ?: return
        val target = if (draft.targetText.isBlank()) null else ClockText.parseCentis(draft.targetText) ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                val stored = PlanGoalJson.decode(plans.plansFlow.first().firstOrNull { it.id == planId }?.goalJson)
                    as? PlanGoal.Sprint ?: return@withLock
                plans.setGoal(planId, stored.copy(targetCentis = target, byEpochDay = draft.byEpochDay))
                dismissGoal()
            }
        }
    }

    fun changeGoalDraft(draft: PlanGoalDraft) {
        savedState[CARDIO_GOAL_DRAFT] = draft.edited(savedState.get<PlanGoalDraft>(CARDIO_GOAL_DRAFT))
    }

    fun removeGoal() {
        viewModelScope.launch {
            mutationLock.withLock {
                plans.setGoal(planId, null)
                dismissGoal()
            }
        }
    }

    fun openShuttleEntry() {
        if (uiState.value.goal?.goal is PlanGoal.Shuttle) savedState[SHUTTLE_ENTRY] = ""
    }

    fun changeShuttleEntry(text: String) {
        if (text.length <= 3 && text.all { it in '0'..'9' }) savedState[SHUTTLE_ENTRY] = text
    }

    fun saveShuttleEntry() {
        val count = shuttleEntry.value?.toIntOrNull()?.takeIf { it in 0..999 } ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                plans.logShuttleAttempt(planId, count)
                dismissShuttleEntry()
            }
        }
    }

    fun dismissShuttleEntry() { savedState[SHUTTLE_ENTRY] = null }

    private fun PlanUiState.liftGoalDialog(id: String): PlanGoalDialog? {
        goals.firstOrNull { it.exerciseId == id }?.let {
            return PlanGoalDialog(id, it.name, it.perHand, it.goalLb, hasGoal = it.goalLb != null)
        }
        return testLifts.firstOrNull { it.exerciseId == id }?.let {
            PlanGoalDialog(id, it.name, ExerciseLibrary.get(id).perHand, it.goalLb, hasGoal = it.goalLb != null)
        }
    }

    fun openGoal(exerciseId: String) {
        val row = uiState.value.liftGoalDialog(exerciseId) ?: return
        savedState[SPRINT_GOAL_DRAFT] = null
        savedState[CARDIO_GOAL_DRAFT] = null
        savedState[GOAL] = exerciseId
        savedState[GOAL_DRAFT] = row.draftLb
    }

    fun setGoalDraft(lb: Double?) { savedState[GOAL_DRAFT] = lb }

    fun saveGoal() {
        cardioGoalDraft.value?.let { draft ->
            if (uiState.value.sprintGoal != null) return
            val next = draft.goal() ?: return
            viewModelScope.launch {
                mutationLock.withLock {
                    if (PlanGoalJson.decode(plans.plansFlow.first().firstOrNull { it.id == planId }?.goalJson) is PlanGoal.Sprint) return@withLock
                    plans.setGoal(planId, next)
                    if (uiState.value.testRun != null) plans.setGoalDate(planId, next.byEpochDay)
                    dismissGoal()
                }
            }
            return
        }
        val id = goal.value ?: return
        val draft = goalDraft.value?.takeIf { it.isFinite() && it > 0.0 && it <= PlanRecipeJson.MAX_GOAL_LB } ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                plans.setPlanGoal(planId, id, draft)
                dismissGoal()
            }
        }
    }

    fun removeLiftGoal() {
        val id = goal.value ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                plans.setPlanGoal(planId, id, null)
                dismissGoal()
            }
        }
    }

    fun dismissGoal() {
        savedState[SPRINT_GOAL_DRAFT] = null
        savedState[CARDIO_GOAL_DRAFT] = null
        savedState[GOAL] = null
        savedState[GOAL_DRAFT] = null
    }

    fun setAutoApply(on: Boolean) { viewModelScope.launch { plans.setAutoApply(planId, on) } }
    fun setDefaultsFor(goal: LiftGoal) {
        viewModelScope.launch { plans.setDefaultsFor(planId, goal) }
    }
    fun setLightWeek(cycles: Int?) { viewModelScope.launch { plans.setLightWeek(planId, cycles) } }
    fun setRunCap(on: Boolean) { viewModelScope.launch { plans.setRunCap(planId, on) } }

    fun setOfferStrengthIncreases(on: Boolean) {
        viewModelScope.launch { plans.setOfferStrengthIncreases(planId, on) }
    }

    fun setOfferCardioIncreases(on: Boolean) {
        viewModelScope.launch { plans.setOfferCardioIncreases(planId, on) }
    }

    fun openDayMenu(dayId: String?) { savedState[MENU] = dayId }

    fun addDay() {
        val id = uiState.value.planId ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                val days = plans.programFor(id).first().days
                val letter = ('A'..'Z').firstOrNull { candidate -> days.none { it.id == candidate.toString() } }
                    ?: return@withLock
                repo.addDay(id, context.getString(R.string.authored_day_title, letter.toString()))
            }
        }
    }

    fun addRest() {
        val id = uiState.value.planId ?: return
        viewModelScope.launch {
            mutationLock.withLock { repo.addRestDay(id, context.getString(R.string.rest_day_title)) }
        }
    }

    fun moveDay(dayId: String, up: Boolean) {
        val id = uiState.value.planId ?: return
        savedState[MENU] = null
        viewModelScope.launch {
            mutationLock.withLock { repo.moveDay(id, dayId, up) }
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
        const val TEST_GOAL_DRAFT = "plan_test_goal_draft"
        const val SPRINT_GOAL_DRAFT = "plan_sprint_goal_draft"
        const val CARDIO_GOAL_DRAFT = "plan_goal_draft"
        const val SHUTTLE_ENTRY = "plan_shuttle_entry"
        const val GOAL = "plan_goal_exercise"
        const val GOAL_DRAFT = "plan_goal_draft_lb"
        const val DIALOG = "plan_day_dialog"
        const val DAY = "plan_dialog_day"
        const val PLAN = "plan_dialog_plan"
        const val TITLE = "plan_day_title"
        const val MENU = "plan_day_menu"
    }
}
