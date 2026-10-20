package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/** An older phone defaults the token to 0 too; only a plan id proves it knows runs. */
internal val WatchSnapshot?.deltaRunToken: Long?
    get() = this?.takeIf { it.planId != null }?.runToken

/** A swap echoes the same plan/run context as a set edit, though it edits the program. */
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
    runToken = snapshot.deltaRunToken,
)
