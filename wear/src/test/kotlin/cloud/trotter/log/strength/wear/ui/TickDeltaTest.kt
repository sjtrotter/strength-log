package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
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

    @Test
    fun `an undo addresses its own lift, so reaching across the day needs no new wire field`() {
        val squat = buildUndoDelta(dayId = "A", programExerciseId = 7L, setIndex = 2, editedAtMillis = 500L)
        val press = buildUndoDelta(dayId = "A", programExerciseId = 9L, setIndex = 1, editedAtMillis = 500L)

        // Cross-lift undo rides (dayId, programExerciseId, slot, setIndex), so an old phone sees an ordinary untick.
        listOf(squat to 7L, press to 9L).forEach { (delta, programExerciseId) ->
            assertEquals(programExerciseId, delta.programExerciseId)
            assertEquals("main", delta.slot)
            assertEquals(false, delta.done)
            assertEquals(1, delta.schemaVersion)
            assertNull(delta.weightLb)
            assertNull(delta.reps)
            assertNull(delta.seconds)
            assertNull(delta.startedAtMillis)
            assertNull(delta.completedAtMillis)
        }
    }

    /** [F] */
    @Test
    fun `tick and undo carry the main exercise identity and nullable run`() {
        for (exerciseId in listOf("bb_back_squat", "", "   ")) {
            val main = WatchExercise(7L, "main", "Squat", 235.0, false, null, emptyList(), emptyList(), exerciseId = exerciseId)
            val snapshot = WatchSnapshot(
                revision = 1, suggestedDayId = "A", unit = "lb", runId = "r-1",
                day = WatchDay("A", "A", 0, listOf(main.copy(slot = "ss", exerciseId = "partner"), main)),
            )
            val tick = buildTickDelta("A", 7L, 0, 100L, 200L, snapshot = snapshot)
            val undo = buildUndoDelta("A", 7L, 0, 300L, snapshot = snapshot)
            for (delta in listOf(tick, undo)) {
                assertEquals("r-1", delta.runId)
                assertEquals(exerciseId.takeIf { it.isNotBlank() }, delta.exerciseId)
            }
        }
        assertNull(buildTickDelta("A", 7L, 0, null, 200L).exerciseId)
        assertNull(buildUndoDelta("A", 7L, 0, 300L).exerciseId)
    }
}
