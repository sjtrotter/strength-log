package cloud.trotter.log.strength.ui.setup

import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.theme.ThemePreference

/** One row of the live GOAL preview (spec §8.4) — a main lift's name and its
 *  current GOAL, already formatted for display in the lifter's chosen unit. */
data class GoalPreviewItem(val name: String, val display: String, val perHand: Boolean)

/** One row of the rest-timer editor (W2c) — a [category] and its *effective*
 *  seconds (the user's override, or else [RestPolicy]'s default; see
 *  [SetupStateBuilder.restCategoryRows]). `0` means "no timer". */
data class RestCategoryUiState(val category: RestCategory, val seconds: Int)

/**
 * Everything [SetupScreen] renders. [bodyweightDisplay] is [config]'s
 * bodyweight converted to [unit] for the stepper (canonical storage stays lb,
 * A5); [goalPreview] is the four main-lift GOALs recomputed live off
 * [config] and the stored wizard anchors — see [SetupStateBuilder].
 * [restTimerEnabled]/[restCategories] mirror `RestSettings` (W2a) resolved
 * against [RestPolicy]'s defaults.
 */
data class SetupUiState(
    val config: LifterConfig = LifterConfig(),
    val cardio: CardioPrefs = CardioPrefs(),
    val unit: WeightUnit = WeightUnit.LB,
    val bodyweightDisplay: Double = LifterConfig().bodyweightLb.toDouble(),
    val goalPreview: List<GoalPreviewItem> = emptyList(),
    val restTimerEnabled: Boolean = true,
    val restCategories: List<RestCategoryUiState> =
        RestCategory.entries.map { RestCategoryUiState(it, RestPolicy.defaultSeconds(it)) },
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
)

/** Callbacks the screen forwards to [SetupViewModel] — mirrors [cloud.trotter.log.strength.ui.wizard.WizardActions]. */
data class SetupActions(
    val onBodyweightChange: (Double) -> Unit,
    val onAgeChange: (Int) -> Unit,
    val onLevelChange: (ExperienceLevel) -> Unit,
    val onEmphasisChange: (GoalEmphasis) -> Unit,
    val onCardioModeChange: (CardioMode) -> Unit,
    val onCardioPlacementChange: (CardioPlacement) -> Unit,
    val onFiveKChange: (Boolean) -> Unit,
    val onUnitToggle: (WeightUnit) -> Unit,
    val onThemePreferenceChange: (ThemePreference) -> Unit = {},
    val onRestTimerEnabledChange: (Boolean) -> Unit,
    val onRestOverrideChange: (RestCategory, Int) -> Unit,
    val onRestOverridesReset: () -> Unit,
    val onRerunWizard: () -> Unit,
    val onCreateCustomExercise: () -> Unit,
    val onOpenBackup: () -> Unit,
    val onOpenLicenses: () -> Unit,
    val onBack: () -> Unit,
)
