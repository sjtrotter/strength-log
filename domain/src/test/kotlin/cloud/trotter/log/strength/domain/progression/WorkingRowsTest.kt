package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkingRowsTest {
    private fun squat() = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 275.0, 5, 1, WeightUnit.LB,
        listOf(Equipment.BARBELL, Equipment.RACK), Scheme.STRENGTH)
    private fun prototype(structure: SetStructure = SetStructure.RAMP_TOP_BACKOFF) = SetSeeder.shape(structure, 235.0, 5, 1)
    private fun work(weight: Double = 70.0) = List(3) { LoggedSet(weight, 5, SetKind.WORK) }
    private fun actual(planned: List<LoggedSet>, done: Boolean = false) =
        planned.mapIndexed { i, row -> row.copy(plannedIndex = i, done = done) }
    private fun assertCleared(rows: List<LoggedSet>) = assertTrue(rows.all {
        !it.done && it.startedAtMillis == null && it.completedAtMillis == null && it.plannedIndex == null && it.watchEditedAt == null
    })

    @Test
    fun `cleared removes run results and keeps every other value including rir`() {
        val planned = squat().map { it.copy(seconds = 10, rir = 1) }
        val logged = actual(planned, true).map { it.copy(startedAtMillis = 100, completedAtMillis = 200, watchEditedAt = 300) }
        assertEquals(planned, WorkingRows.cleared(logged))
        assertCleared(WorkingRows.cleared(logged))
    }

    @Test
    fun `working indices choose TOP else WORK and planned EXTRA and first working weight`() {
        assertEquals(listOf(2), WorkingRows.workingIndices(squat()))
        assertEquals(275.0, WorkingRows.workingWeight(squat()))
        val rows = SetKind.entries.filter { it != SetKind.TOP }.map { LoggedSet(50.0, 10, it) }
        assertEquals(listOf(2, 3), WorkingRows.workingIndices(rows))
        assertEquals(50.0, WorkingRows.workingWeight(rows))
        assertEquals(emptyList(), WorkingRows.workingIndices(listOf(LoggedSet(70.0, 5, SetKind.BACKOFF))))
        assertNull(WorkingRows.workingWeight(emptyList()))
    }

    @Test
    fun `withWorkingWeight cascades Strength squat once`() {
        assertEquals(listOf(170.0, 230.0, 285.0, 255.0, 255.0),
            WorkingRows.withWorkingWeight(squat(), 285.0, scheme = Scheme.STRENGTH).map { it.weightLb })
    }

    @Test
    fun `withWorkingWeight sets WORK and EXTRA and keeps other rows`() {
        val planned = SetKind.entries.filter { it != SetKind.TOP }.map { LoggedSet(50.0, 10, it) }
        assertEquals(planned.map { if (it.kind == SetKind.WORK || it.kind == SetKind.EXTRA) it.copy(weightLb = 55.0) else it },
            WorkingRows.withWorkingWeight(planned, 55.0))
    }

    @Test
    fun `carried unchanged TOP keeps edited ramp and logged backoff`() {
        val planned = prototype()
        val logged = actual(planned, true).mapIndexed { i, row -> if (i == 0 || i == 5) row.copy(weightLb = 140.0) else row }
        val carried = WorkingRows.carried(planned, logged)
        assertEquals(listOf(140.0, 165.0, 190.0, 210.0, 235.0, 140.0), carried.map { it.weightLb })
        assertCleared(carried)
    }

    @Test
    fun `carried changed TOP restores opening rows with or without a backoff`() {
        for (structure in listOf(SetStructure.RAMP_TOP, SetStructure.RAMP_TOP_BACKOFF)) {
            val planned = prototype(structure)
            val logged = SetEditor.editWeight(actual(planned), 4, 245.0)
            assertEquals(planned, WorkingRows.carried(planned, logged))
        }
    }

    @Test
    fun `carried excludes an EXTRA added in the run`() {
        val planned = squat()
        assertEquals(planned, WorkingRows.carried(planned, SetEditor.addSet(actual(planned))))
    }

    @Test
    fun `carried keeps planned WORK reps and weight on misses lighter and heavier logs`() {
        val planned = work()
        for (weight in listOf(60.0, 70.0, 80.0)) {
            assertEquals(planned, WorkingRows.carried(planned, actual(planned, true).map { it.copy(weightLb = weight, reps = 3) }))
        }
    }

    @Test
    fun `carried matches row identity and keeps kinds seconds and missing rows`() {
        val planned = prototype().map { it.copy(seconds = 10) }
        val logged = actual(planned).reversed().filter { it.plannedIndex != 1 }.map {
            it.copy(weightLb = if (it.plannedIndex == 0) 140.0 else it.weightLb, kind = SetKind.EXTRA, reps = 1, seconds = 90)
        }
        assertEquals(planned.mapIndexed { i, row -> if (i == 0) row.copy(weightLb = 140.0) else row }, WorkingRows.carried(planned, logged))
    }

    @Test
    fun `withWorkingWeight on kg prototype keeps bar floor and unit grid`() {
        val kg = WeightUnit.KG
        val equipment = listOf(Equipment.BARBELL)
        val planned = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, kg.toLb(60.0), 5, 1, kg, equipment)
        WorkingRows.withWorkingWeight(planned, kg.toLb(20.0), kg, equipment).forEach {
            assertEquals(20.0, kg.fromLb(it.weightLb), 1e-9)
        }
    }

    @Test
    fun `work set indices judge TOP and both backoffs`() {
        assertEquals(listOf(2, 3, 4), WorkingRows.workSetIndices(squat()))
    }
}
