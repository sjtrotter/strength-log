package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlotSeedingPlanLoadTest {
    private val unit = WeightUnit.LB
    private val steps = LoadSteps.defaults(unit)
    private fun rule(reps: Int = 5, structure: SetStructure = SetStructure.STRAIGHT) =
        SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, structure, reps, MovementPattern.SQUAT_BILATERAL)
    private fun work(n: Int, weight: Double, reps: Int) = List(n) { LoggedSet(weight, reps, SetKind.WORK) }

    @Test fun `a planned load seeds straight sets as is ahead of history`() {
        val slot = ProgramExercise("bb_back_squat", SetStructure.STRAIGHT, 3)
        val seeded = SlotSeeding.rows(slot, rule().copy(state = SlotState(calibrating = true)),
            KnownLoads(mapOf(slot.exerciseId to LoggedSet(100.0, 8, SetKind.WORK))), steps, unit, planLb = 227.5)
        assertEquals(work(3, 227.5, 5), seeded.rows)
        assertFalse(seeded.rule.state.calibrating)
    }

    @Test fun `a planned load is a ramped slot's top and the scheme cascades from it`() {
        val structure = SetStructure.RAMP_TOP_BACKOFF
        val seeded = SlotSeeding.rows(ProgramExercise("bb_back_squat", structure, 6), rule(structure = structure),
            KnownLoads.NONE, steps, unit, planLb = 245.0)
        assertEquals(listOf(
            LoggedSet(135.0, 5, SetKind.RAMP), LoggedSet(170.0, 5, SetKind.RAMP),
            LoggedSet(195.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
            LoggedSet(245.0, 5, SetKind.TOP), LoggedSet(185.0, 8, SetKind.BACKOFF),
        ), seeded.rows)
        assertFalse(seeded.rule.state.calibrating)
    }

    @Test fun `a loaded hold takes the planned or last load and a bodyweight hold ignores both`() {
        val last = LoggedSet(30.0, 0, SetKind.WORK, seconds = 60)
        for ((id, planned, expected) in listOf(
            Triple("weighted_plank", 35.0, 35.0), Triple("weighted_plank", null, 30.0), Triple("plank", 35.0, 0.0),
        )) {
            val seeded = SlotSeeding.rows(ProgramExercise(id, targetSets = 3), rule(),
                KnownLoads(mapOf(id to last)), steps, unit, planLb = planned)
            assertEquals(List(3) { LoggedSet(expected, 0, SetKind.WORK, seconds = 45) }, seeded.rows)
        }
    }

    @Test fun `a prescription wins and a zero planned load falls back to history`() {
        val prescription = Prescription(PrescribedTarget.LoadReps(135.0, 5))
        val slot = ProgramExercise("bb_bench", targetSets = 3, prescription = prescription)
        val seeded = SlotSeeding.rows(slot, rule(), KnownLoads.NONE, steps, unit, planLb = 200.0)
        assertEquals(SetSeeder.seedPrescribed(3, prescription, equipment = ExerciseLibrary.get("bb_bench").equipment), seeded.rows)
        val fromHistory = SlotSeeding.rows(slot.copy(prescription = null), rule(),
            KnownLoads(mapOf(slot.exerciseId to LoggedSet(185.0, 8, SetKind.WORK, rir = 2))), steps, unit, planLb = 0.0)
        assertEquals(work(3, 200.0, 5), fromHistory.rows)
        assertFalse(fromHistory.rule.state.calibrating)
    }

    @Test fun `a planned 245 by 5 top seeds ten rep straight sets at 215 pounds`() {
        val seeded = SlotSeeding.rows(ProgramExercise("bb_back_squat", targetSets = 3),
            rule(10).copy(state = SlotState(calibrating = true)), KnownLoads.NONE, steps, unit,
            planLb = 245.0, planReps = 5)
        assertEquals(work(3, 215.0, 10), seeded.rows)
        assertFalse(seeded.rule.state.calibrating)
    }

    @Test fun `a planned 225 by 5 straight load seeds five rep sets at exactly 225 pounds`() {
        val seeded = SlotSeeding.rows(ProgramExercise("bb_back_squat", targetSets = 3), rule(), KnownLoads.NONE,
            steps, unit, planLb = 225.0, planReps = 5, planRir = 3)
        assertEquals(work(3, 225.0, 5), seeded.rows)
        assertFalse(seeded.rule.state.calibrating)
    }

    @Test fun `a converted plan load uses source and target rir and the unit step grid`() {
        val kg = WeightUnit.KG
        val seeded = SlotSeeding.rows(ProgramExercise("bb_back_squat", targetSets = 3), rule(10).copy(rir = 3),
            KnownLoads.NONE, LoadSteps.defaults(kg), kg, planLb = kg.toLb(100.0), planReps = 5, planRir = 3)
        assertEquals(3, seeded.rows.size)
        seeded.rows.forEach {
            val expected = kg.toLb(87.5)
            assertEquals(expected, it.weightLb, 1e-9)
            assertEquals(LoggedSet(expected, 10, SetKind.WORK), it.copy(weightLb = expected))
        }
        assertFalse(seeded.rule.state.calibrating)
    }

    @Test fun `a plan load Estimate cannot convert rounds down and calibrates`() {
        val seeded = SlotSeeding.rows(ProgramExercise("db_row", SetStructure.RAMP_TOP, 5),
            rule(structure = SetStructure.RAMP_TOP), KnownLoads.NONE, steps, unit,
            planLb = 62.5, planReps = 10, planRir = 2)
        assertEquals(60.0, seeded.rows.single { it.kind == SetKind.TOP }.weightLb)
        assertTrue(seeded.rule.state.calibrating)
    }
}
