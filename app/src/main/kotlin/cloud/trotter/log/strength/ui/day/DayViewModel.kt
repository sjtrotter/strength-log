package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.progression.CardioEffort
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.DayTracks
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.LastTimes
import cloud.trotter.log.strength.data.PerformedDay
import cloud.trotter.log.strength.data.PerformedSlot
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.SlotSession
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.data.TrackEdit
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import cloud.trotter.log.strength.data.serialization.TestEventJson
import cloud.trotter.log.strength.domain.progression.Benchmark
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestStop
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.forRun
import cloud.trotter.log.strength.data.isSlotSkipped
import cloud.trotter.log.strength.data.performedExercise
import cloud.trotter.log.strength.data.prefs.RoutinePrefs
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.routine.CreditedExercise
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.domain.routine.Routines
import cloud.trotter.log.strength.data.prefs.ReviewTarget
import cloud.trotter.log.strength.data.prefs.UndoHalf
import cloud.trotter.log.strength.data.prefs.UndoTarget
import cloud.trotter.log.strength.data.prefs.WorkoutRecord
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioRouteJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.setCounts
import cloud.trotter.log.strength.run.GpsDistance
import cloud.trotter.log.strength.run.GpsLiveStatus
import cloud.trotter.log.strength.run.GpsNotice
import cloud.trotter.log.strength.run.GpsState
import cloud.trotter.log.strength.run.LocationAccess
import cloud.trotter.log.strength.run.LocationAnswer
import cloud.trotter.log.strength.run.usesGps
import cloud.trotter.log.strength.domain.cardio.DistanceTrack
import cloud.trotter.log.strength.domain.cardio.CardioExecution
import cloud.trotter.log.strength.domain.cardio.SetMeter
import cloud.trotter.log.strength.domain.cardio.BootAnchor
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.Rotation
import cloud.trotter.log.strength.domain.glance.GlanceLines
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Side
import cloud.trotter.log.strength.domain.model.Sides
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetLabel
import cloud.trotter.log.strength.domain.model.SetLabels
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.progression.LoadFollowing
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.seeding.SetEdit
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.standards.HoldClock
import cloud.trotter.log.strength.domain.standards.PhoneRestTimer
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.sync.WatchContinuitySource
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Day screen ViewModel (spec §8.2, MVVM/UDF). Holds no working truth of its own:
 * every render value derives from Room/DataStore flows, and every mutation writes
 * straight back through [TrackerRepository]. Ephemeral UI state — the view-day
 * override, lift selection and sheet identity — lives in [SavedStateHandle].
 *
 * The open sheet is one atomic, day-scoped identity; rendering and Back use only
 * its resolution against the loaded day's exercises and rows. Resolution never
 * mutates saved state: only opening, closing, switching days or completing a day
 * replaces or clears it. Lift selection is one saved key, resolved against the
 * current workout groups even before rows exist. The ViewModel collects authoritative day
 * data after seeding and pins a null selection to the derived page once rows
 * exist; sheet ticks keep that selection while action logs advance it through
 * the workout resolver. A day switch or completion clears both
 * identities.
 *
 * The §8.2 decision logic lives in the pure [DayScreenBuilder]; this class is the
 * flow/write wiring around it. All cascade/edit math comes from `:domain`
 * ([SetEditor]) — never reimplemented here.
 *
 * Cardio runs persist their frozen prescriptions, per-set outcomes and clock anchors
 * as one saved value. Each mutation also mirrors the run to [CardioRunStore], keyed
 * by plan and day, so the next Day screen for that day starts where this one stopped
 * (#627). Logged distance drafts have separate saved state and never retain execution
 * ownership. Set-end alarms are shared with timed reps and holds.
 * Outdoor runs measure distance through [GpsDistance]; its stamps have their own saved key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val sessionPublisher: SessionPublisher,
    private val shareCardService: ShareCardService,
    private val savedState: SavedStateHandle,
    private val cardioClock: CardioClock,
    private val cardioAlarm: CardioAlarm,
    private val phoneRest: PhoneRestController,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val watchContinuity: WatchContinuitySource = WatchContinuitySource.None,
    private val settings: SettingsStore,
    private val plans: PlanRepository,
    private val catalog: CatalogRepository,
    private val historyRepository: HistoryRepository,
    private val gps: GpsDistance = GpsDistance.None,
    private val sprintBeeper: SprintBeeper = object : SprintBeeper { override fun beep() = Unit },
    private val cardioRunStore: CardioRunStore = CardioRunStore.None,
) : ViewModel() {

    val nowMillis: () -> Long = clock::millis
    val elapsedClock: () -> Long = cardioClock::elapsedRealtimeMillis
    val workoutStart = WorkoutStart(repo, savedState, viewModelScope, phoneRest)
    private val pendingStart = savedState.getStateFlow<PendingStartTick?>(KEY_PENDING_START, null)

    private val warmUpProgress = savedState.getStateFlow<RoutineProgress?>(KEY_WARM_UP, null)
    private val coolDownProgress = savedState.getStateFlow<RoutineProgress?>(KEY_COOL_DOWN, null)
    private val closedWarmUpOwner = savedState.getStateFlow<String?>(KEY_CLOSED_WARM_UP_OWNER, null)
    private val closedCoolDownOwner = savedState.getStateFlow<String?>(KEY_CLOSED_COOL_DOWN_OWNER, null)
    private val routinePrefs: StateFlow<RoutinePrefs?> = settings.routinePrefsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val distanceUnit: StateFlow<DistanceUnit> = settings.distanceUnitFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, DistanceUnit.MI)

    private val viewDayOverride = savedState.getStateFlow<DaySelection?>(
        KEY_VIEW_SELECTION,
        savedState.get<String>(KEY_VIEW_DAY)?.let { DaySelection(null, it) }
            ?: savedState.get<String>(Routes.DAY_ID_ARG)?.let {
                DaySelection(savedState.get<String>(Routes.DAY_PLAN_ID_ARG)?.toLongOrNull(), it)
            },
    )
    private val selectedPage = savedState.getStateFlow<String?>(KEY_SELECTED_PAGE, null)
    /** The finished day whose page must not be re-pinned while the real advance reaches [effectiveDayId]. */
    private var pinHeldFor: String? = null
    private val swapUndo = savedState.getStateFlow<WorkoutSwapUndo?>(KEY_SWAP_UNDO, null)
    private val openSheet = savedState.getStateFlow<OpenSheet?>(KEY_OPEN_SHEET, null)
    val pastCardio: StateFlow<CardioEntryDraft?> = savedState.getStateFlow<CardioEntryDraft?>(KEY_PAST_CARDIO, null)
    val pastCardioEffort = savedState.getStateFlow<CardioEffort?>(KEY_PAST_CARDIO_EFFORT, null)
    private val pastCardioDay = savedState.getStateFlow<DaySelection?>(KEY_PAST_CARDIO_DAY, null)
    private val _pastCardioSaveFailed = MutableStateFlow(false)
    val pastCardioSaveFailed: StateFlow<Boolean> = _pastCardioSaveFailed.asStateFlow()
    private val _pastCardioCollision = MutableStateFlow(false)
    val pastCardioCollision: StateFlow<Boolean> = _pastCardioCollision.asStateFlow()
    private val _pastCardioSaving = MutableStateFlow(false)
    val pastCardioSaving: StateFlow<Boolean> = _pastCardioSaving.asStateFlow()
    private val pastCardioReceiptId = savedState.getStateFlow<Long?>(KEY_PAST_CARDIO_RECEIPT_ID, null)
    private val cardioRun = savedState.getStateFlow<CardioRun?>(KEY_CARDIO_RUN, null)
    private val sprintTimer = savedState.getStateFlow<SprintTimerState?>(KEY_SPRINT_TIMER, null)
    private val sprintTimes = savedState.getStateFlow<List<CardioSprintTime>>(KEY_SPRINT_TIMES, emptyList())
    private val sprintProtocol = savedState.getStateFlow<String?>(KEY_SPRINT_PROTOCOL, null)
    val sprintTimeDraft = savedState.getStateFlow<SprintTimeDraft?>(KEY_SPRINT_TIME_DRAFT, null)
    private var sprintBeepJob: kotlinx.coroutines.Job? = null
    private val gpsSets = savedState.getStateFlow<ArrayList<CardioGpsSet>>(KEY_CARDIO_GPS_SETS, arrayListOf())
    private val gpsAsk = savedState.getStateFlow<String?>(KEY_GPS_ASK, null)
    private val gpsEnabled = settings.gpsDistanceFlow.map { enabled ->
        enabled && gps.access() != LocationAccess.UNAVAILABLE
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val saveRoute = settings.saveRouteFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val mutableGpsNotice = MutableStateFlow<GpsNotice?>(null)
    val gpsNotice: StateFlow<GpsNotice?> = mutableGpsNotice.asStateFlow()
    private val runDistance = savedState.getStateFlow<CardioRunDistance?>(KEY_CARDIO_RUN_DISTANCE, null)
    private val readingInputs = savedState.getStateFlow<ArrayList<CardioReadingInput>>(KEY_CARDIO_READING_INPUTS, arrayListOf())
    private val distanceInputs = savedState.getStateFlow<ArrayList<CardioDistanceInput>>(KEY_CARDIO_DISTANCE_INPUTS, arrayListOf())
    private val pendingCardioDistances = savedState.getStateFlow<ArrayList<CardioRun>>(KEY_CARDIO_DISTANCES, arrayListOf())
    private val deferredCardioReceipt = savedState.getStateFlow<CardioRun?>(KEY_DEFERRED_CARDIO_RECEIPT, null)
    private var cardioDraftSaveInFlight = false
    private var cardioAlertAnchor: String? = null
    private var cardioSaveInFlight = false
    // Not saved: process restoration gets a fresh automatic retry.
    private var lastCardioValidationContext: DayContext? = null
    private val cardioTick = MutableStateFlow(0L)
    private var cardioScreenLive = false
    private val timedRepEvent = savedState.getStateFlow<TimedRepEvent?>(KEY_TIMED_REP_EVENT, null)
    // An alarm armed in this process owes this clock one buzz. A return after a
    // missed or frozen alarm buzzes once through the ticker.
    private var timedRepAlertAnchor: Long? = null
    private val holdEvent = savedState.getStateFlow<HoldEvent?>(KEY_HOLD, null)
    // The timed-rep buzz rule: an alarm armed in this process owes this side one buzz.
    private var holdAlertAnchor: Long? = null
    private var holdLogging: HoldEvent? = null
    /** The hold in progress, or reserved behind the start sheet, runs past its target (#643). */
    private val holdPastTarget: Boolean get() = savedState.get<Boolean>(KEY_HOLD_PAST_TARGET) == true

    /** A timed-rep clock runs or a hold is in progress. One set timer at a time, and cardio waits for it. */
    private val setTimerBusy: Boolean get() = timedRepEvent.value?.awaitingCount == false || holdEvent.value != null

    /** Keeps UI side effects ordered with their repository writes. */
    private val mutationLock = Mutex()

    private val displayTargetFlow = combine(
        repo.todayTargetFlow, plans.selectedPlanFlow, plans.plansFlow, viewDayOverride,
    ) { display, selected, plans, override ->
        val target = display as? TodayTarget.Day
        val candidates = if (override?.planId != null) plans.filter { it.id == override.planId }
            else listOfNotNull(target?.plan, selected).distinctBy { it.id }
        Triple(target, candidates, override)
    }.flatMapLatest { (target, candidates, override) ->
        if (override == null || candidates.isEmpty()) flowOf(target) else combine(
            candidates.map { plan -> plans.programFor(plan.id).map { plan to it } },
        ) { programs ->
            programs.firstOrNull { (_, program) -> program.days.any { it.id == override.dayId } }
                ?.let { (plan, _) -> TodayTarget.Day(plan, override.dayId) } ?: target
        }
    }.distinctUntilChanged()

    private val effectiveDayId: StateFlow<String?> = displayTargetFlow.flatMapLatest { target ->
        if (target == null) flowOf(null) else plans.programFor(target.plan.id).map { program ->
            program.dayToShow(target.dayId)?.id
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val contextFlow = displayTargetFlow.flatMapLatest { target ->
        if (target == null) combine(settings.unitFlow, catalog.catalogFlow) {
                unit, catalog -> DayContext(Program(emptyList()), null, null, unit, catalog)
        }
        else combine(plans.programFor(target.plan.id), settings.unitFlow, catalog.catalogFlow) {
                program, unit, catalog ->
            DayContext(program, target.plan.suggestedDayId, program.dayToShow(target.dayId)?.id,
                unit, catalog, plan = target.plan)
        }
    }.combine(effectiveDayId) { ctx, dayId ->
        ctx.copy(dayId = ctx.dayId?.takeIf { it == dayId })
    }
            // Every DataStore-backed flow above re-emits on *any* preferences
            // write, so without this an identical context would re-run the
            // day pipeline and blank the day the lifter is on.
            .distinctUntilChanged()

    // Only repository data drives this flow. Saved-selection writes cannot emit
    // an old day's rows back into the pinning collector during a day switch.
    private val committedDayFlow: Flow<CommittedDay> = contextFlow.flatMapLatest { ctx ->
        val dayId = ctx.dayId
        if (dayId == null) {
            flowOf(CommittedDay(noDayState(ctx)))
        } else {
            // A reset replaces slot ids even when the program and history ids stay equal.
            // Those rows must be seeded before observing the replacement day.
            val planId = ctx.plan?.id ?: return@flatMapLatest flowOf(CommittedDay(noDayState(ctx)))
            val historyKeys = combine(plans.slotsFor(planId, dayId), dayHistoryKeys(repo.observeDay(planId, dayId), historyRepository.sessionSummariesFor(planId))) { slots, ids ->
                slots to ids
            }
            // One flatMapLatest per slot emission: a reset cancels the old observer at once, so the
            // skipped-lift controls never pair the new day's cards with the old slot ids.
            historyKeys.flatMapLatest { (slots, ids) ->
                flow {
                    ensureSeeded(planId, dayId)
                    emit(fetchDayHistory(ids, planId, dayId))
                }.flatMapLatest { history ->
                combine(repo.observeDay(planId, dayId), repo.openRunFlow) { day, open ->
                    val tracks = day.tracks
                    val built = buildState(ctx, day, history)
                    val state = built.copy(runId = tracks.runId, runOpen = tracks.runOpen,
                            beforeStart = (!tracks.runOpen || tracks.draft) && built.exercises.isNotEmpty(),
                            runSetCounts = open?.takeIf { it.id == tracks.runId }?.tracks?.setCounts(),
                            openRunElsewhere = open?.takeIf { it.planId != planId || it.dayId != dayId }?.dayTitle,
                            skippedLifts = slots.filter { it.programExerciseId in tracks.skippedLifts }.map { slot ->
                                val id = slot.exercise.exerciseId
                                SkippedLift(slot.programExerciseId, slot.position, day.catalog.find(id)?.name ?: id)
                            },
                            skippedPartners = day.slots.map { it.slot }.filter { it.programExerciseId in tracks.droppedPartners }.mapNotNull { slot ->
                                val skipped = open?.tracks?.singleOrNull { it.programExerciseId == slot.programExerciseId && it.slot == Slot.MAIN && it.dropped }
                                    ?: open?.tracks?.singleOrNull { it.programExerciseId == slot.programExerciseId && it.slot == Slot.SS && it.dropped }
                                skipped?.let { slot.programExerciseId to SkippedLift(slot.programExerciseId, slot.position,
                                    day.catalog.find(it.exerciseId)?.name ?: it.exerciseId, it.slot) }
                            }.toMap(),
                            retiredRoundDoneCount = tracks.retiredRoundsDone,
                            retiredTracks = tracks.retired.mapNotNull { track ->
                                val doneCount = track.sets.count { it.done }
                                if (doneCount == 0) null else RetiredTrackLine(
                                    day.catalog.find(track.exerciseId)?.name ?: track.exerciseId, doneCount,
                                )
                            })
                    CommittedDay(state, tracks, ctx)
                }
                }
            }
                // flatMapLatest cancels the day the lifter just left, but nothing
                // *publishes* that — [uiState] keeps handing out the old day's
                // fully tickable rows until [fetchDayHistory] returns from Room.
                // So each branch declares itself unresolved before it reads
                // anything: on the usual fast fetch the real day arrives well
                // inside ProgramLoadingState's reveal delay and nothing is drawn,
                // and on a slow one the lifter waits instead of ticking sets into
                // the day they walked away from.
                .onStart { emit(CommittedDay(DayUiState(loading = true))) }
        }
    }

    private val committedDayState = committedDayFlow.stateIn(
        viewModelScope, SharingStarted.Lazily, CommittedDay(DayUiState(loading = true)),
    )

    private val dayState = committedDayState.map { it.state }
        .stateIn(viewModelScope, SharingStarted.Lazily, DayUiState(loading = true))

    /**
     * Keep-screen-on rides on the outside, not through [contextFlow]: it changes
     * nothing about which day is on screen, and threading it through the
     * flatMapLatest would restart the whole branch — and blank the screen —
     * every time a lifter flips a switch mid-workout.
     */
    /** One tick per second while a block runs and someone is watching; nothing
     *  otherwise. Living inside the uiState pipeline means WhileSubscribed owns
     *  its lifecycle — no free-running loop survives the screen. */
    private val cardioSecondTicker = combine(cardioRun, timedRepEvent, holdEvent, gps.state) { cardio, reps, hold, location ->
        cardio?.running == true || reps?.awaitingCount == false || hold?.running == true ||
            location is GpsState.Tracking || location is GpsState.LocationOff
    }.distinctUntilChanged().flatMapLatest { running ->
        if (!running) flowOf(0L)
        else flow {
            var beat = 0L
            while (true) {
                if (gpsEnabled.value && (gps.state.value is GpsState.Tracking || gps.state.value is GpsState.LocationOff)) {
                    gps.refreshAccess()
                }
                emit(beat++)
                delay(CARDIO_TICK_MS)
            }
        }
    }

    private val phoneRestState: Flow<RestUiState?> = phoneRest.state.flatMapLatest { rest ->
        if (rest == null) flowOf(null) else flow {
            while (true) {
                val now = clock.millis()
                val remaining = PhoneRestTimer.remainingSeconds(rest, now)
                emit(RestUiState(rest, remaining, PhoneRestTimer.remainingFraction(rest, now), remaining == 0))
                if (remaining == 0) {
                    phoneRest.expireForeground(rest.deadlineEpochMillis, clock::millis)
                    break
                }
                delay(REST_TICK_MS)
            }
        }
    }

    private val executionState = combine(
        cardioRun, cardioTick, cardioSecondTicker, timedRepEvent, holdEvent,
    ) { run, _, _, reps, hold -> Triple(run, reps, hold) }.combine(pendingCardioDistances) { execution, _ -> execution }
        .combine(runDistance) { execution, _ -> execution }
        .combine(distanceInputs) { execution, _ -> execution }
        .combine(readingInputs) { execution, _ -> execution }
        .combine(gps.state) { execution, _ -> execution }
        .combine(gpsSets) { execution, _ -> execution }
        .combine(gpsAsk) { execution, _ -> execution }
        .combine(gpsEnabled) { execution, _ -> execution }
        .combine(distanceUnit) { execution, _ -> execution }
        .combine(sprintTimer) { execution, _ -> execution }
        .combine(sprintTimes) { execution, _ -> execution }
        .combine(sprintProtocol) { execution, _ -> execution }

    private val cardioHistory = combine(historyRepository.cardioSessionsFlow, historyRepository.cardioSetResultsFlow) { sessions, sets ->
        sessions to sets
    }

    private val workoutRecord: Flow<WorkoutRecord?> =
        settings.workoutRecordFlow

    private val workoutDay = combine(
        committedDayState, workoutRecord, selectedPage,
    ) { day, record, _ -> day to record }

    private val baseUiState: Flow<DayUiState> =
        combine(workoutDay, settings.keepScreenOnFlow, cardioHistory, executionState, phoneRestState) {
                input, preferences, history, execution, rest ->
            val (committed, record) = input
            val state = committed.state
            val (run, event, hold) = execution
            val cardio = buildCardioState(state, history.first, history.second, run, committed.context)
            val timedRep = reconcileTimedRep(state, event)
            val held = reconcileHold(state, hold)
            val rendered = state.copy(
                keepScreenOn = preferences,
                cardio = cardio,
                timedRep = timedRep,
                hold = held,
                executionBusy = cardioRun.value != null || timedRep?.event?.awaitingCount == false || held != null,
                executionBlockedReason = when {
                    timedRep?.event?.awaitingCount == false || held != null || run?.running == true -> WorkoutBlockedReason.TIMER_RUNNING
                    run?.saveFailed == true -> WorkoutBlockedReason.CARDIO_NOT_SAVED
                    run?.complete == true -> WorkoutBlockedReason.CARDIO_SAVING
                    run != null -> WorkoutBlockedReason.CARDIO_IN_PROGRESS
                    else -> null
                },
                cardioRunning = cardioRun.value?.running == true,
                rest = rest,
            )
            rendered.copy(
                workout = buildWorkout(rendered, committed.tracks, record),
            ).also(::refreshGpsWarmup)
        }

    private val sheetUiState = combine(
        baseUiState, selectedPage, openSheet, swapUndo,
        // Defer access until the editor is initialized; confirmation reads uiState.
        flow { emitAll(editor.pendingPlanSwap) },
    ) { state, selection, savedSheet, undo, pending ->
        state.copy(
            selectedPageKey = selection,
            pendingPlanSwap = pending?.takeIf {
                !state.runOpen && state.planId == it.planId && state.viewDayId == it.dayId &&
                    state.exercises.any { card -> card.programExerciseId == it.programExerciseId }
            },
            swapUndo = undo?.takeIf { offer ->
                state.runOpen && state.runId == offer.runId && state.viewDayId == offer.dayId &&
                    (if (offer.skippedSlot != null) {
                        state.skippedLifts.any { it.programExerciseId == offer.programExerciseId } ||
                            state.skippedPartners[offer.programExerciseId]?.slot == offer.skippedSlot
                    } else state.exercises.any { card -> card.programExerciseId == offer.programExerciseId &&
                        (if (offer.slot == Slot.SS) card.partnerExerciseId else card.exerciseId) == offer.exerciseId })
            },
            openSheet = savedSheet?.takeIf { sheetResolves(state, it, state.timedRep?.event) },
        )
    }

    val uiState: StateFlow<DayUiState> = combine(
        sheetUiState,
        watchContinuity.continuityFlow,
        watchContinuity.remoteTickFlow, pendingStart,
    ) { state, continuity, remoteTick, pending ->
        DayScreenBuilder.markRemoteTick(
            state.copy(
                startSheet = pending != null && pending.dayId == state.viewDayId && state.beforeStart,
                watchStatus = DayScreenBuilder.watchStatusLine(
                    continuity.lastContactMillis, continuity.syncingChanges,
                    queuedChanges = 0, watchConnected = continuity.lastContactMillis > 0L,
                    nowMillis = clock.millis(),
                ),
            ),
            remoteTick,
        )
    }.combine(settings.effortScaleFlow) { state, scale -> state.copy(effortScale = scale) }
        .combine(combine(routinePrefs, warmUpProgress, coolDownProgress, ::Triple)) { state, (prefs, warm, cool) ->
            state.copy(warmUp = RoutineSheets.warmUp(state, prefs, warm), coolDown = RoutineSheets.coolDown(state, prefs, cool))
        }
        .combine(combine(closedWarmUpOwner, closedCoolDownOwner, ::Pair)) { state, (warm, cool) ->
            state.copy(warmUp = state.warmUp?.takeUnless { it.owner == warm },
                coolDown = state.coolDown?.takeUnless { it.owner == cool })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayUiState(loading = true))

    private val otherWorkoutOpenMessage = MutableStateFlow(false)

    fun dismissSwapMessage() { otherWorkoutOpenMessage.value = false }

    val dayEditState: StateFlow<DayEditUiState> get() = editor.dayEditState

    val notificationPermissionAsked = settings.phoneRestNotificationAskedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun markNotificationPermissionAsked() = viewModelScope.launch { settings.markPhoneRestNotificationAsked() }

    private fun cardioBestsKey(run: CardioRun) = "day_cardio_standing_bests:${run.runId}"

    private fun cardioStandingKey(run: CardioRun) = "day_cardio_standing_readings:${run.runId}"

    private suspend fun standingBestsFor(run: CardioRun): List<SavedCardioStanding> {
        savedState.get<ArrayList<SavedCardioStanding>>(cardioStandingKey(run))?.let { return it }
        savedState.get<ArrayList<SavedCardioBest>>(cardioBestsKey(run))?.let { old ->
            val standing = old.map { SavedCardioStanding.from(it.toDomain()) }
            savedState[cardioStandingKey(run)] = ArrayList(standing)
            return standing
        }
        // A stale snapshot may have neither sessionId nor a baseline even though
        // the transaction committed. Exclude the same identity used to deduplicate.
        val standing = historyRepository.cardioBestsFor(
            run.block.activity, excludedDayId = run.dayId, excludedStartedAt = run.startedAt,
        ).values.map(SavedCardioStanding::from)
        savedState[cardioStandingKey(run)] = ArrayList(standing)
        return standing
    }

    private fun clearCardioRecordState(run: CardioRun) {
        savedState.remove<ArrayList<SavedCardioBest>>(cardioBestsKey(run))
        savedState.remove<ArrayList<SavedCardioStanding>>(cardioStandingKey(run))
    }

    /**
     * The standing undo offers for removed sets, oldest first — a bounded LIFO
     * stack, because each entry's index describes the track *as it was when
     * that row left it*. Only the last entry's index still means anything
     * against the current list, so only the last entry can be taken; popping it
     * makes the one beneath it current again.
     *
     * A bare field because this is a [UNDO_WINDOW_MS]-long courtesy, not user
     * data, and an offer restored after process death would put a row back
     * into a workout the user had already moved on from. One shared window covers the whole stack; it
     * restarts whenever the stack changes, and running out clears all of it.
     * A day switch or a DONE clears it outright.
     */
    private val _removedSets = MutableStateFlow<List<RemovedSet>>(emptyList())
    val removedSets: StateFlow<List<RemovedSet>> = _removedSets.asStateFlow()
    private var undoWindowJob: Job? = null

    internal val editor: DayEditor
    internal val finisher: SessionFinisher

    init {
        viewModelScope.launch {
            workoutStart.navigation.collect { target ->
                if (target.planId != dayState.value.planId || target.dayId != currentDay()) selectDay(target)
            }
        }
        viewModelScope.launch {
            combine(dayState, pendingStart) { state, pending ->
                pending?.takeIf { !state.loading && state.viewDayId != null &&
                    (state.viewDayId != it.dayId || !state.beforeStart) }
            }.collect { stale ->
                if (stale != null) mutationLock.withLock {
                    if (pendingStart.value != stale) return@withLock
                    val state = dayState.value
                    if (state.loading || state.viewDayId == null ||
                        (state.viewDayId == stale.dayId && state.beforeStart)) return@withLock
                    // A process can die between starting the run and saving its timer reservation.
                    val timer = savedState.get<TimedRepEvent>(KEY_PENDING_START_TIMER)
                    if (stale.startTimer && timer != null && timedRepEvent.value == null &&
                        timer.dayId == stale.dayId && timer.programExerciseId == stale.programExerciseId &&
                        timer.setIndex == stale.index && timedRepMatches(state, timer)) {
                        savedState[KEY_TIMED_REP_EVENT] = timer
                    }
                    val hold = savedState.get<HoldEvent>(KEY_PENDING_START_HOLD)
                    if (stale.startTimer && hold != null && holdEvent.value == null &&
                        hold.dayId == stale.dayId && hold.programExerciseId == stale.programExerciseId &&
                        hold.setIndex == stale.index && holdMatches(state, hold)) {
                        savedState[KEY_HOLD] = hold
                    }
                    dismissStart()
                }
            }
        }
        viewModelScope.launch {
            swapUndo.flatMapLatest { offer ->
                if (offer == null) flowOf(null) else repo.openRunFlow.map { run ->
                    offer.takeUnless {
                        run != null && run.id == offer.runId &&
                            (if (offer.skippedSlot != null) {
                                run.tracks.any { track -> track.programExerciseId == offer.programExerciseId &&
                                    track.slot == offer.skippedSlot && track.dropped && track.exerciseId == offer.previousExerciseId } &&
                                    (run.isSlotSkipped(offer.programExerciseId, Slot.MAIN) || run.tracks.any { track ->
                                        !track.retired && track.programExerciseId == offer.programExerciseId &&
                                            track.slot == Slot.MAIN && track.exerciseId == offer.exerciseId
                                    })
                            } else run.tracks.any { track ->
                                !track.retired && track.programExerciseId == offer.programExerciseId &&
                                    track.slot == offer.slot && track.exerciseId == offer.exerciseId
                            })
                    }
                }
            }.collect { invalid ->
                if (invalid != null && swapUndo.value == invalid) dismissSwapUndo()
            }
        }
        editor = DayEditor(
            repo = repo,
            savedState = savedState,
            scope = viewModelScope,
            context = contextFlow.map { ctx ->
                DayEditor.Context(ctx.program, ctx.dayId, ctx.catalog, ctx.plan)
            },
            unitFlow = settings.unitFlow,
            dayState = dayState,
            currentDay = ::currentDay,
            mutate = ::mutate,
            mutationLock = mutationLock,
            currentPendingPlanSwap = { uiState.value.pendingPlanSwap },
            clearLogUndo = ::clearLogUndo,
            dismissSwapUndo = ::dismissSwapUndo,
            otherWorkoutOpenMessage = otherWorkoutOpenMessage,
            plans = plans,
            catalog = catalog,
            restSettingsFlow = settings.restSettingsFlow,
        )

        finisher = SessionFinisher(
            repo = repo,
            historyRepository = historyRepository,
            plans = plans,
            catalog = catalog,
            settings = settings,
            sessionPublisher = sessionPublisher,
            shareCardService = shareCardService,
            savedState = savedState,
            scope = viewModelScope,
            mutationLock = mutationLock,
            clock = clock,
            afterCommit = { day, nextDayId ->
                phoneRest.skip()
                if (timedRepEvent.value?.dayId == day) clearTimedRep()
                if (holdEvent.value?.dayId == day) clearHold()
                closeUndoWindow()
                savedState[KEY_VIEW_SELECTION] = null
                pinHeldFor = day.takeIf { nextDayId != day && effectiveDayId.value == day }
                savedState[KEY_SELECTED_PAGE] = null
                closeSheet()
            },
            executionActive = { executionActive },
            loggedCardio = { planId, dayId ->
                historyRepository.cardioSessionsFlow.first().loggedToday(planId, dayId, cardioClock.wallMillis(), repo.zone)
            },
        )

        viewModelScope.launch {
            combine(dayState, historyRepository.cardioSessionsFlow) { state, history ->
                val logged = history.loggedToday(state.planId, state.viewDayId, cardioClock.wallMillis(), repo.zone)
                state.copy(cardio = state.cardio?.copy(loggedSeconds = logged?.seconds))
            }.collect { state ->
                if (!state.loading && state.viewDayId == currentDay() &&
                    state.viewDayId != pinHeldFor &&
                    selectedPage.value == null &&
                    state.exercises.any { it.rows.isNotEmpty() }
                ) {
                    state.pages.getOrNull(state.currentPage)?.key?.let { savedState[KEY_SELECTED_PAGE] = it }
                }
            }
        }
        viewModelScope.launch {
            var previous: DaySelection? = null
            displayTargetFlow.collect { target ->
                val current = target?.dayId?.let { dayId -> DaySelection(target.plan.id, dayId) }
                if (previous != null && previous != current) phoneRest.skip()
                previous = current
            }
        }
        viewModelScope.launch {
            maybeAmendCardioDistances()
            effectiveDayId.collect { day ->
                if (day != pinHeldFor) pinHeldFor = null
            }
        }
        viewModelScope.launch {
            combine(cardioRun, timedRepEvent, holdEvent, deferredCardioReceipt) { run, reps, hold, receipt ->
                run == null && reps == null && hold == null && receipt != null
            }.distinctUntilChanged().collect { ready ->
                if (ready) mutationLock.withLock { showDeferredCardioReceipt() }
            }
        }
        viewModelScope.launch {
            combine(cardioRun, timedRepEvent, holdEvent, pastCardioReceiptId) { run, reps, hold, receipt ->
                receipt.takeIf { run == null && reps == null && hold == null }
            }.distinctUntilChanged().collect { receiptId ->
                if (receiptId != null) recoverPastCardioReceipt()
            }
        }
        viewModelScope.launch {
            saveRoute.filterNotNull().collect { on ->
                cardioRun.value?.takeIf { it.block.activity.usesGps }?.let { gps.keepRoute(it.runId, on) }
            }
        }
        viewModelScope.launch {
            gps.reached.collect { runId ->
                if (cardioRun.value?.runId == runId) cardioAlarm.buzz()
            }
        }
        viewModelScope.launch {
            gps.lastFix.collect {
                val run = cardioRun.value ?: return@collect
                if (cardioAlertAnchor != null) return@collect
                val end = run.block.sets.getOrNull(run.position)?.end as? Measure.Time ?: return@collect
                if (isPocketed(run) && run.running && run.elapsedMillis(cardioClock) > end.seconds * 1000L) {
                    reconcileCardio()
                    afterPocketStep(run)
                    cardioTick.value++
                }
            }
        }
        viewModelScope.launch {
            gpsEnabled.collect { enabled ->
                if (!enabled) {
                    cardioRun.value?.takeIf { it.block.activity.usesGps }?.let { gps.end(it.runId) }
                    coolDownGps()
                } else {
                    cardioRun.value?.takeIf { it.running }?.let(::beginCardioGps)
                    refreshGpsWarmup(uiState.value)
                }
            }
        }
        if (savedState.get<Boolean>(Routes.DAY_EDITOR_ARG) != true) viewModelScope.launch {
            dayState.map { state -> state.planId?.takeIf { !state.loading }?.let { plan -> state.viewDayId?.let { plan to it } } }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (planId, dayId) -> mutationLock.withLock { restoreCardioRun(planId, dayId) } }
        }
    }

    // --- user intents --------------------------------------------------------

    fun selectDay(dayId: String) {
        selectDay(DaySelection(dayState.value.planId, dayId))
    }

    fun selectDay(selection: DaySelection) {
        pinHeldFor = null
        val previous = DaySelection(dayState.value.planId, currentDay().orEmpty())
        savedState[KEY_VIEW_SELECTION] = selection
        if (selection == previous) return
        closeUndoWindow()
        editor.setDayEditorOpen(false)
        savedState[KEY_SELECTED_PAGE] = null
        closeSheet()
    }

    fun applyCardioNextTime(block: CardioBlock) {
        val state = uiState.value
        val planId = state.planId ?: return
        val dayId = state.viewDayId ?: return
        mutate {
            if (uiState.value.planId != planId || currentDay() != dayId || cardioRun.value != null) return@mutate
            val offer = uiState.value.cardio?.nextTime as? CardioNextTime.Offer
            if (offer?.sessionId != null) {
                repo.setRunOfferApplied(offer.sessionId, !offer.applied)
            } else repo.setCardioWorking(planId, dayId, block)
        }
    }

    private fun routineSheet(kind: RoutineKind): RoutineSheetState? = when (kind) {
        RoutineKind.WARM_UP -> uiState.value.warmUp
        RoutineKind.COOL_DOWN -> uiState.value.coolDown
    }

    private fun routineKey(kind: RoutineKind): String = when (kind) {
        RoutineKind.WARM_UP -> KEY_WARM_UP
        RoutineKind.COOL_DOWN -> KEY_COOL_DOWN
    }

    private fun routineProgress(kind: RoutineKind, sheet: RoutineSheetState): RoutineProgress =
        savedState.get<RoutineProgress>(routineKey(kind))?.takeIf { it.owner == sheet.owner }
            ?: RoutineProgress(sheet.owner, sheet.moves.map { it.name }, emptyList(), null)

    fun toggleRoutineMove(kind: RoutineKind, move: Move) {
        val sheet = routineSheet(kind) ?: return
        if (move !in sheet.moves) return
        val progress = routineProgress(kind, sheet)
        savedState[routineKey(kind)] = progress.copy(checked =
            if (move.name in progress.checked) progress.checked - move.name else progress.checked + move.name)
    }

    fun startRoutineTimer(kind: RoutineKind) {
        val sheet = routineSheet(kind) ?: return
        savedState[routineKey(kind)] = routineProgress(kind, sheet).copy(timerStartedAt = clock.millis())
    }

    fun stopRoutineTimer(kind: RoutineKind) {
        val sheet = routineSheet(kind) ?: return
        savedState[routineKey(kind)] = routineProgress(kind, sheet).copy(timerStartedAt = null)
    }

    /** Done, Skip and dismissal only close the sheet; they never log workout data. */
    fun closeRoutine(kind: RoutineKind) {
        val sheet = routineSheet(kind) ?: return
        val runId = sheet.runId
        if (runId == null) {
            savedState[routineKey(kind)] = null
            return
        }
        savedState[when (kind) {
            RoutineKind.WARM_UP -> KEY_CLOSED_WARM_UP_OWNER
            RoutineKind.COOL_DOWN -> KEY_CLOSED_COOL_DOWN_OWNER
        }] = sheet.owner
        viewModelScope.launch {
            when (kind) {
                RoutineKind.WARM_UP -> settings.closeWarmUp(runId)
                RoutineKind.COOL_DOWN -> settings.closeCoolDown(runId)
            }
            if (savedState.get<RoutineProgress>(routineKey(kind))?.owner == sheet.owner) {
                savedState[routineKey(kind)] = null
            }
        }
    }

    fun requestCardioStart() {
        val prefs = routinePrefs.value
        val state = uiState.value
        val planId = state.planId
        val dayId = state.viewDayId
        if (prefs?.warmUp == true && state.exercises.isEmpty() && state.cardio != null &&
            state.cardio.run == null && state.cardio.loggedSeconds == null && cardioRun.value == null &&
            planId != null && dayId != null) {
            savedState[KEY_WARM_UP] = RoutineProgress(RoutineProgress.cardioStartOwner(planId, dayId),
                state.warmUpMoves.map { it.name }, emptyList(), null)
        } else startCardio()
    }

    fun startCardioAfterWarmUp() {
        savedState[KEY_WARM_UP] = null
        startCardio()
    }

    fun startCardio() {
        if (setTimerBusy) return
        val existing = cardioRun.value
        if (existing != null) {
            if (!existing.running && !existing.complete && existing.dayId == currentDay()) {
                saveCardioRun(existing.start(cardioClock).also(::beginCardioGps))
            }
            return
        }
        mutate {
            if (cardioRun.value != null || setTimerBusy) return@mutate
            val run = newCardioRun() ?: return@mutate
            if (!setTimerBusy) saveCardioRun(run.start(cardioClock).also(::beginCardioGps))
        }
    }

    private suspend fun newCardioRun(): CardioRun? {
        val dayId = currentDay() ?: return null
        val planId = dayState.value.takeIf { it.viewDayId == dayId }?.planId ?: return null
        val plan = plans.plansFlow.first().firstOrNull { it.id == planId } ?: return null
        val program = plans.programFor(plan.id).first()
        val day = program.days.firstOrNull { it.id == dayId } ?: return null
        val block = day.cardioToRun ?: return null
        val (sessions, sets) = cardioHistory.first()
        val opening = DayScreenBuilder.cardioOpening(day, plan, sessions, sets, cardioClock.wallMillis(), program, settings.unitFlow.first(), repo.zone) ?: return null
        val nextId = Rotation.upNextAfter(program, plan.suggestedDayId, dayId)
        val next = program.days.firstOrNull { it.id == nextId }
        if (currentDay() != dayId || dayState.value.planId != plan.id) return null
        repo.clearFinishUndoForDay(plan.id, dayId)
        return CardioRun(plan.id, plan.name, dayId, Program.accentIndex(dayId),
            CardioBlockJson.encode(block), CardioBlockJson.encode(opening),
            day.exercises.isEmpty(), next?.let { GlanceLines.dayLine(it.id, it.title) })
    }

    private fun saveCardioRun(run: CardioRun) {
        savedState[KEY_CARDIO_RUN] = run
        watchCardioDistance(run)
        persistCardioRun()
    }

    private fun persistCardioRun() {
        val run = cardioRun.value ?: return
        cardioRunStore.save(StoredCardioRun(cardioClock.wallMillis(), run, gpsSetsFor(run), sprintTimesFor(run),
            readingInputs.value.filter { it.runId == run.runId }, runDistance.value?.takeIf { it.runId == run.runId }))
    }

    private fun watchCardioDistance(run: CardioRun) {
        val end = run.block.sets.getOrNull(run.position)?.end as? Measure.Distance
        if (run.block.activity.usesGps && run.running && end != null &&
            end.metres >= DistanceTrack.MIN_DISTANCE_END_METRES) {
            gps.watch(run.runId, run.anchorElapsedMillis(cardioClock), end.metres)
        } else gps.watch(run.runId, null, null)
    }

    private fun gpsSetsFor(run: CardioRun): List<CardioGpsSet> = gpsSets.value.filter { it.runId == run.runId }

    private fun clearGpsStamps(runId: String) {
        val kept = gpsSets.value.filterNot { it.runId == runId }
        if (kept.size != gpsSets.value.size) savedState[KEY_CARDIO_GPS_SETS] = ArrayList(kept)
    }

    private fun stepCardio(run: CardioRun, step: (CardioExecution, SetMeter?) -> CardioExecution): CardioRun {
        val (next, stamps) = run.stepped { step(it, if (run.block.activity.usesGps && gpsEnabled.value) gps.meter(run.runId) else null) }
        if (stamps.isNotEmpty()) {
            val merged = gpsSets.value.filterNot { old -> stamps.any { it.runId == old.runId && it.position == old.position } } + stamps
            if (merged != gpsSets.value) savedState[KEY_CARDIO_GPS_SETS] = ArrayList(merged)
        }
        return next
    }

    private fun beginCardioGps(run: CardioRun) {
        if (!run.block.activity.usesGps || !gpsEnabled.value || !cardioScreenLive) return
        val access = gps.access()
        if (access != LocationAccess.PRECISE && access != LocationAccess.LOCATION_OFF && gpsAsk.value == null) {
            savedState[KEY_GPS_ASK] = run.runId
        }
        gps.begin(run.runId, run.block.activity, requireNotNull(run.startedAt))
        saveRoute.value?.let { gps.keepRoute(run.runId, it) }
        watchCardioDistance(run)
    }

    private fun coolDownGps() {
        if (gps.state.value == GpsState.Tracking(null) || gps.state.value == GpsState.LocationOff(null)) gps.coolDown()
    }

    private fun refreshGpsWarmup(state: DayUiState) {
        val cardio = state.cardio ?: return coolDownGps()
        val block = cardio.block
        val selected = (state.workout?.shown as? WorkoutSelection.Shown.Group)?.key == "cardio"
        if (cardioScreenLive && selected && block?.activity?.usesGps == true &&
            cardio.loggedSeconds == null && cardio.run?.complete != true && cardio.run?.running != true &&
            gpsEnabled.value
        ) gps.warmUp(block.activity) else coolDownGps()
    }

    fun answerLocation(answer: LocationAnswer) {
        val asked = gpsAsk.value
        savedState[KEY_GPS_ASK] = null
        when (answer) {
            LocationAnswer.PRECISE -> cardioRun.value?.takeIf { it.runId == asked }?.let(::beginCardioGps)
            LocationAnswer.APPROXIMATE -> {
                viewModelScope.launch { settings.setGpsDistance(false) }
                mutableGpsNotice.value = GpsNotice.NEEDS_PRECISE
            }
            LocationAnswer.DENIED, LocationAnswer.BLOCKED -> {
                viewModelScope.launch { settings.setGpsDistance(false) }
                mutableGpsNotice.value = GpsNotice.OFF
            }
        }
    }

    fun gpsNoticeShown() { mutableGpsNotice.value = null }

    private fun cardioGpsState(block: CardioBlock?, run: CardioRun?): CardioGpsState? {
        if (block?.activity?.usesGps != true || !gpsEnabled.value || run?.complete == true) return null
        return when (val location = gps.state.value) {
            is GpsState.LocationOff -> CardioGpsState(GpsShown.LOCATION_OFF)
            is GpsState.Tracking -> {
                val live = gps.live(location.runId,
                    run?.takeIf { it.running }?.anchorElapsedMillis(cardioClock), cardioClock.elapsedRealtimeMillis())
                    ?: return null
                val shown = if (run?.running != true) {
                    if (live.status == GpsLiveStatus.LIVE) GpsShown.READY else GpsShown.FINDING
                } else when (live.status) {
                    GpsLiveStatus.FINDING -> GpsShown.FINDING
                    GpsLiveStatus.LIVE -> GpsShown.LIVE
                    GpsLiveStatus.NO_SIGNAL -> GpsShown.NO_SIGNAL
                    GpsLiveStatus.PARTIAL -> GpsShown.PARTIAL
                }
                CardioGpsState(shown, live.metres, live.metresPerSecond)
            }
            else -> null
        }
    }

    private fun CardioRun.matches(action: CardioSetAction): Boolean =
        runId == action.runId && dayId == action.dayId && position == action.setIndex

    private fun runDistanceFor(run: CardioRun): CardioRunDistance? =
        runDistance.value?.takeIf { it.runId == run.runId && it.position == run.position }

    private fun distanceUnitFor(run: CardioRun): DistanceUnit =
        DistanceUnit.forActivity(distanceUnit.value, run.block.activity)

    private fun distanceInputText(run: CardioRun, position: Int, meters: String): String {
        val unit = distanceUnitFor(run)
        return distanceInputs.value.firstOrNull {
            it.runId == run.runId && it.position == position && it.unit == unit
        }?.text ?: meters.toIntOrNull()?.takeIf { it > 0 }?.let(unit::inputText).orEmpty()
    }

    private fun saveDistanceInput(run: CardioRun, position: Int, text: String): String {
        val unit = distanceUnitFor(run)
        savedState[KEY_CARDIO_DISTANCE_INPUTS] = ArrayList(distanceInputs.value.filterNot {
            it.runId == run.runId && it.position == position
        } + CardioDistanceInput(run.runId, position, text, unit))
        return if (text.isBlank()) "" else text.toDoubleOrNull()?.let(unit::toMetres)?.toString() ?: "0"
    }

    private fun clearDistanceInputs(runId: String) {
        savedState[KEY_CARDIO_READING_INPUTS] = ArrayList(readingInputs.value.filterNot { it.runId == runId })
        savedState[KEY_CARDIO_DISTANCE_INPUTS] = ArrayList(distanceInputs.value.filterNot { it.runId == runId })
    }

    private fun acceptsDistanceText(text: String, unit: DistanceUnit): Boolean =
        if (unit == DistanceUnit.M) text.all { it in '0'..'9' }
        else text.matches(Regex("[0-9]*(\\.[0-9]{0,2})?"))

    fun changeCardioRunDistance(text: String) {
        val run = cardioRun.value?.takeIf { it.running && it.block.openEnded } ?: return
        if (acceptsDistanceText(text, distanceUnitFor(run))) {
            savedState[KEY_CARDIO_RUN_DISTANCE] = CardioRunDistance(run.runId, run.position,
                saveDistanceInput(run, run.position, text))
            persistCardioRun()
        }
    }

    fun stopCardio(action: CardioSetAction) {
        val run = reconcileCardio(waitForFix = false) ?: return
        if (!run.matches(action) || !run.running || run.complete) return
        cardioAlarm.cancel()
        cardioAlertAnchor = null
        val finished = if (run.block.openEnded) {
            val metres = runDistanceFor(run)?.metres()
            val elapsed = cardioElapsedSeconds(run)
            val passedEnd = FinishedRule.awaitsReading(CardioSetResult(run.block.sets[run.position], CardioSetStatus.FINISHED, elapsed))
            stepCardio(run) { exec, meter ->
                exec.finishStep(CardioSetStatus.FINISHED, elapsed, cardioClock.clocks(), metres = metres,
                    requestDistance = metres == null || passedEnd, gps = meter?.let { exec.measure(it, cardioClock.clocks()) })
            }
                .let { done -> if (metres == null) done else done.copy(drafts = done.drafts.map {
                    if (it.position == run.position) it.copy(text = metres.toString()) else it
                }) }
        } else stepCardio(run) { exec, meter ->
            exec.finishStep(CardioSetStatus.SHORTENED, cardioElapsedSeconds(run), cardioClock.clocks(),
                gps = meter?.let { exec.measure(it, cardioClock.clocks()) })
        }
        if (run.block.openEnded) clearDistanceInputs(run.runId)
        savedState[KEY_CARDIO_RUN_DISTANCE] = null
        saveCardioRun(finished)
        if (cardioScreenLive && gpsSetsFor(finished).isNotEmpty() && finished.drafts.any { !it.saved }) openCardioDistance()
        maybeLogCardio()
    }

    private fun sprintTimesFor(run: CardioRun): List<CardioSprintTime> =
        sprintTimes.value.filter { it.runId == run.runId }

    private fun sprintRun(action: CardioSetAction): CardioRun? = cardioRun.value?.takeIf { run ->
        run.matches(action) && run.running && !run.complete && uiState.value.cardio?.sprintRep != null &&
            run.block.sets[run.position].let { it.role == CardioRole.WORK && it.hard && it.end is Measure.Distance }
    }

    private fun readSprintTimer(run: CardioRun): SprintTimerState? {
        val timer = sprintTimer.value ?: return null
        if (timer.runId != run.runId || timer.position != run.position || !run.running ||
            timer.bootCount != cardioClock.bootCount() ||
            cardioClock.elapsedRealtimeMillis() - timer.beepElapsedMillis > 600_000L) {
            clearSprintTimer()
            return null
        }
        return timer
    }

    private fun clearSprintTimer() {
        sprintBeepJob?.cancel()
        sprintBeepJob = null
        savedState[KEY_SPRINT_TIMER] = null
    }

    private fun scheduleSprintBeep(timer: SprintTimerState) {
        sprintBeepJob = viewModelScope.launch {
            delay((timer.beepElapsedMillis - cardioClock.elapsedRealtimeMillis()).coerceAtLeast(0L))
            val run = cardioRun.value ?: return@launch
            if (readSprintTimer(run) == timer) sprintBeeper.beep()
        }
    }

    fun armSprintTimer(action: CardioSetAction) {
        val run = sprintRun(action) ?: return
        if (readSprintTimer(run) != null) return
        val timer = SprintTimerState(run.runId, run.position,
            cardioClock.elapsedRealtimeMillis() + SPRINT_BEEP_DELAY_MILLIS, cardioClock.bootCount())
        savedState[KEY_SPRINT_TIMER] = timer
        scheduleSprintBeep(timer)
    }

    private fun currentSprintProtocol(): StartProtocol =
        sprintProtocol.value?.let { name -> StartProtocol.entries.firstOrNull { it.name == name } }
            ?: uiState.value.cardio?.sprintRep?.protocol ?: StartProtocol.STANDING

    fun stopSprintTimer(action: CardioSetAction) {
        val run = sprintRun(action) ?: return
        val timer = readSprintTimer(run) ?: return
        val centis = sprintCentis(timer, cardioClock.elapsedRealtimeMillis())
        clearSprintTimer()
        if (centis != null) saveSprintTime(action, centis, TimingMethod.PHONE, currentSprintProtocol())
    }

    fun saveSprintTime(action: CardioSetAction, centis: Int, method: TimingMethod, protocol: StartProtocol) {
        val run = sprintRun(action) ?: return
        if (centis !in 1..SprintTime.MAX_CENTIS) return
        savedState[KEY_SPRINT_TIMES] = ArrayList(sprintTimes.value +
            CardioSprintTime(run.runId, run.position, centis, method.name, protocol.name))
        savedState[KEY_SPRINT_PROTOCOL] = protocol.name
        savedState[KEY_SPRINT_TIME_DRAFT] = null
        persistCardioRun()
        clearSprintTimer()
        reachCardioTarget(action)
    }

    fun setSprintProtocol(name: String) {
        if (StartProtocol.entries.none { it.name == name }) return
        savedState[KEY_SPRINT_PROTOCOL] = name
    }

    fun openSprintTime(action: CardioSetAction) {
        val run = sprintRun(action) ?: return
        if (readSprintTimer(run) != null) return
        savedState[KEY_SPRINT_TIME_DRAFT] = SprintTimeDraft(run.runId, run.position, "", TimingMethod.HAND.name,
            currentSprintProtocol().name)
    }

    fun changeSprintTimeDraft(draft: SprintTimeDraft) {
        val current = sprintTimeDraft.value ?: return
        if (current.runId == draft.runId && current.position == draft.position) savedState[KEY_SPRINT_TIME_DRAFT] = draft
    }

    fun dismissSprintTime() { savedState[KEY_SPRINT_TIME_DRAFT] = null }

    fun saveSprintTimeDraft(centis: Int, method: TimingMethod, protocol: StartProtocol) {
        val draft = sprintTimeDraft.value ?: return
        val run = cardioRun.value?.takeIf { it.runId == draft.runId && it.position == draft.position } ?: return
        saveSprintTime(run.action(), centis, method, protocol)
    }

    fun reachCardioTarget(action: CardioSetAction) {
        val run = reconcileCardio(waitForFix = false) ?: return
        if (!run.matches(action) || !run.running || run.complete) return
        if (run.block.sets[run.position].end !is Measure.Distance) return
        saveCardioRun(stepCardio(run) { exec, meter -> exec.reachTarget(cardioClock.clocks(), meter) })
        maybeLogCardio()
    }

    fun skipCardioSet(action: CardioSetAction) {
        mutate {
            if (action.dayId != currentDay()) return@mutate
            val existing = cardioRun.value
            if (existing?.runId != action.runId || (existing?.position ?: 0) != action.setIndex) return@mutate
            val run = existing ?: newCardioRun() ?: return@mutate
            if (setTimerBusy || run.running || run.complete) return@mutate
            saveCardioRun(run.finish(CardioSetStatus.SKIPPED, 0, cardioClock))
            maybeLogCardio()
        }
    }

    fun endCardioBlock() {
        mutate {
            if (setTimerBusy) return@mutate
            val run = reconcileCardio(waitForFix = false) ?: newCardioRun() ?: return@mutate
            endCardioRun(run)
        }
    }

    private fun endCardioRun(run: CardioRun) {
        var ended = stepCardio(run) { exec, meter -> exec.reconcile(cardioClock.clocks(), meter, waitForFix = false) }
        if (ended.running) {
            val active = ended
            ended = stepCardio(active) { exec, meter ->
                exec.finishStep(if (active.block.openEnded) CardioSetStatus.FINISHED else CardioSetStatus.SHORTENED,
                    cardioElapsedSeconds(active), cardioClock.clocks(),
                    metres = runDistanceFor(active)?.metres().takeIf { active.block.openEnded }, requestDistance = false,
                    gps = meter?.let { exec.measure(it, cardioClock.clocks()) })
            }
        }
        if (run.block.openEnded) clearDistanceInputs(run.runId)
        savedState[KEY_CARDIO_RUN_DISTANCE] = null
        while (!ended.complete) ended = ended.finish(CardioSetStatus.SKIPPED, 0, cardioClock)
        cardioAlarm.cancel()
        cardioAlertAnchor = null
        saveCardioRun(ended)
        maybeLogCardio()
    }

    fun swapCardio(activity: CardioActivity, template: CardioTemplate?) {
        val dayId = currentDay() ?: return
        mutate {
            if (currentDay() != dayId || uiState.value.cardio?.canSwap != true) return@mutate
            val run = reconcileCardio() ?: newCardioRun() ?: return@mutate
            if (run.dayId != dayId || run.startedAt != null || run.results.isNotEmpty()) return@mutate
            val block = template?.block(activity) ?: run.block.copy(activity = activity)
            if (block != run.block) saveCardioRun(run.copy(blockJson = CardioBlockJson.encode(block)))
        }
    }

    fun addCardioSet() {
        val dayId = currentDay() ?: return
        mutate {
            if (currentDay() != dayId || cardioRun.value?.let { it.dayId != dayId } == true) return@mutate
            val run = reconcileCardio() ?: newCardioRun() ?: return@mutate
            if (run.dayId != dayId || !run.canAddSet || setTimerBusy) return@mutate
            val block = run.block
            val workIndex = block.sets.indexOfLast { it.role == CardioRole.WORK }
            val work = block.sets.getOrNull(workIndex) ?: block.sets[run.position].copy(role = CardioRole.WORK)
            val recovery = block.sets.getOrNull(workIndex + 1)?.takeIf { it.role == CardioRole.RECOVERY }
                ?: block.sets.lastOrNull { it.role == CardioRole.RECOVERY }
                ?: CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120))
            val additions = listOf(work, recovery)
            val insert = block.sets.indices.firstOrNull {
                block.sets[it].role == CardioRole.COOLDOWN &&
                    (it > run.position || (it == run.position && !run.running))
            } ?: block.sets.size
            saveCardioRun(run.copy(blockJson = CardioBlockJson.encode(block.copy(
                sets = block.sets.take(insert) + additions + block.sets.drop(insert),
            ))))
        }
    }

    fun openCardioSets() {
        val day = currentDay() ?: return
        savedState[KEY_OPEN_SHEET] = OpenSheet(day, OpenSheet.Kind.CARDIO_SETS, 0, 0)
    }

    private fun distanceRuns(): List<CardioRun> = listOfNotNull(cardioRun.value) + pendingCardioDistances.value

    private fun distanceRun(action: CardioSetAction): CardioRun? = distanceRuns().firstOrNull {
        it.runId == action.runId && it.dayId == action.dayId && it.drafts.any { draft -> draft.position == action.setIndex }
    }

    private fun saveDistanceRun(run: CardioRun) {
        if (cardioRun.value?.runId == run.runId) saveCardioRun(run)
        else savedState[KEY_CARDIO_DISTANCES] = ArrayList(pendingCardioDistances.value.map {
            if (it.runId == run.runId) run else it
        })
    }

    fun requestCardioDistance(action: CardioSetAction) {
        val run = distanceRun(action) ?: return
        if (run.dayId != currentDay() || run.planId != uiState.value.planId) return
        saveDistanceRun(run.copy(drafts = run.drafts.map {
            if (it.position == action.setIndex) it.copy(requested = false, dismissed = false, saved = false) else it
        }))
    }

    fun openCardioDistance() {
        if (openSheet.value != null) return
        val state = uiState.value
        if (state.viewDayId != currentDay()) return
        val run = distanceRuns().firstOrNull { entry ->
            entry.dayId == currentDay() && entry.planId == state.planId &&
                entry.drafts.any { !it.saved && !it.requested && !it.dismissed }
        } ?: return
        val draft = run.drafts.first { !it.saved && !it.requested && !it.dismissed }
        saveDistanceRun(run.copy(drafts = run.drafts.map {
            if (it.position == draft.position) it.copy(requested = true) else it
        }))
        savedState[KEY_OPEN_SHEET] = OpenSheet(run.dayId, OpenSheet.Kind.CARDIO_DISTANCE, 0, draft.position, run.runId)
    }

    private fun distanceSheetAction(): CardioSetAction? = openSheet.value
        ?.takeIf { it.kind == OpenSheet.Kind.CARDIO_DISTANCE }
        ?.let { CardioSetAction(it.dayId, it.cardioRunId, it.setIndex) }

    fun changeCardioDistance(text: String) {
        val action = distanceSheetAction() ?: return
        val run = distanceRun(action) ?: return
        if (!acceptsDistanceText(text, distanceUnitFor(run))) return
        val stamps = gpsSets.value.map { if (it.runId == run.runId && it.position == action.setIndex) it.copy(typed = true) else it }
        if (stamps != gpsSets.value) savedState[KEY_CARDIO_GPS_SETS] = ArrayList(stamps)
        val meters = saveDistanceInput(run, action.setIndex, text)
        saveDistanceRun(run.copy(drafts = run.drafts.map { if (it.position == action.setIndex) it.copy(text = meters) else it }))
    }

    fun changeCardioReading(text: String) {
        val action = distanceSheetAction() ?: return
        val run = distanceRun(action) ?: return
        val unit = distanceUnitFor(run)
        val accepted = when (run.block.sets[action.setIndex].end) {
            is Measure.Time -> acceptsDistanceText(text, unit)
            is Measure.Distance -> text.matches(Regex("[0-9]{0,3}(:[0-9]{0,2})?"))
        }
        if (!accepted) return
        savedState[KEY_CARDIO_READING_INPUTS] = ArrayList(readingInputs.value.filterNot {
            it.runId == run.runId && it.position == action.setIndex
        } + CardioReadingInput(run.runId, action.setIndex, text, unit))
        persistCardioRun()
    }

    fun saveCardioDistance(action: CardioSetAction) {
        mutate {
            if (distanceSheetAction() != action) return@mutate
            val run = distanceRun(action) ?: return@mutate
            val position = action.setIndex
            val draft = run.drafts.firstOrNull { it.position == position && it.valid() && !it.saved } ?: return@mutate
            val awaiting = CardioReadingEntry.awaiting(run, position, draft.metres())
            val input = readingInputs.value.firstOrNull { it.runId == run.runId && it.position == position }
                ?.takeIf { awaiting?.spec?.end !is Measure.Time || it.unit == distanceUnitFor(run) }
            if (awaiting != null && input != null && input.text.isNotBlank()) {
                if (CardioReadingEntry.check(awaiting, input.text, input.unit) != CardioReadingEntry.Check.VALID) return@mutate
            } else {
                savedState[KEY_CARDIO_READING_INPUTS] = ArrayList(readingInputs.value.filterNot {
                    it.runId == run.runId && it.position == position
                })
            }
            val next = run.enterDistance(position, draft.metres()).copy(
                drafts = run.drafts.map { if (it.position == position) it.copy(saved = true, amended = false) else it },
                saveFailed = false,
            )
            // Persist the intent before amending; a restored draft can retry the same set.
            saveDistanceRun(next)
            savedState[KEY_OPEN_SHEET] = null
            maybeLogCardio()
            maybeAmendCardioDistances()
        }
    }

    fun retryCardioSave() {
        cardioRun.value?.let { saveCardioRun(it.copy(saveFailed = false)) }
        savedState[KEY_CARDIO_DISTANCES] = ArrayList(pendingCardioDistances.value.map { it.copy(saveFailed = false) })
        maybeLogCardio()
        maybeAmendCardioDistances()
        recoverPastCardioReceipt()
    }

    private fun cardioElapsedSeconds(run: CardioRun): Int =
        (run.elapsedMillis(cardioClock) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun reconcileCardio(waitForFix: Boolean = true): CardioRun? {
        val before = cardioRun.value ?: return null
        val after = stepCardio(before) { exec, meter -> exec.reconcile(cardioClock.clocks(), meter, waitForFix) }
        if (after != before) {
            saveCardioRun(after)
            if (cardioAlertAnchor == cardioAlarmIdentity(before)) {
                cardioAlarm.cancel()
                cardioAlarm.buzz()
            }
            cardioAlertAnchor = null
            maybeLogCardio()
        }
        return after
    }

    private fun cardioAlarmIdentity(run: CardioRun): String = "cardio:${run.startedAt}:${run.position}:${run.wallStart}"

    fun openPastCardio() {
        val state = uiState.value
        val block = state.cardio?.block ?: return
        val planId = state.planId ?: return
        val dayId = state.viewDayId ?: return
        if (executionActive || _pastCardioSaving.value) return
        val seconds = if (block.sets.all { it.end is Measure.Time }) {
            block.sets.sumOf { (it.end as Measure.Time).seconds }
        } else 1800
        val startedAt = defaultPastCardioStart(seconds)
        savedState[KEY_PAST_CARDIO_START_SET] = null
        savedState[KEY_PAST_CARDIO_DAY] = DaySelection(planId, dayId)
        savedState[KEY_PAST_CARDIO_EFFORT] = null
        savedState[KEY_PAST_CARDIO] = CardioEntryDraft(block.activity, startedAt, seconds)
        _pastCardioSaveFailed.value = false
        _pastCardioCollision.value = false
    }

    fun setPastCardioEffort(effort: CardioEffort?) {
        if (pastCardio.value != null && !_pastCardioSaving.value) savedState[KEY_PAST_CARDIO_EFFORT] = effort
    }

    fun changePastCardio(draft: CardioEntryDraft) {
        val old = pastCardio.value ?: return
        if (_pastCardioSaving.value) return
        val updated = when {
            draft.startedAt != old.startedAt -> {
                savedState[KEY_PAST_CARDIO_START_SET] = true
                draft
            }
            savedState.get<Boolean>(KEY_PAST_CARDIO_START_SET) != true && draft.seconds != old.seconds &&
                draft.seconds in 1..ManualCardio.MAX_SECONDS -> draft.copy(startedAt = defaultPastCardioStart(draft.seconds))
            else -> draft
        }
        savedState[KEY_PAST_CARDIO] = updated.copy(note = updated.note.take(NOTE_MAX_LENGTH))
        _pastCardioSaveFailed.value = false
        _pastCardioCollision.value = false
    }

    private fun defaultPastCardioStart(seconds: Int): Long =
        Math.floorDiv(cardioClock.wallMillis() - seconds * 1_000L, 60_000L) * 60_000L

    fun dismissPastCardio() {
        if (_pastCardioSaving.value) return
        savedState[KEY_PAST_CARDIO_START_SET] = null
        savedState[KEY_PAST_CARDIO] = null
        savedState[KEY_PAST_CARDIO_DAY] = null
        savedState[KEY_PAST_CARDIO_EFFORT] = null
        _pastCardioSaveFailed.value = false
        _pastCardioCollision.value = false
    }

    fun savePastCardio() {
        if (_pastCardioSaving.value || pastCardio.value == null) return
        _pastCardioSaving.value = true
        _pastCardioCollision.value = false
        viewModelScope.launch {
            try {
                mutationLock.withLock {
                    val draft = pastCardio.value ?: return@withLock
                    val selection = pastCardioDay.value ?: return@withLock
                    val planId = selection.planId ?: return@withLock
                    val dayId = selection.dayId
                    if (executionActive || !draft.valid(cardioClock.wallMillis())) return@withLock
                    val day = plans.programFor(planId).first().days.firstOrNull { it.id == dayId } ?: return@withLock
                    val finishDay = day.exercises.isEmpty()
                    val summary = ManualCardio.summary(draft.activity, draft.seconds)
                    val results = listOf(ManualCardio.result(draft.seconds, draft.metres))
                    if (finishDay) savedState[KEY_VIEW_SELECTION] = selection
                    val entry = CardioSessionEntity(
                        dayId = dayId, mode = summary.mode.name, hard = false, label = summary.label,
                        startedAt = draft.startedAt, completedAt = draft.completedAt, seconds = draft.seconds,
                        stepsCompleted = 1, planId = planId, activity = draft.activity.name, note = draft.note,
                        effort = pastCardioEffort.value?.name,
                    )
                    val id = repo.logCardioSession(entry, results, finishDay = finishDay)
                    val session = requireNotNull(historyRepository.cardioSession(id))
                    // Retry identity omits the plan and contents; only matching work acknowledges this draft.
                    val expected = entry.copy(id = id, planName = session.planName,
                        note = entry.note.trim().take(NOTE_MAX_LENGTH))
                    if (session.copy(progressionUndoJson = null, increaseApplied = false) != expected || historyRepository.cardioSetsFor(id) != results) {
                        _pastCardioSaveFailed.value = true
                        _pastCardioCollision.value = true
                        return@withLock
                    }
                    if (repo.openRunFlow.first()?.let { it.planId == planId && it.dayId == dayId && !it.draft } != true) {
                        savedState[KEY_PAST_CARDIO_RECEIPT_ID] = id
                    }
                    savedState[KEY_PAST_CARDIO_START_SET] = null
                    savedState[KEY_PAST_CARDIO] = null
                    savedState[KEY_PAST_CARDIO_DAY] = null
                    savedState[KEY_PAST_CARDIO_EFFORT] = null
                    _pastCardioSaveFailed.value = false
                    viewModelScope.launch { sessionPublisher.publishCardio(id) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _pastCardioSaveFailed.value = true
            } finally {
                _pastCardioSaving.value = false
            }
        }
    }

    private fun recoverPastCardioReceipt() {
        if (pastCardioReceiptId.value == null) return
        viewModelScope.launch {
            mutationLock.withLock {
                val id = pastCardioReceiptId.value ?: return@withLock
                if (executionActive) return@withLock
                try {
                    val session = requireNotNull(historyRepository.cardioSession(id))
                    val dayId = requireNotNull(session.dayId)
                    val activity = CardioActivity.entries.firstOrNull { it.name == session.activity }
                    val standing = try {
                        activity?.let {
                            historyRepository.cardioBestsFor(it, excludedDayId = dayId, excludedStartedAt = session.startedAt)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    finisher.showLoggedCardio(session, historyRepository.cardioSetsFor(id), Program.accentIndex(dayId), standing)
                    if (!executionActive) savedState[KEY_PAST_CARDIO_RECEIPT_ID] = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The session is committed; retry its receipt on return or process restoration.
                }
            }
        }
    }

    private fun maybeLogCardio() {
        val run = cardioRun.value ?: return
        if (!run.complete || run.saveFailed || cardioSaveInFlight) return
        if (run.startedAt == null) return clearCardioExecution()
        cardioSaveInFlight = true
        viewModelScope.launch {
            try {
                mutationLock.withLock {
                    val current = cardioRun.value?.takeIf { it.runId == run.runId } ?: return@withLock
                    val activity = current.block.activity
                    standingBestsFor(current)
                    val summary = current.summary()
                    // Keep entry on its originating day while the committed rotation moves on.
                    if (current.finishesDay && current.showReceipt) {
                        savedState[KEY_VIEW_SELECTION] = DaySelection(current.planId, current.dayId)
                    }
                    val route = gps.route(current.runId).takeIf { it.size >= 2 && saveRoute.value == true }?.let(CardioRouteJson::encode)
                    val strengthOpen = repo.openRunFlow.first()?.let {
                        it.planId == current.planId && it.dayId == current.dayId && !it.draft
                    } == true
                    val id = current.sessionId ?: repo.logCardioSession(CardioSessionEntity(
                        dayId = current.dayId, mode = summary.mode.name,
                        hard = summary.hard,
                        label = summary.label, startedAt = requireNotNull(current.startedAt),
                        completedAt = requireNotNull(current.completedAt),
                        seconds = summary.seconds,
                        stepsCompleted = summary.stepsCompleted,
                        planId = current.planId, planName = current.planName, activity = activity.name,
                    ), current.resultSets(gpsSetsFor(current), sprintTimesFor(current)), finishDay = current.finishesDay, route = route)
                    val nextDayId = plans.plansFlow.first().firstOrNull { it.id == current.planId }?.suggestedDayId
                    val program = plans.programFor(current.planId).first()
                    val nextDay = program.days.firstOrNull { it.id == nextDayId }
                    val latest = cardioRun.value?.takeIf { it.runId == current.runId } ?: return@withLock
                    val logged = latest.copy(
                        sessionId = id,
                        nextDayTitle = nextDay?.let { GlanceLines.dayLine(it.id, it.title) },
                        // Distance can be entered after strength Start; receipt ownership stays with the commit.
                        showReceipt = latest.showReceipt && !strengthOpen,
                    )
                    // Drafts own only amendment state, never the execution slot or alarm.
                    if (logged.drafts.isNotEmpty()) {
                        savedState[KEY_CARDIO_DISTANCES] = ArrayList(
                            pendingCardioDistances.value.filterNot { it.runId == logged.runId } + logged,
                        )
                    }
                    clearCardioExecution(keepDistanceSheet = true, keepRecordState = true)
                    viewModelScope.launch { sessionPublisher.publishCardio(id) }
                    if (logged.drafts.isEmpty()) finishCardioReceipt(logged)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cardioRun.value?.takeIf { it.runId == run.runId }?.let { saveCardioRun(it.copy(saveFailed = true)) }
            } finally {
                cardioSaveInFlight = false
                if (viewModelScope.isActive) {
                    maybeLogCardio()
                    maybeAmendCardioDistances()
                }
            }
        }
    }

    private fun readingAtEnd(run: CardioRun, position: Int): Int? = readingInputs.value.firstOrNull {
        it.runId == run.runId && it.position == position
    }?.let { CardioReadingEntry.value(run.block.sets[position].end, it.text, it.unit) }

    private fun maybeAmendCardioDistances() {
        if (cardioDraftSaveInFlight) return
        val pending = pendingCardioDistances.value.firstOrNull { run ->
            !run.saveFailed && run.drafts.any { it.saved && !it.amended }
        } ?: return
        cardioDraftSaveInFlight = true
        viewModelScope.launch {
            try {
                mutationLock.withLock {
                    val id = requireNotNull(pending.sessionId)
                    standingBestsFor(pending)
                    pending.drafts.filter { it.saved && !it.amended }.forEach { draft ->
                        repo.amendCardioSetDistance(id, draft.position, pending.results[draft.position].metres,
                            readingAtEnd(pending, draft.position))
                        val latest = pendingCardioDistances.value.firstOrNull { it.runId == pending.runId } ?: return@withLock
                        saveDistanceRun(latest.copy(drafts = latest.drafts.map {
                            if (it.position == draft.position && it.saved && it.text == draft.text) it.copy(amended = true) else it
                        }))
                    }
                    val latest = pendingCardioDistances.value.firstOrNull { it.runId == pending.runId } ?: return@withLock
                    if (latest.drafts.all { it.saved && it.amended }) {
                        savedState[KEY_CARDIO_DISTANCES] = ArrayList(pendingCardioDistances.value.filterNot { it.runId == latest.runId })
                        finishCardioReceipt(latest)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pendingCardioDistances.value.firstOrNull { it.runId == pending.runId }?.let {
                    saveDistanceRun(it.copy(saveFailed = true))
                }
            } finally {
                cardioDraftSaveInFlight = false
                if (viewModelScope.isActive) maybeAmendCardioDistances()
            }
        }
    }

    private suspend fun finishCardioReceipt(run: CardioRun) {
        clearGpsStamps(run.runId)
        clearDistanceInputs(run.runId)
        if (!run.showReceipt) {
            clearCardioRecordState(run)
            return
        }
        // Keep the first due receipt when another run finishes before the screen is free.
        if (deferredCardioReceipt.value == null) {
            savedState[KEY_DEFERRED_CARDIO_RECEIPT] = run
        } else {
            clearCardioRecordState(run)
        }
        showDeferredCardioReceipt()
    }

    /** Read current ownership at navigation time, before the UI flow catches up. */
    val executionActive: Boolean get() = cardioRun.value != null || timedRepEvent.value != null || holdEvent.value != null

    private suspend fun showDeferredCardioReceipt() {
        if (executionActive) return
        val run = deferredCardioReceipt.value ?: return
        val coolDownMoves = if (run.finishesDay) {
            val day = repo.observeDay(run.planId, run.dayId).first()
            Routines.coolDown(routineExercises(day), run.block, day.equipment)
        } else emptyList()
        val advancedTo = plans.plansFlow.first().firstOrNull { it.id == run.planId }?.suggestedDayId
        val sessionId = requireNotNull(run.sessionId)
        val standing = try {
            standingBestsFor(run)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val session = historyRepository.cardioSession(sessionId)
        if (executionActive) return
        finisher.showCardio(run, standing?.let { CardioBests.bests(run.block.activity, it.map(SavedCardioStanding::toDomain)) }, session)
        if (executionActive) return
        if (run.finishesDay && routinePrefs.value?.coolDown == true) {
            savedState[KEY_COOL_DOWN] = RoutineProgress(RoutineProgress.cardioOwner(run.runId),
                coolDownMoves.map { it.name }, emptyList(), null)
        }
        clearCardioRecordState(run)
        savedState[KEY_DEFERRED_CARDIO_RECEIPT] = null
        if (currentDay() == run.dayId) {
            savedState[KEY_VIEW_SELECTION] = null
            pinHeldFor = run.dayId.takeIf { advancedTo != it && effectiveDayId.value == it }
            savedState[KEY_SELECTED_PAGE] = null
            closeSheet()
        }
    }

    /** Screen lifecycle controls presentation and GPS warm-up. A running set keeps
     *  its boundary armed until it ends or this ViewModel clears. */
    fun setCardioScreenLive(live: Boolean) {
        cardioScreenLive = live
        if (!live) {
            cardioRun.value?.takeIf { it.running }?.let(::armCardioBoundary)
            cardioRun.value?.takeIf { !it.running && it.block.activity.usesGps }?.let { gps.end(it.runId) }
        } else {
            cardioRun.value?.takeIf { it.running && it.block.activity.usesGps && gpsEnabled.value && gps.meter(it.runId) == null }
                ?.let(::beginCardioGps)
            cardioTick.value++
            recoverPastCardioReceipt()
        }
        refreshGpsWarmup(uiState.value)
    }

    fun startWorkout() {
        val state = dayState.value
        val planId = state.planId ?: return
        val day = state.viewDayId ?: return
        workoutStart.start(DaySelection(planId, day))
    }

    fun dismissStart() {
        savedState[KEY_PENDING_START] = null
        savedState[KEY_PENDING_START_TIMER] = null
        savedState[KEY_PENDING_START_HOLD] = null
        if (holdEvent.value == null) savedState.remove<Boolean>(KEY_HOLD_PAST_TARGET)
    }

    fun confirmStart() {
        mutate {
            val pending = pendingStart.value ?: return@mutate
            val state = dayState.value
            if (state.viewDayId != pending.dayId) return@mutate
            val runId = state.runId ?: return@mutate
            if (pending.startTimer) {
                val planId = state.planId ?: return@mutate
                val timer = timedRepToStart(pending.programExerciseId, pending.index)
                val hold = if (timer == null) holdToStart(pending.programExerciseId, pending.index) else null
                val exerciseId = timer?.exerciseId ?: hold?.exerciseId ?: return@mutate
                val tracks = repo.dayTracksFlow(planId, pending.dayId).first()
                if (tracks.runId != runId || tracks.tracks.firstOrNull {
                        it.programExerciseId == pending.programExerciseId && it.slot == pending.slot && it.exerciseId == exerciseId
                    }?.sets?.getOrNull(pending.index)?.done != false) return@mutate
                if (timer != null) savedState[KEY_PENDING_START_TIMER] = timer else savedState[KEY_PENDING_START_HOLD] = hold
                when (repo.startRun(planId, pending.dayId)) {
                    is RunStartResult.Started, is RunStartResult.AlreadyOpen -> {
                        if (timer != null) savedState[KEY_TIMED_REP_EVENT] = timer else savedState[KEY_HOLD] = hold
                        dismissStart()
                    }
                    is RunStartResult.OtherRunOpen -> reportOtherDay(RunWriteResult.Rejected, planId, pending.dayId)
                    RunStartResult.NoStrengthSlots -> Unit
                }
                return@mutate
            }
            val countEvent = if (pending.timedRep) timedRepEvent.value ?: return@mutate else null
            val slots = if (pending.actionLog && countEvent == null) {
                val planId = state.planId ?: return@mutate
                loggableSlots(repo.dayTracksFlow(planId, pending.dayId).first(),
                    pending.programExerciseId, pending.index, pending.slot)
            } else listOf(pending.slot)
            if (tickSetLocked(pending.dayId, runId, pending.programExerciseId, pending.index,
                    checked = true, slots = slots, countEvent = countEvent, actionLog = pending.actionLog, startOnTick = true)) {
                dismissStart()
            }
        }
    }

    fun startTimedRep(programExerciseId: Long, setIndex: Int) =
        startSetTimer(programExerciseId, setIndex, { timedRepToStart(programExerciseId, setIndex) }, { it.dayId }) {
            savedState[KEY_TIMED_REP_EVENT] = it
        }

    fun startHold(programExerciseId: Long, setIndex: Int) = beginHold(programExerciseId, setIndex, pastTarget = false)

    fun startHoldPastTarget(programExerciseId: Long, setIndex: Int) = beginHold(programExerciseId, setIndex, pastTarget = true)

    private fun beginHold(programExerciseId: Long, setIndex: Int, pastTarget: Boolean) {
        val waiting = holdEvent.value
        if (waiting != null) {
            if (!waiting.running && !waiting.complete && waiting.programExerciseId == programExerciseId &&
                waiting.setIndex == setIndex) {
                savedState[KEY_HOLD] = waiting.copy(running = true, wallStart = cardioClock.wallMillis(),
                    elapsedStart = cardioClock.elapsedRealtimeMillis(), bootCount = cardioClock.bootCount())
            }
            return
        }
        val card = uiState.value.exercises.firstOrNull { it.programExerciseId == programExerciseId }
        val runPastTarget = card?.benchmark != null || (pastTarget && card?.holdPressureCaution != true)
        startSetTimer(programExerciseId, setIndex, { holdToStart(programExerciseId, setIndex) }, { it.dayId },
            onAccepted = { savedState[KEY_HOLD_PAST_TARGET] = runPastTarget }) {
            savedState[KEY_HOLD] = it
        }
    }

    fun stopHold() {
        val event = holdEvent.value?.takeIf { !it.complete } ?: return
        if (!event.running) {
            val ended = event.copy(heldSeconds = event.heldSeconds + List(event.sides - event.heldSeconds.size) { 0 })
            savedState[KEY_HOLD] = ended
            logHold(ended)
            return
        }
        val elapsed = BootAnchor(event.wallStart, event.elapsedStart, event.bootCount).elapsedSince(cardioClock.clocks())
        val held = HoldClock.heldSeconds(elapsed, HoldClock.limitSeconds(event.targetSeconds, holdPastTarget))
        if (held < 1 && event.heldSeconds.isEmpty()) clearHold() else endHoldSide(event, held, buzz = false)
    }

    /** Starts a set timer now when this day's workout is open, else reserves it behind the start sheet. */
    private fun <T : Any> startSetTimer(
        programExerciseId: Long, setIndex: Int, build: () -> T?, dayOf: (T) -> String,
        onAccepted: () -> Unit = {}, save: (T) -> Unit,
    ) {
        val event = build() ?: return
        val state = dayState.value
        val planId = state.planId ?: return
        if (!state.beforeStart && state.openRunElsewhere == null) {
            // Reserve synchronously, so a queued cardio Start cannot replace the alarm.
            onAccepted()
            save(event)
            return
        }
        mutate {
            val current = build()?.takeIf { dayOf(it) == dayOf(event) } ?: return@mutate
            val open = repo.openRunFlow.first()
            if (open != null && (open.planId != planId || open.dayId != dayOf(current))) {
                reportOtherDay(RunWriteResult.Rejected, planId, dayOf(current))
            } else if (open == null || open.draft) {
                if (pendingStart.value != null) return@mutate
                onAccepted()
                closeSheet()
                savedState[KEY_PENDING_START] = PendingStartTick(dayOf(current), programExerciseId, Slot.MAIN, setIndex,
                    actionLog = false, timedRep = false, startTimer = true)
            } else {
                onAccepted()
                save(current)
            }
        }
    }

    private fun timedRepToStart(programExerciseId: Long, setIndex: Int): TimedRepEvent? {
        if (cardioRun.value != null || timedRepEvent.value != null || holdEvent.value != null) return null
        val state = uiState.value
        val day = currentDay() ?: return null
        val card = state.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return null
        val duration = card.timedRepDurationSeconds?.takeIf { it > 0 } ?: return null
        if (state.viewDayId != day || card.focusIndex != setIndex || card.rows.getOrNull(setIndex)?.done != false) return null
        return TimedRepEvent(day, programExerciseId, card.exerciseId, setIndex, duration,
            cardioClock.wallMillis(), cardioClock.elapsedRealtimeMillis(), cardioClock.bootCount())
    }

    fun stopTimedRep() {
        if (timedRepEvent.value?.awaitingCount != false) return
        clearTimedRep()
    }

    /** The screen calls this only when its sheet host is free. */
    fun openTimedRepCount() {
        val event = timedRepEvent.value?.takeIf {
            it.awaitingCount && !it.sheetRequested && !it.sheetDismissed
        } ?: return
        if (openSheet.value?.let { sheetResolves(dayState.value, it, event) } == true ||
            !timedRepMatches(dayState.value, event)
        ) return
        savedState[KEY_TIMED_REP_EVENT] = event.copy(sheetRequested = true, sheetDismissed = false)
        savedState[KEY_OPEN_SHEET] = OpenSheet(
            event.dayId, OpenSheet.Kind.TIMED_REP_COUNT, event.programExerciseId, event.setIndex,
        )
    }

    fun requestTimedRepCount() {
        val event = timedRepEvent.value?.takeIf { it.awaitingCount } ?: return
        savedState[KEY_TIMED_REP_EVENT] = event.copy(sheetRequested = false, sheetDismissed = false)
    }

    fun changeTimedRepDraft(draft: String) {
        val event = timedRepEvent.value?.takeIf { it.awaitingCount } ?: return
        if (draft.isEmpty() || (draft.all(Char::isDigit) && draft.toIntOrNull() != null)) {
            savedState[KEY_TIMED_REP_EVENT] = event.copy(draft = draft)
        }
    }

    fun logTimedRepCount() {
        val event = timedRepEvent.value?.takeIf { it.awaitingCount && it.count != null } ?: return
        tickSet(event.programExerciseId, event.setIndex, true, Slot.MAIN, event)
    }

    override fun onCleared() {
        cardioRun.value?.takeIf { it.block.activity.usesGps }?.let { gps.end(it.runId) }
        coolDownGps()
        if (timedRepEvent.value?.awaitingCount == false || holdEvent.value?.running == true ||
            cardioRun.value?.running == true) cardioAlarm.cancel()
        super.onCleared()
    }

    fun setWorkingWeight(programExerciseId: Long, slot: String, weightLb: Double) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate { repo.setWorkingWeight(runId, planId, day, programExerciseId, slot, weightLb) }
    }

    fun changeWeight(programExerciseId: Long, slot: String, index: Int, newDisplayWeight: Double) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            val unit = settings.unitFlow.first()
            val lb = unit.toLb(newDisplayWeight)
            val programSlot = plans.slotsFor(planId, day).first().firstOrNull { it.programExerciseId == programExerciseId }
                ?: return@mutate
            val track = repo.dayTracksFlow(planId, day).first().tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == slot
            }
            val performed = performedExercise(programSlot, track?.exerciseId, catalog.catalogFlow.first(), slot)
            val tapped = trackFor(day, programExerciseId, slot).getOrNull(index)
            val result = repo.applyToRun(runId, planId, day, listOf(
                TrackEdit(programExerciseId, slot, createsMissing = true) { rows ->
                    if (index !in rows.indices) null else SetEditor.edit(rows, index, SetEdit(weightLb = lb), unit, performed.entry?.equipment.orEmpty())
                },
            ))
            reportOtherDay(result, planId, day)
            if (tapped?.kind == SetKind.TOP && tapped.weightLb != lb) settings.markTopSetHelperSeen()
        }
    }

    fun changeReps(programExerciseId: Long, slot: String, index: Int, newReps: Int) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            changeRepsLocked(day, runId, planId, programExerciseId, slot, index, newReps)
        }
    }

    private suspend fun changeRepsLocked(
        day: String, runId: String, planId: Long, programExerciseId: Long, slot: String, index: Int, newReps: Int,
    ) {
        val tapped = trackFor(day, programExerciseId, slot).getOrNull(index)
        val result = repo.applyToRun(runId, planId, day, listOf(
            TrackEdit(programExerciseId, slot, createsMissing = true) { rows ->
                if (index !in rows.indices) null else SetEditor.edit(rows, index, SetEdit(reps = newReps))
            },
        ))
        reportOtherDay(result, planId, day)
        if (tapped?.kind == SetKind.TOP && tapped.reps != newReps) settings.markTopSetHelperSeen()
    }

    fun changeSeconds(programExerciseId: Long, slot: String, index: Int, newSeconds: Int) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            val result = repo.applyToRun(runId, planId, day, listOf(
                TrackEdit(programExerciseId, slot, createsMissing = true) { rows ->
                    if (index !in rows.indices) null else SetEditor.edit(rows, index, SetEdit(seconds = newSeconds))
                },
            ))
            reportOtherDay(result, planId, day)
        }
    }

    fun setEachSide(programExerciseId: Long, index: Int, split: Boolean) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            val result = repo.applyToRun(runId, planId, day, listOf(
                TrackEdit(programExerciseId, Slot.MAIN, createsMissing = true) { rows ->
                    val row = rows.getOrNull(index)
                    when {
                        split && row != null && row.sides == null -> SetEditor.edit(rows, index, SetEdit(sides = Sides.same(row)))
                        !split && row?.sides != null -> rows.mapIndexed { i, r -> if (i == index) r.copy(sides = null) else r }
                        else -> null
                    }
                },
            ))
            reportOtherDay(result, planId, day)
        }
    }

    fun changeSide(programExerciseId: Long, index: Int, side: Side, weightDisplay: Double?, reps: Int?, seconds: Int?) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            val unit = settings.unitFlow.first()
            val programSlot = plans.slotsFor(planId, day).first().firstOrNull { it.programExerciseId == programExerciseId }
                ?: return@mutate
            val track = repo.dayTracksFlow(planId, day).first().tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == Slot.MAIN
            }
            val performed = performedExercise(programSlot, track?.exerciseId, catalog.catalogFlow.first(), Slot.MAIN)
            val result = repo.applyToRun(runId, planId, day, listOf(
                TrackEdit(programExerciseId, Slot.MAIN, createsMissing = true) { rows ->
                    val sides = rows.getOrNull(index)?.sides
                    if (sides == null) null else {
                        val old = if (side == Side.LEFT) sides.left else sides.right
                        val changed = old.copy(weightLb = weightDisplay?.let(unit::toLb) ?: old.weightLb,
                            reps = reps ?: old.reps, seconds = seconds ?: old.seconds)
                        val newSides = if (side == Side.LEFT) sides.copy(left = changed) else sides.copy(right = changed)
                        SetEditor.edit(rows, index, SetEdit(sides = newSides), unit, performed.entry?.equipment.orEmpty())
                    }
                },
            ))
            reportOtherDay(result, planId, day)
        }
    }

    fun toggleDone(programExerciseId: Long, index: Int, checked: Boolean, slot: String) {
        tickSet(programExerciseId, index, checked, slot)
    }

    fun logFocusedSet() {
        val state = dayState.value
        val day = currentDay()?.takeIf { it == state.viewDayId } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        mutate {
            val tracks = repo.dayTracksFlow(planId, day).first()
            if (tracks.runId != runId || currentDay() != day) return@mutate
            if (workoutReview(uiState.value.exercises, tracks, settings.workoutRecordFlow.first()) != null) return@mutate
            val focus = focusedWorkoutSet(tracks) ?: return@mutate
            tickSetLocked(day, runId, focus.programExerciseId, focus.index, true,
                loggableSlots(tracks, focus.programExerciseId, focus.index, focus.slot), actionLog = true)
        }
    }

    fun setEffort(programExerciseId: Long, slot: String, index: Int, effort: Effort?) =
        setRepsLeft(programExerciseId, slot, index, effort?.rir)

    fun setRepsLeft(programExerciseId: Long, slot: String, index: Int, rir: Int?) {
        val state = dayState.value
        val day = currentDay()?.takeIf { it == state.viewDayId } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        mutate {
            val result = repo.applyToRun(runId, planId, day, listOf(
                TrackEdit(programExerciseId, slot) { rows -> SetEditor.repsLeft(rows, index, rir) },
            ))
            reportOtherDay(result, planId, day)
        }
    }

    fun decrementFocusedReps(programExerciseId: Long, slot: String, index: Int) {
        val state = dayState.value
        val day = currentDay()?.takeIf { it == state.viewDayId } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        mutate {
            val tracks = repo.dayTracksFlow(planId, day).first()
            if (tracks.runId != runId || currentDay() != day) return@mutate
            val focus = (if (tracks.runOpen) focusedWorkoutSet(tracks) else uiState.value.workout?.focus)
                ?: return@mutate
            if (programExerciseId != focus.programExerciseId) return@mutate
            val displayedIndex = timedRepEvent.value?.takeIf { it.programExerciseId == programExerciseId }?.setIndex
                ?: focus.index
            if (index != displayedIndex) return@mutate
            if (slot != Slot.MAIN && slot != Slot.SS) return@mutate
            val card = uiState.value.exercises.firstOrNull { it.programExerciseId == focus.programExerciseId } ?: return@mutate
            if (card.tracking(slot) == TrackingType.TIMED ||
                (slot == Slot.MAIN && (card.timedRepDurationSeconds ?: 0) > 0)
            ) return@mutate
            val reps = if (tracks.runOpen) {
                val set = tracks.tracks.firstOrNull {
                    it.programExerciseId == focus.programExerciseId && it.slot == slot
                }?.sets?.getOrNull(index) ?: return@mutate
                if (set.done) return@mutate
                set.reps
            } else {
                val row = card.rows.getOrNull(index) ?: return@mutate
                if (slot == Slot.SS) {
                    val partner = row.partner ?: return@mutate
                    if (partner.done) return@mutate
                    partner.reps
                } else {
                    if (row.done) return@mutate
                    row.reps
                }
            }
            if (reps <= 1) return@mutate
            changeRepsLocked(day, runId, planId, focus.programExerciseId, slot, index, reps - 1)
        }
    }

    private fun loggableSlots(tracks: DayTracks, programExerciseId: Long, index: Int, slot: String): List<String> {
        val card = uiState.value.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return emptyList()
        return (if (card.isSuperset) listOf(Slot.MAIN, Slot.SS) else listOf(slot)).filter { candidate ->
            !(candidate == Slot.MAIN && (card.timedRepDurationSeconds ?: 0) > 0) && tracks.tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == candidate
            }?.sets?.getOrNull(index)?.done == false
        }
    }

    private suspend fun focusedWorkoutSet(tracks: DayTracks): WorkoutFocus? {
        val projected = projectWorkout(uiState.value, tracks)
        val record = settings.workoutRecordFlow.first()?.takeIf { tracks.runOpen && it.runId == tracks.runId }
        val selection = if (record != null) record.selectedKey else selectedPage.value
        val shown = WorkoutSelection.shown(workoutGroups(projected), selection)
        return workoutFocus(projected, shown)
    }

    fun selectWorkoutLift(key: String) {
        updateWorkoutSelection { state, _ -> key.takeIf { candidate -> workoutGroups(state).any { it.key == candidate } } }
    }

    fun nextWorkoutLift() {
        updateWorkoutSelection { state, selection ->
            val groups = workoutGroups(state)
            val shown = WorkoutSelection.shown(groups, selection) as? WorkoutSelection.Shown.Group
            WorkoutSelection.next(groups, shown?.key)
        }
    }

    private fun updateWorkoutSelection(selection: (DayUiState, String?) -> String?) {
        val state = dayState.value
        val day = currentDay()?.takeIf { it == state.viewDayId } ?: return
        val planId = state.planId ?: return
        val runId = state.runId
        mutate {
            val tracks = repo.dayTracksFlow(planId, day).first()
            if (tracks.runId != runId || currentDay() != day) return@mutate
            val projected = projectWorkout(uiState.value, tracks)
            if (!tracks.runOpen) {
                savedState[KEY_SELECTED_PAGE] = selection(projected, selectedPage.value)
            } else {
                val record = settings.workoutRecordFlow.first()?.takeIf { it.runId == runId }
                    ?: WorkoutRecord(requireNotNull(runId), selectedPage.value)
                val selected = selection(projected, record.selectedKey)
                settings.setWorkoutRecord(record.copy(selectedKey = selected,
                    review = record.review?.takeIf { selected == "exercise:${it.programExerciseId}" }))
            }
        }
    }

    fun undoLastLog() {
        val state = dayState.value
        val day = currentDay()?.takeIf { it == state.viewDayId } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        mutate {
            phoneRest.locked {
                val tracks = repo.dayTracksFlow(planId, day).first()
                val record = settings.workoutRecordFlow.first()
                val target = validUndo(tracks, record) ?: return@locked
                if (tracks.runId != runId || currentDay() != day) return@locked
                val halves = undoHalves(target)
                val before = halves.map { half ->
                    runCatching { half.before.toDomain() }.getOrNull() ?: return@locked
                }
                val result = repo.applyToRun(runId, planId, day, halves.mapIndexed { index, half ->
                    TrackEdit(half.programExerciseId, half.slot) { current ->
                        WorkoutUndo.revert(current, half, before[index])
                    }
                })
                if (result !is RunWriteResult.InRun) return@locked
                if (halves.any { it.slot == Slot.MAIN }) {
                    historyRepository.deleteTestResult(runId, target.programExerciseId, target.index)
                }
                skip()
                settings.setWorkoutRecord(WorkoutRecord(runId, "exercise:${target.programExerciseId}"))
            }
        }
    }

    private fun tickSet(
        programExerciseId: Long,
        index: Int,
        checked: Boolean,
        slot: String,
        countEvent: TimedRepEvent? = null,
    ) {
        val day = currentDay() ?: return
        val runId = dayState.value.takeIf { it.viewDayId == day }?.runId ?: return
        mutate { tickSetLocked(day, runId, programExerciseId, index, checked, listOf(slot), countEvent) }
    }

    private suspend fun tickSetLocked(
        day: String,
        runId: String,
        programExerciseId: Long,
        index: Int,
        checked: Boolean,
        slots: List<String>,
        countEvent: TimedRepEvent? = null,
        actionLog: Boolean = false,
        startOnTick: Boolean = false,
        hold: HoldEvent? = null,
    ): Boolean {
        if (countEvent != null) {
            if (timedRepEvent.value != countEvent || day != countEvent.dayId ||
                !timedRepMatches(dayState.value, countEvent)
            ) return false
            val planId = plans.planIdOfSlot(programExerciseId) ?: return false
            val programSlot = plans.slotsFor(planId, day).first().firstOrNull { it.programExerciseId == programExerciseId }
                ?: return false
            val track = repo.dayTracksFlow(planId, day).first().tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == Slot.MAIN
            }
            val performed = performedExercise(programSlot, track?.exerciseId, catalog.catalogFlow.first())
            val target = performed.prescription?.target as? PrescribedTarget.RepsInTime
            val duration = if (track?.opening?.benchmark == true) {
                performed.prescription?.test?.let { ref -> TestEvents.matches(ref)?.let { TestEvents.window(it, ref.windowSeconds) } }
            } else target?.seconds
            if (performed.exerciseId != countEvent.exerciseId || duration != countEvent.durationSeconds) return false
        }
        if (hold != null && (holdEvent.value != hold || day != hold.dayId || !holdMatches(dayState.value, hold))) return false
        if (slots.isEmpty() || slots.any { it != Slot.MAIN && it != Slot.SS }) return false
        val planId = plans.planIdOfSlot(programExerciseId) ?: return false
        val loggedAt = clock.millis()
        val writtenBefore = mutableMapOf<String, LoggedSet>()
        val writtenAfter = mutableMapOf<String, LoggedSet>()
        val card = uiState.value.exercises.firstOrNull { it.programExerciseId == programExerciseId }
        if (checked && (actionLog || countEvent != null) && card != null) {
            val tracks = repo.dayTracksFlow(planId, day).first()
            fun current(slot: String) = tracks.tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == slot
            }?.sets?.getOrNull(index)
            val completesRound = !card.isSuperset || listOf(Slot.MAIN, Slot.SS).all {
                it in slots || current(it)?.done == true
            }
            if ((tracks.runOpen || startOnTick) && tracks.runId == runId && slots.any { current(it)?.done == false } &&
                roundEffortSlots(card, tracks, index).isNotEmpty() && completesRound
            ) {
                val record = settings.workoutRecordFlow.first()?.takeIf { it.runId == runId }
                    ?: WorkoutRecord(runId, selectedPage.value)
                // The target stays inert until Room commits the completed row.
                settings.setWorkoutRecord(record.copy(review = ReviewTarget(programExerciseId, index, hold?.endedAtMillis ?: loggedAt)))
            }
        }
        val result = repo.applyToRun(runId, planId, day, slots.map { slot ->
            TrackEdit(programExerciseId, slot, createsMissing = true) { rows ->
                val current = rows.getOrNull(index)
                if (current == null || ((countEvent != null || actionLog || startOnTick) && current.done)) null else {
                    val updated = SetEditor.edit(rows, index, phoneTickEdit(current, checked, loggedAt, countEvent?.count, hold))
                    writtenBefore[slot] = current
                    writtenAfter[slot] = updated[index]
                    updated
                }
            }
        }, startOnTick = startOnTick)
        reportOtherDay(result, planId, day)
        if (result == RunWriteResult.NeedsStart) {
            if (pendingStart.value != null) return false
            closeSheet()
            savedState[KEY_PENDING_START] = PendingStartTick(day, programExerciseId, slots.first(), index, actionLog, countEvent != null)
            return false
        }
        if (result !is RunWriteResult.InRun) return result == RunWriteResult.Unchanged
        val slot = slots.firstOrNull { it in writtenBefore } ?: return true
        val row = writtenBefore.getValue(slot)
        val logged = writtenAfter.getValue(slot)
        if (countEvent != null) clearTimedRep()
        if (hold != null) clearHold()
        if (Slot.MAIN in writtenBefore) {
            val before = writtenBefore.getValue(Slot.MAIN)
            val after = writtenAfter.getValue(Slot.MAIN)
            if (!checked && before.done) {
                historyRepository.deleteTestResult(runId, programExerciseId, index)
            } else if (checked && !before.done && after.done) {
                card?.prescriptionTest?.let { link ->
                    historyRepository.logTestResult(TestResultEntity(
                        completedAt = loggedAt, planId = planId, dayId = day, runId = runId,
                        programExerciseId = programExerciseId, setIndex = index, exerciseId = card.exerciseId,
                        eventId = link.ref.eventId, eventVersion = link.ref.version,
                        windowSeconds = TestEvents.window(link.event, link.ref.windowSeconds),
                        count = if (link.event.timed) null else after.reps,
                        seconds = if (link.event.timed) after.seconds else null,
                        stop = when {
                            !link.event.timed -> TestStop.TIME_UP
                            after.seconds >= HoldClock.limitSeconds(hold?.targetSeconds ?: after.seconds, true) -> TestStop.LIMIT
                            else -> TestStop.STOPPED
                        }.name,
                        rulesJson = TestEventJson.encode(link.event),
                    ))
                }
            }
        }
        val planSlots = plans.slotsFor(planId, day).first()
        val workoutSlots = repo.dayTracksFlow(planId, day).first().let {
            planSlots.forRun(it.droppedPartners, it.skippedLifts)
        }
        val isSuperset = workoutSlots.firstOrNull { it.programExerciseId == programExerciseId }?.exercise?.superset != null
        if (checked && isSuperset && !row.done) settings.markSupersetHelperSeen()
        val main = trackFor(day, programExerciseId, Slot.MAIN)
        val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS).takeIf { it.isNotEmpty() } else null
        val roundComplete = main.getOrNull(index)?.done == true &&
            partner?.getOrNull(index)?.done != false
        val latest = repo.dayTracksFlow(planId, day).first()
        if (!latest.runOpen || latest.runId != runId) return true
        val projected = projectWorkout(uiState.value, latest)
        val groups = workoutGroups(projected)
        val loggedKey = "exercise:$programExerciseId"
        val previous = settings.workoutRecordFlow.first()?.takeIf { it.runId == runId }
        val recordLog = checked && !row.done && (actionLog || countEvent != null)
        val selection = if (recordLog) WorkoutSelection.afterActionLog(groups, loggedKey)
            else if (previous != null) previous.selectedKey else selectedPage.value
        phoneRest.locked {
            if (checked && !row.done) {
                if (projected.exercises.none { current -> current.rows.any { !it.complete } } || !roundComplete) {
                    skip()
                } else if (phoneRest.available && settings.phoneRestTimerEnabledFlow.first()) {
                    val set = main.getOrNull(index)
                    if (card != null && set != null) {
                        val restSettings = settings.restSettingsFlow.first()
                        val seconds = RestPolicy.effectiveRestSeconds(set.kind, card.tracking, restSettings.overrides, card.rule, card.liftGoal)
                        val next = workoutNextLabel(projected, latest, selection)
                        PhoneRestTimer.start(loggedAt, seconds, next)?.let { rest ->
                            start(rest)
                        }
                    }
                }
            }
            if (recordLog) {
                val candidate = ReviewTarget(programExerciseId, index, logged.completedAtMillis ?: loggedAt)
                val review = candidate.takeIf {
                    workoutReview(projected.exercises, latest, WorkoutRecord(runId, selection, review = it))
                        ?.effortSlots?.isNotEmpty() == true
                }
                settings.setWorkoutRecord(WorkoutRecord(runId, selection, UndoTarget(
                    programExerciseId, slot, index, SetDto.of(row), SetDto.of(logged), logged.completedAtMillis ?: loggedAt,
                    slots.drop(1).firstOrNull { it in writtenBefore }?.let {
                        UndoHalf(SetDto.of(writtenBefore.getValue(it)), SetDto.of(writtenAfter.getValue(it)))
                    },
                ), review))
            } else if (previous == null) {
                settings.setWorkoutRecord(WorkoutRecord(runId, selection))
            }
        }
        return true
    }

    fun adjustRest(seconds: Int) = viewModelScope.launch {
        phoneRest.adjust(clock.millis(), seconds)
    }

    fun continueWorkout() {
        val state = dayState.value
        val day = currentDay()?.takeIf { it == state.viewDayId } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        mutate {
            phoneRest.locked {
                val tracks = repo.dayTracksFlow(planId, day).first()
                if (!tracks.runOpen || tracks.runId != runId || currentDay() != day) return@locked
                val record = settings.workoutRecordFlow.first()?.takeIf { it.runId == runId }
                val review = workoutReview(uiState.value.exercises, tracks, record)
                if (review?.unrated?.isNotEmpty() == true) return@locked
                if (current() != null) skip()
                if (record?.review != null) settings.setWorkoutRecord(record.copy(review = null))
            }
        }
    }

    fun dismissLogUndo(loggedAtMillis: Long) {
        val runId = dayState.value.runId ?: return
        mutate {
            val record = settings.workoutRecordFlow.first()?.takeIf { it.runId == runId } ?: return@mutate
            if (record.undo?.loggedAtMillis == loggedAtMillis) settings.setWorkoutRecord(record.copy(undo = null))
        }
    }

    fun addSet(programExerciseId: Long, isSuperset: Boolean) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            val paired = isSuperset && trackFor(day, programExerciseId, Slot.SS).isNotEmpty()
            cancelSetTimersForSetListChange(day, programExerciseId)
            val edits = listOfNotNull(
                // An empty track has no row to copy, so adding to it stays a no-op.
                TrackEdit(programExerciseId, Slot.MAIN) { rows -> rows.takeIf { it.isNotEmpty() }?.let(SetEditor::addSet) },
                if (paired) TrackEdit(programExerciseId, Slot.SS) { rows ->
                    rows.takeIf { it.isNotEmpty() }?.let(SetEditor::addSet)
                } else null,
            )
            val result = repo.applyToRun(runId, planId, day, edits)
            reportOtherDay(result, planId, day)
        }
    }

    /** The × on a set row. Low-friction by design (mid-workout, high frequency),
     *  so it stays one tap and offers [undoRemoveSet] afterwards instead of
     *  asking first — see [_removedSet]. */
    fun removeSet(programExerciseId: Long, index: Int, isSuperset: Boolean) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            var removedMain: LoggedSet? = null
            var removedPartner: LoggedSet? = null
            val paired = isSuperset && trackFor(day, programExerciseId, Slot.SS).isNotEmpty()
            cancelSetTimersForSetListChange(day, programExerciseId)
            val result = repo.applyToRun(runId, planId, day, listOfNotNull(
                TrackEdit(programExerciseId, Slot.MAIN, createsMissing = true) { rows ->
                    if (rows.size <= 1 || index !in rows.indices) null else {
                        removedMain = rows[index]
                        SetEditor.keepingIndexStamps(rows, SetEditor.removeSet(rows, index))
                    }
                },
                if (paired) TrackEdit(programExerciseId, Slot.SS, createsMissing = true) { rows ->
                    removedPartner = rows.getOrNull(index)?.takeIf { rows.size > 1 }
                    SetEditor.keepingIndexStamps(rows, SetEditor.removeSet(rows, index))
                } else null,
            ))
            reportOtherDay(result, planId, day)
            if (result !is RunWriteResult.InRun) return@mutate
            pushUndo(RemovedSet(programExerciseId, index, day, runId, removedMain ?: return@mutate, removedPartner))
        }
    }

    /** Takes the newest offer: puts that row back where it was, kind and tick
     *  included, and makes the offer beneath it current. A no-op once the window
     *  has closed, so a stale tap can't resurrect anything. */
    fun undoRemoveSet() {
        val removed = _removedSets.value.lastOrNull() ?: return
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        popUndo()
        mutate {
            if (day != removed.dayId || runId != removed.runId) return@mutate
            if (repo.dayTracksFlow(planId, day).first().runId != removed.runId) return@mutate
            val paired = removed.partner != null && trackFor(day, removed.programExerciseId, Slot.SS).isNotEmpty()
            cancelSetTimersForSetListChange(day, removed.programExerciseId)
            repo.applyToRun(runId, planId, day, listOfNotNull(
                TrackEdit(removed.programExerciseId, Slot.MAIN) { rows ->
                    SetEditor.keepingIndexStamps(rows, SetEditor.insertSet(rows, removed.index, removed.main))
                },
                if (paired) TrackEdit(removed.programExerciseId, Slot.SS) { rows ->
                    SetEditor.keepingIndexStamps(rows, SetEditor.insertSet(rows, removed.index, requireNotNull(removed.partner)))
                } else null,
            ))
        }
    }

    private fun pushUndo(removed: RemovedSet) {
        // Bounded: past [UNDO_STACK_LIMIT] the oldest entry is dropped. Its
        // index was the most stale, and it was never the one on offer.
        _removedSets.update { (it + removed).takeLast(UNDO_STACK_LIMIT) }
        restartUndoWindow()
    }

    private fun popUndo() {
        _removedSets.update { it.dropLast(1) }
        // The window is shared, so it restarts rather than running down: an
        // offer revealed by a pop must be as reachable as one just pushed.
        if (_removedSets.value.isEmpty()) closeUndoWindow() else restartUndoWindow()
    }

    private fun restartUndoWindow() {
        undoWindowJob?.cancel()
        undoWindowJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            _removedSets.value = emptyList()
        }
    }

    private fun closeUndoWindow() {
        undoWindowJob?.cancel()
        undoWindowJob = null
        _removedSets.value = emptyList()
    }

    fun openSetEditor(id: Long, index: Int) {
        val day = currentDay() ?: return
        savedState[KEY_OPEN_SHEET] = OpenSheet(day, OpenSheet.Kind.EDITOR, id, index)
    }

    fun openLiftSheet(id: Long, slot: String) {
        val day = currentDay() ?: return
        savedState[KEY_OPEN_SHEET] = OpenSheet(day, OpenSheet.Kind.LIFT, id, 0, slot = slot)
    }

    fun openDaySheet() {
        val day = currentDay() ?: return
        savedState[KEY_OPEN_SHEET] = OpenSheet(day, OpenSheet.Kind.DAY, 0, 0)
    }

    fun openSwapSheet(position: Int, slot: String) {
        val state = uiState.value
        val day = currentDay() ?: return
        val card = state.exercises.firstOrNull { it.position == position } ?: return
        savedState[KEY_OPEN_SHEET] = OpenSheet(day, OpenSheet.Kind.SWAP, card.programExerciseId, 0, slot = slot)
    }

    private suspend fun clearLogUndo() {
        val record = settings.workoutRecordFlow.first() ?: return
        if (record.runId == uiState.value.runId) settings.setWorkoutRecord(record.copy(undo = null, review = null))
    }

    fun dismissSwapUndo() { savedState[KEY_SWAP_UNDO] = null }

    fun undoWorkoutSwap() {
        val offer = uiState.value.swapUndo ?: return
        mutate {
            val state = uiState.value
            if (state.swapUndo != offer) return@mutate
            if (offer.skippedSlot != null) {
                restoreSkippedInRun(offer.runId, offer.programExerciseId, offer.skippedSlot)
                dismissSwapUndo()
                return@mutate
            }
            val planId = state.planId ?: return@mutate
            val tracks = repo.dayTracksFlow(planId, offer.dayId).first()
            if (!tracks.runOpen || tracks.runId != offer.runId || tracks.tracks.none {
                it.programExerciseId == offer.programExerciseId && it.slot == offer.slot && it.exerciseId == offer.exerciseId
            }) return@mutate
            repo.swapInRun(offer.runId, offer.programExerciseId, offer.slot, offer.previousExerciseId,
                expectedExerciseId = offer.exerciseId)
            clearLogUndo()
            dismissSwapUndo()
        }
    }

    fun closeSheet() {
        if (openSheet.value?.kind == OpenSheet.Kind.CARDIO_DISTANCE) {
            distanceSheetAction()?.let { action ->
                distanceRun(action)?.let { run -> saveDistanceRun(run.copy(drafts = run.drafts.map {
                    if (it.position == action.setIndex) it.copy(dismissed = true) else it
                })) }
            }
        }
        if (openSheet.value?.kind == OpenSheet.Kind.TIMED_REP_COUNT) {
            timedRepEvent.value?.let { savedState[KEY_TIMED_REP_EVENT] = it.copy(sheetDismissed = true) }
        }
        savedState[KEY_OPEN_SHEET] = null
    }

    fun clearTodayChecks() {
        val state = uiState.value
        val runId = state.takeIf { it.viewDayId == currentDay() && it.runOpen }?.runId ?: return
        mutate {
            if (repo.clearRunChecks(runId)) {
                closeUndoWindow()
                dismissSwapUndo()
                settings.setWorkoutRecord(settings.workoutRecordFlow.first()?.takeIf { it.runId == runId }?.copy(undo = null, review = null))
                phoneRest.skip()
                clearTimedRep()
                clearHold()
            }
        }
    }

    fun discardWorkout() = discardWorkout(draftOnly = false)

    fun dropDraftWorkout() = discardWorkout(draftOnly = true)

    private fun discardWorkout(draftOnly: Boolean) {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day && it.runOpen } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            closeUndoWindow()
            if (repo.discardRun(runId, draftOnly = draftOnly)) {
                phoneRest.skip()
                clearTimedRep()
                clearHold()
                seedWorkingValues(planId, day)
            }
        }
    }

    fun setExerciseNote(programExerciseId: Long, text: String) {
        viewModelScope.launch { repo.setExerciseNote(programExerciseId, text) }
    }

    fun completeDay() {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day && it.runOpen } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        reconcileCardio()?.takeIf { it.dayId == day && !it.complete }?.let(::endCardioRun)
        mutate {
            if (cardioRun.value != null) return@mutate
            finisher.start(runId, planId, day, Program.accentIndex(day), state.dayTitle)
        }
    }

    fun skipLift(programExerciseId: Long, half: String) {
        val day = currentDay() ?: return
        val state = uiState.value.takeIf { it.viewDayId == day } ?: return
        val planId = state.planId ?: return
        val card = state.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return
        if (!card.isSuperset && state.exercises.size <= 1) return
        val skippedId = if (half == Slot.SS) card.partnerExerciseId ?: return else card.exerciseId
        mutate {
            val result = repo.skipLift(planId, day, programExerciseId, half, card.exerciseId, card.partnerExerciseId)
            reportOtherDay(result, planId, day)
            if (result !is RunWriteResult.InRun) return@mutate
            closeSheet()
            clearLogUndo()
            closeUndoWindow()
            _removedSets.value = emptyList()
            val key = if (card.isSuperset) "exercise:$programExerciseId" else
                WorkoutSelection.next(workoutGroups(state), "exercise:$programExerciseId")
            savedState[KEY_SELECTED_PAGE] = key
            settings.setWorkoutRecord(WorkoutRecord(result.runId, key))
            val keptId = if (half == Slot.MAIN && card.isSuperset) card.partnerExerciseId.orEmpty() else card.exerciseId
            savedState[KEY_SWAP_UNDO] = WorkoutSwapUndo(day, result.runId, programExerciseId, Slot.MAIN,
                skippedId, keptId, catalog.catalogFlow.first().find(skippedId)?.name ?: skippedId,
                skippedSlot = if (card.isSuperset && card.exerciseId == card.partnerExerciseId) Slot.SS else half)
        }
    }

    fun restoreSkipped(programExerciseId: Long, half: String) {
        val runId = uiState.value.runId ?: return
        mutate {
            restoreSkippedInRun(runId, programExerciseId, half)
            dismissSwapUndo()
        }
    }

    private suspend fun restoreSkippedInRun(runId: String, programExerciseId: Long, half: String) {
        val run = repo.openRunFlow.first()?.takeIf { it.id == runId } ?: return
        if (!run.isSlotSkipped(programExerciseId, Slot.MAIN)) {
            repo.restoreSkipped(runId, programExerciseId, half)
            return
        }
        repo.restoreSkippedLift(runId, programExerciseId)
    }

    fun skipNowSet(programExerciseId: Long, half: String) {
        val state = uiState.value
        val focus = state.workout?.focus?.takeIf { it.programExerciseId == programExerciseId && it.slot == half } ?: return
        val card = state.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return
        if (card.rows.getOrNull(focus.index)?.values(half)?.done != false) return
        closeSheet()
        removeSet(programExerciseId, focus.index, card.isSuperset)
    }

    fun swapWorkoutSlot(position: Int, newExerciseId: String) = swapWorkoutSlot(position, newExerciseId, Slot.MAIN)

    fun swapWorkoutSlot(position: Int, newExerciseId: String, half: String) {
        if (half != Slot.MAIN && half != Slot.SS) return
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        mutate {
            val slots = plans.slotsFor(planId, day).first()
            val slot = slots.firstOrNull { it.position == position } ?: return@mutate
            if (half == Slot.SS && slot.exercise.superset == null) return@mutate
            val tracks = repo.dayTracksFlow(planId, day).first().tracks
            val previousId = tracks.firstOrNull { it.programExerciseId == slot.programExerciseId && it.slot == half }?.exerciseId
                ?: if (half == Slot.SS) slot.exercise.superset?.exerciseId else slot.exercise.exerciseId
            if (previousId == null || previousId == newExerciseId) return@mutate
            val catalog = this.catalog.catalogFlow.first()
            val swapped = repo.swapInRun(runId, slot.programExerciseId, half, newExerciseId)
            if (swapped) {
                clearLogUndo()
                savedState[KEY_SWAP_UNDO] = WorkoutSwapUndo(
                    day, runId, slot.programExerciseId, half, previousId, newExerciseId,
                    catalog.find(newExerciseId)?.name ?: newExerciseId,
                )
            } else reportOtherDay(RunWriteResult.Rejected, planId, day)
        }
    }

    private suspend fun reportOtherDay(result: RunWriteResult, planId: Long, day: String) {
        if (result != RunWriteResult.Rejected) return
        val open = repo.openRunFlow.first() ?: return
        if (open.planId == planId && open.dayId == day) return
        if (open.draft) workoutStart.refuseEdit(DaySelection(planId, day), open.id)
        else otherWorkoutOpenMessage.value = true
    }

    // --- seeding & bootstrap -------------------------------------------------

    /** Seeds each day's logs before its authoritative rows are observed. */
    private suspend fun ensureSeeded(planId: Long, dayId: String) = mutationLock.withLock {
        repo.ensureSeeded(planId, dayId)
    }

    private suspend fun seedWorkingValues(planId: Long, dayId: String) {
        repo.ensureSeeded(planId, dayId)
    }

    // --- helpers -------------------------------------------------------------

    /** Launches a log mutation with its read-modify-write held under [mutationLock]. */
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch { mutationLock.withLock { block() } }
    }

    private fun currentDay(): String? = effectiveDayId.value

    private fun workoutGroups(state: DayUiState): List<WorkoutSelection.Group> =
        state.pages.map { WorkoutSelection.Group(it.key, it.allDone || it.total == 0) }

    private fun workoutFocus(state: DayUiState, shown: WorkoutSelection.Shown): WorkoutFocus? {
        val group = shown as? WorkoutSelection.Shown.Group ?: return null
        if (group.complete) return null
        val card = state.exercises.firstOrNull { "exercise:${it.programExerciseId}" == group.key } ?: return null
        val row = card.rows.getOrNull(DayScreenBuilder.focusIndex(card.rows))?.takeUnless { it.complete } ?: return null
        return WorkoutFocus(card.programExerciseId, row.nextSlot, row.index)
    }

    private fun projectWorkout(state: DayUiState, tracks: DayTracks): DayUiState {
        val latest = tracks.tracks.associateBy { it.programExerciseId to it.slot }
        return state.copy(exercises = state.exercises.map { card ->
            val main = latest[card.programExerciseId to Slot.MAIN]?.sets.orEmpty()
            val partner = latest[card.programExerciseId to Slot.SS]?.sets
            val rows = main.mapIndexed { index, set ->
                SetRowState(
                    index = index, kindLabel = "", isTop = set.kind == SetKind.TOP,
                    weightDisplay = state.unit.fromLb(set.weightLb), reps = set.reps, done = set.done,
                    seconds = set.seconds, kind = set.kind, rir = set.rir, sides = set.sides,
                    partner = partner?.getOrNull(index)?.let {
                        PartnerRowState(state.unit.fromLb(it.weightLb), it.reps, it.done, it.seconds, rir = it.rir)
                    },
                )
            }
            card.copy(rows = rows, allDone = rows.all { it.complete }, focusIndex = DayScreenBuilder.focusIndex(rows))
        })
    }

    private fun runRows(tracks: DayTracks): List<LoggedSet> =
        tracks.tracks.flatMap { it.sets } + tracks.retired.flatMap { it.sets }

    private fun roundEffortSlots(card: ExerciseCardState, tracks: DayTracks, index: Int): List<String> {
        val slots = if (card.isSuperset) listOf(Slot.MAIN, Slot.SS) else listOf(Slot.MAIN)
        return slots.filter { slot ->
            val row = tracks.tracks.firstOrNull { it.programExerciseId == card.programExerciseId && it.slot == slot }
                ?.sets?.getOrNull(index)
            // Partner rows carry no set kind and are never warm-ups.
            row != null && showsEffort(if (slot == Slot.SS) SetKind.WORK else row.kind, card.tracking(slot), row.seconds)
        }
    }

    private fun workoutReview(cards: List<ExerciseCardState>, tracks: DayTracks?, record: WorkoutRecord?): WorkoutReview? {
        if (tracks == null || !tracks.runOpen) return null
        val target = record?.takeIf { it.runId == tracks.runId }?.review ?: return null
        val card = cards.firstOrNull { it.programExerciseId == target.programExerciseId } ?: return null
        val slots = if (card.isSuperset) listOf(Slot.MAIN, Slot.SS) else listOf(Slot.MAIN)
        val rows = slots.map { slot ->
            tracks.tracks.firstOrNull { it.programExerciseId == target.programExerciseId && it.slot == slot }
                ?.sets?.getOrNull(target.index)?.takeIf { it.done } ?: return null
        }
        if (rows.none { it.completedAtMillis == target.loggedAtMillis } ||
            runRows(tracks).any { (it.completedAtMillis ?: Long.MIN_VALUE) > target.loggedAtMillis }) return null
        val effortSlots = roundEffortSlots(card, tracks, target.index)
        return WorkoutReview(target.programExerciseId, target.index, effortSlots,
            effortSlots.filter { card.tracking(it) != TrackingType.TIMED && rows[slots.indexOf(it)].rir == null })
    }

    private fun undoHalves(target: UndoTarget): List<UndoTarget> = listOfNotNull(target, target.partner?.let {
        target.copy(slot = if (target.slot == Slot.MAIN) Slot.SS else Slot.MAIN,
            before = it.before, after = it.after, partner = null)
    })

    private fun validUndo(tracks: DayTracks?, record: WorkoutRecord?): UndoTarget? {
        if (tracks == null || !tracks.runOpen) return null
        val target = record?.takeIf { it.runId == tracks.runId }?.undo ?: return null
        if (undoHalves(target).any { half ->
                val row = tracks.tracks.firstOrNull {
                    it.programExerciseId == half.programExerciseId && it.slot == half.slot
                }?.sets?.getOrNull(half.index)
                row == null || !WorkoutUndo.matches(row, half)
            }) return null
        if (runRows(tracks).any { (it.completedAtMillis ?: Long.MIN_VALUE) > target.loggedAtMillis }) return null
        return target
    }

    private fun buildWorkout(
        state: DayUiState,
        tracks: DayTracks?,
        savedRecord: WorkoutRecord?,
    ): WorkoutState {
        val record = savedRecord?.takeIf { tracks?.runOpen == true && it.runId == tracks.runId }
        // The run's record wins once it exists; until then (no run, or a run the watch started) the route's.
        val selection = if (record != null) record.selectedKey else selectedPage.value
        val shown = WorkoutSelection.shown(workoutGroups(state), selection)
        val undo = validUndo(tracks, record)?.let { target ->
            state.exercises.firstOrNull { it.programExerciseId == target.programExerciseId }?.let { card ->
                UndoOffer(
                    target.programExerciseId, target.slot, target.index,
                    if (target.slot == Slot.SS) card.partnerTitle.orEmpty() else card.title,
                    requireNotNull(tracks).tracks.first {
                        it.programExerciseId == target.programExerciseId && it.slot == target.slot
                    }.sets[target.index],
                    if (target.slot == Slot.SS) card.partnerTracking ?: TrackingType.WEIGHTED else card.tracking,
                    state.unit,
                    target.partner?.let {
                        requireNotNull(tracks).tracks.first {
                            it.programExerciseId == target.programExerciseId && it.slot != target.slot
                        }.sets[target.index]
                    },
                )
            }
        }
        return WorkoutState(
            shown, workoutFocus(state, shown), (shown as? WorkoutSelection.Shown.Group)?.complete == true,
            undo, tracks?.takeIf { it.runOpen }?.let(::runRows)?.mapNotNull { it.completedAtMillis }?.maxOrNull(),
            workoutReview(state.exercises, tracks, record),
        )
    }

    private suspend fun workoutNextLabel(state: DayUiState, tracks: DayTracks, selectedKey: String?): String {
        val shown = WorkoutSelection.shown(workoutGroups(state), selectedKey)
        val focus = workoutFocus(state, shown) ?: return ""
        val card = state.exercises.first { it.programExerciseId == focus.programExerciseId }
        val title = if (focus.slot == Slot.SS) card.partnerTitle.orEmpty() else card.title
        val tracking = if (focus.slot == Slot.SS) card.partnerTracking ?: TrackingType.WEIGHTED else card.tracking
        val set = tracks.tracks.firstOrNull {
            it.programExerciseId == focus.programExerciseId && it.slot == focus.slot
        }?.sets?.getOrNull(focus.index) ?: return ""
        val load = SetFormatter.summary(tracking, set.weightLb, set.reps, set.seconds, settings.unitFlow.first(), spaced = true)
        val label = listOf(title, load.trim()).filter { it.isNotBlank() }.joinToString(" ")
        return if (card.isSuperset) "Round ${focus.index + 1}, $label" else label
    }

    private suspend fun trackFor(dayId: String, programExerciseId: Long, slot: String): List<LoggedSet> {
        val planId = plans.planIdOfSlot(programExerciseId) ?: return emptyList()
        return repo.dayTracksFlow(planId, dayId).first().tracks
            .firstOrNull { it.programExerciseId == programExerciseId && it.slot == slot }?.sets.orEmpty()
    }

    /**
     * The state for a context that resolved to no day. The program itself says
     * which of the two that is: days but no day yet is the one frame where
     * [effectiveDayId] hasn't caught up with a program emission, and is still
     * loading; no days at all is the answer, and gets the recovery.
     */
    private fun noDayState(ctx: DayContext): DayUiState {
        validateCardioRun(ctx)
        return DayUiState(hasProgram = false, loading = ctx.program.days.isNotEmpty())
    }

    /** One batched read for the whole day's "last time" chips (PLAN.md A1
     *  bonus) AND its "Best" profile chips (performance-profile.md Phase 1)
     *  — never one query per exercise for either. */
    private suspend fun fetchDayHistory(ids: List<String>, planId: Long, dayId: String): DayHistory =
        DayHistory(historyRepository.lastTimes(ids, planId, dayId), historyRepository.personalRecords(ids),
            historyRepository.slotSessionsById(planId, dayId))

    private fun buildState(
        ctx: DayContext,
        performedDay: PerformedDay,
        history: DayHistory,
    ): DayUiState {
        validateCardioRun(ctx)
        val program = performedDay.program
        val dayId = performedDay.dayId
        val day = performedDay.day ?: return noDayState(ctx)
        val dayIndex = Program.accentIndex(dayId)
        val cards = performedDay.slots.map { slot ->
            buildCard(slot, ctx.unit, history, ctx.plan,
                ctx.plan?.planSettings?.defaultsFor ?: LiftGoal.STRENGTH, program, dayId, performedDay.catalog).copy(
                weightSwap = DayScreenBuilder.weightSwapAffordance(slot.main.performed.entry, performedDay.catalog),
            )
        }
        val credited = routineExercises(performedDay)
        return DayUiState(
            hasProgram = true,
            isProgramDay = program.strengthDays.any { it.id == dayId },
            viewDayId = dayId,
            planId = performedDay.planId,
            dayIndex = dayIndex,
            dayTitle = day.title,
            emphasisLine = day.emphasisLine,
            unit = ctx.unit,
            nextDayId = Rotation.upNextAfter(program, ctx.suggested, dayId),
            exercises = cards,
            cardioLeads = day.kind == ProgramDayKind.CARDIO,
            warmUpMoves = Routines.warmUp(credited, day.cardioToRun, performedDay.equipment),
            coolDownMoves = Routines.coolDown(credited, day.cardioToRun, performedDay.equipment),
            cardio = day.cardioToRun?.let {
                CardioCardState(label = activityLabel(it.activity), detail = it.plannedTimeDetail(),
                    hard = it.sets.any { spec -> spec.hard }, block = it, finishesDay = day.exercises.isEmpty())
            },
        )
    }

    private fun routineExercises(day: PerformedDay): List<CreditedExercise> = day.slots.flatMap { slot ->
        listOfNotNull(
            slot.main.performed.entry?.let { entry ->
                CreditedExercise(entry, slot.main.sets.count { it.kind != SetKind.RAMP })
            },
            slot.partner?.let { partner ->
                partner.performed.entry?.let { entry ->
                    CreditedExercise(entry, minOf(slot.main.sets.size, partner.sets.size))
                }
            },
        )
    }.filter { it.workingSets > 0 }

    private fun sheetResolves(state: DayUiState, sheet: OpenSheet, event: TimedRepEvent?): Boolean =
        !state.loading && sheet.dayId == state.viewDayId &&
            if (sheet.kind == OpenSheet.Kind.DAY) true
            else if (sheet.kind == OpenSheet.Kind.CARDIO_SETS || sheet.kind == OpenSheet.Kind.CARDIO_DISTANCE) {
                state.cardio != null && (sheet.kind != OpenSheet.Kind.CARDIO_DISTANCE ||
                    distanceRuns().any { run ->
                        run.runId == sheet.cardioRunId && run.dayId == sheet.dayId && run.planId == state.planId &&
                            run.drafts.any { it.position == sheet.setIndex }
                    })
            } else state.exercises.any { card ->
            card.programExerciseId == sheet.programExerciseId &&
                (sheet.kind in listOf(OpenSheet.Kind.LIFT, OpenSheet.Kind.SWAP) || card.rows.getOrNull(sheet.setIndex) != null) &&
                (sheet.slot != Slot.SS || card.isSuperset) &&
                (sheet.kind != OpenSheet.Kind.TIMED_REP_COUNT ||
                    (event?.awaitingCount == true && event.programExerciseId == sheet.programExerciseId &&
                        event.setIndex == sheet.setIndex))
        }

    private fun timedRepMatches(state: DayUiState, event: TimedRepEvent): Boolean {
        val card = state.exercises.firstOrNull { it.programExerciseId == event.programExerciseId } ?: return false
        return state.viewDayId == event.dayId && card.exerciseId == event.exerciseId &&
            card.timedRepDurationSeconds == event.durationSeconds &&
            card.rows.getOrNull(event.setIndex)?.done == false
    }

    private fun reconcileTimedRep(state: DayUiState, event: TimedRepEvent?): TimedRepState? {
        if (event == null || event != timedRepEvent.value || state.loading) return null
        if (!timedRepMatches(state, event) || (cardioRun.value != null && !event.awaitingCount)) {
            clearTimedRep()
            return null
        }
        if (event.awaitingCount) return TimedRepState(event, 0)
        val elapsed = BootAnchor(event.wallStart, event.elapsedStart, event.bootCount).elapsedSince(cardioClock.clocks())
        val remainingMillis = (event.durationSeconds * 1_000L - elapsed).coerceAtLeast(0L)
        if (remainingMillis == 0L) {
            finishTimedRep(event, buzz = timedRepAlertAnchor == event.wallStart)
            return TimedRepState(event.copy(awaitingCount = true), 0)
        }
        if (cardioScreenLive) {
            timedRepAlertAnchor = event.wallStart
            cardioAlarm.armQuiet(
                cardioClock.elapsedRealtimeMillis() + remainingMillis,
                "timed-reps:${event.programExerciseId}:${event.setIndex}:${event.wallStart}",
            ) { onTimedRepDeadline(event) }
        }
        return TimedRepState(event, ((remainingMillis + 999L) / 1_000L).toInt())
    }

    private fun onTimedRepDeadline(event: TimedRepEvent) {
        if (timedRepEvent.value != event) return
        val state = dayState.value
        if (!state.loading && !timedRepMatches(state, event)) clearTimedRep() else finishTimedRep(event, buzz = true)
    }

    private fun finishTimedRep(event: TimedRepEvent, buzz: Boolean) {
        if (timedRepEvent.value != event || event.awaitingCount) return
        cardioAlarm.cancel()
        timedRepAlertAnchor = null
        // Save the transition before feedback; re-collection and restore cannot replay it.
        savedState[KEY_TIMED_REP_EVENT] = event.copy(awaitingCount = true)
        if (buzz) cardioAlarm.buzz()
    }

    private fun clearTimedRep() {
        if (timedRepEvent.value?.awaitingCount == false) cardioAlarm.cancel()
        timedRepAlertAnchor = null
        savedState[KEY_TIMED_REP_EVENT] = null
        if (openSheet.value?.kind == OpenSheet.Kind.TIMED_REP_COUNT) savedState[KEY_OPEN_SHEET] = null
    }

    private fun cancelSetTimersForSetListChange(dayId: String, programExerciseId: Long) {
        // Rows have positional identities. Cancel on every structural change in
        // this slot, even after the event's index, rather than risk retargeting it.
        timedRepEvent.value?.takeIf { it.dayId == dayId && it.programExerciseId == programExerciseId }?.let { clearTimedRep() }
        holdEvent.value?.takeIf { it.dayId == dayId && it.programExerciseId == programExerciseId }?.let { clearHold() }
    }

    private fun holdToStart(programExerciseId: Long, setIndex: Int): HoldEvent? {
        if (cardioRun.value != null || timedRepEvent.value != null || holdEvent.value != null) return null
        val state = uiState.value
        val day = currentDay() ?: return null
        val card = state.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return null
        val target = card.holdTargetSeconds(setIndex).takeIf { it > 0 } ?: return null
        if (state.viewDayId != day || card.focusIndex != setIndex || card.rows.getOrNull(setIndex)?.done != false) return null
        val wall = cardioClock.wallMillis()
        return HoldEvent(day, programExerciseId, card.exerciseId, setIndex, target,
            sides = if (card.headerTarget.eachSide) 2 else 1, startedAtMillis = wall, wallStart = wall,
            elapsedStart = cardioClock.elapsedRealtimeMillis(), bootCount = cardioClock.bootCount())
    }

    private fun holdMatches(state: DayUiState, event: HoldEvent): Boolean {
        val card = state.exercises.firstOrNull { it.programExerciseId == event.programExerciseId } ?: return false
        return state.viewDayId == event.dayId && card.exerciseId == event.exerciseId &&
            card.holdTargetSeconds(event.setIndex) == event.targetSeconds &&
            card.rows.getOrNull(event.setIndex)?.done == false
    }

    private fun reconcileHold(state: DayUiState, event: HoldEvent?): HoldState? {
        if (event == null || event != holdEvent.value || state.loading) return null
        if (!holdMatches(state, event) || cardioRun.value != null) {
            clearHold()
            return null
        }
        if (event.complete) {
            logHold(event)
            return HoldState(event, event.loggedSeconds)
        }
        if (!event.running) return HoldState(event, 0)
        val elapsed = BootAnchor(event.wallStart, event.elapsedStart, event.bootCount).elapsedSince(cardioClock.clocks())
        val limit = HoldClock.limitSeconds(event.targetSeconds, holdPastTarget)
        if (HoldClock.reached(elapsed, limit)) {
            endHoldSide(event, limit, buzz = holdAlertAnchor == event.wallStart)
            return holdEvent.value?.let { HoldState(it, if (it.complete) it.loggedSeconds else 0) }
        }
        if (HoldClock.reached(elapsed, event.targetSeconds)) {
            // Past target: the target owes one buzz, from the ticker or the alarm, whichever comes first.
            if (holdAlertAnchor == event.wallStart) {
                holdAlertAnchor = null
                cardioAlarm.cancel()
                cardioAlarm.buzz()
            }
        } else if (cardioScreenLive) {
            holdAlertAnchor = event.wallStart
            cardioAlarm.armQuiet(
                cardioClock.elapsedRealtimeMillis() + event.targetSeconds * 1_000L - elapsed,
                "hold:${event.programExerciseId}:${event.setIndex}:${event.wallStart}",
            ) { onHoldDeadline(event) }
        }
        return HoldState(event, HoldClock.heldSeconds(elapsed, limit))
    }

    private fun onHoldDeadline(event: HoldEvent) {
        if (holdEvent.value != event) return
        val state = dayState.value
        when {
            !state.loading && !holdMatches(state, event) -> clearHold()
            holdPastTarget -> if (holdAlertAnchor == event.wallStart) {
                holdAlertAnchor = null
                cardioAlarm.buzz()
            }
            else -> endHoldSide(event, event.targetSeconds, buzz = true)
        }
    }

    private fun endHoldSide(event: HoldEvent, seconds: Int, buzz: Boolean) {
        if (holdEvent.value != event || !event.running) return
        cardioAlarm.cancel()
        holdAlertAnchor = null
        val ended = event.copy(running = false, heldSeconds = event.heldSeconds + seconds,
            endedAtMillis = event.wallStart + seconds * 1_000L)
        // Save the transition before feedback; re-collection and restore cannot replay it.
        savedState[KEY_HOLD] = ended
        if (buzz) cardioAlarm.buzz()
        if (ended.complete) logHold(ended)
    }

    private fun logHold(event: HoldEvent) {
        if (holdLogging == event) return
        holdLogging = event
        mutate {
            try {
                val runId = dayState.value.takeIf { it.viewDayId == event.dayId }?.runId
                if (holdEvent.value == event && runId != null) {
                    tickSetLocked(event.dayId, runId, event.programExerciseId, event.setIndex, checked = true,
                        slots = listOf(Slot.MAIN), actionLog = true, hold = event)
                }
            } finally {
                if (holdEvent.value == event) clearHold()
                holdLogging = null
            }
        }
    }

    private fun clearHold() {
        if (holdEvent.value?.running == true) cardioAlarm.cancel()
        holdAlertAnchor = null
        savedState.remove<Boolean>(KEY_HOLD_PAST_TARGET)
        savedState[KEY_HOLD] = null
    }

    /** Seeds an empty execution slot with the day's durable run (#627). [validateCardioRun] and the next state build own it from there. */
    private suspend fun restoreCardioRun(planId: Long, dayId: String) {
        if (cardioRun.value != null) return
        val stored = cardioRunStore.load(planId, dayId) ?: return
        val run = stored.run
        val now = cardioClock.wallMillis()
        val sessions = historyRepository.cardioSessionsFlow.first()
        val logged = run.startedAt?.let { started -> sessions.any { it.dayId == run.dayId && it.startedAt == started } }
            ?: (sessions.loggedToday(planId, dayId, now, repo.zone) != null)
        if (run.planId != planId || run.dayId != dayId || localDate(stored.savedAt) < localDate(now) || logged) {
            return cardioRunStore.delete(planId, dayId)
        }
        if (cardioRun.value != null) return
        savedState[KEY_CARDIO_GPS_SETS] = ArrayList(gpsSets.value.filterNot { it.runId == run.runId } + stored.gpsSets)
        savedState[KEY_SPRINT_TIMES] = ArrayList(sprintTimes.value.filterNot { it.runId == run.runId } + stored.sprintTimes)
        savedState[KEY_CARDIO_READING_INPUTS] =
            ArrayList(readingInputs.value.filterNot { it.runId == run.runId } + stored.readingInputs)
        stored.runDistance?.let { savedState[KEY_CARDIO_RUN_DISTANCE] = it }
        savedState[KEY_CARDIO_RUN] = run
        if (cardioScreenLive && run.running && run.block.activity.usesGps && gpsEnabled.value && gps.meter(run.runId) == null) {
            beginCardioGps(run)
        }
        lastCardioValidationContext?.let(::validateCardioRun)
        cardioTick.value++
    }

    private fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(repo.zone).toLocalDate()

    private fun validateCardioRun(ctx: DayContext) {
        val contextChanged = ctx != lastCardioValidationContext
        lastCardioValidationContext = ctx
        val run = cardioRun.value ?: return
        // Record every validation, including the one that ends the run, so a failed
        // insert cannot retry itself as history re-emits for the same context.
        if (!run.showReceipt && run.saveFailed) {
            if (contextChanged) retryCardioSave()
            return
        }
        val day = ctx.program.days.firstOrNull { it.id == run.dayId }
        // Started work owns its frozen prescription. Edits to its block apply to the next run.
        if (ctx.plan?.id != run.planId || day == null) {
            if (run.startedAt == null) return clearCardioExecution()
            if (openSheet.value?.kind in listOf(OpenSheet.Kind.CARDIO_DISTANCE, OpenSheet.Kind.CARDIO_SETS)) {
                savedState[KEY_OPEN_SHEET] = null
            }
            endCardioRun(stepCardio(run) { exec, meter -> exec.reconcile(cardioClock.clocks(), meter) }.copy(showReceipt = false))
        } else if (run.startedAt == null &&
            day.cardioToRun?.let(CardioBlockJson::encode) != run.sourceBlockJson
        ) {
            clearCardioExecution()
        }
    }

    private fun buildCardioState(
        state: DayUiState,
        history: List<CardioSessionEntity>,
        sets: Map<Long, List<cloud.trotter.log.strength.domain.model.CardioSetResult>>,
        savedRun: CardioRun?,
        context: DayContext?,
    ): CardioCardState? {
        // An invalidated run still owns the execution slot until its save succeeds.
        // Keep its existing failure/Retry card reachable from the day now in view.
        if (!state.loading && savedRun != null && savedRun == cardioRun.value &&
            !savedRun.showReceipt && savedRun.saveFailed &&
            (savedRun.planId != state.planId || savedRun.dayId != state.viewDayId)
        ) {
            return CardioCardState(activityLabel(savedRun.block.activity), "", savedRun.block.sets.any { it.hard },
                block = savedRun.block, run = savedRun, canAddSet = false, finishesDay = savedRun.finishesDay,
                distanceUnit = distanceUnit.value)
        }
        val logged = history.loggedToday(state.planId, state.viewDayId, cardioClock.wallMillis(), repo.zone)
        val loggedSets = logged?.let { sets[it.id] }.orEmpty()
        val activeRun = savedRun?.takeIf {
            it == cardioRun.value && it.planId == state.planId && it.dayId == state.viewDayId
        }
        val frozen = activeRun ?: pendingCardioDistances.value.firstOrNull {
            it.planId == state.planId && it.dayId == state.viewDayId && it.drafts.isNotEmpty()
        }
        val day = context?.program?.days?.firstOrNull { it.id == state.viewDayId }
        val plan = context?.plan
        val opening = if (context != null && day != null && plan != null) {
            DayScreenBuilder.cardioOpening(day, plan, history, sets, cardioClock.wallMillis(), context.program, context.unit, repo.zone)
        } else null
        // Removing the source block must leave the frozen run and pending distances accessible.
        val sourceCard = state.cardio?.let { card ->
            opening?.let { card.copy(block = it, detail = it.plannedTimeDetail(), hard = it.sets.any { set -> set.hard }) } ?: card
        } ?: frozen?.let {
            CardioCardState(activityLabel(it.block.activity), "", it.block.sets.any { spec -> spec.hard },
                block = it.block, finishesDay = it.finishesDay)
        }
        val nextTime = if (savedRun == null && frozen == null && logged == null && day != null && plan != null) {
            DayScreenBuilder.cardioNextTime(day, plan, history, sets)
        } else null
        val sprint = if (context != null && savedRun == null && frozen == null && logged == null && day != null && plan != null) {
            DayScreenBuilder.sprintStart(day, plan, history, sets, cardioClock.wallMillis(), context.program, context.unit, repo.zone)
        } else null
        val base = sourceCard?.let { source ->
            val loggedActivity = logged?.activity?.let { name -> CardioActivity.entries.firstOrNull { it.name == name } }
            val completed = activeRun == null && loggedSets.isNotEmpty()
            source.copy(nextTime = nextTime, sprint = sprint, distanceUnit = distanceUnit.value, loggedSeconds = logged?.seconds, loggedSessionId = logged?.id, loggedSets = loggedSets,
                label = if (completed && loggedActivity != null) activityLabel(loggedActivity) else source.label,
                loggedActivity = loggedActivity,
                distanceRuns = distanceRuns().filter { it.dayId == state.viewDayId && it.planId == state.planId },
                distanceInputs = distanceInputs.value,
                readingInputs = readingInputs.value,
                gps = cardioGpsState(source.block, activeRun),
                gpsAsk = activeRun != null && gpsAsk.value == activeRun.runId,
                gpsSets = gpsSets.value.filter { stamp -> distanceRuns().any {
                    it.runId == stamp.runId && it.dayId == state.viewDayId && it.planId == state.planId
                } },
                canSwap = state.cardio != null && logged == null &&
                    !setTimerBusy &&
                    (cardioRun.value == null || cardioRun.value?.let {
                        it.planId == state.planId && it.dayId == state.viewDayId && it.startedAt == null && it.results.isEmpty()
                    } == true),
                canAddSet = (cardioRun.value == null || cardioRun.value?.dayId == state.viewDayId) &&
                    cardioRun.value?.canAddSet != false &&
                    !setTimerBusy,
                block = if (completed) {
                    source.block?.copy(sets = loggedSets.map { it.spec })
                } else source.block)
        }
        if (state.loading || savedRun == null || savedRun != cardioRun.value) return base
        val run = reconcileCardio() ?: return base
        maybeLogCardio()
        armCardioBoundary(run)
        if (run.dayId != state.viewDayId || base == null) return base
        val end = run.block.sets.getOrNull(run.position)?.end as? Measure.Time
        val elapsed = cardioElapsedSeconds(run)
        // This state builder must stay on Main: timer validation writes SavedStateHandle and can schedule the beep.
        val timer = readSprintTimer(run)
        if (timer != null && sprintBeepJob == null && timer.beepElapsedMillis > cardioClock.elapsedRealtimeMillis()) {
            scheduleSprintBeep(timer)
        }
        val spec = run.block.sets.getOrNull(run.position)
        val sprintRep = if (day?.cardioRule?.session?.sprint == true && run.running && spec != null &&
            ((spec.role == CardioRole.WORK && spec.hard && spec.end is Measure.Distance) || spec.role == CardioRole.RECOVERY)) {
            val previousRep = (0 until run.position).lastOrNull { run.block.sets[it].role == CardioRole.WORK }
            val prior = sprintTimesFor(run).lastOrNull { it.position == previousRep }
            val newestProtocol = history.filter { it.planId == run.planId && it.dayId == run.dayId }
                .sortedWith(compareByDescending<CardioSessionEntity> { it.completedAt }.thenByDescending { it.id })
                .firstNotNullOfOrNull { session -> sets[session.id].orEmpty().lastOrNull { it.timing != null }?.timing?.startProtocol }
            val protocol = sprintProtocol.value?.let { name -> StartProtocol.entries.firstOrNull { it.name == name } }
                ?: newestProtocol ?: StartProtocol.STANDING
            SprintRepUi(timer != null, timer?.beepElapsedMillis, protocol,
                prior?.let { SprintTime(it.centis, TimingMethod.valueOf(it.method), StartProtocol.valueOf(it.protocol)) })
        } else null
        return base.copy(block = run.block, run = run, gps = cardioGpsState(run.block, run), sprintRep = sprintRep,
            gpsAsk = gpsAsk.value == run.runId,
            label = activityLabel(run.block.activity), hard = run.block.sets.any { it.hard },
            phase = if (run.running) CardioPhase.EXECUTING else CardioPhase.SUGGESTION,
            elapsedSeconds = elapsed,
            runDistanceText = distanceInputText(run, run.position, runDistanceFor(run)?.text.orEmpty()),
            stepSecondsLeft = end?.let { ((it.seconds * 1_000L - run.elapsedMillis(cardioClock) + 999L) / 1_000L).toInt().coerceAtLeast(0) } ?: 0)
    }

    private fun isPocketed(run: CardioRun): Boolean =
        !cardioScreenLive && gps.state.value == GpsState.Tracking(run.runId)

    private fun armCardioBoundary(run: CardioRun) {
        if (!setTimerBusy) {
            val end = run.block.sets.getOrNull(run.position)?.end as? Measure.Time
            if (run.running && end != null && !run.block.openEnded) {
                val remaining = end.seconds * 1000L - run.elapsedMillis(cardioClock)
                val identity = cardioAlarmIdentity(run)
                if (remaining <= 0 && cardioAlertAnchor == identity) return
                if (remaining > 0) {
                    cardioAlertAnchor = identity
                    cardioAlarm.arm(cardioClock.elapsedRealtimeMillis() + remaining, identity) { onCardioBoundary(identity) }
                } else if (isPocketed(run)) {
                    cardioAlertAnchor = null
                    val deadline = requireNotNull(run.anchorElapsedMillis(cardioClock)) + end.seconds * 1000L + DistanceTrack.FRESH_MILLIS
                    cardioAlarm.armQuiet(deadline, "$identity:settle") { onCardioBoundary(identity) }
                } else {
                    cardioAlertAnchor = null
                    cardioAlarm.cancel()
                }
            } else {
                cardioAlertAnchor = null
                cardioAlarm.cancel()
            }
        }
    }

    private fun onCardioBoundary(identity: String) {
        val run = cardioRun.value ?: return
        if (cardioAlarmIdentity(run) != identity) return
        cardioAlertAnchor = null
        reconcileCardio()
        cardioTick.value++
        if (!cardioScreenLive) afterPocketStep(run)
    }

    private fun afterPocketStep(run: CardioRun) {
        val current = cardioRun.value?.takeIf { it.runId == run.runId } ?: return
        if (current.complete && current.sessionId != null) return
        if (current.running) armCardioBoundary(current) else gps.end(current.runId)
    }

    private fun clearCardioExecution(keepDistanceSheet: Boolean = false, keepRecordState: Boolean = false) {
        cardioRun.value?.let { cardioRunStore.delete(it.planId, it.dayId) }
        cardioRun.value?.takeIf { it.block.activity.usesGps }?.let {
            gps.end(it.runId)
            gps.keepRoute(it.runId, false)
        }
        savedState[KEY_GPS_ASK] = null
        if (!keepDistanceSheet) cardioRun.value?.let {
            clearDistanceInputs(it.runId)
            clearGpsStamps(it.runId)
        }
        if (!keepRecordState) cardioRun.value?.let(::clearCardioRecordState)
        cardioAlarm.cancel()
        cardioAlertAnchor = null
        clearSprintTimer()
        savedState[KEY_SPRINT_TIMES] = ArrayList(sprintTimes.value.filterNot { it.runId == cardioRun.value?.runId })
        savedState[KEY_SPRINT_PROTOCOL] = null
        savedState[KEY_SPRINT_TIME_DRAFT] = null
        savedState[KEY_CARDIO_RUN] = null
        savedState[KEY_CARDIO_RUN_DISTANCE] = null
        if (!keepDistanceSheet && openSheet.value?.kind in listOf(OpenSheet.Kind.CARDIO_DISTANCE, OpenSheet.Kind.CARDIO_SETS)) {
            savedState[KEY_OPEN_SHEET] = null
        }
        cardioTick.value++
    }

    private fun buildCard(
        slot: PerformedSlot,
        unit: WeightUnit,
        history: DayHistory,
        plan: PlanEntity?,
        liftGoal: LiftGoal,
        program: Program,
        dayId: String,
        catalog: ExerciseCatalog,
    ): ExerciseCardState {
        val id = slot.slot.programExerciseId
        val performed = slot.main.performed
        val pe = slot.slot.exercise
        val benchmarkLink = performed.prescription?.test?.takeIf { slot.main.benchmark }?.let { ref ->
            TestEvents.matches(ref)?.takeIf { it.exerciseId == performed.exerciseId }?.let { event ->
                BenchmarkLink(ref.copy(windowSeconds = TestEvents.window(event, ref.windowSeconds)), event)
            }
        }
        val benchmark = benchmarkLink?.let { BenchmarkUi(it.event.name, it.ref.windowSeconds, it.event.ceilingSeconds) }
        val cardPrescription = if (benchmarkLink != null) performed.prescription?.copy(
            target = Benchmark.target(benchmarkLink.event, benchmarkLink.ref), eachSide = false,
        ) else performed.prescription
        val entry = performed.entry
        val name = entry?.name ?: performed.exerciseId
        val goalTarget = slot.main.goal
            ?: GoalTarget(0.0, perHand = false)
        val tracking = slot.main.tracking
        val main = slot.main.sets
        val partner = slot.partner?.performed
        val partnerEntry = partner?.entry
        val partnerGoalTarget = slot.partner?.goal
        val partnerTracking = slot.partner?.tracking
        val partnerSets = slot.partner?.sets

        val labels = DayScreenBuilder.kindLabels(main)
        val setLabels = SetLabels.of(main.map { it.kind })
        val rows = main.mapIndexed { i, s ->
            SetRowState(
                index = i,
                kindLabel = labels.getOrElse(i) { "" },
                kind = s.kind,
                rampIndex = (setLabels[i] as? SetLabel.Ramp)?.number ?: 0,
                isTop = s.kind == SetKind.TOP,
                weightDisplay = unit.fromLb(s.weightLb),
                reps = s.reps,
                seconds = s.seconds,
                done = s.done,
                rir = s.rir,
                sides = s.sides,
                partner = partnerSets?.getOrNull(i)?.let {
                    PartnerRowState(weightDisplay = unit.fromLb(it.weightLb), reps = it.reps, done = it.done, seconds = it.seconds, rir = it.rir)
                },
            )
        }
        val lastPerformed = history.last.performed[performed.exerciseId]
        val headerTarget = DayScreenBuilder.headerTarget(pe.copy(prescription = cardPrescription), goalTarget, unit, entry)
        val partnerHeaderTarget = partnerGoalTarget?.let { DayScreenBuilder.headerTarget(
            pe.copy(prescription = null), it, unit) }
        return ExerciseCardState(
            programExerciseId = id,
            exerciseId = performed.exerciseId,
            timedRepDurationSeconds = (cardPrescription?.target as? PrescribedTarget.RepsInTime)?.seconds,
            timedRepAim = slot.main.repAim,
            position = slot.slot.position,
            title = name,
            partnerTitle = partnerEntry?.name ?: partner?.exerciseId,
            partnerExerciseId = partner?.exerciseId,
            equipment = entry?.equipment.orEmpty(),
            note = if (performed.exerciseId != pe.exerciseId) slot.main.note else pe.note,
            isSuperset = pe.superset != null,
            headerTarget = headerTarget,
            marker = LiftMarker.of(pe.rule.takeIf { !pe.keepToPlan && performed.exerciseId == pe.exerciseId }, slot.main.openingInfo, plan?.lightCycleRemaining ?: 0),
            partnerMarker = slot.partner?.let { LiftMarker.of(pe.superset?.rule.takeIf { !pe.keepToPlan && partner?.exerciseId == pe.superset?.exerciseId },
                it.openingInfo, plan?.lightCycleRemaining ?: 0) },
            workingHeading = if (benchmark != null) benchmark.name
                else if (cardPrescription?.target is PrescribedTarget.RepsInTime) headerTarget.display
                else DayScreenBuilder.workingHeading(slot.main.planned ?: main, tracking, unit),
            partnerWorkingHeading = if (partner?.prescription?.target is PrescribedTarget.RepsInTime) partnerHeaderTarget?.display
                else (slot.partner?.planned ?: partnerSets)?.let { DayScreenBuilder.workingHeading(it, partnerTracking ?: TrackingType.WEIGHTED, unit) },
            nextTime = DayScreenBuilder.nextTime(slot.main.baseRows.orEmpty(), history.slotSessions[id to Slot.MAIN]?.takeIf { performed.exerciseId == pe.exerciseId }, pe.keepToPlan, unit,
                offersOn = NextTimeGate.strength(plan, pe.rule?.nextTime), rule = pe.rule),
            partnerNextTime = slot.partner?.let { partnerTrack ->
                DayScreenBuilder.nextTime(partnerTrack.baseRows.orEmpty(), history.slotSessions[id to Slot.SS]?.takeIf { partnerTrack.performed.exerciseId == pe.superset?.exerciseId }, pe.keepToPlan, unit,
                    offersOn = NextTimeGate.strength(plan, pe.superset?.rule?.nextTime), rule = pe.superset?.rule)
            },
            perHand = entry?.perHand == true,
            tracking = tracking,
            timedShowsWeight = slot.main.showsLoad,
            partnerTracking = partnerTracking,
            partnerTimedShowsWeight = slot.partner?.showsLoad == true,
            lastTimeDisplay = DayScreenBuilder.lastTimeDisplay(lastPerformed, history.last.sets[performed.exerciseId].orEmpty(), unit),
            lastTimeDate = history.last.dates[performed.exerciseId]?.let { DayScreenBuilder.lastTimeDate(it, clock.zone) },
            lastTimeSource = history.last.sources[performed.exerciseId],
            follows = LoadFollowing.followedDay(program, dayId, pe, catalog.loadSourceTracking(pe.exerciseId)),
            partnerSetLabels = DayScreenBuilder.kindLabels(partnerSets.orEmpty()),
            partnerHeaderTarget = partnerHeaderTarget,
            partnerLastTimeDisplay = DayScreenBuilder.lastTimeDisplay(partner?.exerciseId?.let(history.last.performed::get),
                partner?.exerciseId?.let(history.last.sets::get).orEmpty(), unit),
            partnerLastTimeDate = partner?.exerciseId?.let(history.last.dates::get)?.let { DayScreenBuilder.lastTimeDate(it, clock.zone) },
            partnerLastTimeSource = partner?.exerciseId?.let(history.last.sources::get),
            personalRecordDisplay = DayScreenBuilder.personalRecordDisplay(history.personalRecords[performed.exerciseId], lastPerformed, unit),
            topSetComparison = DayScreenBuilder.topSetComparison(main, lastPerformed, unit),
            allDone = DayScreenBuilder.allDone(rows),
            focusIndex = DayScreenBuilder.focusIndex(rows),
            summaryLine = DayScreenBuilder.summaryLine(main, partnerSets, tracking, partnerTracking ?: TrackingType.WEIGHTED, unit),
            rows = rows,
            rule = pe.rule,
            liftGoal = liftGoal,
            holdPressureCaution = entry?.pressureCaution == true,
            benchmark = benchmark,
            prescriptionTest = benchmarkLink,
        )
    }

    private data class CommittedDay(val state: DayUiState, val tracks: DayTracks? = null, val context: DayContext? = null)

    /** One day's worth of batched history reads (see [fetchDayHistory]): the A1
     *  "last time" chip and the performance-profile "Best" chip share the same
     *  fetch-once-per-exercise-id-set cadence, so they travel together. */
    private data class DayHistory(
        val last: LastTimes,
        val personalRecords: Map<String, PersonalRecord>,
        val slotSessions: Map<Pair<Long, String>, SlotSession> = emptyMap(),
    )

    private data class DayContext(
        val program: Program,
        val suggested: String?,
        val dayId: String?,
        val unit: WeightUnit,
        val catalog: ExerciseCatalog,
        val plan: PlanEntity? = null,
    )

    private companion object {
        const val REST_TICK_MS = 250L
        const val KEY_WARM_UP = "day_warm_up"
        const val KEY_COOL_DOWN = "day_cool_down"
        const val KEY_CLOSED_WARM_UP_OWNER = "day_closed_warm_up_owner"
        const val KEY_CLOSED_COOL_DOWN_OWNER = "day_closed_cool_down_owner"
        const val KEY_VIEW_DAY = "day_view_override"
        const val KEY_VIEW_SELECTION = "day_view_selection"
        const val KEY_SWAP_UNDO = "day_swap_undo"
        const val KEY_PENDING_START = "day_pending_start_tick"
        const val KEY_PENDING_START_TIMER = "day_pending_start_timer"
        const val KEY_PENDING_START_HOLD = "day_pending_start_hold"
        const val KEY_OPEN_SHEET = "day_open_sheet"
        const val KEY_SELECTED_PAGE = "selectedPageKey"
        const val KEY_TIMED_REP_EVENT = "day_timed_rep_event"
        const val KEY_HOLD = "day_hold"
        const val KEY_HOLD_PAST_TARGET = "day_hold_past_target"
        const val KEY_PAST_CARDIO_EFFORT = "day_past_cardio_effort"
        const val KEY_PAST_CARDIO_START_SET = "day_past_cardio_start_set"
        const val KEY_PAST_CARDIO = "day_past_cardio_draft"
        const val KEY_PAST_CARDIO_DAY = "day_past_cardio_day"
        const val KEY_PAST_CARDIO_RECEIPT_ID = "day_past_cardio_receipt_id"
        const val KEY_CARDIO_GPS_SETS = "day_cardio_gps_sets"
        const val KEY_GPS_ASK = "day_gps_ask"
        const val KEY_SPRINT_TIMER = "day_sprint_timer"
        const val KEY_SPRINT_TIMES = "day_sprint_times"
        const val KEY_SPRINT_PROTOCOL = "day_sprint_protocol"
        const val KEY_SPRINT_TIME_DRAFT = "day_sprint_time_draft"
        const val KEY_CARDIO_RUN = "day_cardio_run"
        const val KEY_CARDIO_RUN_DISTANCE = "day_cardio_run_distance"
        const val KEY_CARDIO_READING_INPUTS = "day_cardio_reading_inputs"
        const val KEY_CARDIO_DISTANCE_INPUTS = "day_cardio_distance_inputs"
        const val KEY_CARDIO_DISTANCES = "day_cardio_distances"
        const val KEY_DEFERRED_CARDIO_RECEIPT = "day_deferred_cardio_receipt"
        const val STOP_TIMEOUT_MS = 5_000L
        const val CARDIO_TICK_MS = 1_000L

        /** How long a removed set stays undoable. Long enough to notice the row
         *  vanish and reach for it, short enough that the offer is gone before
         *  the next set is logged. */
        const val UNDO_WINDOW_MS = 5_000L

        /** How many removals one shared window will hold. Five seconds is not
         *  long enough to deliberately stack more than a couple, so this is a
         *  ceiling on a runaway finger, not a feature. */
        const val UNDO_STACK_LIMIT = 5
    }
}
