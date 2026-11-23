package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class HoldProgressionTest {
    private fun rule(every: Int = 1, state: SlotState = SlotState()) =
        SlotRule(SlotRole.CORE, RepTarget.Fixed(10), 2, 0.0125, every, scheme = Scheme.STRENGTH, state = state)
    private fun holds(seconds: Int, n: Int = 3, lb: Double = 0.0) =
        List(n) { LoggedSet(lb, 0, SetKind.WORK, seconds = seconds) }
    private fun ctx(ceiling: Int?) = Progression.Context(
        WeightUnit.LB, emptySet(), LoadSteps.defaults(WeightUnit.LB), 130, holdCeilingSeconds = ceiling)
    private fun finish(rule: SlotRule, rows: List<LoggedSet>, held: List<Int>, ceiling: Int?,
                       ratings: List<Int?> = List(rows.size) { null }) =
        Progression.afterSession(rule, rows, Progression.Exposure(rows,
            rows.mapIndexed { i, row -> row.copy(seconds = held[i], done = true, plannedIndex = i, rir = ratings[i]) }, 130), ctx(ceiling))

    @Test fun `case 39 a plank steps five seconds a session up to its sixty second ceiling`() {
        var rows = holds(45)
        var current = rule()
        for ((exposure, seconds) in listOf(50, 55, 60).withIndex()) {
            val out = finish(current, rows, rows.map { it.seconds }, 60)
            assertEquals(Change.SecondsTo(seconds), out.change)
            assertEquals(Reason.HoldStep, out.reason)
            assertEquals(holds(seconds), out.rows)
            assertEquals(exposure + 1, out.rule.state.exposures)
            rows = out.rows
            current = out.rule
        }
        val ceiling = finish(current, rows, listOf(60, 60, 60), 60)
        assertEquals(Change.Hold, ceiling.change)
        assertEquals(Reason.HoldCeiling, ceiling.reason)
        assertEquals(holds(60), ceiling.rows)
        assertEquals(4, ceiling.rule.state.exposures)
    }

    @Test fun `case 39 a short or missing hold keeps the target`() {
        val input = rule(state = SlotState(successStreak = 1, repMissStreak = 2, effortMissStreak = 1, pendingIncrease = true))
        val rows = holds(45)
        val short = finish(input, rows, listOf(45, 40, 45), 60)
        val missing = Progression.afterSession(input, rows, Progression.Exposure(rows,
            rows.mapIndexed { i, row -> row.copy(done = i < 2, plannedIndex = i) }, 130), ctx(60))
        assertEquals(Reason.RepMiss, short.reason)
        assertEquals(Reason.Incomplete, missing.reason)
        for (out in listOf(short, missing)) {
            assertEquals(Change.Hold, out.change)
            assertEquals(holds(45), out.rows)
            assertEquals(0, out.rule.state.successStreak)
            assertEquals(false, out.rule.state.pendingIncrease)
            assertEquals(input.state.repMissStreak, out.rule.state.repMissStreak)
            assertEquals(input.state.effortMissStreak, out.rule.state.effortMissStreak)
        }
    }

    @Test fun `case 39 cadence two steps on the second met session`() {
        val first = finish(rule(every = 2), holds(45), listOf(45, 45, 45), 60)
        assertEquals(Reason.Streak, first.reason)
        assertEquals(Change.Hold, first.change)
        assertEquals(holds(45), first.rows)
        assertEquals(1, first.rule.state.successStreak)
        val second = finish(first.rule, first.rows, listOf(45, 45, 45), 60)
        assertEquals(Change.SecondsTo(50), second.change)
        assertEquals(Reason.HoldStep, second.reason)
    }

    @Test fun `case 39 loaded holds and holds without a ceiling keep the Timed result`() {
        val input = rule(state = SlotState(successStreak = 1, repMissStreak = 2, effortMissStreak = 1))
        for ((rows, ceiling) in listOf(holds(40, lb = 50.0) to 60, holds(45) to null)) {
            val out = finish(input, rows, rows.map { it.seconds }, ceiling)
            assertEquals(Reason.Timed, out.reason)
            assertEquals(Change.Hold, out.change)
            assertEquals(rows, out.rows)
            assertEquals(input.state.copy(exposures = 1), out.rule.state)
        }
    }

    @Test fun `case 39 a scaled layoff opening holds a met hold`() {
        val rows = holds(45)
        val out = Progression.afterSession(rule(), rows, Progression.Exposure(rows,
            rows.mapIndexed { i, row -> row.copy(done = true, plannedIndex = i) }, 130,
            layoff = LayoffKind.Scaled(0.95)), ctx(60))
        assertEquals(Reason.Layoff, out.reason)
        assertEquals(Change.Hold, out.change)
        assertEquals(holds(45), out.rows)
    }

    @Test fun `case 40 an overshoot counts as one ordinary success`() {
        val first = finish(rule(), holds(45), listOf(107, 45, 45), 60)
        assertEquals(Change.SecondsTo(50), first.change)
        assertEquals(Reason.HoldStep, first.reason)
        assertEquals(holds(50), first.rows)
        val cadence = finish(rule(every = 2), holds(45), listOf(107, 45, 45), 60)
        assertEquals(Reason.Streak, cadence.reason)
        assertEquals(Change.Hold, cadence.change)
        assertEquals(holds(45), cadence.rows)
        assertEquals(1, cadence.rule.state.successStreak)
    }

    @Test fun `case 39 all Easy holds double the step within the ceiling`() {
        for ((target, next) in listOf(45 to 55, 55 to 60, 60 to 60)) {
            val out = finish(rule(), holds(target), List(3) { target }, 60, List(3) { Effort.EASY.rir })
            assertEquals(holds(next), out.rows)
            assertEquals(if (target == 60) Change.Hold else Change.SecondsTo(next), out.change)
            assertEquals(if (target == 60) Reason.HoldCeiling else Reason.HoldStep, out.reason)
        }
    }

    @Test fun `case 39 a non Easy or unrated hold earns only five seconds`() {
        for (rating in listOf(Effort.GOOD.rir, Effort.HARD.rir, Effort.MAX.rir, null)) {
            val out = finish(rule(), holds(45), listOf(107, 45, 45), 60,
                listOf(Effort.EASY.rir, rating, Effort.EASY.rir))
            assertEquals(Change.SecondsTo(50), out.change)
            assertEquals(Reason.HoldStep, out.reason)
            assertEquals(holds(50), out.rows)
        }
    }

    @Test fun `case 39 Easy holds still require every target and the cadence`() {
        val rows = holds(45)
        val easy = List(3) { Effort.EASY.rir }
        val short = finish(rule(), rows, listOf(45, 44, 45), 60, easy)
        assertEquals(Reason.RepMiss, short.reason)
        assertEquals(rows, short.rows)
        val missing = Progression.afterSession(rule(), rows, Progression.Exposure(rows,
            rows.take(2).mapIndexed { i, row -> row.copy(done = true, plannedIndex = i, rir = Effort.EASY.rir) }, 130), ctx(60))
        assertEquals(Reason.Incomplete, missing.reason)
        assertEquals(rows, missing.rows)
        val first = finish(rule(every = 2), rows, listOf(45, 45, 45), 60, easy)
        assertEquals(Reason.Streak, first.reason)
        assertEquals(rows, first.rows)
        val second = finish(first.rule, rows, listOf(45, 45, 45), 60, easy)
        assertEquals(Change.SecondsTo(55), second.change)
        assertEquals(Reason.HoldStep, second.reason)
        val unrated = finish(first.rule, rows, listOf(45, 45, 45), 60)
        assertEquals(Change.SecondsTo(50), unrated.change)
    }

    @Test fun `case 39 a target above the ceiling keeps neutral Timed status`() {
        val rows = holds(90)
        val out = finish(rule(), rows, listOf(90, 90, 90), 60, List(3) { Effort.EASY.rir })
        assertEquals(Reason.Timed, out.reason)
        assertEquals(Change.Hold, out.change)
        assertEquals(rows, out.rows)
    }
}
