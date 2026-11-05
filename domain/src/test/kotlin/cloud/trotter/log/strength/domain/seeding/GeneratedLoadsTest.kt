package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GeneratedLoadsTest {
    private val config = LifterConfig(bodyweightLb = 235, age = 40)
    private val squat = ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)
    private val barbell = listOf(Equipment.BARBELL)
    private val kg = config.copy(weightUnit = WeightUnit.KG)

    @Test
    fun `barbell ramps and backoff floor at the bar without dropping rows or changing dumbbells`() {
        val cases = mapOf(
            45.0 to listOf(45.0, 45.0, 45.0, 45.0, 45.0, 45.0),
            65.0 to listOf(45.0, 45.0, 50.0, 60.0, 65.0, 50.0),
            95.0 to listOf(50.0, 65.0, 75.0, 85.0, 95.0, 70.0),
        )
        for ((goal, expected) in cases) {
            val sets = SetSeeder.seed(squat, goal, config)
            assertEquals(expected, sets.map { it.weightLb })
            assertEquals(listOf(5, 5, 5, 3, 5, 8), sets.map { it.reps })
        }
        assertEquals(listOf(25.0, 30.0, 35.0, 40.0, 45.0, 35.0),
            SetSeeder.seed(squat.copy(exerciseId = "incline_db"), 45.0, config).map { it.weightLb })
    }

    @Test
    fun `prescribed barbell ramps use the bar floor while retaining the written top`() {
        val exercise = squat.copy(prescription = Prescription(PrescribedTarget.LoadReps(40.0, 3)))
        val sets = assertNotNull(SlotSeeds.main(exercise, ExerciseLibrary.get(squat.exerciseId), config))
        assertEquals(listOf(45.0, 45.0, 45.0, 45.0, 40.0, 45.0), sets.map { it.weightLb })
        assertEquals(3, sets[4].reps)
        val writtenKg = WeightUnit.KG.toLb(42.5)
        val kgSets = assertNotNull(SlotSeeds.main(
            squat.copy(prescription = Prescription(PrescribedTarget.LoadReps(writtenKg, 3))),
            ExerciseLibrary.get(squat.exerciseId), kg,
        ))
        assertGrid(kgSets.map { it.weightLb }, 2.5)
        assertEquals(writtenKg, kgSets[4].weightLb, 1e-9)
    }

    @Test
    fun `kg goals and seeded ramps store clean kg loads including light and partner rows`() {
        val entry = ExerciseLibrary.get(squat.exerciseId)
        val goal = GoalCalculator.targetFor(entry, kg) as GoalTarget.Weight
        assertEquals(105.0, WeightUnit.KG.fromLb(goal.lb), 1e-9)
        val sets = assertNotNull(SlotSeeds.main(squat, entry, kg))
        assertGrid(sets.map { it.weightLb }, 2.5)
        assertEquals(goal.lb, sets[4].weightLb, 1e-9)

        val fractional = GoalCalculator.targetFor(ExerciseLibrary.get("leg_press"), kg) as GoalTarget.Weight
        assertGrid(listOf(fractional.lb), 2.5)
        val timed = GoalCalculator.targetFor(ExerciseLibrary.get("weighted_plank"), kg) as GoalTarget.Time
        assertGrid(listOf(timed.addedLb), 2.5)

        val light = ExerciseLibrary.get("incline_db").copy(goal = GoalSource.Flat(15.0))
        val lightGoal = GoalCalculator.targetFor(light, kg) as GoalTarget.Weight
        assertEquals(6.25, WeightUnit.KG.fromLb(lightGoal.lb), 1e-9)
        assertGrid(assertNotNull(SlotSeeds.main(squat.copy(exerciseId = light.id), light, kg)).map { it.weightLb }, 1.25)
        assertKg(List(3) { 6.25 }, assertNotNull(SlotSeeds.partner(light, 3, kg)).map { it.weightLb })
        val zero = light.copy(goal = GoalSource.Flat(0.0))
        assertEquals(GoalTarget.Weight(0.0, light.perHand), GoalCalculator.targetFor(zero, kg))
    }

    @Test
    fun `kg barbell seed uses the 20 kg bar and custom entry equipment`() {
        val entry = ExerciseLibrary.get(squat.exerciseId).copy(id = "custom_barbell", goal = GoalSource.Flat(45.0))
        val sets = assertNotNull(SlotSeeds.main(squat.copy(exerciseId = entry.id), entry, kg))
        assertKg(List(6) { 20.0 }, sets.map { it.weightLb })
    }

    @Test
    fun `top cascade clamps barbell rows but keeps completed rows and non-top edits`() {
        val sets = SetSeeder.seed(squat, 235.0, config).mapIndexed { i, row -> row.copy(done = i == 0) }
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
        val sets = SetSeeder.seed(squat, 235.0, kg)
        val after = SetEditor.edit(sets, 4, SetEdit(weightLb = WeightUnit.KG.toLb(42.5), reps = 3), WeightUnit.KG, barbell)
        assertKg(listOf(22.5, 30.0, 35.0, 37.5, 42.5, 32.5), after.map { it.weightLb })
        assertEquals(listOf(5, 5, 5, 3, 3, 8), after.map { it.reps })
        val low = SetEditor.editWeight(after, 4, WeightUnit.KG.toLb(20.0), WeightUnit.KG, barbell)
        assertKg(List(6) { 20.0 }, low.map { it.weightLb })
        val completed = sets.map { it.copy(done = true) }
        val edited = SetEditor.editWeight(completed, 4, WeightUnit.KG.toLb(42.5), WeightUnit.KG, barbell)
        assertKg(listOf(22.5, 30.0, 35.0, 37.5, 42.5, 32.5),
            CarryForward.next(sets, edited, WeightUnit.KG, barbell).map { it.weightLb })
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
