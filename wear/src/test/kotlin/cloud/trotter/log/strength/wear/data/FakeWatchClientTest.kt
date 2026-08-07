package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeWatchClientTest {

    @Test
    fun `applies a weight edit to the addressed round only`() = runTest {
        val client = FakeWatchClient()
        client.sendEdit(
            SetEditDelta(dayId = "A", programExerciseId = 1L, slot = "main", setIndex = 4, weightLb = 245.0, editedAtMillis = 1L),
        )
        val squat = client.snapshotFlow().first().day.exercises.single { it.programExerciseId == 1L }
        assertEquals(245.0, squat.sets[4].weightLb)
        assertEquals(130.0, squat.sets[0].weightLb) // unrelated round untouched
    }

    @Test
    fun `bumps revision on every applied edit`() = runTest {
        val client = FakeWatchClient()
        val before = client.snapshotFlow().first().revision
        client.sendEdit(
            SetEditDelta(dayId = "A", programExerciseId = 1L, slot = "main", setIndex = 0, reps = 6, editedAtMillis = 1L),
        )
        assertEquals(before + 1, client.snapshotFlow().first().revision)
    }

    @Test
    fun `a done edit on the main track also flips the aligned superset partner round`() = runTest {
        val client = FakeWatchClient()
        client.sendEdit(
            SetEditDelta(dayId = "A", programExerciseId = 2L, slot = "main", setIndex = 1, done = true, editedAtMillis = 1L),
        )
        val press = client.snapshotFlow().first().day.exercises.single { it.programExerciseId == 2L }
        assertTrue(press.sets[1].done)
        assertTrue(press.ssSets[1].done)
        // other rounds are untouched
        assertEquals(false, press.sets[0].done)
        assertEquals(false, press.ssSets[0].done)
    }

    @Test
    fun `an untick on the main track also releases the aligned superset partner round`() = runTest {
        val client = FakeWatchClient()
        client.sendEdit(
            SetEditDelta(
                dayId = "A",
                programExerciseId = 2L,
                slot = "main",
                setIndex = 0,
                done = true,
                editedAtMillis = 1L,
            ),
        )
        val logged = client.snapshotFlow().first().day.exercises.single { it.programExerciseId == 2L }
        assertTrue(logged.sets[0].done)
        assertTrue(logged.ssSets[0].done)

        client.sendEdit(
            SetEditDelta(
                dayId = "A",
                programExerciseId = 2L,
                slot = "main",
                setIndex = 0,
                done = false,
                editedAtMillis = 2L,
            ),
        )
        val press = client.snapshotFlow().first().day.exercises.single { it.programExerciseId == 2L }
        assertEquals(false, press.sets[0].done)
        assertEquals(false, press.ssSets[0].done)
        assertEquals(false, press.sets[1].done)
        assertEquals(false, press.ssSets[1].done)
    }

    @Test
    fun `a done edit on the partner track does not flip the main track`() = runTest {
        val client = FakeWatchClient()
        client.sendEdit(
            SetEditDelta(dayId = "A", programExerciseId = 2L, slot = "ss", setIndex = 0, done = true, editedAtMillis = 1L),
        )
        val press = client.snapshotFlow().first().day.exercises.single { it.programExerciseId == 2L }
        assertTrue(press.ssSets[0].done)
        assertEquals(false, press.sets[0].done)
    }

    @Test
    fun `the standalone fake never reports a pending count — it has no real transport to queue against`() = runTest {
        val client = FakeWatchClient()
        assertEquals(0, client.pendingCountFlow().first())
        client.sendEdit(
            SetEditDelta(dayId = "A", programExerciseId = 1L, slot = "main", setIndex = 0, reps = 6, editedAtMillis = 1L),
        )
        assertEquals(0, client.pendingCountFlow().first())
    }

    @Test
    fun `a null field on a delta leaves that field unchanged`() = runTest {
        val client = FakeWatchClient()
        client.sendEdit(
            SetEditDelta(dayId = "A", programExerciseId = 1L, slot = "main", setIndex = 0, reps = 6, editedAtMillis = 1L),
        )
        val squat = client.snapshotFlow().first().day.exercises.single { it.programExerciseId == 1L }
        assertEquals(6, squat.sets[0].reps)
        assertEquals(130.0, squat.sets[0].weightLb) // weight untouched by a reps-only delta
    }

    /**
     * The echo changes the name and *nothing else*. Not the sets — the phone seeds,
     * and the replaced lift's numbers under the replacement's name is the one echo
     * that could hurt someone. Not the prescription either: an empty `alternates` is
     * how [PendingEdits] recognises the phone refusing a swap, so an echo that
     * blanked the list could settle a request still in flight.
     */
    @Test
    fun `a swap renames only its slot and leaves its sets and prescription alone`() = runTest {
        val client = FakeWatchClient()
        val before = client.snapshotFlow().first().day.exercises
        client.sendSwap(
            ExerciseSwapDelta(
                dayId = "A",
                programExerciseId = 1L,
                exerciseId = "front_squat",
                exerciseName = "Front Squat",
                editedAtMillis = 1L,
            ),
        )
        val after = client.snapshotFlow().first().day.exercises
        val swapped = after.single { it.programExerciseId == 1L }
        assertEquals("Front Squat", swapped.name)
        val original = before.single { it.programExerciseId == 1L }
        assertEquals(original.sets, swapped.sets)
        assertEquals(original.alternates, swapped.alternates)
        // The ack is a slot identity only the phone can write; the echo must not
        // forge one by claiming the slot already holds what it asked for.
        assertEquals(original.exerciseId, swapped.exerciseId)
        assertEquals(before.filter { it.programExerciseId != 1L }, after.filter { it.programExerciseId != 1L })
    }
}
