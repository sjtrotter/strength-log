package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise

/**
 * One exercise slot of a day paired with its stable Room row id. The pure-domain
 * [ProgramExercise] carries no persistence id (spec: `:domain` stays id-free), but
 * the day screen needs [programExerciseId] to key each slot's live log and to seed
 * it — [logFlow]'s rows and [updateSets] are both keyed by that id.
 */
data class ProgramSlot(
    val programExerciseId: Long,
    val position: Int,
    val exercise: ProgramExercise,
)
