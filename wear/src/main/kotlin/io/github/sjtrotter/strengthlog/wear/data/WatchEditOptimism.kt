package io.github.sjtrotter.strengthlog.wear.data

import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet

/**
 * The optimistic on-wrist echo of an edit (spec §9: the watch renders optimistically
 * and reconciles on the next snapshot). Applies only *non-derived* changes — the
 * addressed row's touched fields, plus the one-tick-per-round pairing that flips the
 * aligned superset round — exactly the shape the phone will confirm. Cascade and
 * seeding are phone-authoritative and arrive in the next snapshot, which overwrites
 * this echo (last-write-wins).
 *
 * Shared by [FakeWatchClient] (standalone) and [DataLayerWatchClient] (real) so the
 * echo rule lives in one place.
 */
object WatchEditOptimism {

    fun apply(exercises: List<WatchExercise>, delta: SetEditDelta): List<WatchExercise> =
        exercises.map { exercise ->
            if (exercise.programExerciseId != delta.programExerciseId) exercise
            else applyToExercise(exercise, delta)
        }

    private fun applyToExercise(exercise: WatchExercise, delta: SetEditDelta): WatchExercise {
        val editingMain = delta.slot == SLOT_MAIN
        val track = if (editingMain) exercise.sets else exercise.ssSets
        if (delta.setIndex !in track.indices) return exercise

        val updatedTrack = track.mapIndexed { i, set ->
            if (i == delta.setIndex) set.applying(delta) else set
        }

        // One tick per round: a done edit on the main row dims the aligned partner
        // round too — there is no independent tick on the sub-row.
        val done = delta.done
        val updatedSs = if (editingMain && done != null && exercise.ssSets.isNotEmpty()) {
            exercise.ssSets.mapIndexed { i, set -> if (i == delta.setIndex) set.copy(done = done) else set }
        } else {
            exercise.ssSets
        }

        return if (editingMain) {
            exercise.copy(sets = updatedTrack, ssSets = updatedSs)
        } else {
            exercise.copy(ssSets = updatedTrack)
        }
    }

    private fun WatchSet.applying(delta: SetEditDelta): WatchSet = copy(
        weightLb = delta.weightLb ?: weightLb,
        reps = delta.reps ?: reps,
        done = delta.done ?: done,
    )

    private const val SLOT_MAIN = "main"
}
