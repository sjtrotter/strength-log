package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.model.ProgramExercise

/**
 * One exercise slot of a day paired with its stable Room row id. The pure-domain
 * [ProgramExercise] carries no persistence id (spec: `:domain` stays id-free), but
 * persisted working values and run tracks use [programExerciseId] to identify
 * the slot independently of its exercise.
 */
data class ProgramSlot(
    val programExerciseId: Long,
    val position: Int,
    val exercise: ProgramExercise,
)

fun List<ProgramSlot>.forRun(dropped: Set<Long>): List<ProgramSlot> = map { slot ->
    if (slot.programExerciseId in dropped) slot.copy(exercise = slot.exercise.copy(superset = null)) else slot
}
