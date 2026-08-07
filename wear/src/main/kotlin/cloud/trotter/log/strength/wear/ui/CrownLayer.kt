package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

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

    /**
     * True once the day has stopped honouring [selectedIndex] — the chosen lift is
     * finished (or gone), so [resolve] is already ignoring it.
     *
     * The choice must eventually be *cleared*, not merely ignored: a selection left
     * lying around is one phone-side untick away from being honoured again, which
     * would yank the dial back to a lift the lifter finished and walked away from.
     * Ignoring is not forgetting. When it is safe to forget is [shouldForget].
     */
    fun isStale(snapshot: WatchSnapshot, selectedIndex: Int?): Boolean =
        selectedIndex != null && resolve(snapshot, selectedIndex) != selectedIndex

    /**
     * True when the choice should be forgotten: the day has stopped honouring it
     * **and** nothing of ours is still in flight against that lift.
     *
     * The settlement clause is what makes this safe mid-undo. An undo reopens its
     * lift optimistically, but a snapshot can arrive from the phone with a newer
     * revision and the *pre-undo* state — an unrelated edit, or the phone's
     * publisher restarting — and is installed wholesale. Judged on staleness alone
     * the choice would be dropped at exactly that moment, and the confirming
     * snapshot would then land the dial on an earlier unfinished lift instead of on
     * the set the lifter just took back. A lift with an unacked delta against it is
     * a lift nothing has been decided about yet, so [pendingExerciseIds] holds the
     * clear off until the phone has answered.
     */
    fun shouldForget(
        snapshot: WatchSnapshot,
        selectedIndex: Int?,
        pendingExerciseIds: Set<Long>,
    ): Boolean {
        if (selectedIndex == null || !isStale(snapshot, selectedIndex)) return false
        val chosen = snapshot.day.exercises.getOrNull(selectedIndex) ?: return true
        return chosen.programExerciseId !in pendingExerciseIds
    }

    /** Where [detents] of crown from [fromIndex] land: clamped, never wrapping —
     *  the day has ends, and rolling off one into the other loses the lifter. */
    fun move(fromIndex: Int, detents: Int, exerciseCount: Int): Int {
        if (exerciseCount <= 0) return 0
        return (fromIndex + detents).coerceIn(0, exerciseCount - 1)
    }

    /**
     * The next lift a swipe moves to (v3 §3): the following one with work left,
     * wrapping through the day's own order. Finished lifts are skipped because
     * [resolve] would bounce straight off them, and a swipe that visibly does
     * nothing is worse than no swipe at all. Returns [fromIndex] when the day
     * holds nothing else to go to.
     */
    fun next(fromIndex: Int, hasWorkLeft: List<Boolean>): Int {
        if (hasWorkLeft.isEmpty()) return fromIndex
        for (step in 1..hasWorkLeft.size) {
            val index = Math.floorMod(fromIndex + step, hasWorkLeft.size)
            if (hasWorkLeft[index]) return index
        }
        return fromIndex
    }
}

/**
 * Browsing the program's other days from the overview (v3 §3) — the same "a glance
 * is not a place" rule as the crown's peek, one axis out: it moves forward only,
 * and coming back round to today is how it ends, which is why today is `null`
 * rather than an index. Nothing about it is persisted.
 */
object CycleBrowse {

    /** The day after [current] (today when it is null), or null once that is today
     *  again. Null too when there is no cycle worth walking. */
    fun next(current: Int?, snapshot: WatchSnapshot): Int? {
        val days = snapshot.cycle
        val today = days.indexOfFirst { it.dayId == snapshot.day.dayId }
        if (today < 0 || days.size <= 1) return null
        val index = ((current ?: today) + 1) % days.size
        return index.takeIf { it != today }
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

/**
 * Which round of which exercise a 700ms hold on the disc would untick (§6). The
 * exercise travels with the round because the undo reaches across the day: the set
 * that wants taking back is often the one that *moved* the dial off its exercise.
 */
data class UndoTarget(val exerciseIndex: Int, val roundIndex: Int) {

    companion object {

        /**
         * The day's most recent tick: **the latest one this watch still remembers
         * making**, wherever in the day it landed, and only failing that the nearest
         * logged round looking back from [fromExerciseIndex]. Null when nothing is
         * logged anywhere and there is nothing to take back.
         *
         * Recency has to come from [memory] because done flags carry no chronology —
         * ticking round A then round C leaves a day indistinguishable from C then A,
         * and on a destructive gesture guessing wrong removes a set the lifter did
         * not touch. A remembered tick is only trusted while the snapshot still
         * agrees the round is logged: a phone-side untick or edit can strip it out
         * from under the memory, and a stale entry must not outrank a real one.
         *
         * **The fallback degrades honestly.** With no live remembered tick — every
         * candidate logged on the phone, or the memory genuinely lost — it returns
         * the positionally nearest logged round rather than the temporally latest.
         * Within a lift the two agree (rounds are logged in order). Across lifts
         * they can disagree, and this is the case where they do: the lifter is
         * offered the set nearest where they are looking, which the disc names.
         */
        fun of(
            exercises: List<WatchExercise>,
            fromExerciseIndex: Int,
            memory: TickMemory = TickMemory.EMPTY,
        ): UndoTarget? {
            if (exercises.isEmpty()) return null
            return remembered(exercises, memory) ?: nearestLookingBack(exercises, fromExerciseIndex)
        }

        /** The newest remembered tick the snapshot still calls logged. */
        private fun remembered(exercises: List<WatchExercise>, memory: TickMemory): UndoTarget? =
            memory.newestFirst().firstNotNullOfOrNull { ref ->
                val index = exercises.indexOfFirst { it.programExerciseId == ref.programExerciseId }
                val stillLogged = exercises.getOrNull(index)
                    ?.sets?.getOrNull(ref.roundIndex)?.done == true
                if (stillLogged) UndoTarget(index, ref.roundIndex) else null
            }

        /**
         * Position as a stand-in for time: the first logged round found walking back
         * from [fromExerciseIndex], wrapping through the day. The wrap is what keeps
         * a lift the crown skipped ahead to reachable once it is finished and the
         * dial has fallen back to the first lift with work left.
         */
        private fun nearestLookingBack(exercises: List<WatchExercise>, fromExerciseIndex: Int): UndoTarget? {
            for (step in exercises.indices) {
                val index = Math.floorMod(fromExerciseIndex - step, exercises.size)
                val round = exercises[index].sets.indexOfLast { it.done }
                if (round >= 0) return UndoTarget(index, round)
            }
            return null
        }
    }
}
