package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.standards.GoalTarget

data class PerformedExercise(
    val exerciseId: String,
    val entry: ExerciseEntry?,
    val prescription: Prescription?,
) {
    val tracking: TrackingType get() = prescription?.tracking ?: entry?.tracking ?: TrackingType.WEIGHTED
    /** The plan's own goal for this lift (`LifterConfig.goalsLb`); only a known weighted entry has one. */
    fun goal(config: LifterConfig): GoalTarget.Weight? = entry?.takeIf { it.tracking == TrackingType.WEIGHTED }
        ?.let { known -> config.goalsLb[known.id]?.let { GoalTarget.Weight(it, known.perHand) } }
}

fun performedExercise(
    slot: ProgramSlot,
    trackExerciseId: String?,
    catalog: ExerciseCatalog,
    trackSlot: String = Slot.MAIN,
): PerformedExercise {
    val plannedId = when (trackSlot) {
        Slot.MAIN -> slot.exercise.exerciseId
        Slot.SS -> requireNotNull(slot.exercise.superset).exerciseId
        else -> error("Unknown slot: $trackSlot")
    }
    val id = trackExerciseId ?: plannedId
    return PerformedExercise(id, catalog.find(id),
        slot.exercise.prescription.takeIf { trackSlot == Slot.MAIN && id == plannedId })
}
