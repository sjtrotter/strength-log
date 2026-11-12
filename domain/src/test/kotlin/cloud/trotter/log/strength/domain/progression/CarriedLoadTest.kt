package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class CarriedLoadTest {
    private val rule = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.0125, 1, scheme = Scheme.STRENGTH)
    private val ctx = Progression.Context(WeightUnit.LB, setOf(Equipment.BARBELL), LoadSteps.defaults(WeightUnit.LB), 130)
    private fun work(n: Int = 3) = List(n) { LoggedSet(185.0, 5, SetKind.WORK) }
    private fun finish(rows: List<LoggedSet>, weights: List<Double>, rule: SlotRule = this.rule, actual: List<LoggedSet>? = null) =
        Progression.afterSession(rule, rows, Progression.Exposure(rows, actual ?: rows.mapIndexed { i, row ->
            row.copy(weightLb = weights[i], plannedIndex = i, done = true)
        }, 130), ctx)

    @Test fun `a strict majority adopts the lower load without an increase`() {
        val out = finish(work(), listOf(175.0, 175.0, 185.0))
        assertEquals(Change.KeptLowered(175.0), out.change)
        assertEquals(List(3) { LoggedSet(175.0, 5, SetKind.WORK) }, out.rows)
        assertEquals(0, out.rule.state.successStreak)
    }

    @Test fun `a lower minority blocks success and two rep misses drop the anchor`() {
        val rows = work()
        val first = finish(rows, listOf(175.0, 185.0, 185.0))
        assertEquals(Change.Hold, first.change)
        assertEquals(Reason.RepMiss, first.reason)
        assertEquals(1, first.rule.state.repMissStreak)
        val second = finish(rows, listOf(175.0, 185.0, 185.0), first.rule)
        assertEquals(Change.Decrease(185.0, 175.0), second.change)
    }

    @Test fun `ties missing votes ramps and unplanned extras cannot choose the load`() {
        val rows = work(4)
        assertEquals(Change.Hold, finish(rows, listOf(185.0, 185.0, 195.0, 195.0)).change)
        val actual = listOf(rows[0].copy(done = true, weightLb = 175.0, plannedIndex = 0)) +
            List(5) { LoggedSet(175.0, 5, SetKind.EXTRA, done = true) }
        val out = finish(rows, emptyList(), actual = actual)
        assertEquals(rows, out.rows)
        assertEquals(0, out.rule.state.successStreak)
    }

    @Test fun `a completed TOP supplies the next anchor and a missing TOP keeps it`() {
        val rows = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 185.0, 5, 1, WeightUnit.LB, ctx.equipment, rule.scheme)
        val actual = rows.mapIndexed { i, row -> row.copy(done = true, plannedIndex = i,
            weightLb = if (row.kind == SetKind.TOP) 175.0 else row.weightLb) }
        val out = finish(rows, emptyList(), actual = actual)
        assertEquals(Change.KeptLowered(175.0), out.change)
        assertEquals(WorkingRows.withWorkingWeight(rows, 175.0, WeightUnit.LB, ctx.equipment, rule.scheme), out.rows)
        val missing = finish(rows, emptyList(), actual = actual.filter { it.kind != SetKind.TOP })
        assertEquals(185.0, WorkingRows.workingWeight(missing.rows))
    }

    @Test fun `a row planned at zero or timed cannot be a lowered load`() {
        for (seconds in listOf(0, 30)) {
            val rows = List(3) { LoggedSet(0.0, 5, SetKind.WORK, seconds = seconds) }
            val out = finish(rows, List(3) { 0.0 })
            assertEquals(if (seconds > 0) Reason.Timed else Reason.AllMade, out.reason)
        }
    }
}
