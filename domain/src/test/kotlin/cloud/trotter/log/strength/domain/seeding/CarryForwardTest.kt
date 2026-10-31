package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Working values retain edits while dropping results from the finished run. */
class CarryForwardTest {
    private fun squat() = SetSeeder.seed(ProgramExercise("bb_back_squat", isMain = true), 235.0, LifterConfig())

    /** [F] */
    @Test
    fun `changed top recascades every ramp and clears results`() {
        val planned = squat()
        val actual = planned.mapIndexed { i, set ->
            set.copy(done = i < 2, startedAtMillis = 100, completedAtMillis = 200, plannedIndex = i, watchEditedAt = 300)
        }
        val next = CarryForward.next(planned, SetEditor.editWeight(actual, 4, 245.0))
        assertEquals(listOf(135.0, 170.0, 195.0, 220.0, 245.0, 185.0), next.map { it.weightLb })
        assertCleared(next)
    }

    /** [F] */
    @Test
    fun `unchanged top preserves a manually edited ramp`() {
        val planned = squat()
        val next = CarryForward.next(planned, SetEditor.editWeight(planned, 0, 140.0))
        assertEquals(listOf(140.0, 165.0, 190.0, 210.0, 235.0, 175.0), next.map { it.weightLb })
        assertCleared(next)
    }

    /** [F] */
    @Test
    fun `work rows retain performed reps`() {
        val planned = SetSeeder.seed(ProgramExercise("ez_curl", targetSets = 3), 50.0, LifterConfig())
        val actual = planned.mapIndexed { i, set -> if (i == 1) set.copy(reps = 12, done = true) else set }
        val next = CarryForward.next(planned, actual)
        assertEquals(listOf(10, 12, 10), next.map { it.reps })
        assertEquals(listOf(50.0, 50.0, 50.0), next.map { it.weightLb })
        assertCleared(next)
    }

    /** [F] */
    @Test
    fun `extra rows remain working values`() {
        val planned = squat()
        val next = CarryForward.next(planned, SetEditor.addSet(planned))
        assertEquals(planned + planned.last().copy(kind = SetKind.EXTRA), next)
        assertCleared(next)
    }

    private fun assertCleared(sets: List<LoggedSet>) {
        assertTrue(sets.all {
            !it.done && it.startedAtMillis == null && it.completedAtMillis == null && it.plannedIndex == null && it.watchEditedAt == null
        })
    }
}
