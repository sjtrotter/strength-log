package io.github.sjtrotter.strengthlog.ui.setup

import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/** One row of the live GOAL preview (spec §8.4) — a main lift's name and its
 *  current GOAL, already formatted for display in the lifter's chosen unit. */
data class GoalPreviewItem(val name: String, val display: String, val perHand: Boolean)

/**
 * Everything [SetupScreen] renders. [bodyweightDisplay] is [config]'s
 * bodyweight converted to [unit] for the stepper (canonical storage stays lb,
 * A5); [goalPreview] is the four main-lift GOALs recomputed live off
 * [config] and the stored wizard anchors — see [SetupStateBuilder].
 */
data class SetupUiState(
    val config: LifterConfig = LifterConfig(),
    val cardio: CardioPrefs = CardioPrefs(),
    val unit: WeightUnit = WeightUnit.LB,
    val bodyweightDisplay: Double = LifterConfig().bodyweightLb.toDouble(),
    val goalPreview: List<GoalPreviewItem> = emptyList(),
)

/** Callbacks the screen forwards to [SetupViewModel] — mirrors [io.github.sjtrotter.strengthlog.ui.wizard.WizardActions]. */
data class SetupActions(
    val onBodyweightChange: (Double) -> Unit,
    val onAgeChange: (Int) -> Unit,
    val onLevelChange: (ExperienceLevel) -> Unit,
    val onEmphasisChange: (GoalEmphasis) -> Unit,
    val onCardioModeChange: (CardioMode) -> Unit,
    val onCardioPlacementChange: (CardioPlacement) -> Unit,
    val onFiveKChange: (Boolean) -> Unit,
    val onUnitToggle: (WeightUnit) -> Unit,
    val onRerunWizard: () -> Unit,
    val onCreateCustomExercise: () -> Unit,
    val onOpenBackup: () -> Unit,
    val onOpenLicenses: () -> Unit,
    val onBack: () -> Unit,
)
