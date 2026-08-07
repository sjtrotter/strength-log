package cloud.trotter.log.strength.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.SelectionCard
import cloud.trotter.log.strength.ui.components.Stepper
import cloud.trotter.log.strength.ui.components.SwitchToggle
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.Surface
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TabLetter
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.readableWidth

/**
 * The setup screen (spec §8.4): steppers/selectors for the GOAL inputs, a live
 * preview of the four main-lift GOALs those inputs drive, cardio prefs, the
 * lb/kg display toggle, and the destructive "re-run wizard" escape hatch.
 * Stateless: renders [state] and forwards every intent to [SetupActions] —
 * every field commits immediately (no draft, unlike the wizard), so leaving
 * this screen never loses an edit.
 */
@Composable
fun SetupScreen(state: SetupUiState, actions: SetupActions) {
    var showRerunConfirm by rememberSaveable { mutableStateOf(false) }
    var showRestResetConfirm by rememberSaveable { mutableStateOf(false) }
    val accent = dayAccent(0)

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            SetupHeader(actions.onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                item { SectionHeader("TRAINING") }
                item { GoalPreviewCard(state.goalPreview, accent) }
                item { BodyweightCard(state.bodyweightDisplay, state.unit, actions.onBodyweightChange) }
                item { AgeCard(state.config.age, actions.onAgeChange) }
                item { LevelSection(state.config.level, actions.onLevelChange) }
                item { EmphasisSection(state.config.emphasis, actions.onEmphasisChange) }
                item { CardioSection(state.cardio, actions) }
                item { SectionHeader("DISPLAY") }
                item { UnitCard(state.unit, actions.onUnitToggle) }
                item { SectionHeader("WATCH") }
                item {
                    RestTimerSection(
                        state.restTimerEnabled,
                        state.restCategories,
                        actions,
                        onResetDefaults = { showRestResetConfirm = true },
                    )
                }
                item { SectionHeader("DATA") }
                item { CreateCustomExerciseButton(accent, actions.onCreateCustomExercise) }
                item { DataBackupButton(accent, actions.onOpenBackup) }
                item { SectionHeader("ABOUT") }
                item { LicensesButton(actions.onOpenLicenses) }
                item { Spacer(Modifier.size(20.dp)) }
                item { RerunWizardButton(onClick = { showRerunConfirm = true }) }
                item { Spacer(Modifier.size(8.dp)) }
            }
        }
        if (showRerunConfirm) {
            RerunConfirmDialog(
                onConfirm = {
                    showRerunConfirm = false
                    actions.onRerunWizard()
                },
                onDismiss = { showRerunConfirm = false },
            )
        }
        if (showRestResetConfirm) {
            RestDefaultsConfirmDialog(
                onConfirm = {
                    showRestResetConfirm = false
                    actions.onRestOverridesReset()
                },
                onDismiss = { showRestResetConfirm = false },
            )
        }
    }
}

@Composable
private fun SetupHeader(onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackButton(onBack)
            Text("SETUP", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(40.dp, 40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .pressable(
                onClickLabel = "Back",
                role = Role.Button,
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
            )
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = TextSecondary, style = TabLetter.copy(fontSize = 20.sp), modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * A section rule: caps overline over a hairline, the same editorial divider
 * Today uses between its blocks. Plain text — never interactive, so it adds no
 * touch target to a screen that is otherwise all controls.
 */
@Composable
private fun SectionHeader(label: String) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.size(8.dp))
        Text(label, color = TextFaint, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

// --- live GOAL preview (spec §8.4) -------------------------------------------

@Composable
private fun GoalPreviewCard(items: List<GoalPreviewItem>, accent: Color) {
    AppCard {
        Text("YOUR MAIN-LIFT GOALS", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(10.dp))
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(item.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.display, color = accent, style = DisplayXl)
                    if (item.perHand) {
                        Text("/hand", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (index != items.lastIndex) Spacer(Modifier.size(8.dp))
        }
    }
}

// --- bodyweight / age steppers ------------------------------------------------

@Composable
private fun BodyweightCard(displayValue: Double, unit: WeightUnit, onChange: (Double) -> Unit) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Bodyweight (${unit.name.lowercase()})", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = displayValue,
                onValueChange = onChange,
                step = { WeightStepper.increment(it, unit) },
                minValue = 1.0,
                format = WeightStepper::format,
                round = { WeightStepper.round(it, unit) },
                decreaseDescription = "Decrease bodyweight",
                increaseDescription = "Increase bodyweight",
            )
        }
    }
}

@Composable
private fun AgeCard(age: Int, onChange: (Int) -> Unit) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Age", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = age.toDouble(),
                onValueChange = { onChange(it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
                decreaseDescription = "Decrease age",
                increaseDescription = "Increase age",
            )
        }
    }
}

// --- level / emphasis selectors (same copy as the wizard's About-you/Emphasis steps) ---

@Composable
private fun LevelSection(level: ExperienceLevel, onChange: (ExperienceLevel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Experience level", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        ExperienceLevel.entries.forEach { entry ->
            SelectionCard(title = levelLabel(entry), selected = level == entry, onClick = { onChange(entry) })
        }
    }
}

private fun levelLabel(level: ExperienceLevel): String = when (level) {
    ExperienceLevel.NOVICE -> "Novice"
    ExperienceLevel.INTERMEDIATE -> "Intermediate"
    ExperienceLevel.ADVANCED -> "Advanced"
}

@Composable
private fun EmphasisSection(emphasis: GoalEmphasis, onChange: (GoalEmphasis) -> Unit) {
    val options = listOf(
        GoalEmphasis.STRENGTH to ("Strength-leaning" to "Fewer reps, more weight on the mains."),
        GoalEmphasis.BALANCED to ("Balanced strength + muscle" to "Even mix of heavy work and volume."),
        GoalEmphasis.PHYSIQUE to ("Physique-leaning" to "More volume and isolation work."),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Training emphasis", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        options.forEach { (value, copy) ->
            SelectionCard(
                title = copy.first,
                subtitle = copy.second,
                selected = emphasis == value,
                onClick = { onChange(value) },
            )
        }
    }
}

// --- cardio prefs (same shape as the wizard's Cardio step) --------------------

@Composable
private fun CardioSection(cardio: CardioPrefs, actions: SetupActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cardio", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        CardioMode.entries.forEach { mode ->
            SelectionCard(title = cardioModeLabel(mode), selected = cardio.mode == mode, onClick = { actions.onCardioModeChange(mode) })
        }
        if (cardio.mode != CardioMode.NONE) {
            Spacer(Modifier.size(2.dp))
            Text("Placement", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            CardioPlacement.entries.filter { it != CardioPlacement.NONE }.forEach { placement ->
                SelectionCard(
                    title = cardioPlacementLabel(placement),
                    selected = cardio.placement == placement,
                    onClick = { actions.onCardioPlacementChange(placement) },
                )
            }
            Spacer(Modifier.size(2.dp))
            AppCard {
                SwitchToggle(label = "Keep me 5k-ready", checked = cardio.fiveKGoal, onCheckedChange = actions.onFiveKChange)
            }
        }
    }
}

private fun cardioModeLabel(mode: CardioMode): String = when (mode) {
    CardioMode.OUTDOOR_RUN -> "Outdoor run"
    CardioMode.TREADMILL -> "Treadmill"
    CardioMode.LOW_IMPACT -> "Bike / elliptical"
    CardioMode.NONE -> "None"
}

private fun cardioPlacementLabel(placement: CardioPlacement): String = when (placement) {
    CardioPlacement.FINISHERS -> "Finishers after lifting"
    CardioPlacement.SEPARATE_DAYS -> "Separate days"
    CardioPlacement.BOTH -> "Both"
    CardioPlacement.NONE -> "None"
}

// --- unit toggle (A5) ---------------------------------------------------------

@Composable
private fun UnitCard(unit: WeightUnit, onToggle: (WeightUnit) -> Unit) {
    AppCard {
        SwitchToggle(
            label = "Display weights in kilograms",
            checked = unit == WeightUnit.KG,
            onCheckedChange = { useKg -> onToggle(if (useKg) WeightUnit.KG else WeightUnit.LB) },
        )
    }
}

// --- rest timer (watch W2c: master toggle + per-category overrides) ----------

@Composable
private fun RestTimerSection(
    enabled: Boolean,
    categories: List<RestCategoryUiState>,
    actions: SetupActions,
    onResetDefaults: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppCard {
            SwitchToggle(
                label = "Rest timer on watch",
                checked = enabled,
                onCheckedChange = actions.onRestTimerEnabledChange,
            )
        }
        if (enabled) {
            AppCard {
                categories.forEachIndexed { index, row ->
                    RestCategoryRow(row, onChange = { seconds -> actions.onRestOverrideChange(row.category, seconds) })
                    if (index != categories.lastIndex) Spacer(Modifier.size(10.dp))
                }
            }
            ResetRestDefaultsRow(onResetDefaults)
        }
    }
}

@Composable
private fun RestCategoryRow(row: RestCategoryUiState, onChange: (Int) -> Unit) {
    val label = restCategoryLabel(row.category)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Stepper(
            value = row.seconds.toDouble(),
            onValueChange = { onChange(it.toInt()) },
            step = { REST_STEP_SECONDS },
            minValue = 0.0,
            // Doubles as the stepper's clamp (Stepper has no maxValue param):
            // every tap's result is coerced into RestPolicy's accepted range.
            round = { it.coerceIn(0.0, RestPolicy.MAX_REST_SECONDS.toDouble()) },
            format = { SetupStateBuilder.restTimerLabel(it.toInt()) },
            valueColor = if (row.seconds == 0) TextFaint else TextPrimary,
            decreaseDescription = "Decrease $label rest",
            increaseDescription = "Increase $label rest",
        )
    }
}

private fun restCategoryLabel(category: RestCategory): String = when (category) {
    RestCategory.RAMP -> "Warm-up"
    RestCategory.TOP -> "Top set"
    RestCategory.BACKOFF -> "Back-off"
    RestCategory.WORK -> "Accessory work"
    RestCategory.LIGHT -> "Bodyweight · timed"
}

private const val REST_STEP_SECONDS = 15.0

@Composable
private fun ResetRestDefaultsRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .pressable(onClick = onClick, shape = RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("RESET DEFAULTS", color = TextSecondary, style = DoneButtonLabel)
    }
}

// --- create custom exercise (route #13, D1: reachable from Setup and the day-edit picker) ---

@Composable
private fun CreateCustomExerciseButton(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn(min), not height (A7 font-scale): long labels wrap to
            // two lines at large fontScale instead of overflowing the button.
            .heightIn(min = 52.dp)
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .pressable(onClick = onClick, shape = RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ CREATE CUSTOM EXERCISE", color = accent, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

// --- data / backup (PLAN.md A2, brief D9's :app-side UI PR) ------------------

@Composable
private fun DataBackupButton(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .pressable(onClick = onClick, shape = RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("DATA / BACKUP", color = accent, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

// --- OSS licenses (M6 #23: Barlow Condensed OFL + third-party notices) -------

@Composable
private fun LicensesButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .pressable(onClick = onClick, shape = RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("OSS LICENSES", color = TextSecondary, style = DoneButtonLabel)
    }
}

// --- re-run wizard (destructive escape hatch, spec §8.4) ---------------------

@Composable
private fun RerunWizardButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .border(1.dp, Error, RoundedCornerShape(12.dp))
            .pressable(onClick = onClick, shape = RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("RE-RUN SETUP WIZARD", color = Error, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun RerunConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Re-run setup wizard?") },
        text = { Text("This replaces your current program from scratch. Your workout history isn't touched.") },
        confirmButton = { DialogAction("Re-run", Error, onConfirm) },
        dismissButton = { DialogAction("Cancel", TextSecondary, onDismiss) },
    )
}

/** Confirms the destructive reset of all per-category rest overrides. */
@Composable
private fun RestDefaultsConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Reset rest timers?") },
        text = { Text("Every per-category rest length goes back to the built-in default. The rest timer stays on.") },
        confirmButton = { DialogAction("Reset", Error, onConfirm) },
        dismissButton = { DialogAction("Cancel", TextSecondary, onDismiss) },
    )
}

@Preview(showBackground = true, heightDp = 1700, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SetupScreenPreview() {
    AppTheme {
        SetupScreen(
            state = SetupStateBuilder.buildUiState(
                cfg = LifterConfig(),
                cardio = CardioPrefs(),
                unit = WeightUnit.LB,
                answers = WizardAnswers(),
            ),
            actions = SetupActions(
                onBodyweightChange = {}, onAgeChange = {}, onLevelChange = {}, onEmphasisChange = {},
                onCardioModeChange = {}, onCardioPlacementChange = {}, onFiveKChange = {},
                onUnitToggle = {}, onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> },
                onRestOverridesReset = {}, onRerunWizard = {}, onCreateCustomExercise = {}, onOpenBackup = {},
                onOpenLicenses = {}, onBack = {},
            ),
        )
    }
}
