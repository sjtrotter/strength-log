package cloud.trotter.log.strength.ui.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.data.TrackerRepository
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
    private val rows = combine(repo.todayPlans, repo.selectedPlanIdFlow) { plans, preferred -> plans to preferred }
        .flatMapLatest { (plans, preferred) ->
            if (plans.isEmpty()) flowOf(emptyList()) else combine(plans.map { plan ->
                repo.programFor(plan.id).map { program ->
                    val next = plan.suggestedDayId?.takeIf { id -> program.days.any { it.id == id } }
                        ?: program.strengthDays.firstOrNull()?.id ?: program.days.firstOrNull()?.id
                    program.days.map { day ->
                        val row = TodayDay(plan.id, plan.name, day.id,
                            program.strengthDays.indexOfFirst { it.id == day.id }.coerceAtLeast(0), day.title,
                            preferred = plan.id == preferred, cardioOnly = day.kind == ProgramDayKind.CARDIO ||
                                (day.exercises.isEmpty() && day.cardioBlock != null))
                        row to (day.id == next)
                    }
                }
            }) { it.toList().flatten() }
        }
    private val resume = repo.openRunFlow.flatMapLatest { run ->
        val planId = run?.planId
        val dayId = run?.dayId
        if (run == null || planId == null || dayId == null) flowOf(null) else repo.programFor(planId).map { program ->
            TodayDay(planId, run.planName.orEmpty(), dayId,
                program.strengthDays.indexOfFirst { it.id == dayId }.coerceAtLeast(0), run.dayTitle)
        }
    }
    private val overview = combine(repo.todayTargetFlow, rows, resume) { target, days, openDay ->
        TodayUiState(
            loading = target == null,
            hasProgram = days.isNotEmpty() || openDay != null,
            nothingOnToday = target == TodayTarget.NothingOnToday,
            resume = openDay,
            upNext = days.filter { it.second }.map { it.first },
            otherDays = days.filterNot { it.second }.map { it.first },
        )
    }
    private val fact = repo.todayTargetFlow.flatMapLatest { target ->
        if (target !is TodayTarget.Day) flowOf(null) else combine(
            repo.lastSessionAtFor(target.plan.id), civilTimeSource.civilTime,
        ) { last, now -> TodayScreenBuilder.coachLine(last?.let(java.time.Instant::ofEpochMilli), now.zone) }
    }
    val uiState = combine(overview, expanded, fact) { state, showOther, line ->
        state.copy(otherDaysExpanded = showOther, fact = line)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TodayUiState(loading = true))

    fun toggleOtherDays() { savedState["today_other_days"] = !expanded.value }
}
