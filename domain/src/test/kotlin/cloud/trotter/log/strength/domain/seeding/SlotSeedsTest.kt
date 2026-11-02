package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SlotSeedsTest {
    private val config = LifterConfig(bodyweightLb = 235, age = 40)
    private val squat = ExerciseLibrary.get("bb_back_squat")

    @Test
    fun `main lift preserves the pinned squat sequence`() {
        val exercise = ProgramExercise(squat.id, isMain = true)
        val sets = assertNotNull(SlotSeeds.main(exercise, squat, config))

        assertEquals(SetSeeder.seed(exercise, GoalCalculator.targetFor(squat, config), config), sets)
        assertEquals(
            listOf(
                LoggedSet(130.0, 5, SetKind.RAMP),
                LoggedSet(165.0, 5, SetKind.RAMP),
                LoggedSet(190.0, 5, SetKind.RAMP),
                LoggedSet(210.0, 3, SetKind.RAMP),
                LoggedSet(235.0, 5, SetKind.TOP),
                LoggedSet(175.0, 8, SetKind.BACKOFF),
            ),
            sets,
        )
    }

    @Test
    fun `prescription takes precedence over any entry including an unknown one`() {
        val prescriptions = listOf(
            PrescribedTarget.LoadReps(37.5, 8),
            PrescribedTarget.Reps(12),
            PrescribedTarget.Hold(45, 22.5),
            PrescribedTarget.RepsInTime(120, 50),
        ).map { Prescription(it) }

        for (prescription in prescriptions) {
            val exercise = ProgramExercise(squat.id, isMain = true, targetSets = 2, prescription = prescription)
            val expected = SetSeeder.seedPrescribed(2, prescription)
            assertEquals(expected, SlotSeeds.prescribed(2, prescription))
            assertEquals(expected, SlotSeeds.main(exercise, squat, config))
            assertEquals(expected, SlotSeeds.main(exercise, null, config))
        }
    }

    @Test
    fun `unknown main without a prescription cannot be seeded`() {
        assertNull(SlotSeeds.main(ProgramExercise("unknown"), null, config))
    }

    @Test
    fun `partner uses the supplied live main row count and its own goal`() {
        val entry = ExerciseLibrary.get("ez_curl")
        val sets = assertNotNull(SlotSeeds.partner(entry, mainRows = 7, config = config))

        assertEquals(SetSeeder.seedPartner(7, GoalCalculator.targetFor(entry, config)), sets)
        assertEquals(List(7) { LoggedSet(60.0, 10, SetKind.WORK) }, sets)
    }

    @Test
    fun `unknown partner cannot be seeded`() {
        assertNull(SlotSeeds.partner(null, mainRows = 7, config = config))
    }

    @Test
    fun `reps and timed goals preserve their row shape for main and partner tracks`() {
        val cases = listOf(
            GoalSource.Reps(6) to LoggedSet(0.0, 6, SetKind.WORK),
            GoalSource.Time(45, 25.0) to LoggedSet(25.0, 0, SetKind.WORK, seconds = 45),
        )
        for ((goal, row) in cases) {
            val entry = squat.copy(goal = goal)
            val exercise = ProgramExercise(entry.id, isMain = true, targetSets = 4)

            assertEquals(List(4) { row }, SlotSeeds.main(exercise, entry, config))
            assertEquals(List(7) { row }, SlotSeeds.partner(entry, mainRows = 7, config = config))
        }
    }

    @Test
    fun `caller supplied count replaces the plan count for prescription edits`() {
        val prescription = Prescription(PrescribedTarget.LoadReps(37.5, 8))
        val exercise = ProgramExercise(squat.id, targetSets = 3, prescription = prescription)

        assertEquals(
            SetSeeder.seedPrescribed(5, prescription),
            SlotSeeds.prescribed(5, prescription),
        )
        assertEquals(
            SlotSeeds.main(exercise.copy(targetSets = 5), null, config),
            SlotSeeds.prescribed(5, prescription),
        )
    }

    @Test
    fun `clearing a prescription for a substitute uses its goal`() {
        val exercise = ProgramExercise(
            squat.id, isMain = true, prescription = Prescription(PrescribedTarget.LoadReps(37.5, 8)),
        )
        val entry = ExerciseLibrary.get("hack_squat")
        val substitute = exercise.copy(exerciseId = entry.id, prescription = null)

        assertEquals(
            SetSeeder.seed(substitute, GoalCalculator.targetFor(entry, config), config),
            SlotSeeds.main(substitute, entry, config),
        )
    }
}
