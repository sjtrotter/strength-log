package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot

/**
 * What the crown and the long-press address (brief §6), as pure decisions: which
 * exercise the dial is on when the lifter has chosen one, which round a peek is
 * looking at, and which round a hold would untick. The composables read these; they
 * never decide them, which is what keeps the crown layer testable without a wrist.
 */

/** Which exercise the dial shows when the crown has been used to pick one. */
object ExerciseSelection {

    /**
     * The crown's choice, while it still has work left; otherwise the derived
     * "first exercise with work left" ([currentExerciseIndex]).
     *
     * The override deliberately outlives the *start* of the chosen exercise: having
     * skipped ahead to the third lift, the lifter must not be yanked back to the
     * first by the derived rule the moment they begin. It falls away by itself once
     * every round of the chosen exercise is logged — there is nothing left to hold
     * the dial there, and the day's own order takes over again.
     */
    fun resolve(snapshot: WatchSnapshot, selectedIndex: Int?): Int {
        val exercises = snapshot.day.exercises
        val chosen = selectedIndex?.takeIf { it in exercises.indices }
        val hasWorkLeft = chosen != null && exercises[chosen].sets.any { !it.done }
        return if (chosen != null && hasWorkLeft) chosen else currentExerciseIndex(snapshot)
    }

    /** Where [detents] of crown from [fromIndex] land: clamped, never wrapping —
     *  the day has ends, and rolling off one into the other loses the lifter. */
    fun move(fromIndex: Int, detents: Int, exerciseCount: Int): Int {
        if (exerciseCount <= 0) return 0
        return (fromIndex + detents).coerceIn(0, exerciseCount - 1)
    }
}

/** A peek in flight: which round is being looked at, and when the crown last moved. */
data class PeekState(val roundIndex: Int, val lastTurnElapsedMillis: Long)

/**
 * Crown-scrub across the current exercise's rounds — read-only browsing (§6).
 *
 * A rotary crown has no "release" event, so the brief's `↺ RELEASE TO RETURN` is
 * read here as *stop turning*: after [IDLE_TIMEOUT_MILLIS] without a turn the peek
 * snaps back to where the lifter actually is. Nothing else exits it, and nothing
 * about it is persisted — a peek is a glance, not a place.
 */
object PeekScrub {

    /** How long the crown must sit still before the dial returns on its own. */
    const val IDLE_TIMEOUT_MILLIS = 1_500L

    /**
     * The peek after a turn of [detents], starting from [currentRoundIndex] when
     * this turn is the one that enters the peek. Clamped to the exercise's rounds;
     * null when there is nothing to scrub.
     */
    fun turn(
        current: PeekState?,
        currentRoundIndex: Int,
        detents: Int,
        roundCount: Int,
        nowElapsedMillis: Long,
    ): PeekState? {
        if (roundCount <= 0) return null
        val from = current?.roundIndex ?: currentRoundIndex
        val index = (from + detents).coerceIn(0, roundCount - 1)
        return PeekState(roundIndex = index, lastTurnElapsedMillis = nowElapsedMillis)
    }

    /** True once the crown has sat still long enough to return the dial. */
    fun expired(peek: PeekState?, nowElapsedMillis: Long): Boolean {
        val last = peek?.lastTurnElapsedMillis ?: return false
        return nowElapsedMillis - last >= IDLE_TIMEOUT_MILLIS
    }
}

/** Which round a 700ms hold on the disc would untick (§6). */
object UndoTarget {

    /**
     * The most recently logged round of the exercise in front of the lifter, or
     * null when none of it is logged and there is nothing to take back.
     *
     * "Most recently" is read as the *last logged round in set order*: rounds are
     * logged in order (the dial only ever offers the first undone one), so the two
     * readings agree, and this one needs no memory of the session to be right after
     * a process death.
     */
    fun of(doneFlags: List<Boolean>): Int? = doneFlags.indexOfLast { it }.takeIf { it >= 0 }
}
