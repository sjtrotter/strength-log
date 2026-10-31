package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/**
 * The watch's one outbound mutation: a set is done, here is when it started and
 * when it finished. All three facts ride the *same* delta — the phone derives
 * active time and actual rest from the pair of stamps, so splitting them across
 * two edits would let a flush land half a set (the queue settles per field, but
 * a half-stamped set is still a lie about the workout).
 *
 * Stamps are wall clock ([System.currentTimeMillis]) because the phone reads
 * them as points in the day; the watch's own countdowns stay on
 * `elapsedRealtime` and never leave the wrist.
 */
fun buildTickDelta(
    dayId: String,
    programExerciseId: Long,
    setIndex: Int,
    startedAtMillis: Long?,
    completedAtMillis: Long,
    editedAtMillis: Long = completedAtMillis,
    snapshot: WatchSnapshot? = null,
    reps: Int? = null,
): SetEditDelta = SetEditDelta(
    dayId = dayId,
    programExerciseId = programExerciseId,
    slot = SLOT_MAIN,
    setIndex = setIndex,
    done = true,
    reps = reps,
    editedAtMillis = editedAtMillis,
    planId = snapshot?.planId,
    runId = snapshot?.runId,
    exerciseId = snapshot?.day?.exercises?.firstOrNull {
        it.programExerciseId == programExerciseId && it.slot == SLOT_MAIN
    }?.exerciseId?.takeIf { it.isNotBlank() },
    startedAtMillis = startedAtMillis?.takeIf { it > 0L },
    completedAtMillis = completedAtMillis,
)

/**
 * The other direction: a deliberate long-press takes a set back (brief §6). It
 * carries `done = false` and **no stamps** — an untick retracts the tick's facts
 * rather than restating them, and the phone clears the row's start/complete millis
 * on a `done = false` delta for exactly that reason (the same rule the daily
 * checkmark reset applies to a stale check).
 */
fun buildUndoDelta(
    dayId: String,
    programExerciseId: Long,
    setIndex: Int,
    editedAtMillis: Long,
    snapshot: WatchSnapshot? = null,
): SetEditDelta = SetEditDelta(
    dayId = dayId,
    programExerciseId = programExerciseId,
    slot = SLOT_MAIN,
    setIndex = setIndex,
    done = false,
    editedAtMillis = editedAtMillis,
    planId = snapshot?.planId,
    runId = snapshot?.runId,
    exerciseId = snapshot?.day?.exercises?.firstOrNull {
        it.programExerciseId == programExerciseId && it.slot == SLOT_MAIN
    }?.exerciseId?.takeIf { it.isNotBlank() },
)

/** The watch only ever ticks the main track; a superset partner follows its round. */
private const val SLOT_MAIN = "main"
