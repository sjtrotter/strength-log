package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import cloud.trotter.log.strength.domain.model.DistanceSource

class ProgressionEasyRunTest {
    private val rule = CardioRule(null, RunSession.EASY)
    private fun block(seconds: Int) = CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds))))
    private fun finished(block: CardioBlock) = block.sets.map {
        CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as? Measure.Time)?.seconds ?: 1200)
    }
    private fun ctx(stop: Int = 1800, cycle: Int = 0) =
        Progression.CardioContext(1000, ProgressMeasure.TIME, 5, easy = EasyRunContext(stop, cycle))
    private fun finish(
        opening: CardioBlock = block(1200),
        storedRule: CardioRule = rule,
        context: Progression.CardioContext = ctx(),
        effort: CardioEffort? = CardioEffort.EASY,
        pain: Boolean = false,
        results: List<CardioSetResult> = finished(opening),
        history: CardioHistory = CardioHistory(),
    ) = Progression.cardioAfterSession(opening, storedRule, results, effort, history, context, pain)

    @Test fun `easy run grows five percent by time up to its stop`() {
        for (effort in listOf(CardioEffort.EASY, CardioEffort.GOOD, null)) {
            assertEquals(CardioOutcome(block(1260), rule.copy(stepCycle = 0), CardioChange.Increase(ProgressMeasure.TIME, false)),
                finish(effort = effort))
        }
    }

    @Test fun `easy run stops at the session minutes and never shrinks`() {
        assertEquals(CardioOutcome(block(1800), rule.copy(stepCycle = 0), CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(block(1750)))
        for (seconds in listOf(1800, 1900)) {
            val opening = block(seconds)
            val next = finish(opening)
            assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), next)
            assertSame(opening, next.block)
        }
    }

    @Test fun `easy run grows once per rotation`() {
        val opening = block(1200)
        val storedRule = rule.copy(stepCycle = 3)
        assertEquals(CardioOutcome(opening, storedRule, CardioChange.Repeat), finish(opening, storedRule, ctx(cycle = 3)))
        assertEquals(CardioOutcome(block(1260), rule.copy(stepCycle = 4), CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(opening, storedRule, ctx(cycle = 4)))
    }

    @Test fun `unfinished Hard or Pain holds the easy run`() {
        val opening = block(1200)
        val storedRule = rule.copy(stepCycle = 2)
        val repeated = CardioOutcome(opening, storedRule, CardioChange.Repeat)
        assertEquals(repeated, finish(opening, storedRule, effort = CardioEffort.HARD))
        assertEquals(repeated, finish(opening, storedRule, pain = true))
        val unfinished = finished(opening).map { it.copy(status = CardioSetStatus.SHORTENED, elapsedSeconds = 600) }
        assertEquals(repeated, finish(opening, storedRule, results = unfinished))
        assertEquals(repeated, finish(opening, storedRule, results = emptyList<CardioSetResult>()))
        val aimed = opening.copy(sets = opening.sets.map { it.copy(aim = Measure.Distance(3000)) })
        val missedAim = finished(aimed).map { it.copy(metres = 2900, distanceSource = DistanceSource.MANUAL) }
        assertEquals(CardioOutcome(aimed, storedRule, CardioChange.Repeat), finish(aimed, storedRule, results = missedAim))
    }

    @Test fun `easy runs accept a step cycle and other run days reject it`() {
        assertEquals(0, CardioRule(null, RunSession.EASY, stepCycle = 0).stepCycle)
        assertFailsWith<IllegalArgumentException> { CardioRule(null, RunSession.THRESHOLD, stepCycle = 0) }
        assertFailsWith<IllegalArgumentException> { CardioRule(null, RunSession.LONG, longSeconds = 5400, stepCycle = 0) }
        assertFailsWith<IllegalArgumentException> { CardioRule(null, RunSession.EASY, stepCycle = -1) }
        assertFailsWith<IllegalArgumentException> { EasyRunContext(59, 0) }
        assertFailsWith<IllegalArgumentException> { EasyRunContext(1800, -1) }
    }

    @Test fun `the run cap clips easy growth against the longest including this session`() {
        val context = ctx().copy(percent = 25)
        assertEquals(1320, RunCap.limit(1200, 60))
        assertEquals(CardioOutcome(block(1320), rule.copy(stepCycle = 0), CardioChange.Increase(ProgressMeasure.TIME, true)),
            finish(context = context))
        assertEquals(CardioOutcome(block(1500), rule.copy(stepCycle = 0), CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(context = context.copy(runCap = false)))
        assertEquals(CardioOutcome(block(1500), rule.copy(stepCycle = 0), CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(context = context, history = CardioHistory(longest = RecentMaxima(null, 1800))))
    }

    @Test fun `an edited distance end repeats even with an easy run context`() {
        val opening = CardioBlock(CardioActivity.ROAD_RUN,
            listOf(CardioSetSpec(CardioRole.WORK, Measure.Distance(3000))))
        assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat),
            finish(opening, context = ctx().copy(measure = ProgressMeasure.DISTANCE)))
    }
    @Test fun `the easy stop keeps the pace of a distance aim`() {
        val opening = block(1750).copy(sets = listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1750), Measure.Distance(5000))))
        val results = finished(opening).map { it.copy(metres = 5000, distanceSource = DistanceSource.MANUAL) }
        val expected = opening.copy(sets = listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800), Measure.Distance(5142))))
        // 1750 + round(87.5) = 1838; round(5000 * 1838 / 1750) = 5251; round(5251 * 1800 / 1838) = 5142.
        assertEquals(CardioOutcome(expected, rule.copy(stepCycle = 0), CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(opening, context = ctx().copy(runCap = false), results = results))
    }

}
