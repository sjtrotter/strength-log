package cloud.trotter.log.strength.ui.day

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cloud.trotter.log.strength.data.LastPerformed
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalFormatter
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Day screen ViewModel (spec §8.2, MVVM/UDF). Holds no working truth of its own:
 * every render value derives from Room/DataStore flows, and every mutation writes
 * straight back through [TrackerRepository]. Ephemeral UI state — the view-day
 * override, keep-screen-on, and the manual collapse overrides — lives in
 * [SavedStateHandle] per PLAN.md A6, so rotation and process death can't lose it;
 * collapse overrides still reset when a session advances (spec §8.2).
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
 * [toggleDone] also stamps the session-start time on the first done=true tick
 * (session-start capture) via [TrackerRepository.stampSessionStartIfUnset] —
 * ticking is performing, not planning, so weight/rep edits never stamp. The
 * watch delta path ([cloud.trotter.log.strength.sync.SetEditApplier])
 * shares the same repository helper so a watch-first workout stamps too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val sessionPublisher: SessionPublisher,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val viewDayOverride: StateFlow<String?> = savedState.getStateFlow(KEY_VIEW_DAY, null)
    private val manualCollapse: StateFlow<Map<Long, Boolean>> =
        savedState.getStateFlow(KEY_COLLAPSE, emptyMap())

    /** Serializes all read-modify-write log mutations (see class doc). */
    private val mutationLock = Mutex()

    /** The day actually shown: a valid override, else the suggested day, else the first. */
    private val effectiveDayId: StateFlow<String?> =
        combine(repo.programFlow, repo.suggestedDayFlow, viewDayOverride) { program, suggested, override ->
            resolveDay(program, suggested, override)
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val contextFlow =
        combine(
            repo.programFlow,
            repo.suggestedDayFlow,
            effectiveDayId,
            repo.keepScreenOnFlow,
            ::PartialContext,
        ).let { partial ->
            combine(partial, repo.configFlow, repo.unitFlow, repo.catalogFlow) { p, cfg, unit, catalog ->
                DayContext(p.program, p.suggested, p.dayId, cfg, unit, catalog, p.keepOn)
            }
        }

    val uiState: StateFlow<DayUiState> = contextFlow.flatMapLatest { ctx ->
        val dayId = ctx.dayId
        if (dayId == null) {
            flowOf(DayUiState(hasProgram = false, keepScreenOn = ctx.keepOn))
        } else {
            // Re-fetched only when the day's exercise ids change (a program edit),
            // not on every weight/rep keystroke — both history chips are prior
            // history, not live state, so neither needs log-level freshness.
            val slotsWithHistory = repo.daySlotsFlow(dayId).flatMapLatest { slots ->
                flow { emit(slots to fetchDayHistory(slots)) }
            }
            combine(slotsWithHistory, repo.logFlow(dayId), manualCollapse) { slotsAndHistory, logs, collapse ->
                val (slots, history) = slotsAndHistory
                buildState(ctx, dayId, slots, logs.associateBy { it.programExerciseId to it.slot }, collapse, history)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayUiState())

    /** Render model for the day-edit sheet (#11, spec §8.3) — a second view over
     *  the same day, kept out of [uiState] so the sheet never touches the
     *  exercise-card render path (see [DayEditUiState] doc). */
    val dayEditState: StateFlow<DayEditUiState> = effectiveDayId.flatMapLatest { dayId ->
        if (dayId == null) {
            flowOf(DayEditUiState())
        } else {
            combine(repo.daySlotsFlow(dayId), repo.catalogFlow, repo.wizardAnswersFlow) { slots, catalog, answers ->
                buildEditState(slots, catalog, answers.equipment)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayEditUiState())

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
     * The standing undo offers for removed sets (#124), oldest first — a
     * bounded LIFO stack, because each entry's index describes the track *as it
     * was when that row left it*. Only the last entry's index still means
     * anything against the current list, so only the last entry can be taken;
     * popping it makes the one beneath it current again.
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
        seedMissingLogs()
    }

    // --- user intents --------------------------------------------------------

    fun selectDay(dayId: String) {
        closeUndoWindow()
        savedState[KEY_VIEW_DAY] = dayId
    }

    fun changeWeight(programExerciseId: Long, slot: String, index: Int, newDisplayWeight: Double) {
        val day = currentDay() ?: return
        mutate {
            val unit = repo.unitFlow.first()
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            val updated = SetEditor.editWeight(sets, index, unit.toLb(newDisplayWeight))
            repo.updateSets(day, programExerciseId, slot, updated)
        }
    }

    fun changeReps(programExerciseId: Long, slot: String, index: Int, newReps: Int) {
        val day = currentDay() ?: return
        mutate {
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            repo.updateSets(day, programExerciseId, slot, SetEditor.editReps(sets, index, newReps))
        }
    }

    /** TIMED tracks' seconds edit — same write-immediately shape as
     *  [changeReps]/[changeWeight], routed through [SetEditor.editSeconds]
     *  (never cascades, exactly like reps). */
    fun changeSeconds(programExerciseId: Long, slot: String, index: Int, newSeconds: Int) {
        val day = currentDay() ?: return
        mutate {
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            repo.updateSets(day, programExerciseId, slot, SetEditor.editSeconds(sets, index, newSeconds))
        }
    }

    fun toggleDone(programExerciseId: Long, index: Int, checked: Boolean, isSuperset: Boolean) {
        val day = currentDay() ?: return
        mutate {
            // A tick is performing, not planning (weight/rep edits don't stamp) —
            // only the FIRST done=true tick since the last advance/clear starts
            // the session clock (session-start capture).
            if (checked) repo.stampSessionStartIfUnset()
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            // A missing partner track (never seeded) must stay missing — writing an
            // empty SS row would mark it as seeded forever. Same rule as addSet.
            val partner = if (isSuperset) {
                trackFor(day, programExerciseId, Slot.SS).takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner, index, checked)
            if (newPartner != null) {
                repo.updateSetsPaired(day, programExerciseId, newMain, newPartner)
            } else {
                repo.updateSets(day, programExerciseId, Slot.MAIN, newMain)
            }
        }
    }

    fun addSet(programExerciseId: Long, isSuperset: Boolean) {
        val day = currentDay() ?: return
        mutate {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            // An unseeded track has no last row to copy — nothing to add yet.
            if (main.isEmpty()) return@mutate
            val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS) else emptyList()
            if (partner.isNotEmpty()) {
                val (newMain, newPartner) = SetEditor.addSetPaired(main, partner)
                repo.updateSetsPaired(day, programExerciseId, newMain, newPartner)
            } else {
                // No partner track exists (e.g. its catalog entry is unknown, so it
                // never seeded) — there is nothing to keep aligned; plain add.
                repo.updateSets(day, programExerciseId, Slot.MAIN, SetEditor.addSet(main))
            }
        }
    }

    /** The × on a set row. Low-friction by design (mid-workout, high frequency),
     *  so it stays one tap and offers [undoRemoveSet] afterwards instead of
     *  asking first — see [_removedSet]. */
    fun removeSet(programExerciseId: Long, index: Int, isSuperset: Boolean) {
        val day = currentDay() ?: return
        mutate {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            if (index !in main.indices) return@mutate
            // SetEditor's floor: the last row never goes. Nothing was removed,
            // so nothing is offered back.
            if (main.size <= 1) return@mutate
            val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS) else emptyList()
            // Offered before the write lands: removing the last unfinished row
            // finishes the card, and the screen has to know the offer is open
            // before it decides whether to fold that card away.
            pushUndo(
                RemovedSet(
                    programExerciseId = programExerciseId,
                    index = index,
                    dayId = day,
                    main = main[index],
                    partner = partner.getOrNull(index),
                ),
            )
            if (partner.isNotEmpty()) {
                val (newMain, newPartner) = SetEditor.removeSetPaired(main, partner, index)
                repo.updateSetsPaired(day, programExerciseId, newMain, newPartner)
            } else {
                repo.updateSets(day, programExerciseId, Slot.MAIN, SetEditor.removeSet(main, index))
            }
        }
    }

    /** Takes the newest offer: puts that row back where it was, kind and tick
     *  included, and makes the offer beneath it current. A no-op once the window
     *  has closed, so a stale tap can't resurrect anything. */
    fun undoRemoveSet() {
        val removed = _removedSets.value.lastOrNull() ?: return
        popUndo()
        mutate {
            val day = currentDay() ?: return@mutate
            if (day != removed.dayId) return@mutate
            val main = trackFor(day, removed.programExerciseId, Slot.MAIN)
            val partner = trackFor(day, removed.programExerciseId, Slot.SS)
            if (removed.partner != null && partner.isNotEmpty()) {
                val (newMain, newPartner) =
                    SetEditor.insertSetPaired(main, partner, removed.index, removed.main, removed.partner)
                repo.updateSetsPaired(day, removed.programExerciseId, newMain, newPartner)
            } else {
                val restored = SetEditor.insertSet(main, removed.index, removed.main)
                repo.updateSets(day, removed.programExerciseId, Slot.MAIN, restored)
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

    fun toggleCollapse(programExerciseId: Long) {
        val card = uiState.value.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return
        savedState[KEY_COLLAPSE] = manualCollapse.value + (programExerciseId to !card.collapsed)
    }

    /** Writes the keep-screen-on preference (#125). It lives in DataStore, not
     *  [SavedStateHandle], so it outlives the process the way every other thing
     *  the user chose does — and the activity, not this screen, applies it. */
    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { repo.setKeepScreenOn(on) }
    }

    fun clearChecks() {
        val day = currentDay() ?: return
        // Under the lock: a mutation that read its track before the clear must not
        // write stale done-flags back after it.
        mutate { repo.clearChecks(day) }
    }

    /** DONE — advance: record the session (A1), reset collapse, follow the new
     *  suggested day, then hand the just-written session to Health Connect
     *  (#17/D7 trigger point). The publish is fired after `advanceDay` returns
     *  and never blocks or fails the completion — [SessionPublisher] swallows
     *  every failure path, and the no-op binding covers devices without HC.
     *
     *  The all-time top-set highs are read *before* the advance appends to
     *  history; comparing them with what the session actually recorded is the
     *  whole cascade-ceremony trigger (journal brief §2). Nothing about the
     *  ceremony is stored, so it can only ever fire on this transition. */
    fun completeDay() {
        val day = currentDay() ?: return
        closeUndoWindow()
        mutate {
            val previousHighs = CascadeCeremonyBuilder.allTimeHighs(repo.topSetHistoryFlow.first())
            val dayIndex = repo.programFlow.first().days.indexOfFirst { it.id == day }
            val sessionId = repo.advanceDay(day)
            savedState[KEY_COLLAPSE] = emptyMap<Long, Boolean>()
            savedState[KEY_VIEW_DAY] = null
            _cascadeCeremony.value = CascadeCeremonyBuilder.from(
                previousHighs = previousHighs,
                sessionSets = repo.sessionSets(sessionId),
                dayIndex = dayIndex.coerceAtLeast(0),
                unit = repo.unitFlow.first(),
            )
            viewModelScope.launch { sessionPublisher.publish(sessionId) }
        }
    }

    /** Dismisses the scrim (tap anywhere, or back). */
    fun dismissCascadeCeremony() {
        _cascadeCeremony.value = null
    }

    // --- day-edit sheet intents (#11, spec §8.3) ------------------------------

    /** Confirming a swap in the substitution picker. The old log stays keyed to
     *  the slot's [position]; the repository clears it so the new exercise
     *  seeds fresh from its own GOAL (D4 — seeding happens on next observation,
     *  never here). Also clears any manual collapse override held on the slot's
     *  stable id, so the swapped-in exercise starts in pure auto-collapse (#95). */
    fun swapDaySlot(position: Int, newExerciseId: String) {
        val day = currentDay() ?: return
        mutate {
            val slotId = repo.daySlotsFlow(day).first()
                .firstOrNull { it.position == position }?.programExerciseId
            repo.swapExercise(day, position, newExerciseId)
            if (slotId != null) savedState[KEY_COLLAPSE] = manualCollapse.value - slotId
        }
    }

    /** Appends a new slot for the picked exercise (spec §8.3 add flow). Mirrors
     *  `ProgramGenerator`'s plain-accessory shape (3 sets, "8–12") — a sane
     *  default for a manually added exercise, not a re-seed of pinned math. */
    fun addDaySlot(exerciseId: String) {
        val day = currentDay() ?: return
        mutate { repo.addExercise(day, ProgramExercise(exerciseId = exerciseId, repSchemeLabel = "8–12")) }
    }

    /** Removes a slot, enforcing the minimum-3-exercises-per-day floor (spec
     *  §8.3) against a fresh read so the guard can't race a concurrent add. */
    fun removeDaySlot(position: Int) {
        val day = currentDay() ?: return
        mutate {
            val slots = repo.daySlotsFlow(day).first()
            if (!DayEditRules.canRemove(slots.size)) return@mutate
            repo.removeExercise(day, position)
        }
    }

    /** Picking a superset partner for a slot (#93) — the same intent whether the
     *  slot had no partner or is swapping the one it had. The repository clears
     *  only the SS track so the new partner seeds fresh from its own GOAL on the
     *  next observation while the main track's history stays put. */
    fun setDaySlotSuperset(position: Int, partnerExerciseId: String) {
        val day = currentDay() ?: return
        mutate { repo.setSupersetPartner(day, position, partnerExerciseId) }
    }

    /** Breaking a superset back into a plain slot (#93): the partner and its SS
     *  track go, the main track stays. */
    fun removeDaySlotSuperset(position: Int) {
        val day = currentDay() ?: return
        mutate { repo.removeSupersetPartner(day, position) }
    }

    /** The reset-day-to-template escape hatch (spec §8.3): regenerates this one
     *  day from the stored wizard answers; other days are untouched. */
    fun resetDayToTemplate() {
        val day = currentDay() ?: return
        mutate { repo.resetDayToTemplate(day) }
    }

    // --- seeding & bootstrap -------------------------------------------------

    /** Seeds each day's logs from GOAL exactly once (M2 rule: seeding is the VM's job). */
    private fun seedMissingLogs() {
        viewModelScope.launch {
            effectiveDayId
                .flatMapLatest { day ->
                    if (day == null) flowOf(null) else repo.daySlotsFlow(day).map { day to it }
                }
                .distinctUntilChanged()
                .collect { pair -> pair?.let { (day, slots) -> ensureSeeded(day, slots) } }
        }
    }

    private suspend fun ensureSeeded(dayId: String, slots: List<ProgramSlot>) = mutationLock.withLock {
        val existing = repo.logFlow(dayId).first()
            .associate { (it.programExerciseId to it.slot) to it.sets.size }
        val cfg = repo.configFlow.first()
        val catalog = repo.catalogFlow.first()
        DayScreenBuilder.seedPlan(slots, existing, cfg, catalog).forEach { write ->
            repo.updateSets(dayId, write.programExerciseId, write.slot, write.sets)
        }
    }

    // --- helpers -------------------------------------------------------------

    /** Launches a log mutation with its read-modify-write held under [mutationLock]. */
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch { mutationLock.withLock { block() } }
    }

    private fun currentDay(): String? = effectiveDayId.value

    private suspend fun trackFor(dayId: String, programExerciseId: Long, slot: String): List<LoggedSet> =
        repo.logFlow(dayId).first()
            .firstOrNull { it.programExerciseId == programExerciseId && it.slot == slot }
            ?.sets ?: emptyList()

    private fun resolveDay(program: Program, suggested: String?, override: String?): String? {
        val ids = program.days.map { it.id }
        return override?.takeIf { it in ids } ?: suggested?.takeIf { it in ids } ?: ids.firstOrNull()
    }

    /** One batched read for the whole day's "last time" chips (#14 A1 bonus)
     *  AND its "Best" profile chips (performance-profile.md Phase 1) — never
     *  one query per exercise for either. */
    private suspend fun fetchDayHistory(slots: List<ProgramSlot>): DayHistory {
        val ids = slots.map { it.exercise.exerciseId }.distinct()
        return DayHistory(repo.lastPerformed(ids), repo.personalRecords(ids))
    }

    private fun buildState(
        ctx: DayContext,
        dayId: String,
        slots: List<ProgramSlot>,
        logsByKey: Map<Pair<Long, String>, cloud.trotter.log.strength.data.LoggedSlot>,
        collapse: Map<Long, Boolean>,
        history: DayHistory,
    ): DayUiState {
        val program = ctx.program
        val day = program.days.firstOrNull { it.id == dayId }
            ?: return DayUiState(hasProgram = false, keepScreenOn = ctx.keepOn)
        val dayIndex = program.days.indexOfFirst { it.id == dayId }
        return DayUiState(
            hasProgram = true,
            tabs = program.days.mapIndexed { i, d ->
                DayTab(d.id, i, isSuggested = d.id == ctx.suggested, isSelected = d.id == dayId)
            },
            viewDayId = dayId,
            dayIndex = dayIndex,
            dayTitle = day.title,
            emphasisLine = day.emphasisLine,
            unit = ctx.unit,
            suggestedDayId = ctx.suggested,
            nextDayId = Rotation.next(program, dayId),
            exercises = slots.map { buildCard(it, logsByKey, ctx.cfg, ctx.unit, ctx.catalog, collapse, history) },
            cardio = day.cardio,
            keepScreenOn = ctx.keepOn,
        )
    }

    private fun buildCard(
        slot: ProgramSlot,
        logsByKey: Map<Pair<Long, String>, cloud.trotter.log.strength.data.LoggedSlot>,
        cfg: LifterConfig,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
        collapse: Map<Long, Boolean>,
        history: DayHistory,
    ): ExerciseCardState {
        val pe = slot.exercise
        val id = slot.programExerciseId
        val entry = catalog.find(pe.exerciseId)
        val name = entry?.name ?: pe.exerciseId
        val goalTarget = entry?.let { GoalCalculator.targetFor(it, cfg) }
            ?: GoalTarget.Weight(0.0, perHand = false)
        val tracking = entry?.tracking ?: TrackingType.WEIGHTED
        val timedShowsWeight = (goalTarget as? GoalTarget.Time)?.let { it.addedLb > 0.0 } ?: false
        val main = logsByKey[id to Slot.MAIN]?.sets ?: emptyList()
        val partnerEntry = pe.superset?.let { catalog.find(it.exerciseId) }
        val partnerGoalTarget = partnerEntry?.let { GoalCalculator.targetFor(it, cfg) }
        val partnerTracking = partnerEntry?.tracking
        val partnerTimedShowsWeight = (partnerGoalTarget as? GoalTarget.Time)?.let { it.addedLb > 0.0 } ?: false
        val partnerSets = pe.superset?.let { logsByKey[id to Slot.SS]?.sets }

        val labels = DayScreenBuilder.kindLabels(main)
        val rows = main.mapIndexed { i, s ->
            SetRowState(
                index = i,
                kindLabel = labels.getOrElse(i) { "" },
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
        val goalDisplay = GoalFormatter.label(goalTarget, unit)
        val lastPerformed = history.lastPerformed[pe.exerciseId]
        return ExerciseCardState(
            programExerciseId = id,
            position = slot.position,
            title = if (pe.superset != null) {
                "SS: $name + ${partnerEntry?.name ?: pe.superset!!.exerciseId}"
            } else {
                name
            },
            isMain = pe.isMain,
            isSuperset = pe.superset != null,
            hasWarmupHint = pe.hasWarmupHint,
            goalDisplay = goalDisplay,
            perHand = entry?.perHand == true,
            tracking = tracking,
            timedShowsWeight = timedShowsWeight,
            partnerTracking = partnerTracking,
            partnerTimedShowsWeight = partnerTimedShowsWeight,
            lastTimeDisplay = DayScreenBuilder.lastTimeDisplay(lastPerformed, unit),
            personalRecordDisplay = DayScreenBuilder.personalRecordDisplay(history.personalRecords[pe.exerciseId], lastPerformed, unit),
            plateLine = DayScreenBuilder.plateLine(main, entry?.equipment.orEmpty(), unit),
            allDone = DayScreenBuilder.allDone(main),
            collapsed = DayScreenBuilder.collapsed(main, collapse[id]),
            collapsedSummary = DayScreenBuilder.collapsedSummary(
                main, partnerSets, goalDisplay, unit,
                tracking = tracking, partnerTracking = partnerTracking ?: TrackingType.WEIGHTED,
            ),
            rows = rows,
            weightSwap = DayScreenBuilder.weightSwapAffordance(entry, catalog),
        )
    }

    private fun buildEditState(
        slots: List<ProgramSlot>,
        catalog: ExerciseCatalog,
        equipment: Set<Equipment>,
    ): DayEditUiState {
        val rows = slots.map { slot ->
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
            )
        }
        return DayEditUiState(
            slots = rows,
            canRemove = DayEditRules.canRemove(rows.size),
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

    private data class PartialContext(
        val program: Program,
        val suggested: String?,
        val dayId: String?,
        val keepOn: Boolean,
    )

    private data class DayContext(
        val program: Program,
        val suggested: String?,
        val dayId: String?,
        val cfg: LifterConfig,
        val unit: WeightUnit,
        val catalog: ExerciseCatalog,
        val keepOn: Boolean,
    )

    private companion object {
        const val KEY_VIEW_DAY = "day_view_override"
        const val KEY_COLLAPSE = "day_collapse_overrides"
        const val STOP_TIMEOUT_MS = 5_000L

        /** How long a removed set stays undoable (#124). Long enough to notice
         *  the row vanish and reach for it, short enough that the offer is gone
         *  before the next set is logged. */
        const val UNDO_WINDOW_MS = 5_000L

        /** How many removals one shared window will hold. Five seconds is not
         *  long enough to deliberately stack more than a couple, so this is a
         *  ceiling on a runaway finger, not a feature. */
        const val UNDO_STACK_LIMIT = 5
    }
}
