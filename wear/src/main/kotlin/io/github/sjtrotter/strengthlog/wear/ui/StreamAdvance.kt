package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot

/** Where the exercise-stream screen goes right after a tick marks a round done. */
sealed interface StreamAdvance {
    /** Jump to the next undone round, still within the same exercise. */
    data class NextRound(val index: Int) : StreamAdvance

    /** This exercise is done but other exercises in the day aren't — return to the list. */
    data object BackToList : StreamAdvance

    /** Every exercise in the day is now done. */
    data object DayDone : StreamAdvance
}

/**
 * Pure port of the design digest's `advanceD` (appendix): after a tick, jump to
 * the next undone round *within this exercise only* — the watch never
 * auto-opens the next exercise (an explicit design rule in the digest). If none
 * are left here, either the whole day is done or we drop back to the list.
 *
 * [exerciseDoneFlags] is this exercise's rounds' `done` flags *after* the tick
 * that triggered this decision. [allExercisesDoneAfterThisTick] must be
 * computed from the same post-tick state — see [allExercisesDone].
 */
fun decideStreamAdvance(
    exerciseDoneFlags: List<Boolean>,
    allExercisesDoneAfterThisTick: Boolean,
): StreamAdvance {
    val nextUndone = exerciseDoneFlags.indexOfFirst { !it }
    return when {
        nextUndone >= 0 -> StreamAdvance.NextRound(nextUndone)
        allExercisesDoneAfterThisTick -> StreamAdvance.DayDone
        else -> StreamAdvance.BackToList
    }
}

/**
 * True once every round of every exercise in the day is done — the day-done
 * trigger. A superset's partner round is aligned 1:1 with its main round and
 * flipped together by [io.github.sjtrotter.strengthlog.wear.data.WatchEditOptimism]'s
 * one-tick-per-round rule, so checking the main track alone is sufficient.
 */
fun allExercisesDone(snapshot: WatchSnapshot): Boolean =
    snapshot.day.exercises.all { exercise -> exercise.sets.all { it.done } }
