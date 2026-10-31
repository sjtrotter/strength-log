package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern

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
    val config: LifterConfig = LifterConfig(),
    val canResetToTemplate: Boolean = false,
    val unit: WeightUnit = WeightUnit.LB,
    val prescriptionDraft: PrescriptionDraft? = null,
    val editorOpen: Boolean = false,
    val planId: Long? = null,
    val dayId: String? = null,
    val cardioBlock: CardioBlock? = null,
    val cardioDraft: CardioBlockDraft? = null,
    val runOpen: Boolean = false,
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
    val partnerExerciseId: String? = null,
    /** The partner's catalog name, falling back to its raw id when the id
     *  doesn't resolve — same rule as [title]. */
    val partnerTitle: String? = null,
    val prescription: Prescription? = null,
    val targetSets: Int = 3,
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
    val keepToPlan: Boolean = false,
)

/** Callbacks the day-edit sheet forwards to [DayViewModel]. */
data class DayEditActions(
    val onSwap: (position: Int, newExerciseId: String) -> Unit,
    val onAdd: (exerciseId: String) -> Unit,
    val onRemove: (position: Int) -> Unit,
    /** Adds a partner to a plain slot, or swaps the one it already has. */
    val onSetSuperset: (position: Int, partnerExerciseId: String) -> Unit,
    val onRemoveSuperset: (position: Int) -> Unit,
    val onResetToTemplate: () -> Unit,
    val onMove: (slotId: Long, up: Boolean) -> Unit = { _, _ -> },
    val onOpenPrescription: (Long) -> Unit = {},
    val onPrescriptionDraft: (PrescriptionDraft) -> Unit = {},
    val onSavePrescription: () -> Unit = {},
    val onDiscardPrescription: () -> Unit = {},
    val onEditorOpen: (Boolean) -> Unit = {},
    val onOpenCardio: () -> Unit = {},
    val onCardioDraft: (CardioBlockDraft) -> Unit = {},
    val onSaveCardio: () -> Unit = {},
    val onBackCardio: () -> Unit = {},
    val onRemoveCardio: () -> Unit = {},
    val onSetKeepToPlan: (slotId: Long, keep: Boolean) -> Unit = { _, _ -> },
    val onResetSlotToPlan: (slotId: Long) -> Unit = {},
)
