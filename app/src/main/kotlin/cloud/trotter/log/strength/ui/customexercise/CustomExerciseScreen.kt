package cloud.trotter.log.strength.ui.customexercise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.CloseAction
import cloud.trotter.log.strength.ui.components.NumberField
import cloud.trotter.log.strength.ui.text.patternLabel
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.readableWidth

/**
 * Custom-exercise creation form (PLAN.md A4). Renders committed [state] and
 * forwards intents to [CustomExerciseActions]. Active target drafts report
 * their validity before Save can commit them and save the exercise.
 */
@Composable
fun CustomExerciseScreen(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    var targetValid by rememberSaveable(state.tracking, state.unit) { mutableStateOf(true) }
    var addedWeightValid by rememberSaveable(state.tracking, state.unit) { mutableStateOf(true) }
    val targetError = when {
        !targetValid -> when (state.tracking) {
            TrackingType.WEIGHTED -> R.string.form_custom_weight_required
            TrackingType.REPS -> R.string.form_custom_reps_required
            TrackingType.TIMED -> R.string.form_custom_hold_required
        }
        state.tracking == TrackingType.TIMED && !addedWeightValid -> R.string.form_custom_load_required
        else -> null
    }
    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            Header(actions)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = FooterHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NameField(state.name, actions.onNameChange) }
                item { TrackingSection(state, actions) }
                item {
                    PerHandAndTargetSection(
                        state, actions,
                        onTargetValidityChange = { targetValid = it },
                        onAddedWeightValidityChange = { addedWeightValid = it },
                    )
                }
                item { PatternSection(state, actions) }
                item { EquipmentSection(state, actions) }
                item { Spacer(Modifier.size(8.dp)) }
            }
            Footer(state, actions, targetError)
        }
    }
}

@Composable
private fun Header(actions: CustomExerciseActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.custom_exercise_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        CloseAction(onClick = actions.onCancel, contentDescription = "Cancel")
    }
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.custom_exercise_name_label)) },
            isError = value.isNotEmpty() && value.isBlank(),
            supportingText = if (value.isNotEmpty() && value.isBlank()) {
                { Text(stringResource(R.string.form_exercise_name_error)) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(stringResource(R.string.custom_exercise_name_hint)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = TextPrimary,
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = Border,
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedPlaceholderColor = TextSecondary,
                unfocusedPlaceholderColor = TextSecondary,
            ),
        )
    }
}

@Composable
private fun PatternSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.custom_exercise_pattern_label)) },
        supportingContent = { Text(state.pattern?.let { patternLabel(it) }
            ?: stringResource(R.string.form_choose_pattern)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { choosing = true },
    )
    if (choosing) {
        AppAlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.custom_exercise_pattern_label)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).selectableGroup()) {
                    items(MovementPattern.entries) { pattern ->
                        ListItem(
                            headlineContent = { Text(patternLabel(pattern)) },
                            leadingContent = { RadioButton(selected = state.pattern == pattern, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = if (state.pattern == pattern)
                                MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                                selected = state.pattern == pattern, role = Role.RadioButton,
                                onClick = { actions.onPatternChange(pattern); choosing = false },
                            ),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.custom_exercise_cancel_button)) }
            },
        )
    }
}

@Composable
private fun EquipmentSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    Column {
        Text(stringResource(R.string.custom_exercise_equipment_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Equipment.entries.forEach { equip ->
                ListItem(
                    headlineContent = { Text(equipmentLabel(equip)) },
                    leadingContent = { Checkbox(checked = equip in state.equipment, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = if (equip in state.equipment) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = equip in state.equipment, role = Role.Checkbox, onValueChange = { actions.onEquipmentToggle(equip) }),
                )
            }
        }
    }
}

/**
 * The tracking-type choice (tracking-types §2.1, §5.6): what the exercise's
 * GOAL is and how its sets log — weight×reps, reps only, or a timed hold.
 * M3 radio rows give each tracking type one selectable target.
 */
@Composable
private fun TrackingSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.custom_exercise_tracking_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.custom_exercise_weighted_title)) },
                supportingContent = { Text(stringResource(R.string.custom_exercise_weighted_description)) },
                leadingContent = { RadioButton(selected = state.tracking == TrackingType.WEIGHTED, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = if (state.tracking == TrackingType.WEIGHTED) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).selectable(selected = state.tracking == TrackingType.WEIGHTED, role = Role.RadioButton, onClick = { focusManager.clearFocus(); actions.onTrackingChange(TrackingType.WEIGHTED) }),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.custom_exercise_reps_title)) },
                supportingContent = { Text(stringResource(R.string.custom_exercise_reps_description)) },
                leadingContent = { RadioButton(selected = state.tracking == TrackingType.REPS, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = if (state.tracking == TrackingType.REPS) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).selectable(selected = state.tracking == TrackingType.REPS, role = Role.RadioButton, onClick = { focusManager.clearFocus(); actions.onTrackingChange(TrackingType.REPS) }),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.custom_exercise_timed_title)) },
                supportingContent = { Text(stringResource(R.string.custom_exercise_timed_description)) },
                leadingContent = { RadioButton(selected = state.tracking == TrackingType.TIMED, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = if (state.tracking == TrackingType.TIMED) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).selectable(selected = state.tracking == TrackingType.TIMED, role = Role.RadioButton, onClick = { focusManager.clearFocus(); actions.onTrackingChange(TrackingType.TIMED) }),
            )
        }
    }
}

/** The per-hand toggle plus whichever target input matches [state.tracking]
 *  (§3): a starting GOAL weight, a target rep count, or a target hold with an
 *  optional added load — never all three at once. */
@Composable
private fun PerHandAndTargetSection(
    state: CustomExerciseUiState,
    actions: CustomExerciseActions,
    onTargetValidityChange: (Boolean) -> Unit,
    onAddedWeightValidityChange: (Boolean) -> Unit,
) {
    AppCard {
        ListItem(
            headlineContent = { Text(stringResource(R.string.custom_exercise_per_hand_label)) },
            trailingContent = { Switch(checked = state.perHand, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().toggleable(value = state.perHand, role = Role.Switch, onValueChange = actions.onPerHandChange),
        )
    }
    Spacer(Modifier.size(12.dp))
    when (state.tracking) {
        TrackingType.WEIGHTED -> WeightTargetCard(
            label = stringResource(R.string.custom_exercise_starting_weight_label, state.unit.name.lowercase()),
            weightDisplay = state.weightDisplay,
            unit = state.unit,
            onWeightChange = actions.onWeightChange,
            onValidityChange = onTargetValidityChange,
        )
        TrackingType.REPS -> TargetField(
            label = stringResource(R.string.custom_exercise_target_reps_label),
            value = state.targetReps,
            onValueChange = actions.onTargetRepsChange,
            step = 1,
            onValidityChange = onTargetValidityChange,
        )
        TrackingType.TIMED -> {
            TargetField(
                label = stringResource(R.string.custom_exercise_target_hold_label),
                value = state.targetSeconds,
                onValueChange = actions.onTargetSecondsChange,
                step = 5,
                onValidityChange = onTargetValidityChange,
            )
            Spacer(Modifier.size(12.dp))
            WeightTargetCard(
                label = stringResource(R.string.custom_exercise_added_load_label, state.unit.name.lowercase()),
                weightDisplay = state.addedWeightDisplay,
                unit = state.unit,
                onWeightChange = actions.onAddedWeightChange,
                onValidityChange = onAddedWeightValidityChange,
            )
        }
    }
}

@Composable
private fun WeightTargetCard(
    label: String,
    weightDisplay: Double,
    unit: WeightUnit,
    onWeightChange: (Double) -> Unit,
    onValidityChange: (Boolean) -> Unit,
) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            NumberField(
                label = label,
                decimalInput = true,
                weightInput = true,
                value = weightDisplay,
                onValueChange = onWeightChange,
                round = { WeightStepper.round(it, unit) },
                format = WeightStepper::format,
                onValidityChange = onValidityChange,
            )
        }
    }
}

@Composable
private fun TargetField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    step: Int,
    onValidityChange: (Boolean) -> Unit,
) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            NumberField(
                label = label,
                value = value.toDouble(),
                onValueChange = { onValueChange(it.toInt()) },
                minValue = step.toDouble(),
                format = { it.toInt().toString() },
                onValidityChange = onValidityChange,
            )
        }
    }
}

@Composable
private fun Footer(
    state: CustomExerciseUiState,
    actions: CustomExerciseActions,
    targetError: Int?,
) {
    val focusManager = LocalFocusManager.current
    val canSave = state.canSave && targetError == null
    Column(Modifier.fillMaxWidth().background(Background)) {
        HorizontalDivider(thickness = FooterDividerThickness, color = Border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = FooterVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = actions.onCancel,
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(vertical = FooterButtonVerticalPadding),
            ) {
                FooterButtonLabel(stringResource(R.string.custom_exercise_cancel_button))
            }
            Button(
                enabled = canSave,
                modifier = Modifier.weight(2f),
                onClick = { focusManager.clearFocus(); actions.onSave() },
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(vertical = FooterButtonVerticalPadding),
            ) {
                FooterButtonLabel(stringResource(R.string.custom_exercise_save_button))
            }
        }
        val saveError = when {
            state.name.isBlank() -> R.string.operation_custom_exercise_name_required
            state.pattern == null -> R.string.form_pattern_required
            else -> targetError
        }
        if (saveError != null) Text(stringResource(saveError),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FooterButtonLabel(label: String) {
    Text(
        label,
        style = DoneButtonLabel,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.heightIn(min = FooterLabelMinHeight),
    )
}

private fun equipmentLabel(equipment: Equipment): String = equipment.name
    .split("_")
    .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

private val FooterDividerThickness = 1.dp
private val FooterVerticalPadding = 10.dp
private val FooterButtonVerticalPadding = 8.dp
private val FooterLabelMinHeight = 36.dp
private val FooterHeight = FooterDividerThickness +
    FooterVerticalPadding * 2 +
    FooterButtonVerticalPadding * 2 +
    FooterLabelMinHeight
@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun CustomExerciseScreenPreview() {
    AppTheme {
        CustomExerciseScreen(
            state = CustomExerciseUiState(name = "Cable Hack Squat", pattern = MovementPattern.SQUAT_BILATERAL),
            actions = CustomExerciseActions(
                onNameChange = {}, onPatternChange = {}, onEquipmentToggle = {},
                onPerHandChange = {}, onTrackingChange = {}, onWeightChange = {},
                onTargetRepsChange = {}, onTargetSecondsChange = {}, onAddedWeightChange = {},
                onSave = {}, onCancel = {},
            ),
        )
    }
}
