package cloud.trotter.log.strength.ui.setup

import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.run.LocationAccess
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.units.DistanceUnit

/** One row of the live GOAL preview (spec §8.4) — a main lift's name and its
 *  current GOAL, already formatted for display in the lifter's chosen unit. */
data class GoalPreviewItem(
    val name: String,
    val display: String,
    val perHand: Boolean,
    val exerciseId: String = "",
    val goalLb: Double = 0.0,
    val calculated: String? = null,
)

/** One row of the rest-timer editor (W2c) — a [category] and its *effective*
 *  seconds (the user's override, or else [RestPolicy]'s default; see
 *  [SetupStateBuilder.restCategoryRows]). `0` means "no timer". */
data class RestCategoryUiState(val category: RestCategory, val seconds: Int)

data class SetupUiState(
    val unit: WeightUnit = WeightUnit.LB,
    val distanceUnit: DistanceUnit = DistanceUnit.MI,
    val restTimerEnabled: Boolean = true,
    val phoneRestTimerEnabled: Boolean = true,
    val keepScreenOn: Boolean = false,
    val restCategories: List<RestCategoryUiState> =
        RestCategory.entries.map { RestCategoryUiState(it, RestPolicy.defaultSeconds(it)) },
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val gpsDistance: Boolean = true,
    val gpsAccess: LocationAccess = LocationAccess.DENIED,
    val saveRoute: Boolean = false,
    val effortScale: EffortScale = EffortScale.WORDS,
)

/** Callbacks the screen forwards to [SetupViewModel] — mirrors [cloud.trotter.log.strength.ui.wizard.WizardActions]. */
data class SetupActions(
    val onUnitToggle: (WeightUnit) -> Unit,
    val onDistanceUnitChange: (DistanceUnit) -> Unit = {},
    val onThemePreferenceChange: (ThemePreference) -> Unit = {},
    val onRestTimerEnabledChange: (Boolean) -> Unit,
    val onPhoneRestTimerEnabledChange: (Boolean) -> Unit = {},
    val onRestOverrideChange: (RestCategory, Int) -> Unit,
    val onRestOverridesReset: () -> Unit,
    val onCreateCustomExercise: () -> Unit,
    val onOpenBackup: () -> Unit,
    val onOpenLicenses: () -> Unit,
    val onBack: () -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit = {},
    val onGpsDistanceChange: (Boolean) -> Unit = {},
    val onRefreshLocationAccess: () -> Unit = {},
    val onSaveRouteChange: (Boolean) -> Unit = {},
    val onEffortScaleChange: (EffortScale) -> Unit = {},
)
