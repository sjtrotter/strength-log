package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.Equipment
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

class SlotSeedingTest {
    private fun rule(reps: Int = 5) = SlotRule(SlotRole.MAIN, RepTarget.Fixed(reps), 2, 0.025, 1, scheme = Scheme.STRENGTH)
    private fun seed(id: String, rule: SlotRule = rule(), last: LoggedSet? = null, unit: WeightUnit = WeightUnit.LB,
                     structure: SetStructure = SetStructure.STRAIGHT): SlotSeeding.Seeded =
        SlotSeeding.rows(ProgramExercise(id, structure, 3), rule,
            if (last == null) KnownLoads.NONE else KnownLoads(mapOf(id to last)), LoadSteps.defaults(unit), unit)
    private fun last(weight: Double, reps: Int, rir: Int? = 2) = LoggedSet(weight, reps, SetKind.WORK, rir = rir)
    private fun assertWork(seed: SlotSeeding.Seeded, weight: Double, reps: Int, calibrating: Boolean) {
        assertEquals(List(3) { LoggedSet(weight, reps, SetKind.WORK) }, seed.rows)
        assertEquals(calibrating, seed.rule.state.calibrating)
    }

    @Test
    fun `case 4 opening uses the estimate or keeps an untrusted history load for calibration`() {
        assertWork(seed("bb_bench", last = last(185.0, 8)), 200.0, 5, false)
        assertWork(seed("bb_bench", last = last(135.0, 12)), 135.0, 5, true)
    }

    @Test
    fun `case 27 history without rir reads as two and adjusts to Muscle reps`() {
        assertWork(seed("bb_back_squat", rule(10), last(225.0, 5, null)), 200.0, 10, false)
    }

    @Test
    fun `case 38 rows keep the prototype scheme for a hand added ramped bench`() {
        val rule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.RAMP_TOP_BACKOFF, 5, MovementPattern.H_PUSH)
        val seeded = seed("bb_bench", rule, last(185.0, 5), structure = SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(listOf(100.0, 130.0, 150.0, 165.0, 185.0, 140.0), seeded.rows.map { it.weightLb })
        assertEquals(listOf(5, 5, 5, 3, 5, 8), seeded.rows.map { it.reps })
        assertEquals(listOf(SetKind.RAMP, SetKind.RAMP, SetKind.RAMP, SetKind.RAMP, SetKind.TOP, SetKind.BACKOFF), seeded.rows.map { it.kind })
        assertFalse(seeded.rule.state.calibrating)
    }

    @Test
    fun `equipment floors calibrate without a formula in pounds and kg`() {
        for ((id, lb, kg) in listOf(
            Triple("bb_back_squat", 45.0, 20.0), Triple("hammer_curl", 10.0, 5.0),
            Triple("cable_curl", 20.0, 10.0), Triple("kb_goblet_squat", 18.0, 8.0),
            Triple("ez_curl", 20.0, 10.0), Triple("trap_dl", 45.0, 20.0), Triple("custom_exercise", 10.0, 5.0),
        )) {
            assertWork(seed(id), lb, 5, true)
            assertWork(seed(id, unit = WeightUnit.KG), WeightUnit.KG.toLb(kg), 5, true)
        }
    }

    @Test
    fun `case 28 never opened hack squat seeds three tens at twenty pounds calibrating`() {
        val rule = SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, SetStructure.STRAIGHT, null, MovementPattern.SQUAT_BILATERAL)
        assertWork(seed("hack_squat", rule), 20.0, 10, true)
    }

    @Test
    fun `bodyweight rows use the rule reps and keep the rule`() {
        val rule = rule(8).copy(state = SlotState(calibrating = true))
        val seeded = seed("pullup", rule)
        assertEquals(List(3) { LoggedSet(0.0, 8, SetKind.WORK) }, seeded.rows)
        assertEquals(rule, seeded.rule)
    }

    @Test
    fun `authored load prescription seeds its own rows and keeps the rule`() {
        val slot = ProgramExercise("bb_bench", prescription = Prescription(PrescribedTarget.LoadReps(30.0, 8)))
        val rule = rule().copy(state = SlotState(calibrating = true))
        val seeded = SlotSeeding.rows(slot, rule, KnownLoads.NONE, LoadSteps.defaults(WeightUnit.LB), WeightUnit.LB)
        assertEquals(SetSeeder.seedPrescribed(3, requireNotNull(slot.prescription), equipment = ExerciseLibrary.get(slot.exerciseId).equipment), seeded.rows)
        assertEquals(rule, seeded.rule)
    }

    @Test
    fun `timed rows use catalog seconds and added load and keep the rule`() {
        val rule = rule()
        val seeded = seed("weighted_plank", rule)
        assertEquals(List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 45) }, seeded.rows)
        assertEquals(rule, seeded.rule)
        assertTrue(seed("bb_bench", last = last(0.0, 5)).rule.state.calibrating)
    }

    @Test
    fun `custom timed hold seeds forty five seconds from the resolved entry`() {
        val entry = ExerciseLibrary.get("weighted_plank").copy(id = "custom_hold", goal = GoalSource.Time(45))
        val input = rule()
        val seeded = SlotSeeding.rows(ProgramExercise(entry.id, targetSets = 3), input, KnownLoads.NONE,
            LoadSteps.defaults(WeightUnit.LB), WeightUnit.LB, entry = entry)
        assertEquals(List(3) { LoggedSet(0.0, 0, SetKind.WORK, seconds = 45) }, seeded.rows)
        assertEquals(input, seeded.rule)
    }

    @Test
    fun `custom barbell lift uses the resolved equipment floor and step`() {
        val entry = ExerciseLibrary.get("bb_back_squat").copy(id = "custom_barbell", equipment = listOf(Equipment.BARBELL))
        val slot = ProgramExercise(entry.id, targetSets = 3)
        val steps = LoadSteps(barLb = 10.0, dumbbellLb = 5.0, stackLb = 20.0)
        val seeded = SlotSeeding.rows(slot, rule(), KnownLoads.NONE, steps, WeightUnit.LB, entry = entry)
        assertWork(seeded, 45.0, 5, true)
        val known = KnownLoads(mapOf(entry.id to last(186.0, 5)))
        val fromHistory = SlotSeeding.rows(slot, rule(), known, steps, WeightUnit.LB, entry = entry)
        assertWork(fromHistory, 190.0, 5, false)
    }
}
