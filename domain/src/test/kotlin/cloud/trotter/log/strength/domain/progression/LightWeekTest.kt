package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class LightWeekTest {
    private val ctx = Progression.Context(WeightUnit.LB, setOf(Equipment.BARBELL, Equipment.RACK),
        LoadSteps.defaults(WeightUnit.LB), 1004, light = true)
    private fun squat() = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 275.0, 5, 1, WeightUnit.LB, ctx.equipment, Scheme.STRENGTH)
    private fun rule() = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 2, scheme = Scheme.STRENGTH,
        state = SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1, exposures = 4))
    private fun actual(planned: List<LoggedSet>, done: Boolean = true) =
        planned.mapIndexed { i, row -> row.copy(plannedIndex = i, done = done) }

    @Test
    fun `case 14 light rows keep ramps and sixty percent of work sets at ninety percent load`() {
        val rows = LightWeek.rows(squat(), ctx)
        assertEquals(listOf(
            LoggedSet(165.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
            LoggedSet(250.0, 5, SetKind.TOP), LoggedSet(225.0, 5, SetKind.BACKOFF),
        ), rows)
        assertEquals(rows, Progression.atStart(rule(), squat(), 1000, 4, ctx).rows)
    }

    @Test
    fun `case 14 Finish restores working rows and preserves all streaks on a light day`() {
        val input = rule()
        val planned = LightWeek.rows(squat(), ctx)
        val out = Progression.afterSession(input, squat(), Progression.Exposure(planned, actual(planned), 1004), ctx)
        assertEquals(Change.LightCycle, out.change)
        assertEquals(Reason.LightCycle, out.reason)
        assertEquals(WorkingRows.cleared(squat()), out.rows)
        assertEquals(input.state.copy(exposures = 5), out.rule.state)
        assertEquals(listOf(2, 1, 1), listOf(out.rule.state.successStreak, out.rule.state.repMissStreak, out.rule.state.effortMissStreak))
    }

    @Test
    fun `light rows keep at least one work set and preserve zero load`() {
        val bodyweight = listOf(LoggedSet(0.0, 8, SetKind.WORK))
        assertEquals(bodyweight, LightWeek.rows(bodyweight, ctx))
        assertEquals(emptyList(), LightWeek.rows(emptyList(), ctx))
    }

    @Test
    fun `light view follows layoff scaling`() {
        val normal = ctx.copy(todayEpochDay = 1016, light = false)
        val scaled = Progression.atStart(rule(), squat(), 1000, 4, normal)
        val light = Progression.atStart(rule(), squat(), 1000, 4, normal.copy(light = true))
        assertEquals(LightWeek.rows(scaled.rows, ctx), light.rows)
        assertEquals(scaled.layoff, light.layoff)
    }
}
