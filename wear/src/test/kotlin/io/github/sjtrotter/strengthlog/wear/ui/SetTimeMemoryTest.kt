package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The watch's own recollection of how long each set took (brief §6's TOOK line). */
class SetTimeMemoryTest {

    @Test
    fun `a recorded set remembers its duration, per exercise and round`() {
        val memory = SetTimeMemory.EMPTY.record(7L, roundIndex = 2, durationSeconds = 52)
        assertEquals(52, memory.secondsFor(7L, 2))
        assertNull(memory.secondsFor(7L, 3))
        assertNull(memory.secondsFor(8L, 2))
    }

    @Test
    fun `a round the watch never timed simply has no entry`() {
        assertNull(SetTimeMemory.EMPTY.secondsFor(1L, 0))
        // A zero-length set is not a fact worth keeping — it means no start was seen.
        assertNull(SetTimeMemory.EMPTY.record(1L, 0, durationSeconds = 0).secondsFor(1L, 0))
        assertNull(SetTimeMemory.EMPTY.record(1L, 0, durationSeconds = -3).secondsFor(1L, 0))
    }

    @Test
    fun `an untick forgets the time the tick claimed`() {
        val memory = SetTimeMemory.EMPTY.record(7L, 0, 40).record(7L, 1, 48)
        val undone = memory.forget(7L, 1)
        assertEquals(40, undone.secondsFor(7L, 0))
        assertNull(undone.secondsFor(7L, 1))
    }

    @Test
    fun `it survives a round trip through a saved-instance string`() {
        val memory = SetTimeMemory.EMPTY.record(7L, 0, 40).record(12L, 3, 105)
        val restored = SetTimeMemory.decode(memory.encode())
        assertEquals(40, restored.secondsFor(7L, 0))
        assertEquals(105, restored.secondsFor(12L, 3))
    }

    @Test
    fun `a garbled encoding costs a TOOK line, never a crash`() {
        assertNull(SetTimeMemory.decode("").secondsFor(1L, 0))
        assertNull(SetTimeMemory.decode("nonsense").secondsFor(1L, 0))
        assertEquals(40, SetTimeMemory.decode("7:0=40;broken;=;9:1=x").secondsFor(7L, 0))
    }
}
