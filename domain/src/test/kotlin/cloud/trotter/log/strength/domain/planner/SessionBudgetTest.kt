package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionBudgetTest {
    private fun rule(target: RepTarget) = SlotRule(SlotRole.ISOLATION, target, 2, 0.025, 1,
        scheme = Scheme.STRENGTH, restSeconds = 0)
    private fun day(slot: ProgramExercise) = ProgramDay("A", "Upper", "", listOf(slot))
    private fun repSlot(reps: Int) = ProgramExercise("ez_curl", targetSets = 1, rule = rule(RepTarget.Fixed(reps)))

    @Test fun `five and twelve reps include setup and rep time`() {
        // Work: 15+5*3=30 and 15+12*3=51; zero rest, plus the 300-second warm-up.
        assertEquals(300 + 30, SessionBudget.seconds(day(repSlot(5)), LiftGoal.STRENGTH))
        assertEquals(300 + 51, SessionBudget.seconds(day(repSlot(12)), LiftGoal.STRENGTH))
    }

    @Test fun `eachSide doubles eight reps but not setup`() {
        val slot = repSlot(8).copy(prescription = Prescription(PrescribedTarget.Reps(8), eachSide = true))
        // Work: 15+8*3*2=63, with setup counted once.
        assertEquals(300 + 63, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH))
    }

    @Test fun `a thirty second eachSide hold doubles time but not setup`() {
        val slot = ProgramExercise("single_leg_stance", targetSets = 1, rule = rule(RepTarget.Fixed(8)))
        // Catalog eachSide hold: 15+30*2=75, with zero fixture rest.
        assertEquals(300 + 75, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH))
    }

    @Test fun `a superset counts both ten rep lifts once and one rest`() {
        val slot = repSlot(10).copy(rule = rule(RepTarget.Fixed(10)).copy(restSeconds = 60),
            superset = SupersetPartner("rope_pushdown", rule(RepTarget.Fixed(10)).copy(restSeconds = 120)))
        // Each lift: 15+10*3=45; one primary rest of 60, ignoring the partner's 120.
        assertEquals(300 + 45 + 45 + 60, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH))
    }

    @Test fun `a range uses its current target and ramps and backoffs use their own reps`() {
        val slot = repSlot(8).copy(rule = rule(RepTarget.Range(8, 12, 11)))
        // Current target: 15+11*3=48; scheme work is 30/24/30/30/30, rest 90/90/0/120/120.
        assertEquals(300 + 48, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH))
        val ramped = repSlot(5).copy(structure = SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(300 + (30 + 90) + (24 + 90) + 30 + 2 * (30 + 120),
            SessionBudget.seconds(day(ramped), LiftGoal.STRENGTH))
    }

    @Test fun `strength and cardio include planned time and an opening warmup replaces the allowance`() {
        val strength = day(repSlot(5))
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))
        // 300 warm-up + (15+5*3) work + 1200 run = 1530; optional cool-down is outside the budget.
        assertEquals(1530, SessionBudget.seconds(strength.copy(cardioBlock = block), LiftGoal.STRENGTH))
        val cardio = strength.copy(kind = ProgramDayKind.CARDIO, exercises = emptyList<ProgramExercise>(), cardioBlock = block)
        assertEquals(300 + 1200, SessionBudget.seconds(cardio, LiftGoal.STRENGTH))
        val warmed = block.copy(sets = listOf(CardioSetSpec(CardioRole.WARMUP, Measure.Time(600))) + block.sets)
        assertEquals(600 + 1200, SessionBudget.seconds(cardio.copy(cardioBlock = warmed), LiftGoal.STRENGTH))
    }

    @Test fun `ramped prescriptions count the scheme sets when targetSets differs`() {
        val slot = repSlot(5).copy(structure = SetStructure.RAMP_TOP_BACKOFF,
            prescription = Prescription(PrescribedTarget.Reps(5)))
        // targetSets is 1; the Strength scheme has five sets. Prescription work is 15+5*3 on each.
        assertEquals(300 + (30 + 90) + (30 + 90) + 30 + 2 * (30 + 120),
            SessionBudget.seconds(day(slot), LiftGoal.STRENGTH))
        val timed = slot.copy(prescription = Prescription(PrescribedTarget.Hold(30), eachSide = true))
        // Each prescribed hold is 15+30*2=75; rests are 90+90+0+120+120=420.
        assertEquals(300 + 5 * 75 + 420, SessionBudget.seconds(day(timed), LiftGoal.STRENGTH))
        val catalogTimed = slot.copy(exerciseId = "plank", prescription = null)
        // Catalog holds use all five scheme sets too: 15+45=60 seconds each.
        assertEquals(300 + 5 * 60 + 420, SessionBudget.seconds(day(catalogTimed), LiftGoal.STRENGTH))
    }
    @Test fun `the cardio budget is the minutes less the rest of the day`() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))
        val cardio = ProgramDay("A", "Run", "", emptyList<ProgramExercise>(), kind = ProgramDayKind.CARDIO, cardioBlock = block)
        assertEquals(3300, SessionBudget.cardioBudget(cardio, 60, LiftGoal.STRENGTH))
        val warmed = cardio.copy(cardioBlock = block.copy(sets = listOf(CardioSetSpec(CardioRole.WARMUP, Measure.Time(300))) + block.sets))
        assertEquals(3600, SessionBudget.cardioBudget(warmed, 60, LiftGoal.STRENGTH))
        val slot = ProgramExercise("bb_back_squat", targetSets = 2,
            rule = SlotRule.defaults(SlotRole.SECONDARY, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 5, MovementPattern.SQUAT_BILATERAL))
        val combined = cardio.copy(exercises = listOf(slot))
        val liftSeconds = SessionBudget.seconds(combined, LiftGoal.STRENGTH) - 300 - 1200
        assertEquals(3300 - liftSeconds, SessionBudget.cardioBudget(combined, 60, LiftGoal.STRENGTH))
        assertEquals(null, SessionBudget.cardioBudget(cardio.copy(cardioBlock = null), 60, LiftGoal.STRENGTH))
    }

}
