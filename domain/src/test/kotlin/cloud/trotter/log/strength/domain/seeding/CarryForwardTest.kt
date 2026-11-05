package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarryForwardTest {
    private fun squat() = SetSeeder.seed(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF), 235.0, LifterConfig())
    private fun work(weight: Double = 70.0, reps: Int = 5) = List(3) { LoggedSet(weight, reps, SetKind.WORK) }
    private fun actual(planned: List<LoggedSet>, done: Boolean = false) =
        planned.mapIndexed { i, row -> row.copy(plannedIndex = i, done = done) }

    @Test
    fun `a changed top carries the opening rows`() {
        val planned = squat()
        val rows = planned.mapIndexed { i, set ->
            set.copy(done = i < 2, startedAtMillis = 100, completedAtMillis = 200, plannedIndex = i, watchEditedAt = 300)
        }
        val edited = SetEditor.editWeight(rows, 4, 245.0)
        val next = CarryForward.next(planned, edited)
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0), next.map { it.weightLb })
        assertCleared(next)
        assertEquals(245.0, CarryForward.offer(planned, edited.mapIndexed { i, row ->
            if (i == 4) row.copy(done = true) else row
        }, WeightUnit.LB))
        assertNull(CarryForward.offer(planned, SetEditor.editWeight(actual(planned, true), 4, 230.0), WeightUnit.LB))
    }

    @Test
    fun `a changed top on ramp to top carries opening rows without a backoff`() {
        val planned = SetSeeder.seed(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP), 235.0, LifterConfig())
        val next = CarryForward.next(planned, SetEditor.editWeight(actual(planned, true), 4, 245.0))
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0), next.map { it.weightLb })
        assertEquals(planned.map { it.kind }, next.map { it.kind })
        assertEquals(planned.map { it.reps }, next.map { it.reps })
        assertCleared(next)
    }

    @Test
    fun `unchanged top preserves a manually edited ramp`() {
        val planned = squat()
        val next = CarryForward.next(planned, SetEditor.editWeight(actual(planned), 0, 140.0))
        assertEquals(listOf(140.0, 165.0, 190.0, 210.0, 235.0, 175.0), next.map { it.weightLb })
        assertCleared(next)
    }

    @Test
    fun `work rows keep planned reps and opening weight`() {
        val planned = SetSeeder.seed(ProgramExercise("ez_curl", targetSets = 3), 50.0, LifterConfig())
        val rows = actual(planned).mapIndexed { i, set -> if (i == 1) set.copy(reps = 12, done = true) else set }
        val next = CarryForward.next(planned, rows)
        assertEquals(listOf(10, 10, 10), next.map { it.reps })
        assertEquals(listOf(50.0, 50.0, 50.0), next.map { it.weightLb })
        assertCleared(next)
    }

    @Test
    fun `an extra row added in the run does not carry`() {
        val planned = squat()
        val next = CarryForward.next(planned, SetEditor.addSet(actual(planned)))
        assertEquals(CarryForward.cleared(planned), next)
        assertCleared(next)
    }

    @Test
    fun `a miss carries the plan`() {
        val planned = work()
        for (reps in listOf(listOf(5, 5, 4), listOf(5, 4, 3), listOf(5, 5, 3))) {
            val rows = actual(planned, true).mapIndexed { i, row -> row.copy(reps = reps[i]) }
            assertEquals(planned, CarryForward.next(planned, rows))
            assertNull(CarryForward.offer(planned, rows, WeightUnit.LB))
        }
    }

    @Test
    fun `lighter sets never prefill`() {
        val planned = work(30.0, 10)
        val cases = listOf(
            listOf(30.0 to 6, 20.0 to 10, 20.0 to 10),
            List(3) { 25.0 to 10 },
        )
        for (values in cases) {
            val rows = actual(planned, true).mapIndexed { i, row -> row.copy(weightLb = values[i].first, reps = values[i].second) }
            assertEquals(planned, CarryForward.next(planned, rows))
            assertNull(CarryForward.offer(planned, rows, WeightUnit.LB))
        }
    }

    @Test
    fun `a logged back-off carries when the top held`() {
        val planned = squat()
        val rows = actual(planned).mapIndexed { i, row ->
            when (i) {
                4 -> row.copy(done = true)
                5 -> row.copy(weightLb = 140.0, done = true)
                else -> row
            }
        }
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 140.0), CarryForward.next(planned, rows).map { it.weightLb })
    }

    @Test
    fun `full success offers one increment without applying it`() {
        val planned = work()
        val rows = actual(planned, true)
        assertEquals(75.0, CarryForward.offer(planned, rows, WeightUnit.LB))
        assertEquals(planned, CarryForward.next(planned, rows))
    }

    @Test
    fun `a heavier working set offers its weight only when it makes its reps and never stacks`() {
        val planned = work()
        for ((weight, reps, expected) in listOf(Triple(75.0, 5, 75.0), Triple(80.0, 5, 80.0), Triple(75.0, 3, null))) {
            val rows = actual(planned, true).mapIndexed { i, row -> if (i == 0) row.copy(weightLb = weight, reps = reps) else row }
            assertEquals(expected, CarryForward.offer(planned, rows, WeightUnit.LB))
            assertEquals(planned, CarryForward.next(planned, rows))
        }
        val heavierOnly = actual(planned).mapIndexed { i, row -> if (i == 0) row.copy(weightLb = 75.0, done = true) else row }
        assertEquals(75.0, CarryForward.offer(planned, heavierOnly, WeightUnit.LB))
    }

    @Test
    fun `ramp success depends on the top alone`() {
        val planned = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 200.0, 3, 1)
        val rows = actual(planned).map { row ->
            when (row.kind) {
                SetKind.TOP -> row.copy(done = true)
                SetKind.BACKOFF -> row.copy(weightLb = 140.0, done = true)
                else -> row
            }
        }
        assertEquals(205.0, CarryForward.offer(planned, rows, WeightUnit.LB))
    }

    @Test
    fun `accepting the squat offer cascades once from the opening rows`() {
        val planned = squat()
        val rows = actual(planned).map { if (it.kind == SetKind.TOP) it.copy(done = true) else it }
        val offer = CarryForward.offer(planned, rows, WeightUnit.LB)
        assertEquals(240.0, offer)
        val accepted = CarryForward.withWorkingWeight(CarryForward.next(planned, rows), requireNotNull(offer))
        assertEquals(240.0, accepted[4].weightLb)
        assertEquals(180.0, accepted[5].weightLb)
        assertEquals(SetEditor.editWeight(CarryForward.cleared(planned), 4, 240.0), accepted)
        assertEquals(planned, CarryForward.withWorkingWeight(accepted, 235.0))
    }

    @Test
    fun `a skip earns nothing`() {
        val planned = work()
        assertNull(CarryForward.offer(planned, actual(planned), WeightUnit.LB))
    }

    @Test
    fun `offers exclude bodyweight timed and nonworking rows`() {
        for (planned in listOf(emptyList(), work(0.0), work().map { it.copy(seconds = 30) }, listOf(LoggedSet(70.0, 5, SetKind.RAMP)))) {
            assertNull(CarryForward.offer(planned, actual(planned, true), WeightUnit.LB))
        }
        assertNull(CarryForward.workingWeight(emptyList()))
        assertEquals(emptyList(), CarryForward.workingIndices(listOf(LoggedSet(70.0, 5, SetKind.BACKOFF))))
    }

    @Test
    fun `offers use the light threshold and kg grid`() {
        val light = listOf(LoggedSet(20.0, 10, SetKind.WORK))
        assertEquals(22.5, CarryForward.offer(light, actual(light, true), WeightUnit.LB))
        val kg = WeightUnit.KG
        for ((opening, expected) in listOf(60.0 to 62.5, 61.0 to 62.5, 6.25 to 7.5)) {
            val planned = work(kg.toLb(opening))
            assertEquals(kg.toLb(expected), requireNotNull(CarryForward.offer(planned, actual(planned, true), kg)), 1e-6)
        }
    }

    @Test
    fun `generated offers floor at the unit bar`() {
        for ((unit, opening, expected) in listOf(Triple(WeightUnit.LB, 30.0, 45.0), Triple(WeightUnit.KG, 15.0, 20.0))) {
            val planned = work(unit.toLb(opening))
            assertEquals(unit.toLb(expected), requireNotNull(CarryForward.offer(planned, actual(planned, true), unit, listOf(Equipment.BARBELL))), 1e-6)
        }
    }

    @Test
    fun `withWorkingWeight edits WORK and planned EXTRA rows and leaves other rows alone`() {
        val planned = SetKind.entries.filter { it != SetKind.TOP }.map { LoggedSet(50.0, 10, it) }
        val next = CarryForward.withWorkingWeight(planned, 55.0)
        assertEquals(listOf(SetKind.WORK, SetKind.EXTRA).map { kind -> planned.indexOfFirst { it.kind == kind } }, CarryForward.workingIndices(planned))
        assertEquals(50.0, CarryForward.workingWeight(planned))
        assertEquals(planned.map { if (it.kind == SetKind.WORK || it.kind == SetKind.EXTRA) it.copy(weightLb = 55.0) else it }, next)
        assertEquals(planned, CarryForward.next(planned, actual(next, true)))
    }

    @Test
    fun `row identity preserves planned kinds seconds and missing rows`() {
        val planned = squat().map { it.copy(seconds = 10) }
        val rows = actual(planned).reversed().filter { it.plannedIndex != 1 }.map {
            it.copy(weightLb = if (it.plannedIndex == 0) 140.0 else it.weightLb, kind = SetKind.EXTRA, reps = 1, seconds = 90)
        }
        val next = CarryForward.next(planned, rows)
        assertEquals(planned.mapIndexed { i, row -> if (i == 0) row.copy(weightLb = 140.0) else row }, next)
        assertNull(CarryForward.offer(work(), actual(work(), true).map { it.copy(plannedIndex = null, weightLb = 100.0) }, WeightUnit.LB))
    }

    @Test
    fun `accepting a kg ramp weight preserves the grid and bar floor`() {
        val unit = WeightUnit.KG
        val equipment = listOf(Equipment.BARBELL)
        val planned = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, unit.toLb(60.0), 5, 1, unit, equipment)
        assertEquals(listOf(4), CarryForward.workingIndices(planned))
        val next = CarryForward.withWorkingWeight(planned, unit.toLb(20.0), unit, equipment)
        assertEquals(SetEditor.editWeight(planned, 4, unit.toLb(20.0), unit, equipment), next)
        next.forEach { assertEquals(20.0, unit.fromLb(it.weightLb), 1e-6) }
    }

    private fun assertCleared(sets: List<LoggedSet>) {
        assertTrue(sets.all {
            !it.done && it.startedAtMillis == null && it.completedAtMillis == null && it.plannedIndex == null && it.watchEditedAt == null
        })
    }
}
