package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedLoadsTest {
    private val squat = ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)
    private val barbell = listOf(Equipment.BARBELL)

    @Test
    fun `barbell ramps and backoff floor at the bar without dropping rows or changing dumbbells`() {
        val cases = mapOf(
            45.0 to listOf(45.0, 45.0, 45.0, 45.0, 45.0, 45.0),
            65.0 to listOf(45.0, 45.0, 50.0, 60.0, 65.0, 50.0),
            95.0 to listOf(50.0, 65.0, 75.0, 85.0, 95.0, 70.0),
        )
        for ((goal, expected) in cases) {
            val sets = SetSeeder.shape(squat.structure, goal, 5, squat.targetSets, equipment = barbell)
            assertEquals(expected, sets.map { it.weightLb })
            assertEquals(listOf(5, 5, 5, 3, 5, 8), sets.map { it.reps })
        }
        assertEquals(listOf(25.0, 30.0, 35.0, 40.0, 45.0, 35.0),
            SetSeeder.shape(squat.structure, 45.0, 5, squat.targetSets, equipment = listOf(Equipment.DUMBBELL, Equipment.BENCH)).map { it.weightLb })
    }

    @Test
    fun `prescribed barbell ramps use the bar floor while retaining the written top`() {
        val exercise = squat.copy(prescription = Prescription(PrescribedTarget.LoadReps(40.0, 3)))
        val sets = SetSeeder.seedPrescribed(exercise.targetSets, requireNotNull(exercise.prescription), exercise.structure, equipment = barbell)
        assertEquals(listOf(45.0, 45.0, 45.0, 45.0, 40.0, 45.0), sets.map { it.weightLb })
        assertEquals(3, sets[4].reps)
        val writtenKg = WeightUnit.KG.toLb(42.5)
        val kgSets = SetSeeder.seedPrescribed(squat.targetSets, Prescription(PrescribedTarget.LoadReps(writtenKg, 3)),
            squat.structure, WeightUnit.KG, barbell)
        assertGrid(kgSets.map { it.weightLb }, 2.5)
        assertEquals(writtenKg, kgSets[4].weightLb, 1e-9)
    }

    @Test
    fun `kg seeded ramps store clean kg loads including light and partner rows`() {
        val top = WeightUnit.KG.toLb(105.0)
        val sets = SetSeeder.shape(squat.structure, top, 5, squat.targetSets, WeightUnit.KG, barbell)
        assertGrid(sets.map { it.weightLb }, 2.5)
        assertEquals(top, sets[4].weightLb, 1e-9)

        val lightLb = WeightUnit.KG.toLb(6.25)
        val light = SetSeeder.shape(squat.structure, lightLb, 5, squat.targetSets,
            WeightUnit.KG, listOf(Equipment.DUMBBELL, Equipment.BENCH))
        assertGrid(light.map { it.weightLb }, 1.25)
        val partner = SetSeeder.seedPrescribed(3, Prescription(PrescribedTarget.LoadReps(lightLb, 10)))
        assertKg(List(3) { 6.25 }, partner.map { it.weightLb })
    }

    @Test
    fun `kg barbell seed uses the 20 kg bar and custom entry equipment`() {
        val entry = ExerciseLibrary.get(squat.exerciseId).copy(id = "custom_barbell")
        val sets = SetSeeder.shape(squat.structure, WeightUnit.KG.toLb(20.0), 5, squat.targetSets, WeightUnit.KG, entry.equipment)
        assertKg(List(6) { 20.0 }, sets.map { it.weightLb })
    }

    @Test
    fun `top cascade clamps barbell rows but keeps completed rows and non-top edits`() {
        val sets = SetSeeder.shape(squat.structure, 235.0, 5, squat.targetSets, equipment = barbell).mapIndexed { i, row -> row.copy(done = i == 0) }
        val after = SetEditor.edit(sets, 4, SetEdit(weightLb = 45.0, reps = 2), equipment = barbell)
        assertEquals(listOf(130.0, 45.0, 45.0, 45.0, 45.0, 45.0), after.map { it.weightLb })
        assertEquals(sets[0], after[0])
        assertEquals(listOf(5, 5, 5, 3, 2, 8), after.map { it.reps })
        val manual = SetEditor.editWeight(after, 1, 12.3, equipment = barbell)
        assertEquals(after.mapIndexed { i, row -> if (i == 1) row.copy(weightLb = 12.3) else row }, manual)
        val dumbbell = SetEditor.editWeight(sets.map { it.copy(done = false) }, 4, 45.0,
            equipment = listOf(Equipment.DUMBBELL))
        assertEquals(listOf(25.0, 30.0, 35.0, 40.0, 45.0, 35.0), dumbbell.map { it.weightLb })
    }

    @Test
    fun `kg cascade and carry forward use the kg grid and bar floor without cascading reps`() {
        val sets = SetSeeder.shape(squat.structure, WeightUnit.KG.toLb(107.5), 5, squat.targetSets, WeightUnit.KG, barbell)
        val after = SetEditor.edit(sets, 4, SetEdit(weightLb = WeightUnit.KG.toLb(42.5), reps = 3), WeightUnit.KG, barbell)
        assertKg(listOf(22.5, 30.0, 35.0, 37.5, 42.5, 32.5), after.map { it.weightLb })
        assertEquals(listOf(5, 5, 5, 3, 3, 8), after.map { it.reps })
        val low = SetEditor.editWeight(after, 4, WeightUnit.KG.toLb(20.0), WeightUnit.KG, barbell)
        assertKg(List(6) { 20.0 }, low.map { it.weightLb })
        val completed = sets.mapIndexed { i, row -> row.copy(done = true, plannedIndex = i) }
        val edited = SetEditor.editWeight(completed, 4, WeightUnit.KG.toLb(42.5), WeightUnit.KG, barbell)
        assertEquals(WorkingRows.cleared(sets), WorkingRows.carried(sets, edited))
        val manual = SetEditor.editWeight(after, 0, WeightUnit.KG.toLb(21.3), WeightUnit.KG, barbell)
        assertEquals(21.3, WeightUnit.KG.fromLb(manual[0].weightLb), 1e-9)
        assertEquals(after.drop(1), manual.drop(1))
        assertEquals(after.map { it.weightLb }, SetEditor.editReps(after, 4, 8).map { it.weightLb })
        val light = SetEditor.editWeight(sets, 4, WeightUnit.KG.toLb(6.25), WeightUnit.KG,
            listOf(Equipment.DUMBBELL))
        assertGrid(light.map { it.weightLb }, 1.25)
    }

    private fun assertGrid(pounds: List<Double>, stepKg: Double) {
        pounds.forEach { lb ->
            val steps = WeightUnit.KG.fromLb(lb) / stepKg
            assertEquals(Math.round(steps).toDouble(), steps, 1e-9)
        }
    }

    private fun assertKg(expected: List<Double>, pounds: List<Double>) {
        assertEquals(expected.size, pounds.size)
        expected.zip(pounds).forEach { (kg, lb) -> assertEquals(kg, WeightUnit.KG.fromLb(lb), 1e-9) }
    }
}
