package cloud.trotter.log.strength.ui.setup

import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalFormatter
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.SecondsStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.components.BodyweightInput

/** Goal math and anchor selection stay in :domain. */
object SetupStateBuilder {

    fun goalPreview(cfg: LifterConfig, answers: WizardAnswers, unit: WeightUnit): List<GoalPreviewItem> =
        ProgramGenerator.anchorIds(answers).map { id ->
            val entry = ExerciseLibrary.get(id)
            GoalPreviewItem(
                name = entry.name,
                display = GoalFormatter.label(GoalCalculator.targetFor(entry, cfg), unit),
                perHand = entry.perHand,
            )
        }

    /** [cfg]'s canonical-lb bodyweight converted to [unit] for the stepper's display value. */
    fun bodyweightDisplay(cfg: LifterConfig, unit: WeightUnit): Double =
        BodyweightInput.display(cfg.bodyweightLb, unit)

    fun bodyweightLb(displayValue: Double, unit: WeightUnit): Double =
        BodyweightInput.canonicalLb(displayValue, unit)

    /**
     * The rest-timer editor's rows (W2c): every [RestCategory] paired with its
     * *effective* seconds — [settings]' override for that bucket, or else
     * [RestPolicy]'s default. Absent-means-default (never a duplicated number),
     * so a defaults change ships to every lifter who hasn't overridden that
     * bucket without touching this function.
     */
    fun restCategoryRows(settings: RestSettings): List<RestCategoryUiState> =
        RestCategory.entries.map { category ->
            RestCategoryUiState(category, settings.overrides[category] ?: RestPolicy.defaultSeconds(category))
        }

    /** A rest row's value label: [SecondsStepper.format] (SSOT — "90s", "2:00"),
     *  except `0` — "no timer" — which reads as "OFF" rather than "0s". */
    fun restTimerLabel(seconds: Int): String =
        if (seconds == 0) "OFF" else SecondsStepper.format(seconds)

    /** Assembles Settings from app preferences. */
    fun buildUiState(
        unit: WeightUnit,
        restSettings: RestSettings = RestSettings(),
        phoneRestTimerEnabled: Boolean = true,
        themePreference: ThemePreference = ThemePreference.SYSTEM,
        distanceUnit: DistanceUnit = DistanceUnit.suggestedFor(unit),
    ): SetupUiState = SetupUiState(
        unit = unit,
        distanceUnit = distanceUnit,
        restTimerEnabled = restSettings.enabled,
        phoneRestTimerEnabled = phoneRestTimerEnabled,
        restCategories = restCategoryRows(restSettings),
        themePreference = themePreference,
    )
}
