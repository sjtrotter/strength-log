package cloud.trotter.log.strength.ui.preview

import cloud.trotter.log.strength.domain.model.CardioBlock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.day.LiftMarker
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val repo: TrackerRepository,
    savedState: SavedStateHandle,
    phoneRest: PhoneRestController,
    private val settings: SettingsStore,
    private val plans: PlanRepository,
    private val historyRepository: HistoryRepository,
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) : ViewModel() {
    private val planId = checkNotNull(savedState.get<String>(Routes.PLAN_ID_ARG)).toLong()
    private val dayId = checkNotNull(savedState.get<String>(Routes.DAY_ID_ARG))
    val selection = DaySelection(planId, dayId)
    val workoutStart = WorkoutStart(repo, savedState, viewModelScope, phoneRest)
    private val history = dayHistoryKeys(repo.observeDay(planId, dayId), historyRepository.sessionSummariesFor(planId)).flatMapLatest { ids ->
        flow {
            emit(Pair(historyRepository.lastPerformedForSlots(planId, dayId, ids),
                historyRepository.slotSessionsById(planId, dayId)))
        }
    }

    private val cardioHistory = combine(historyRepository.cardioSessionsFlow, historyRepository.cardioSetResultsFlow) { sessions, sets ->
        sessions to sets
    }

    val uiState = combine(
        repo.observeDay(planId, dayId), settings.unitFlow, history, plans.plansFlow, cardioHistory,
    ) { performedDay, unit, history, plans, cardioHistory ->
        val (last, slotSessions) = history
        val day = performedDay.day
        val plan = plans.firstOrNull { it.id == planId }
        val (cardioSessions, cardioSets) = cardioHistory
        val cardioNextTime = if (day != null && plan != null) {
            DayScreenBuilder.cardioNextTime(day, plan, cardioSessions, cardioSets)
        } else null
        val opening = if (day != null && plan != null) {
            DayScreenBuilder.cardioOpening(day, plan, cardioSessions, cardioSets, clock.millis())
        } else null
        PreviewUiState(
            loading = false, selection = selection, runId = performedDay.tracks.runId,
            planName = plan?.name.orEmpty(),
            dayIndex = Program.accentIndex(dayId),
            title = day?.title.orEmpty(), emphasis = day?.emphasisLine.orEmpty(),
            lifts = performedDay.slots.map { slot ->
                val tracks = listOfNotNull(slot.main, slot.partner)
                val working = tracks.mapNotNull { track ->
                    if (track.performed.prescription?.target is PrescribedTarget.RepsInTime) {
                        track.sets.firstOrNull { it.kind != SetKind.RAMP }?.let { set ->
                            SetFormatter.display(track.tracking, set.weightLb, set.reps, set.seconds, unit)
                        }
                    } else DayScreenBuilder.workingHeading(track.sets, track.tracking, unit)
                }.takeIf { it.isNotEmpty() }?.joinToString(" + ")
                val nextTime = DayScreenBuilder.nextTime(slot.main.baseRows.orEmpty(),
                    slotSessions[slot.slot.programExerciseId to Slot.MAIN]?.takeIf {
                        slot.main.performed.exerciseId == slot.slot.exercise.exerciseId
                    }, slot.slot.exercise.keepToPlan, unit,
                    offersOn = cloud.trotter.log.strength.ui.day.NextTimeGate.strength(plan, slot.slot.exercise.rule?.nextTime), rule = slot.slot.exercise.rule)
                val previousResults = listOfNotNull(Slot.MAIN to slot.main, slot.partner?.let { Slot.SS to it }).map { (slotName, track) ->
                    val slotLast = last[track.performed.exerciseId to slotName]
                    val previous = slotLast?.last
                    val comparable = previous == null ||
                        SetFormatter.trackingOfValues(previous.weightLb, previous.reps, previous.seconds) == track.tracking
                    val source = slotLast?.takeUnless { it.sameSlot }
                    val samePlan = source?.planId == planId || (planId == PlanEntity.LEGACY_ID && source?.planId == null)
                    PreviewPreviousResult(
                        last = previous?.takeIf { comparable }?.let {
                            slotLast?.sets?.takeIf { sets -> sets.isNotEmpty() }?.let { sets -> SetFormatter.performed(sets, unit) }
                                ?: SetFormatter.display(track.tracking, it.weightLb, it.reps, it.seconds, unit)
                        },
                        comparable = comparable,
                        exerciseName = track.performed.entry?.name ?: track.performed.exerciseId,
                        source = source?.dayId,
                        sourcePlanName = source?.takeUnless { samePlan }?.planName?.takeIf { it.isNotBlank() },
                    )
                }
                PreviewLift(tracks.joinToString(" + ") { it.performed.entry?.name ?: it.performed.exerciseId },
                    working, previousResults, nextTime, note = slot.slot.exercise.note,
                    programExerciseId = slot.slot.programExerciseId,
                    marker = LiftMarker.of(slot.slot.exercise.rule.takeIf { !slot.slot.exercise.keepToPlan &&
                        slot.main.performed.exerciseId == slot.slot.exercise.exerciseId },
                        slot.main.openingInfo, plan?.lightCycleRemaining ?: 0),
                    partnerMarker = slot.partner?.let { partnerTrack -> LiftMarker.of(
                        slot.slot.exercise.superset?.rule.takeIf { !slot.slot.exercise.keepToPlan &&
                            partnerTrack.performed.exerciseId == slot.slot.exercise.superset?.exerciseId },
                        partnerTrack.openingInfo, plan?.lightCycleRemaining ?: 0) })
            },
            cardio = opening?.let { block ->
                PreviewCardio(activityLabel(block.activity), block.plannedTimeDetail(), block.activity, cardioNextTime)
            },
        )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PreviewUiState(selection = selection))

    fun start() = workoutStart.start(selection)

    fun applyCardioNextTime(block: CardioBlock) {
        viewModelScope.launch { repo.setCardioWorking(planId, dayId, block) }
    }

    fun setWorkingWeight(programExerciseId: Long, weightLb: Double) {
        val runId = uiState.value.runId ?: return
        viewModelScope.launch {
            if (repo.setWorkingWeight(runId, planId, dayId, programExerciseId, Slot.MAIN, weightLb) == RunWriteResult.Rejected) {
                workoutStart.startWithWorkingWeight(selection, programExerciseId, weightLb)
            } else {
                workoutStart.dismiss()
            }
        }
    }
}
