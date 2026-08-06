package cloud.trotter.log.strength.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one outbound edit: done, started, completed — all on the same delta. */
class TickDeltaTest {

    @Test
    fun `the tick carries done and both stamps in one delta`() {
        val delta = buildTickDelta(
            dayId = "A",
            programExerciseId = 7L,
            setIndex = 2,
            startedAtMillis = 1_700_000_000_000L,
            completedAtMillis = 1_700_000_042_000L,
        )
        assertEquals(true, delta.done)
        assertEquals(1_700_000_000_000L, delta.startedAtMillis)
        assertEquals(1_700_000_042_000L, delta.completedAtMillis)
        assertEquals("A", delta.dayId)
        assertEquals(7L, delta.programExerciseId)
        assertEquals(2, delta.setIndex)
        assertEquals("main", delta.slot)
    }

    @Test
    fun `the edit stamp defaults to the moment the set was completed`() {
        val delta = buildTickDelta("A", 1L, 0, startedAtMillis = 1L, completedAtMillis = 99L)
        assertEquals(99L, delta.editedAtMillis)
    }

    @Test
    fun `an unobserved start is null rather than zero`() {
        // A set the watch never saw START on (restored mid-set from an older
        // build, or ticked straight from Ready) sends no start time at all — the
        // phone reads null as "not observed", but would read 0 as the epoch.
        assertNull(buildTickDelta("A", 1L, 0, startedAtMillis = 0L, completedAtMillis = 99L).startedAtMillis)
        assertNull(buildTickDelta("A", 1L, 0, startedAtMillis = null, completedAtMillis = 99L).startedAtMillis)
    }

    @Test
    fun `the tick never touches a value field`() {
        val delta = buildTickDelta("A", 1L, 0, startedAtMillis = 1L, completedAtMillis = 2L)
        assertTrue(delta.weightLb == null && delta.reps == null && delta.seconds == null)
    }

    @Test
    fun `an undo unticks the set and carries no stamps at all`() {
        val delta = buildUndoDelta(dayId = "A", programExerciseId = 7L, setIndex = 2, editedAtMillis = 500L)
        assertEquals(false, delta.done)
        assertNull(delta.startedAtMillis)
        assertNull(delta.completedAtMillis)
        assertEquals(500L, delta.editedAtMillis)
        assertEquals("main", delta.slot)
        assertEquals(2, delta.setIndex)
        assertTrue(delta.weightLb == null && delta.reps == null && delta.seconds == null)
    }
}
