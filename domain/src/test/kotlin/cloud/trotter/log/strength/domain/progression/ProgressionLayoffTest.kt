package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionLayoffTest {
    private fun squat() = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 275.0, 5, 1, WeightUnit.LB,
        listOf(Equipment.BARBELL, Equipment.RACK), Scheme.STRENGTH)
    private fun rule(state: SlotState = SlotState()) =
        SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 2, scheme = Scheme.STRENGTH, state = state)
    private fun ctx(day: Long = 1016) = Progression.Context(WeightUnit.LB, setOf(Equipment.BARBELL, Equipment.RACK),
        LoadSteps.defaults(WeightUnit.LB), day)
    private fun actual(planned: List<LoggedSet>, done: Boolean = true) =
        planned.mapIndexed { i, row -> row.copy(plannedIndex = i, done = done) }
    private fun opening(day: Long, rule: SlotRule = rule()) = Progression.atStart(rule, squat(), 1000, 4, ctx(day))
    private fun finish(opening: Opening, input: SlotRule = rule(), logged: List<LoggedSet> = actual(opening.rows)) =
        Progression.afterSession(input, squat(), Progression.Exposure(opening.rows, logged, 1016, false, opening.layoff), ctx())
    private fun top(rows: List<LoggedSet>) = requireNotNull(WorkingRows.workingWeight(rows))
    private fun assertStreaks(out: Outcome, success: Int, rep: Int, effort: Int) {
        assertEquals(listOf(success, rep, effort), listOf(out.rule.state.successStreak, out.rule.state.repMissStreak, out.rule.state.effortMissStreak))
    }

    @Test
    fun `case 13 expected recurrence uses the rotation appearances`() {
        assertEquals(4, Progression.expectedRecurrenceDays(4, 4, 2))
        assertEquals(7, Progression.expectedRecurrenceDays(2, 2, 1))
    }

    @Test
    fun `case 13 a normal gap and three extra days run normally`() {
        for (day in listOf(1004L, 1007L)) {
            val opened = opening(day)
            assertEquals(LayoffKind.None, opened.layoff)
            assertEquals(squat(), opened.rows)
            assertNull(opened.ruleOverride)
            val out = finish(opened)
            assertEquals(Reason.Streak, out.reason)
            assertEquals(Change.Hold, out.change)
            assertStreaks(out, 1, 0, 0)
        }
    }

    @Test
    fun `case 13 layoff Hold keeps rows and all streaks`() {
        val input = rule(SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1, exposures = 4))
        val opened = opening(1009, input)
        assertEquals(LayoffKind.Hold, opened.layoff)
        assertEquals(squat(), opened.rows)
        val out = finish(opened, input)
        assertEquals(squat(), out.rows)
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.Layoff, out.reason)
        assertEquals(input.state.copy(exposures = 5), out.rule.state)
        assertStreaks(out, 2, 1, 1)
    }

    @Test
    fun `case 13 twelve extra days scale the squat to ninety five percent`() {
        val opened = opening(1016)
        assertEquals(LayoffKind.Scaled(0.95), opened.layoff)
        assertEquals(260.0, top(opened.rows), 1e-9)
        assertEquals(3, WorkingRows.workSetIndices(opened.rows).size)
        assertNull(opened.ruleOverride)
    }

    @Test
    fun `case 13 scaled success or incomplete restores pre layoff rows and all streaks`() {
        val input = rule(SlotState(successStreak = 1, repMissStreak = 1, effortMissStreak = 1, exposures = 4))
        val opened = opening(1016, input)
        for (logged in listOf(actual(opened.rows), actual(opened.rows).dropLast(1))) {
            val out = finish(opened, input, logged)
            assertEquals(275.0, top(out.rows), 1e-9)
            assertEquals(Change.Hold, out.change)
            assertEquals(Reason.Layoff, out.reason)
            assertStreaks(out, 1, 1, 1)
            assertEquals(5, out.rule.state.exposures)
        }
    }

    @Test
    fun `case 13 scaled rep miss restarts and clears all streaks`() {
        val input = rule(SlotState(successStreak = 1, repMissStreak = 1, effortMissStreak = 1))
        val opened = opening(1016, input)
        val logged = actual(opened.rows).mapIndexed { i, row -> if (i == 4) row.copy(reps = 4) else row }
        val out = finish(opened, input, logged)
        assertEquals(Change.Restart(0.95), out.change)
        assertEquals(Reason.Layoff, out.reason)
        assertEquals(260.0, top(out.rows), 1e-9)
        assertEquals(squat().size, out.rows.size)
        assertStreaks(out, 0, 0, 0)
    }

    @Test
    fun `case 13 scaled effort miss restarts with one effort miss`() {
        val input = rule(SlotState(successStreak = 1, repMissStreak = 1, effortMissStreak = 1))
        val opened = opening(1016, input)
        val logged = actual(opened.rows).map { if (it.kind == SetKind.TOP) it.copy(rir = 0) else it }
        val out = finish(opened, input, logged)
        assertEquals(Change.Restart(0.95), out.change)
        assertEquals(Reason.Layoff, out.reason)
        assertEquals(260.0, top(out.rows), 1e-9)
        assertStreaks(out, 0, 0, 1)
    }

    @Test
    fun `case 13 twenty six extra days scale to eighty five percent and keep two thirds of work sets`() {
        val opened = opening(1030)
        assertEquals(LayoffKind.Scaled(0.85), opened.layoff)
        assertEquals(235.0, top(opened.rows), 1e-9)
        assertEquals(listOf(SetKind.RAMP, SetKind.RAMP, SetKind.TOP, SetKind.BACKOFF), opened.rows.map { it.kind })
        assertEquals(2, WorkingRows.workSetIndices(opened.rows).size)
    }

    @Test
    fun `case 13 long layoff recalibrates as a view and all Easy keeps finding the load`() {
        val input = rule()
        val opened = opening(1060, input)
        assertEquals(LayoffKind.Recalibrate, opened.layoff)
        assertEquals(205.0, top(opened.rows), 1e-9)
        assertTrue(assertNotNull(opened.ruleOverride).state.calibrating)
        assertFalse(input.state.calibrating)
        val logged = actual(opened.rows, done = false).map { if (it.kind == SetKind.TOP) it.copy(done = true, rir = 4) else it }
        val out = finish(opened, input, logged)
        assertTrue(out.rule.state.calibrating)
        assertEquals(260.0, top(out.rows), 1e-9)
        assertEquals(Change.Increase(205.0, 260.0), out.change)
        assertEquals(Reason.Calibrating, out.reason)
    }

    @Test
    fun `case 13 Rough day preserves recalibration after a fifty six day gap`() {
        val input = rule(SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1, exposures = 4))
        val opened = opening(1056, input)
        assertEquals(LayoffKind.Recalibrate, opened.layoff)
        assertEquals(205.0, top(opened.rows), 1e-9)
        assertFalse(input.state.calibrating)
        val logged = actual(opened.rows, done = false).map {
            if (it.kind == SetKind.TOP) it.copy(done = true, rir = 2) else it
        }
        val out = Progression.afterSession(input, squat(),
            Progression.Exposure(opened.rows, logged, 1056, true, opened.layoff), ctx(1056))
        assertEquals(Reason.RoughDay, out.reason)
        assertEquals(Change.Hold, out.change)
        assertEquals(squat(), out.rows)
        assertEquals(input.state.copy(calibrating = true, exposures = 5), out.rule.state)
        val next = Progression.atStart(out.rule, out.rows, 1056, 4, ctx(1060))
        assertEquals(LayoffKind.None, next.layoff)
        assertTrue((next.ruleOverride ?: out.rule).state.calibrating)
    }

    @Test
    fun `case 13 light cycle preserves recalibration after a fifty six day gap`() {
        val input = rule(SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1, exposures = 4))
        val context = ctx(1056).copy(light = true)
        val opened = Progression.atStart(input, squat(), 1000, 4, context)
        assertEquals(LayoffKind.Recalibrate, opened.layoff)
        assertEquals(185.0, top(opened.rows), 1e-9)
        assertFalse(input.state.calibrating)
        val logged = actual(opened.rows, done = false).map {
            if (it.kind == SetKind.TOP) it.copy(done = true, rir = 2) else it
        }
        val out = Progression.afterSession(input, squat(),
            Progression.Exposure(opened.rows, logged, 1056, false, opened.layoff), context)
        assertEquals(Reason.LightCycle, out.reason)
        assertEquals(Change.LightCycle, out.change)
        assertEquals(squat(), out.rows)
        assertEquals(input.state.copy(calibrating = true, exposures = 5), out.rule.state)
        val next = Progression.atStart(out.rule, out.rows, 1056, 4, ctx(1060))
        assertEquals(LayoffKind.None, next.layoff)
        assertTrue((next.ruleOverride ?: out.rule).state.calibrating)
    }

    @Test
    fun `case 13 pullups after a sixty day gap keep rep progression without load calibration`() {
        val rows = List(3) { LoggedSet(0.0, 8, SetKind.WORK) }
        val context = ctx(1060).copy(equipment = emptySet())
        for (calibrating in listOf(false, true)) {
            val input = rule(SlotState(calibrating = calibrating)).copy(target = RepTarget.Fixed(8), everyExposures = 1)
            val opened = Progression.atStart(input, rows, 1000, 4, context)
            assertEquals(LayoffKind.Recalibrate, opened.layoff)
            assertEquals(rows, opened.rows)
            assertNull(opened.ruleOverride)
            val running = opened.ruleOverride ?: input
            val logged = actual(opened.rows).map { it.copy(rir = 2) }
            assertEquals(rows[1], Progression.nextSet(running, logged.take(1), rows[1], context))
            val out = Progression.afterSession(input, rows,
                Progression.Exposure(opened.rows, logged, 1060, false, opened.layoff), context)
            assertEquals(Change.RepsTo(9), out.change)
            assertEquals(Reason.AllMade, out.reason)
            assertEquals(RepTarget.Fixed(9), out.rule.target)
            assertEquals(List(3) { LoggedSet(0.0, 9, SetKind.WORK) }, out.rows)
            val next = Progression.atStart(out.rule, out.rows, 1060, 4, ctx(1064).copy(equipment = emptySet()))
            assertEquals(out.rows, next.rows)
            assertTrue(next.rows.all { it.weightLb == 0.0 })
        }
    }

    @Test
    fun `case 13 timed planks after a sixty day gap keep seconds without load calibration`() {
        val rows = List(3) { LoggedSet(0.0, 0, SetKind.WORK, seconds = 30) }
        val context = ctx(1060).copy(equipment = emptySet())
        for (calibrating in listOf(false, true)) {
            val input = rule(SlotState(calibrating = calibrating))
            val opened = Progression.atStart(input, rows, 1000, 4, context)
            assertEquals(LayoffKind.Recalibrate, opened.layoff)
            assertEquals(rows, opened.rows)
            assertNull(opened.ruleOverride)
            val running = opened.ruleOverride ?: input
            val logged = actual(opened.rows).map { it.copy(rir = 2) }
            assertEquals(rows[1], Progression.nextSet(running, logged.take(1), rows[1], context))
            val out = Progression.afterSession(input, rows,
                Progression.Exposure(opened.rows, logged, 1060, false, opened.layoff), context)
            assertEquals(Change.Hold, out.change)
            assertEquals(Reason.Timed, out.reason)
            assertEquals(rows, out.rows)
            assertEquals(input.state.copy(exposures = 1), out.rule.state)
            val next = Progression.atStart(out.rule, out.rows, 1060, 4, ctx(1064).copy(equipment = emptySet()))
            assertEquals(rows, next.rows)
        }
    }

    @Test
    fun `no last trained day opens without a layoff`() {
        val opened = Progression.atStart(rule(), squat(), null, 4, ctx(5000))
        assertEquals(LayoffKind.None, opened.layoff)
        assertEquals(squat(), opened.rows)
        assertNull(opened.ruleOverride)
    }

    @Test
    fun `layoff boundaries are measured after expected recurrence`() {
        for ((extra, expected) in listOf(3 to LayoffKind.None, 4 to LayoffKind.Hold, 7 to LayoffKind.Hold,
            8 to LayoffKind.Scaled(0.95), 14 to LayoffKind.Scaled(0.95), 15 to LayoffKind.Scaled(0.85),
            42 to LayoffKind.Scaled(0.85), 43 to LayoffKind.Recalibrate)) {
            assertEquals(expected, opening(1004L + extra).layoff)
        }
    }

    @Test
    fun `a zero load anchor is not scaled on a layoff`() {
        val rows = List(3) { LoggedSet(0.0, 8, SetKind.WORK) }
        val opened = Progression.atStart(rule(), rows, 1000, 4, ctx(1016))
        assertEquals(rows, opened.rows)
        assertEquals(LayoffKind.Scaled(0.95), opened.layoff)
    }
}
