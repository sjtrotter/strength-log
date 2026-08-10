package cloud.trotter.log.strength.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.R
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
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.BackAction
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
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.accentEmphasis
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
    val accent = accentEmphasis(0)

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            SetupHeader(actions.onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                item { SectionHeader(stringResource(R.string.setup_training_section)) }
                item { GoalPreviewCard(state.goalPreview, accent) }
                item { BodyweightCard(state.bodyweightDisplay, state.unit, actions.onBodyweightChange) }
                item { AgeCard(state.config.age, actions.onAgeChange) }
                item { LevelSection(state.config.level, actions.onLevelChange) }
                item { EmphasisSection(state.config.emphasis, actions.onEmphasisChange) }
                item { CardioSection(state.cardio, actions) }
                item { SectionHeader(stringResource(R.string.setup_display_section)) }
                item { UnitCard(state.unit, actions.onUnitToggle) }
                item { ThemeSection(state.themePreference, actions.onThemePreferenceChange) }
                item { SectionHeader(stringResource(R.string.setup_watch_section)) }
                item {
                    RestTimerSection(
                        state.restTimerEnabled,
                        state.restCategories,
                        actions,
                        onResetDefaults = { showRestResetConfirm = true },
                    )
                }
                item { SectionHeader(stringResource(R.string.setup_data_section)) }
                item { CreateCustomExerciseButton(accent, actions.onCreateCustomExercise) }
                item { DataBackupButton(accent, actions.onOpenBackup) }
                item { SectionHeader(stringResource(R.string.setup_about_section)) }
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
private fun ThemeSection(theme: ThemePreference, onChange: (ThemePreference) -> Unit) {
    val copy = listOf(
        ThemePreference.SYSTEM to (R.string.setup_theme_system_title to R.string.setup_theme_system_description),
        ThemePreference.DARK to (R.string.setup_theme_dark_title to R.string.setup_theme_dark_description),
        ThemePreference.LIGHT to (R.string.setup_theme_light_title to R.string.setup_theme_light_description),
    )
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.setup_theme_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        copy.forEach { (value, resources) ->
            SelectionCard(
                title = stringResource(resources.first),
                subtitle = stringResource(resources.second),
                selected = theme == value,
                onClick = { onChange(value) },
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
            BackAction(onBack)
            Text(stringResource(R.string.setup_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(thickness = 1.dp, color = Border)
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
        HorizontalDivider(thickness = 1.dp, color = Border)
    }
}

// --- live GOAL preview (spec §8.4) -------------------------------------------

@Composable
private fun GoalPreviewCard(items: List<GoalPreviewItem>, accent: Color) {
    AppCard {
        Text(stringResource(R.string.setup_goals_title), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
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
                        Text(stringResource(R.string.setup_per_hand_suffix), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
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
            Text(stringResource(R.string.setup_bodyweight_label, unit.name.lowercase()), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = displayValue,
                onValueChange = onChange,
                step = { WeightStepper.increment(it, unit) },
                minValue = 1.0,
                format = WeightStepper::format,
                round = { WeightStepper.round(it, unit) },
                decreaseDescription = stringResource(R.string.setup_decrease_bodyweight_description),
                increaseDescription = stringResource(R.string.setup_increase_bodyweight_description),
            )
        }
    }
}

@Composable
private fun AgeCard(age: Int, onChange: (Int) -> Unit) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_age_label), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = age.toDouble(),
                onValueChange = { onChange(it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
                decreaseDescription = stringResource(R.string.setup_decrease_age_description),
                increaseDescription = stringResource(R.string.setup_increase_age_description),
            )
        }
    }
}

// --- level / emphasis selectors (same copy as the wizard's About-you/Emphasis steps) ---

@Composable
private fun LevelSection(level: ExperienceLevel, onChange: (ExperienceLevel) -> Unit) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.setup_experience_level_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        ExperienceLevel.entries.forEach { entry ->
            SelectionCard(title = levelLabel(entry), selected = level == entry, onClick = { onChange(entry) })
        }
    }
}

@Composable
private fun levelLabel(level: ExperienceLevel): String = when (level) {
    ExperienceLevel.NOVICE -> stringResource(R.string.setup_level_novice)
    ExperienceLevel.INTERMEDIATE -> stringResource(R.string.setup_level_intermediate)
    ExperienceLevel.ADVANCED -> stringResource(R.string.setup_level_advanced)
}

@Composable
private fun EmphasisSection(emphasis: GoalEmphasis, onChange: (GoalEmphasis) -> Unit) {
    val options = listOf(
        GoalEmphasis.STRENGTH to (stringResource(R.string.setup_emphasis_strength_title) to stringResource(R.string.setup_emphasis_strength_description)),
        GoalEmphasis.BALANCED to (stringResource(R.string.setup_emphasis_balanced_title) to stringResource(R.string.setup_emphasis_balanced_description)),
        GoalEmphasis.PHYSIQUE to (stringResource(R.string.setup_emphasis_physique_title) to stringResource(R.string.setup_emphasis_physique_description)),
    )
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.setup_emphasis_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
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
        Text(stringResource(R.string.setup_cardio_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardioMode.entries.forEach { mode ->
                SelectionCard(title = cardioModeLabel(mode), selected = cardio.mode == mode, onClick = { actions.onCardioModeChange(mode) })
            }
        }
        if (cardio.mode != CardioMode.NONE) {
            Spacer(Modifier.size(2.dp))
            Text(stringResource(R.string.setup_cardio_placement_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CardioPlacement.entries.filter { it != CardioPlacement.NONE }.forEach { placement ->
                    SelectionCard(
                        title = cardioPlacementLabel(placement),
                        selected = cardio.placement == placement,
                        onClick = { actions.onCardioPlacementChange(placement) },
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            AppCard {
                SwitchToggle(label = stringResource(R.string.setup_cardio_five_k_toggle), checked = cardio.fiveKGoal, onCheckedChange = actions.onFiveKChange)
            }
        }
    }
}

@Composable
private fun cardioModeLabel(mode: CardioMode): String = when (mode) {
    CardioMode.OUTDOOR_RUN -> stringResource(R.string.setup_cardio_mode_outdoor_run)
    CardioMode.TREADMILL -> stringResource(R.string.setup_cardio_mode_treadmill)
    CardioMode.LOW_IMPACT -> stringResource(R.string.setup_cardio_mode_low_impact)
    CardioMode.NONE -> stringResource(R.string.setup_cardio_mode_none)
}

@Composable
private fun cardioPlacementLabel(placement: CardioPlacement): String = when (placement) {
    CardioPlacement.FINISHERS -> stringResource(R.string.setup_cardio_placement_finishers)
    CardioPlacement.SEPARATE_DAYS -> stringResource(R.string.setup_cardio_placement_separate_days)
    CardioPlacement.BOTH -> stringResource(R.string.setup_cardio_placement_both)
    CardioPlacement.NONE -> stringResource(R.string.setup_cardio_placement_none)
}

// --- unit toggle (A5) ---------------------------------------------------------

@Composable
private fun UnitCard(unit: WeightUnit, onToggle: (WeightUnit) -> Unit) {
    AppCard {
        SwitchToggle(
            label = stringResource(R.string.setup_display_kilograms_toggle),
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
                label = stringResource(R.string.setup_rest_timer_toggle),
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
            decreaseDescription = stringResource(R.string.setup_decrease_rest_description, label),
            increaseDescription = stringResource(R.string.setup_increase_rest_description, label),
        )
    }
}

@Composable
private fun restCategoryLabel(category: RestCategory): String = when (category) {
    RestCategory.RAMP -> stringResource(R.string.setup_rest_category_warm_up)
    RestCategory.TOP -> stringResource(R.string.setup_rest_category_top_set)
    RestCategory.BACKOFF -> stringResource(R.string.setup_rest_category_back_off)
    RestCategory.WORK -> stringResource(R.string.setup_rest_category_accessory)
    RestCategory.LIGHT -> stringResource(R.string.setup_rest_category_bodyweight)
}

private const val REST_STEP_SECONDS = 15.0

@Composable
private fun ResetRestDefaultsRow(onClick: () -> Unit) {
    SetupOutlineAction(stringResource(R.string.setup_reset_defaults_button), TextSecondary, 48, 6, onClick)
}

// --- create custom exercise (route #13, D1: reachable from Setup and the day-edit picker) ---

@Composable
private fun CreateCustomExerciseButton(accent: Color, onClick: () -> Unit) {
    SetupOutlineAction(stringResource(R.string.setup_create_custom_exercise_button), accent, 52, 8, onClick)
}

// --- data / backup (PLAN.md A2, brief D9's :app-side UI PR) ------------------

@Composable
private fun DataBackupButton(accent: Color, onClick: () -> Unit) {
    SetupOutlineAction(stringResource(R.string.setup_data_backup_button), accent, 52, 8, onClick)
}

// --- OSS licenses (M6 #23: Barlow Condensed OFL + third-party notices) -------

@Composable
private fun LicensesButton(onClick: () -> Unit) {
    SetupOutlineAction(stringResource(R.string.setup_licenses_button), TextSecondary, 52, 6, onClick)
}

// --- re-run wizard (destructive escape hatch, spec §8.4) ---------------------

@Composable
private fun RerunWizardButton(onClick: () -> Unit) {
    SetupOutlineAction(stringResource(R.string.setup_rerun_wizard_button), Error, 52, 8, onClick)
}

@Composable
private fun SetupOutlineAction(
    label: String,
    color: Color,
    minHeight: Int,
    verticalPadding: Int,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        // heightIn(min), not height (A7): long labels may wrap at large font scale.
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(vertical = verticalPadding.dp),
    ) {
        Text(label, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun RerunConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_rerun_confirm_title)) },
        text = { Text(stringResource(R.string.setup_rerun_confirm_message)) },
        confirmButton = { DialogAction(stringResource(R.string.setup_rerun_confirm_button), Error, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.setup_cancel_button), TextSecondary, onDismiss) },
    )
}

/** Confirms the destructive reset of all per-category rest overrides. */
@Composable
private fun RestDefaultsConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_rest_reset_confirm_title)) },
        text = { Text(stringResource(R.string.setup_rest_reset_confirm_message)) },
        confirmButton = { DialogAction(stringResource(R.string.setup_rest_reset_confirm_button), Error, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.setup_cancel_button), TextSecondary, onDismiss) },
    )
}

@Preview(showBackground = true, heightDp = 1700, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SetupScreenPreview() {
    SetupScreenPreviewContent(ThemePreference.DARK)
}

@Preview(showBackground = true, heightDp = 1700, backgroundColor = 0xFFF1EFEA)
@Composable
private fun SetupScreenLightPreview() {
    SetupScreenPreviewContent(ThemePreference.LIGHT)
}

@Composable
private fun SetupScreenPreviewContent(theme: ThemePreference) {
    AppTheme(preference = theme) {
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
                onThemePreferenceChange = {},
                onRestOverridesReset = {}, onRerunWizard = {}, onCreateCustomExercise = {}, onOpenBackup = {},
                onOpenLicenses = {}, onBack = {},
            ),
        )
    }
}
