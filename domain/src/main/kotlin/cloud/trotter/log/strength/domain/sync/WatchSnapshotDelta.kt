package cloud.trotter.log.strength.domain.sync

/**
 * Applies one watch delta to a snapshot for display only. The phone-owned stamp and
 * every untouched field are preserved; a missing exercise, track, or row is a no-op
 * so prescription drift cannot corrupt another row.
 */
fun applyDelta(snapshot: WatchSnapshot, delta: SetEditDelta): WatchSnapshot =
    snapshot.copy(
        day = snapshot.day.copy(
            exercises = snapshot.day.exercises.map { exercise ->
                if (exercise.programExerciseId != delta.programExerciseId) exercise
                else exercise.applying(delta)
            },
        ),
    )

/** A swap echo changes only the displayed name; seeding and identity stay phone-owned. */
fun applyDelta(snapshot: WatchSnapshot, delta: ExerciseSwapDelta): WatchSnapshot =
    snapshot.copy(
        day = snapshot.day.copy(
            exercises = snapshot.day.exercises.map { exercise ->
                if (exercise.programExerciseId != delta.programExerciseId) exercise
                else exercise.copy(name = delta.exerciseName)
            },
        ),
    )

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
