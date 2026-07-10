package io.github.sjtrotter.strengthlog.ui.setup

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.SelectionCard
import io.github.sjtrotter.strengthlog.ui.components.Stepper
import io.github.sjtrotter.strengthlog.ui.components.SwitchToggle
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.DisplayXl
import io.github.sjtrotter.strengthlog.ui.theme.DoneButtonLabel
import io.github.sjtrotter.strengthlog.ui.theme.Error
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TabLetter
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent

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
    val accent = dayAccent(0)

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            SetupHeader(actions.onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                item { GoalPreviewCard(state.goalPreview, accent) }
                item { BodyweightCard(state.bodyweightDisplay, state.unit, actions.onBodyweightChange) }
                item { AgeCard(state.config.age, actions.onAgeChange) }
                item { LevelSection(state.config.level, actions.onLevelChange) }
                item { EmphasisSection(state.config.emphasis, actions.onEmphasisChange) }
                item { CardioSection(state.cardio, actions) }
                item { UnitCard(state.unit, actions.onUnitToggle) }
                item { CreateCustomExerciseButton(accent, actions.onCreateCustomExercise) }
                item { DataBackupButton(accent, actions.onOpenBackup) }
                item { LicensesButton(actions.onOpenLicenses) }
                item {
                    RerunWizardButton(onClick = { showRerunConfirm = true })
                }
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
            .size(40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = TextSecondary, style = TabLetter.copy(fontSize = 20.sp))
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

// --- create custom exercise (route #13, D1: reachable from Setup and the day-edit picker) ---

@Composable
private fun CreateCustomExerciseButton(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ CREATE CUSTOM EXERCISE", color = accent, style = DoneButtonLabel)
    }
}

// --- data / backup (PLAN.md A2, brief D9's :app-side UI PR) ------------------

@Composable
private fun DataBackupButton(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("DATA / BACKUP", color = accent, style = DoneButtonLabel)
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
            .clickable(onClick = onClick),
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
            .height(52.dp)
            .border(1.dp, Error, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("RE-RUN SETUP WIZARD", color = Error, style = DoneButtonLabel)
    }
}

@Composable
private fun RerunConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.padding(24.dp).clickable(enabled = false) {}) {
            AppCard {
                Text("Re-run setup wizard?", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Text(
                    "This replaces your current program from scratch. Your workout history isn't touched.",
                    color = TextFaint,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(Surface2, RoundedCornerShape(12.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("CANCEL", color = TextPrimary, style = DoneButtonLabel)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(Error, RoundedCornerShape(12.dp))
                            .clickable(onClick = onConfirm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("RE-RUN", color = TextPrimary, style = DoneButtonLabel)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1400, backgroundColor = 0xFF0D0D0F)
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
                onUnitToggle = {}, onRerunWizard = {}, onCreateCustomExercise = {}, onOpenBackup = {},
                onOpenLicenses = {}, onBack = {},
            ),
        )
    }
}
