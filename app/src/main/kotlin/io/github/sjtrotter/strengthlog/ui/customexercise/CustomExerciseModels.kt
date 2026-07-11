package io.github.sjtrotter.strengthlog.ui.customexercise

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * Everything [CustomExerciseScreen] renders (brief #13, extended by tracking
 * types P4). [weightDisplay]/[addedWeightDisplay] are already converted to
 * [unit] so the screen never touches [WeightUnit] conversion itself (SSOT
 * lives in `:domain`, mirrors [io.github.sjtrotter.strengthlog.ui.day.DayViewModel]).
 *
 * [tracking] picks which target input the form shows (§2.1): WEIGHTED reads
 * [weightDisplay] as the starting GOAL weight; REPS reads [targetReps];
 * TIMED reads [targetSeconds] plus [addedWeightDisplay] as an optional load —
 * a separate field from [weightDisplay] (rather than reusing it) so switching
 * tracking types never carries a stale "45 lb barbell" default into "optional
 * added load", which should start at none.
 *
 * [canSave] is the one validation rule the form has (name required, trimmed,
 * non-blank) — [CustomExerciseScreen] disables the save action on it so a
 * blank/whitespace name can never reach [io.github.sjtrotter.strengthlog.data.TrackerRepository.addCustomExercise].
 */
data class CustomExerciseUiState(
    val name: String = "",
    val pattern: MovementPattern = MovementPattern.entries.first(),
    val equipment: Set<Equipment> = emptySet(),
    val perHand: Boolean = false,
    val tracking: TrackingType = TrackingType.WEIGHTED,
    val weightDisplay: Double = 45.0,
    val targetReps: Int = 10,
    val targetSeconds: Int = 30,
    val addedWeightDisplay: Double = 0.0,
    val unit: WeightUnit = WeightUnit.LB,
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = name.trim().isNotEmpty()
}

/** Callbacks the screen forwards to [CustomExerciseViewModel] — mirrors
 *  [io.github.sjtrotter.strengthlog.ui.wizard.WizardActions]. */
data class CustomExerciseActions(
    val onNameChange: (String) -> Unit,
    val onPatternChange: (MovementPattern) -> Unit,
    val onEquipmentToggle: (Equipment) -> Unit,
    val onPerHandChange: (Boolean) -> Unit,
    val onTrackingChange: (TrackingType) -> Unit,
    val onWeightChange: (Double) -> Unit,
    val onTargetRepsChange: (Int) -> Unit,
    val onTargetSecondsChange: (Int) -> Unit,
    val onAddedWeightChange: (Double) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)
