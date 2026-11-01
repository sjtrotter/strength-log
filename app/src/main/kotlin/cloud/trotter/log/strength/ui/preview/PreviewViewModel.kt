package cloud.trotter.log.strength.ui.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.performedExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.RestRuntime
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.WorkoutStart
import cloud.trotter.log.strength.ui.day.dayHistoryKeys
import cloud.trotter.log.strength.ui.day.activityLabel
import cloud.trotter.log.strength.ui.day.performedDayExerciseIds
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
    restRuntime: RestRuntime = NoOpRestRuntime,
) : ViewModel() {
    private val planId = checkNotNull(savedState.get<String>(Routes.PLAN_ID_ARG)).toLong()
    private val dayId = checkNotNull(savedState.get<String>(Routes.DAY_ID_ARG))
    val selection = DaySelection(planId, dayId)
    val workoutStart = WorkoutStart(repo, savedState, viewModelScope, restRuntime)
    private val context = combine(
        repo.programFor(planId), repo.wizardAnswersFor(planId), repo.catalogFlow, repo.unitFlow,
    ) { program, answers, catalog, unit -> PreviewContext(program, answers.config, catalog, unit) }.distinctUntilChanged()

    val uiState = context.flatMapLatest { ctx ->
        val history = repo.dayHistoryKeys(planId, dayId).flatMapLatest { (slots, ids) ->
            flow { emit(slots to repo.lastPerformed(performedDayExerciseIds(slots, ids, ctx.catalog))) }
        }
        combine(history, repo.dayTracksFlow(planId, dayId), repo.plansFlow) { (slots, last), tracks, plans ->
            val day = ctx.program.days.firstOrNull { it.id == dayId }
            val stored = tracks.tracks.associateBy { it.programExerciseId to it.slot }
            val seeds = DayScreenBuilder.seedPlan(slots, stored.mapValues { it.value.sets.size }, ctx.config, ctx.catalog)
                .associateBy { it.programExerciseId to it.slot }
            PreviewUiState(
                loading = false, selection = selection,
                planName = plans.firstOrNull { it.id == planId }?.name.orEmpty(),
                dayIndex = ctx.program.strengthDays.indexOfFirst { it.id == dayId }.coerceAtLeast(0),
                title = day?.title.orEmpty(), emphasis = day?.emphasisLine.orEmpty(),
                lifts = slots.map { slot ->
                    val trackSlots = listOfNotNull(Slot.MAIN, Slot.SS.takeIf { slot.exercise.superset != null })
                    val performed = trackSlots.map { trackSlot ->
                        val key = slot.programExerciseId to trackSlot
                        performedExercise(slot, stored[key]?.exerciseId, ctx.catalog, trackSlot)
                    }
                    val working = trackSlots.mapIndexedNotNull { index, trackSlot ->
                        val key = slot.programExerciseId to trackSlot
                        val sets = stored[key]?.sets ?: seeds[key]?.sets.orEmpty()
                        sets.firstOrNull { it.kind != SetKind.RAMP }?.let { set ->
                            SetFormatter.summary(performed[index].tracking, set.weightLb, set.reps, set.seconds, ctx.unit, spaced = true).trim()
                        }
                    }.takeIf { it.isNotEmpty() }?.joinToString(" + ")
                    val lastDisplay = performed.mapNotNull { DayScreenBuilder.lastTimeDisplay(last[it.exerciseId], ctx.unit) }
                        .takeIf { it.isNotEmpty() }?.joinToString(" + ")
                    PreviewLift(performed.joinToString(" + ") { it.entry?.name ?: it.exerciseId }, working, lastDisplay)
                },
                cardio = day?.cardioBlock?.let { PreviewCardio(activityLabel(it.activity), it.plannedTimeDetail()) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PreviewUiState(selection = selection))

    fun start() = workoutStart.start(selection)

    private data class PreviewContext(
        val program: cloud.trotter.log.strength.domain.model.Program,
        val config: cloud.trotter.log.strength.domain.model.LifterConfig,
        val catalog: cloud.trotter.log.strength.data.catalog.ExerciseCatalog,
        val unit: cloud.trotter.log.strength.domain.units.WeightUnit,
    )
}
