package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet

/** Stores literal fixture rows without making them repository seed inputs. */
suspend fun StrengthDatabase.storeTestRows(
    dayId: String,
    programExerciseId: Long,
    slot: String,
    sets: List<LoggedSet>,
    runId: String? = null,
) {
    val exercise = requireNotNull(programDao().exercise(programExerciseId))
    val json = SetJson.encodeSets(sets)
    if (runId == null) {
        programDao().upsertLog(ExerciseLogEntity(
            exercise.planId, dayId, programExerciseId, slot, json, 0L,
        ))
        return
    }
    val current = runDao().tracks(runId).firstOrNull {
        it.programExerciseId == programExerciseId && it.slot == slot && !it.retired
    }
    if (current != null) {
        runDao().updateTrack(current.copy(setsJson = json))
    } else {
        val exerciseId = if (slot == Slot.SS) requireNotNull(exercise.supersetExerciseId) else exercise.exerciseId
        runDao().insertTrack(RunTrackEntity(
            id = 0, runId = runId, position = exercise.position,
            programExerciseId = programExerciseId, slot = slot, exerciseId = exerciseId,
            plannedJson = json, setsJson = json, updatedAt = 0L,
        ))
    }
}
