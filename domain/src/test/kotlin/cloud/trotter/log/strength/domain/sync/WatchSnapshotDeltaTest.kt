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

    @Test
    fun `a set edit assigns every carried value and preserves row metadata`() {
        val wire = snapshot()
        val edit = delta(done = true, stamp = 9L).copy(weightLb = 111.5, reps = 7, seconds = 42)

        val displayed = applyDelta(wire, edit)

        assertEquals(
            WatchSet(111.5, 7, "WORK", done = true, seconds = 42, restAfterSeconds = 90),
            displayed.day.exercises.single().sets.single(),
        )
        assertEquals(wire.copy(day = wire.day.copy(exercises = listOf(
            wire.day.exercises.single().copy(
                sets = listOf(WatchSet(111.5, 7, "WORK", true, 42, 90)),
                ssSets = listOf(wire.day.exercises.single().ssSets.single().copy(done = true)),
            ),
        ))), displayed)
    }

    @Test
    fun `null set fields preserve all existing values`() {
        val wire = snapshot()

        assertEquals(wire, applyDelta(wire, delta(done = null, stamp = 9L)))
    }

    @Test
    fun `main done propagates only to the aligned superset row`() {
        val wire = snapshot(twoRows = true)

        val displayed = applyDelta(wire, delta(done = true, stamp = 9L))
        val exercise = displayed.day.exercises.single()

        assertEquals(listOf(true, false), exercise.sets.map { it.done })
        assertEquals(listOf(true, false), exercise.ssSets.map { it.done })
        assertEquals(wire.day.exercises.single().ssSets[0].copy(done = true), exercise.ssSets[0])
    }

    @Test
    fun `superset edits update the partner row without propagating to main`() {
        val wire = snapshot()
        val edit = delta(done = true, stamp = 9L).copy(slot = "ss", weightLb = 55.0, reps = 12, seconds = 30)

        val displayed = applyDelta(wire, edit).day.exercises.single()

        assertEquals(wire.day.exercises.single().sets, displayed.sets)
        assertEquals(WatchSet(55.0, 12, "SS", true, 30, 0), displayed.ssSets.single())
    }

    @Test
    fun `an out of range row is skipped on either track`() {
        val wire = snapshot()

        assertEquals(wire, applyDelta(wire, delta(done = true, stamp = 9L).copy(setIndex = 4)))
        assertEquals(wire, applyDelta(wire, delta(done = true, stamp = 9L).copy(slot = "ss", setIndex = 4)))
    }

    @Test
    fun `a swap changes only the name and preserves slot identity and prescription`() {
        val wire = snapshot()
        val swap = ExerciseSwapDelta(
            dayId = "A",
            programExerciseId = 1L,
            exerciseId = "front_squat",
            exerciseName = "Front Squat",
            editedAtMillis = 9L,
        )

        val displayed = applyDelta(wire, swap)

        assertEquals(
            wire.copy(day = wire.day.copy(exercises = listOf(
                wire.day.exercises.single().copy(name = "Front Squat"),
            ))),
            displayed,
        )
        val exercise = displayed.day.exercises.single()
        assertEquals(1L, exercise.programExerciseId)
        assertEquals("main", exercise.slot)
        assertEquals(100.0, exercise.goal)
        assertEquals("100 lb", exercise.goalLabel)
        assertEquals("barbell_back_squat", exercise.exerciseId)
        assertEquals(listOf(WatchAlternate("front_squat", "Front Squat")), exercise.alternates)
        assertEquals(wire.day.exercises.single().sets, exercise.sets)
        assertEquals(wire.day.exercises.single().ssSets, exercise.ssSets)
    }

    private fun delta(done: Boolean?, stamp: Long) = SetEditDelta(
        dayId = "A",
        programExerciseId = 1L,
        slot = "main",
        setIndex = 0,
        done = done,
        editedAtMillis = stamp,
    )

    private fun snapshot(twoRows: Boolean = false) = WatchSnapshot(
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
                    perHand = true,
                    supersetPartnerName = "Row",
                    sets = listOf(WatchSet(100.0, 5, "WORK", done = false, seconds = 10, restAfterSeconds = 90)) +
                        if (twoRows) listOf(WatchSet(80.0, 8, "BACKOFF", done = false)) else emptyList(),
                    ssSets = listOf(WatchSet(40.0, 10, "SS", done = false)) +
                        if (twoRows) listOf(WatchSet(35.0, 12, "SS", done = false)) else emptyList(),
                    goalLabel = "100 lb",
                    tracking = "weighted",
                    ssTracking = "reps",
                    alternates = listOf(WatchAlternate("front_squat", "Front Squat")),
                    exerciseId = "barbell_back_squat",
                ),
            ),
        ),
        unit = "lb",
    )
}
