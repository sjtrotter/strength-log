package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The watch's own ledger of the ticks it made, their order and durations. */
class TickMemoryTest {

    @Test
    fun `a recorded set remembers its duration, per exercise and round`() {
        val memory = TickMemory.EMPTY.record(7L, roundIndex = 2, durationSeconds = 52)
        assertEquals(52, memory.secondsFor(7L, 2))
        assertNull(memory.secondsFor(7L, 3))
        assertNull(memory.secondsFor(8L, 2))
    }

    @Test
    fun `a round the watch never timed is still remembered, just without a duration`() {
        assertNull(TickMemory.EMPTY.secondsFor(1L, 0))
        assertNull(TickMemory.EMPTY.record(1L, 0, durationSeconds = 0).secondsFor(1L, 0))
        assertNull(TickMemory.EMPTY.record(1L, 0, durationSeconds = -3).secondsFor(1L, 0))
        assertEquals(listOf(TickRef(1L, 0)), TickMemory.EMPTY.record(1L, 0, 0).newestFirst())
    }

    @Test
    fun `an untick forgets the time the tick claimed`() {
        val memory = TickMemory.EMPTY.record(7L, 0, 40).record(7L, 1, 48)
        val undone = memory.forget(7L, 1)
        assertEquals(40, undone.secondsFor(7L, 0))
        assertNull(undone.secondsFor(7L, 1))
        assertEquals(listOf(TickRef(7L, 0)), undone.newestFirst())
    }

    @Test
    fun `it survives a round trip through a saved-instance string`() {
        val memory = TickMemory.EMPTY.record(7L, 0, 40).record(12L, 3, 105)
        val restored = TickMemory.decode(memory.encode())
        assertEquals(40, restored.secondsFor(7L, 0))
        assertEquals(105, restored.secondsFor(12L, 3))
        assertEquals(listOf(TickRef(12L, 3), TickRef(7L, 0)), restored.newestFirst())
    }

    @Test
    fun `a garbled encoding costs a TOOK line, never a crash`() {
        assertNull(TickMemory.decode("").secondsFor(1L, 0))
        assertNull(TickMemory.decode("nonsense").secondsFor(1L, 0))
        // Entries are seconds@order; old entries without an order remain valid.
        assertEquals(40, TickMemory.decode("7:0=40;broken;=;9:1=x").secondsFor(7L, 0))
    }

    @Test
    fun `the ledger reads newest first`() {
        val memory = TickMemory.EMPTY.record(1L, 0, 40).record(3L, 0, 30).record(2L, 1, 20)
        assertEquals(listOf(TickRef(2L, 1), TickRef(3L, 0), TickRef(1L, 0)), memory.newestFirst())
    }

    @Test
    fun `re-ticking a round moves it back to the front`() {
        val memory = TickMemory.EMPTY.record(1L, 0, 40).record(3L, 0, 30).record(1L, 0, 45)
        assertEquals(listOf(TickRef(1L, 0), TickRef(3L, 0)), memory.newestFirst())
        assertEquals(45, memory.secondsFor(1L, 0))
    }

    @Test
    fun `order survives a saved-instance round trip`() {
        val memory = TickMemory.EMPTY.record(1L, 0, 40).record(3L, 2, 30)
        assertEquals(
            listOf(TickRef(3L, 2), TickRef(1L, 0)),
            TickMemory.decode(memory.encode()).newestFirst(),
        )
    }

    @Test
    fun `an encoding from before ticks were ordered keeps the order it was written in`() {
        assertEquals(
            listOf(TickRef(9L, 1), TickRef(7L, 0)),
            TickMemory.decode("7:0=40;9:1=30").newestFirst(),
        )
    }

    @Test
    fun `forgetExercise drops every round of that lift and keeps the others`() {
        val memory = TickMemory.EMPTY.record(1L, 0, 40).record(2L, 0, 30).record(1L, 1, 45)
        val forgotten = memory.forgetExercise(1L)
        assertEquals(listOf(TickRef(2L, 0)), forgotten.newestFirst())
        assertNull(forgotten.secondsFor(1L, 0))
        assertEquals(30, forgotten.secondsFor(2L, 0))
    }

    @Test
    fun `forgetExercise with no matching entries leaves an equal ledger shape`() {
        val memory = TickMemory.EMPTY.record(1L, 0, 40)
        assertEquals(memory.encode(), memory.forgetExercise(9L).encode())
    }

    @Test
    fun `forgetExercise survives a saved-instance round trip`() {
        val forgotten = TickMemory.EMPTY.record(1L, 0, 40).record(2L, 1, 30).forgetExercise(1L)
        val restored = TickMemory.decode(forgotten.encode())
        assertEquals(listOf(TickRef(2L, 1)), restored.newestFirst())
        assertEquals(30, restored.secondsFor(2L, 1))
    }

    @Test
    fun `undo skips a stale remembered swap tick and reaches a live logged round`() {
        fun exercise(id: Long, done: Boolean) = WatchExercise(
            id, "main", "Ex$id", 100.0, false, null,
            listOf(WatchSet(100.0, 5, "WORK", done)), emptyList(),
        )
        val exercises = listOf(exercise(1L, false), exercise(2L, true))
        val memory = TickMemory.EMPTY.record(2L, 0, 30).record(1L, 0, 40)
        assertEquals(UndoTarget(1, 0), UndoTarget.of(exercises, 0, memory))
    }
}
