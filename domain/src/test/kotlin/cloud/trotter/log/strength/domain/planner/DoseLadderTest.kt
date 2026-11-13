package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoseLadderTest {
    private fun answers(days: Int = 1, minutes: Int = 30, older: Boolean = false) =
        referenceAnswers().copy(days = days, minutes = minutes, olderAdult = older, recent = emptyMap())

    @Test fun `case 25 one short day retains five mains at step six`() {
        val draft = Planner.plan(answers())
        val day = draft.program.strengthDays.single()
        assertEquals(referenceAnswers().mains, day.exercises.map { it.exerciseId })
        for (slot in day.exercises) {
            assertEquals(SlotRole.MAIN, slot.rule!!.role)
            assertEquals(SetStructure.RAMP_TOP, slot.structure)
            assertEquals(2, slot.targetSets)
            assertEquals(listOf(Scheme.Ramp(0.80, 3)), slot.rule!!.scheme.ramps)
            assertEquals(0, slot.rule!!.scheme.backoffs)
            assertEquals(120, slot.rule!!.restSeconds)
        }
        assertEquals(1800, SessionBudget.seconds(day, LiftGoal.STRENGTH))
        assertEquals(listOf(PlanReason.ShortSession("A", 6)), draft.reasons)
        assertEquals(listOf(SetKind.RAMP, SetKind.TOP), draft.working.getValue(SlotKey("A", 0)).map { it.kind })
    }

    @Test fun `case 25 two short days stop at steps four and three`() {
        val draft = Planner.plan(answers(days = 2))
        assertEquals(2, draft.program.days.size)
        for ((index, day) in draft.program.strengthDays.withIndex()) {
            val mains = day.exercises.filter { it.rule?.role == SlotRole.MAIN }
            assertEquals(if (index == 0) listOf("bb_back_squat", "bb_bench", "bb_row") else listOf("trap_dl", "ohp"),
                mains.map { it.exerciseId })
            for (slot in mains) {
                assertEquals(if (index == 0) SetStructure.RAMP_TOP else SetStructure.STRAIGHT, slot.structure)
                assertEquals(2, slot.targetSets)
                assertNull(slot.rule!!.restSeconds)
                if (index == 0) assertEquals(listOf(Scheme.Ramp(0.80, 3)), slot.rule!!.scheme.ramps)
            }
            assertEquals(1, day.exercises.count { it.rule?.role == SlotRole.CORE })
            assertEquals(2, day.exercises.single { it.rule?.role == SlotRole.CORE }.targetSets)
            assertEquals(mains.size + 1, day.exercises.size)
            assertEquals(if (index == 0) 1590 else 1410, SessionBudget.seconds(day, LiftGoal.STRENGTH))
        }
        assertEquals(listOf(PlanReason.ShortSession("A", 4), PlanReason.ShortSession("B", 3)), draft.reasons)
    }

    @Test fun `case 36 balance stays while an older day reports every excess second`() {
        val short = Planner.plan(answers(older = true))
        val day = short.program.strengthDays.single()
        assertEquals(listOf("single_leg_stance", "farmers_carry"), day.exercises.take(2).map { it.exerciseId })
        assertEquals(referenceAnswers().mains, day.exercises.filter { it.rule?.role == SlotRole.MAIN }.map { it.exerciseId })
        assertFalse(day.exercises.any { it.rule?.role == SlotRole.CORE })
        assertEquals(2160, SessionBudget.seconds(day, LiftGoal.STRENGTH))
        assertEquals(listOf(PlanReason.ShortSession("A", 6), PlanReason.OverBudget("A", 360)), short.reasons)
        assertEquals(6, ceil(short.reasons.filterIsInstance<PlanReason.OverBudget>().single().seconds / 60.0).toInt())
        assertEquals(listOf(30, 30), short.working.getValue(SlotKey("A", 0)).map { it.seconds })
        assertEquals(listOf(20, 20), short.working.getValue(SlotKey("A", 1)).map { it.seconds })
        val longer = Planner.plan(answers(minutes = 45, older = true))
        assertEquals(listOf(PlanReason.ShortSession("A", 1)), longer.reasons)
        assertEquals(2520, SessionBudget.seconds(longer.program.strengthDays.single(), LiftGoal.STRENGTH))
        val four = Planner.plan(answers(older = true).copy(mains = referenceAnswers().mains!! - "bb_row"))
        assertEquals(listOf(PlanReason.ShortSession("A", 6), PlanReason.OverBudget("A", 60)), four.reasons)
        assertEquals(1860, SessionBudget.seconds(four.program.strengthDays.single(), LiftGoal.STRENGTH))
        val three = Planner.plan(answers(older = true).copy(mains = referenceAnswers().mains!! - setOf("bb_row", "ohp")))
        assertEquals(listOf(PlanReason.ShortSession("A", 4)), three.reasons)
        assertEquals(1770, SessionBudget.seconds(three.program.strengthDays.single(), LiftGoal.STRENGTH))
    }

    @Test fun `General mains retain the resolved ninety second rest through step five`() {
        val draft = Planner.plan(answers().copy(goal = LiftGoal.GENERAL))
        assertTrue(draft.reasons.any { it is PlanReason.ShortSession && it.step >= 5 })
        for (slot in draft.program.strengthDays.single().exercises.filter { it.rule?.role == SlotRole.MAIN }) {
            assertNull(slot.rule!!.restSeconds)
            assertEquals(90, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED,
                emptyMap(), slot.rule, LiftGoal.GENERAL))
        }
    }

    @Test fun `two seventy five minute days need no ladder`() {
        val draft = Planner.plan(answers(days = 2, minutes = 75))
        assertTrue(draft.reasons.isEmpty())
        assertEquals(listOf(3960, 3465), draft.program.strengthDays.map { SessionBudget.seconds(it, LiftGoal.STRENGTH) })
    }

    @Test fun `empty main selection never lets a removal empty the day`() {
        val draft = Planner.plan(answers().copy(mains = emptyList(), equipment = EquipmentPreset.BODYWEIGHT.equipment))
        assertTrue(draft.program.strengthDays.single().exercises.isNotEmpty())
        assertTrue(draft.working.isNotEmpty())
    }
}
