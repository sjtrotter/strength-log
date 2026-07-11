package io.github.sjtrotter.strengthlog.ui.customexercise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.SelectionCard
import io.github.sjtrotter.strengthlog.ui.components.Stepper
import io.github.sjtrotter.strengthlog.ui.components.SwitchToggle
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.DoneButtonLabel
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent
import io.github.sjtrotter.strengthlog.ui.theme.onDayAccent

/**
 * Custom-exercise creation form (PLAN.md A4, brief #13). Stateless: renders
 * [state] and forwards every intent to [CustomExerciseActions], same shape as
 * [io.github.sjtrotter.strengthlog.ui.wizard.WizardScreen]. Day index 0's
 * accent stands in as the app's one "primary" highlight here too — this
 * screen isn't day-scoped either.
 */
@Composable
fun CustomExerciseScreen(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    val accent = dayAccent(0)
    val onAccent = onDayAccent(0)

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(actions)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
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
        Text("New exercise", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(onClickLabel = "Cancel", role = Role.Button, onClick = actions.onCancel)
                .semantics { contentDescription = "Cancel" },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = TextSecondary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    Column {
        Text("Name", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface2, NameFieldShape)
                .border(1.dp, Border, NameFieldShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) {
                Text("e.g. Cable Hack Squat", color = TextFaint, style = NameFieldStyle)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = NameFieldStyle.copy(color = TextPrimary),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PatternSection(state: CustomExerciseUiState, actions: CustomExerciseActions) {
    Column {
        Text("Movement pattern", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
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
        Text("Equipment", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Equipment.entries.forEach { equip ->
                SelectionCard(
                    title = equipmentLabel(equip),
                    selected = equip in state.equipment,
                    onClick = { actions.onEquipmentToggle(equip) },
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
    Column {
        Text("How is it tracked?", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionCard(
                title = "Weighted",
                subtitle = "Load × reps — barbell, dumbbell, machine, cable.",
                selected = state.tracking == TrackingType.WEIGHTED,
                onClick = { actions.onTrackingChange(TrackingType.WEIGHTED) },
            )
            SelectionCard(
                title = "Reps",
                subtitle = "Bodyweight, counted reps — push-up, pull-up, sit-up.",
                selected = state.tracking == TrackingType.REPS,
                onClick = { actions.onTrackingChange(TrackingType.REPS) },
            )
            SelectionCard(
                title = "Timed",
                subtitle = "A hold or carry, logged in seconds — plank, wall sit.",
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
            label = "Per hand (dumbbell/unilateral)",
            checked = state.perHand,
            onCheckedChange = actions.onPerHandChange,
        )
    }
    Spacer(Modifier.size(12.dp))
    when (state.tracking) {
        TrackingType.WEIGHTED -> WeightTargetCard(
            label = "Starting weight (${state.unit.name.lowercase()})",
            weightDisplay = state.weightDisplay,
            unit = state.unit,
            onWeightChange = actions.onWeightChange,
        )
        TrackingType.REPS -> TargetStepperCard(
            label = "Target reps",
            value = state.targetReps,
            onValueChange = actions.onTargetRepsChange,
            step = 1,
            decreaseDescription = "Decrease target reps",
            increaseDescription = "Increase target reps",
        )
        TrackingType.TIMED -> {
            TargetStepperCard(
                label = "Target hold (seconds)",
                value = state.targetSeconds,
                onValueChange = actions.onTargetSecondsChange,
                step = 5,
                decreaseDescription = "Decrease target hold",
                increaseDescription = "Increase target hold",
            )
            Spacer(Modifier.size(12.dp))
            WeightTargetCard(
                label = "Added load, optional (${state.unit.name.lowercase()})",
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
                decreaseDescription = "Decrease $label",
                increaseDescription = "Increase $label",
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
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FooterButton(
                label = "CANCEL",
                fill = Border,
                textColor = TextPrimary,
                modifier = Modifier.weight(1f),
                onClick = actions.onCancel,
            )
            FooterButton(
                label = "SAVE",
                fill = accent,
                textColor = onAccent,
                enabled = state.canSave,
                modifier = Modifier.weight(2f),
                onClick = actions.onSave,
            )
        }
    }
}

@Composable
private fun FooterButton(
    label: String,
    fill: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(fill, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

private fun patternLabel(pattern: MovementPattern): String = when (pattern) {
    MovementPattern.SQUAT_BILATERAL -> "Squat (bilateral)"
    MovementPattern.SINGLE_LEG -> "Single-leg"
    MovementPattern.HINGE -> "Hinge"
    MovementPattern.KNEE_FLEXION -> "Knee flexion (leg curl)"
    MovementPattern.KNEE_EXTENSION -> "Knee extension (leg extension)"
    MovementPattern.H_PUSH -> "Horizontal push"
    MovementPattern.V_PUSH -> "Vertical push"
    MovementPattern.H_PULL -> "Horizontal pull"
    MovementPattern.V_PULL -> "Vertical pull"
    MovementPattern.SIDE_DELT -> "Side delt"
    MovementPattern.REAR_DELT -> "Rear delt"
    MovementPattern.BICEPS -> "Biceps"
    MovementPattern.TRICEPS -> "Triceps"
    MovementPattern.CALF_GASTROC -> "Calf (gastroc)"
    MovementPattern.CALF_SOLEUS -> "Calf (soleus)"
    MovementPattern.CORE_ANTI_EXT -> "Core (anti-extension)"
    MovementPattern.CORE_ANTI_ROT -> "Core (anti-rotation)"
    MovementPattern.CORE_FLEX -> "Core (flexion)"
    MovementPattern.CARDIO -> "Cardio"
}

private fun equipmentLabel(equipment: Equipment): String = equipment.name
    .split("_")
    .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

private val NameFieldShape = RoundedCornerShape(10.dp)
private val NameFieldStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge

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
