package cloud.trotter.log.strength.domain.sync

/**
 * Applies one watch delta to a snapshot for display only. The phone-owned stamp and
 * every untouched field are preserved; a missing exercise, track, or row is a no-op
 * so prescription drift cannot corrupt another row. Only the displayed plan and
 * day may echo an edit; absent identity keeps older peers' behavior.
 */
fun applyDelta(snapshot: WatchSnapshot, delta: SetEditDelta): WatchSnapshot =
    if (!snapshot.matches(delta.planId, delta.dayId) || snapshot.hasExerciseMismatch(delta)) snapshot else snapshot.copy(
        day = snapshot.day.copy(
            exercises = snapshot.day.exercises.map { exercise ->
                if (exercise.programExerciseId != delta.programExerciseId) exercise
                else exercise.applying(delta)
            },
        ),
    )

/** Older snapshots without content identity cannot confirm an exercise mismatch. */
fun WatchSnapshot.hasExerciseMismatch(delta: SetEditDelta): Boolean =
    // Only a run-aware delta is judged by exercise: an old phone publishes no processed
    // stamps to settle it on, and it applies a run-less delta to the slot's current lift.
    delta.runId != null && delta.exerciseId != null && day.exercises.any {
        it.programExerciseId == delta.programExerciseId && it.slot == delta.slot &&
            it.exerciseId.isNotBlank() && it.exerciseId != delta.exerciseId
    }

/** A swap echo changes only the displayed name; seeding and identity stay phone-owned. */
fun applyDelta(snapshot: WatchSnapshot, delta: ExerciseSwapDelta): WatchSnapshot =
    if (!snapshot.matches(delta.planId, delta.dayId)) snapshot else snapshot.copy(
        day = snapshot.day.copy(
            exercises = snapshot.day.exercises.map { exercise ->
                if (exercise.programExerciseId != delta.programExerciseId) exercise
                else exercise.copy(name = delta.exerciseName)
            },
        ),
    )

private fun WatchSnapshot.matches(planId: Long?, dayId: String): Boolean =
    day.dayId == dayId &&
        (this.planId == null || planId == null || this.planId == planId)

private fun WatchExercise.applying(delta: SetEditDelta): WatchExercise {
    val editingMain = delta.slot == SLOT_MAIN
    val track = if (editingMain) sets else ssSets
    if (delta.setIndex !in track.indices) return this

    val updatedTrack = track.mapIndexed { index, set ->
        if (index == delta.setIndex) set.applying(delta) else set
    }
    // One tick represents the whole aligned superset round.
    val updatedSs = if (editingMain && delta.done != null && ssSets.isNotEmpty()) {
        ssSets.mapIndexed { index, set ->
            if (index == delta.setIndex) set.copy(done = delta.done) else set
        }
    } else {
        ssSets
    }

    return if (editingMain) copy(sets = updatedTrack, ssSets = updatedSs)
    else copy(ssSets = updatedTrack)
}

private fun WatchSet.applying(delta: SetEditDelta): WatchSet = copy(
    weightLb = delta.weightLb ?: weightLb,
    reps = delta.reps ?: reps,
    done = delta.done ?: done,
    seconds = delta.seconds ?: seconds,
)

private const val SLOT_MAIN = "main"
