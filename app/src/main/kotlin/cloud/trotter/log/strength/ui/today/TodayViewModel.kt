package cloud.trotter.log.strength.ui.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.LoggedSlot
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.effectiveCardio
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.log.JournalBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Today screen ViewModel (#121, MVVM/UDF). Holds no working truth of its own:
 * every render value derives from repository flows, and every formatting
 * decision is delegated to the pure [TodayScreenBuilder]. Only the plan
 * picker's visibility is saved; selection goes through the repository.
 * Today never seeds a log and never writes one.
 *
 * Seeding stays [cloud.trotter.log.strength.ui.day.DayViewModel]'s job, but the
 * *rules* are `:domain`'s, so Today can run them without writing anything: a day
 * with no log rows yet is previewed at [seededRowCount], the count the day
 * screen will show the moment it is opened. A preview that promised a different
 * number than the workout it opens would be a lie, and this screen exists to
 * tell the truth about what is next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val civilTimeSource: CivilTimeSource,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val planPickerOpen = savedState.getStateFlow(PLAN_PICKER_OPEN, false)

    // Resolve selection once; every read contributing to the day uses this identity.
    private val contextFlow = repo.selectedPlanFlow.flatMapLatest { plan ->
        if (plan == null) flowOf(null) else combine(
            repo.programFor(plan.id),
            repo.catalogFlow,
            repo.wizardAnswersFor(plan.id),
        ) { program, catalog, answers ->
            val dayId = plan.suggestedDayId?.takeIf { id -> program.days.any { it.id == id } }
                ?: program.strengthDays.firstOrNull()?.id ?: program.days.firstOrNull()?.id
            TodayContext(plan.id, program, dayId, catalog, answers.config, answers.cardio)
        }
    }

    private val dayState = contextFlow.flatMapLatest { ctx ->
        val dayId = ctx?.dayId
        if (ctx == null || dayId == null) {
            // [contextFlow] picks the day from the same program emission, so a
            // null day here means the program has no days — the answer, not a
            // pause. Loading is the state before this flow emits at all, which
            // is [uiState]'s initial value below (#127).
            flowOf(TodayUiState(planId = ctx?.planId))
        } else {
            civilTimeSource.civilTime.flatMapLatest { now ->
                combine(
                    repo.slotsFor(ctx.planId, dayId),
                    repo.logsFor(ctx.planId, dayId, flowOf(now.date)),
                    repo.sessionSummariesFor(ctx.planId),
                    repo.topSetHistoryFlow,
                    repo.lastSessionAtFor(ctx.planId),
                ) { slots, logs, sessions, topSets, lastSessionAt ->
                    build(ctx, dayId, slots, logs, sessions, topSets, lastSessionAt, now)
                }
            }
        }
    }

    val uiState: StateFlow<TodayUiState> = combine(
        dayState,
        repo.planSummariesFlow,
        planPickerOpen,
        repo.cardioSessionsFlow,
        civilTimeSource.civilTime,
    ) { state, plans, pickerOpen, sessions, now ->
        val active = plans.filterNot { it.archived }
        state.copy(
            cardio = state.cardio?.copy(loggedSeconds = sessions.firstOrNull {
                it.planId == state.planId && it.dayId == state.dayId &&
                    java.time.Instant.ofEpochMilli(it.completedAt).atZone(now.zone).toLocalDate() == now.date
            }?.seconds),
            standaloneCardio = state.standaloneCardio?.let { cardio ->
                cardio.copy(loggedSeconds = sessions.firstOrNull {
                    it.planId == state.planId && it.dayId == cardio.dayId &&
                        java.time.Instant.ofEpochMilli(it.completedAt).atZone(now.zone).toLocalDate() == now.date
                }?.seconds)
            },
            planName = plans.firstOrNull { it.id == state.planId }?.name,
            activePlans = active,
            planPickerOpen = pickerOpen && active.size > 1,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState(loading = true))

    fun showPlanPicker() {
        if (uiState.value.activePlans.size > 1) savedState[PLAN_PICKER_OPEN] = true
    }

    fun dismissPlanPicker() {
        savedState[PLAN_PICKER_OPEN] = false
    }

    fun selectPlan(id: Long) {
        viewModelScope.launch {
            repo.selectPlan(id)
            dismissPlanPicker()
        }
    }

    private fun build(
        ctx: TodayContext,
        dayId: String,
        slots: List<ProgramSlot>,
        logs: List<LoggedSlot>,
        sessions: List<SessionSummaryRow>,
        topSets: List<TopSetRow>,
        lastSessionAt: Long?,
        now: CivilTime,
    ): TodayUiState {
        val day = ctx.program.days.first { it.id == dayId }
        val dayIndex = ctx.program.strengthDays.indexOfFirst { it.id == dayId }
        val standalone = ctx.program.cardioDays.firstOrNull()
        // Rounds, the same count the widget, the watch and the session receipt
        // show — see [Slot.isRound] for why a superset partner isn't one.
        val mainLogs = logs.filter { Slot.isRound(it.slot) }.associateBy { it.programExerciseId }
        val rowsBySlot = slots.associate { slot ->
            slot.programExerciseId to mainLogs[slot.programExerciseId]?.sets
        }
        val countBySlot = slots.associate { slot ->
            slot.programExerciseId to (rowsBySlot[slot.programExerciseId]?.size ?: seededRowCount(slot, ctx))
        }
        val totalSets = countBySlot.values.sum()
        val doneSets = slots.sumOf { slot -> rowsBySlot[slot.programExerciseId]?.count { it.done } ?: 0 }

        return TodayUiState(
            planId = ctx.planId,
            hasProgram = true,
            dayId = dayId,
            dayIndex = dayIndex,
            dayTitle = day.title,
            coachLine = if (doneSets == 0) {
                TodayScreenBuilder.coachLine(
                    lastSessionAt?.let(java.time.Instant::ofEpochMilli),
                    now.instant,
                    now.zone,
                )
            } else {
                TodayScreenBuilder.progressLine(doneSets, totalSets)
            },
            lifts = slots.map { slot ->
                TodayLift(
                    name = ctx.catalog.find(slot.exercise.exerciseId)?.name ?: slot.exercise.exerciseId,
                    setCount = countBySlot.getValue(slot.programExerciseId),
                )
            },
            cardio = day.effectiveCardio(ctx.cardioPrefs)?.let {
                TodayCardio(cloud.trotter.log.strength.ui.day.activityLabel(it.activity), day.cardio?.detail.orEmpty(), it.activity)
            },
            standaloneCardio = standalone?.let {
                StandaloneCardio(it.id, TodayScreenBuilder.standaloneCardioLine())
            },
            actionLabel = TodayScreenBuilder.actionLabel(dayId, doneSets, totalSets),
            lifeLine = TodayScreenBuilder.lifeLine(
                mains = JournalBuilder.mainLifts(ctx.program, ctx.catalog, ctx.cfg),
                sessions = sessions,
                topSets = topSets,
                strengthDays = ctx.program.strengthDays.size,
                now = now,
            ),
        )
    }

    /**
     * How many rows a slot the lifter has never opened *will* have, run through
     * the same `:domain` seeding the day screen writes on first visit
     * ([SetSeeder.seed] off [GoalCalculator.targetFor]) rather than the slot's
     * `targetSets`. Those two disagree for every weighted main — a main with a
     * target of 3 seeds four ramps, a TOP and a back-off — so reading
     * `targetSets` here would promise a 3-set day and open a 6-set one.
     *
     * The unresolved-id fallback lives in [DayScreenBuilder.previewMainSets], so
     * Today and snapshot/glance surfaces cannot drift on this count.
     */
    private fun seededRowCount(slot: ProgramSlot, ctx: TodayContext): Int =
        DayScreenBuilder.previewMainSets(slot, ctx.cfg, ctx.catalog).size

    private data class TodayContext(
        val planId: Long,
        val program: Program,
        val dayId: String?,
        val catalog: ExerciseCatalog,
        val cfg: LifterConfig,
        val cardioPrefs: CardioPrefs,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val PLAN_PICKER_OPEN = "today_plan_picker_open"
    }
}
