package cloud.trotter.log.strength.ui.day

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.data.RunSeed
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.LastPerformed
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.effectiveCardio
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.standards.PhoneRestTimer
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.rest.RestRuntime
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.sync.WatchContinuitySource
import java.time.Instant
import java.time.ZoneId
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Day screen ViewModel (spec §8.2, MVVM/UDF). Holds no working truth of its own:
 * every render value derives from Room/DataStore flows, and every mutation writes
 * straight back through [TrackerRepository]. Ephemeral UI state — the view-day
 * override, page selection and sheet identity — lives in [SavedStateHandle].
 *
 * The open sheet is one atomic, day-scoped identity; rendering and Back use only
 * its resolution against the loaded day's exercises and rows. Resolution never
 * mutates saved state: only opening, closing, switching days or completing a day
 * replaces or clears it. Page selection is one saved key, accepted only from the
 * current pages even before rows exist. The ViewModel collects authoritative day
 * data after seeding and pins a null selection to the derived page once rows
 * exist; subsequent log changes cannot move that selection. The pager reports
 * every settle, including its initial page (page zero while unseeded), and follows
 * the resolved selection only while idle. A day switch or completion clears both
 * identities.
 *
 * Log mutations are read-modify-write over a whole set track, so they are
 * serialized through [mutationLock] — without it two rapid intents on one slot
 * (tick set 1, tick set 2) could each read the same stored list and the second
 * write would silently drop the first.
 *
 * The §8.2 decision logic lives in the pure [DayScreenBuilder]; this class is the
 * flow/write wiring around it. All cascade/edit math comes from `:domain`
 * ([SetEditor]) — never reimplemented here.
 *
 * Cardio runs persist their frozen prescriptions, per-set outcomes and clock anchors
 * as one saved value. Logged distance drafts have separate saved state and never
 * retain execution ownership. Foreground alarms are shared with timed reps.
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
    private val phoneRestRuntime: RestRuntime = NoOpRestRuntime,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val watchContinuity: WatchContinuitySource = WatchContinuitySource.None,
) : ViewModel() {

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
    private val openSheet = savedState.getStateFlow<OpenSheet?>(KEY_OPEN_SHEET, null)
    private val cardioRun = savedState.getStateFlow<CardioRun?>(KEY_CARDIO_RUN, null)
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
    // Only a clock observed running in this foreground session may buzz. Restores
    // and returns after a missed deadline present entry silently.
    private var timedRepAlertAnchor: Long? = null

    /** Serializes all read-modify-write log mutations (see class doc). */
    private val mutationLock = Mutex()

    private val displayTargetFlow = combine(
        repo.todayTargetFlow, repo.selectedPlanFlow, repo.plansFlow, viewDayOverride,
    ) { target, selected, plans, override ->
        val candidates = if (override?.planId != null) plans.filter { it.id == override.planId }
            else listOfNotNull(target?.plan, selected).distinctBy { it.id }
        Triple(target, candidates, override)
    }.flatMapLatest { (target, candidates, override) ->
        if (override == null || candidates.isEmpty()) flowOf(target) else combine(
            candidates.map { plan -> repo.programFor(plan.id).map { plan to it } },
        ) { programs ->
            programs.firstOrNull { (_, program) -> program.days.any { it.id == override.dayId } }
                ?.let { (plan, _) -> TodayTarget(plan, override.dayId) } ?: target
        }
    }.distinctUntilChanged()

    private val effectiveDayId: StateFlow<String?> = displayTargetFlow.flatMapLatest { target ->
        if (target == null) flowOf(null) else repo.programFor(target.plan.id).map { program ->
            resolveDay(program, target.dayId)
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val contextFlow = displayTargetFlow.flatMapLatest { target ->
        if (target == null) combine(repo.programFlow, repo.configFlow, repo.unitFlow, repo.catalogFlow) {
                program, cfg, unit, catalog -> DayContext(program, null, null, cfg, unit, catalog)
        }
        else combine(repo.programFor(target.plan.id), repo.wizardAnswersFor(target.plan.id), repo.unitFlow, repo.catalogFlow) {
                program, answers, unit, catalog ->
            DayContext(program, target.plan.suggestedDayId, resolveDay(program, target.dayId),
                answers.config, unit, catalog, plan = target.plan, cardioPrefs = answers.cardio)
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
    private val dayState: StateFlow<DayUiState> = contextFlow.flatMapLatest { ctx ->
        val dayId = ctx.dayId
        if (dayId == null) {
            flowOf(noDayState(ctx))
        } else {
            // Re-fetched when the day's exercise ids or archived history change.
            // Live-set keystrokes still do not touch this path, while a Log edit
            // immediately corrects both "last time" and "Best" on an open day.
            val planId = ctx.plan?.id ?: return@flatMapLatest flowOf(noDayState(ctx))
            val historyKeys = combine(repo.slotsFor(planId, dayId), repo.sessionSummariesFor(planId)) { slots, _ -> slots }
            val slotsWithHistory = historyKeys.flatMapLatest { slots ->
                flow {
                    ensureSeeded(planId, dayId, slots)
                    emit(slots to fetchDayHistory(slots))
                }
            }
            slotsWithHistory.flatMapLatest { (slots, history) ->
                combine(repo.dayTracksFlow(planId, dayId), repo.openRunFlow) { tracks, open ->
                    buildState(ctx, dayId, slots, tracks.tracks.associateBy { it.programExerciseId to it.slot }, history)
                        .copy(runId = tracks.runId, runOpen = tracks.runOpen,
                            openRunElsewhere = open?.takeIf { it.planId != planId || it.dayId != dayId }?.dayTitle,
                            retiredRoundDoneCount = tracks.retiredRoundsDone,
                            retiredTracks = tracks.retired.mapNotNull { track ->
                                val doneCount = track.sets.count { it.done }
                                if (doneCount == 0) null else RetiredTrackLine(
                                    ctx.catalog.find(track.exerciseId)?.name ?: track.exerciseId, doneCount,
                                )
                            })
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
                .onStart { emit(DayUiState(loading = true)) }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, DayUiState(loading = true))

    /**
     * Keep-screen-on rides on the outside, not through [contextFlow]: it changes
     * nothing about which day is on screen, and threading it through the
     * flatMapLatest would restart the whole branch — and blank the screen —
     * every time a lifter flips a switch mid-workout.
     */
    /** One tick per second while a block runs and someone is watching; nothing
     *  otherwise. Living inside the uiState pipeline means WhileSubscribed owns
     *  its lifecycle — no free-running loop survives the screen. */
    private val cardioSecondTicker = combine(cardioRun, timedRepEvent) { cardio, reps ->
        cardio?.running == true || reps?.awaitingCount == false
    }.distinctUntilChanged().flatMapLatest { running ->
        if (!running) flowOf(0L)
        else flow {
            var beat = 0L
            while (true) {
                emit(beat++)
                delay(CARDIO_TICK_MS)
            }
        }
    }

    private val phoneRestState: Flow<RestUiState?> = repo.phoneRestFlow.flatMapLatest { rest ->
        if (rest == null) flowOf(null) else flow {
            while (true) {
                val now = clock.millis()
                val remaining = PhoneRestTimer.remainingSeconds(rest, now)
                emit(RestUiState(rest, remaining, PhoneRestTimer.remainingFraction(rest, now), remaining == 0))
                if (remaining == 0) {
                    phoneRestRuntime.complete()
                    delay(REST_OVER_MS)
                    phoneRestRuntime.cancel()
                    repo.setPhoneRest(null)
                    break
                }
                delay(REST_TICK_MS)
            }
        }
    }

    private val executionState = combine(
        cardioRun, cardioTick, cardioSecondTicker, timedRepEvent,
    ) { run, _, _, reps -> run to reps }.combine(pendingCardioDistances) { execution, _ -> execution }

    private val cardioHistory = combine(repo.cardioSessionsFlow, repo.cardioSetResultsFlow) { sessions, sets ->
        sessions to sets
    }

    private val baseUiState: Flow<DayUiState> =
        combine(dayState, repo.keepScreenOnFlow, cardioHistory, executionState, phoneRestState) {
                state, preferences, history, execution, rest ->
            val (run, event) = execution
            val cardio = buildCardioState(state, history.first, history.second, run)
            val timedRep = reconcileTimedRep(state, event)
            state.copy(
                keepScreenOn = preferences,
                cardio = cardio,
                timedRep = timedRep,
                executionBusy = cardioRun.value != null || timedRep?.event?.awaitingCount == false,
                cardioRunning = cardioRun.value?.running == true,
                rest = rest,
            )
        }

    private val sheetUiState = combine(baseUiState, selectedPage, openSheet) { state, selection, savedSheet ->
        state.copy(
            selectedPageKey = selection,
            openSheet = savedSheet?.takeIf { sheetResolves(state, it, state.timedRep?.event) },
        )
    }

    val uiState: StateFlow<DayUiState> = combine(
        sheetUiState,
        watchContinuity.continuityFlow,
        watchContinuity.remoteTickFlow,
    ) { state, continuity, remoteTick ->
        DayScreenBuilder.markRemoteTick(
            state.copy(
                watchStatus = DayScreenBuilder.watchStatusLine(
                    continuity.lastContactMillis, continuity.syncingChanges,
                    queuedChanges = 0, watchConnected = continuity.lastContactMillis > 0L,
                    nowMillis = clock.millis(),
                ),
            ),
            remoteTick,
        )
    }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayUiState(loading = true))

    /** Render model for the day-edit sheet (spec §8.3) — a second view over the
     *  same day, kept out of [uiState] so the sheet never touches the
     *  exercise-card render path (see [DayEditUiState] doc). */
    private val editProgramState = contextFlow.flatMapLatest { ctx ->
        val dayId = ctx.dayId
        val plan = ctx.plan
        if (dayId == null || plan == null) flowOf(DayEditUiState()) else combine(
            repo.slotsFor(plan.id, dayId), repo.wizardAnswersFor(plan.id), repo.openRunFlow,
        ) { slots, answers, run ->
            val origin = PlanOrigin.entries.firstOrNull { it.name == plan.origin } ?: PlanOrigin.GENERATED
            val block = ctx.program.days.firstOrNull { it.id == dayId }?.cardioBlock
            buildEditState(slots, ctx.catalog, answers.equipment, origin).copy(
                planId = plan.id, dayId = dayId, cardioBlock = block,
                runOpen = run?.planId == plan.id && run.dayId == dayId,
                canRemove = DayEditRules.canRemove(slots.size, origin, block != null),
            )
        }
    }
    private val prescriptionDraft = savedState.getStateFlow<PrescriptionDraft?>(KEY_PRESCRIPTION_DRAFT, null)
    private val dayEditorOpen = savedState.getStateFlow(KEY_DAY_EDITOR_OPEN, false)
    private val cardioDraft = savedState.getStateFlow<CardioBlockDraft?>(KEY_CARDIO_DRAFT, null)
    private val prescriptionEditState = combine(
        editProgramState, repo.unitFlow, prescriptionDraft, dayEditorOpen,
    ) { state, unit, draft, editorOpen ->
        state.copy(
            unit = unit, editorOpen = editorOpen,
            prescriptionDraft = draft?.takeIf { restored ->
                state.authored && state.slots.any { it.programExerciseId == restored.slotId }
            },
        )
    }
    val dayEditState: StateFlow<DayEditUiState> = combine(prescriptionEditState, cardioDraft) { state, draft ->
        state.copy(cardioDraft = draft?.takeIf {
            state.authored && it.planId == state.planId && it.dayId == state.dayId
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayEditUiState())

    val notificationPermissionAsked = repo.phoneRestNotificationAskedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun markNotificationPermissionAsked() = viewModelScope.launch { repo.markPhoneRestNotificationAsked() }

    /**
     * The cascade scrim (journal brief §2). Intentionally a bare ViewModel field
     * and NOT [SavedStateHandle] — the one piece of UI state in this app that
     * must *not* survive process death (CLAUDE.md's rule guards state the user
     * would lose; this is a moment they already had). It is written only by
     * [completeDay]'s before/after comparison, so no restore, re-collection, or
     * re-entry can produce it.
     */
    private val _cascadeCeremony = MutableStateFlow<CascadeCeremony?>(null)
    val cascadeCeremony: StateFlow<CascadeCeremony?> = _cascadeCeremony.asStateFlow()

    /**
     * The session receipt — the same bare-field treatment as [_cascadeCeremony],
     * and for the same reason: everything it reports is already committed to
     * Room by the time it exists, so losing it to process death costs the lifter
     * nothing, while restoring it would drop a finished workout's summary over
     * whatever they came back to. Rotation keeps it, because a ViewModel
     * outlives a configuration change. A cardio receipt held for an execution
     * that still owns the screen is saved separately until it can be presented.
     */
    private val _sessionReceipt = MutableStateFlow<SessionReceipt?>(null)
    val sessionReceipt: StateFlow<SessionReceipt?> = _sessionReceipt.asStateFlow()

    /**
     * The receipt's SHARE payoff, built by the one service that builds share
     * intents anywhere in this app (session-share brief §4). Set only by
     * [shareSession] and cleared by [shareHandled] once the screen has launched
     * it, so a rotation mid-chooser doesn't fire a second one.
     */
    private val _pendingShare = MutableStateFlow<Intent?>(null)
    val pendingShare: StateFlow<Intent?> = _pendingShare.asStateFlow()

    /**
     * The standing undo offers for removed sets, oldest first — a bounded LIFO
     * stack, because each entry's index describes the track *as it was when
     * that row left it*. Only the last entry's index still means anything
     * against the current list, so only the last entry can be taken; popping it
     * makes the one beneath it current again.
     *
     * A bare field for the same reason as [_cascadeCeremony]: this is a
     * [UNDO_WINDOW_MS]-long courtesy, not user data, and an offer restored
     * after process death would put a row back into a workout the user had
     * already moved on from. One shared window covers the whole stack; it
     * restarts whenever the stack changes, and running out clears all of it.
     * A day switch or a DONE clears it outright.
     */
    private val _removedSets = MutableStateFlow<List<RemovedSet>>(emptyList())
    val removedSets: StateFlow<List<RemovedSet>> = _removedSets.asStateFlow()
    private var undoWindowJob: Job? = null

    init {
        viewModelScope.launch {
            dayState.collect { state ->
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
            maybeAmendCardioDistances()
            effectiveDayId.collect { day ->
                if (day != pinHeldFor) pinHeldFor = null
            }
        }
        viewModelScope.launch {
            combine(cardioRun, timedRepEvent, deferredCardioReceipt) { run, reps, receipt ->
                run == null && reps == null && receipt != null
            }.distinctUntilChanged().collect { ready ->
                if (ready) mutationLock.withLock { showDeferredCardioReceipt() }
            }
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
        setDayEditorOpen(false)
        savedState[KEY_SELECTED_PAGE] = null
        closeSheet()
    }

    fun startCardio() {
        if (timedRepEvent.value?.awaitingCount == false) return
        val existing = cardioRun.value
        if (existing != null) {
            if (!existing.running && !existing.complete && existing.dayId == currentDay()) {
                saveCardioRun(existing.start(cardioClock))
            }
            return
        }
        mutate {
            if (cardioRun.value != null || timedRepEvent.value?.awaitingCount == false) return@mutate
            val run = newCardioRun() ?: return@mutate
            if (timedRepEvent.value?.awaitingCount != false) saveCardioRun(run.start(cardioClock))
        }
    }

    private suspend fun newCardioRun(): CardioRun? {
        val dayId = currentDay() ?: return null
        val planId = dayState.value.takeIf { it.viewDayId == dayId }?.planId ?: return null
        val plan = repo.plansFlow.first().firstOrNull { it.id == planId } ?: return null
        val program = repo.programFor(plan.id).first()
        val day = program.days.firstOrNull { it.id == dayId } ?: return null
        val block = day.effectiveCardio(repo.wizardAnswersFor(plan.id).first().cardio) ?: return null
        val nextId = Rotation.nextFor(program, dayId, authored = plan.origin == PlanOrigin.AUTHORED.name)
            ?: plan.suggestedDayId
        val next = program.days.firstOrNull { it.id == nextId }
        if (currentDay() != dayId || dayState.value.planId != plan.id) return null
        val json = CardioBlockJson.encode(block)
        return CardioRun(plan.id, plan.name, dayId, program.days.indexOf(day), json, json,
            day.exercises.isEmpty(), next?.title)
    }

    private fun saveCardioRun(run: CardioRun) {
        savedState[KEY_CARDIO_RUN] = run
    }

    private fun CardioRun.matches(action: CardioSetAction): Boolean =
        runId == action.runId && dayId == action.dayId && position == action.setIndex

    fun stopCardio(action: CardioSetAction) {
        val run = reconcileCardio() ?: return
        if (!run.matches(action) || !run.running || run.complete) return
        cardioAlarm.cancel()
        cardioAlertAnchor = null
        saveCardioRun(run.finish(CardioSetStatus.SHORTENED, cardioElapsedSeconds(run), cardioClock))
        maybeLogCardio()
    }

    fun reachCardioTarget(action: CardioSetAction) {
        val run = reconcileCardio() ?: return
        if (!run.matches(action) || !run.running || run.complete) return
        val target = run.block.sets[run.position].end as? Measure.Distance ?: return
        var next = run.finish(CardioSetStatus.FINISHED, cardioElapsedSeconds(run), cardioClock, target.metres)
        if (!next.complete && next.block.sets[next.position].role in listOf(CardioRole.RECOVERY, CardioRole.COOLDOWN)) {
            next = next.start(cardioClock)
        }
        saveCardioRun(next)
        maybeLogCardio()
    }

    fun skipCardioSet(action: CardioSetAction) {
        mutate {
            if (action.dayId != currentDay()) return@mutate
            val existing = cardioRun.value
            if (existing?.runId != action.runId || (existing?.position ?: 0) != action.setIndex) return@mutate
            val run = existing ?: newCardioRun() ?: return@mutate
            if (timedRepEvent.value?.awaitingCount == false || run.running || run.complete) return@mutate
            saveCardioRun(run.finish(CardioSetStatus.SKIPPED, 0, cardioClock))
            maybeLogCardio()
        }
    }

    fun endCardioBlock() {
        mutate {
            if (timedRepEvent.value?.awaitingCount == false) return@mutate
            val run = reconcileCardio() ?: newCardioRun() ?: return@mutate
            endCardioRun(run)
        }
    }

    private fun endCardioRun(run: CardioRun) {
        var ended = run
        if (ended.running) {
            ended = ended.finish(CardioSetStatus.SHORTENED, cardioElapsedSeconds(ended), cardioClock, requestDistance = false)
        }
        while (!ended.complete) ended = ended.finish(CardioSetStatus.SKIPPED, 0, cardioClock)
        cardioAlarm.cancel()
        cardioAlertAnchor = null
        saveCardioRun(ended)
        maybeLogCardio()
    }

    fun addCardioSet() {
        val dayId = currentDay() ?: return
        mutate {
            if (currentDay() != dayId || cardioRun.value?.let { it.dayId != dayId } == true) return@mutate
            val run = reconcileCardio() ?: newCardioRun() ?: return@mutate
            if (run.dayId != dayId || run.complete || timedRepEvent.value?.awaitingCount == false) return@mutate
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
        saveDistanceRun(run.copy(drafts = run.drafts.map { if (it.position == action.setIndex) it.copy(text = text) else it }))
    }

    fun saveCardioDistance(action: CardioSetAction) {
        mutate {
            if (distanceSheetAction() != action) return@mutate
            val run = distanceRun(action) ?: return@mutate
            val position = action.setIndex
            val draft = run.drafts.firstOrNull { it.position == position && it.valid && !it.saved } ?: return@mutate
            val next = run.copy(results = run.results.mapIndexed { index, result ->
                if (index == position) result.copy(metres = draft.metres) else result
            }, drafts = run.drafts.map { if (it.position == position) it.copy(saved = true, amended = false) else it }, saveFailed = false)
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
    }

    private fun cardioElapsedSeconds(run: CardioRun): Int =
        (run.elapsedMillis(cardioClock) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun reconcileCardio(): CardioRun? {
        val before = cardioRun.value ?: return null
        val after = before.reconcile(cardioClock)
        if (after != before) {
            saveCardioRun(after)
            if (cardioScreenLive && cardioAlertAnchor == cardioAlarmIdentity(before)) cardioAlarm.buzz()
            cardioAlertAnchor = null
            maybeLogCardio()
        }
        return after
    }

    private fun cardioAlarmIdentity(run: CardioRun): String = "cardio:${run.startedAt}:${run.position}:${run.wallStart}"

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
                    val mode = CardioBlocks.legacyModeOf(activity)
                    // Keep entry on its originating day while the committed rotation moves on.
                    if (current.finishesDay && current.showReceipt) {
                        savedState[KEY_VIEW_SELECTION] = DaySelection(current.planId, current.dayId)
                    }
                    val id = current.sessionId ?: repo.logCardioSession(CardioSessionEntity(
                        dayId = current.dayId, mode = mode.name,
                        hard = current.block.sets.any { it.role == CardioRole.WORK && it.hard },
                        label = activityLabel(activity), startedAt = requireNotNull(current.startedAt),
                        completedAt = requireNotNull(current.completedAt),
                        seconds = current.results.sumOf { it.seconds },
                        stepsCompleted = current.results.count { it.status == CardioSetStatus.FINISHED.name },
                        planId = current.planId, planName = current.planName, activity = activity.name,
                    ), current.resultSets, finishDay = current.finishesDay)
                    val latest = cardioRun.value?.takeIf { it.runId == current.runId } ?: return@withLock
                    val logged = latest.copy(sessionId = id)
                    // Drafts own only amendment state, never the execution slot or alarm.
                    if (logged.drafts.isNotEmpty()) {
                        savedState[KEY_CARDIO_DISTANCES] = ArrayList(
                            pendingCardioDistances.value.filterNot { it.runId == logged.runId } + logged,
                        )
                    }
                    clearCardioExecution(keepDistanceSheet = true)
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
                    pending.drafts.filter { it.saved && !it.amended }.forEach { draft ->
                        repo.amendCardioSetDistance(id, draft.position, draft.metres)
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
        if (!run.finishesDay || !run.showReceipt) return
        // Keep the first due receipt when another run finishes before the screen is free.
        if (deferredCardioReceipt.value == null) savedState[KEY_DEFERRED_CARDIO_RECEIPT] = run
        showDeferredCardioReceipt()
    }

    /** Read current ownership at navigation time, before the UI flow catches up. */
    val executionActive: Boolean get() = cardioRun.value != null || timedRepEvent.value != null

    private suspend fun showDeferredCardioReceipt() {
        if (executionActive) return
        val run = deferredCardioReceipt.value ?: return
        val advancedTo = repo.suggestedDayFlow.first()
        if (executionActive) return
        savedState[KEY_DEFERRED_CARDIO_RECEIPT] = null
        _sessionReceipt.value = SessionReceipt(
            sessionId = requireNotNull(run.sessionId), dayIndex = run.dayIndex, headline = "", dayTitle = "",
            completedSetCount = 0, totalSetCount = 0, setCount = 0, strongest = null,
            nextDayLine = run.nextDayTitle, cardio = run,
        )
        if (currentDay() == run.dayId) {
            savedState[KEY_VIEW_SELECTION] = null
            pinHeldFor = run.dayId.takeIf { advancedTo != it && effectiveDayId.value == it }
            savedState[KEY_SELECTED_PAGE] = null
            closeSheet()
        }
    }

    /** Called by the day-screen lifecycle; background screens never retain a boundary alarm. */
    fun setCardioScreenLive(live: Boolean) {
        cardioScreenLive = live
        if (!live) {
            timedRepAlertAnchor = null
            cardioAlertAnchor = null
            cardioAlarm.cancel()
        } else cardioTick.value++
    }

    fun startTimedRep(programExerciseId: Long, setIndex: Int) {
        if (cardioRun.value != null || timedRepEvent.value != null) return
        val state = uiState.value
        val day = currentDay() ?: return
        val card = state.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return
        val duration = card.timedRepDurationSeconds?.takeIf { it > 0 } ?: return
        if (state.viewDayId != day || card.focusIndex != setIndex || card.rows.getOrNull(setIndex)?.done != false) return
        // Reserve synchronously, so a cardio Start or a second tap cannot replace the alarm.
        savedState[KEY_TIMED_REP_EVENT] = TimedRepEvent(
            day, programExerciseId, card.exerciseId, setIndex, duration,
            cardioClock.wallMillis(), cardioClock.elapsedRealtimeMillis(), cardioClock.bootCount(),
        )
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
        tickSet(event.programExerciseId, event.setIndex, true, false, event)
    }

    override fun onCleared() {
        cardioAlarm.cancel()
        super.onCleared()
    }

    fun changeWeight(programExerciseId: Long, slot: String, index: Int, newDisplayWeight: Double) {
        val day = currentDay() ?: return
        val runId = dayState.value.takeIf { it.viewDayId == day }?.runId ?: return
        mutate {
            val unit = repo.unitFlow.first()
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            val updated = SetEditor.editWeight(sets, index, unit.toLb(newDisplayWeight))
            repo.updateSets(runId, day, programExerciseId, slot, updated)
            if (sets[index].kind == SetKind.TOP && updated != sets) repo.markTopSetHelperSeen()
        }
    }

    fun changeReps(programExerciseId: Long, slot: String, index: Int, newReps: Int) {
        val day = currentDay() ?: return
        val runId = dayState.value.takeIf { it.viewDayId == day }?.runId ?: return
        mutate {
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            val updated = SetEditor.editReps(sets, index, newReps)
            repo.updateSets(runId, day, programExerciseId, slot, updated)
            if (sets[index].kind == SetKind.TOP && updated != sets) repo.markTopSetHelperSeen()
        }
    }

    /** TIMED tracks' seconds edit — same write-immediately shape as
     *  [changeReps]/[changeWeight], routed through [SetEditor.editSeconds]
     *  (never cascades, exactly like reps). */
    fun changeSeconds(programExerciseId: Long, slot: String, index: Int, newSeconds: Int) {
        val day = currentDay() ?: return
        val runId = dayState.value.takeIf { it.viewDayId == day }?.runId ?: return
        mutate {
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            repo.updateSets(runId, day, programExerciseId, slot, SetEditor.editSeconds(sets, index, newSeconds))
        }
    }

    fun toggleDone(programExerciseId: Long, index: Int, checked: Boolean, isSuperset: Boolean) {
        tickSet(programExerciseId, index, checked, isSuperset)
    }

    private fun tickSet(
        programExerciseId: Long,
        index: Int,
        checked: Boolean,
        isSuperset: Boolean,
        countEvent: TimedRepEvent? = null,
    ) {
        val day = currentDay() ?: return
        val runId = dayState.value.takeIf { it.viewDayId == day }?.runId ?: return
        mutate {
            if (countEvent != null) {
                if (timedRepEvent.value != countEvent || day != countEvent.dayId ||
                    !timedRepMatches(dayState.value, countEvent)
                ) return@mutate
                val planId = repo.planIdOfSlot(programExerciseId) ?: return@mutate
                val slot = repo.slotsFor(planId, day).first().firstOrNull { it.programExerciseId == programExerciseId }
                    ?: return@mutate
                val target = slot.exercise.prescription?.target as? PrescribedTarget.RepsInTime
                if (slot.exercise.exerciseId != countEvent.exerciseId || target?.seconds != countEvent.durationSeconds) return@mutate
            }
            val storedMain = trackFor(day, programExerciseId, Slot.MAIN)
            if (countEvent != null && storedMain.getOrNull(index)?.done != false) return@mutate
            val main = if (countEvent == null) storedMain else storedMain.mapIndexed { i, set ->
                if (i == index) set.copy(reps = countEvent.count ?: return@mutate, seconds = 0) else set
            }
            // A missing partner track (never seeded) must stay missing — writing an
            // empty SS row would mark it as seeded forever. Same rule as addSet.
            val partner = if (isSuperset) {
                trackFor(day, programExerciseId, Slot.SS).takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner, index, checked)
            val result = if (newPartner != null) {
                repo.updateSetsPaired(runId, day, programExerciseId, newMain, newPartner)
            } else {
                repo.updateSets(runId, day, programExerciseId, Slot.MAIN, newMain)
            }
            if (result == RunWriteResult.Rejected) return@mutate
            if (countEvent != null) clearTimedRep()
            if (checked && isSuperset && newMain != main) repo.markSupersetHelperSeen()
            if (checked && phoneRestRuntime.available && repo.phoneRestTimerEnabledFlow.first()) {
                val card = uiState.value.exercises.firstOrNull { it.programExerciseId == programExerciseId }
                val set = main.getOrNull(index)
                if (card != null && set != null) {
                    val settings = repo.restSettingsFlow.first()
                    val seconds = RestPolicy.effectiveRestSeconds(set.kind, card.tracking, settings.overrides)
                    val projected = uiState.value.exercises.map { current ->
                        if (current.programExerciseId != programExerciseId) current
                        else current.copy(rows = current.rows.map { row -> if (row.index == index) row.copy(done = true) else row })
                    }
                    val nextCard = projected.firstOrNull { it.rows.any { row -> !row.done } }
                    val nextRow = nextCard?.rows?.firstOrNull { !it.done }
                    val next = if (nextCard != null && nextRow != null) "${nextCard.title} ${nextRow.kindLabel}" else ""
                    PhoneRestTimer.start(clock.millis(), seconds, next)?.let { rest ->
                        repo.setPhoneRest(rest)
                        phoneRestRuntime.arm(rest)
                    }
                }
            }
        }
    }

    fun adjustRest(seconds: Int) = viewModelScope.launch {
        val current = repo.phoneRestFlow.first() ?: return@launch
        val adjusted = PhoneRestTimer.adjust(current, clock.millis(), seconds)
        repo.setPhoneRest(adjusted)
        if (adjusted == null) phoneRestRuntime.cancel() else phoneRestRuntime.arm(adjusted)
    }

    fun skipRest() = viewModelScope.launch { repo.setPhoneRest(null); phoneRestRuntime.cancel() }

    fun addSet(programExerciseId: Long, isSuperset: Boolean) {
        val day = currentDay() ?: return
        val runId = dayState.value.takeIf { it.viewDayId == day }?.runId ?: return
        mutate {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            // An unseeded track has no last row to copy — nothing to add yet.
            if (main.isEmpty()) return@mutate
            val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS) else emptyList()
            cancelTimedRepForSetListChange(day, programExerciseId)
            if (partner.isNotEmpty()) {
                val (newMain, newPartner) = SetEditor.addSetPaired(main, partner)
                repo.updateSetsPaired(runId, day, programExerciseId, newMain, newPartner)
            } else {
                // No partner track exists (e.g. its catalog entry is unknown, so it
                // never seeded) — there is nothing to keep aligned; plain add.
                repo.updateSets(runId, day, programExerciseId, Slot.MAIN, SetEditor.addSet(main))
            }
        }
    }

    /** The × on a set row. Low-friction by design (mid-workout, high frequency),
     *  so it stays one tap and offers [undoRemoveSet] afterwards instead of
     *  asking first — see [_removedSet]. */
    fun removeSet(programExerciseId: Long, index: Int, isSuperset: Boolean) {
        val day = currentDay() ?: return
        val runId = dayState.value.takeIf { it.viewDayId == day }?.runId ?: return
        mutate {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            if (index !in main.indices) return@mutate
            // SetEditor's floor: the last row never goes. Nothing was removed,
            // so nothing is offered back.
            if (main.size <= 1) return@mutate
            val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS) else emptyList()
            cancelTimedRepForSetListChange(day, programExerciseId)
            // Offered before the write lands: removing the last unfinished row
            // finishes the card, and the screen has to know the offer is open
            // before it decides whether to fold that card away.
            pushUndo(
                RemovedSet(
                    programExerciseId = programExerciseId,
                    index = index,
                    dayId = day,
                    runId = runId,
                    main = main[index],
                    partner = partner.getOrNull(index),
                ),
            )
            if (partner.isNotEmpty()) {
                val (newMain, newPartner) = SetEditor.removeSetPaired(main, partner, index)
                repo.updateSetsPaired(runId, day, programExerciseId, newMain, newPartner)
            } else {
                repo.updateSets(runId, day, programExerciseId, Slot.MAIN, SetEditor.removeSet(main, index))
            }
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
            val main = trackFor(day, removed.programExerciseId, Slot.MAIN)
            val partner = trackFor(day, removed.programExerciseId, Slot.SS)
            cancelTimedRepForSetListChange(day, removed.programExerciseId)
            if (removed.partner != null && partner.isNotEmpty()) {
                val (newMain, newPartner) =
                    SetEditor.insertSetPaired(main, partner, removed.index, removed.main, removed.partner)
                repo.updateSetsPaired(runId, day, removed.programExerciseId, newMain, newPartner)
            } else {
                val restored = SetEditor.insertSet(main, removed.index, removed.main)
                repo.updateSets(runId, day, removed.programExerciseId, Slot.MAIN, restored)
            }
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

    fun selectPage(key: String) {
        val state = dayState.value
        if (state.viewDayId != currentDay() || state.pages.none { it.key == key }) return
        pinHeldFor = null
        savedState[KEY_SELECTED_PAGE] = key
    }

    fun openSetEditor(id: Long, index: Int) {
        val day = currentDay() ?: return
        savedState[KEY_OPEN_SHEET] = OpenSheet(day, OpenSheet.Kind.EDITOR, id, index)
    }

    fun openAllSets(id: Long) {
        val day = currentDay() ?: return
        savedState[KEY_OPEN_SHEET] = OpenSheet(day, OpenSheet.Kind.ALL_SETS, id, 0)
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

    /** Writes the keep-screen-on preference. It lives in DataStore, not
     *  [SavedStateHandle], so it outlives the process the way every other thing
     *  the user chose does — and the activity, not this screen, applies it. */
    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { repo.setKeepScreenOn(on) }
    }

    fun discardWorkout() {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day && it.runOpen } ?: return
        val runId = state.runId ?: return
        val planId = state.planId ?: return
        mutate {
            closeUndoWindow()
            if (repo.discardRun(runId)) {
                clearTimedRep()
                seedWorkingValues(planId, day, repo.slotsFor(planId, day).first())
            }
        }
    }

    fun setExerciseNote(programExerciseId: Long, text: String) {
        viewModelScope.launch { repo.setExerciseNote(programExerciseId, text) }
    }

    fun setReceiptNote(text: String) {
        val sessionId = _sessionReceipt.value?.sessionId ?: return
        val clean = text.trim().take(120)
        viewModelScope.launch {
            repo.setSessionNote(sessionId, clean)
            _sessionReceipt.update { it?.takeIf { receipt -> receipt.sessionId == sessionId }?.copy(note = clean) }
        }
    }

    /** DONE — advance: record the session (A1), follow the new suggested day,
     *  then hand the just-written session to Health Connect (D7 trigger
     *  point). The publish is fired after `finishRun` returns and never
     *  blocks or fails the completion — [SessionPublisher] swallows every
     *  failure path, and the no-op binding covers devices without HC.
     *
     *  The all-time top-set highs are read *before* the advance appends to
     *  history; comparing them with what the session actually recorded is the
     *  whole cascade-ceremony trigger (journal brief §2). Nothing about the
     *  ceremony is stored, so it can only ever fire on this transition.
     *
     *  Both post-DONE surfaces are raised here off ONE read of the just-written
     *  session's sets: the cascade says what moved, the receipt says what was
     *  done. Reading twice would let them describe different sessions if a watch
     *  delta landed in between. The completed day's id, index and title are all
     *  captured before [TrackerRepository.finishRun] moves the pointer, because
     *  from the next line on "the current day" means the next one. */
    fun completeDay() {
        val day = currentDay() ?: return
        val state = dayState.value.takeIf { it.viewDayId == day && it.runOpen } ?: return
        val planId = state.planId ?: return
        val runId = state.runId ?: return
        mutate {
            // Counted from the log itself, under the lock: uiState is either
            // the loading placeholder (nobody collecting) or fed by a flow
            // that seeds under this same lock, so waiting on it here would
            // deadlock. Rounds only — the header counts the same way.
            val tracks = repo.dayTracksFlow(planId, day).first()
            if (!tracks.runOpen || tracks.runId != runId) return@mutate
            closeUndoWindow()
            val rounds = tracks.tracks.filter { Slot.isRound(it.slot) }
            val completedSetCount = rounds.sumOf { track -> track.sets.count { it.done } } + tracks.retiredRoundsDone
            val totalSetCount = rounds.sumOf { it.sets.size } + tracks.retiredRoundsDone
            if (completedSetCount == 0) return@mutate
            val program = repo.programFor(planId).first()
            val previousHighs = CascadeCeremonyBuilder.allTimeHighs(repo.topSetHistoryFlow.first())
            val dayIndex = program.days.indexOfFirst { it.id == day }
            val plan = repo.plansFlow.first().firstOrNull { it.id == planId }
            val nextDayId = if (dayIndex >= 0) {
                Rotation.nextFor(program, day, authored = plan?.origin == PlanOrigin.AUTHORED.name)
                    ?: repo.suggestedDayFlow.first()
            } else null
            val sessionId = repo.finishRun(runId) ?: return@mutate
            if (timedRepEvent.value?.dayId == day) clearTimedRep()
            savedState[KEY_VIEW_SELECTION] = null
            val advancedTo = repo.suggestedDayFlow.first()
            pinHeldFor = day.takeIf { advancedTo != day && effectiveDayId.value == day }
            savedState[KEY_SELECTED_PAGE] = null
            closeSheet()
            val unit = repo.unitFlow.first()
            val sessionSets = repo.sessionSets(sessionId)
            _cascadeCeremony.value = CascadeCeremonyBuilder.from(
                previousHighs = previousHighs,
                sessionSets = sessionSets,
                dayIndex = dayIndex.coerceAtLeast(0),
                unit = unit,
            )
            _sessionReceipt.value = SessionReceiptBuilder.from(
                sessionId = sessionId,
                dayId = day,
                dayIndex = dayIndex.coerceAtLeast(0),
                dayTitle = program.days.firstOrNull { it.id == day }?.title.orEmpty(),
                completedSetCount = completedSetCount,
                totalSetCount = totalSetCount,
                sessionSets = sessionSets,
                nextDayId = nextDayId,
                nextDayTitle = program.days.firstOrNull { it.id == nextDayId }?.title.orEmpty(),
                unit = unit,
            )
            viewModelScope.launch { sessionPublisher.publish(sessionId) }
        }
    }

    /** Dismisses the scrim (tap anywhere, or back). */
    fun dismissCascadeCeremony() {
        _cascadeCeremony.value = null
    }

    /** Returns whether the receipt closed and navigation back to Today is safe. */
    fun dismissSessionReceipt(): Boolean {
        if (executionActive || _sessionReceipt.value == null) return false
        _sessionReceipt.value = null
        return true
    }

    /** The receipt's SHARE. Renders off the main thread inside
     *  [ShareCardService]; a session that somehow no longer resolves simply
     *  produces no intent and no chooser. */
    fun shareSession() {
        val sessionId = _sessionReceipt.value?.sessionId ?: return
        viewModelScope.launch {
            shareCardService.buildShareIntent(sessionId)?.let { _pendingShare.value = it }
        }
    }

    /** Called once the screen has launched [pendingShare], so it doesn't fire again. */
    fun shareHandled() {
        _pendingShare.value = null
    }

    // --- day-edit sheet intents (spec §8.3) ------------------------------

    fun setDayEditorOpen(open: Boolean) {
        savedState[KEY_DAY_EDITOR_OPEN] = open
        if (!open) {
            discardPrescription()
            savedState[KEY_CARDIO_DRAFT] = null
        }
    }

    fun openCardioBlock() {
        val state = dayEditState.value
        if (!state.authored) return
        val plan = state.planId ?: return
        val day = state.dayId ?: return
        savedState[KEY_CARDIO_DRAFT] = state.cardioBlock?.let { CardioBlockDraft.from(plan, day, it) }
            ?: CardioBlockDraft.new(plan, day)
    }

    fun changeCardioDraft(draft: CardioBlockDraft) {
        val current = cardioDraft.value ?: return
        if (draft.planId != current.planId || draft.dayId != current.dayId) return
        draft.block() // Validate before keeping a draft that a later Save will commit.
        require(draft.editingSet == null || draft.editingSet in draft.sets.indices)
        savedState[KEY_CARDIO_DRAFT] = draft
    }

    fun backCardioBlock() {
        val draft = cardioDraft.value ?: return
        savedState[KEY_CARDIO_DRAFT] = if (draft.editingSet != null) draft.copy(editingSet = null) else null
    }

    /** Both set-page Save and block-page Save commit immediately to the owning day. */
    fun saveCardioBlock() {
        val draft = cardioDraft.value ?: return
        mutate {
            repo.setCardioBlock(draft.planId, draft.dayId, draft.block())
            if (cardioDraft.value == draft) {
                savedState[KEY_CARDIO_DRAFT] = if (draft.editingSet != null) draft.copy(editingSet = null) else null
            }
        }
    }

    fun removeCardioBlock() {
        val state = dayEditState.value
        if (!state.authored || state.slots.isEmpty()) return
        val plan = state.planId ?: return
        val day = state.dayId ?: return
        mutate { repo.setCardioBlock(plan, day, null) }
    }

    fun openPrescription(slotId: Long) {
        val state = dayEditState.value
        if (!state.authored) return
        val slot = state.slots.firstOrNull { it.programExerciseId == slotId } ?: return
        savedState[KEY_PRESCRIPTION_DRAFT] = PrescriptionDraft.from(slot)
    }

    fun changePrescriptionDraft(draft: PrescriptionDraft) {
        val current = prescriptionDraft.value ?: return
        if (current.slotId != draft.slotId) return
        savedState[KEY_PRESCRIPTION_DRAFT] = draft.copy(resistance = draft.resistance.take(40))
    }

    fun discardPrescription() { savedState[KEY_PRESCRIPTION_DRAFT] = null }

    fun savePrescription() {
        val draft = prescriptionDraft.value ?: return
        mutate {
            repo.setPrescription(draft.slotId, draft.prescription(), draft.sets)
            discardPrescription()
        }
    }

    fun moveDaySlot(slotId: Long, up: Boolean) { mutate { repo.moveExercise(slotId, up) } }

    /** Swaps the slot's exercise while the run keeps done work.
     * The new exercise seeds from its own goal on the next observation. */
    fun swapDaySlot(position: Int, newExerciseId: String) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            if (repo.plansFlow.first().firstOrNull { it.id == planId }?.origin == PlanOrigin.AUTHORED.name) {
                // Removal leaves position gaps; the legacy position writer takes an ordinal.
                val slot = repo.slotsFor(planId, day).first().firstOrNull { it.position == position } ?: return@mutate
                repo.swapExerciseById(day, slot.programExerciseId, newExerciseId)
            } else {
                repo.swapExercise(day, position, newExerciseId, targetPlanId = planId)
            }
        }
    }

    /** Appends a new slot for the picked exercise (spec §8.3 add flow). Mirrors
     *  `ProgramGenerator`'s plain-accessory shape (3 sets, "8–12") — a sane
     *  default for a manually added exercise, not a re-seed of pinned math. */
    fun addDaySlot(exerciseId: String) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate { repo.addExercise(day, ProgramExercise(exerciseId = exerciseId, repSchemeLabel = "8–12"), targetPlanId = planId) }
    }

    /** Removes a slot; the repository enforces its owning day's floor atomically. */
    fun removeDaySlot(position: Int) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            val slots = repo.slotsFor(planId, day).first()
            val slot = slots.firstOrNull { it.position == position } ?: return@mutate
            repo.removeExercise(slot.programExerciseId)
        }
    }

    /** Changes the superset partner while the run keeps done work and the main track.
     * The new partner seeds on the next observation, aligned to the main track. */
    fun setDaySlotSuperset(position: Int, partnerExerciseId: String) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate { repo.setSupersetPartner(day, position, partnerExerciseId, targetPlanId = planId) }
    }

    fun setDaySlotKeepToPlan(slotId: Long, keep: Boolean) {
        mutate { repo.setKeepToPlan(slotId, keep) }
    }

    fun resetDaySlotToPlan(slotId: Long) {
        mutate { repo.resetSlotToPlan(slotId) }
    }

    /** Breaking a superset back into a plain slot: the partner and its SS track
     *  go, the main track stays. */
    fun removeDaySlotSuperset(position: Int) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate { repo.removeSupersetPartner(day, position, targetPlanId = planId) }
    }

    /** The reset-day-to-template escape hatch (spec §8.3): regenerates this one
     *  day from the stored wizard answers; other days are untouched. */
    fun resetDayToTemplate() {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate { repo.resetDayToTemplate(day, targetPlanId = planId) }
    }

    // --- seeding & bootstrap -------------------------------------------------

    /** Seeds each day's logs before its authoritative rows are observed. */
    private suspend fun ensureSeeded(planId: Long, dayId: String, slots: List<ProgramSlot>) = mutationLock.withLock {
        val tracks = repo.dayTracksFlow(planId, dayId).first()
        val existing = tracks.tracks.associate { (it.programExerciseId to it.slot) to it.sets.size }
        val cfg = repo.wizardAnswersFor(planId).first().config
        val catalog = repo.catalogFlow.first()
        val seeds = DayScreenBuilder.seedPlan(slots, existing, cfg, catalog)
        val runId = tracks.runId
        if (tracks.runOpen && runId != null) {
            repo.seedRunTracksIfMissing(runId, seeds.map { RunSeed(it.programExerciseId, it.slot, it.sets) })
        } else {
            seeds.forEach { repo.seedIfEmpty(dayId, it.programExerciseId, it.slot, it.sets) }
        }
    }

    private suspend fun seedWorkingValues(planId: Long, dayId: String, slots: List<ProgramSlot>) {
        val existing = repo.dayTracksFlow(planId, dayId).first().tracks
            .associate { (it.programExerciseId to it.slot) to it.sets.size }
        val cfg = repo.wizardAnswersFor(planId).first().config
        val catalog = repo.catalogFlow.first()
        DayScreenBuilder.seedPlan(slots, existing, cfg, catalog).forEach {
            repo.seedIfEmpty(dayId, it.programExerciseId, it.slot, it.sets)
        }
    }

    // --- helpers -------------------------------------------------------------

    /** Launches a log mutation with its read-modify-write held under [mutationLock]. */
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch { mutationLock.withLock { block() } }
    }

    private fun currentDay(): String? = effectiveDayId.value

    private suspend fun trackFor(dayId: String, programExerciseId: Long, slot: String): List<LoggedSet> {
        val planId = repo.planIdOfSlot(programExerciseId) ?: return emptyList()
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

    private fun resolveDay(program: Program, suggested: String?): String? {
        val allIds = program.days.map { it.id }
        val strengthIds = program.strengthDays.map { it.id }
        return suggested?.takeIf { it in allIds }
            ?: strengthIds.firstOrNull() ?: allIds.firstOrNull()
    }

    /** One batched read for the whole day's "last time" chips (PLAN.md A1
     *  bonus) AND its "Best" profile chips (performance-profile.md Phase 1)
     *  — never one query per exercise for either. */
    private suspend fun fetchDayHistory(slots: List<ProgramSlot>): DayHistory {
        val ids = slots.map { it.exercise.exerciseId }.distinct()
        return DayHistory(repo.lastPerformed(ids), repo.personalRecords(ids))
    }

    private fun buildState(
        ctx: DayContext,
        dayId: String,
        slots: List<ProgramSlot>,
        logsByKey: Map<Pair<Long, String>, cloud.trotter.log.strength.data.LoggedSlot>,
        history: DayHistory,
    ): DayUiState {
        validateCardioRun(ctx)
        val program = ctx.program
        val day = program.days.firstOrNull { it.id == dayId }
            ?: return noDayState(ctx)
        val dayIndex = program.strengthDays.indexOfFirst { it.id == dayId }.coerceAtLeast(0)
        return DayUiState(
            hasProgram = true,
            isProgramDay = program.strengthDays.any { it.id == dayId },
            viewDayId = dayId,
            planId = ctx.plan?.id,
            dayIndex = dayIndex,
            dayTitle = day.title,
            emphasisLine = day.emphasisLine,
            unit = ctx.unit,
            nextDayId = Rotation.nextFor(program, dayId, authored = ctx.plan?.origin == PlanOrigin.AUTHORED.name),
            exercises = slots.map { buildCard(it, logsByKey, ctx.cfg, ctx.unit, ctx.catalog, history) },
            cardio = day.effectiveCardio(ctx.cardioPrefs)?.let {
                CardioCardState(label = activityLabel(it.activity), detail = day.cardio?.detail.orEmpty(),
                    hard = it.sets.any { spec -> spec.hard }, block = it, finishesDay = day.exercises.isEmpty())
            },
        )
    }

    private fun sheetResolves(state: DayUiState, sheet: OpenSheet, event: TimedRepEvent?): Boolean =
        !state.loading && sheet.dayId == state.viewDayId &&
            if (sheet.kind == OpenSheet.Kind.CARDIO_SETS || sheet.kind == OpenSheet.Kind.CARDIO_DISTANCE) {
                state.cardio != null && (sheet.kind != OpenSheet.Kind.CARDIO_DISTANCE ||
                    distanceRuns().any { run ->
                        run.runId == sheet.cardioRunId && run.dayId == sheet.dayId && run.planId == state.planId &&
                            run.drafts.any { it.position == sheet.setIndex }
                    })
            } else state.exercises.any { card ->
            card.programExerciseId == sheet.programExerciseId &&
                (sheet.kind == OpenSheet.Kind.ALL_SETS || card.rows.getOrNull(sheet.setIndex) != null) &&
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
        val elapsed = derivedElapsedMillis(CardioAnchors(null, event.wallStart, event.elapsedStart, event.bootCount))
        val remainingMillis = (event.durationSeconds * 1_000L - elapsed).coerceAtLeast(0L)
        if (remainingMillis == 0L) {
            finishTimedRep(event, buzz = cardioScreenLive && timedRepAlertAnchor == event.wallStart)
            return TimedRepState(event.copy(awaitingCount = true), 0)
        }
        if (cardioScreenLive) {
            timedRepAlertAnchor = event.wallStart
            cardioAlarm.arm(
                cardioClock.elapsedRealtimeMillis() + remainingMillis,
                "timed-reps:${event.programExerciseId}:${event.setIndex}:${event.wallStart}",
            ) { finishTimedRep(event, buzz = false) }
        }
        return TimedRepState(event, ((remainingMillis + 999L) / 1_000L).toInt())
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

    private fun cancelTimedRepForSetListChange(dayId: String, programExerciseId: Long) {
        val event = timedRepEvent.value ?: return
        // Rows have positional identities. Cancel on every structural change in
        // this slot, even after the event's index, rather than risk retargeting it.
        if (event.dayId == dayId && event.programExerciseId == programExerciseId) clearTimedRep()
    }

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
            endCardioRun(run.reconcile(cardioClock).copy(showReceipt = false))
        } else if (run.startedAt == null &&
            day.effectiveCardio(ctx.cardioPrefs)?.let(CardioBlockJson::encode) != run.sourceBlockJson
        ) {
            clearCardioExecution()
        }
    }

    private fun buildCardioState(
        state: DayUiState,
        history: List<CardioSessionEntity>,
        sets: Map<Long, List<cloud.trotter.log.strength.domain.model.CardioSetResult>>,
        savedRun: CardioRun?,
    ): CardioCardState? {
        // An invalidated run still owns the execution slot until its save succeeds.
        // Keep its existing failure/Retry card reachable from the day now in view.
        if (!state.loading && savedRun != null && savedRun == cardioRun.value &&
            !savedRun.showReceipt && savedRun.saveFailed &&
            (savedRun.planId != state.planId || savedRun.dayId != state.viewDayId)
        ) {
            return CardioCardState(activityLabel(savedRun.block.activity), "", savedRun.block.sets.any { it.hard },
                block = savedRun.block, run = savedRun, canAddSet = false, finishesDay = savedRun.finishesDay)
        }
        val civilDate = Instant.ofEpochMilli(cardioClock.wallMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
        val logged = history.firstOrNull {
            it.planId == state.planId && it.dayId == state.viewDayId &&
                Instant.ofEpochMilli(it.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == civilDate
        }
        val loggedSets = logged?.let { sets[it.id] }.orEmpty()
        val frozen = savedRun?.takeIf {
            it == cardioRun.value && it.planId == state.planId && it.dayId == state.viewDayId
        } ?: pendingCardioDistances.value.firstOrNull {
            it.planId == state.planId && it.dayId == state.viewDayId && it.drafts.isNotEmpty()
        }
        // Removing the source block must leave the frozen run and pending distances accessible.
        val sourceCard = state.cardio ?: frozen?.let {
            CardioCardState(activityLabel(it.block.activity), "", it.block.sets.any { spec -> spec.hard },
                block = it.block, finishesDay = it.finishesDay)
        }
        val base = sourceCard?.let { source ->
            source.copy(loggedSeconds = logged?.seconds, loggedSessionId = logged?.id, loggedSets = loggedSets,
                distanceRuns = distanceRuns().filter { it.dayId == state.viewDayId && it.planId == state.planId },
                canAddSet = (cardioRun.value == null || cardioRun.value?.dayId == state.viewDayId) &&
                    timedRepEvent.value?.awaitingCount != false,
                block = if (savedRun?.dayId != state.viewDayId && loggedSets.isNotEmpty()) {
                    source.block?.copy(sets = loggedSets.map { it.spec })
                } else source.block)
        }
        if (state.loading || savedRun == null || savedRun != cardioRun.value) return base
        val run = reconcileCardio() ?: return base
        maybeLogCardio()
        if (timedRepEvent.value?.awaitingCount != false) {
            val end = run.block.sets.getOrNull(run.position)?.end as? Measure.Time
            if (run.running && end != null && cardioScreenLive) {
                val remaining = end.seconds * 1_000L - run.elapsedMillis(cardioClock)
                val identity = cardioAlarmIdentity(run)
                cardioAlertAnchor = identity
                cardioAlarm.arm(cardioClock.elapsedRealtimeMillis() + remaining, identity) {
                    if (cardioRun.value?.let(::cardioAlarmIdentity) == identity) {
                        cardioAlertAnchor = null // The adapter already delivered feedback.
                        reconcileCardio()
                        cardioTick.value++
                    }
                }
            } else cardioAlarm.cancel()
        }
        if (run.dayId != state.viewDayId || base == null) return base
        val end = run.block.sets.getOrNull(run.position)?.end as? Measure.Time
        val elapsed = cardioElapsedSeconds(run)
        return base.copy(block = run.block, run = run,
            phase = if (run.running) CardioPhase.EXECUTING else CardioPhase.SUGGESTION,
            elapsedSeconds = elapsed,
            stepSecondsLeft = end?.let { ((it.seconds * 1_000L - run.elapsedMillis(cardioClock) + 999L) / 1_000L).toInt().coerceAtLeast(0) } ?: 0)
    }

    private fun derivedElapsedMillis(anchors: CardioAnchors): Long {
        val wallDelta = (cardioClock.wallMillis() - (anchors.wallMillis ?: return 0L)).coerceAtLeast(0L)
        val elapsedAnchor = anchors.elapsedMillis ?: return wallDelta
        // The boot counter decides exactly: same boot, the monotonic clock is
        // live and wall adjustments (NTP, manual) can never hijack elapsed;
        // different boot, the monotonic anchor is meaningless and wall time is
        // the only witness left.
        if (anchors.bootCount != cardioClock.bootCount()) return wallDelta
        return (cardioClock.elapsedRealtimeMillis() - elapsedAnchor).coerceAtLeast(0L)
    }

    private fun clearCardioExecution(keepDistanceSheet: Boolean = false) {
        cardioAlarm.cancel()
        cardioAlertAnchor = null
        savedState[KEY_CARDIO_RUN] = null
        if (!keepDistanceSheet && openSheet.value?.kind in listOf(OpenSheet.Kind.CARDIO_DISTANCE, OpenSheet.Kind.CARDIO_SETS)) {
            savedState[KEY_OPEN_SHEET] = null
        }
        cardioTick.value++
    }

    private fun buildCard(
        slot: ProgramSlot,
        logsByKey: Map<Pair<Long, String>, cloud.trotter.log.strength.data.LoggedSlot>,
        cfg: LifterConfig,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
        history: DayHistory,
    ): ExerciseCardState {
        val pe = slot.exercise
        val id = slot.programExerciseId
        val entry = catalog.find(pe.exerciseId)
        val name = entry?.name ?: pe.exerciseId
        val goalTarget = entry?.let { GoalCalculator.targetFor(it, cfg) }
            ?: GoalTarget.Weight(0.0, perHand = false)
        val tracking = pe.prescription?.tracking ?: entry?.tracking ?: TrackingType.WEIGHTED
        val timedShowsWeight = when (val target = pe.prescription?.target) {
            null -> (goalTarget as? GoalTarget.Time)?.let { it.addedLb > 0.0 } ?: false
            is PrescribedTarget.LoadReps -> true
            is PrescribedTarget.Hold -> target.addedLb > 0.0
            is PrescribedTarget.Reps, is PrescribedTarget.RepsInTime -> false
        }
        val main = logsByKey[id to Slot.MAIN]?.sets ?: emptyList()
        val partnerEntry = pe.superset?.let { catalog.find(it.exerciseId) }
        val partnerGoalTarget = partnerEntry?.let { GoalCalculator.targetFor(it, cfg) }
        val partnerTracking = partnerEntry?.tracking
        val partnerTimedShowsWeight = (partnerGoalTarget as? GoalTarget.Time)?.let { it.addedLb > 0.0 } ?: false
        val partnerSets = pe.superset?.let { logsByKey[id to Slot.SS]?.sets }

        val labels = DayScreenBuilder.kindLabels(main)
        var rampIndex = 0
        val rows = main.mapIndexed { i, s ->
            SetRowState(
                index = i,
                kindLabel = labels.getOrElse(i) { "" },
                kind = s.kind,
                rampIndex = if (s.kind == SetKind.RAMP) ++rampIndex else 0,
                isTop = s.kind == SetKind.TOP,
                weightDisplay = unit.fromLb(s.weightLb),
                reps = s.reps,
                seconds = s.seconds,
                done = s.done,
                partner = partnerSets?.getOrNull(i)?.let {
                    PartnerRowState(weightDisplay = unit.fromLb(it.weightLb), reps = it.reps, seconds = it.seconds)
                },
            )
        }
        val lastPerformed = history.lastPerformed[pe.exerciseId]
        return ExerciseCardState(
            programExerciseId = id,
            exerciseId = pe.exerciseId,
            timedRepDurationSeconds = (pe.prescription?.target as? PrescribedTarget.RepsInTime)?.seconds,
            timedRepAim = (pe.prescription?.target as? PrescribedTarget.RepsInTime)?.repAim,
            position = slot.position,
            title = name,
            partnerTitle = partnerEntry?.name ?: pe.superset?.exerciseId,
            equipment = entry?.equipment.orEmpty(),
            note = pe.note,
            isMain = pe.isMain,
            isSuperset = pe.superset != null,
            hasWarmupHint = pe.hasWarmupHint,
            headerTarget = DayScreenBuilder.headerTarget(pe, goalTarget, unit),
            perHand = entry?.perHand == true,
            tracking = tracking,
            timedShowsWeight = timedShowsWeight,
            partnerTracking = partnerTracking,
            partnerTimedShowsWeight = partnerTimedShowsWeight,
            lastTimeDisplay = DayScreenBuilder.lastTimeDisplay(lastPerformed, unit),
            personalRecordDisplay = DayScreenBuilder.personalRecordDisplay(history.personalRecords[pe.exerciseId], lastPerformed, unit),
            topSetComparison = DayScreenBuilder.topSetComparison(main, lastPerformed, unit),
            plateLine = DayScreenBuilder.plateLine(main, entry?.equipment.orEmpty(), unit),
            allDone = DayScreenBuilder.allDone(main),
            focusIndex = DayScreenBuilder.focusIndex(rows),
            focusLabel = DayScreenBuilder.focusLabel(main.map { it.kind }, DayScreenBuilder.focusIndex(rows)),
            summaryLine = DayScreenBuilder.summaryLine(main, partnerSets, tracking, partnerTracking ?: TrackingType.WEIGHTED, unit),
            nextSetDisplay = if (pe.prescription?.target is PrescribedTarget.RepsInTime) null
                else DayScreenBuilder.nextSetDisplay(rows, DayScreenBuilder.focusIndex(rows), tracking, unit),
            rows = rows,
            weightSwap = DayScreenBuilder.weightSwapAffordance(entry, catalog),
        )
    }

    private fun buildEditState(
        slots: List<ProgramSlot>,
        catalog: ExerciseCatalog,
        equipment: Set<Equipment>,
        origin: PlanOrigin,
    ): DayEditUiState {
        val rows = slots.mapIndexed { index, slot ->
            val pe = slot.exercise
            val entry = catalog.find(pe.exerciseId)
            val partnerId = pe.superset?.exerciseId
            DayEditSlotState(
                programExerciseId = slot.programExerciseId,
                position = slot.position,
                exerciseId = pe.exerciseId,
                title = entry?.name ?: pe.exerciseId,
                pattern = entry?.pattern,
                isSuperset = pe.superset != null,
                partnerExerciseId = partnerId,
                partnerTitle = partnerId?.let { catalog.find(it)?.name ?: it },
                canSetSuperset = origin != PlanOrigin.AUTHORED && pe.prescription == null,
                prescription = pe.prescription,
                keepToPlan = pe.keepToPlan,
                targetSets = pe.targetSets,
                canMoveUp = index > 0,
                canMoveDown = index < slots.lastIndex,
            )
        }
        return DayEditUiState(
            slots = rows,
            authored = origin == PlanOrigin.AUTHORED,
            canRemove = DayEditRules.canRemove(rows.size, origin),
            defaultEquipmentFilter = equipment,
            catalog = catalog,
        )
    }

    /** One day's worth of batched history reads (see [fetchDayHistory]): the A1
     *  "last time" chip and the performance-profile "Best" chip share the same
     *  fetch-once-per-exercise-id-set cadence, so they travel together. */
    private data class DayHistory(
        val lastPerformed: Map<String, LastPerformed>,
        val personalRecords: Map<String, PersonalRecord>,
    )

    private data class DayContext(
        val program: Program,
        val suggested: String?,
        val dayId: String?,
        val cfg: LifterConfig,
        val unit: WeightUnit,
        val catalog: ExerciseCatalog,
        val plan: PlanEntity? = null,
        val cardioPrefs: CardioPrefs? = null,
    )

    private data class CardioAnchors(
        val identity: String?,
        val wallMillis: Long?,
        val elapsedMillis: Long?,
        val bootCount: Int?,
    )

    private companion object {
        const val REST_TICK_MS = 250L
        const val REST_OVER_MS = 2_000L
        const val KEY_PRESCRIPTION_DRAFT = "day_prescription_draft"
        const val KEY_CARDIO_DRAFT = "day_cardio_block_draft"
        const val KEY_DAY_EDITOR_OPEN = "day_editor_open"
        const val KEY_VIEW_DAY = "day_view_override"
        const val KEY_VIEW_SELECTION = "day_view_selection"
        const val KEY_OPEN_SHEET = "day_open_sheet"
        const val KEY_SELECTED_PAGE = "selectedPageKey"
        const val KEY_TIMED_REP_EVENT = "day_timed_rep_event"
        const val KEY_CARDIO_RUN = "day_cardio_run"
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
