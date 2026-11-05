package cloud.trotter.log.strength.ui.day

import androidx.lifecycle.SavedStateHandle
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
import cloud.trotter.log.strength.domain.model.Equipment
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
) {
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
        ) { slots, answers, (runOpen, performed) ->
            val block = ctx.program.days.firstOrNull { it.id == dayId }?.cardioBlock
            buildEditState(slots, ctx.catalog, answers.equipment).copy(
                planId = plan.id, dayId = dayId, cardioBlock = block, config = answers.config,
                runOpen = runOpen,
                performedExerciseIds = performed.filterKeys { it.second == Slot.MAIN }.mapKeys { it.key.first },
                performedPartnerIds = performed.filterKeys { it.second == Slot.SS }.mapKeys { it.key.first },
                canRemove = DayEditRules.canRemove(slots.size, block != null),
                canResetToTemplate = plan.origin == PlanOrigin.GENERATED.name &&
                    ProgramGenerator.generate(answers).days.any { it.id == dayId },
            )
        }
    }
    private val prescriptionDraft = savedState.getStateFlow<PrescriptionDraft?>(KEY_PRESCRIPTION_DRAFT, null)
    private val dayEditorOpen = savedState.getStateFlow(
        KEY_DAY_EDITOR_OPEN, savedState.get<Boolean>(Routes.DAY_EDITOR_ARG) == true,
    )
    private val cardioDraft = savedState.getStateFlow<CardioBlockDraft?>(KEY_CARDIO_DRAFT, null)
    private val prescriptionEditState = combine(
        editProgramState, unitFlow, prescriptionDraft, dayEditorOpen,
    ) { state, unit, draft, editorOpen ->
        state.copy(
            unit = unit, editorOpen = editorOpen,
            prescriptionDraft = draft?.takeIf { restored ->
                state.slots.any { it.programExerciseId == restored.slotId }
            },
        )
    }
    val dayEditState: StateFlow<DayEditUiState> = combine(
        prescriptionEditState, cardioDraft, otherWorkoutOpenMessage,
    ) { state, draft, message ->
        state.copy(cardioDraft = draft?.takeIf {
            it.planId == state.planId && it.dayId == state.dayId
        }, otherWorkoutOpenMessage = message)
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayEditUiState())

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
            discardPrescription()
            savedState.remove<CardioSetDraft>(KEY_CARDIO_SET_BEFORE_EDIT)
            savedState[KEY_CARDIO_DRAFT] = null
        }
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
        savedState[KEY_PRESCRIPTION_DRAFT] = PrescriptionDraft.from(slot, state.catalog, state.config)
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
            val entry = catalog.catalogFlow.first().find(exerciseId)
            val structure = entry?.let { SetStructure.defaultFor(it) } ?: SetStructure.STRAIGHT
            repo.addExercise(day, ProgramExercise(exerciseId, structure = structure), targetPlanId = planId)
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
                prescription = pe.prescription,
                keepToPlan = pe.keepToPlan,
                structure = pe.structure,
                weightedTarget = entry?.tracking == TrackingType.WEIGHTED &&
                    pe.prescription.let { it == null || it.target is PrescribedTarget.LoadReps },
                targetSets = pe.targetSets,
                canMoveUp = index > 0,
                canMoveDown = index < slots.lastIndex,
            )
        }
        return DayEditUiState(
            slots = rows,
            canRemove = DayEditRules.canRemove(rows.size),
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
        const val KEY_PRESCRIPTION_DRAFT = "day_prescription_draft"
        const val KEY_CARDIO_SET_BEFORE_EDIT = "day_cardio_set_before_edit"
        const val KEY_CARDIO_DRAFT = "day_cardio_block_draft"
        const val KEY_DAY_EDITOR_OPEN = "day_editor_open"
        const val KEY_PENDING_PLAN_SWAP = "day_pending_plan_swap"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
