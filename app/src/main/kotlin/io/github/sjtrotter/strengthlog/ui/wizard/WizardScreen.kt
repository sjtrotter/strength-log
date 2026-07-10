package io.github.sjtrotter.strengthlog.ui.wizard

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.components.SelectionCard
import io.github.sjtrotter.strengthlog.ui.components.Stepper
import io.github.sjtrotter.strengthlog.ui.components.SwitchToggle
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.DoneButtonLabel
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent
import io.github.sjtrotter.strengthlog.ui.theme.onDayAccent

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
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
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
            "STEP ${state.stepIndex + 1} OF ${state.totalSteps}",
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

private fun stepTitle(step: WizardStep): String = when (step) {
    WizardStep.EMPHASIS -> "What are you training for?"
    WizardStep.DAYS_PER_WEEK -> "How many days a week can you commit?"
    WizardStep.SPLIT -> "Your split"
    WizardStep.ANCHORS -> "Main-lift anchors"
    WizardStep.CARDIO -> "Cardio"
    WizardStep.ABOUT_YOU -> "About you"
    WizardStep.EQUIPMENT -> "What equipment do you have?"
}

@Composable
private fun StepContent(state: WizardUiState, actions: WizardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state.step) {
            WizardStep.EMPHASIS -> EmphasisStep(state.answers, actions)
            WizardStep.DAYS_PER_WEEK -> DaysPerWeekStep(state.answers, actions)
            WizardStep.SPLIT -> SplitStep(state, actions)
            WizardStep.ANCHORS -> AnchorsStep(state, actions)
            WizardStep.CARDIO -> CardioStep(state.answers, actions)
            WizardStep.ABOUT_YOU -> AboutYouStep(state.answers, actions)
            WizardStep.EQUIPMENT -> EquipmentStep(state.answers, actions)
        }
    }
}

// --- step 1: emphasis --------------------------------------------------------

@Composable
private fun EmphasisStep(answers: WizardAnswers, actions: WizardActions) {
    val options = listOf(
        GoalEmphasis.STRENGTH to ("Strength-leaning" to "Fewer reps, more weight on the mains."),
        GoalEmphasis.BALANCED to ("Balanced strength + muscle" to "The default — even mix of heavy work and volume."),
        GoalEmphasis.PHYSIQUE to ("Physique-leaning" to "More volume and isolation work."),
    )
    options.forEach { (value, copy) ->
        SelectionCard(
            title = copy.first,
            subtitle = copy.second,
            selected = answers.config.emphasis == value,
            onClick = { actions.onEmphasisChange(value) },
        )
    }
}

// --- step 2: days/week --------------------------------------------------------

@Composable
private fun DaysPerWeekStep(answers: WizardAnswers, actions: WizardActions) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Days per week", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(10.dp))
            Stepper(
                value = answers.daysPerWeek.toDouble(),
                onValueChange = { actions.onDaysPerWeekChange(it.toInt()) },
                step = { 1.0 },
                minValue = 2.0,
                format = { it.toInt().toString() },
                round = { it.coerceIn(2.0, 6.0) },
                decreaseDescription = "Decrease days per week",
                increaseDescription = "Increase days per week",
            )
        }
    }
    Text(
        "The default is 4 — a full-body rotation that's hard to fall behind on.",
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}

// --- step 3: split -------------------------------------------------------------

@Composable
private fun SplitStep(state: WizardUiState, actions: WizardActions) {
    val options = listOfNotNull(state.splitOptions.default, state.splitOptions.alternative)
    options.forEach { split ->
        SelectionCard(
            title = splitLabel(split),
            subtitle = if (split == state.splitOptions.default) "Suggested for ${state.answers.daysPerWeek} days/week" else "Alternative",
            selected = state.answers.split == split,
            onClick = { actions.onSplitChange(split) },
        )
    }
}

private fun splitLabel(split: SplitTemplate): String = when (split) {
    SplitTemplate.FULL_BODY -> "Full-body rotation"
    SplitTemplate.UPPER_LOWER -> "Upper / Lower"
    SplitTemplate.PPL -> "Push / Pull / Legs"
    SplitTemplate.PPLUL -> "Push / Pull / Legs + Upper / Lower"
}

// --- step 4: anchors -----------------------------------------------------------

@Composable
private fun AnchorsStep(state: WizardUiState, actions: WizardActions) {
    val answers = state.answers
    Text("Main lifts", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    AnchorScheme.entries.forEach { scheme ->
        SelectionCard(
            title = anchorSchemeLabel(scheme),
            subtitle = anchorNames(scheme, answers.deadliftVariant),
            selected = answers.anchorScheme == scheme,
            onClick = { actions.onAnchorSchemeChange(scheme) },
        )
    }
    Spacer(Modifier.size(4.dp))
    Text("Deadlift variant", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    DeadliftVariant.entries.forEach { variant ->
        SelectionCard(
            title = deadliftLabel(variant),
            selected = answers.deadliftVariant == variant,
            onClick = { actions.onDeadliftVariantChange(variant) },
        )
    }
    val allAnchors = ProgramGenerator.anchorIds(answers)
    if (answers.split == SplitTemplate.FULL_BODY && state.activeAnchorIds.size < allAnchors.size) {
        val names = state.activeAnchorIds.joinToString(", ") { ExerciseLibrary.get(it).name }
        Spacer(Modifier.size(4.dp))
        Text(
            "At ${answers.daysPerWeek} days/week only the first ${state.activeAnchorIds.size} rotate this cycle: $names.",
            color = TextFaint,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun anchorSchemeLabel(scheme: AnchorScheme): String = when (scheme) {
    AnchorScheme.PROTOTYPE -> "Squat / Bench / Deadlift / Incline DB"
    AnchorScheme.BIG_4 -> "Squat / Bench / Deadlift / Row (Big 4)"
    AnchorScheme.FIVE_THREE_ONE -> "Squat / Bench / Deadlift / OHP (5/3/1-style)"
}

private fun anchorNames(scheme: AnchorScheme, deadlift: DeadliftVariant): String {
    val ids = ProgramGenerator.anchorIds(
        WizardAnswers(anchorScheme = scheme, deadliftVariant = deadlift),
    )
    return ids.joinToString(" · ") { ExerciseLibrary.get(it).name }
}

private fun deadliftLabel(variant: DeadliftVariant): String = when (variant) {
    DeadliftVariant.TRAP_BAR -> "Trap-bar (default)"
    DeadliftVariant.CONVENTIONAL -> "Conventional"
    DeadliftVariant.SUMO -> "Sumo"
}

// --- step 5: cardio --------------------------------------------------------------

@Composable
private fun CardioStep(answers: WizardAnswers, actions: WizardActions) {
    Text("Mode", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    CardioMode.entries.forEach { mode ->
        SelectionCard(
            title = cardioModeLabel(mode),
            selected = answers.cardio.mode == mode,
            onClick = { actions.onCardioModeChange(mode) },
        )
    }
    if (answers.cardio.mode != CardioMode.NONE) {
        Spacer(Modifier.size(4.dp))
        Text("Placement", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        CardioPlacement.entries.filter { it != CardioPlacement.NONE }.forEach { placement ->
            SelectionCard(
                title = cardioPlacementLabel(placement),
                selected = answers.cardio.placement == placement,
                onClick = { actions.onCardioPlacementChange(placement) },
            )
        }
        Spacer(Modifier.size(4.dp))
        AppCard {
            SwitchToggle(
                label = "Keep me 5k-ready",
                checked = answers.cardio.fiveKGoal,
                onCheckedChange = actions.onFiveKChange,
            )
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

// --- step 6: about you -------------------------------------------------------------

@Composable
private fun AboutYouStep(answers: WizardAnswers, actions: WizardActions) {
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Bodyweight (lb)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = answers.config.bodyweightLb.toDouble(),
                onValueChange = { actions.onBodyweightChange(it.toInt()) },
                step = { 5.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
                decreaseDescription = "Decrease bodyweight",
                increaseDescription = "Increase bodyweight",
            )
        }
    }
    AppCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Age", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Stepper(
                value = answers.config.age.toDouble(),
                onValueChange = { actions.onAgeChange(it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
                decreaseDescription = "Decrease age",
                increaseDescription = "Increase age",
            )
        }
    }
    Text("Experience level", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    ExperienceLevel.entries.forEach { level ->
        SelectionCard(
            title = levelLabel(level),
            selected = answers.config.level == level,
            onClick = { actions.onLevelChange(level) },
        )
    }
}

private fun levelLabel(level: ExperienceLevel): String = when (level) {
    ExperienceLevel.NOVICE -> "Novice"
    ExperienceLevel.INTERMEDIATE -> "Intermediate"
    ExperienceLevel.ADVANCED -> "Advanced"
}

// --- step 7: equipment (optional, PLAN.md A4) --------------------------------------

@Composable
private fun EquipmentStep(answers: WizardAnswers, actions: WizardActions) {
    Text(
        "Optional — defaults to everything. Filters what the program picks and how substitutions are ranked.",
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    Equipment.entries.forEach { equip ->
        SelectionCard(
            title = equipmentLabel(equip),
            selected = equip in answers.equipment,
            onClick = { actions.onEquipmentToggle(equip) },
        )
    }
}

private fun equipmentLabel(equipment: Equipment): String = equipment.name
    .split("_")
    .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

// --- footer: back / next / finish --------------------------------------------------

@Composable
private fun WizardFooter(
    state: WizardUiState,
    accent: Color,
    onAccent: Color,
    actions: WizardActions,
) {
    Column(Modifier.fillMaxWidth().background(Background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.isFirstStep) {
                FooterButton(
                    label = "BACK",
                    fill = Border,
                    textColor = TextPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = actions.onBack,
                )
            }
            FooterButton(
                label = if (state.isLastStep) "GENERATE PROGRAM" else "NEXT",
                fill = accent,
                textColor = onAccent,
                modifier = Modifier.weight(if (state.isFirstStep) 1f else 2f),
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
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            // heightIn(min), not height (A7 font-scale): "GENERATE PROGRAM"
            // wraps to two lines at large fontScale instead of overflowing.
            .heightIn(min = 52.dp)
            .background(fill, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = DoneButtonLabel, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun WizardScreenPreview() {
    AppTheme {
        WizardScreen(
            state = WizardStateBuilder.buildUiState(0, WizardAnswers(), false),
            actions = WizardActions(
                onNext = {}, onBack = {}, onEmphasisChange = {}, onDaysPerWeekChange = {},
                onSplitChange = {}, onAnchorSchemeChange = {}, onDeadliftVariantChange = {},
                onCardioModeChange = {}, onCardioPlacementChange = {}, onFiveKChange = {},
                onBodyweightChange = {}, onAgeChange = {}, onLevelChange = {}, onEquipmentToggle = {},
            ),
        )
    }
}
