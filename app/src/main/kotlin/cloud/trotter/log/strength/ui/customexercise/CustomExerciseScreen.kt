package cloud.trotter.log.strength.ui.customexercise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.CloseAction
import cloud.trotter.log.strength.ui.components.SelectionCard
import cloud.trotter.log.strength.ui.components.SelectionMode
import cloud.trotter.log.strength.ui.components.Stepper
import cloud.trotter.log.strength.ui.components.SwitchToggle
import cloud.trotter.log.strength.ui.components.disabledAlpha
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.BorderStrong
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import cloud.trotter.log.strength.ui.theme.readableWidth

/**
 * Custom-exercise creation form (PLAN.md A4, brief #13). Stateless: renders
 * [state] and forwards every intent to [CustomExerciseActions], same shape as
 * [cloud.trotter.log.strength.ui.wizard.WizardScreen]. Day index 0's
 * accent stands in as the app's one "primary" highlight here too — this
 * screen isn't day-scoped either.
 */
@Composable
fun CustomExerciseScreen(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    val accent = dayAccent(0)
    val onAccent = onDayAccent(0)

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            Header(actions)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = FooterHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NameField(state.name, actions.onNameChange) }
                item { PatternSection(state, actions) }
                item { EquipmentSection(state, actions) }
                item { TrackingSection(state, actions) }
                item { PerHandAndTargetSection(state, actions) }
                item { Spacer(Modifier.size(8.dp)) }
            }
            Footer(state, accent, onAccent, actions)
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
        Text(stringResource(R.string.custom_exercise_name_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(stringResource(R.string.custom_exercise_name_hint)) },
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
}

@Composable
private fun PatternSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.custom_exercise_pattern_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MovementPattern.entries.forEach { pattern ->
                SelectionCard(
                    title = patternLabel(pattern),
                    selected = state.pattern == pattern,
                    onClick = { actions.onPatternChange(pattern) },
                )
            }
        }
    }
}

@Composable
private fun EquipmentSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    Column {
        Text(stringResource(R.string.custom_exercise_equipment_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Equipment.entries.forEach { equip ->
                SelectionCard(
                    title = equipmentLabel(equip),
                    selected = equip in state.equipment,
                    onClick = { actions.onEquipmentToggle(equip) },
                    mode = SelectionMode.Check,
                )
            }
        }
    }
}

/**
 * The tracking-type choice (tracking-types §2.1, §5.6): what the exercise's
 * GOAL is and how its sets log — weight×reps, reps only, or a timed hold.
 * Three [SelectionCard]s, same pattern as [PatternSection], so the choice
 * reads as a first-class question rather than a buried toggle.
 */
@Composable
private fun TrackingSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.custom_exercise_tracking_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionCard(
                title = stringResource(R.string.custom_exercise_weighted_title),
                subtitle = stringResource(R.string.custom_exercise_weighted_description),
                selected = state.tracking == TrackingType.WEIGHTED,
                onClick = { actions.onTrackingChange(TrackingType.WEIGHTED) },
            )
            SelectionCard(
                title = stringResource(R.string.custom_exercise_reps_title),
                subtitle = stringResource(R.string.custom_exercise_reps_description),
                selected = state.tracking == TrackingType.REPS,
                onClick = { actions.onTrackingChange(TrackingType.REPS) },
            )
            SelectionCard(
                title = stringResource(R.string.custom_exercise_timed_title),
                subtitle = stringResource(R.string.custom_exercise_timed_description),
                selected = state.tracking == TrackingType.TIMED,
                onClick = { actions.onTrackingChange(TrackingType.TIMED) },
            )
        }
    }
}

/** The per-hand toggle plus whichever target input matches [state.tracking]
 *  (§3): a starting GOAL weight, a target rep count, or a target hold with an
 *  optional added load — never all three at once. */
@Composable
private fun PerHandAndTargetSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    AppCard {
        SwitchToggle(
            label = stringResource(R.string.custom_exercise_per_hand_label),
            checked = state.perHand,
            onCheckedChange = actions.onPerHandChange,
        )
    }
    Spacer(Modifier.size(12.dp))
    when (state.tracking) {
        TrackingType.WEIGHTED -> WeightTargetCard(
            label = stringResource(R.string.custom_exercise_starting_weight_label, state.unit.name.lowercase()),
            weightDisplay = state.weightDisplay,
            unit = state.unit,
            onWeightChange = actions.onWeightChange,
        )
        TrackingType.REPS -> TargetStepperCard(
            label = stringResource(R.string.custom_exercise_target_reps_label),
            value = state.targetReps,
            onValueChange = actions.onTargetRepsChange,
            step = 1,
            decreaseDescription = stringResource(R.string.custom_exercise_decrease_target_reps_action),
            increaseDescription = stringResource(R.string.custom_exercise_increase_target_reps_action),
        )
        TrackingType.TIMED -> {
            TargetStepperCard(
                label = stringResource(R.string.custom_exercise_target_hold_label),
                value = state.targetSeconds,
                onValueChange = actions.onTargetSecondsChange,
                step = 5,
                decreaseDescription = stringResource(R.string.custom_exercise_decrease_target_hold_action),
                increaseDescription = stringResource(R.string.custom_exercise_increase_target_hold_action),
            )
            Spacer(Modifier.size(12.dp))
            WeightTargetCard(
                label = stringResource(R.string.custom_exercise_added_load_label, state.unit.name.lowercase()),
                weightDisplay = state.addedWeightDisplay,
                unit = state.unit,
                onWeightChange = actions.onAddedWeightChange,
            )
        }
    }
}

@Composable
private fun WeightTargetCard(label: String, weightDisplay: Double, unit: WeightUnit, onWeightChange: (Double) -> Unit) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = weightDisplay,
                onValueChange = onWeightChange,
                step = { WeightStepper.increment(it, unit) },
                round = { WeightStepper.round(it, unit) },
                format = WeightStepper::format,
                decreaseDescription = stringResource(R.string.custom_exercise_decrease_value_action, label),
                increaseDescription = stringResource(R.string.custom_exercise_increase_value_action, label),
            )
        }
    }
}

@Composable
private fun TargetStepperCard(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    step: Int,
    decreaseDescription: String,
    increaseDescription: String,
) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = value.toDouble(),
                onValueChange = { onValueChange(it.toInt()) },
                step = { step.toDouble() },
                minValue = step.toDouble(),
                format = { it.toInt().toString() },
                decreaseDescription = decreaseDescription,
                increaseDescription = increaseDescription,
            )
        }
    }
}

@Composable
private fun Footer(
    state: CustomExerciseUiState,
    accent: androidx.compose.ui.graphics.Color,
    onAccent: androidx.compose.ui.graphics.Color,
    actions: CustomExerciseActions,
) {
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
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Border,
                    contentColor = TextPrimary,
                ),
                contentPadding = PaddingValues(vertical = FooterButtonVerticalPadding),
            ) {
                FooterButtonLabel(stringResource(R.string.custom_exercise_cancel_button))
            }
            Button(
                enabled = state.canSave,
                modifier = Modifier.weight(2f).disabledAlpha(state.canSave),
                onClick = actions.onSave,
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = onAccent,
                    // Preserve the existing whole-control 40% treatment: M3
                    // owns the disabled interaction, this modifier owns its
                    // established rendering.
                    disabledContainerColor = accent,
                    disabledContentColor = onAccent,
                ),
                contentPadding = PaddingValues(vertical = FooterButtonVerticalPadding),
            ) {
                FooterButtonLabel(stringResource(R.string.custom_exercise_save_button))
            }
        }
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

@Composable
private fun patternLabel(pattern: MovementPattern): String = stringResource(when (pattern) {
    MovementPattern.SQUAT_BILATERAL -> R.string.custom_exercise_pattern_squat_bilateral
    MovementPattern.SINGLE_LEG -> R.string.custom_exercise_pattern_single_leg
    MovementPattern.HINGE -> R.string.custom_exercise_pattern_hinge
    MovementPattern.KNEE_FLEXION -> R.string.custom_exercise_pattern_knee_flexion
    MovementPattern.KNEE_EXTENSION -> R.string.custom_exercise_pattern_knee_extension
    MovementPattern.H_PUSH -> R.string.custom_exercise_pattern_horizontal_push
    MovementPattern.V_PUSH -> R.string.custom_exercise_pattern_vertical_push
    MovementPattern.H_PULL -> R.string.custom_exercise_pattern_horizontal_pull
    MovementPattern.V_PULL -> R.string.custom_exercise_pattern_vertical_pull
    MovementPattern.SIDE_DELT -> R.string.custom_exercise_pattern_side_delt
    MovementPattern.REAR_DELT -> R.string.custom_exercise_pattern_rear_delt
    MovementPattern.BICEPS -> R.string.custom_exercise_pattern_biceps
    MovementPattern.TRICEPS -> R.string.custom_exercise_pattern_triceps
    MovementPattern.CALF_GASTROC -> R.string.custom_exercise_pattern_calf_gastroc
    MovementPattern.CALF_SOLEUS -> R.string.custom_exercise_pattern_calf_soleus
    MovementPattern.CORE_ANTI_EXT -> R.string.custom_exercise_pattern_core_anti_extension
    MovementPattern.CORE_ANTI_ROT -> R.string.custom_exercise_pattern_core_anti_rotation
    MovementPattern.CORE_FLEX -> R.string.custom_exercise_pattern_core_flexion
    MovementPattern.CARDIO -> R.string.custom_exercise_pattern_cardio
})

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
