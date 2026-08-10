package cloud.trotter.log.strength.ui.day

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.BackAction
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.SelectionCard
import cloud.trotter.log.strength.ui.components.SelectionMode
import cloud.trotter.log.strength.ui.components.disabledAlpha
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.components.pressableToggleable
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.BorderStrong
import cloud.trotter.log.strength.ui.theme.CardTitle
import cloud.trotter.log.strength.ui.theme.CardTitleSmall
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary

/**
 * The day-edit sheet (spec §8.3): a [ModalBottomSheet] living inside the day
 * feature and sharing [DayViewModel] (brief D1) — it is not a nav destination.
 * Internal navigation between the slot list, the substitution/add picker and
 * the pattern picker is plain Compose state; only the page identity is
 * [rememberSaveable] (a rotation mid-edit shouldn't punt the user back to the
 * slot list). Search/filter drafts are saveable too: Create exercise is a
 * designed round trip out to a navigation destination, and returning should
 * restore the picker the user had already narrowed down.
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
    var swapSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingPattern by rememberSaveable { mutableStateOf(false) }
    var addPatternName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    var optionsSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ssSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ssPatternName by rememberSaveable { mutableStateOf<String?>(null) }

    // One back action for the ← chip and the system back button (#122): pop the
    // deepest open page, and only let a back press through to dismiss the sheet
    // once we're on the root slot list.
    val backTarget = dayEditBackTarget(
        swapping = swapSlotId != null,
        supersetSlot = ssSlotId != null,
        supersetPatternPicked = ssPatternName != null,
        pickingPattern = pickingPattern,
        addingFromPattern = addPatternName != null,
        options = optionsSlotId != null,
    )
    val onBack = {
        when (backTarget) {
            DayEditPage.SWAP -> swapSlotId = null
            DayEditPage.SUPERSET_EXERCISE -> ssPatternName = null
            DayEditPage.SUPERSET_PATTERN -> ssSlotId = null
            DayEditPage.ADD_PATTERN -> pickingPattern = false
            DayEditPage.ADD_EXERCISE -> addPatternName = null
            DayEditPage.OPTIONS -> optionsSlotId = null
            null -> Unit
        }
    }
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Inside the sheet's content, so the handler lands on the sheet dialog's
        // own back dispatcher (M3 hosts the sheet in a ComponentDialog) ahead of
        // the dismiss-on-back callback the dialog registers for itself. M3 runs
        // its own predictive dismissal there; page-back is only a content swap
        // with nothing to preview, and another gesture animation would fight
        // the sheet's. This handler only claims the press while a page can go
        // back. Disabling it at the root returns the gesture to the sheet.
        BackHandler(enabled = backTarget != null, onBack = onBack)
        val swapSlot = state.slots.firstOrNull { it.programExerciseId == swapSlotId }
        val ssSlot = state.slots.firstOrNull { it.programExerciseId == ssSlotId }
        val optionsSlot = state.slots.firstOrNull { it.programExerciseId == optionsSlotId }
        when {
            // Swap and the superset pickers layer above the options page so
            // "swap, then add a superset" reads as one continuous flow — they
            // never clear optionsSlotId, so finishing (or backing out of) one
            // lands back on the options page rather than the slot list.
            swapSlot != null && swapSlot.pattern != null -> SwapPickerPage(
                slot = swapSlot,
                pattern = swapSlot.pattern,
                state = state,
                accent = accent,
                onPick = { entry -> actions.onSwap(swapSlot.position, entry.id); swapSlotId = null },
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
                    title = stringResource(R.string.day_edit_superset_pattern_title),
                    patterns = ordered,
                    onPick = { pattern -> ssPatternName = pattern.name },
                    onBack = onBack,
                )
            }
            ssSlot != null && ssPatternName != null -> {
                val pattern = MovementPattern.valueOf(ssPatternName!!)
                ExercisePickerScreen(
                    key = "ss-${ssSlot.programExerciseId}-$ssPatternName",
                    title = stringResource(R.string.day_edit_superset_exercise_title, ssSlot.title),
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
                title = stringResource(R.string.day_edit_add_pattern_title),
                patterns = state.catalog.entries.map { it.pattern }.distinct().sortedBy { it.ordinal },
                onPick = { pattern -> addPatternName = pattern.name; pickingPattern = false },
                onBack = onBack,
            )
            addPatternName != null -> {
                val pattern = MovementPattern.valueOf(addPatternName!!)
                ExercisePickerScreen(
                    key = "add-${pattern.name}",
                    title = stringResource(R.string.day_edit_add_exercise_title, patternLabel(pattern)),
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
                onBack = onBack,
                onSwapClick = { swapSlotId = optionsSlot.programExerciseId },
                onSupersetClick = { ssSlotId = optionsSlot.programExerciseId },
                onRemoveSupersetClick = { actions.onRemoveSuperset(optionsSlot.position) },
            )
            else -> DaySlotList(
                state = state,
                accent = accent,
                onEditClick = { slotId -> optionsSlotId = slotId },
                onRemoveClick = actions.onRemove,
                onAddClick = { pickingPattern = true },
                onResetClick = { confirmingReset = true },
            )
        }
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
    accent: Color,
    onEditClick: (Long) -> Unit,
    onRemoveClick: (Int) -> Unit,
    onAddClick: () -> Unit,
    onResetClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.day_edit_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.slots, key = { it.programExerciseId }) { slot ->
                DaySlotRow(
                    slot = slot,
                    canRemove = state.canRemove,
                    accent = accent,
                    onEditClick = { onEditClick(slot.programExerciseId) },
                    onRemoveClick = { onRemoveClick(slot.position) },
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton(stringResource(R.string.day_edit_add_exercise_button), onClick = onAddClick, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.size(8.dp))
        SheetButton(stringResource(R.string.day_edit_reset_button), onClick = onResetClick, outlined = true, textColor = TextSecondary)
    }
}

@Composable
private fun DaySlotRow(
    slot: DayEditSlotState,
    canRemove: Boolean,
    accent: Color,
    onEditClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(slot.title, color = TextPrimary, style = CardTitleSmall)
                Text(
                    if (slot.isSuperset) stringResource(R.string.day_edit_superset_with_label, slot.partnerTitle ?: "") else slot.pattern?.let { patternLabel(it) } ?: stringResource(R.string.day_edit_unknown_exercise),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SheetButton(stringResource(R.string.day_edit_edit_button), onClick = onEditClick, compact = true)
            Spacer(Modifier.size(8.dp))
            SheetButton(stringResource(R.string.day_edit_remove_button), onClick = onRemoveClick, enabled = canRemove, compact = true, textColor = Error)
        }
    }
}

// --- page: per-slot options (#93) ----------------------------------------------

@Composable
private fun SlotOptionsScreen(
    slot: DayEditSlotState,
    onBack: () -> Unit,
    onSwapClick: () -> Unit,
    onSupersetClick: () -> Unit,
    onRemoveSupersetClick: () -> Unit,
) {
    val title = slot.partnerTitle?.let { stringResource(R.string.day_edit_slot_pair_title, slot.title, it) } ?: slot.title
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        PickerHeader(title = title, onBack = onBack)
        Spacer(Modifier.size(12.dp))
        SheetButton(
            stringResource(R.string.day_edit_swap_exercise_button),
            onClick = onSwapClick,
            enabled = slot.pattern != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        if (slot.partnerExerciseId == null) {
            SheetButton(stringResource(R.string.day_edit_add_superset_button), onClick = onSupersetClick, modifier = Modifier.fillMaxWidth())
        } else {
            SheetButton(stringResource(R.string.day_edit_swap_superset_button), onClick = onSupersetClick, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            SheetButton(
                stringResource(R.string.day_edit_remove_superset_button),
                onClick = onRemoveSupersetClick,
                textColor = Error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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
                SelectionCard(
                    title = patternLabel(pattern),
                    selected = false,
                    onClick = { onPick(pattern) },
                    mode = SelectionMode.Action,
                )
            }
        }
    }
}

// --- the swap page, wherever it is reached from (#122) ------------------------

/**
 * The ranked same-pattern substitution picker for one slot — the page the
 * sheet's Edit → Swap lands on, and the page an exercise card's ⇄ chip opens
 * directly ([SlotSwapSheet]). One definition so both routes rank, filter and
 * title identically.
 */
@Composable
private fun SwapPickerPage(
    slot: DayEditSlotState,
    pattern: MovementPattern,
    state: DayEditUiState,
    accent: Color,
    onPick: (ExerciseEntry) -> Unit,
    onBack: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
) {
    ExercisePickerScreen(
        key = "swap-${slot.programExerciseId}",
        title = stringResource(R.string.day_edit_swap_title, slot.title),
        pattern = pattern,
        candidates = state.catalog.substitutionsFor(slot.exerciseId),
        defaultEquipment = state.defaultEquipmentFilter,
        accent = accent,
        onPick = onPick,
        onBack = onBack,
        onCreateExercise = onCreateExercise,
    )
}

/**
 * The swap picker on its own sheet, opened from an exercise card's ⇄ chip
 * (issue #122) instead of walking the slot list and options pages. It has no
 * parent page, so ← and system back both just close it — no [BackHandler]
 * needed, the sheet dialog's own dismiss-on-back is exactly right.
 *
 * [onSwap] is the same [DayEditActions.onSwap] the sheet's swap page calls, so
 * the mutation (log kept keyed by programExerciseId, new exercise reseeded from
 * its GOAL — spec §8.3) is identical whichever route the user took.
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SwapPickerPage(
            slot = slot,
            pattern = pattern,
            state = state,
            accent = accent,
            onPick = { entry -> onSwap(slot.position, entry.id); onDismiss() },
            onBack = onDismiss,
            onCreateExercise = onCreateExercise,
        )
    }
}

// --- page 3: substitution / add candidate picker (spec §8.3, PLAN.md A4) -----

@Composable
internal fun ExercisePickerScreen( // internal: restoration pinned directly (#178) — the sheet's dialog window is invisible to StateRestorationTester
    key: String,
    title: String,
    pattern: MovementPattern,
    candidates: List<ExerciseEntry>,
    defaultEquipment: Set<Equipment>,
    accent: Color,
    onPick: (ExerciseEntry) -> Unit,
    onBack: () -> Unit,
    onCreateExercise: (MovementPattern) -> Unit,
) {
    var query by rememberSaveable(key) { mutableStateOf("") }
    var equipmentFilter by rememberSaveable(
        key,
        stateSaver = listSaver(
            save = { selected -> selected.map(Equipment::name) },
            // A saved bundle can outlive an enum rename across an app update —
            // an unknown name is a dropped filter, never a crash.
            restore = { names -> names.mapNotNullTo(mutableSetOf()) { n -> Equipment.entries.find { it.name == n } } },
        ),
    ) { mutableStateOf(defaultEquipment) }
    val allEquipment = remember(candidates) { candidates.flatMap { it.equipment }.distinct() }
    val results = remember(candidates, query, equipmentFilter) {
        ExercisePicker.filter(candidates, query, equipmentFilter)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        PickerHeader(title = title, onBack = onBack)
        Spacer(Modifier.size(10.dp))
        SearchField(query = query, onQueryChange = { query = it })
        if (allEquipment.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            EquipmentFilterRow(
                options = allEquipment,
                selected = equipmentFilter,
                accent = accent,
                onToggle = { equip ->
                    equipmentFilter = if (equip in equipmentFilter) equipmentFilter - equip else equipmentFilter + equip
                },
            )
        }
        Spacer(Modifier.size(10.dp))
        if (results.isEmpty()) {
            Text(stringResource(R.string.day_edit_no_matches), color = TextFaint, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.id }) { entry ->
                    SelectionCard(
                        title = entry.name,
                        subtitle = entry.equipment.joinToString(", ") {
                            equipmentLabel(it)
                        },
                        selected = false,
                        onClick = { onPick(entry) },
                        mode = SelectionMode.Action,
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        // Navigates to the customExercise route (D1) with this picker's pattern
        // pre-filled; on save the route pops back and the picker re-shows with
        // the new custom entry visible, ranked after the catalog per
        // ExerciseCatalog.CUSTOM_SUBRANK.
        SheetButton(stringResource(R.string.day_edit_create_exercise_button), onClick = { onCreateExercise(pattern) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PickerHeader(title: String, onBack: () -> Unit) {
    val backDescription = stringResource(R.string.day_edit_back_action)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackAction(
            onClick = onBack,
            visualSize = 32.dp,
            outlined = false,
            shape = MaterialTheme.shapes.small,
            iconSize = 18.dp,
        )
        Spacer(Modifier.size(10.dp))
        Text(title, color = TextPrimary, style = CardTitle)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
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
            cursorColor = TextPrimary,
            focusedBorderColor = BorderStrong,
            unfocusedBorderColor = Border,
            focusedContainerColor = Surface2,
            unfocusedContainerColor = Surface2,
            focusedPlaceholderColor = TextFaint,
            unfocusedPlaceholderColor = TextFaint,
        ),
    )
}

@Composable
/**
 * Compact equipment filters. Material 3 FilterChip does not expose its
 * 16dp-per-side label padding, while this row's pinned geometry requires 12dp.
 */
internal fun EquipmentFilterRow(
    options: List<Equipment>,
    selected: Set<Equipment>,
    accent: Color,
    onToggle: (Equipment) -> Unit,
) {
    // Intentionally bespoke after the Phase 5 FilterChip audit. Material 3
    // exposes a height override, but not its 16 dp-per-side label padding; this
    // row uses 12 dp and widening every pill would change its compact geometry.
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { equip ->
            val isOn = equip in selected
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .background(if (isOn) accent.copy(alpha = 0.18f) else Surface2, RoundedCornerShape(50))
                    .border(1.dp, if (isOn) accent else Border, RoundedCornerShape(50))
                    .pressableToggleable(
                        value = isOn,
                        role = Role.Checkbox,
                        shape = RoundedCornerShape(50),
                        onValueChange = { onToggle(equip) },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(equipmentLabel(equip), color = if (isOn) accent else TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ResetToTemplateDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.day_edit_reset_confirm_title)) },
        text = { Text(stringResource(R.string.day_edit_reset_confirm_body)) },
        confirmButton = { DialogAction(stringResource(R.string.day_edit_reset_confirm_button), Error, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.day_edit_cancel_button), TextSecondary, onDismiss) },
    )
}

// --- small pieces --------------------------------------------------------------

/**
 * Sheet action. The compact slot-row branch stays bespoke because Material 3
 * Button has a 58dp internal minimum width that cannot be capped externally;
 * the regular branch delegates to M3 buttons.
 */
@Composable
private fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
    compact: Boolean = false,
    textColor: Color = Done,
) {
    if (compact) {
        // The slot-row Edit/Remove pair stays bespoke (audit §8's compact-row
        // exception): an outer modifier cannot cap M3 Button's internal 58dp
        // minimum width, so migrating would visibly widen every slot row.
        Box(
            modifier = modifier
                .disabledAlpha(enabled)
                .minimumInteractiveComponentSize()
                .then(if (outlined) Modifier.border(1.dp, Border, RoundedCornerShape(8.dp)) else Modifier)
                .background(if (outlined) Color.Transparent else Surface2, RoundedCornerShape(8.dp))
                .pressable(enabled = enabled, onClick = onClick, shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = textColor, style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    val contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.disabledAlpha(enabled),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, Border),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = textColor,
                disabledContentColor = textColor,
            ),
            contentPadding = contentPadding,
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.disabledAlpha(enabled),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Surface2,
                contentColor = textColor,
                disabledContainerColor = Surface2,
                disabledContentColor = textColor,
            ),
            contentPadding = contentPadding,
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun patternLabel(pattern: MovementPattern): String = stringResource(when (pattern) {
    MovementPattern.SQUAT_BILATERAL -> R.string.day_edit_pattern_squat_bilateral
    MovementPattern.SINGLE_LEG -> R.string.day_edit_pattern_single_leg
    MovementPattern.HINGE -> R.string.day_edit_pattern_hinge
    MovementPattern.KNEE_FLEXION -> R.string.day_edit_pattern_knee_flexion
    MovementPattern.KNEE_EXTENSION -> R.string.day_edit_pattern_knee_extension
    MovementPattern.H_PUSH -> R.string.day_edit_pattern_horizontal_push
    MovementPattern.V_PUSH -> R.string.day_edit_pattern_vertical_push
    MovementPattern.H_PULL -> R.string.day_edit_pattern_horizontal_pull
    MovementPattern.V_PULL -> R.string.day_edit_pattern_vertical_pull
    MovementPattern.SIDE_DELT -> R.string.day_edit_pattern_side_delt
    MovementPattern.REAR_DELT -> R.string.day_edit_pattern_rear_delt
    MovementPattern.BICEPS -> R.string.day_edit_pattern_biceps
    MovementPattern.TRICEPS -> R.string.day_edit_pattern_triceps
    MovementPattern.CALF_GASTROC -> R.string.day_edit_pattern_calf_gastroc
    MovementPattern.CALF_SOLEUS -> R.string.day_edit_pattern_calf_soleus
    MovementPattern.CORE_ANTI_EXT -> R.string.day_edit_pattern_core_anti_ext
    MovementPattern.CORE_ANTI_ROT -> R.string.day_edit_pattern_core_anti_rot
    MovementPattern.CORE_FLEX -> R.string.day_edit_pattern_core_flex
    MovementPattern.CARDIO -> R.string.day_edit_pattern_cardio
})

private fun equipmentLabel(equipment: Equipment): String = enumLabel(equipment.name)

private fun enumLabel(name: String): String =
    name.split("_").joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
