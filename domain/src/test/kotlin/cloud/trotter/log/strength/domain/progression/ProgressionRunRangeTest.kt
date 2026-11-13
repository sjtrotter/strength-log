package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProgressionRunRangeTest {
    private val ctx = Progression.CardioContext(1000, ProgressMeasure.DISTANCE, 5)
    private val roomy = CardioHistory(longest = RecentMaxima(null, 10_000))
    private fun time(seconds: Int, hard: Boolean = false, role: CardioRole = CardioRole.WORK) =
        CardioSetSpec(role, Measure.Time(seconds), hard = hard)
    private fun distance(metres: Int, aim: Int? = null, hard: Boolean = false) =
        CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), aim?.let { Measure.Time(it) }, hard)
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())
    private fun intervals(rounds: Int, rep: CardioSetSpec, recovery: Int) = CardioBlock(CardioActivity.ROAD_RUN, buildList {
        repeat(rounds) { index ->
            if (index > 0) add(time(recovery, role = CardioRole.RECOVERY))
            add(rep)
        }
    })
    private fun range(session: RunSession) = requireNotNull(RunRange.of(session))
    private fun result(spec: CardioSetSpec, status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
        spec, status, ((spec.end as? Measure.Time) ?: (spec.aim as? Measure.Time))?.seconds ?: 0,
        (spec.end as? Measure.Distance)?.metres,
        if (spec.end is Measure.Distance) DistanceSource.MANUAL else null,
    )
    private fun finished(block: CardioBlock) = block.sets.map { result(it) }
    private fun finish(block: CardioBlock, rule: CardioRule, effort: CardioEffort? = CardioEffort.HARD,
                       history: CardioHistory = roomy, context: Progression.CardioContext = ctx) =
        Progression.cardioAfterSession(block, rule, finished(block), effort, history, context)

    @Test fun `range table builds exact low and high blocks and fills rounds first`() {
        for (session in listOf(RunSession.LADDER, RunSession.EASY, RunSession.LONG)) assertNull(RunRange.of(session))
        val sessions = listOf(RunSession.THRESHOLD, RunSession.TEMPO, RunSession.VO2MAX,
            RunSession.RACE_PACE, RunSession.SHARPENING, RunSession.TEST_INTERVALS)
        assertEquals(listOf(10, 10, 2, 2, 3, 6), sessions.map { range(it).top })
        val low = listOf(block(time(1200, true)), block(time(900, true)), intervals(5, time(180, true), 180),
            intervals(6, distance(400, hard = true), 120), intervals(3, distance(300, hard = true), 240),
            intervals(4, distance(400, hard = true), 120))
        val high = listOf(block(time(1800, true)), block(time(1500, true)), intervals(6, time(240, true), 240),
            intervals(8, distance(400, hard = true), 120), intervals(4, distance(500, hard = true), 240),
            intervals(6, distance(800, hard = true), 120))
        for ((index, session) in sessions.withIndex()) {
            val range = range(session)
            assertEquals(low[index], range.block(0, CardioActivity.ROAD_RUN), session.name)
            assertEquals(high[index], range.block(range.top, CardioActivity.ROAD_RUN), session.name)
            assertFailsWith<IllegalArgumentException> { range.block(range.top + 1, CardioActivity.ROAD_RUN) }
        }
        assertEquals(intervals(6, time(180, true), 180), range(RunSession.VO2MAX).block(1, CardioActivity.ROAD_RUN))
    }

    @Test fun `case 29 eight hundred metres steps every two finished hard sessions and scales aims`() {
        var rule = CardioRule(null, RunSession.SHARPENING)
        var opening = intervals(3, distance(300, 45, true), 240)
        for (session in 1..8) {
            val next = finish(opening, rule)
            val steps = minOf(session / 2, 3)
            val rep = when (steps) { 0, 1 -> 300; 2 -> 400; else -> 500 }
            val aim = when (steps) { 0, 1 -> 45; 2 -> 60; else -> 75 }
            assertEquals(intervals(if (steps == 0) 3 else 4, distance(rep, aim, true), 240), next.block)
            assertEquals(if (session in listOf(2, 4, 6)) CardioChange.Step else CardioChange.Repeat, next.change)
            assertEquals(CardioRule(null, RunSession.SHARPENING, steps, if (session < 6) session % 2 else 0), next.rule)
            val shortened = finished(opening).mapIndexed { index, row ->
                if (index == 0) row.copy(status = CardioSetStatus.SHORTENED) else row
            }
            assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat),
                Progression.cardioAfterSession(opening, rule, shortened, CardioEffort.HARD, roomy, ctx))
            rule = assertNotNull(next.rule)
            opening = next.block
        }
    }

    @Test fun `fifteen hundred metres fills eight race pace rounds and stops`() {
        var rule = CardioRule(null, RunSession.RACE_PACE)
        var opening = intervals(6, distance(400, 72, true), 120)
        val efforts = listOf(CardioEffort.EASY, CardioEffort.GOOD, CardioEffort.HARD, null)
        for (session in 1..6) {
            val next = finish(opening, rule, efforts[(session - 1) % efforts.size])
            val steps = minOf(session / 2, 2)
            assertEquals(intervals(6 + steps, distance(400, 72, true), 120), next.block)
            assertEquals(if (session == 2 || session == 4) CardioChange.Step else CardioChange.Repeat, next.change)
            assertEquals(CardioRule(null, RunSession.RACE_PACE, steps, if (session < 4) session % 2 else 0), next.rule)
            opening = next.block
            rule = assertNotNull(next.rule)
        }
    }

    @Test fun `good band holds on hard and counts null easy and good without losing credit`() {
        val opening = block(time(900, true))
        val rule = CardioRule(null, RunSession.TEMPO)
        assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), finish(opening, rule))
        val credited = finish(opening, rule, null)
        assertEquals(CardioOutcome(opening, rule.copy(credits = 1), CardioChange.Repeat), credited)
        assertEquals(credited, finish(opening, assertNotNull(credited.rule), CardioEffort.HARD))
        assertEquals(CardioOutcome(block(time(960, true)), rule.copy(steps = 1), CardioChange.Step),
            finish(opening, assertNotNull(credited.rule), CardioEffort.EASY))
        val threshold = block(time(1200, true))
        val first = finish(threshold, CardioRule(null, RunSession.THRESHOLD), CardioEffort.GOOD)
        assertEquals(CardioOutcome(block(time(1260, true)), CardioRule(null, RunSession.THRESHOLD, steps = 1), CardioChange.Step),
            finish(threshold, assertNotNull(first.rule), CardioEffort.GOOD))
    }

    @Test fun `lone base quality alternates with shared steps through the larger top`() {
        val threshold = block(time(1200, true))
        val rule = CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.VO2MAX)
        assertEquals(CardioOutcome(threshold, rule, CardioChange.Repeat), finish(threshold, rule))
        val first = finish(threshold, rule, CardioEffort.GOOD)
        assertEquals(CardioOutcome(range(RunSession.VO2MAX).block(0, CardioActivity.ROAD_RUN),
            CardioRule(null, RunSession.VO2MAX, credits = 1, alternate = RunSession.THRESHOLD),
            CardioChange.Alternate(RunSession.VO2MAX)), first)
        assertEquals(CardioOutcome(block(time(1260, true)), rule.copy(steps = 1), CardioChange.Alternate(RunSession.THRESHOLD)),
            finish(first.block, assertNotNull(first.rule)))
        val high = finish(range(RunSession.VO2MAX).block(2, CardioActivity.ROAD_RUN),
            CardioRule(null, RunSession.VO2MAX, steps = 9, credits = 1, alternate = RunSession.THRESHOLD))
        assertEquals(CardioOutcome(block(time(1800, true)), rule.copy(steps = 10), CardioChange.Alternate(RunSession.THRESHOLD)), high)
        val back = finish(high.block, assertNotNull(high.rule), CardioEffort.GOOD)
        assertEquals(CardioOutcome(intervals(6, time(240, true), 240),
            CardioRule(null, RunSession.VO2MAX, steps = 10, alternate = RunSession.THRESHOLD),
            CardioChange.Alternate(RunSession.VO2MAX)), back)
    }

    @Test fun `a capped step waits with its credit until the cap permits it`() {
        val opening = intervals(6, distance(400, 60, true), 120)
        val rule = CardioRule(null, RunSession.RACE_PACE, credits = 1)
        assertEquals(RecentMaxima(null, 960), RunCap.maxima(listOf(finished(opening))))
        assertEquals(1020, RunCap.limit(960, 60))
        assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), finish(opening, rule, history = CardioHistory()))
        assertEquals(CardioOutcome(intervals(7, distance(400, 60, true), 120), rule.copy(steps = 1, credits = 0), CardioChange.Step),
            finish(opening, rule, history = CardioHistory(), context = ctx.copy(runCap = false)))
    }

    @Test fun `case 29 fifty K long run stops at distance or three and a half hours without shrinking`() {
        val rule = CardioRule(null, RunSession.LONG, longMetres = 40_000, longSeconds = 12_600)
        for ((pace, metres) in listOf(
            0.30 to listOf(31_500, 33_075, 34_729, 36_465, 38_288, 40_000),
            0.36 to listOf(31_500, 33_075, 34_729, 35_000),
            null to listOf(31_500, 33_075, 34_729, 36_465, 38_288, 40_000),
        )) {
            var previous = 30_000
            for (expected in metres) {
                val next = finish(block(distance(previous)), rule,
                    history = CardioHistory(longest = RecentMaxima(previous, null), secondsPerMetre = pace))
                assertEquals(CardioOutcome(block(distance(expected)), rule, CardioChange.Increase(ProgressMeasure.DISTANCE, false)), next)
                previous = expected
            }
            val opening = block(distance(previous))
            assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), finish(opening, rule,
                history = CardioHistory(longest = RecentMaxima(previous, null), secondsPerMetre = pace)))
        }
        val opening = block(distance(36_000))
        assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), finish(opening, rule,
            history = CardioHistory(longest = RecentMaxima(36_000, null), secondsPerMetre = 0.36)))
    }

    @Test fun `time long run stops at six hours`() {
        val rule = CardioRule(null, RunSession.LONG, longSeconds = 21_600)
        val timeContext = ctx.copy(measure = ProgressMeasure.TIME)
        var opening = block(time(20_000))
        for (seconds in listOf(21_000, 21_600)) {
            val next = finish(opening, rule, context = timeContext)
            assertEquals(CardioOutcome(block(time(seconds)), rule, CardioChange.Increase(ProgressMeasure.TIME, false)), next)
            opening = next.block
        }
        assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), finish(opening, rule, context = timeContext))
    }

    @Test fun `ruled easy repeats`() {
        val rule = CardioRule(null, RunSession.EASY)
        val opening = block(time(1800))
        assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), finish(opening, rule, CardioEffort.EASY))
    }

    @Test fun `rule bounds reject inconsistent state and accept shared range tops`() {
        val invalid = listOf<() -> CardioRule>(
            { CardioRule(3, RunSession.EASY) },
            { CardioRule(null, RunSession.LADDER) },
            { CardioRule(null, RunSession.EASY, steps = 1) },
            { CardioRule(null, RunSession.THRESHOLD, credits = 2) },
            { CardioRule(null, RunSession.THRESHOLD, steps = 11) },
            { CardioRule(null, RunSession.EASY, alternate = RunSession.VO2MAX) },
            { CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.THRESHOLD) },
            { CardioRule(null, RunSession.THRESHOLD, longMetres = 1) },
            { CardioRule(null, RunSession.LONG) },
            { CardioRule(null, RunSession.EASY, credits = 1) },
            { CardioRule(null, RunSession.LONG, longMetres = 0) },
            { CardioRule(null, RunSession.LONG, longMetres = 200_001) },
            { CardioRule(null, RunSession.LONG, longSeconds = 0) },
            { CardioRule(null, RunSession.LONG, longSeconds = 86_401) },
        )
        for (construct in invalid) assertFailsWith<IllegalArgumentException> { construct() }
        assertEquals(10, CardioRule(null, RunSession.THRESHOLD, steps = 10, alternate = RunSession.VO2MAX).steps)
        assertEquals(10, CardioRule(null, RunSession.VO2MAX, steps = 10, alternate = RunSession.THRESHOLD).steps)
    }
}
