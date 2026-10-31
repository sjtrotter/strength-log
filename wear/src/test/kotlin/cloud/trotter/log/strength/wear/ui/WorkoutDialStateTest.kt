package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutDialStateTest {

    private val saveScope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun inProgress() = WorkoutDialState(1L, "A", null, DialFace.WORKOUT).apply {
        lifting = true
        startedAtWallMillis = 100_000L
        startedAtElapsedMillis = 10_000L
        restDeadlineMillis = 20_000L
        restTotalSeconds = 60
        restBetweenExercises = true
        restedSeconds = 30
        sessionStartedAtMillis = 90_000L
        sessionEndedAtMillis = 95_000L
        selectedExercise = 1
        tickMemory = tickMemory.record(7L, 0, 5)
    }

    private fun save(state: WorkoutDialState): Any = with(workoutDialStateSaver(state.planId, state.dayId, state.runId)) {
        assertNotNull(saveScope.save(state))
    }

    @Test
    fun `restored in-progress state is discarded when the first snapshot belongs to another plan`() {
        val saved = save(inProgress())

        assertNull(workoutDialStateSaver(2L, "A", null).restore(saved))
    }

    @Test
    fun `restored in-progress state is discarded when the first snapshot belongs to another day`() {
        val saved = save(inProgress())

        assertNull(workoutDialStateSaver(1L, "B", null).restore(saved))
    }

    @Test
    fun `restored in-progress state is kept when the first snapshot has the same owner`() {
        val original = inProgress()
        val saved = save(original)

        val restored = assertNotNull(workoutDialStateSaver(1L, "A", null).restore(saved))

        assertEquals(original.planId, restored.planId)
        assertEquals(original.dayId, restored.dayId)
        assertEquals(DialFace.WORKOUT, restored.face)
        assertTrue(restored.lifting)
        assertEquals(original.startedAtWallMillis, restored.startedAtWallMillis)
        assertEquals(original.startedAtElapsedMillis, restored.startedAtElapsedMillis)
        assertEquals(original.restDeadlineMillis, restored.restDeadlineMillis)
        assertEquals(original.restTotalSeconds, restored.restTotalSeconds)
        assertEquals(original.restBetweenExercises, restored.restBetweenExercises)
        assertEquals(original.restedSeconds, restored.restedSeconds)
        assertEquals(original.sessionStartedAtMillis, restored.sessionStartedAtMillis)
        assertEquals(original.sessionEndedAtMillis, restored.sessionEndedAtMillis)
        assertEquals(original.selectedExercise, restored.selectedExercise)
        assertEquals(original.tickMemory.encode(), restored.tickMemory.encode())
    }

    /** [F] */
    @Test
    fun `restored dial state belongs to exactly one run`() {
        val original = WorkoutDialState(1L, "A", "r-1", DialFace.WORKOUT).apply {
            lifting = true
            startedAtWallMillis = 100_000L
            restDeadlineMillis = 20_000L
            tickMemory = tickMemory.record(7L, 0, 5)
        }
        val saved = save(original)
        assertNull(workoutDialStateSaver(1L, "A", "r-2").restore(saved))
        val restored = assertNotNull(workoutDialStateSaver(1L, "A", "r-1").restore(saved))
        assertEquals("r-1", restored.runId)
        assertTrue(restored.lifting)
        assertEquals(original.startedAtWallMillis, restored.startedAtWallMillis)
        assertEquals(original.restDeadlineMillis, restored.restDeadlineMillis)
        assertEquals(original.tickMemory.encode(), restored.tickMemory.encode())
    }
}
