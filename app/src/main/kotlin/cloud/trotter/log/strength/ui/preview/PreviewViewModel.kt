package cloud.trotter.log.strength.ui.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.WorkoutStart
import cloud.trotter.log.strength.ui.day.activityLabel
import cloud.trotter.log.strength.ui.day.dayHistoryKeys
import cloud.trotter.log.strength.ui.day.plannedTimeDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PreviewViewModel @Inject constructor(
    repo: TrackerRepository,
    savedState: SavedStateHandle,
    phoneRest: PhoneRestController,
    private val settings: SettingsStore,
    private val plans: PlanRepository,
) : ViewModel() {
    private val planId = checkNotNull(savedState.get<String>(Routes.PLAN_ID_ARG)).toLong()
    private val dayId = checkNotNull(savedState.get<String>(Routes.DAY_ID_ARG))
    val selection = DaySelection(planId, dayId)
    val workoutStart = WorkoutStart(repo, savedState, viewModelScope, phoneRest)
    private val history = dayHistoryKeys(repo.observeDay(planId, dayId), repo.sessionSummariesFor(planId)).flatMapLatest { ids ->
        flow { emit(repo.lastPerformed(ids)) }
    }

    val uiState = combine(
        repo.observeDay(planId, dayId), settings.unitFlow, history, plans.plansFlow,
    ) { performedDay, unit, last, plans ->
        val day = performedDay.day
        PreviewUiState(
            loading = false, selection = selection,
            planName = plans.firstOrNull { it.id == planId }?.name.orEmpty(),
            dayIndex = Program.accentIndex(dayId),
            title = day?.title.orEmpty(), emphasis = day?.emphasisLine.orEmpty(),
            lifts = performedDay.slots.map { slot ->
                val tracks = listOfNotNull(slot.main, slot.partner)
                val working = tracks.mapNotNull { track ->
                    track.sets.firstOrNull { it.kind != SetKind.RAMP }?.let { set ->
                        SetFormatter.summary(track.tracking, set.weightLb, set.reps, set.seconds, unit, spaced = true).trim()
                    }
                }.takeIf { it.isNotEmpty() }?.joinToString(" + ")
                val lastDisplay = tracks.mapNotNull { DayScreenBuilder.lastTimeDisplay(last[it.performed.exerciseId], unit) }
                    .takeIf { it.isNotEmpty() }?.joinToString(" + ")
                PreviewLift(tracks.joinToString(" + ") { it.performed.entry?.name ?: it.performed.exerciseId }, working, lastDisplay)
            },
            cardio = day?.cardioBlock?.let { PreviewCardio(activityLabel(it.activity), it.plannedTimeDetail()) },
        )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PreviewUiState(selection = selection))

    fun start() = workoutStart.start(selection)
}
