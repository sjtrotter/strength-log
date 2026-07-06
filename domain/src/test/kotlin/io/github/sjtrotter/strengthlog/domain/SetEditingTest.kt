package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.seeding.SetEditor
import io.github.sjtrotter.strengthlog.domain.seeding.SetSeeder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetEditingTest {

    private val cfg = LifterConfig()
    private fun squat() = SetSeeder.seed(ProgramExercise("bb_back_squat", isMain = true), 235.0, cfg)

    @Test
    fun `non-top weight edit touches only that row`() {
        val seeded = squat()
        val edited = SetEditor.editWeight(seeded, 1, 999.0) // a RAMP row, not TOP
        assertEquals(999.0, edited[1].weightLb, 1e-9)
        assertEquals(seeded[0], edited[0])
        assertEquals(seeded[4], edited[4]) // TOP unchanged
        assertEquals(seeded[5], edited[5]) // BACKOFF unchanged
    }

    @Test
    fun `reps edit never cascades`() {
        val seeded = squat()
        val topIndex = seeded.indexOfFirst { it.kind == SetKind.TOP }
        val edited = SetEditor.editReps(seeded, topIndex, 2)
        assertEquals(2, edited[topIndex].reps)
        assertEquals(seeded.map { it.weightLb }, edited.map { it.weightLb })
    }

    @Test
    fun `add set copies last row as EXTRA`() {
        val seeded = squat()
        val added = SetEditor.addSet(seeded)
        assertEquals(seeded.size + 1, added.size)
        val last = added.last()
        assertEquals(SetKind.EXTRA, last.kind)
        assertEquals(seeded.last().weightLb, last.weightLb, 1e-9)
        assertEquals(seeded.last().reps, last.reps)
        assertTrue(!last.done)
    }

    @Test
    fun `remove set never drops below one`() {
        var sets = listOf(LoggedSet(100.0, 10, SetKind.WORK))
        sets = SetEditor.removeSet(sets, 0)
        assertEquals(1, sets.size)
    }

    @Test
    fun `superset add and remove keep tracks aligned`() {
        val primary = SetSeeder.seed(ProgramExercise("ez_curl", targetSets = 3), 60.0, cfg)
        val partner = SetSeeder.seedPartner(primary.size, 50.0)
        assertEquals(primary.size, partner.size)

        val (p2, s2) = SetEditor.addSetPaired(primary, partner)
        assertEquals(4, p2.size)
        assertEquals(4, s2.size)
        assertEquals(SetKind.EXTRA, p2.last().kind)
        assertEquals(SetKind.EXTRA, s2.last().kind)

        val (p3, s3) = SetEditor.removeSetPaired(p2, s2, 1)
        assertEquals(3, p3.size)
        assertEquals(3, s3.size)
    }
}
