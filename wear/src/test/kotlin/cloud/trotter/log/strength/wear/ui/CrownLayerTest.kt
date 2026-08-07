package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchCycleDay
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
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
    fun `an undo puts the dial back on the lift it reopened`() {
        val afterUndo = snapshot(
            exercise(1L, listOf(true, false)),
            exercise(2L, listOf(false, false)),
        )
        assertEquals(0, ExerciseSelection.resolve(afterUndo, selectedIndex = 0))

        val skipped = snapshot(
            exercise(1L, listOf(false, false)),
            exercise(2L, listOf(false)),
            exercise(3L, listOf(true, false)),
        )
        assertEquals(2, ExerciseSelection.resolve(skipped, selectedIndex = 2))
        assertEquals(0, ExerciseSelection.resolve(skipped, selectedIndex = null))
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

    // --- swipe: the next lift (v3 §3) ---------------------------------------------

    @Test
    fun `a swipe moves to the next lift and wraps round the day`() {
        val workLeft = listOf(true, true, true)
        assertEquals(1, ExerciseSelection.next(fromIndex = 0, hasWorkLeft = workLeft))
        assertEquals(0, ExerciseSelection.next(fromIndex = 2, hasWorkLeft = workLeft))
    }

    @Test
    fun `a swipe skips lifts with nothing left to do`() {
        assertEquals(2, ExerciseSelection.next(fromIndex = 0, hasWorkLeft = listOf(true, false, true)))
        // Nowhere else to go: the swipe stays put rather than pretending to move.
        assertEquals(1, ExerciseSelection.next(fromIndex = 1, hasWorkLeft = listOf(false, true, false)))
        assertEquals(0, ExerciseSelection.next(fromIndex = 0, hasWorkLeft = emptyList()))
    }

    // --- swipe: browsing the program's days (v3 §3) -------------------------------

    private val cycle = snapshot(exercise(1L, listOf(false))).copy(
        cycle = listOf(
            WatchCycleDay("A", "Lower"),
            WatchCycleDay("B", "Upper"),
            WatchCycleDay("C", "Full"),
        ),
    )

    @Test
    fun `browsing walks forward from today and wraps back to it`() {
        assertEquals(1, CycleBrowse.next(current = null, snapshot = cycle))
        assertEquals(2, CycleBrowse.next(current = 1, snapshot = cycle))
        // Round to today again, which is "not browsing" — a glance, not a place.
        assertNull(CycleBrowse.next(current = 2, snapshot = cycle))
    }

    @Test
    fun `there is nothing to browse without a cycle to walk`() {
        assertNull(CycleBrowse.next(current = null, snapshot = cycle.copy(cycle = emptyList())))
        assertNull(CycleBrowse.next(current = null, snapshot = cycle.copy(cycle = listOf(WatchCycleDay("A", "Lower")))))
        // A cycle that doesn't contain today is a cycle the watch can't place itself in.
        assertNull(CycleBrowse.next(current = null, snapshot = cycle.copy(cycle = listOf(WatchCycleDay("Z", "Other")))))
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

    @Test
    fun `the first forward flick proposes the best ranked alternate`() {
        assertEquals(SwapPreview(0, 100L), SwapPicker.turn(null, 1, 3, 100L))
    }

    @Test
    fun `the swap picker advances and clamps at the last alternate`() {
        assertEquals(SwapPreview(1, 200L), SwapPicker.turn(SwapPreview(0, 100L), 1, 3, 200L))
        assertEquals(SwapPreview(2, 300L), SwapPicker.turn(SwapPreview(1, 200L), 9, 3, 300L))
    }

    @Test
    fun `turning back before the first alternate keeps the prescribed lift`() {
        assertNull(SwapPicker.turn(SwapPreview(0, 100L), -1, 3, 200L))
        assertNull(SwapPicker.turn(null, -1, 3, 200L))
    }

    @Test
    fun `a lift with no alternates has nothing to pick`() {
        assertNull(SwapPicker.turn(SwapPreview(0, 100L), 1, 0, 200L))
        assertNull(SwapPicker.turn(null, 1, 0, 200L))
    }

    /**
     * The prescription can shrink under a live preview when a fresh snapshot lands.
     * The disc clamps so it keeps naming something; the confirm resolves through the
     * same function so it acts on exactly that one, rather than reading past the end
     * of the list and silently doing nothing while the disc still offers a choice.
     */
    @Test
    fun `a preview that outlived its alternate resolves onto the last one left`() {
        assertEquals(0, SwapPicker.resolve(2, alternateCount = 1))
        assertEquals(1, SwapPicker.resolve(2, alternateCount = 2))
        assertEquals(2, SwapPicker.resolve(2, alternateCount = 5))
        assertNull(SwapPicker.resolve(2, alternateCount = 0))
        assertNull(SwapPicker.resolve(null, alternateCount = 3))
    }

    @Test
    fun `the swap preview expires after its longer decision timeout`() {
        val preview = SwapPreview(0, 1_000L)
        assertFalse(SwapPicker.expired(preview, 1_000L + SwapPicker.IDLE_TIMEOUT_MILLIS - 1L))
        assertTrue(SwapPicker.expired(preview, 1_000L + SwapPicker.IDLE_TIMEOUT_MILLIS))
        assertFalse(SwapPicker.expired(null, Long.MAX_VALUE))
        assertTrue(SwapPicker.IDLE_TIMEOUT_MILLIS > PeekScrub.IDLE_TIMEOUT_MILLIS)
    }

    // --- undo target -------------------------------------------------------------

    // Three lifts; the first and the third each have their opening round logged. Which
    // of the two was logged LAST is a fact the done flags cannot carry — only the
    // watch's own ledger can.
    private val twoLogged = listOf(
        exercise(1L, listOf(true, false)),
        exercise(2L, listOf(false)),
        exercise(3L, listOf(true, false)),
    )
    private val aThenC = TickMemory.EMPTY.record(1L, 0, 40).record(3L, 0, 30)
    private val cThenA = TickMemory.EMPTY.record(3L, 0, 30).record(1L, 0, 40)

    @Test
    fun `the newest tick wins, and the done flags cannot tell you which that is`() {
        assertEquals(UndoTarget(2, 0), UndoTarget.of(twoLogged, 0, aThenC))
        assertEquals(UndoTarget(0, 0), UndoTarget.of(twoLogged, 0, cThenA))
    }

    @Test
    fun `where the lifter is looking does not change which set comes back`() {
        listOf(0, 1, 2).forEach { assertEquals(UndoTarget(2, 0), UndoTarget.of(twoLogged, it, aThenC)) }
        listOf(0, 1, 2).forEach { assertEquals(UndoTarget(0, 0), UndoTarget.of(twoLogged, it, cThenA)) }
    }

    @Test
    fun `re-ticking a round makes it the one that comes back`() {
        val retick = aThenC.record(1L, 0, 45)
        assertEquals(UndoTarget(0, 0), UndoTarget.of(twoLogged, 2, retick))
    }

    @Test
    fun `a tick the phone has already taken back is not offered again`() {
        val phoneUnticked = listOf(
            exercise(1L, listOf(true, false)),
            exercise(2L, listOf(false)),
            exercise(3L, listOf(false, false)),
        )
        assertEquals(UndoTarget(0, 0), UndoTarget.of(phoneUnticked, 0, aThenC))
    }

    @Test
    fun `a lift the program no longer holds is skipped`() {
        val gone = TickMemory.EMPTY.record(99L, 0, 20)
        assertEquals(UndoTarget(0, 0), UndoTarget.of(twoLogged, 0, gone))
    }

    @Test
    fun `with nothing remembered it falls back to the nearest logged round looking back`() {
        val phoneOnly = listOf(
            exercise(1L, listOf(false, false)),
            exercise(2L, listOf(true, true)),
        )
        assertEquals(UndoTarget(1, 1), UndoTarget.of(phoneOnly, 1, TickMemory.EMPTY))
        assertEquals(UndoTarget(1, 1), UndoTarget.of(phoneOnly, 0, TickMemory.EMPTY))
        assertEquals(
            UndoTarget(0, 1),
            UndoTarget.of(listOf(exercise(1L, listOf(true, true, false))), 0, TickMemory.EMPTY),
        )
    }

    @Test
    fun `nothing logged, nothing to take back`() {
        assertNull(UndoTarget.of(listOf(exercise(1L, listOf(false, false))), 0, TickMemory.EMPTY))
        assertNull(UndoTarget.of(emptyList(), 0, aThenC))
        assertNull(UndoTarget.of(listOf(exercise(1L, listOf(false))), 0, aThenC))
    }

    // --- the crown's choice is forgotten, not just ignored ----------------------

    @Test
    fun `a choice the day still honours is not stale`() {
        assertFalse(ExerciseSelection.isStale(day, 2))
        assertFalse(ExerciseSelection.isStale(day, null))
    }

    @Test
    fun `finishing the chosen lift makes the choice stale`() {
        val finished = snapshot(
            exercise(1L, listOf(false, false)),
            exercise(2L, listOf(false, false)),
            exercise(3L, listOf(true)),
        )
        assertTrue(ExerciseSelection.isStale(finished, 2))
        assertTrue(ExerciseSelection.isStale(finished, 7))
    }

    /**
     * The emission sequence a cross-lift undo actually sees. The lifter skipped ahead
     * to lift 2L, finished it, and held to take its last set back; lift 1L is still
     * untouched, so the derived rule would send the dial there the moment the choice
     * is forgotten.
     */
    @Test
    fun `a snapshot that predates an in-flight undo must not cost the lifter their place`() {
        fun day(secondLift: List<Boolean>, revision: Long) = snapshot(
            exercise(1L, listOf(false, false)),
            exercise(2L, secondLift),
        ).copy(revision = revision)

        // The undo has fired: the echo reopened 2L on the wrist and pointed the
        // selection at it, with the delta still queued.
        val echoed = day(listOf(true, false), revision = 5L)
        assertFalse(ExerciseSelection.shouldForget(echoed, 1, setOf(2L)))

        // Now the phone publishes something newer that predates the undo — an
        // unrelated edit, or its publisher restarting. Installed wholesale, it says
        // 2L is finished again, and staleness alone would forget the choice here.
        val predatesUndo = day(listOf(true, true), revision = 6L)
        assertTrue(ExerciseSelection.isStale(predatesUndo, 1))
        assertFalse(ExerciseSelection.shouldForget(predatesUndo, 1, setOf(2L)))

        // The confirming snapshot lands and the delta settles. The choice survived,
        // so the dial is on the set just taken back — not on the untouched first lift.
        val confirmed = day(listOf(true, false), revision = 7L)
        assertFalse(ExerciseSelection.shouldForget(confirmed, 1, emptySet()))
        assertEquals(1, ExerciseSelection.resolve(confirmed, selectedIndex = 1))
        assertEquals(0, ExerciseSelection.resolve(confirmed, selectedIndex = null))

        // Finishing 2L again for real, with nothing in flight, forgets it as before.
        val finishedAgain = day(listOf(true, true), revision = 8L)
        assertTrue(ExerciseSelection.shouldForget(finishedAgain, 1, emptySet()))
    }

    @Test
    fun `an edit against some other lift does not hold the clear off`() {
        val finished = snapshot(exercise(1L, listOf(false, false)), exercise(2L, listOf(true, true)))
        assertTrue(ExerciseSelection.shouldForget(finished, 1, setOf(1L)))
        assertTrue(ExerciseSelection.shouldForget(finished, 1, emptySet()))
        // A choice the day still honours is never forgotten, in flight or not.
        assertFalse(ExerciseSelection.shouldForget(finished, 0, emptySet()))
        assertFalse(ExerciseSelection.shouldForget(finished, null, setOf(2L)))
        // A choice pointing past the end of the day can have nothing in flight.
        assertTrue(ExerciseSelection.shouldForget(finished, 7, setOf(1L, 2L)))
    }

    @Test
    fun `an undo that reopens the chosen lift must not clear it`() {
        val reopened = snapshot(
            exercise(1L, listOf(false, false)),
            exercise(2L, listOf(false, false)),
            exercise(3L, listOf(false)),
        )
        assertFalse(ExerciseSelection.isStale(reopened, 2))
        assertEquals(2, ExerciseSelection.resolve(reopened, 2))
    }
}
