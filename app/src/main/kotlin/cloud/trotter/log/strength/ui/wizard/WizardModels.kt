package cloud.trotter.log.strength.ui.wizard

import android.os.Parcelable
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.SplitDefaults
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.text.UiText
import kotlinx.parcelize.Parcelize

/**
 * Wizard questions; [WizardStateBuilder.steps] owns their order in each branch.
 */
enum class WizardStep {
    FOCUS,
    CARDIO_ACTIVITY,
    CARDIO_START,
    CARDIO_SESSIONS,
    ALSO_LIFT,
    EMPHASIS,
    DAYS_PER_WEEK,
    SPLIT,
    ANCHORS,
    CARDIO,
    ABOUT_YOU,
    EQUIPMENT,
    LIFTS,
    ROTATION,
}

/** One recent-lifts row as typed; weight in lb. Saved under its own key. */
@Parcelize
data class WizardLiftDraft(val exerciseId: String, val weightLb: Double?, val reps: Int?) : Parcelable

data class WizardLiftRow(val exerciseId: String, val name: String, val perHand: Boolean, val weightLb: Double?, val reps: Int)

data class WizardPreviewLift(val name: String, val setCount: Int, val detail: String?)

/**
 * Everything [cloud.trotter.log.strength.ui.wizard.WizardScreen] renders:
 * the current step, the in-progress [answers] draft, and the
 * per-step derived previews ([splitOptions], [activeAnchorIds], [previewLifts]) so the screen
 * never has to call into [SplitDefaults]/[cloud.trotter.log.strength.domain.generator.ProgramGenerator]
 * itself — [WizardStateBuilder] is the one place that happens.
 */
data class WizardUiState(
    val stepIndex: Int = 0,
    val step: WizardStep = WizardStep.FOCUS,
    val answers: WizardAnswers = WizardAnswers(),
    val steps: List<WizardStep> = WizardStateBuilder.steps(answers),
    val splitOptions: SplitDefaults.Options = SplitDefaults.optionsFor(WizardAnswers().daysPerWeek),
    val activeAnchorIds: List<String> = emptyList(),
    val unit: WeightUnit = WeightUnit.LB,
    val distanceUnit: DistanceUnit = DistanceUnit.MI,
    val cardioStartByTime: Boolean = true,
    val previewProgram: Program? = null,
    val previewLifts: Map<String, List<WizardPreviewLift>> = emptyMap(),
    val isComplete: Boolean = false,
    val restore: WizardRestoreState = WizardRestoreState(),
    val finishError: UiText? = null,
    val finishInFlight: Boolean = false,
    val initialized: Boolean = true,
    val bodyweightLb: Double? = null,
    val age: Int? = null,
    val liftRows: List<WizardLiftRow> = emptyList(),
) {
    val canContinue: Boolean get() = initialized && (step != WizardStep.ABOUT_YOU ||
        (bodyweightLb != null && bodyweightLb.isFinite() && bodyweightLb >= 1.0 && age != null && age >= 1))
    val emptyRotation: Boolean get() = isLastStep && previewProgram?.days.isNullOrEmpty()
    val totalSteps: Int get() = steps.size
    val isFirstStep: Boolean get() = stepIndex == 0
    val isLastStep: Boolean get() = step == WizardStep.ROTATION
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
    val onBodyweightChange: (Double?) -> Unit,
    val onUnitChange: (WeightUnit) -> Unit,
    val onAgeChange: (Int?) -> Unit,
    val onLevelChange: (ExperienceLevel) -> Unit,
    val onEquipmentToggle: (Equipment) -> Unit,
    val onRestoreFromBackup: () -> Unit,
    val onFocusChange: (Boolean) -> Unit = {},
    val onCardioFamilyChange: (CardioFamily) -> Unit = {},
    val onCardioActivityChange: (CardioActivity) -> Unit = {},
    val onCardioStartByTimeChange: (Boolean) -> Unit = {},
    val onCardioStartSecondsChange: (Int) -> Unit = {},
    val onCardioStartMetresChange: (Int) -> Unit = {},
    val onCardioSessionsChange: (Int) -> Unit = {},
    val onLiftDaysChange: (Int) -> Unit = {},
    val onDistanceUnitChange: (DistanceUnit) -> Unit = {},
    val onLiftWeightChange: (String, Double?) -> Unit = { _, _ -> },
    val onLiftRepsChange: (String, Int?) -> Unit = { _, _ -> },
)
