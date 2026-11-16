package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O07: audit 2026-11-13 decision 12. The offer is optional and only changes Lift's rotation after acceptance. */
class PlannerBalanceDaysTest {
    private fun older(days: Int) = SetupAnswers(olderAdult = true, days = days)

    // audit 2026-11-13 decision 12.
    @Test fun `an older lifter on one day is offered balance days`() {
        val draft = Planner.plan(older(1))
        assertEquals(listOf("A"), draft.program.days.map { it.id })
        assertEquals(listOf("Full Body"), draft.program.days.map { it.title })
        assertEquals(1, draft.reasons.count { it == PlanReason.BalanceDaysOffered })
        assertFalse(PlanReason.BalanceDays in draft.reasons)
    }

    // audit 2026-11-13 decision 12.
    @Test fun `accepted balance days on one lifting day add two entries`() {
        val draft = Planner.plan(older(1).copy(balanceDays = true))
        assertEquals(listOf("A", "B", "C"), draft.program.days.map { it.id })
        assertEquals(listOf("Full Body", "Balance", "Balance"), draft.program.days.map { it.title })
        assertTrue(draft.program.days.all { it.kind == ProgramDayKind.STRENGTH })
        for (day in draft.program.days.drop(1)) {
            assertEquals(listOf("single_leg_stance", "farmers_carry"), day.exercises.map { it.exerciseId })
            assertTrue(day.exercises.all { it.rule?.role == SlotRole.BALANCE })
            assertEquals(listOf(30, 30), draft.working.getValue(SlotKey(day.id, 0)).map { it.seconds })
            val carry = draft.working.getValue(SlotKey(day.id, 1))
            assertEquals(listOf(20, 20), carry.map { it.seconds })
            assertEquals(listOf(10.0, 10.0), carry.map { it.weightLb })
        }
        assertEquals(1, draft.reasons.count { it == PlanReason.BalanceDays })
        assertFalse(PlanReason.BalanceDaysOffered in draft.reasons)
    }

    // audit 2026-11-13 decision 12.
    @Test fun `accepted balance days on two lifting days add one entry between them`() {
        val answers = older(2).copy(balanceDays = true)
        val draft = Planner.plan(answers)
        val original = liftDraft(older(2), KnownLoads.NONE)
        assertEquals(listOf("Full Body", "Balance", "Full Body"), draft.program.days.map { it.title })
        val lifts = draft.program.days.filter { it.title != "Balance" }
        assertEquals(Planner.defaultMains(EquipmentPreset.FULL_GYM.equipment).sorted(),
            lifts.flatMap { it.exercises }.filter { it.rule?.role == SlotRole.MAIN }.map { it.exerciseId }.sorted())
        for ((before, after) in original.program.days.zip(lifts)) {
            assertEquals(before.copy(id = after.id), after)
            assertEquals(original.working.filterKeys { it.dayId == before.id }.mapKeys { it.key.index },
                draft.working.filterKeys { it.dayId == after.id }.mapKeys { it.key.index })
        }
        assertEquals(relabel(original, mapOf("A" to "A", "B" to "C")).reasons + PlanReason.BalanceDays, draft.reasons)
    }

    // audit 2026-11-13 decision 12.
    @Test fun `three days and younger lifters get neither reason`() {
        val answers = listOf(older(3), older(3).copy(balanceDays = true)) +
            (1..2).map { SetupAnswers(days = it, balanceDays = true) }
        for (a in answers) {
            val draft = Planner.plan(a)
            assertEquals(liftDraft(a, KnownLoads.NONE), draft)
            assertTrue(draft.program.days.none { it.title == "Balance" })
            assertFalse(PlanReason.BalanceDays in draft.reasons || PlanReason.BalanceDaysOffered in draft.reasons)
        }
    }

    // audit 2026-11-13 decision 12.
    @Test fun `without dumbbells the balance entry is the stance alone`() {
        val draft = Planner.plan(older(2).copy(balanceDays = true, equipment = EquipmentPreset.KETTLEBELLS.equipment))
        assertEquals(listOf("single_leg_stance"), draft.program.days[1].exercises.map { it.exerciseId })
    }

    // audit 2026-11-13 decision 12.
    @Test fun `an excluded balance block offers and adds nothing`() {
        for (accepted in listOf(false, true)) {
            val a = older(1).copy(balanceDays = accepted, excluded = setOf("single_leg_stance", "farmers_carry"))
            assertEquals(liftDraft(a, KnownLoads.NONE), Planner.plan(a))
            assertEquals(1, Planner.plan(a).program.days.size)
        }
    }

    // audit 2026-11-13 decision 12.
    @Test fun `Planner day rebuilds a balance entry`() {
        val day = requireNotNull(Planner.day(older(2).copy(balanceDays = true), "B"))
        assertEquals("Balance", day.day.title)
        assertEquals(setOf(0, 1), day.working.keys)
        assertTrue(day.reasons.isEmpty())
    }

    // audit 2026-11-13 decision 12.
    @Test fun `Both keeps its layout for older lifters`() {
        val a = SetupAnswers(branch = PlanBranch.BOTH, days = 2, olderAdult = true, balanceDays = true)
        val draft = Planner.plan(a, todayEpochDay = 0)
        assertEquals(Planner.plan(a.copy(balanceDays = false), todayEpochDay = 0), draft)
        assertTrue(draft.program.days.none { it.title == "Balance" })
        assertFalse(PlanReason.BalanceDays in draft.reasons || PlanReason.BalanceDaysOffered in draft.reasons)
    }
}
