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

/** The 2a tick/advance decision (design digest appendix `advanceD`), pulled out pure. */
class StreamAdvanceTest {

    @Test
    fun `jumps to the next undone round within the same exercise`() {
        val advance = decideStreamAdvance(
            exerciseDoneFlags = listOf(true, true, false, false),
            allExercisesDoneAfterThisTick = false,
        )
        assertEquals(StreamAdvance.NextRound(2), advance)
    }

    @Test
    fun `drops back to the list when this exercise is done but others aren't`() {
        val advance = decideStreamAdvance(
            exerciseDoneFlags = listOf(true, true, true),
            allExercisesDoneAfterThisTick = false,
        )
        assertEquals(StreamAdvance.BackToList, advance)
    }

    @Test
    fun `shows day-done when this exercise and every other exercise is done`() {
        val advance = decideStreamAdvance(
            exerciseDoneFlags = listOf(true, true, true),
            allExercisesDoneAfterThisTick = true,
        )
        assertEquals(StreamAdvance.DayDone, advance)
    }

    @Test
    fun `never auto-opens a different exercise — only NextRound, BackToList, or DayDone`() {
        // The digest's explicit rule: "exercise complete -> back to the list; the
        // watch never auto-opens the next one." There is no result variant that
        // names another exercise — this test pins the sealed type's shape.
        val results = listOf(
            decideStreamAdvance(listOf(false), false),
            decideStreamAdvance(listOf(true), false),
            decideStreamAdvance(listOf(true), true),
        )
        assertTrue(results[0] is StreamAdvance.NextRound)
        assertTrue(results[1] is StreamAdvance.BackToList)
        assertTrue(results[2] is StreamAdvance.DayDone)
    }

    private fun watchSet(done: Boolean) = WatchSet(weightLb = 100.0, reps = 5, kind = "WORK", done = done)

    private fun exercise(id: Long, doneFlags: List<Boolean>) = WatchExercise(
        programExerciseId = id,
        slot = "main",
        name = "Ex$id",
        goal = 100.0,
        perHand = false,
        supersetPartnerName = null,
        sets = doneFlags.map { watchSet(it) },
        ssSets = emptyList(),
    )

    private fun snapshot(exercises: List<WatchExercise>) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay("A", "Day", accentIndex = 0, exercises = exercises),
        unit = "lb",
    )

    @Test
    fun `allExercisesDone is true only when every exercise's every round is done`() {
        val allDone = snapshot(listOf(exercise(1, listOf(true, true)), exercise(2, listOf(true))))
        assertTrue(allExercisesDone(allDone))

        val oneUndone = snapshot(listOf(exercise(1, listOf(true, false)), exercise(2, listOf(true))))
        assertFalse(allExercisesDone(oneUndone))
    }

    // --- nextExerciseLabel: the "next" name the day-list rest pill shows (issue #81) ---

    @Test
    fun `next label is the first later exercise that still has an undone set`() {
        val snap = snapshot(listOf(exercise(1, listOf(true, true)), exercise(2, listOf(false))))
        assertEquals("Ex2", nextExerciseLabel(snap, justFinishedExerciseId = 1))
    }

    @Test
    fun `the just-finished exercise is excluded even if its optimistic flags read undone`() {
        // Exercise 1's flags still show an undone set (optimism lag), but it's the one
        // we just left — the next is the first OTHER undone exercise.
        val snap = snapshot(listOf(exercise(1, listOf(true, false)), exercise(2, listOf(false))))
        assertEquals("Ex2", nextExerciseLabel(snap, justFinishedExerciseId = 1))
    }

    @Test
    fun `next label is blank when every other exercise is already done`() {
        val snap = snapshot(listOf(exercise(1, listOf(true)), exercise(2, listOf(true, true))))
        assertEquals("", nextExerciseLabel(snap, justFinishedExerciseId = 1))
    }

    // --- listRestPill: which rest the day-list pill surfaces (issue #81) ---

    @Test
    fun `the hoisted between-exercise rest wins over a controller fallback`() {
        val controller = RestTimerController.ActiveRest(deadlineMillis = 20_000L, nextLabel = "within")
        val pill = listRestPill(
            hoistedDeadline = 30_000L,
            hoistedLabel = "Bench",
            controllerRest = controller,
            nowElapsedMillis = 0L,
        )
        assertEquals(ListRestPill(30_000L, "Bench"), pill)
    }

    @Test
    fun `a swipe-dismissed within-exercise rest surfaces when there is no hoisted rest`() {
        val controller = RestTimerController.ActiveRest(deadlineMillis = 20_000L, nextLabel = "190 × 5")
        val pill = listRestPill(
            hoistedDeadline = 0L,
            hoistedLabel = "",
            controllerRest = controller,
            nowElapsedMillis = 0L,
        )
        assertEquals(ListRestPill(20_000L, "190 × 5"), pill)
    }

    @Test
    fun `an expired deadline shows no pill`() {
        val pill = listRestPill(
            hoistedDeadline = 30_000L,
            hoistedLabel = "Bench",
            controllerRest = null,
            nowElapsedMillis = 30_000L,
        )
        assertNull(pill)
    }

    @Test
    fun `no rest at all shows no pill`() {
        assertNull(listRestPill(hoistedDeadline = 0L, hoistedLabel = "", controllerRest = null, nowElapsedMillis = 0L))
    }
}
