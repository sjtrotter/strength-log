package cloud.trotter.log.strength.ui.day

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SlotSeeds
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.AppSheetLayout
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.SheetCommitButton
import cloud.trotter.log.strength.ui.text.patternLabel
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * The day-edit sheet (spec §8.3): a [AppModalBottomSheet] living inside the day
 * feature and sharing [DayViewModel] (brief D1) — it is not a nav destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayEditSheet(
    state: DayEditUiState,
    actions: DayEditActions,
    accent: Color,
    onDismiss: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        DayEditPages(state, actions, accent, onDismiss, onCreateExercise)
    }
}

/**
 * Internal navigation between the slot list, the substitution/add picker and
 * the pattern picker is plain Compose state; only the page identity is
 * [rememberSaveable] (a rotation mid-edit shouldn't punt the user back to the
 * slot list). Search/filter drafts are saveable too: Create exercise is a
 * designed round trip out to a navigation destination, and returning should
 * restore the picker the user had already narrowed down. The prescription
 * and cardio drafts, their page identities, and open-editor identity live in
 * DayViewModel's SavedStateHandle.
 */
@Composable
internal fun DayEditPages(
    state: DayEditUiState,
    actions: DayEditActions,
    accent: Color,
    onDismiss: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
    onRootBack: (() -> Unit)? = null,
) {
    var swapSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var swapHalf by rememberSaveable { mutableStateOf(Slot.MAIN) }
    var pickingPattern by rememberSaveable { mutableStateOf(false) }
    var addPatternName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    var optionsSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ssSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ssPatternName by rememberSaveable { mutableStateOf<String?>(null) }

    // One back action for the ← chip and the system back button: pop the
    // deepest open page, and only let a back press through to dismiss the sheet
    // once we're on the root slot list.
    val backTarget = dayEditBackTarget(
        swapping = swapSlotId != null,
        supersetSlot = ssSlotId != null,
        supersetPatternPicked = ssPatternName != null,
        pickingPattern = pickingPattern,
        addingFromPattern = addPatternName != null,
        options = optionsSlotId != null,
        prescription = state.prescriptionDraft != null,
        cardio = state.cardioDraft != null,
        cardioSet = state.cardioDraft?.editingSet != null,
    )
    val onBack = {
        when (backTarget) {
            DayEditPage.CARDIO, DayEditPage.CARDIO_SET -> actions.onBackCardio()
            DayEditPage.PRESCRIPTION -> actions.onDiscardPrescription()
            DayEditPage.SWAP -> swapSlotId = null
            DayEditPage.SUPERSET_EXERCISE -> ssPatternName = null
            DayEditPage.SUPERSET_PATTERN -> ssSlotId = null
            DayEditPage.ADD_PATTERN -> pickingPattern = false
            DayEditPage.ADD_EXERCISE -> addPatternName = null
            DayEditPage.OPTIONS -> optionsSlotId = null
            null -> Unit
        }
    }
    if (onRootBack == null) {
        // The sheet's dialog owns dismissal. Its content claims Back only for
        // page navigation, ahead of M3's predictive dismissal callback.
        BackHandler(enabled = backTarget != null, onBack = onBack)
    } else {
        BackHandler { if (backTarget != null) onBack() else onRootBack() }
    }
    val swapSlot = state.slots.firstOrNull { it.programExerciseId == swapSlotId }?.let { state.performedSwapSlot(it, swapHalf) }
    val ssSlot = state.slots.firstOrNull { it.programExerciseId == ssSlotId }
    val optionsSlot = state.slots.firstOrNull { it.programExerciseId == optionsSlotId }
    when {
        state.cardioDraft != null -> {
            val draft = state.cardioDraft
            val index = draft.editingSet
            if (index != null) {
                CardioSetPage(
                    draft = draft.sets[index],
                    onChange = { set -> actions.onCardioDraft(draft.copy(sets = draft.sets.mapIndexed { i, old -> if (i == index) set else old })) },
                    onSave = actions.onSaveCardio,
                    onBack = onBack,
                )
            } else {
                CardioBlockPage(draft, actions.onCardioDraft, actions.onSaveCardio, onBack)
            }
        }
        state.prescriptionDraft != null -> PrescriptionPage(
            draft = state.prescriptionDraft,
            structure = state.slots.firstOrNull { it.programExerciseId == state.prescriptionDraft.slotId }?.structure
                ?: SetStructure.STRAIGHT,
            unit = state.unit,
            onChange = actions.onPrescriptionDraft,
            onSave = actions.onSavePrescription,
            onBack = onBack,
        )
        // Swap and the superset pickers layer above the options page so
        // "swap, then add a superset" reads as one continuous flow — they
        // never clear optionsSlotId, so finishing (or backing out of) one
        // lands back on the options page rather than the slot list.
        swapSlot != null && swapSlot.pattern != null -> SwapPickerPage(
            slot = swapSlot,
            pattern = swapSlot.pattern,
            state = state,
            accent = accent,
            onPick = { entry ->
                if (swapHalf == Slot.SS) actions.onSetSuperset(swapSlot.position, entry.id)
                else actions.onSwap(swapSlot.position, entry.id)
                swapSlotId = null
            },
            onWorkoutPick = { entry ->
                actions.onWorkoutSwapHalf(swapSlot.position, entry.id, swapHalf)
                actions.onEditorOpen(false)
                swapSlotId = null
            },
            half = swapHalf,
            onBack = onBack,
            onCreateExercise = onCreateExercise,
        )
        ssSlot != null && ssPatternName == null -> {
            // Same-pattern assistance pairings (pull-up + assisted pull-up)
            // are the common case, so the current partner's pattern (or the
            // slot's own pattern, when adding a first partner) is ranked first.
            val referencePattern = ssSlot.partnerExerciseId
                ?.let { state.catalog.find(it)?.pattern }
                ?: ssSlot.pattern
            val patterns = state.catalog.entries.map { it.pattern }.distinct().sortedBy { it.ordinal }
            val ordered = if (referencePattern != null) {
                listOf(referencePattern) + patterns.filter { it != referencePattern }
            } else {
                patterns
            }
            PatternPickerScreen(
                title = stringResource(R.string.sheet_superset_pattern_title),
                patterns = ordered,
                onPick = { pattern -> ssPatternName = pattern.name },
                onBack = onBack,
            )
        }
        ssSlot != null && ssPatternName != null -> {
            val pattern = MovementPattern.valueOf(ssPatternName!!)
            ExercisePickerScreen(
                key = "ss-${ssSlot.programExerciseId}-$ssPatternName",
                title = stringResource(R.string.sheet_superset_exercise_title, ssSlot.title),
                pattern = pattern,
                candidates = state.catalog.byPattern(pattern)
                    .filter { it.id != ssSlot.exerciseId && it.id != ssSlot.partnerExerciseId },
                defaultEquipment = state.defaultEquipmentFilter,
                accent = accent,
                onPick = { entry ->
                    actions.onSetSuperset(ssSlot.position, entry.id)
                    ssSlotId = null
                    ssPatternName = null
                },
                onBack = onBack,
                onCreateExercise = onCreateExercise,
            )
        }
        pickingPattern -> PatternPickerScreen(
            title = stringResource(R.string.sheet_add_pattern_title),
            patterns = state.catalog.entries.map { it.pattern }.distinct().sortedBy { it.ordinal },
            onPick = { pattern -> addPatternName = pattern.name; pickingPattern = false },
            onBack = onBack,
        )
        addPatternName != null -> {
            val pattern = MovementPattern.valueOf(addPatternName!!)
            ExercisePickerScreen(
                key = "add-${pattern.name}",
                title = stringResource(R.string.sheet_add_exercise_title, patternLabel(pattern)),
                pattern = pattern,
                candidates = state.catalog.byPattern(pattern),
                defaultEquipment = state.defaultEquipmentFilter,
                accent = accent,
                onPick = { entry -> actions.onAdd(entry.id); addPatternName = null },
                onBack = onBack,
                onCreateExercise = onCreateExercise,
            )
        }
        optionsSlot != null -> SlotOptionsScreen(
            slot = optionsSlot,
            onPrescriptionClick = { actions.onOpenPrescription(optionsSlot.programExerciseId) },
            onMove = { up -> actions.onMove(optionsSlot.programExerciseId, up) },
            onBack = onBack,
            onSwapClick = { swapHalf = Slot.MAIN; swapSlotId = optionsSlot.programExerciseId },
            onSupersetClick = {
                if (optionsSlot.partnerExerciseId != null) {
                    swapHalf = Slot.SS
                    swapSlotId = optionsSlot.programExerciseId
                } else ssSlotId = optionsSlot.programExerciseId
            },
            onRemoveSupersetClick = { actions.onRemoveSuperset(optionsSlot.position) },
            runOpen = state.runOpen,
            onKeepToPlanChange = { actions.onSetKeepToPlan(optionsSlot.programExerciseId, it) },
            onResetToPlanClick = { actions.onResetSlotToPlan(optionsSlot.programExerciseId) },
            onStructureChange = { actions.onStructureChange(optionsSlot.programExerciseId, it) },
        )
        else -> DaySlotList(
            state = state,
            onEditClick = actions.onOpenPrescription,
            onOptionsClick = { slotId -> optionsSlotId = slotId },
            onSwapClick = { slotId -> swapHalf = Slot.MAIN; swapSlotId = slotId },
            onRemoveClick = actions.onRemove,
            onAddClick = { pickingPattern = true },
            onResetClick = { confirmingReset = true },
            onOpenCardio = actions.onOpenCardio,
            onRemoveCardio = actions.onRemoveCardio,
            showTitle = !state.planEditing,
        )
    }

    if (confirmingReset) {
        ResetToTemplateDialog(
            onConfirm = { confirmingReset = false; actions.onResetToTemplate(); onDismiss() },
            onDismiss = { confirmingReset = false },
        )
    }
}

// --- page 1: the day's slots --------------------------------------------------

@Composable
private fun DaySlotList(
    state: DayEditUiState,
    onEditClick: (Long) -> Unit,
    onOptionsClick: (Long) -> Unit,
    onSwapClick: (Long) -> Unit,
    onRemoveClick: (Int) -> Unit,
    onAddClick: () -> Unit,
    onResetClick: () -> Unit,
    onOpenCardio: () -> Unit,
    onRemoveCardio: () -> Unit,
    showTitle: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showTitle) Text(stringResource(R.string.day_edit_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            if (state.canResetToTemplate) Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.sheet_more)) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.day_edit_reset_button)) },
                        onClick = { menuOpen = false; onResetClick() })
                }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(state.slots, key = { it.programExerciseId }) { slot ->
                DaySlotRow(
                    slot = slot,
                    summary = daySlotSummary(slot, state),
                    canRemove = DayEditRules.canRemove(state.slots.size, state.cardioBlock != null),
                    onEditClick = { onEditClick(slot.programExerciseId) },
                    onOptionsClick = { onOptionsClick(slot.programExerciseId) },
                    onSwapClick = { onSwapClick(slot.programExerciseId) },
                    onRemoveClick = { onRemoveClick(slot.position) },
                )
            }
            item { AddExerciseRow(stringResource(R.string.sheet_add_exercise), onAddClick) }
            item {
                if (state.cardioBlock == null) AddExerciseRow(stringResource(R.string.day_edit_add_cardio), onOpenCardio)
                else ListItem(headlineContent = { Text(stringResource(R.string.day_edit_edit_cardio)) },
                    modifier = Modifier.heightIn(min = 72.dp).clickable(onClick = onOpenCardio))
            }
            if (state.cardioBlock != null) item {
                TextButton(onClick = onRemoveCardio, enabled = state.slots.isNotEmpty(), modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.day_edit_remove_cardio))
                }
            }
        }
    }
}

@Composable
private fun AddExerciseRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = MaterialTheme.colorScheme.primary) },
        leadingContent = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.heightIn(min = 72.dp).clickable(onClick = onClick),
    )
}

@Composable
private fun daySlotSummary(slot: DayEditSlotState, state: DayEditUiState): String {
    if (slot.isSuperset) return stringResource(R.string.sheet_superset_with, slot.partnerTitle.orEmpty())
    if (slot.targetSets == 0) return stringResource(R.string.sheet_no_sets)
    val entry = state.catalog.find(slot.exerciseId)
    val exercise = ProgramExercise(slot.exerciseId, structure = slot.structure,
        targetSets = slot.targetSets, prescription = slot.prescription)
    // Use the workout's seed policy; the prescription editor's defaults are only input drafts.
    val sets = SlotSeeds.main(exercise, entry, state.config)
        ?: return stringResource(when (slot.structure) {
            SetStructure.STRAIGHT -> R.string.day_edit_structure_straight
            SetStructure.RAMP_TOP -> R.string.day_edit_structure_ramp_top
            SetStructure.RAMP_TOP_BACKOFF -> R.string.day_edit_structure_ramp_top_backoff
        })
    val first = sets.firstOrNull() ?: return stringResource(R.string.sheet_no_sets)
    val top = sets.firstOrNull { it.kind == SetKind.TOP }
    if (top != null && sets.any { it.kind == SetKind.RAMP }) {
        val ramp = stringResource(R.string.sheet_ramp_prescription,
            WeightStepper.format(state.unit.fromLb(top.weightLb)), state.unit.name.lowercase())
        val backoffs = sets.count { it.kind == SetKind.BACKOFF }
        return if (backoffs == 0) ramp else pluralStringResource(R.plurals.sheet_ramp_backoffs, backoffs, ramp, backoffs)
    }
    val target = slot.prescription?.target ?: when (entry?.tracking) {
        TrackingType.WEIGHTED -> PrescribedTarget.LoadReps(first.weightLb, first.reps)
        TrackingType.TIMED -> PrescribedTarget.Hold(first.seconds, first.weightLb)
        TrackingType.REPS, null -> PrescribedTarget.Reps(first.reps)
    }
    val value = when (target) {
        is PrescribedTarget.LoadReps -> stringResource(R.string.sheet_load_prescription,
            target.reps, WeightStepper.format(state.unit.fromLb(target.lb)), state.unit.name.lowercase())
        is PrescribedTarget.Reps -> target.reps.toString()
        is PrescribedTarget.Hold -> if (target.addedLb > 0.0) stringResource(R.string.sheet_loaded_hold_prescription,
            target.seconds, WeightStepper.format(state.unit.fromLb(target.addedLb)), state.unit.name.lowercase())
            else stringResource(R.string.sheet_hold_prescription, target.seconds)
        is PrescribedTarget.RepsInTime -> target.repAim?.let { aim ->
            stringResource(R.string.sheet_timed_prescription, aim, formatCardioTime(target.seconds))
        } ?: formatCardioTime(target.seconds)
    }
    return stringResource(R.string.sheet_sets_prescription, sets.size, value)
}

@Composable
private fun DaySlotRow(
    slot: DayEditSlotState,
    summary: String,
    canRemove: Boolean,
    onEditClick: () -> Unit,
    onOptionsClick: () -> Unit,
    onSwapClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    var expanded by remember(slot.programExerciseId) { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(slot.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.sheet_lift_menu, slot.title))
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.day_swap_apply)) },
                        enabled = slot.pattern != null, onClick = { expanded = false; onSwapClick() })
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.day_edit_remove_button))
                                if (!canRemove) Text(stringResource(R.string.sheet_keep_one_lift), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        enabled = canRemove, onClick = { expanded = false; onRemoveClick() },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.sheet_lift_options)) },
                        onClick = { expanded = false; onOptionsClick() })
                }
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onEditClick),
    )
}

// --- page: per-slot options ----------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotOptionsScreen(
    slot: DayEditSlotState,
    onPrescriptionClick: () -> Unit,
    onMove: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSwapClick: () -> Unit,
    onSupersetClick: () -> Unit,
    onRemoveSupersetClick: () -> Unit,
    runOpen: Boolean,
    onKeepToPlanChange: (Boolean) -> Unit,
    onResetToPlanClick: () -> Unit,
    onStructureChange: (SetStructure) -> Unit,
) {
    var confirmingReset by rememberSaveable(slot.programExerciseId, slot.keepToPlan, runOpen) { mutableStateOf(false) }
    if (confirmingReset && !slot.keepToPlan && !runOpen) {
        AppAlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.day_edit_reset_to_plan_confirm_title)) },
            text = { Text(stringResource(R.string.day_edit_reset_to_plan_confirm_body)) },
            confirmButton = {
                DialogAction(stringResource(R.string.day_edit_reset_confirm_button), Error) {
                    confirmingReset = false
                    onResetToPlanClick()
                }
            },
            dismissButton = { DialogAction(stringResource(R.string.day_edit_cancel_button), TextSecondary) { confirmingReset = false } },
        )
    }
    val title = slot.partnerTitle?.let { stringResource(R.string.day_edit_slot_pair_title, slot.title, it) } ?: slot.title
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        PickerHeader(title = title, onBack = onBack)
        Spacer(Modifier.size(24.dp))
        Text(stringResource(R.string.day_edit_next_time), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEachIndexed { index, keep ->
                SegmentedButton(
                    selected = slot.keepToPlan == keep,
                    onClick = { onKeepToPlanChange(keep) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    label = { Text(stringResource(if (keep) R.string.day_edit_next_time_plan else R.string.day_edit_next_time_progress)) },
                )
            }
        }
        Text(stringResource(R.string.day_edit_next_time_disclosure), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (!slot.keepToPlan && !runOpen) {
            TextButton(onClick = { confirmingReset = true }) {
                Text(stringResource(R.string.day_edit_reset_to_plan))
            }
        }
        if (slot.weightedTarget) {
            Spacer(Modifier.size(24.dp))
            Text(stringResource(R.string.day_edit_set_structure), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                listOf(SetStructure.STRAIGHT, SetStructure.RAMP_TOP, SetStructure.RAMP_TOP_BACKOFF).forEach { structure ->
                    val headline = stringResource(when (structure) {
                        SetStructure.STRAIGHT -> R.string.day_edit_structure_straight
                        SetStructure.RAMP_TOP -> R.string.day_edit_structure_ramp_top
                        SetStructure.RAMP_TOP_BACKOFF -> R.string.day_edit_structure_ramp_top_backoff
                    })
                    val supporting = stringResource(when (structure) {
                        SetStructure.STRAIGHT -> R.string.day_edit_structure_straight_description
                        SetStructure.RAMP_TOP -> R.string.day_edit_structure_ramp_top_description
                        SetStructure.RAMP_TOP_BACKOFF -> R.string.day_edit_structure_ramp_top_backoff_description
                    })
                    ListItem(
                        headlineContent = { Text(headline) },
                        supportingContent = { Text(supporting) },
                        leadingContent = { RadioButton(selected = slot.structure == structure, onClick = null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = slot.structure == structure,
                            role = Role.RadioButton,
                            onClick = { onStructureChange(structure) },
                        ),
                    )
                }
            }
        }
        TextButton(onClick = onSwapClick, enabled = slot.pattern != null) { Text(stringResource(R.string.day_edit_swap_exercise_button)) }
        TextButton(onClick = onPrescriptionClick) { Text(stringResource(R.string.day_edit_prescription)) }
        TextButton(onClick = { onMove(true) }, enabled = slot.canMoveUp) { Text(stringResource(R.string.day_edit_move_up)) }
        TextButton(onClick = { onMove(false) }, enabled = slot.canMoveDown) { Text(stringResource(R.string.day_edit_move_down)) }
        TextButton(onClick = onSupersetClick) {
            Text(stringResource(if (slot.partnerExerciseId == null) R.string.day_edit_add_superset_button else R.string.day_edit_swap_superset_button))
        }
        if (slot.partnerExerciseId != null) TextButton(onClick = onRemoveSupersetClick) {
            Text(stringResource(R.string.day_edit_remove_superset_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrescriptionPage(
    draft: PrescriptionDraft,
    unit: WeightUnit,
    onChange: (PrescriptionDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    structure: SetStructure = SetStructure.STRAIGHT,
) {
    AppSheetLayout(
        title = { PickerHeader(stringResource(R.string.day_edit_prescription), onBack) },
        footer = {
            SheetCommitButton(stringResource(R.string.plans_save), onSave,
                cancelLabel = stringResource(R.string.day_edit_cancel_button), onCancel = onBack)
        },
    ) {
        var trackingExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = trackingExpanded, onExpandedChange = { trackingExpanded = it }) {
            OutlinedTextField(
                value = prescriptionKindLabel(draft.kind), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.sheet_tracking)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(trackingExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
            )
            ExposedDropdownMenu(expanded = trackingExpanded, onDismissRequest = { trackingExpanded = false }) {
                PrescriptionKind.entries.forEach { kind ->
                    DropdownMenuItem(text = { Text(prescriptionKindLabel(kind)) }, onClick = {
                        trackingExpanded = false
                        onChange(draft.copy(kind = kind))
                    })
                }
            }
        }
        if (structure == SetStructure.STRAIGHT) {
            PrescriptionNumber(stringResource(R.string.prescription_sets), draft.sets, 1, 20) { onChange(draft.copy(sets = it)) }
        }
        if (draft.kind == PrescriptionKind.LOAD_REPS || draft.kind == PrescriptionKind.REPS) {
            PrescriptionNumber(stringResource(R.string.prescription_reps), draft.reps, 1, 999) { onChange(draft.copy(reps = it)) }
        }
        if (draft.kind == PrescriptionKind.HOLD || draft.kind == PrescriptionKind.REPS_IN_TIME) {
            PrescriptionNumber(stringResource(R.string.sheet_duration), draft.seconds, 5, 3600, 30, duration = true) { onChange(draft.copy(seconds = it)) }
        }
        if (draft.kind == PrescriptionKind.LOAD_REPS || draft.kind == PrescriptionKind.HOLD) {
            val label = stringResource(R.string.sheet_weight, unit.name.lowercase())
            NumericSetField(
                identity = "prescription:${draft.slotId}:weight", value = unit.fromLb(draft.loadLb),
                label = label, field = stringResource(R.string.set_edit_weight_field), partnerName = null,
                modifier = Modifier.fillMaxWidth(), minimum = 0.0, maximum = unit.fromLb(2000.0), decimal = true,
                step = { WeightStepper.increment(it, unit) },
                round = { if (it <= 0.0) 0.0 else WeightStepper.round(it, unit) },
                onChange = { onChange(draft.copy(loadLb = unit.toLb(it).coerceIn(0.0, 2000.0))) },
            )
        }
        if (draft.kind == PrescriptionKind.REPS_IN_TIME) {
            val repAimLabel = stringResource(R.string.prescription_rep_aim)
            ListItem(
                headlineContent = { Text(repAimLabel) },
                trailingContent = {
                    Switch(
                        checked = draft.hasRepAim,
                        onCheckedChange = { onChange(draft.copy(hasRepAim = it)) },
                        modifier = Modifier.semantics { contentDescription = repAimLabel },
                    )
                },
            )
            if (draft.hasRepAim) {
                PrescriptionNumber(stringResource(R.string.prescription_rep_aim), draft.repAim, 1, 999) { onChange(draft.copy(repAim = it)) }
            }
        }
        val eachSideLabel = stringResource(R.string.day_target_each_side)
        ListItem(
            headlineContent = { Text(eachSideLabel) },
            trailingContent = {
                Switch(
                    checked = draft.eachSide,
                    onCheckedChange = { onChange(draft.copy(eachSide = it)) },
                    modifier = Modifier.semantics { contentDescription = eachSideLabel },
                )
            },
        )
        OutlinedTextField(
            value = draft.resistance,
            onValueChange = { onChange(draft.copy(resistance = it.take(40))) },
            label = { Text(stringResource(R.string.prescription_resistance)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun prescriptionKindLabel(kind: PrescriptionKind): String = stringResource(when (kind) {
    PrescriptionKind.LOAD_REPS -> R.string.sheet_weight_reps
    PrescriptionKind.REPS -> R.string.prescription_reps
    PrescriptionKind.HOLD -> R.string.sheet_hold_time
    PrescriptionKind.REPS_IN_TIME -> R.string.prescription_reps_in_time
})

@Composable
private fun PrescriptionNumber(label: String, value: Int, min: Int, max: Int, increment: Int = 1,
    duration: Boolean = false, onChange: (Int) -> Unit) {
    NumericSetField(
        identity = "prescription:$label", value = value.toDouble(), label = label, field = label,
        partnerName = null, modifier = Modifier.fillMaxWidth(), minimum = min.toDouble(), maximum = max.toDouble(),
        step = { increment.toDouble() }, duration = duration,
        round = { if (duration) it else (it / increment).roundToInt().toDouble() * increment },
        onChange = { onChange(it.toInt()) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardioBlockPage(
    draft: CardioBlockDraft,
    onChange: (CardioBlockDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AppSheetLayout(
        title = { PickerHeader(stringResource(R.string.day_edit_edit_cardio), onBack) },
        footer = {
            SheetCommitButton(stringResource(R.string.plans_save), onSave,
                cancelLabel = stringResource(R.string.day_edit_cancel_button), onCancel = onBack)
        },
    ) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = cardioActivityName(draft.activity), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.cardio_edit_activity)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                CardioActivity.entries.forEach { activity ->
                    DropdownMenuItem(text = { Text(cardioActivityName(activity)) }, onClick = {
                        onChange(draft.copy(activity = activity))
                        expanded = false
                    })
                }
            }
        }
        Text(stringResource(R.string.cardio_edit_start_from), style = MaterialTheme.typography.titleSmall)
        CardioTemplate.entries.forEach { template ->
            TextButton(onClick = { onChange(draft.startFrom(template)) }) {
                Text(stringResource(when (template) {
                    CardioTemplate.EASY -> R.string.cardio_edit_easy
                    CardioTemplate.TEMPO -> R.string.cardio_edit_tempo
                    CardioTemplate.INTERVALS -> R.string.cardio_edit_intervals
                }))
            }
        }
        draft.sets.forEachIndexed { index, set ->
            ListItem(
                modifier = Modifier.clickable { onChange(draft.copy(editingSet = index)) },
                headlineContent = { Text(stringResource(R.string.cardio_edit_set_title, index + 1, cardioEditRoleName(set.role))) },
                supportingContent = {
                    val spec = set.spec()
                    val end = cardioEditMeasure(spec.end)
                    val target = spec.aim?.let { stringResource(R.string.cardio_end_aim, end, cardioEditMeasure(it)) } ?: end
                    Text(if (set.hard) stringResource(R.string.cardio_edit_hard_summary, target) else target)
                },
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { onChange(draft.moveSet(index, true)) }, enabled = index > 0) {
                    Text(stringResource(R.string.day_edit_move_up))
                }
                TextButton(onClick = { onChange(draft.moveSet(index, false)) }, enabled = index < draft.sets.lastIndex) {
                    Text(stringResource(R.string.day_edit_move_down))
                }
                TextButton(onClick = { onChange(draft.removeSet(index)) }, enabled = draft.sets.size > 1) {
                    Text(stringResource(R.string.day_edit_remove_button))
                }
            }
        }
        TextButton(onClick = { onChange(draft.addSet()) }) { Text(stringResource(R.string.cardio_add_set)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardioSetPage(
    draft: CardioSetDraft,
    onChange: (CardioSetDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    AppSheetLayout(
        title = { PickerHeader(stringResource(R.string.cardio_edit_set), onBack) },
        footer = {
            SheetCommitButton(stringResource(R.string.plans_save), onSave,
                cancelLabel = stringResource(R.string.day_edit_cancel_button), onCancel = onBack)
        },
    ) {
        Text(stringResource(R.string.cardio_edit_role))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CardioRole.entries.forEachIndexed { index, role ->
                SegmentedButton(
                    selected = draft.role == role, onClick = { onChange(draft.copy(role = role)) },
                    shape = SegmentedButtonDefaults.itemShape(index, CardioRole.entries.size),
                    label = { Text(cardioEditRoleName(role)) },
                )
            }
        }
        Text(stringResource(R.string.cardio_edit_end))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(true, false).forEachIndexed { index, isTime ->
                SegmentedButton(
                    selected = draft.endIsTime == isTime, onClick = { onChange(draft.copy(endIsTime = isTime)) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    label = { Text(stringResource(if (isTime) R.string.cardio_edit_time else R.string.cardio_edit_distance)) },
                )
            }
        }
        CardioMeasureNumber(draft.endIsTime, draft, onChange)
        val aimLabel = stringResource(R.string.cardio_edit_aim)
        ListItem(
            headlineContent = { Text(aimLabel) },
            supportingContent = { Text(stringResource(if (draft.endIsTime) R.string.sheet_aim_distance else R.string.sheet_aim_time)) },
            trailingContent = {
                Switch(checked = draft.hasAim, onCheckedChange = { onChange(draft.copy(hasAim = it)) },
                    modifier = Modifier.semantics { contentDescription = aimLabel })
            },
        )
        if (draft.hasAim) CardioMeasureNumber(!draft.endIsTime, draft, onChange)
        val hardLabel = stringResource(R.string.cardio_edit_hard)
        ListItem(
            headlineContent = { Text(hardLabel) },
            supportingContent = { Text(stringResource(R.string.sheet_hard_description)) },
            trailingContent = {
                Switch(checked = draft.hard, onCheckedChange = { onChange(draft.copy(hard = it)) },
                    modifier = Modifier.semantics { contentDescription = hardLabel })
            },
        )
    }
}

@Composable
private fun CardioMeasureNumber(isTime: Boolean, draft: CardioSetDraft, onChange: (CardioSetDraft) -> Unit) {
    val label = stringResource(if (isTime) R.string.sheet_duration else R.string.cardio_distance_label)
    val increment = if (isTime) 30 else 10
    NumericSetField(
        identity = "cardio:$isTime", value = (if (isTime) draft.seconds else draft.metres).toDouble(),
        label = label, field = stringResource(if (isTime) R.string.sheet_duration else R.string.sheet_distance_field),
        partnerName = null, modifier = Modifier.fillMaxWidth(),
        minimum = if (isTime) 5.0 else 10.0, maximum = if (isTime) 86_400.0 else 200_000.0,
        step = { increment.toDouble() }, duration = isTime,
        round = { if (isTime) it else (it / increment).roundToInt().toDouble() * increment },
        onChange = { onChange(if (isTime) draft.copy(seconds = it.toInt()) else draft.copy(metres = it.toInt())) },
    )
}

@Composable
private fun cardioEditRoleName(role: CardioRole): String = stringResource(when (role) {
    CardioRole.WARMUP -> R.string.cardio_warmup
    CardioRole.WORK -> R.string.cardio_work
    CardioRole.RECOVERY -> R.string.cardio_recovery
    CardioRole.COOLDOWN -> R.string.cardio_cooldown
})

@Composable
private fun cardioEditMeasure(measure: Measure): String = when (measure) {
    is Measure.Time -> formatCardioTime(measure.seconds)
    is Measure.Distance -> stringResource(R.string.cardio_metres, measure.metres)
}

// --- page 2: pick a pattern (add flow and superset flow) ----------------------

@Composable
private fun PatternPickerScreen(
    title: String,
    patterns: List<MovementPattern>,
    onPick: (MovementPattern) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        PickerHeader(title = title, onBack = onBack)
        Spacer(Modifier.size(8.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(patterns) { pattern ->
                ListItem(headlineContent = { Text(patternLabel(pattern)) },
                    modifier = Modifier.heightIn(min = 72.dp).clickable { onPick(pattern) })
            }
        }
    }
}

// --- the swap page, wherever it is reached from ------------------------

/**
 * The ranked same-pattern substitution picker for one slot — the page the
 * sheet's Edit → Swap lands on, and the page an exercise card's ⇄ chip opens
 * directly ([SlotSwapSheet]). One definition so both routes rank, filter and
 * title identically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwapPickerPage(
    slot: DayEditSlotState,
    pattern: MovementPattern,
    state: DayEditUiState,
    accent: Color,
    onPick: (ExerciseEntry) -> Unit,
    onWorkoutPick: (ExerciseEntry) -> Unit,
    half: String = Slot.MAIN,
    onBack: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
) {
    var pickedId by rememberSaveable(slot.programExerciseId, half) { mutableStateOf<String?>(null) }
    var workoutOnly by rememberSaveable(slot.programExerciseId, half) { mutableStateOf(state.runOpen) }
    val picked = pickedId?.let(state.catalog::find)
    if (picked != null) {
        val resetPick = { pickedId = null }
        BackHandler(onBack = resetPick)
        AppSheetLayout(
            title = { PickerHeader(picked.name, resetPick) },
            footer = { SheetCommitButton(stringResource(R.string.day_swap_apply),
                onClick = { if (workoutOnly) onWorkoutPick(picked) else onPick(picked) }) },
        ) {
            Text(stringResource(R.string.sheet_swap_for), style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true, false).forEachIndexed { index, workout ->
                    SegmentedButton(
                        selected = workoutOnly == workout,
                        onClick = { workoutOnly = workout },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(if (workout) R.string.day_swap_this_workout else R.string.day_swap_the_plan)) }
                }
            }
        }
    } else {
        ExercisePickerScreen(
            key = "swap-${slot.programExerciseId}-$half",
            title = stringResource(R.string.sheet_swap_title, slot.title),
            pattern = pattern,
            candidates = state.swapCandidates(slot, half),
            defaultEquipment = state.defaultEquipmentFilter,
            accent = accent,
            onPick = { if (state.planEditing) onPick(it) else pickedId = it.id },
            onBack = onBack,
            onCreateExercise = onCreateExercise,
            allCandidates = state.catalog.entries.filter { it.id != slot.exerciseId },
        )
    }
}

/**
 * The card's swap picker shares the day editor's performed identity and choices.
 * After picking an exercise, "This workout" calls [onWorkoutSwap] and "The plan"
 * calls [onSwap]. An open run defaults to "This workout". Applying closes the
 * sheet; the ViewModel starts a run when needed and reports another open workout.
 * Back from the choice returns to the picker; at its root the dialog dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotSwapSheet(
    slot: DayEditSlotState,
    pattern: MovementPattern,
    state: DayEditUiState,
    accent: Color,
    onSwap: (position: Int, newExerciseId: String) -> Unit,
    onDismiss: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
    onWorkoutSwap: (position: Int, newExerciseId: String) -> Unit = { _, _ -> },
    half: String = Slot.MAIN,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SwapPickerPage(
            slot = slot,
            pattern = pattern,
            state = state,
            accent = accent,
            onPick = { entry -> onSwap(slot.position, entry.id); onDismiss() },
            onWorkoutPick = { entry -> onWorkoutSwap(slot.position, entry.id); onDismiss() },
            half = half,
            onBack = onDismiss,
            onCreateExercise = onCreateExercise,
        )
    }
}

// --- page 3: substitution / add candidate picker (spec §8.3, PLAN.md A4) -----

@Composable
internal fun ExercisePickerScreen( // internal: restoration pinned directly — the sheet's dialog window is invisible to StateRestorationTester
    key: String,
    title: String,
    pattern: MovementPattern,
    candidates: List<ExerciseEntry>,
    defaultEquipment: Set<Equipment>,
    accent: Color,
    onPick: (ExerciseEntry) -> Unit,
    onBack: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
    allCandidates: List<ExerciseEntry>? = null,
) {
    var query by rememberSaveable(key) { mutableStateOf("") }
    var searchAll by rememberSaveable(key) { mutableStateOf(false) }
    var equipmentFilter by rememberSaveable(
        key,
        stateSaver = listSaver(
            save = { selected -> selected.map(Equipment::name) },
            // A saved bundle can outlive an enum rename across an app update —
            // an unknown name is a dropped filter, never a crash.
            restore = { names -> names.mapNotNullTo(mutableSetOf()) { n -> Equipment.entries.find { it.name == n } } },
        ),
    ) { mutableStateOf(defaultEquipment) }
    val searchCandidates = if (searchAll && allCandidates != null) allCandidates else candidates
    val allEquipment = remember(searchCandidates) { searchCandidates.flatMap { it.equipment }.distinct() }
    val results = remember(searchCandidates, query, equipmentFilter) {
        ExercisePicker.filter(searchCandidates, query, equipmentFilter)
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerHeader(title = title, onBack = onBack)
        SearchField(query = query, onQueryChange = { query = it }, accent = accent)
        if (allEquipment.isNotEmpty()) {
            EquipmentFilterRow(
                options = allEquipment, selected = equipmentFilter,
                onToggle = { equip ->
                    equipmentFilter = if (equip in equipmentFilter) equipmentFilter - equip else equipmentFilter + equip
                },
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (results.isEmpty()) item {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sheet_no_matching_exercises), style = MaterialTheme.typography.bodyMedium)
                    if (allCandidates != null && !searchAll) TextButton(onClick = { searchAll = true }) {
                        Text(stringResource(R.string.sheet_search_all_exercises))
                    }
                    if (!equipmentFilter.containsAll(allEquipment)) TextButton(onClick = { equipmentFilter = allEquipment.toSet() }) {
                        Text(stringResource(R.string.sheet_clear_filters))
                    }
                }
            }
            items(results, key = { it.id }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(entry.equipment.joinToString(", ") { equipmentLabel(it) }) },
                    modifier = Modifier.heightIn(min = 72.dp).clickable { onPick(entry) },
                )
            }
            item { AddExerciseRow(stringResource(R.string.sheet_create_exercise)) { onCreateExercise(pattern) } }
        }
    }
}

@Composable
private fun PickerHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.day_edit_back_action))
        }
        Spacer(Modifier.size(8.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, accent: Color) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text(stringResource(R.string.day_edit_search_hint)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = accent,
            focusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedBorderColor = Border,
            focusedContainerColor = Surface2,
            unfocusedContainerColor = Surface2,
            focusedPlaceholderColor = TextSecondary,
            unfocusedPlaceholderColor = TextSecondary,
        ),
    )
}

@Composable
internal fun EquipmentFilterRow(
    options: List<Equipment>,
    selected: Set<Equipment>,
    onToggle: (Equipment) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { equip ->
            val isOn = equip in selected
            FilterChip(
                selected = isOn, onClick = { onToggle(equip) },
                label = { Text(equipmentLabel(equip)) },
                leadingIcon = if (isOn) { { Icon(Icons.Filled.Check, null) } } else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun ResetToTemplateDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.day_edit_reset_confirm_title)) },
        text = { Text(stringResource(R.string.sheet_reset_confirm_body)) },
        confirmButton = { DialogAction(stringResource(R.string.day_edit_reset_confirm_button), Error, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.day_edit_cancel_button), TextSecondary, onDismiss) },
    )
}

// --- small pieces --------------------------------------------------------------

private fun equipmentLabel(equipment: Equipment): String = enumLabel(equipment.name)

private fun enumLabel(name: String): String =
    name.split("_").joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
