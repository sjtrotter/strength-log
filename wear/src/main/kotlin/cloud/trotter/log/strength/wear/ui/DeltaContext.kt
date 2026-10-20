package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/** A swap echoes the installed snapshot's plan. */
fun buildSwapDelta(
    snapshot: WatchSnapshot,
    programExerciseId: Long,
    alternate: WatchAlternate,
    editedAtMillis: Long,
): ExerciseSwapDelta = ExerciseSwapDelta(
    dayId = snapshot.day.dayId,
    programExerciseId = programExerciseId,
    exerciseId = alternate.exerciseId,
    exerciseName = alternate.name,
    editedAtMillis = editedAtMillis,
    planId = snapshot.planId,
)
