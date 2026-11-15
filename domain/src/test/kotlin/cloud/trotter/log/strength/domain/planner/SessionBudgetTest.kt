package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
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
import kotlin.test.assertTrue

class SessionBudgetTest {
    private fun rule(target: RepTarget) = SlotRule(SlotRole.ISOLATION, target, 2, 0.025, 1,
        scheme = Scheme.STRENGTH, restSeconds = 0)
    private fun day(slot: ProgramExercise) = ProgramDay("A", "Upper", "", listOf(slot))
    private fun repSlot(reps: Int) = ProgramExercise("ez_curl", targetSets = 1, rule = rule(RepTarget.Fixed(reps)))

    @Test fun `five and twelve reps include setup and rep time`() {
        assertEquals(300 + 30, SessionBudget.seconds(day(repSlot(5)), LiftGoal.STRENGTH, coolDown = false))
        assertEquals(300 + 51, SessionBudget.seconds(day(repSlot(12)), LiftGoal.STRENGTH, coolDown = false))
    }

    @Test fun `eachSide doubles eight reps but not setup`() {
        val slot = repSlot(8).copy(prescription = Prescription(PrescribedTarget.Reps(8), eachSide = true))
        assertEquals(300 + 63, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH, coolDown = false))
    }

    @Test fun `a thirty second eachSide hold doubles time but not setup`() {
        val slot = ProgramExercise("single_leg_stance", targetSets = 1, rule = rule(RepTarget.Fixed(8)))
        assertEquals(300 + 75, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH, coolDown = false))
    }

    @Test fun `a superset counts both ten rep lifts once and one rest`() {
        val slot = repSlot(10).copy(rule = rule(RepTarget.Fixed(10)).copy(restSeconds = 60),
            superset = SupersetPartner("rope_pushdown", rule(RepTarget.Fixed(10)).copy(restSeconds = 120)))
        assertEquals(300 + 45 + 45 + 60, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH, coolDown = false))
    }

    @Test fun `a range uses its current target and ramps and backoffs use their own reps`() {
        val slot = repSlot(8).copy(rule = rule(RepTarget.Range(8, 12, 11)))
        assertEquals(300 + 48, SessionBudget.seconds(day(slot), LiftGoal.STRENGTH, coolDown = false))
        val ramped = repSlot(5).copy(structure = SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(300 + (30 + 90) + (24 + 90) + 30 + 2 * (30 + 120),
            SessionBudget.seconds(day(ramped), LiftGoal.STRENGTH, coolDown = false))
    }

    @Test fun `cool-down adds four minutes to strength days and keeps cardio planned time`() {
        val strength = day(repSlot(5))
        assertTrue(SetupAnswers().coolDown)
        assertEquals(240, SessionBudget.seconds(strength, LiftGoal.STRENGTH) -
            SessionBudget.seconds(strength, LiftGoal.STRENGTH, coolDown = false))
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))
        assertEquals(300 + 240 + 30 + 1200, SessionBudget.seconds(strength.copy(cardioBlock = block), LiftGoal.STRENGTH))
        val cardio = strength.copy(kind = ProgramDayKind.CARDIO, exercises = emptyList<ProgramExercise>(), cardioBlock = block)
        assertEquals(1500, SessionBudget.seconds(cardio, LiftGoal.STRENGTH))
        assertEquals(1500, SessionBudget.seconds(cardio, LiftGoal.STRENGTH, coolDown = false))
    }

    @Test fun `cool-down answer reaches the dose ladder`() {
        val answers = referenceAnswers().copy(days = 1, minutes = 30)
        val on = Planner.plan(answers)
        val off = Planner.plan(answers.copy(coolDown = false))
        assertEquals(listOf(PlanReason.ShortSession("A", 6), PlanReason.OverBudget("A", 60)), on.reasons)
        assertEquals(listOf(PlanReason.ShortSession("A", 6)), off.reasons)
        assertEquals(1620, SessionBudget.seconds(off.program.strengthDays.single(), answers.goal, coolDown = false))
    }
}
