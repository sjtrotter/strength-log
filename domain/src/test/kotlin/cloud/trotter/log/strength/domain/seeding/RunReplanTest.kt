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
    @Test fun `paired shrink uses shared indices and preserves either tracks completed rows`() {
        val main = current() + current().take(1).map { it.copy(plannedIndex = 3, done = true) }
        val partner = current().mapIndexed { i, row -> row.copy(done = i == 2, watchEditedAt = 123L) } +
            current().take(1).map { it.copy(plannedIndex = 3) }
        val result = RunReplan.represcribePaired(main, partner, seeded(1), seeded(1))
        assertEquals(listOf(0, 2, 3), result.first.map { it.plannedIndex })
        assertEquals(listOf(0, 2, 3), result.second.map { it.plannedIndex })
        assertEquals(main[3], result.first[2])
        assertEquals(listOf(partner[0], partner[2], partner[3]), result.second)
    }

    @Test fun `paired growth preserves existing partner rows and pads from its last row`() {
        val main = current()
        val partner = current().map { it.copy(weightLb = 45.0, watchEditedAt = 123L) }
        val partnerSeed = seeded(4).map { it.copy(weightLb = 35.0) }
        val result = RunReplan.represcribePaired(main, partner, seeded(4), partnerSeed)
        assertEquals(partner + LoggedSet(45.0, 10, SetKind.WORK), result.second)
        assertEquals(seeded(4).mapIndexed { i, row -> row.copy(plannedIndex = i.takeIf { it < 3 }) }, result.first)
    }

    @Test fun `swap alignment pads only the shorter side without replacing any existing row`() {
        val main = List(6) { current().first().copy(plannedIndex = it, done = it == 2, watchEditedAt = 123L) }
        val partner = listOf(current().first(), LoggedSet(
            42.0, 6, SetKind.EXTRA, done = true, seconds = 45,
            startedAtMillis = 100L, completedAtMillis = 200L, plannedIndex = 1, watchEditedAt = 300L,
        ))
        val padded = partner + List(4) { LoggedSet(42.0, 6, SetKind.EXTRA, seconds = 45) }
        val result = RunReplan.alignPaired(main, partner, emptyList(), emptyList())
        assertEquals(main, result.first)
        assertEquals(padded, result.second)
        val reverse = RunReplan.alignPaired(partner, main, seeded(1), seeded(3))
        assertEquals(padded, reverse.first)
        assertEquals(main, reverse.second)
    }

    @Test fun `alignment of an empty track uses its current prescription seed`() {
        val partner = current() + current()
        val seed = List(3) { LoggedSet(65.0, 8, SetKind.WORK) }
        val expected = List(6) { LoggedSet(65.0, 8, SetKind.WORK) }
        val result = RunReplan.alignPaired(emptyList(), partner, seed, emptyList())
        assertEquals(expected, result.first)
        assertEquals(partner, result.second)
        val reverse = RunReplan.alignPaired(partner, emptyList(), emptyList(), seed)
        assertEquals(partner, reverse.first)
        assertEquals(expected, reverse.second)
        assertEquals(emptyList<LoggedSet>() to emptyList<LoggedSet>(),
            RunReplan.alignPaired(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test fun `paired replan pads a missing completed tail from the tracks last retained row`() {
        val main = listOf(current().first().copy(done = true, startedAtMillis = 100L,
            completedAtMillis = 200L, watchEditedAt = 300L))
        val partner = current().mapIndexed { i, row -> row.copy(done = i == 2) }
        val result = RunReplan.represcribePaired(main, partner, seeded(1), seeded(1))
        assertEquals(main + LoggedSet(50.0, 10, SetKind.WORK), result.first)
        assertEquals(listOf(partner[0], partner[2]), result.second)
    }
}
