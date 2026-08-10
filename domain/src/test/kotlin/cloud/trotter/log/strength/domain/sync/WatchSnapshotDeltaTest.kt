package cloud.trotter.log.strength.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchSnapshotDeltaTest {

    @Test
    fun `deltas apply in caller supplied queue order`() {
        val first = delta(done = true, stamp = 1L)
        val second = delta(done = false, stamp = 2L)

        val displayed = listOf(first, second).fold(snapshot()) { held, edit ->
            applyDelta(held, edit)
        }

        assertEquals(false, displayed.day.exercises.single().sets.single().done)
        assertEquals(4L, displayed.revision, "display transforms do not spend phone revisions")
    }

    @Test
    fun `a missing target is a no-op`() {
        val wire = snapshot()

        assertEquals(wire, applyDelta(wire, delta(done = true, stamp = 1L).copy(programExerciseId = 99L)))
    }

    private fun delta(done: Boolean, stamp: Long) = SetEditDelta(
        dayId = "A",
        programExerciseId = 1L,
        slot = "main",
        setIndex = 0,
        done = done,
        editedAtMillis = stamp,
    )

    private fun snapshot() = WatchSnapshot(
        revision = 4L,
        suggestedDayId = "A",
        day = WatchDay(
            dayId = "A",
            title = "Day A",
            accentIndex = 0,
            exercises = listOf(
                WatchExercise(
                    programExerciseId = 1L,
                    slot = "main",
                    name = "Squat",
                    goal = 100.0,
                    perHand = false,
                    supersetPartnerName = null,
                    sets = listOf(WatchSet(100.0, 5, "WORK", done = false)),
                    ssSets = emptyList(),
                ),
            ),
        ),
        unit = "lb",
    )
}
