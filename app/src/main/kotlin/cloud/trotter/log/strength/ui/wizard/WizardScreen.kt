package cloud.trotter.log.strength.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalFormatter
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.BodyweightStepper
import cloud.trotter.log.strength.ui.components.SelectionCard
import cloud.trotter.log.strength.ui.components.SelectionMode
import cloud.trotter.log.strength.ui.components.Stepper
import cloud.trotter.log.strength.ui.components.SwitchToggle
import cloud.trotter.log.strength.ui.wizardStepTransition
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import cloud.trotter.log.strength.ui.theme.readableWidth
import cloud.trotter.log.strength.ui.text.resolve
import cloud.trotter.log.strength.ui.today.LiftRow

/**
 * The setup wizard (spec §6.1, PLAN.md A4). Stateless: renders [state] and
 * forwards every intent to [WizardActions]; every default already matches the
 * spec's next-next-next program, so a lifter can finish without touching
 * anything but the Finish button.
 */
@Composable
fun WizardScreen(state: WizardUiState, actions: WizardActions) {
    val accent = dayAccent(0)
    val onAccent = onDayAccent(0)

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(readableWidth()) {
            WizardHeader(state, accent)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                item { StepContent(state, actions) }
                item { Spacer(Modifier.size(8.dp)) }
            }
            WizardFooter(state, accent, onAccent, actions)
        }
    }
}

@Composable
private fun WizardHeader(state: WizardUiState, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            stringResource(R.string.wizard_step_progress, state.stepIndex + 1, state.totalSteps),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.size(6.dp))
        Text(stepTitle(state.step), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Border, RoundedCornerShape(2.dp))) {
            val fraction = (state.stepIndex + 1).toFloat() / state.totalSteps
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun stepTitle(step: WizardStep): String = when (step) {
    WizardStep.EMPHASIS -> stringResource(R.string.wizard_emphasis_title)
    WizardStep.DAYS_PER_WEEK -> stringResource(R.string.wizard_days_per_week_title)
    WizardStep.SPLIT -> stringResource(R.string.wizard_split_title)
    WizardStep.ANCHORS -> stringResource(R.string.wizard_anchors_title)
    WizardStep.CARDIO -> stringResource(R.string.wizard_cardio_title)
    WizardStep.ABOUT_YOU -> stringResource(R.string.wizard_about_you_title)
    WizardStep.EQUIPMENT -> stringResource(R.string.wizard_equipment_title)
    WizardStep.ROTATION -> stringResource(R.string.wizard_rotation_title)
}

@Composable
private fun StepContent(state: WizardUiState, actions: WizardActions) {
    AnimatedContent(
        targetState = state,
        contentKey = { it.step },
        transitionSpec = { wizardStepTransition() },
        label = "wizard step",
    ) { stepState ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (stepState.step) {
                WizardStep.EMPHASIS -> EmphasisStep(stepState, actions)
                WizardStep.DAYS_PER_WEEK -> DaysPerWeekStep(stepState.answers, actions)
                WizardStep.SPLIT -> SplitStep(stepState, actions)
                WizardStep.ANCHORS -> AnchorsStep(stepState, actions)
                WizardStep.CARDIO -> CardioStep(stepState.answers, actions)
                WizardStep.ABOUT_YOU -> AboutYouStep(stepState, actions)
                WizardStep.EQUIPMENT -> EquipmentStep(stepState.answers, actions)
                WizardStep.ROTATION -> RotationStep(stepState)
            }
        }
    }
}

// --- step 1: emphasis --------------------------------------------------------

@Composable
private fun EmphasisStep(state: WizardUiState, actions: WizardActions) {
    val options = listOf(
        GoalEmphasis.STRENGTH to (stringResource(R.string.wizard_emphasis_strength_title) to stringResource(R.string.wizard_emphasis_strength_description)),
        GoalEmphasis.BALANCED to (stringResource(R.string.wizard_emphasis_balanced_title) to stringResource(R.string.wizard_emphasis_balanced_description)),
        GoalEmphasis.PHYSIQUE to (stringResource(R.string.wizard_emphasis_physique_title) to stringResource(R.string.wizard_emphasis_physique_description)),
    )
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { (value, copy) ->
            SelectionCard(
                title = copy.first,
                subtitle = copy.second,
                selected = state.answers.config.emphasis == value,
                onClick = { actions.onEmphasisChange(value) },
            )
        }
    }
    if (state.restore.offered) RestoreFromBackupEntry(state.restore, actions.onRestoreFromBackup)
}

/**
 * The way back in for someone who already has a backup: quiet, below the
 * question, and only on a first run (see [WizardRestoreState]). Deliberately not
 * a card — it is an escape hatch from the wizard, not a fourth answer to it.
 */
@Composable
private fun RestoreFromBackupEntry(restore: WizardRestoreState, onClick: () -> Unit) {
    Spacer(Modifier.size(6.dp))
    OutlinedButton(
        onClick = onClick,
        enabled = !restore.inFlight,
        modifier = Modifier
            // No disabledAlpha: while a restore is in flight this button's own
            // label becomes the progress message, and fading it to 40% would
            // dim the one thing the user is reading.
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextSecondary,
            disabledContentColor = TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (restore.inFlight) stringResource(R.string.wizard_restore_in_progress) else stringResource(R.string.wizard_restore_button),
            style = DoneButtonLabel,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
    restore.error?.let { message ->
        Text(message.resolve(), color = Error, style = MaterialTheme.typography.bodySmall)
    }
}

// --- step 2: days/week --------------------------------------------------------

@Composable
private fun DaysPerWeekStep(answers: WizardAnswers, actions: WizardActions) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wizard_days_per_week_label), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(10.dp))
            Stepper(
                value = answers.daysPerWeek.toDouble(),
                onValueChange = { actions.onDaysPerWeekChange(it.toInt()) },
                step = { 1.0 },
                minValue = 2.0,
                format = { it.toInt().toString() },
                round = { it.coerceIn(2.0, 6.0) },
                decreaseDescription = stringResource(R.string.wizard_decrease_days_description),
                increaseDescription = stringResource(R.string.wizard_increase_days_description),
            )
        }
    }
    Text(
        stringResource(R.string.wizard_days_per_week_description),
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}

// --- step 3: split -------------------------------------------------------------

@Composable
private fun SplitStep(state: WizardUiState, actions: WizardActions) {
    val options = listOfNotNull(state.splitOptions.default, state.splitOptions.alternative)
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { split ->
            SelectionCard(
                title = splitLabel(split),
                subtitle = if (split == state.splitOptions.default) {
                    stringResource(R.string.wizard_split_suggested, state.answers.daysPerWeek)
                } else {
                    stringResource(R.string.wizard_split_alternative)
                },
                selected = state.answers.split == split,
                onClick = { actions.onSplitChange(split) },
            )
        }
    }
}

@Composable
private fun splitLabel(split: SplitTemplate): String = when (split) {
    SplitTemplate.FULL_BODY -> stringResource(R.string.wizard_split_full_body)
    SplitTemplate.UPPER_LOWER -> stringResource(R.string.wizard_split_upper_lower)
    SplitTemplate.PPL -> stringResource(R.string.wizard_split_ppl)
    SplitTemplate.PPLUL -> stringResource(R.string.wizard_split_pplul)
}

// --- step 4: anchors -----------------------------------------------------------

@Composable
private fun AnchorsStep(state: WizardUiState, actions: WizardActions) {
    val answers = state.answers
    Text(stringResource(R.string.wizard_main_lifts_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnchorScheme.entries.forEach { scheme ->
            SelectionCard(
                title = anchorSchemeLabel(scheme),
                subtitle = anchorNames(scheme, answers.deadliftVariant),
                selected = answers.anchorScheme == scheme,
                onClick = { actions.onAnchorSchemeChange(scheme) },
            )
        }
    }
    Spacer(Modifier.size(4.dp))
    Text(stringResource(R.string.wizard_deadlift_variant_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeadliftVariant.entries.forEach { variant ->
            SelectionCard(
                title = deadliftLabel(variant),
                selected = answers.deadliftVariant == variant,
                onClick = { actions.onDeadliftVariantChange(variant) },
            )
        }
    }
    val allAnchors = ProgramGenerator.anchorIds(answers)
    if (answers.split == SplitTemplate.FULL_BODY && state.activeAnchorIds.size < allAnchors.size) {
        val names = state.activeAnchorIds.joinToString(", ") {
            ExerciseLibrary.get(it).name
        }
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(
                R.string.wizard_active_anchors_description,
                answers.daysPerWeek,
                state.activeAnchorIds.size,
                names,
            ),
            color = TextFaint,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun anchorSchemeLabel(scheme: AnchorScheme): String = when (scheme) {
    AnchorScheme.PROTOTYPE -> stringResource(R.string.wizard_anchor_prototype)
    AnchorScheme.BIG_4 -> stringResource(R.string.wizard_anchor_big_four)
    AnchorScheme.FIVE_THREE_ONE -> stringResource(R.string.wizard_anchor_five_three_one)
}

@Composable
private fun anchorNames(scheme: AnchorScheme, deadlift: DeadliftVariant): String {
    val ids = ProgramGenerator.anchorIds(
        WizardAnswers(anchorScheme = scheme, deadliftVariant = deadlift),
    )
    return ids.joinToString(" · ") { ExerciseLibrary.get(it).name }
}

@Composable
private fun deadliftLabel(variant: DeadliftVariant): String = when (variant) {
    DeadliftVariant.TRAP_BAR -> stringResource(R.string.wizard_deadlift_trap_bar)
    DeadliftVariant.CONVENTIONAL -> stringResource(R.string.wizard_deadlift_conventional)
    DeadliftVariant.SUMO -> stringResource(R.string.wizard_deadlift_sumo)
}

// --- step 5: cardio --------------------------------------------------------------

@Composable
private fun CardioStep(answers: WizardAnswers, actions: WizardActions) {
    Text(stringResource(R.string.wizard_cardio_mode_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CardioMode.entries.forEach { mode ->
            SelectionCard(
                title = cardioModeLabel(mode),
                selected = answers.cardio.mode == mode,
                onClick = { actions.onCardioModeChange(mode) },
            )
        }
    }
    if (answers.cardio.mode != CardioMode.NONE) {
        Spacer(Modifier.size(4.dp))
        Text(stringResource(R.string.wizard_cardio_placement_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardioPlacement.entries.filter { it != CardioPlacement.NONE }.forEach { placement ->
                SelectionCard(
                    title = cardioPlacementLabel(placement),
                    selected = answers.cardio.placement == placement,
                    onClick = { actions.onCardioPlacementChange(placement) },
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        AppCard {
            SwitchToggle(
                label = stringResource(R.string.wizard_cardio_five_k_toggle),
                checked = answers.cardio.fiveKGoal,
                onCheckedChange = actions.onFiveKChange,
            )
        }
    }
}

@Composable
private fun cardioModeLabel(mode: CardioMode): String = when (mode) {
    CardioMode.OUTDOOR_RUN -> stringResource(R.string.wizard_cardio_mode_outdoor_run)
    CardioMode.TREADMILL -> stringResource(R.string.wizard_cardio_mode_treadmill)
    CardioMode.LOW_IMPACT -> stringResource(R.string.wizard_cardio_mode_low_impact)
    CardioMode.NONE -> stringResource(R.string.wizard_cardio_mode_none)
}

@Composable
private fun cardioPlacementLabel(placement: CardioPlacement): String = when (placement) {
    CardioPlacement.FINISHERS -> stringResource(R.string.wizard_cardio_placement_finishers)
    CardioPlacement.SEPARATE_DAYS -> stringResource(R.string.wizard_cardio_placement_separate_days)
    CardioPlacement.BOTH -> stringResource(R.string.wizard_cardio_placement_both)
    CardioPlacement.NONE -> stringResource(R.string.wizard_cardio_placement_none)
}

// --- step 6: about you -------------------------------------------------------------

@Composable
private fun AboutYouStep(state: WizardUiState, actions: WizardActions) {
    val answers = state.answers
    UnitChoice(unit = state.unit, onChange = actions.onUnitChange)
    AppCard {
        BodyweightStepper(
            canonicalLb = answers.config.bodyweightLb,
            unit = state.unit,
            label = stringResource(R.string.wizard_bodyweight_label, state.unit.name.lowercase()),
            decreaseDescription = stringResource(R.string.wizard_decrease_bodyweight_description),
            increaseDescription = stringResource(R.string.wizard_increase_bodyweight_description),
            onCanonicalLbChange = actions.onBodyweightChange,
        )
    }
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wizard_age_label), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = answers.config.age.toDouble(),
                onValueChange = { actions.onAgeChange(it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
                decreaseDescription = stringResource(R.string.wizard_decrease_age_description),
                increaseDescription = stringResource(R.string.wizard_increase_age_description),
            )
        }
    }
    Text(stringResource(R.string.wizard_experience_level_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExperienceLevel.entries.forEach { level ->
            SelectionCard(
                title = levelLabel(level),
                selected = answers.config.level == level,
                onClick = { actions.onLevelChange(level) },
            )
        }
    }
}

@Composable
private fun UnitChoice(unit: WeightUnit, onChange: (WeightUnit) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        WeightUnit.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = unit == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, WeightUnit.entries.size),
                label = { Text(option.name) },
            )
        }
    }
}

@Composable
private fun levelLabel(level: ExperienceLevel): String = when (level) {
    ExperienceLevel.NOVICE -> stringResource(R.string.wizard_level_novice)
    ExperienceLevel.INTERMEDIATE -> stringResource(R.string.wizard_level_intermediate)
    ExperienceLevel.ADVANCED -> stringResource(R.string.wizard_level_advanced)
}

// --- step 7: equipment (optional, PLAN.md A4) --------------------------------------

@Composable
private fun EquipmentStep(answers: WizardAnswers, actions: WizardActions) {
    Text(
        stringResource(R.string.wizard_equipment_description),
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    Equipment.entries.forEach { equip ->
        SelectionCard(
            title = equipmentLabel(equip),
            selected = equip in answers.equipment,
            onClick = { actions.onEquipmentToggle(equip) },
            mode = SelectionMode.Check,
        )
    }
}

@Composable
private fun equipmentLabel(equipment: Equipment): String = when (equipment) {
    Equipment.BARBELL -> stringResource(R.string.wizard_equipment_barbell)
    Equipment.TRAP_BAR -> stringResource(R.string.wizard_equipment_trap_bar)
    Equipment.DUMBBELL -> stringResource(R.string.wizard_equipment_dumbbell)
    Equipment.MACHINE -> stringResource(R.string.wizard_equipment_machine)
    Equipment.CABLE -> stringResource(R.string.wizard_equipment_cable)
    Equipment.BODYWEIGHT -> stringResource(R.string.wizard_equipment_bodyweight)
    Equipment.BENCH -> stringResource(R.string.wizard_equipment_bench)
    Equipment.RACK -> stringResource(R.string.wizard_equipment_rack)
    Equipment.PULLUP_BAR -> stringResource(R.string.wizard_equipment_pullup_bar)
    Equipment.EZ_BAR -> stringResource(R.string.wizard_equipment_ez_bar)
    Equipment.KETTLEBELL -> stringResource(R.string.wizard_equipment_kettlebell)
}

// --- step 8: generated rotation -------------------------------------------------

@Composable
private fun RotationStep(state: WizardUiState) {
    state.previewProgram?.days.orEmpty().forEachIndexed { index, day ->
        val accent = dayAccent(index)
        val main = day.exercises.firstOrNull { it.isMain } ?: day.exercises.firstOrNull()
        AppCard(borderColor = accent) {
            Text(
                if (day.kind == cloud.trotter.log.strength.domain.model.ProgramDayKind.CARDIO) "${day.id} · CARDIO"
                else stringResource(R.string.wizard_rotation_day, day.id),
                color = accent,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.size(5.dp))
            Text(day.title.uppercase(), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            if (day.emphasisLine.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(day.emphasisLine.uppercase(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            main?.let { exercise ->
                val entry = ExerciseLibrary.get(exercise.exerciseId)
                Spacer(Modifier.size(8.dp))
                HorizontalDivider(thickness = 1.dp, color = Border)
                LiftRow(entry.name, isMain = true) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.wizard_rotation_goal_label), color = TextFaint, style = MaterialTheme.typography.labelSmall)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                GoalFormatter.label(
                                    GoalCalculator.targetFor(entry, state.answers.config),
                                    state.unit,
                                ),
                                color = accent,
                                style = DisplayXl,
                            )
                            if (entry.perHand) {
                                Text(stringResource(R.string.setup_per_hand_suffix), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    Text(
        stringResource(R.string.wizard_rotation_continuity),
        color = TextFaint,
        style = MaterialTheme.typography.bodySmall,
    )
}

// --- footer: back / next / finish --------------------------------------------------

@Composable
private fun WizardFooter(
    state: WizardUiState,
    accent: Color,
    onAccent: Color,
    actions: WizardActions,
) {
    Column(Modifier.fillMaxWidth().background(Background)) {
        HorizontalDivider(thickness = 1.dp, color = Border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Both dead while a restore is in flight: it is rewriting the very
            // answers this footer navigates, and "GENERATE PROGRAM" landing on
            // top of a just-restored program is the accident (#172).
            if (!state.isFirstStep) {
                FooterButton(
                    label = stringResource(R.string.wizard_back_button),
                    fill = Border,
                    textColor = TextPrimary,
                    modifier = Modifier.weight(1f),
                    enabled = !state.restore.inFlight,
                    onClick = actions.onBack,
                )
            }
            FooterButton(
                label = if (state.isLastStep) stringResource(R.string.wizard_start_button) else stringResource(R.string.wizard_next_button),
                fill = accent,
                textColor = onAccent,
                modifier = Modifier.weight(if (state.isFirstStep) 1f else 2f),
                enabled = !state.restore.inFlight,
                onClick = actions.onNext,
            )
        }
    }
}

@Composable
private fun FooterButton(
    label: String,
    fill: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            // heightIn(min), not height (A7 font-scale): "GENERATE PROGRAM"
            // wraps to two lines at large fontScale instead of overflowing.
            .heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = textColor),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Text(label, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun WizardScreenPreview() {
    AppTheme {
        WizardScreen(
            state = WizardStateBuilder.buildUiState(
                stepIndex = 0,
                answers = WizardAnswers(),
                isComplete = false,
                restore = WizardRestoreState(offered = true),
            ),
            actions = WizardActions(
                onNext = {}, onBack = {}, onEmphasisChange = {}, onDaysPerWeekChange = {},
                onSplitChange = {}, onAnchorSchemeChange = {}, onDeadliftVariantChange = {},
                onCardioModeChange = {}, onCardioPlacementChange = {}, onFiveKChange = {},
                onBodyweightChange = {}, onAgeChange = {}, onLevelChange = {}, onEquipmentToggle = {},
                onUnitChange = {},
                onRestoreFromBackup = {},
            ),
        )
    }
}
