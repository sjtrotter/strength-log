package io.github.sjtrotter.strengthlog.ui.day

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern

/**
 * Render model for the day-edit sheet (spec §8.3): the day's slots plus
 * whatever the swap/add picker needs to rank and filter candidates. Kept
 * separate from [DayUiState]/[ExerciseCardState] so the sheet never touches
 * the exercise-card render path.
 */
data class DayEditUiState(
    val slots: List<DayEditSlotState> = emptyList(),
    val canRemove: Boolean = false,
    /** Seeds the picker's equipment-filter chips (PLAN.md A4: "seeded from the
     *  wizard's equipment profile"); the user can still widen/narrow it live. */
    val defaultEquipmentFilter: Set<Equipment> = Equipment.entries.toSet(),
    val catalog: ExerciseCatalog = ExerciseCatalog.CODE_ONLY,
)

/** One row of the day-edit sheet's slot list. */
data class DayEditSlotState(
    val programExerciseId: Long,
    val position: Int,
    val exerciseId: String,
    val title: String,
    /** Null when the slot's exerciseId doesn't resolve in the catalog (a
     *  dangling custom exercise, say) — swap has no pattern to rank against,
     *  so the sheet disables Swap for that row; Remove still works. */
    val pattern: MovementPattern?,
    val isSuperset: Boolean,
)

/** Callbacks the day-edit sheet forwards to [DayViewModel]. */
data class DayEditActions(
    val onSwap: (position: Int, newExerciseId: String) -> Unit,
    val onAdd: (exerciseId: String) -> Unit,
    val onRemove: (position: Int) -> Unit,
    val onResetToTemplate: () -> Unit,
)
