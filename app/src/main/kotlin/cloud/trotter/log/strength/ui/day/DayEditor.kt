package cloud.trotter.log.strength.ui.day

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.RuleEdit
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.data.DayTracks
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.model.CardioProgress
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
internal class DayEditor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
    scope: CoroutineScope,
    context: Flow<Context>,
    unitFlow: Flow<WeightUnit>,
    private val dayState: StateFlow<DayUiState>,
    private val currentDay: () -> String?,
    private val mutate: (suspend () -> Unit) -> Unit,
    private val mutationLock: Mutex,
    private val currentPendingPlanSwap: () -> PendingPlanSwap?,
    private val clearLogUndo: suspend () -> Unit,
    private val dismissSwapUndo: () -> Unit,
    otherWorkoutOpenMessage: Flow<Boolean>,
    private val plans: PlanRepository,
    private val catalog: CatalogRepository,
    restSettingsFlow: Flow<RestSettings>,
) {
    private val optionsSlotId = savedState.getStateFlow<Long?>(KEY_OPTIONS_SLOT, null)
    val pendingPlanSwap = savedState.getStateFlow<PendingPlanSwap?>(KEY_PENDING_PLAN_SWAP, null)

    /** Render model for the day-edit sheet (spec §8.3) — a second view over the
     *  same day, kept out of the workout state so the sheet never touches the
     *  exercise-card render path (see [DayEditUiState] doc). */
    private val editProgramState = context.flatMapLatest { ctx ->
        val dayId = ctx.dayId
        val plan = ctx.plan
        if (dayId == null || plan == null) flowOf(DayEditUiState()) else combine(
            plans.slotsFor(plan.id, dayId), plans.wizardAnswersFor(plan.id),
            // Set ticks and edits re-emit the tracks; only the run and performed identities matter here.
            repo.dayTracksFlow(plan.id, dayId).map { tracks ->
                Pair(tracks.runOpen, tracks.tracks
                    .mapNotNull { track -> track.exerciseId?.let { (track.programExerciseId to track.slot) to it } }.toMap())
            }.distinctUntilChanged(),
            repo.observePlanRows(plan.id, dayId),
            combine(unitFlow, restSettingsFlow, ::Pair),
        ) { slots, answers, (runOpen, performed), rows, (unit, rest) ->
            val day = ctx.program.days.firstOrNull { it.id == dayId }
            val block = day?.cardioBlock
            buildEditState(slots, ctx.catalog, answers.equipment, rows, plan, unit, rest).copy(
                planId = plan.id, dayId = dayId, cardioBlock = block,
                runOpen = runOpen,
                cardioProgress = day?.cardioProgress ?: CardioProgress(),
                cardioMeasures = block?.let(CardioMeasures::available).orEmpty(),
                performedExerciseIds = performed.filterKeys { it.second == Slot.MAIN }.mapKeys { it.key.first },
                performedPartnerIds = performed.filterKeys { it.second == Slot.SS }.mapKeys { it.key.first },
                canRemove = DayEditRules.canRemove(slots.size, block != null),
                canResetToTemplate = plan.origin == PlanOrigin.GENERATED.name &&
                    day != null && ProgramGenerator.templateFor(answers, day) != null,
            )
        }
    }
    private val planEditing = savedState.get<Boolean>(Routes.DAY_EDITOR_ARG) == true
    private var prescriptionRequest: Any? = null
    private val prescriptionDraft = savedState.getStateFlow<PrescriptionDraft?>(KEY_PRESCRIPTION_DRAFT, null)
    private val prescriptionStructure = savedState.getStateFlow<String?>(KEY_PRESCRIPTION_STRUCTURE, null)
    private val dayEditorOpen = savedState.getStateFlow(
        KEY_DAY_EDITOR_OPEN, planEditing,
    )
    private val cardioDraft = savedState.getStateFlow<CardioBlockDraft?>(KEY_CARDIO_DRAFT, null)
    private val prescriptionEditState = combine(
        editProgramState, combine(prescriptionDraft, prescriptionStructure, ::Pair), dayEditorOpen,
    ) { state, (draft, structure), editorOpen ->
        val shownDraft = draft?.takeIf { restored ->
            state.slots.any { it.programExerciseId == restored.slotId }
        }
        state.copy(
            editorOpen = editorOpen, planEditing = planEditing,
            prescriptionDraft = shownDraft,
            prescriptionStructure = structure?.takeIf { shownDraft != null }?.let { name ->
                SetStructure.entries.firstOrNull { it.name == name }
            },
        )
    }
    val dayEditState: StateFlow<DayEditUiState> = combine(
        prescriptionEditState, cardioDraft, otherWorkoutOpenMessage, optionsSlotId,
    ) { state, draft, message, options ->
        state.copy(optionsSlotId = options, cardioDraft = draft?.takeIf {
            it.planId == state.planId && it.dayId == state.dayId
        }, otherWorkoutOpenMessage = message)
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayEditUiState(planEditing = planEditing))

    init {
        scope.launch {
            combine(dayState, pendingPlanSwap) { state, pending ->
                pending?.takeIf {
                    !state.loading && state.planId == it.planId && state.viewDayId == it.dayId
                }
            }.collect { pending ->
                if (pending != null) mutationLock.withLock { reconcilePlanSwap(pending) }
            }
        }
    }

    // --- day-edit sheet intents (spec §8.3) ------------------------------

    fun setDayEditorOpen(open: Boolean) {
        savedState[KEY_DAY_EDITOR_OPEN] = open
        if (!open) {
            savedState[KEY_OPTIONS_SLOT] = null
            discardPrescription()
            savedState.remove<CardioSetDraft>(KEY_CARDIO_SET_BEFORE_EDIT)
            savedState[KEY_CARDIO_DRAFT] = null
        }
    }

    fun setCardioKeepToPlan(keep: Boolean) {
        val state = dayEditState.value
        val planId = state.planId ?: return
        val dayId = state.dayId ?: return
        mutate { repo.setCardioKeepToPlan(planId, dayId, keep) }
    }

    fun setCardioProgressBy(measure: ProgressMeasure) {
        val state = dayEditState.value
        val planId = state.planId ?: return
        val dayId = state.dayId ?: return
        if (measure !in state.cardioMeasures) return
        mutate { repo.setCardioProgression(planId, dayId, measure, CardioMeasures.defaultPercent(measure)) }
    }

    fun setCardioIncrease(percent: Int) {
        val state = dayEditState.value
        val planId = state.planId ?: return
        val dayId = state.dayId ?: return
        val block = state.cardioBlock ?: return
        val measure = CardioMeasures.resolve(block, state.cardioProgress.measure) ?: return
        mutate { repo.setCardioProgression(planId, dayId, measure, percent) }
    }

    fun openCardioBlock() {
        val state = dayEditState.value
        val plan = state.planId ?: return
        val day = state.dayId ?: return
        savedState.remove<CardioSetDraft>(KEY_CARDIO_SET_BEFORE_EDIT)
        savedState[KEY_CARDIO_DRAFT] = state.cardioBlock?.let { CardioBlockDraft.from(plan, day, it) }
            ?: CardioBlockDraft.new(plan, day)
    }

    fun changeCardioDraft(draft: CardioBlockDraft) {
        val current = cardioDraft.value ?: return
        if (draft.planId != current.planId || draft.dayId != current.dayId) return
        draft.block() // Validate before keeping a draft that a later Save will commit.
        require(draft.editingSet == null || draft.editingSet in draft.sets.indices)
        if (current.editingSet == null && draft.editingSet != null) {
            savedState[KEY_CARDIO_SET_BEFORE_EDIT] = draft.sets[draft.editingSet]
        }
        savedState[KEY_CARDIO_DRAFT] = draft
    }

    fun backCardioBlock() {
        val draft = cardioDraft.value ?: return
        val editing = draft.editingSet
        val before = savedState.get<CardioSetDraft>(KEY_CARDIO_SET_BEFORE_EDIT)
        savedState.remove<CardioSetDraft>(KEY_CARDIO_SET_BEFORE_EDIT)
        savedState[KEY_CARDIO_DRAFT] = when {
            editing == null -> null
            before == null -> draft.copy(editingSet = null)
            else -> draft.copy(editingSet = null, sets = draft.sets.mapIndexed { index, set -> if (index == editing) before else set })
        }
    }

    /** Both set-page Save and block-page Save commit immediately to the owning day. */
    fun saveCardioBlock() {
        val draft = cardioDraft.value ?: return
        mutate {
            repo.setCardioBlock(draft.planId, draft.dayId, draft.block())
            if (cardioDraft.value == draft) {
                savedState.remove<CardioSetDraft>(KEY_CARDIO_SET_BEFORE_EDIT)
                savedState[KEY_CARDIO_DRAFT] = if (draft.editingSet != null) draft.copy(editingSet = null) else null
            }
        }
    }

    fun removeCardioBlock() {
        val state = dayEditState.value
        if (state.slots.isEmpty()) return
        val plan = state.planId ?: return
        val day = state.dayId ?: return
        mutate { repo.setCardioBlock(plan, day, null) }
    }

    fun openPrescription(slotId: Long) {
        val state = dayEditState.value
        val slot = state.slots.firstOrNull { it.programExerciseId == slotId } ?: return
        prescriptionRequest = null
        savedState[KEY_PRESCRIPTION_STRUCTURE] = null
        savedState[KEY_PRESCRIPTION_DRAFT] = PrescriptionDraft.from(slot, state.catalog)
    }

    fun openStraightSets(slotId: Long) {
        val state = dayEditState.value
        val slot = state.slots.firstOrNull { it.programExerciseId == slotId } ?: return
        val plan = state.planId ?: return
        val day = state.dayId ?: return
        val request = Any()
        prescriptionRequest = request
        mutate {
            val tracks = repo.dayTracksFlow(plan, day).first()
            if (prescriptionRequest !== request) return@mutate
            val working = tracks.tracks.firstOrNull {
                it.programExerciseId == slotId && it.slot == Slot.MAIN && it.exerciseId == slot.exerciseId
            }?.sets ?: tracks.retired.firstOrNull {
                it.programExerciseId == slotId && it.slot == Slot.MAIN && it.exerciseId == slot.exerciseId
            }?.sets
            savedState[KEY_PRESCRIPTION_DRAFT] = PrescriptionDraft.straightFrom(slot, working, state.catalog)
            savedState[KEY_PRESCRIPTION_STRUCTURE] = SetStructure.STRAIGHT.name
        }
    }

    fun changePrescriptionDraft(draft: PrescriptionDraft) {
        val current = prescriptionDraft.value ?: return
        if (current.slotId != draft.slotId) return
        savedState[KEY_PRESCRIPTION_DRAFT] = draft.copy(resistance = draft.resistance.take(40))
    }

    fun discardPrescription() {
        prescriptionRequest = null
        savedState[KEY_PRESCRIPTION_DRAFT] = null
        savedState[KEY_PRESCRIPTION_STRUCTURE] = null
    }

    fun savePrescription() {
        val draft = prescriptionDraft.value ?: return
        val structure = savedState.get<String>(KEY_PRESCRIPTION_STRUCTURE)?.let { name ->
            SetStructure.entries.firstOrNull { it.name == name }
        }
        mutate {
            repo.setPrescription(draft.slotId, draft.prescription(), draft.sets, structure)
            discardPrescription()
        }
    }

    fun moveDaySlot(slotId: Long, up: Boolean) { mutate { repo.moveExercise(slotId, up) } }

    /** Swaps the slot's exercise while the run keeps done work.
     * The new exercise seeds from its own goal on the next observation. */
    fun swapDaySlot(position: Int, newExerciseId: String) {
        requestPlanSwap(position, newExerciseId, Slot.MAIN)
    }

    private fun requestPlanSwap(position: Int, exerciseId: String, half: String) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            val slot = plans.slotsFor(planId, day).first().firstOrNull { it.position == position } ?: return@mutate

            val tracks = repo.dayTracksFlow(planId, day).first()
            val doneCount = planSwapDoneCount(slot, tracks, half, exerciseId)
            val oldId = if (half == Slot.SS) slot.exercise.superset?.exerciseId else slot.exercise.exerciseId
            if (doneCount > 0) {
                savedState[KEY_PENDING_PLAN_SWAP] = PendingPlanSwap(
                    day, planId, slot.programExerciseId, half, exerciseId,
                    oldId?.let { catalog.catalogFlow.first().find(it)?.name ?: it }.orEmpty(), doneCount,
                )
            } else applyPlanSwap(day, slot.programExerciseId, half, exerciseId)
        }
    }

    private suspend fun applyPlanSwap(day: String, id: Long, half: String, exerciseId: String) {
        if (half == Slot.SS) repo.setSupersetPartner(day, id, exerciseId)
        else repo.swapExerciseById(day, id, exerciseId)
        clearLogUndo()
        dismissSwapUndo()
    }

    fun dismissPlanSwap() { savedState[KEY_PENDING_PLAN_SWAP] = null }

    private fun planSwapDoneCount(
        slot: ProgramSlot, tracks: DayTracks, half: String, exerciseId: String,
    ): Int {
        if (tracks.runOpen) return 0
        val replacesMain = half == Slot.MAIN && slot.exercise.exerciseId != exerciseId
        if (half == Slot.MAIN && !replacesMain) return 0
        return tracks.tracks.filter {
            it.programExerciseId == slot.programExerciseId && (replacesMain || it.slot == Slot.SS)
        }.sumOf { it.sets.count { set -> set.done } }
    }

    private suspend fun reconcilePlanSwap(pending: PendingPlanSwap, confirmed: Boolean = false) {
        if (pendingPlanSwap.value != pending || dayState.value.planId != pending.planId ||
            currentDay() != pending.dayId) return
        val slot = plans.slotsFor(pending.planId, pending.dayId).first().firstOrNull {
            it.programExerciseId == pending.programExerciseId
        }
        if (slot == null) {
            dismissPlanSwap()
            return
        }
        val tracks = repo.dayTracksFlow(pending.planId, pending.dayId).first()
        val doneCount = planSwapDoneCount(slot, tracks, pending.slot, pending.exerciseId)
        if (doneCount > 0) {
            if (doneCount != pending.doneCount) {
                savedState[KEY_PENDING_PLAN_SWAP] = pending.copy(doneCount = doneCount)
                return
            }
            if (!confirmed) return
        }
        dismissPlanSwap()
        applyPlanSwap(pending.dayId, pending.programExerciseId, pending.slot, pending.exerciseId)
    }

    fun confirmPlanSwap() {
        val pending = currentPendingPlanSwap() ?: return
        mutate { reconcilePlanSwap(pending, confirmed = true) }
    }

    fun addDaySlot(exerciseId: String) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            repo.addExercise(day, ProgramExercise(exerciseId), targetPlanId = planId)
        }
    }

    /** Removes a slot; the repository enforces its owning day's floor atomically. */
    fun removeDaySlot(position: Int) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            val slots = plans.slotsFor(planId, day).first()
            val slot = slots.firstOrNull { it.position == position } ?: return@mutate
            repo.removeExercise(slot.programExerciseId)
        }
    }

    /** Changes the superset partner while the run keeps done work and the main track.
     * The new partner seeds on the next observation, aligned to the main track. */
    fun setDaySlotSuperset(position: Int, partnerExerciseId: String) {
        requestPlanSwap(position, partnerExerciseId, Slot.SS)
    }

    fun setDaySlotKeepToPlan(slotId: Long, keep: Boolean) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            repo.setKeepToPlan(slotId, keep)
            if (keep) repo.ensureSeeded(planId, day)
        }
    }

    fun openOptions(slotId: Long) {
        savedState[KEY_OPTIONS_SLOT] = slotId
        setDayEditorOpen(true)
    }

    fun setNextTime(slotId: Long, mode: NextTimeMode) {
        mutate {
            repo.setKeepToPlan(slotId, false)
            repo.editRule(slotId, Slot.MAIN, RuleEdit.NextTime(mode))
            repo.editRule(slotId, Slot.SS, RuleEdit.NextTime(mode))
        }
    }

    fun editRule(slotId: Long, half: String, edit: RuleEdit) {
        mutate { repo.editRule(slotId, half, edit) }
    }

    fun setDaySlotStructure(slotId: Long, structure: SetStructure) {
        mutate { repo.setStructure(slotId, structure) }
    }

    fun resetDaySlotToPlan(slotId: Long) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            if (repo.resetSlotToPlan(slotId)) {
                repo.ensureSeeded(planId, day)
            }
        }
    }

    /** Breaking a superset back into a plain slot: the partner and its SS track
     *  go, the main track stays. */
    fun removeDaySlotSuperset(position: Int) {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate {
            val slot = plans.slotsFor(planId, day).first().firstOrNull { it.position == position } ?: return@mutate
            repo.removeSupersetPartner(day, slot.programExerciseId)
        }
    }

    /** The reset-day-to-template escape hatch (spec §8.3): regenerates this one
     *  day from the stored wizard answers; other days are untouched. */
    fun resetDayToTemplate() {
        val day = currentDay() ?: return
        val planId = dayState.value.takeIf { it.viewDayId == day }?.planId ?: return
        mutate { repo.resetDayToTemplate(day, targetPlanId = planId) }
    }

    private fun buildEditState(
        slots: List<ProgramSlot>,
        catalog: ExerciseCatalog,
        equipment: Set<Equipment>,
        rows: Map<Long, List<LoggedSet>>,
        plan: PlanEntity,
        unit: WeightUnit,
        rest: RestSettings,
    ): DayEditUiState {
        val slotStates = slots.mapIndexed { index, slot ->
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
                prescription = pe.prescription,
                keepToPlan = pe.keepToPlan,
                nextTime = pe.rule?.nextTime ?: if (plan.autoApply) NextTimeMode.AUTO else NextTimeMode.ASK,
                lifts = listOfNotNull(
                    liftOptionsState(Slot.MAIN, entry?.name ?: pe.exerciseId, pe.rule,
                        pe.prescription?.tracking ?: entry?.tracking ?: TrackingType.WEIGHTED,
                        entry?.equipment.orEmpty(), plan, unit, rest),
                    partnerId?.let { id ->
                        val partner = catalog.find(id)
                        liftOptionsState(Slot.SS, partner?.name ?: id, pe.superset?.rule,
                            partner?.tracking ?: TrackingType.WEIGHTED, partner?.equipment.orEmpty(), plan, unit, rest)
                    },
                ),
                structure = pe.structure,
                weightedTarget = entry?.tracking == TrackingType.WEIGHTED &&
                    pe.prescription.let { it == null || it.target is PrescribedTarget.LoadReps },
                targetSets = pe.targetSets,
                canMoveUp = index > 0,
                canMoveDown = index < slots.lastIndex,
                rows = rows[slot.programExerciseId],
            )
        }
        return DayEditUiState(
            slots = slotStates,
            unit = unit,
            canRemove = DayEditRules.canRemove(slotStates.size),
            defaultEquipmentFilter = equipment,
            catalog = catalog,
        )
    }

    data class Context(
        val program: Program,
        val dayId: String?,
        val catalog: ExerciseCatalog,
        val plan: PlanEntity?,
    )

    private companion object {
        const val KEY_OPTIONS_SLOT = "day_lift_options_slot"
        const val KEY_PRESCRIPTION_DRAFT = "day_prescription_draft"
        const val KEY_PRESCRIPTION_STRUCTURE = "day_prescription_structure"
        const val KEY_CARDIO_SET_BEFORE_EDIT = "day_cardio_set_before_edit"
        const val KEY_CARDIO_DRAFT = "day_cardio_block_draft"
        const val KEY_DAY_EDITOR_OPEN = "day_editor_open"
        const val KEY_PENDING_PLAN_SWAP = "day_pending_plan_swap"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal fun liftOptionsState(half: String, title: String, rule: SlotRule?,
                              tracking: TrackingType, equipment: Collection<Equipment>, plan: PlanEntity,
                              unit: WeightUnit, rest: RestSettings): LiftOptionsState? {
    rule ?: return null
    val goal = plan.planSettings.defaultsFor
    return LiftOptionsState(half, title,
        RestPolicy.effectiveRestSeconds(SetKind.WORK, tracking, rest.overrides, rule, goal),
        RestPolicy.effectiveRestSeconds(SetKind.WORK, tracking, rest.overrides, rule.copy(restSeconds = null), goal),
        rule.restSeconds != null, rule.rir, rule.incrementPct,
        if (tracking == TrackingType.WEIGHTED) SetFormatter.weight(
            (plan.planSettings.loadSteps ?: LoadSteps.defaults(unit)).stepLb(equipment), unit) else null)
}
