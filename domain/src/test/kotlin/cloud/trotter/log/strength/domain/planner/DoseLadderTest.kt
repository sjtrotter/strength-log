package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.KnownLoads
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
        // audit 2026-11-13, row 13: 300 + 5*((15+3*3+90)+(15+5*3+120)) = 1620.
        assertEquals(1620, SessionBudget.seconds(day, LiftGoal.STRENGTH))
        // Step 5 with core is 1620+240=1860 > 1800; step 6 removes core and fits.
        assertEquals(listOf(PlanReason.ShortSession("A", 6)), draft.reasons)
        assertEquals(listOf(SetKind.RAMP, SetKind.TOP), draft.working.getValue(SlotKey("A", 0)).map { it.kind })
    }

    @Test fun `case 25 two short days stop at step three`() {
        val draft = Planner.plan(answers(days = 2))
        assertEquals(2, draft.program.days.size)
        for ((index, day) in draft.program.strengthDays.withIndex()) {
            val mains = day.exercises.filter { it.rule?.role == SlotRole.MAIN }
            assertEquals(if (index == 0) listOf("bb_back_squat", "bb_bench", "bb_row") else listOf("trap_dl", "ohp"),
                mains.map { it.exerciseId })
            for (slot in mains) {
                assertEquals(SetStructure.STRAIGHT, slot.structure)
                assertEquals(2, slot.targetSets)
                assertNull(slot.rule!!.restSeconds)
            }
            assertEquals(1, day.exercises.count { it.rule?.role == SlotRole.CORE })
            assertEquals(2, day.exercises.single { it.rule?.role == SlotRole.CORE }.targetSets)
            assertEquals(mains.size + 1, day.exercises.size)
            // audit 2026-11-13, row 13: step 3 A=300+3*2*210+2*120=1800;
            // B=300+2*2*210+2*99=1338. Both fit with two straight sets per main.
            assertEquals(if (index == 0) 1800 else 1338, SessionBudget.seconds(day, LiftGoal.STRENGTH))
        }
        assertEquals(listOf(PlanReason.ShortSession("A", 3), PlanReason.ShortSession("B", 3)), draft.reasons)
    }

    @Test fun `case 36 balance stays while an older day reports every excess second`() {
        val short = Planner.plan(answers(older = true))
        val day = short.program.strengthDays.single()
        assertEquals(listOf("single_leg_stance", "farmers_carry"), day.exercises.take(2).map { it.exerciseId })
        assertEquals(referenceAnswers().mains, day.exercises.filter { it.rule?.role == SlotRole.MAIN }.map { it.exerciseId })
        assertFalse(day.exercises.any { it.rule?.role == SlotRole.CORE })
        // audit 2026-11-13, row 13: balance = 2*(15+30*2+30)+2*(15+20+60) = 400;
        // 300+400+5*((15+3*3+90)+(15+6*3+120)) = 2035, excess 235, ceil(235/60) = 4.
        assertEquals(2035, SessionBudget.seconds(day, LiftGoal.STRENGTH))
        assertEquals(listOf(PlanReason.ShortSession("A", 6), PlanReason.OverBudget("A", 235)), short.reasons)
        assertEquals(4, ceil(short.reasons.filterIsInstance<PlanReason.OverBudget>().single().seconds / 60.0).toInt())
        assertEquals(listOf(30, 30), short.working.getValue(SlotKey("A", 0)).map { it.seconds })
        assertEquals(listOf(20, 20), short.working.getValue(SlotKey("A", 1)).map { it.seconds })
        val longer = Planner.plan(answers(minutes = 45, older = true))
        // audit 2026-11-13, row 13: step 1=300+400+5*2*153+2*120=2470 <= 2700;
        // all five mains retain two straight sets and core stays.
        assertEquals(listOf(PlanReason.ShortSession("A", 1)), longer.reasons)
        assertEquals(2470, SessionBudget.seconds(longer.program.strengthDays.single(), LiftGoal.STRENGTH))
        val four = Planner.plan(answers(older = true).copy(mains = referenceAnswers().mains!! - "bb_row"))
        // audit 2026-11-13, row 13: 300+400+4*267=1768; with core 1768+240=2008 > 1800, step 6 fits.
        assertEquals(listOf(PlanReason.ShortSession("A", 6)), four.reasons)
        assertEquals(1768, SessionBudget.seconds(four.program.strengthDays.single(), LiftGoal.STRENGTH))
        val three = Planner.plan(answers(older = true).copy(mains = referenceAnswers().mains!! - setOf("bb_row", "ohp")))
        // audit 2026-11-13, row 13: step 3=300+400+6*153+240=1858 > 1800;
        // step 4=300+400+3*267+240=1741, retaining core.
        assertEquals(listOf(PlanReason.ShortSession("A", 4)), three.reasons)
        assertEquals(1741, SessionBudget.seconds(three.program.strengthDays.single(), LiftGoal.STRENGTH))
    }

    @Test fun `General mains retain the resolved ninety second rest through step five`() {
        val answers = answers().copy(goal = LiftGoal.GENERAL)
        // Step 4 with core is 300+5*(114+129)+240=1755; reserve 150 seconds to reach step 5.
        val draft = liftDraft(answers, KnownLoads.NONE, budgetSeconds = 1650)
        assertEquals(listOf(PlanReason.ShortSession("A", 6)), draft.reasons)
        for (slot in draft.program.strengthDays.single().exercises.filter { it.rule?.role == SlotRole.MAIN }) {
            assertNull(slot.rule!!.restSeconds)
            assertEquals(90, RestPolicy.effectiveRestSeconds(SetKind.TOP, TrackingType.WEIGHTED,
                emptyMap(), slot.rule, LiftGoal.GENERAL))
        }
    }

    @Test fun `two seventy five minute days need no ladder`() {
        val draft = Planner.plan(answers(days = 2, minutes = 75))
        assertTrue(draft.reasons.isEmpty())
        // audit 2026-11-13, row 13: A = 300+3*744+4*150+3*99+2*120 = 3669;
        // B = 300+2*744+8*150+3*138+4*99+2*99 = 3996. No ladder or slot change.
        assertEquals(listOf(3669, 3996), draft.program.strengthDays.map { SessionBudget.seconds(it, LiftGoal.STRENGTH) })
        val last = draft.program.strengthDays.last().exercises
        assertEquals(listOf("ez_curl", "seated_curl", "pallof"), last.takeLast(3).map { it.exerciseId })
        val curl = last.single { it.exerciseId == "seated_curl" }
        assertEquals(SlotRole.ISOLATION, curl.rule!!.role)
        assertEquals(4, curl.targetSets)
        assertEquals(6.5, SetCount.weekly(draft.program)[Muscle.HAMSTRINGS])
    }

    @Test fun `empty main selection never lets a removal empty the day`() {
        val draft = Planner.plan(answers().copy(mains = emptyList(), equipment = EquipmentPreset.BODYWEIGHT.equipment))
        assertTrue(draft.program.strengthDays.single().exercises.isNotEmpty())
        assertTrue(draft.working.isNotEmpty())
    }
}
