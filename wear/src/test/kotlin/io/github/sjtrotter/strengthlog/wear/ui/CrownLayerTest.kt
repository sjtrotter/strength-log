package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crown layer's decisions (brief §6): which exercise the dial is on, which
 * round a peek looks at, and which round a hold takes back.
 */
class CrownLayerTest {

    private fun exercise(id: Long, doneFlags: List<Boolean>) = WatchExercise(
        programExerciseId = id,
        slot = "main",
        name = "Ex$id",
        goal = 100.0,
        perHand = false,
        supersetPartnerName = null,
        sets = doneFlags.map { WatchSet(100.0, 5, "WORK", done = it) },
        ssSets = emptyList(),
    )

    private fun snapshot(vararg exercises: WatchExercise) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay("A", "Day A", accentIndex = 0, exercises = exercises.toList()),
        unit = "lb",
    )

    // --- exercise selection ------------------------------------------------------

    private val day = snapshot(
        exercise(1L, listOf(false, false)),
        exercise(2L, listOf(false, false)),
        exercise(3L, listOf(false)),
    )

    @Test
    fun `with no selection the dial follows the day's own order`() {
        assertEquals(0, ExerciseSelection.resolve(day, selectedIndex = null))
    }

    @Test
    fun `the crown's choice wins over the first exercise with work left`() {
        assertEquals(2, ExerciseSelection.resolve(day, selectedIndex = 2))
    }

    @Test
    fun `the choice survives beginning it — the derived rule must not yank the dial back`() {
        val partlyLogged = snapshot(
            exercise(1L, listOf(false, false)),
            exercise(2L, listOf(false, false)),
            exercise(3L, listOf(true)),
        )
        // Exercise 3's only set is logged, so it reverts…
        assertEquals(0, ExerciseSelection.resolve(partlyLogged, selectedIndex = 2))
        // …but while it still has work left, the choice holds even mid-exercise.
        val midExercise = snapshot(
            exercise(1L, listOf(false, false)),
            exercise(2L, listOf(false, false)),
            exercise(3L, listOf(true, false)),
        )
        assertEquals(2, ExerciseSelection.resolve(midExercise, selectedIndex = 2))
    }

    @Test
    fun `a selection the day no longer has is ignored`() {
        assertEquals(0, ExerciseSelection.resolve(day, selectedIndex = 9))
        assertEquals(0, ExerciseSelection.resolve(snapshot(), selectedIndex = 1))
    }

    @Test
    fun `the crown steps between exercises and stops at the ends`() {
        assertEquals(1, ExerciseSelection.move(fromIndex = 0, detents = 1, exerciseCount = 3))
        assertEquals(2, ExerciseSelection.move(fromIndex = 0, detents = 5, exerciseCount = 3))
        assertEquals(0, ExerciseSelection.move(fromIndex = 1, detents = -4, exerciseCount = 3))
        assertEquals(0, ExerciseSelection.move(fromIndex = 0, detents = 1, exerciseCount = 0))
    }

    // --- peek --------------------------------------------------------------------

    @Test
    fun `the first turn enters the peek from where the lifter actually is`() {
        val peek = PeekScrub.turn(current = null, currentRoundIndex = 3, detents = -1, roundCount = 6, nowElapsedMillis = 100L)
        assertEquals(2, peek?.roundIndex)
        assertEquals(100L, peek?.lastTurnElapsedMillis)
    }

    @Test
    fun `scrubbing continues from the peeked round, not the current one`() {
        val first = PeekScrub.turn(null, currentRoundIndex = 3, detents = -1, roundCount = 6, nowElapsedMillis = 100L)
        val second = PeekScrub.turn(first, currentRoundIndex = 3, detents = -1, roundCount = 6, nowElapsedMillis = 200L)
        assertEquals(1, second?.roundIndex)
        assertEquals(200L, second?.lastTurnElapsedMillis)
    }

    @Test
    fun `the peek clamps to the exercise's own rounds`() {
        assertEquals(5, PeekScrub.turn(null, 3, detents = 9, roundCount = 6, nowElapsedMillis = 0L)?.roundIndex)
        assertEquals(0, PeekScrub.turn(null, 3, detents = -9, roundCount = 6, nowElapsedMillis = 0L)?.roundIndex)
    }

    @Test
    fun `an exercise with no rounds has nothing to peek at`() {
        assertNull(PeekScrub.turn(null, 0, detents = 1, roundCount = 0, nowElapsedMillis = 0L))
    }

    @Test
    fun `the peek returns once the crown has sat still`() {
        val peek = PeekState(roundIndex = 2, lastTurnElapsedMillis = 1_000L)
        assertFalse(PeekScrub.expired(peek, nowElapsedMillis = 1_000L + PeekScrub.IDLE_TIMEOUT_MILLIS - 1L))
        assertTrue(PeekScrub.expired(peek, nowElapsedMillis = 1_000L + PeekScrub.IDLE_TIMEOUT_MILLIS))
        assertFalse(PeekScrub.expired(null, nowElapsedMillis = Long.MAX_VALUE))
    }

    // --- undo target -------------------------------------------------------------

    @Test
    fun `a hold takes back the most recently logged round`() {
        assertEquals(1, UndoTarget.of(listOf(true, true, false, false)))
        assertEquals(3, UndoTarget.of(listOf(true, true, true, true)))
    }

    @Test
    fun `nothing logged, nothing to take back`() {
        assertNull(UndoTarget.of(listOf(false, false)))
        assertNull(UndoTarget.of(emptyList()))
    }
}
