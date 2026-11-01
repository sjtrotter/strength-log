package cloud.trotter.log.strength.ui.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.ui.day.WorkoutStart
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    repo: TrackerRepository,
    civilTimeSource: CivilTimeSource,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val workoutStart = WorkoutStart(repo, savedState, viewModelScope)
    private val expanded = savedState.getStateFlow("today_other_days", false)
    // The branch owns both the program reads and the history identity. A delayed
    // membership switch keeps the previous complete overview until all new data arrives.
    private val overview = combine(repo.todayPlans, repo.todayTargetFlow, repo.openRunFlow) { plans, target, run ->
        Triple(plans, target, run)
    }.flatMapLatest { (plans, target, run) ->
        val planIds = (plans.map { it.id } + listOfNotNull(run?.planId)).distinct()
        val programs = if (planIds.isEmpty()) flowOf(emptyMap<Long, Program>()) else combine(
            planIds.map { id -> repo.programFor(id).map { id to it } },
        ) { it.toMap() }
        // todayPlans uses the shared display ordering: preferred on Today, else first.
        val factPlanId = run?.planId ?: plans.firstOrNull()?.id
        val lastSession = factPlanId?.let(repo::lastSessionAtFor) ?: flowOf(null)
        combine(programs, lastSession, civilTimeSource.civilTime) { byPlan, last, now ->
            val days = plans.flatMap { plan ->
                val program = byPlan.getValue(plan.id)
                val next = plan.suggestedDayId?.takeIf { id -> program.days.any { it.id == id } }
                    ?: program.strengthDays.firstOrNull()?.id ?: program.days.firstOrNull()?.id
                program.days.map { day ->
                    TodayDay(plan.id, plan.name, day.id,
                        program.strengthDays.indexOfFirst { it.id == day.id }.coerceAtLeast(0), day.title,
                        cardioOnly = day.kind == ProgramDayKind.CARDIO ||
                            (day.exercises.isEmpty() && day.cardioBlock != null)) to (day.id == next)
                }
            }
            val openDay = run?.takeIf { it.planId != null && it.dayId != null }?.let { open ->
                TodayDay(requireNotNull(open.planId), open.planName.orEmpty(), requireNotNull(open.dayId),
                    byPlan[open.planId]?.strengthDays?.indexOfFirst { it.id == open.dayId }?.coerceAtLeast(0) ?: 0,
                    open.dayTitle)
            }
            TodayUiState(
                loading = target == null,
                hasProgram = days.isNotEmpty() || openDay != null,
                nothingOnToday = plans.isEmpty() && target != null && target != TodayTarget.NoPlans,
                resume = openDay,
                upNext = days.filter { it.second }.map { it.first },
                otherDays = days.filterNot { it.second }.map { it.first },
                fact = TodayScreenBuilder.coachLine(last?.let(java.time.Instant::ofEpochMilli), now.zone),
            )
        }
    }
    val uiState = combine(overview, expanded) { state, showOther ->
        state.copy(otherDaysExpanded = showOther)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TodayUiState(loading = true))

    fun toggleOtherDays() { savedState["today_other_days"] = !expanded.value }
}
