package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.Effort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetEditTest {
    @Test fun `a tick or an untick clears effort and a retick keeps it`() {
        val rows = listOf(
            LoggedSet(275.0, 5, SetKind.WORK, done = false, rir = 4),
            LoggedSet(275.0, 5, SetKind.WORK, done = true, rir = 1),
        )
        assertNull(SetEditor.edit(rows, 0, SetEdit(done = true))[0].rir)
        assertNull(SetEditor.edit(rows, 1, SetEdit(done = false))[1].rir)
        assertEquals(1, SetEditor.edit(rows, 1, SetEdit(done = true))[1].rir)
        assertEquals(1, SetEditor.edit(rows, 1, SetEdit(reps = 4))[1].rir)
    }

    @Test fun `effort rates only a done working row`() {
        val rows = listOf(
            LoggedSet(135.0, 5, SetKind.RAMP, done = true),
            LoggedSet(275.0, 5, SetKind.TOP, done = true,
                startedAtMillis = 10L, completedAtMillis = 70L, watchEditedAt = 100L),
            LoggedSet(275.0, 5, SetKind.WORK),
            LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 30),
        )
        val rated = requireNotNull(SetEditor.effort(rows, 1, Effort.HARD))
        assertEquals(rows.mapIndexed { i, set -> if (i == 1) set.copy(rir = 1) else set }, rated)
        assertEquals(rows, SetEditor.effort(rated, 1, null))
        for (index in listOf(-1, 0, 2, 3, 9)) assertNull(SetEditor.effort(rows, index, Effort.HARD))
        assertNull(SetEditor.effort(rated, 1, Effort.HARD))
        assertNull(SetEditor.effort(rows, 1, null))
    }

    private val row = LoggedSet(50.0, 8, SetKind.WORK)

    @Test fun `watch tick writes observed timing and watch stamp only on its row`() {
        val after = SetEditor.edit(listOf(row, row), 1, SetEdit(
            done = true, startedAtMillis = 10L, completedAtMillis = 70L, watchEditedAt = 100L,
        ))
        assertEquals(row, after[0])
        assertTrue(after[1].done)
        assertEquals(10L, after[1].startedAtMillis)
        assertEquals(70L, after[1].completedAtMillis)
        assertEquals(100L, after[1].watchEditedAt)
    }

    @Test fun `watch retick fills only missing stamps`() {
        for (start in listOf(null, 5L)) for (complete in listOf(null, 60L)) {
            val done = row.copy(done = true, startedAtMillis = start, completedAtMillis = complete)
            val after = SetEditor.edit(listOf(done), 0, SetEdit(
                done = true, startedAtMillis = 10L, completedAtMillis = 70L,
            )).single()
            assertEquals(start ?: 10L, after.startedAtMillis)
            assertEquals(complete ?: 70L, after.completedAtMillis)
        }
    }

    @Test fun `untick clears both stamps even if the edit supplies timing`() {
        val done = row.copy(done = true, startedAtMillis = 10L, completedAtMillis = 70L, watchEditedAt = 100L)
        val after = SetEditor.edit(listOf(done), 0, SetEdit(
            done = false, startedAtMillis = 20L, completedAtMillis = 80L,
        )).single()
        assertFalse(after.done)
        assertNull(after.startedAtMillis)
        assertNull(after.completedAtMillis)
        assertEquals(100L, after.watchEditedAt)
        assertEquals(row.weightLb, after.weightLb)
        assertEquals(row.reps, after.reps)
    }

    @Test fun `value edits keep timing and done state while null done allows timing overrides`() {
        val done = row.copy(done = true, startedAtMillis = 10L, completedAtMillis = 70L, watchEditedAt = 100L)
        assertEquals(done.copy(reps = 9, seconds = 30),
            SetEditor.edit(listOf(done), 0, SetEdit(reps = 9, seconds = 30)).single())
        assertEquals(done.copy(startedAtMillis = 20L, completedAtMillis = 80L),
            SetEditor.edit(listOf(done), 0, SetEdit(startedAtMillis = 20L, completedAtMillis = 80L)).single())
    }

    @Test fun `row edit delegates top weight cascade and keeps completed ramps`() {
        val sets = SetSeeder.seed(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF), 235.0, LifterConfig())
            .mapIndexed { index, set -> set.copy(done = index < 2) }
        val after = SetEditor.edit(sets, 4, SetEdit(weightLb = 245.0, reps = 2))
        assertEquals(listOf(130.0, 165.0, 195.0, 220.0, 245.0, 185.0), after.map { it.weightLb })
        assertEquals(2, after[4].reps)
        assertEquals(sets.take(2), after.take(2))
        assertEquals(sets[5].reps, after[5].reps)
    }

    @Test fun `removing a row keeps the watch stamp at each index`() {
        val stamped = row.copy(done = true, watchEditedAt = 200L)
        val after = SetEditor.keepingIndexStamps(listOf(stamped, row), SetEditor.removeSet(listOf(stamped, row), 0))
        assertEquals(200L, after.single().watchEditedAt)
    }

    @Test fun `reinserting a row keeps the watch stamp at each index`() {
        val stamped = row.copy(done = true, watchEditedAt = 200L)
        val after = SetEditor.keepingIndexStamps(listOf(stamped), SetEditor.insertSet(listOf(stamped), 0, row))
        assertEquals(listOf(200L, 200L), after.map { it.watchEditedAt })
    }
}
