package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prescription edits preserve completed work and captured row identities. */
class RunReplanTest {
    private fun current() = List(3) { LoggedSet(50.0, 10, SetKind.WORK, plannedIndex = it) }
    private fun seeded(count: Int) = List(count) { LoggedSet(55.0, 8, SetKind.WORK) }

    /** [F] */
    @Test fun `growth keeps done rows and gives new rows no planned identity`() {
        val current = current().mapIndexed { i, row -> row.copy(done = i == 0) }
        val result = RunReplan.represcribe(current, seeded(4))
        assertEquals(listOf(current[0]) + seeded(3).mapIndexed { i, row ->
            row.copy(plannedIndex = (i + 1).takeIf { it < 3 })
        }, result)
        assertEquals(listOf(0, 1, 2, null), result.map { it.plannedIndex })
    }

    /** [F] */
    @Test fun `shrinking drops undone rows beyond the seed`() {
        val current = current().mapIndexed { i, row -> row.copy(done = i == 0) }
        val result = RunReplan.represcribe(current, seeded(1))
        assertEquals(1, result.size)
        assertTrue(result.single().done)
        assertEquals(current[0], result.single())
    }

    /** [F] */
    @Test fun `shrinking keeps a done row beyond the seed`() {
        val current = current().take(2).mapIndexed { i, row -> row.copy(done = i == 1) }
        assertEquals(listOf(seeded(1).single().copy(plannedIndex = 0), current[1]),
            RunReplan.represcribe(current, seeded(1)))
    }

    /** [F] */
    @Test fun `a replaced row keeps its watch stamp`() {
        val current = current().take(1).map { it.copy(watchEditedAt = 123L) }
        assertEquals(seeded(1).single().copy(plannedIndex = 0, watchEditedAt = 123L),
            RunReplan.represcribe(current, seeded(1)).single())
    }
}
