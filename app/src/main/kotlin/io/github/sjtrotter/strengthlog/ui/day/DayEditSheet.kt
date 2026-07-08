package io.github.sjtrotter.strengthlog.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.SelectionCard
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Done
import io.github.sjtrotter.strengthlog.ui.theme.Error
import io.github.sjtrotter.strengthlog.ui.theme.Surface
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary

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
fun DayEditSheet(state: DayEditUiState, actions: DayEditActions, accent: Color, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var swapSlotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingPattern by rememberSaveable { mutableStateOf(false) }
    var addPatternName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Surface) {
        val swapSlot = state.slots.firstOrNull { it.programExerciseId == swapSlotId }
        when {
            swapSlot != null && swapSlot.pattern != null -> ExercisePickerScreen(
                key = "swap-${swapSlot.programExerciseId}",
                title = "Swap — ${swapSlot.title}",
                candidates = state.catalog.substitutionsFor(swapSlot.exerciseId),
                defaultEquipment = state.defaultEquipmentFilter,
                accent = accent,
                onPick = { entry -> actions.onSwap(swapSlot.position, entry.id); swapSlotId = null },
                onBack = { swapSlotId = null },
            )
            pickingPattern -> PatternPickerScreen(
                patterns = state.catalog.entries.map { it.pattern }.distinct().sortedBy { it.ordinal },
                onPick = { pattern -> addPatternName = pattern.name; pickingPattern = false },
                onBack = { pickingPattern = false },
            )
            addPatternName != null -> {
                val pattern = MovementPattern.valueOf(addPatternName!!)
                ExercisePickerScreen(
                    key = "add-${pattern.name}",
                    title = "Add — ${patternLabel(pattern)}",
                    candidates = state.catalog.byPattern(pattern),
                    defaultEquipment = state.defaultEquipmentFilter,
                    accent = accent,
                    onPick = { entry -> actions.onAdd(entry.id); addPatternName = null },
                    onBack = { addPatternName = null },
                )
            }
            else -> DaySlotList(
                state = state,
                accent = accent,
                onSwapClick = { slotId -> swapSlotId = slotId },
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
    onSwapClick: (Long) -> Unit,
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
                    onSwapClick = { onSwapClick(slot.programExerciseId) },
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
    onSwapClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(slot.title, color = TextPrimary, style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp))
                Text(
                    if (slot.isSuperset) "superset" else slot.pattern?.let { patternLabel(it) } ?: "unknown exercise",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SheetButton("Swap", onClick = onSwapClick, enabled = slot.pattern != null, compact = true)
            Spacer(Modifier.size(8.dp))
            SheetButton("Remove", onClick = onRemoveClick, enabled = canRemove, compact = true, textColor = Error)
        }
    }
}

// --- page 2: pick a pattern (add flow only) -----------------------------------

@Composable
private fun PatternPickerScreen(patterns: List<MovementPattern>, onPick: (MovementPattern) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        PickerHeader(title = "Add exercise — pick a pattern", onBack = onBack)
        Spacer(Modifier.size(8.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(patterns) { pattern ->
                SelectionCard(title = patternLabel(pattern), selected = false, onClick = { onPick(pattern) })
            }
        }
    }
}

// --- page 3: substitution / add candidate picker (spec §8.3, PLAN.md A4) -----

@Composable
private fun ExercisePickerScreen(
    key: String,
    title: String,
    candidates: List<ExerciseEntry>,
    defaultEquipment: Set<Equipment>,
    accent: Color,
    onPick: (ExerciseEntry) -> Unit,
    onBack: () -> Unit,
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
        // TODO(#13): once the customExercise route lands, add a "+ Create
        // exercise" row here (navigates to it with this picker's pattern
        // pre-filled; on save the picker re-shows with the new custom entry
        // visible, ranked after the catalog per ExerciseCatalog.CUSTOM_SUBRANK).
        // Left out entirely until then — no dead click target on this branch.
    }
}

@Composable
private fun PickerHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Surface2, RoundedCornerShape(8.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("←", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.size(10.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp))
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
                    .clip(RoundedCornerShape(50))
                    .background(if (isOn) accent.copy(alpha = 0.18f) else Surface2, RoundedCornerShape(50))
                    .border(1.dp, if (isOn) accent else Border, RoundedCornerShape(50))
                    .clickable { onToggle(equip) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(equipmentLabel(equip), color = if (isOn) accent else TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ResetToTemplateDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Reset day to template?") },
        text = { Text("This regenerates the day from your setup wizard answers. Any swaps, adds, or removes you made here are discarded — logged history is not affected.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Reset", color = Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
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
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .then(if (outlined) Modifier.border(1.dp, Border, RoundedCornerShape(8.dp)) else Modifier)
            .background(if (outlined) Color.Transparent else Surface2, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 8.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor.copy(alpha = alpha), style = MaterialTheme.typography.labelLarge)
    }
}

private fun patternLabel(pattern: MovementPattern): String = enumLabel(pattern.name)

private fun equipmentLabel(equipment: Equipment): String = enumLabel(equipment.name)

private fun enumLabel(name: String): String =
    name.split("_").joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
