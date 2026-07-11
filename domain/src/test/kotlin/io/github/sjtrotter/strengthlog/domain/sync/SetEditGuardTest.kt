package io.github.sjtrotter.strengthlog.domain.sync

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure per-type field guard (design risk #2): a delta keeps only the fields its
 * track actually logs, so a stale/old watch can't write a dead field. `done` (the
 * tick) always survives.
 */
class SetEditGuardTest {

    /** A delta that (illegally, from a stale watch) carries every value field at once. */
    private fun everything() = SetEditDelta(
        dayId = "A",
        programExerciseId = 1L,
        slot = "main",
        setIndex = 0,
        weightLb = 100.0,
        reps = 8,
        seconds = 45,
        done = true,
        editedAtMillis = 1L,
    )

    @Test
    fun `WEIGHTED keeps weight and reps, drops seconds`() {
        val g = everything().guardedFor(TrackingType.WEIGHTED)
        assertEquals(100.0, g.weightLb)
        assertEquals(8, g.reps)
        assertNull(g.seconds)
        assertEquals(true, g.done)
    }

    @Test
    fun `REPS keeps reps, drops weight and seconds`() {
        val g = everything().guardedFor(TrackingType.REPS)
        assertNull(g.weightLb)
        assertEquals(8, g.reps)
        assertNull(g.seconds)
        assertEquals(true, g.done)
    }

    @Test
    fun `TIMED keeps seconds, drops weight and reps`() {
        val g = everything().guardedFor(TrackingType.TIMED)
        assertNull(g.weightLb)
        assertNull(g.reps)
        assertEquals(45, g.seconds)
        assertEquals(true, g.done)
    }

    @Test
    fun `the tick survives on every track`() {
        val tick = SetEditDelta(dayId = "A", programExerciseId = 1L, slot = "main", setIndex = 0, done = true, editedAtMillis = 1L)
        TrackingType.entries.forEach { assertEquals(true, tick.guardedFor(it).done) }
    }
}
