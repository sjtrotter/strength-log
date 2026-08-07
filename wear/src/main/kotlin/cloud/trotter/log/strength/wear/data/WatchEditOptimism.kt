package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet

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

    /**
     * A swap's optimistic echo is the **name and nothing else** (#90). The lifter
     * asked for a different lift and must see it at once; the sets that go with it
     * are seeded phone-side from the new exercise's GOAL (§8.3) and cannot be guessed
     * here — drawing the replaced lift's numbers under the replacement's name is the
     * one echo that could put weight on a bar nobody prescribed.
     *
     * The name is *all* it changes. In particular the prescription stays: the dial
     * hides Swap while the request is unacked, so nothing renders it — and
     * [PendingEdits] now reads an empty `alternates` as the phone refusing the swap
     * ([PendingEdits.reconcileSwaps]'s drift condition). An echo that blanked the list
     * would be indistinguishable from that refusal, and would silently drop a swap
     * still in flight the moment the two ever met. Leaving it alone keeps the echo
     * incapable of settling anything, which is the property that matters.
     *
     * Likewise [WatchExercise.exerciseId] is left holding the *replaced* exercise:
     * the watch is echoing what it asked for, not asserting that the phone agreed,
     * and the ack is a slot identity only the phone can write.
     */
    fun applySwap(exercises: List<WatchExercise>, swap: ExerciseSwapDelta): List<WatchExercise> =
        exercises.map { exercise ->
            if (exercise.programExerciseId != swap.programExerciseId) {
                exercise
            } else {
                exercise.copy(name = swap.exerciseName)
            }
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
        seconds = delta.seconds ?: seconds,
    )

    private const val SLOT_MAIN = "main"
}
