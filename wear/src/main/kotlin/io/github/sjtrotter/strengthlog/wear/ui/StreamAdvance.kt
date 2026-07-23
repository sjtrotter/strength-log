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

/**
 * The name the between-exercise rest pill shows as "next" (issue #81): the first
 * exercise in day order that still has an undone set, **excluding** the one just
 * finished ([justFinishedExerciseId]) — its optimistic flags may still read undone
 * for a beat, but the lifter is leaving it, so it's never its own "next". "" when
 * nothing else remains (the pill then shows a bare `REST m:ss`).
 */
fun nextExerciseLabel(snapshot: WatchSnapshot, justFinishedExerciseId: Long): String =
    snapshot.day.exercises
        .firstOrNull { it.programExerciseId != justFinishedExerciseId && it.sets.any { s -> !s.done } }
        ?.name
        .orEmpty()

/** The day-list rest pill's payload: a deadline to count down and the next exercise's name. */
data class ListRestPill(val deadlineMillis: Long, val nextLabel: String)

/**
 * Which rest, if any, the day-list pill shows. The hoisted between-exercise rest
 * ([hoistedDeadline] > 0, issue #81) wins; otherwise a still-pending within-exercise
 * rest whose countdown screen the lifter swipe-dismissed ([controllerRest]) gets the
 * pill as its only on-screen indicator (the accepted quirk from PRs #77–#80). Either
 * source is ignored once its deadline has passed — the buzz is the controller's job,
 * the pill just stops showing.
 */
fun listRestPill(
    hoistedDeadline: Long,
    hoistedLabel: String,
    controllerRest: RestTimerController.ActiveRest?,
    nowElapsedMillis: Long,
): ListRestPill? {
    if (hoistedDeadline > 0L && !RestTimer.isExpired(hoistedDeadline, nowElapsedMillis)) {
        return ListRestPill(hoistedDeadline, hoistedLabel)
    }
    if (controllerRest != null && !RestTimer.isExpired(controllerRest.deadlineMillis, nowElapsedMillis)) {
        return ListRestPill(controllerRest.deadlineMillis, controllerRest.nextLabel)
    }
    return null
}
