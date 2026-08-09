package cloud.trotter.log.strength.ui.day

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.SelectionCard
import cloud.trotter.log.strength.ui.components.disabledAlpha
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.Border
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
 * slot list), the search/filter fields inside a picker page are ordinary
 * `remember` — losing an in-progress search string to process death is not
 * data loss, unlike anything in [DayUiState].
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
                    title = "Superset — pick a pattern",
                    patterns = ordered,
                    onPick = { pattern -> ssPatternName = pattern.name },
                    onBack = onBack,
                )
            }
            ssSlot != null && ssPatternName != null -> {
                val pattern = MovementPattern.valueOf(ssPatternName!!)
                ExercisePickerScreen(
                    key = "ss-${ssSlot.programExerciseId}-$ssPatternName",
                    title = "Superset — ${ssSlot.title}",
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
                title = "Add exercise — pick a pattern",
                patterns = state.catalog.entries.map { it.pattern }.distinct().sortedBy { it.ordinal },
                onPick = { pattern -> addPatternName = pattern.name; pickingPattern = false },
                onBack = onBack,
            )
            addPatternName != null -> {
                val pattern = MovementPattern.valueOf(addPatternName!!)
                ExercisePickerScreen(
                    key = "add-${pattern.name}",
                    title = "Add — ${patternLabel(pattern)}",
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
        Text("Edit day", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
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
            SheetButton("+ Add exercise", onClick = onAddClick, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.size(8.dp))
        SheetButton("Reset day to template", onClick = onResetClick, outlined = true, textColor = TextSecondary)
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
                    if (slot.isSuperset) "SS with ${slot.partnerTitle}" else slot.pattern?.let { patternLabel(it) } ?: "unknown exercise",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SheetButton("Edit", onClick = onEditClick, compact = true)
            Spacer(Modifier.size(8.dp))
            SheetButton("Remove", onClick = onRemoveClick, enabled = canRemove, compact = true, textColor = Error)
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
    val title = slot.title + (slot.partnerTitle?.let { " + $it" } ?: "")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        PickerHeader(title = title, onBack = onBack)
        Spacer(Modifier.size(12.dp))
        SheetButton(
            "Swap exercise",
            onClick = onSwapClick,
            enabled = slot.pattern != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        if (slot.partnerExerciseId == null) {
            SheetButton("Add superset", onClick = onSupersetClick, modifier = Modifier.fillMaxWidth())
        } else {
            SheetButton("Swap superset partner", onClick = onSupersetClick, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            SheetButton(
                "Remove superset",
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
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(patterns) { pattern ->
                SelectionCard(title = patternLabel(pattern), selected = false, onClick = { onPick(pattern) })
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
        title = "Swap — ${slot.title}",
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
private fun ExercisePickerScreen(
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
    var query by remember(key) { mutableStateOf("") }
    var equipmentFilter by remember(key) { mutableStateOf(defaultEquipment) }
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
            Text("No matching exercises.", color = TextFaint, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { entry ->
                    SelectionCard(
                        title = entry.name,
                        subtitle = entry.equipment.joinToString(", ") { equipmentLabel(it) },
                        selected = false,
                        onClick = { onPick(entry) },
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        // Navigates to the customExercise route (D1) with this picker's pattern
        // pre-filled; on save the route pops back and the picker re-shows with
        // the new custom entry visible, ranked after the catalog per
        // ExerciseCatalog.CUSTOM_SUBRANK.
        SheetButton("+ Create exercise", onClick = { onCreateExercise(pattern) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PickerHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(32.dp)
                .background(Surface2, RoundedCornerShape(8.dp))
                .pressable(onClickLabel = "Back", role = Role.Button, onClick = onBack, shape = RoundedCornerShape(8.dp))
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            Text("←", color = TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
        }
        Spacer(Modifier.size(10.dp))
        Text(title, color = TextPrimary, style = CardTitle)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Surface2, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) {
            Text("Search exercises", color = TextFaint, style = MaterialTheme.typography.bodyLarge)
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EquipmentFilterRow(
    options: List<Equipment>,
    selected: Set<Equipment>,
    accent: Color,
    onToggle: (Equipment) -> Unit,
) {
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
                    .pressable(onClick = { onToggle(equip) }, shape = RoundedCornerShape(50))
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
        title = { Text("Reset day to template?") },
        text = { Text("This regenerates the day from your setup wizard answers. Any swaps, adds, or removes you made here are discarded — logged history is not affected.") },
        confirmButton = { DialogAction("Reset", Error, onConfirm) },
        dismissButton = { DialogAction("Cancel", TextSecondary, onDismiss) },
    )
}

// --- small pieces --------------------------------------------------------------

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
    Box(
        modifier = modifier
            .disabledAlpha(enabled)
            .minimumInteractiveComponentSize()
            .then(if (outlined) Modifier.border(1.dp, Border, RoundedCornerShape(8.dp)) else Modifier)
            .background(if (outlined) Color.Transparent else Surface2, RoundedCornerShape(8.dp))
            .pressable(enabled = enabled, onClick = onClick, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 8.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelLarge)
    }
}

private fun patternLabel(pattern: MovementPattern): String = enumLabel(pattern.name)

private fun equipmentLabel(equipment: Equipment): String = enumLabel(equipment.name)

private fun enumLabel(name: String): String =
    name.split("_").joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
