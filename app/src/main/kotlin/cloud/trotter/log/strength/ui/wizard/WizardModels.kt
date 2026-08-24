package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.SplitDefaults
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.text.UiText

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
    ROTATION,
}

/**
 * Everything [cloud.trotter.log.strength.ui.wizard.WizardScreen] renders:
 * the current step, the in-progress [answers] draft, and the two pieces of
 * per-step derived preview ([splitOptions], [activeAnchorIds]) so the screen
 * never has to call into [SplitDefaults]/[cloud.trotter.log.strength.domain.generator.ProgramGenerator]
 * itself — [WizardStateBuilder] is the one place that happens.
 */
data class WizardUiState(
    val stepIndex: Int = 0,
    val step: WizardStep = WizardStep.EMPHASIS,
    val answers: WizardAnswers = WizardAnswers(),
    val splitOptions: SplitDefaults.Options = SplitDefaults.optionsFor(WizardAnswers().daysPerWeek),
    val activeAnchorIds: List<String> = emptyList(),
    val unit: WeightUnit = WeightUnit.LB,
    val previewProgram: Program? = null,
    val isComplete: Boolean = false,
    val restore: WizardRestoreState = WizardRestoreState(),
) {
    val totalSteps: Int get() = WizardStep.entries.size
    val isFirstStep: Boolean get() = stepIndex == 0
    val isLastStep: Boolean get() = stepIndex == totalSteps - 1
}

/**
 * The "have a backup?" entry on the wizard's first step. [offered] is latched at
 * wizard entry from `wizardComplete`, so it is true only on a genuine first run:
 * a Setup re-run reaches the same route with data already on the device, and
 * Data/Backup owns import there behind its confirm-overwrite dialog. On a first
 * run there is nothing to overwrite, so the picker commits directly and only
 * [error] (already user-facing copy from
 * [cloud.trotter.log.strength.ui.backup.TransferErrorMessages]) can come back.
 */
data class WizardRestoreState(
    val offered: Boolean = false,
    val inFlight: Boolean = false,
    val error: UiText? = null,
)

/** Callbacks the screen forwards to [WizardViewModel] — mirrors [cloud.trotter.log.strength.ui.day.DayActions]. */
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
    val onUnitChange: (WeightUnit) -> Unit,
    val onAgeChange: (Int) -> Unit,
    val onLevelChange: (ExperienceLevel) -> Unit,
    val onEquipmentToggle: (Equipment) -> Unit,
    val onRestoreFromBackup: () -> Unit,
)
