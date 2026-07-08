package io.github.sjtrotter.strengthlog.ui.wizard

import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitDefaults
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis

/**
 * The wizard's questions in order (spec §6.1) plus the optional equipment step
 * (PLAN.md A4), appended last since it is the one step the spec doesn't ask for.
 */
enum class WizardStep {
    EMPHASIS,
    DAYS_PER_WEEK,
    SPLIT,
    ANCHORS,
    CARDIO,
    ABOUT_YOU,
    EQUIPMENT,
}

/**
 * Everything [io.github.sjtrotter.strengthlog.ui.wizard.WizardScreen] renders:
 * the current step, the in-progress [answers] draft, and the two pieces of
 * per-step derived preview ([splitOptions], [activeAnchorIds]) so the screen
 * never has to call into [SplitDefaults]/[io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator]
 * itself — [WizardStateBuilder] is the one place that happens.
 */
data class WizardUiState(
    val stepIndex: Int = 0,
    val step: WizardStep = WizardStep.EMPHASIS,
    val answers: WizardAnswers = WizardAnswers(),
    val splitOptions: SplitDefaults.Options = SplitDefaults.optionsFor(WizardAnswers().daysPerWeek),
    val activeAnchorIds: List<String> = emptyList(),
    val isComplete: Boolean = false,
) {
    val totalSteps: Int get() = WizardStep.entries.size
    val isFirstStep: Boolean get() = stepIndex == 0
    val isLastStep: Boolean get() = stepIndex == totalSteps - 1
}

/** Callbacks the screen forwards to [WizardViewModel] — mirrors [io.github.sjtrotter.strengthlog.ui.day.DayActions]. */
data class WizardActions(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onEmphasisChange: (GoalEmphasis) -> Unit,
    val onDaysPerWeekChange: (Int) -> Unit,
    val onSplitChange: (SplitTemplate) -> Unit,
    val onAnchorSchemeChange: (AnchorScheme) -> Unit,
    val onDeadliftVariantChange: (DeadliftVariant) -> Unit,
    val onCardioModeChange: (CardioMode) -> Unit,
    val onCardioPlacementChange: (CardioPlacement) -> Unit,
    val onFiveKChange: (Boolean) -> Unit,
    val onBodyweightChange: (Int) -> Unit,
    val onAgeChange: (Int) -> Unit,
    val onLevelChange: (ExperienceLevel) -> Unit,
    val onEquipmentToggle: (Equipment) -> Unit,
)
