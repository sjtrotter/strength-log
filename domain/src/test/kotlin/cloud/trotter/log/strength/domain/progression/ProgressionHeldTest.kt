package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressionHeldTest {
    private fun work(weight: Double = 185.0, reps: Int = 5) = List(3) { LoggedSet(weight, reps, SetKind.WORK) }
    private fun rule(target: RepTarget = RepTarget.Fixed(5), state: SlotState = SlotState()) =
        SlotRule(SlotRole.MAIN, target, 2, 0.025, 1, scheme = Scheme.STRENGTH, state = state)
    private fun finish(rows: List<LoggedSet>, rule: SlotRule = rule(), reps: List<Int>? = null,
                       weight: Double? = null, equipment: Set<Equipment> = setOf(Equipment.BARBELL), rir: Int? = null): Outcome =
        Progression.afterSession(rule, rows, Progression.Exposure(rows, rows.mapIndexed { i, row ->
            row.copy(done = true, plannedIndex = i, weightLb = weight ?: row.weightLb, reps = reps?.get(i) ?: row.reps, rir = rir)
        }, 130, false), Progression.Context(WeightUnit.LB, equipment, LoadSteps.defaults(WeightUnit.LB), 130))

    @Test fun `a fixed increase holds the opening load and asks`() {
        val rows = work(95.0)
        val out = finish(rows)
        assertEquals(Change.Increase(95.0, 100.0), out.change)
        assertEquals(work(100.0), out.rows)
        assertEquals(rows, out.held)
        assertTrue(out.asks)
    }

    @Test fun `a drop holds the stalled load and asks`() {
        val first = finish(work(), reps = listOf(5, 5, 3))
        val out = finish(first.rows, first.rule, listOf(5, 4, 4))
        assertEquals(Change.Decrease(185.0, 175.0), out.change)
        assertEquals(work(175.0), out.rows)
        assertEquals(work(), out.held)
        assertTrue(out.asks)
    }

    @Test fun `a range step at its top holds its rows and asks`() {
        val rows = work(60.0, 12)
        val out = finish(rows, rule(RepTarget.Range(8, 12, 12)), equipment = setOf(Equipment.DUMBBELL))
        assertEquals(Change.Increase(60.0, 65.0), out.change)
        assertEquals(rows, out.held)
        assertTrue(out.asks)
    }

    @Test fun `calibration reps and a kept lowered weight all ask`() {
        val calibration = finish(work(45.0), rule(state = SlotState(calibrating = true)), rir = 4)
        val bodyweight = finish(work(0.0, 15), rule(RepTarget.Fixed(15)), equipment = emptySet())
        val lowered = finish(work(), weight = 175.0)
        for (out in listOf(calibration, bodyweight, lowered)) assertTrue(out.asks)
        assertEquals(Change.RepsTo(16), bodyweight.change)
        assertEquals(Change.KeptLowered(175.0), lowered.change)
        assertEquals(work(), lowered.held)
    }
}
