package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgressionTest {
    private val bar = setOf(Equipment.BARBELL, Equipment.BENCH, Equipment.RACK)
    private val db = setOf(Equipment.DUMBBELL, Equipment.BENCH)
    private fun ctx(equipment: Set<Equipment> = bar) =
        Progression.Context(WeightUnit.LB, equipment, LoadSteps.defaults(WeightUnit.LB), 130)
    private fun rule(reps: Int = 5, rir: Int = 3, pct: Double = 0.025, cadence: Int = 1,
                     target: RepTarget = RepTarget.Fixed(reps), state: SlotState = SlotState()) =
        SlotRule(SlotRole.MAIN, target, rir, pct, cadence, scheme = Scheme.STRENGTH, state = state)
    private fun rRule(bench: Boolean = false) = rule(rir = 2, pct = if (bench) 0.0125 else 0.025, cadence = 2)
    private fun work(weight: Double = 185.0, reps: Int = 5, count: Int = 3) =
        SetSeeder.shape(SetStructure.STRAIGHT, weight, reps, count)
    private fun ramp(bench: Boolean = false) = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF,
        if (bench) 185.0 else 275.0, 5, 1, WeightUnit.LB, bar, Scheme.STRENGTH)
    private fun actual(planned: List<LoggedSet>, done: Boolean = true, reps: List<Int>? = null, rir: Int? = null): List<LoggedSet> {
        var seen = 0
        return planned.mapIndexed { i, row ->
            if (row.kind == SetKind.RAMP) row.copy(plannedIndex = i, done = done)
            else row.copy(plannedIndex = i, done = done, reps = reps?.get(seen++) ?: row.reps, rir = rir)
        }
    }
    private fun finish(rule: SlotRule = rule(), working: List<LoggedSet> = work(), actual: List<LoggedSet> = actual(working),
                       planned: List<LoggedSet> = working, layoff: LayoffKind = LayoffKind.None,
                       context: Progression.Context = ctx(), day: Long = 130) =
        Progression.afterSession(rule, working, Progression.Exposure(planned, actual, day, layoff), context)
    private fun top(out: Outcome) = requireNotNull(WorkingRows.workingWeight(out.rows))
    private fun assertStreaks(out: Outcome, success: Int, rep: Int, effort: Int) {
        assertEquals(listOf(success, rep, effort), listOf(out.rule.state.successStreak, out.rule.state.repMissStreak, out.rule.state.effortMissStreak))
    }
    private fun assertCleared(rows: List<LoggedSet>) = assertTrue(rows.all {
        !it.done && it.startedAtMillis == null && it.completedAtMillis == null && it.plannedIndex == null && it.watchEditedAt == null
    })

    @Test
    fun `case 5 R's squat and bench increase after two successful exposures`() {
        for (bench in listOf(false, true)) {
            val rows = ramp(bench)
            val first = finish(rRule(bench), rows, actual(rows, rir = 2))
            assertEquals(Change.Hold, first.change)
            assertEquals(Reason.Streak, first.reason)
            assertStreaks(first, 1, 0, 0)
            val second = finish(first.rule, first.rows, actual(first.rows, rir = 2))
            assertEquals(Change.Increase(if (bench) 185.0 else 275.0, if (bench) 190.0 else 280.0), second.change)
            assertEquals(if (bench) 190.0 else 280.0, top(second), 1e-9)
        }
    }

    @Test
    fun `case 6 New squat and bench advance by at least one step`() {
        val squat = finish(rule(pct = 0.05), work(95.0), actual(work(95.0), rir = 2))
        assertEquals(Change.Increase(95.0, 100.0), squat.change)
        val bench = finish(working = work(65.0), actual = actual(work(65.0), rir = 2))
        assertEquals(Change.Increase(65.0, 70.0), bench.change)
    }

    @Test
    fun `case 7 Easy doubles the increase within the ten percent cap`() {
        val squat = finish(rRule().copy(everyExposures = 1), ramp(), actual(ramp(), rir = 4))
        assertEquals(Change.Increase(275.0, 285.0), squat.change)
        assertEquals(Reason.EasySession, squat.reason)
        // audit 2026-11-13: a double that would spend the target's reserve falls back to the single step
        val light = finish(rule(pct = 0.05), work(95.0), actual(work(95.0), rir = 4))
        assertEquals(Change.Increase(95.0, 100.0), light.change)
        assertEquals(Reason.AllMade, light.reason)
    }

    @Test
    fun `New squat Easy sessions use a single increment when the double spends target reserve`() {
        for (weight in listOf(95.0, 100.0)) {
            val rows = work(weight)
            val out = finish(rule(pct = 0.05), rows, actual(rows, rir = 4))
            assertEquals(Change.Increase(weight, weight + 5.0), out.change)
            assertEquals(Reason.AllMade, out.reason)
        }
    }

    @Test
    fun `Easy session without a trusted estimate uses a single increment`() {
        val rows = work(100.0, 10)
        val out = finish(rule(10, 2, pct = 0.05), rows, actual(rows, rir = 4))
        assertEquals(Change.Increase(100.0, 105.0), out.change)
        assertEquals(Reason.AllMade, out.reason)
    }

    @Test
    fun `case 8 a first rep miss holds and a second lowers the New bench`() {
        val first = finish(actual = actual(work(), reps = listOf(5, 5, 3)))
        assertEquals(Change.Hold, first.change)
        assertEquals(Reason.RepMiss, first.reason)
        assertStreaks(first, 0, 1, 0)
        val second = finish(first.rule, first.rows, actual(first.rows, reps = listOf(5, 4, 4)))
        assertEquals(Change.Decrease(185.0, 175.0), second.change)
        assertEquals(Reason.Stall, second.reason)
        assertStreaks(second, 0, 0, 0)
        assertEquals(listOf(130L), second.rule.state.resetEpochDays)
        assertEquals(work(175.0), second.rows)
    }

    @Test
    fun `case 10 lowering straight bench becomes the next working load and clears streaks`() {
        val input = rule(state = SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1))
        val out = finish(input, actual = actual(work()).map { it.copy(weightLb = 175.0, rir = 3) })
        assertEquals(Change.KeptLowered(175.0), out.change)
        assertEquals(Reason.Lowered, out.reason)
        assertEquals(work(175.0), out.rows)
        assertStreaks(out, 0, 0, 0)
        assertEquals(Change.Increase(175.0, 180.0), finish(out.rule, out.rows).change)
    }

    @Test
    fun `case 10 lowering R's bench cascades and preserves its two exposure cadence`() {
        val planned = ramp(true)
        val logged = actual(planned).map { when (it.kind) {
            SetKind.TOP -> it.copy(weightLb = 175.0)
            SetKind.BACKOFF -> it.copy(weightLb = 160.0)
            else -> it
        } }
        val out = finish(rRule(true), planned, logged)
        assertEquals(Change.KeptLowered(175.0), out.change)
        assertEquals(listOf(105.0, 140.0, 175.0, 160.0, 160.0), out.rows.map { it.weightLb })
        val first = finish(out.rule, out.rows)
        assertEquals(Change.Hold, first.change)
        val second = finish(first.rule, first.rows)
        assertEquals(Change.Increase(175.0, 180.0), second.change)
    }

    @Test
    fun `case 11 Range uses the lowest anchor reps then increases load at its ceiling`() {
        val input = rule(8, 2, 0.0125, target = RepTarget.Range(8, 12, 8))
        val rows = work(60.0, 8)
        val out = finish(input, rows, actual(rows, reps = listOf(10, 9, 8)), context = ctx(db))
        assertEquals(Change.RepsTo(9), out.change)
        assertEquals(RepTarget.Range(8, 12, 9), out.rule.target)
        assertEquals(work(60.0, 9), out.rows)
        val ceiling = work(60.0, 12)
        val loaded = finish(input.copy(target = RepTarget.Range(8, 12, 12)), ceiling, context = ctx(db))
        assertEquals(Change.Increase(60.0, 65.0), loaded.change)
        assertEquals(RepTarget.Range(8, 12, 8), loaded.rule.target)
        assertEquals(work(65.0, 8), loaded.rows)
    }

    @Test
    fun `case 12 next set calibration climbs until the first non Easy anchor`() {
        // audit 2026-11-13: Easy is read as 4 left
        val input = rule(rir = 2, state = SlotState(calibrating = true))
        val rows = work(45.0, count = 4)
        fun logged(weight: Double, rir: Int) = LoggedSet(weight, 5, SetKind.WORK, done = true, rir = rir)
        val first = listOf(logged(45.0, 4))
        assertEquals(50.0, Progression.nextSet(input, first, rows[1], ctx()).weightLb, 1e-9)
        val second = first + logged(50.0, 4)
        assertEquals(55.0, Progression.nextSet(input, second, rows[2], ctx()).weightLb, 1e-9)
        val third = second + logged(55.0, 4)
        assertEquals(60.0, Progression.nextSet(input, third, rows[3], ctx()).weightLb, 1e-9)
        assertEquals(60.0, Progression.nextSet(input, third + logged(60.0, 2), rows[3], ctx()).weightLb, 1e-9)
        assertEquals(55.0, Progression.nextSet(input, third + logged(60.0, 0), rows[3], ctx()).weightLb, 1e-9)
        val completed = (third + logged(60.0, 2)).mapIndexed { i, row -> row.copy(plannedIndex = i) }
        val out = finish(input, rows, completed)
        assertEquals(Change.Calibrated(60.0), out.change)
        assertEquals(Reason.Calibrated, out.reason)
        assertEquals(work(60.0, count = 4), out.rows)
        assertFalse(out.rule.state.calibrating)
        assertStreaks(out, 0, 0, 0)
    }

    @Test
    fun `Easy calibration at ten pounds adds one dumbbell step`() {
        val input = rule(10, 2, state = SlotState(calibrating = true))
        val rows = work(10.0, 10)
        val logged = actual(rows.take(1), rir = 4)
        assertEquals(15.0, Progression.nextSet(input, logged, rows[1], ctx(db)).weightLb, 1e-9)
    }

    @Test
    fun `Easy calibration beyond the estimate rep limit adds one step`() {
        val input = rule(12, 2, state = SlotState(calibrating = true))
        val rows = work(45.0, 12)
        val logged = actual(rows.take(1), rir = 4)
        assertEquals(50.0, Progression.nextSet(input, logged, rows[1], ctx()).weightLb, 1e-9)
    }

    @Test
    fun `Easy calibration rounds the bound down and reads Easy as exactly four left`() {
        val input = rule(rir = 2, state = SlotState(calibrating = true))
        val rows = work(100.0)
        for (rir in listOf(4, 5)) {
            val logged = actual(rows.take(1), rir = rir)
            assertEquals(105.0, Progression.nextSet(input, logged, rows[1], ctx()).weightLb, 1e-9)
        }
    }

    @Test
    fun `case 26 coarse dumbbells normalize to a Range and take one step at its ceiling`() {
        val input = rule(12, 2, 0.0125)
        val rows = work(10.0, 12)
        val out = finish(input, rows, context = ctx(db))
        assertEquals(RepTarget.Range(12, 16, 13), out.rule.target)
        assertEquals(Change.RepsTo(13), out.change)
        assertEquals(work(10.0, 13), out.rows)
        val loaded = finish(input.copy(target = RepTarget.Range(12, 16, 16)), work(10.0, 16), context = ctx(db))
        assertEquals(Change.Increase(10.0, 15.0), loaded.change)
        assertEquals(RepTarget.Range(12, 16, 12), loaded.rule.target)
        assertEquals(work(15.0, 12), loaded.rows)
    }

    @Test
    fun `case 26 pullup adds reps through twenty then offers weight`() {
        for ((from, to) in listOf(8 to 9, 19 to 20, 20 to 20)) {
            val out = finish(rule(from, 2), work(0.0, from), context = ctx(emptySet()))
            assertEquals(if (from == 20) Change.Hold else Change.RepsTo(to), out.change)
            assertEquals(if (to == 20) Reason.AddWeight else Reason.AllMade, out.reason)
            assertEquals(RepTarget.Fixed(to), out.rule.target)
            assertEquals(work(0.0, to), out.rows)
        }
    }

    @Test
    fun `case 32 two effort misses slow cadence and never lower the New bench`() {
        val logged = actual(work()).mapIndexed { i, row -> if (i == 1) row.copy(rir = 1) else row }
        val first = finish(actual = logged)
        assertEquals(Change.Hold, first.change)
        assertEquals(Reason.EffortMiss, first.reason)
        assertStreaks(first, 0, 0, 1)
        val second = finish(first.rule, first.rows, logged)
        assertEquals(185.0, top(second), 1e-9)
        assertEquals(2, second.rule.everyExposures)
        assertStreaks(second, 0, 0, 0)
    }

    @Test
    fun `case 32 two Max sessions slow R's bench cadence to three`() {
        val rows = ramp(true)
        val logged = actual(rows).map { if (it.kind == SetKind.TOP) it.copy(rir = 0) else it }
        val first = finish(rRule(true), rows, logged)
        val second = finish(first.rule, first.rows, logged)
        assertEquals(185.0, top(second), 1e-9)
        assertEquals(3, second.rule.everyExposures)
        assertEquals(Reason.EffortMiss, second.reason)
        assertStreaks(second, 0, 0, 0)
    }

    @Test
    fun `case 32 two rep misses still lower the load from a fresh rule`() {
        val first = finish(actual = actual(work(), reps = listOf(5, 5, 3)))
        val second = finish(first.rule, first.rows, actual(first.rows, reps = listOf(5, 4, 4)))
        assertEquals(Change.Decrease(185.0, 175.0), second.change)
    }

    @Test
    fun `case 33 high rep calibration anchors directly and lowers a missed target`() {
        val input = rule(10, 2, state = SlotState(calibrating = true))
        val rows = work(40.0, 10)
        val made = actual(rows.take(1), rir = 2)
        assertEquals(40.0, Progression.nextSet(input, made, rows[1], ctx(db)).weightLb, 1e-9)
        val missed = made.map { it.copy(reps = 9) }
        assertEquals(35.0, Progression.nextSet(input, missed, rows[1], ctx(db)).weightLb, 1e-9)
        val out = finish(input, rows, missed, context = ctx(db))
        assertEquals(Change.Calibrated(35.0), out.change)
        assertEquals(work(35.0, 10), out.rows)
    }

    @Test
    fun `case 38 curl reps reach the range ceiling before load increases`() {
        val input = SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 8, MovementPattern.BICEPS)
        val rows = work(30.0, 8)
        val first = finish(input, rows, actual(rows, reps = listOf(12, 12, 12)), context = ctx(db))
        assertEquals(RepTarget.Range(8, 12, 12), first.rule.target)
        assertEquals(Change.RepsTo(12), first.change)
        assertEquals(30.0, top(first), 1e-9)
        val second = finish(first.rule, first.rows, context = ctx(db))
        assertEquals(Change.Increase(30.0, 35.0), second.change)
        assertEquals(RepTarget.Range(8, 12, 8), second.rule.target)
    }

    @Test
    fun `Max blocks a load increase after success but permits a range rep increase`() {
        val input = rule(10, 1)
        val rows = work(100.0, 10)
        val logged = actual(rows).mapIndexed { i, row -> if (i == 1) row.copy(rir = 0) else row }
        val fixed = finish(input, rows, logged)
        assertEquals(Change.Hold, fixed.change)
        assertEquals(Reason.MaxSet, fixed.reason)
        assertStreaks(fixed, 1, 0, 0)
        val rangeRows = work(100.0, 9)
        val range = finish(input.copy(target = RepTarget.Range(8, 12, 9)), rangeRows, logged)
        assertEquals(Change.RepsTo(11), range.change)
        assertEquals(RepTarget.Range(8, 12, 11), range.rule.target)
    }

    @Test
    fun `second stall within forty two days slows cadence and records the last two days`() {
        for ((day, cadence) in listOf(130L to 2, 150L to 1)) {
            val input = rule(state = SlotState(repMissStreak = 1, resetEpochDays = listOf(100)))
            val out = finish(input, actual = actual(work(), reps = listOf(5, 5, 3)), day = day)
            assertEquals(Change.Decrease(185.0, 175.0), out.change)
            assertEquals(cadence, out.rule.everyExposures)
            assertEquals(listOf(100L, day), out.rule.state.resetEpochDays)
            assertStreaks(out, 0, 0, 0)
        }
        val out = finish(rule(state = SlotState(repMissStreak = 1, resetEpochDays = listOf(50, 100))),
            actual = actual(work(), reps = listOf(5, 5, 3)))
        assertEquals(listOf(100L, 130L), out.rule.state.resetEpochDays)
    }

    @Test
    fun `zero drop holds a stall load while resetting streaks`() {
        val out = finish(rule(state = SlotState(repMissStreak = 1)).copy(dropPct = 0.0),
            actual = actual(work(), reps = listOf(5, 5, 3)))
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.Stall, out.reason)
        assertEquals(185.0, top(out), 1e-9)
        assertStreaks(out, 0, 0, 0)
    }

    @Test
    fun `cadence steps back after four successes without passing the plan floor`() {
        val input = rRule().copy(state = SlotState(successStreak = 3))
        val faster = finish(input, ramp(), context = ctx().copy(baseCadence = 1))
        assertIs<Change.Increase>(faster.change)
        assertEquals(1, faster.rule.everyExposures)
        assertStreaks(faster, 0, 0, 0)
        val held = finish(input, ramp(), context = ctx().copy(baseCadence = 2))
        assertIs<Change.Increase>(held.change)
        assertEquals(2, held.rule.everyExposures)
        assertStreaks(held, 4, 0, 0)
    }

    @Test
    fun `cadence three steps back to two after exactly four consecutive successes`() {
        val context = ctx().copy(baseCadence = 1)
        var input = rule(cadence = 3)
        var rows = work()
        repeat(3) { index ->
            val out = finish(input, rows, context = context)
            assertEquals(3, out.rule.everyExposures)
            assertStreaks(out, index + 1, 0, 0)
            input = out.rule
            rows = out.rows
        }
        val fourth = finish(input, rows, context = context)
        assertEquals(2, fourth.rule.everyExposures)
        assertStreaks(fourth, 0, 0, 0)
        assertEquals(Change.Hold, fourth.change)
        assertEquals(Reason.Streak, fourth.reason)
        assertEquals(rows, fourth.rows)
    }

    @Test
    fun `cadence never passes three after a second effort miss`() {
        val out = finish(rule(cadence = 3, state = SlotState(effortMissStreak = 1)), actual = actual(work(), rir = 1))
        assertEquals(3, out.rule.everyExposures)
        assertStreaks(out, 0, 0, 0)
    }

    @Test
    fun `incomplete clears success and keeps both miss streaks`() {
        val input = rule(state = SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1))
        val out = finish(input, actual = actual(work()).take(2))
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.Incomplete, out.reason)
        assertStreaks(out, 0, 1, 1)
        assertEquals(1, out.rule.state.exposures)
    }

    @Test
    fun `no sets preserves all state including exposures`() {
        val input = rule(state = SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1, exposures = 9))
        val out = finish(input, actual = actual(work(), done = false))
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.NoSets, out.reason)
        assertEquals(input.state, out.rule.state)
        assertStreaks(out, 2, 1, 1)
    }

    @Test
    fun `timed holds have no progression and keep streaks`() {
        val input = rule(state = SlotState(successStreak = 2, repMissStreak = 1, effortMissStreak = 1))
        val rows = work(0.0, 0).map { it.copy(seconds = 30) }
        val out = finish(input, rows)
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.Timed, out.reason)
        assertStreaks(out, 2, 1, 1)
    }

    @Test
    fun `heavier than planned logs are judged normally and increase from the planned load because the design has no heavier rule`() {
        val logged = actual(work()).mapIndexed { i, row -> if (i == 0) row.copy(weightLb = 195.0) else row }
        val out = finish(actual = logged)
        assertEquals(Change.Increase(185.0, 190.0), out.change)
        assertEquals(work(190.0), out.rows)
    }

    @Test
    fun `kg progression adds a two point five kg bar step`() {
        val kg = WeightUnit.KG
        val rows = work(kg.toLb(100.0))
        val context = Progression.Context(kg, setOf(Equipment.BARBELL), LoadSteps.defaults(kg), 130)
        val out = finish(working = rows, context = context)
        assertEquals(102.5, kg.fromLb(top(out)), 1e-6)
    }

    @Test
    fun `Hold regenerates a manually edited ramp from the completed TOP`() {
        val rows = ramp()
        val logged = actual(rows).mapIndexed { i, row -> if (i == 0) row.copy(weightLb = 140.0) else row }
        val out = finish(rRule(), rows, logged)
        assertEquals(Change.Hold, out.change)
        assertEquals(rows[0].weightLb, out.rows[0].weightLb, 1e-9)
        assertCleared(out.rows)
    }

    @Test
    fun `a lowered backoff blocks success and regenerates from TOP`() {
        val rows = ramp()
        val logged = actual(rows).mapIndexed { i, row -> if (i == 3) row.copy(weightLb = 140.0) else row }
        val out = finish(rRule(), rows, logged)
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.RepMiss, out.reason)
        assertEquals(rows[3].weightLb, out.rows[3].weightLb, 1e-9)
        assertEquals(1, out.rule.state.repMissStreak)
    }

    @Test
    fun `an EXTRA added in the run never carries and all outcome rows are cleared`() {
        val rows = ramp()
        val logged = SetEditor.addSet(actual(rows)).map { it.copy(startedAtMillis = 100, completedAtMillis = 200, watchEditedAt = 300) }
        val out = finish(rRule(), rows, logged)
        assertEquals(rows.size, out.rows.size)
        assertEquals(rows, out.rows)
        assertCleared(out.rows)
    }

    @Test
    fun `success increments success streak and clears both miss streaks`() {
        val out = finish(rule(cadence = 3, state = SlotState(successStreak = 1, repMissStreak = 1, effortMissStreak = 1)))
        assertEquals(Reason.Streak, out.reason)
        assertStreaks(out, 2, 0, 0)
        assertEquals(1, out.rule.state.exposures)
    }

    @Test
    fun `first rep miss clears success and effort streaks`() {
        val out = finish(rule(state = SlotState(successStreak = 2, effortMissStreak = 1)),
            actual = actual(work(), reps = listOf(5, 5, 3)))
        assertStreaks(out, 0, 1, 0)
    }

    @Test
    fun `first effort miss clears success and rep miss streaks`() {
        val out = finish(rule(state = SlotState(successStreak = 2, repMissStreak = 1)), actual = actual(work(), rir = 1))
        assertStreaks(out, 0, 0, 1)
    }

    @Test
    fun `all Easy calibration clears streaks and stays calibrating`() {
        val input = rule(rir = 2, state = SlotState(calibrating = true, successStreak = 2, repMissStreak = 1, effortMissStreak = 1))
        val rows = work(45.0)
        val out = finish(input, rows, actual(rows, rir = 4))
        // audit 2026-11-13: Easy is read as 4 left, so the calibration step is one plate step
        assertEquals(Change.Increase(45.0, 50.0), out.change)
        assertEquals(Reason.Calibrating, out.reason)
        assertTrue(out.rule.state.calibrating)
        assertStreaks(out, 0, 0, 0)
    }

    @Test
    fun `calibration without a done TOP or WORK clears streaks and holds`() {
        val input = rRule().copy(state = SlotState(calibrating = true, successStreak = 2, repMissStreak = 1, effortMissStreak = 1))
        val rows = ramp()
        val logged = actual(rows, done = false).map { if (it.kind == SetKind.BACKOFF) it.copy(done = true) else it }
        val out = finish(input, rows, logged)
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.Calibrating, out.reason)
        assertEquals(rows, out.rows)
        assertTrue(out.rule.state.calibrating)
        assertStreaks(out, 0, 0, 0)
    }

    @Test
    fun `concluded calibration uses planned order and clears all streaks`() {
        val input = rule(rir = 2, state = SlotState(calibrating = true, successStreak = 2, repMissStreak = 1, effortMissStreak = 1))
        val rows = work(75.0)
        val logged = actual(rows, rir = 2).mapIndexed { i, row -> if (i == 2) row.copy(weightLb = 100.0) else row }.reversed()
        val out = finish(input, rows, logged)
        assertEquals(Change.Calibrated(75.0), out.change)
        assertFalse(out.rule.state.calibrating)
        assertStreaks(out, 0, 0, 0)
    }

    @Test
    fun `nextSet leaves noncalibrating ramp and unanchored pending sets unchanged`() {
        val pending = work(45.0).first()
        assertEquals(pending, Progression.nextSet(rule(), actual(work()), pending, ctx()))
        val calibrating = rule(state = SlotState(calibrating = true))
        assertEquals(pending, Progression.nextSet(calibrating, emptyList(), pending, ctx()))
        val ramp = pending.copy(kind = SetKind.RAMP)
        assertEquals(ramp, Progression.nextSet(calibrating, actual(work()), ramp, ctx()))
    }

    @Test
    fun `nextSet derives pending backoff from the calibrated load and scheme`() {
        val input = rRule().copy(state = SlotState(calibrating = true))
        val anchor = LoggedSet(75.0, 5, SetKind.TOP, done = true, rir = 2)
        val pending = LoggedSet(45.0, 5, SetKind.BACKOFF)
        assertEquals(70.0, Progression.nextSet(input, listOf(anchor), pending, ctx()).weightLb, 1e-9)
    }

    @Test
    fun `coarse normalization survives an incomplete exposure`() {
        val rows = work(10.0, 12)
        val out = finish(rule(12, 2), rows, actual(rows).take(2), context = ctx(db))
        assertEquals(RepTarget.Range(12, 16, 12), out.rule.target)
        assertEquals(Reason.Incomplete, out.reason)
    }

    @Test
    fun `bodyweight and coarse stalls lower reps with a floor of three`() {
        for ((weight, target) in listOf(0.0 to RepTarget.Fixed(3), 10.0 to RepTarget.Range(8, 12, 8))) {
            val rows = work(weight, target.reps)
            val input = rule(target.reps, 2, target = target, state = SlotState(repMissStreak = 1))
            val out = finish(input, rows, actual(rows, reps = List(3) { target.reps - 1 }), context = ctx(db))
            val reps = maxOf(3, target.reps - 1)
            assertEquals(Change.RepsTo(reps), out.change)
            assertEquals(weight, top(out), 1e-9)
            assertEquals(if (target is RepTarget.Range) RepTarget.Range(7, 12, 7) else RepTarget.Fixed(3), out.rule.target)
        }
    }

    @Test
    fun `a backoff rep miss is judged even when the TOP made its reps`() {
        val rows = ramp()
        val out = finish(rRule(), rows, actual(rows, reps = listOf(5, 5, 4)))
        assertEquals(Reason.RepMiss, out.reason)
        assertStreaks(out, 0, 1, 0)
    }

    @Test
    fun `Easy increase requires every work set including backoffs to be Easy`() {
        val rows = ramp()
        val logged = actual(rows, rir = 4).mapIndexed { i, row -> if (i == 4) row.copy(rir = 2) else row }
        val out = finish(rRule().copy(everyExposures = 1), rows, logged)
        assertEquals(Change.Increase(275.0, 280.0), out.change)
        assertEquals(Reason.AllMade, out.reason)
    }

    @Test
    fun `a lower minority blocks success at the majority load`() {
        val logged = actual(work()).mapIndexed { i, row -> row.copy(weightLb = if (i == 0) 175.0 else 195.0) }
        val out = finish(actual = logged)
        assertEquals(Change.Hold, out.change)
        assertEquals(Reason.RepMiss, out.reason)
    }
}
