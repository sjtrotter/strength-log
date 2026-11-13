package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProgressionSprintTest {
    private val history = CardioHistory()
    private fun ctx(today: Long = 1_000, last: Long? = 993, other: Int = 30, trial: Int? = null) =
        Progression.CardioContext(today, ProgressMeasure.DISTANCE, 5, sprint = SprintContext(last, other, trial))
    private fun acc(steps: Int) = sprintBlock(RunSession.ACCELERATION, steps)
    private fun maxv(steps: Int) = sprintBlock(RunSession.MAX_VELOCITY, steps)
    private fun se(steps: Int) = sprintBlock(RunSession.SPRINT_ENDURANCE, steps)
    private fun tempo(steps: Int) = sprintBlock(RunSession.SPRINT_TEMPO, steps)
    private fun intervals(rounds: Int, metres: Int, recovery: Int, warmup: Boolean = true, hard: Boolean = true) =
        CardioBlock(CardioActivity.TRACK_RUN, buildList<CardioSetSpec> {
            if (warmup) add(CardioSetSpec(CardioRole.WARMUP, Measure.Time(600)))
            repeat(rounds) { index ->
                if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(recovery)))
                add(CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = hard))
            }
        })
    private fun result(spec: CardioSetSpec, status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
        spec, status, ((spec.end as? Measure.Time) ?: (spec.aim as? Measure.Time))?.seconds ?: 0,
        (spec.end as? Measure.Distance)?.metres,
        if (spec.end is Measure.Distance) DistanceSource.MANUAL else null,
    )
    private fun finished(block: CardioBlock) = block.sets.map { result(it) }
    private fun finish(block: CardioBlock, rule: CardioRule, effort: CardioEffort? = CardioEffort.HARD,
                       context: Progression.CardioContext = ctx(), pain: Boolean = false) =
        Progression.cardioAfterSession(block, rule, finished(block), effort, history, context, pain)
    private fun opening(block: CardioBlock, rule: CardioRule, context: Progression.CardioContext = ctx()) =
        Progression.cardioAtStart(block, rule, history, PlanGoal.Sprint(100), context)

    @Test fun `two counted sessions step a sprint day whatever the word`() {
        assertEquals(intervals(5, 10, 60), acc(1))
        for (effort in listOf(CardioEffort.EASY, CardioEffort.GOOD, CardioEffort.HARD, null)) {
            val first = finish(acc(0), CardioRule(null, RunSession.ACCELERATION), effort)
            assertEquals(CardioOutcome(acc(0), CardioRule(null, RunSession.ACCELERATION, credits = 1), CardioChange.Repeat), first)
            assertEquals(CardioOutcome(acc(1), CardioRule(null, RunSession.ACCELERATION, steps = 1), CardioChange.Step),
                finish(first.block, assertNotNull(first.rule), effort))
        }
    }

    @Test fun `an unfinished WORK set or Pain does not count`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, credits = 1)
        val block = acc(0)
        val shortened = finished(block).mapIndexed { index, row ->
            if (index == block.sets.indexOfLast { it.role == CardioRole.WORK }) row.copy(status = CardioSetStatus.SHORTENED) else row
        }
        val repeat = CardioOutcome(block, rule, CardioChange.Repeat)
        assertEquals(repeat, Progression.cardioAfterSession(block, rule, shortened, CardioEffort.HARD, history, ctx()))
        assertEquals(repeat, finish(block, rule, pain = true))
        val tempoRule = CardioRule(null, RunSession.SPRINT_TEMPO, credits = 1)
        assertEquals(CardioOutcome(tempo(0), tempoRule, CardioChange.Repeat), finish(tempo(0), tempoRule))
        assertEquals(intervals(6, 200, 60, warmup = false, hard = false), tempo(1))
        assertEquals(CardioOutcome(tempo(1), CardioRule(null, RunSession.SPRINT_TEMPO, steps = 1), CardioChange.Step),
            finish(tempo(0), tempoRule, CardioEffort.GOOD))
    }

    @Test fun `the top repeats without credit`() {
        val rule = CardioRule(null, RunSession.MAX_VELOCITY, steps = 3)
        assertEquals(intervals(5, 20, 240), maxv(3))
        repeat(2) { assertEquals(CardioOutcome(maxv(3), rule, CardioChange.Repeat), finish(maxv(3), rule)) }
    }

    @Test fun `the intro holds the low end for three counted sessions`() {
        for (session in RunSession.entries.filter { it.sprint }) {
            var rule = CardioRule(null, session, intro = 3)
            val block = sprintBlock(session, 0)
            val effort = if (session == RunSession.SPRINT_TEMPO) CardioEffort.GOOD else CardioEffort.HARD
            val context = ctx(other = 1_000)
            for (intro in 2 downTo 0) {
                val next = finish(block, rule, effort, context)
                rule = CardioRule(null, session, intro = intro)
                assertEquals(CardioOutcome(block, rule, CardioChange.Repeat), next)
                if (intro == 2) {
                    assertEquals(next, finish(block, rule, effort, context, pain = true))
                    val shortened = finished(block).map { row ->
                        if (row.spec.role == CardioRole.WORK) row.copy(status = CardioSetStatus.SHORTENED) else row
                    }
                    assertEquals(next, Progression.cardioAfterSession(block, rule, shortened, effort, history, context))
                }
            }
            val fourth = finish(block, rule, effort, context)
            assertEquals(CardioOutcome(block, rule.copy(credits = 1), CardioChange.Repeat), fourth)
            assertEquals(CardioOutcome(sprintBlock(session, 1), rule.copy(steps = 1), CardioChange.Step),
                finish(block, assertNotNull(fourth.rule), effort, context))
        }
        var rule = CardioRule(null, RunSession.ACCELERATION, intro = 3)
        repeat(4) { rule = assertNotNull(finish(acc(0), rule).rule) }
        assertEquals(CardioOutcome(acc(1), CardioRule(null, RunSession.ACCELERATION, steps = 1), CardioChange.Step),
            finish(acc(0), rule))
        val tempoRule = CardioRule(null, RunSession.SPRINT_TEMPO, intro = 2)
        assertEquals(CardioOutcome(tempo(0), tempoRule, CardioChange.Repeat), finish(tempo(0), tempoRule))
    }

    @Test fun `a step past the weekly 15% waits and each wait adds 15%`() {
        for ((session, expected) in listOf(
            RunSession.ACCELERATION to listOf(40, 50, 60, 70, 80, 160, 240),
            RunSession.MAX_VELOCITY to listOf(30, 40, 50, 100),
            RunSession.SPRINT_ENDURANCE to listOf(300, 400, 440, 480, 520, 560, 600),
            RunSession.SPRINT_TEMPO to listOf(600, 1_200, 1_800),
        )) {
            assertEquals(expected, (0..requireNotNull(RunRange.of(session)).top).map {
                SprintRules.workMetres(sprintBlock(session, it))
            }, session.name)
        }
        for ((before, after, held) in listOf(Triple(70, 80, 0), Triple(100, 115, 0), Triple(130, 210, 3), Triple(400, 500, 1))) {
            assertTrue(SprintRules.withinRise(before, after, held), "$before -> $after held $held")
        }
        for ((before, after, held) in listOf(Triple(100, 116, 0), Triple(130, 210, 2), Triple(400, 500, 0), Triple(40, 50, 0))) {
            assertFalse(SprintRules.withinRise(before, after, held), "$before -> $after held $held")
        }
        val ready = CardioRule(null, RunSession.ACCELERATION, credits = 1)
        val held = finish(acc(0), ready, context = ctx(other = 0))
        assertEquals(CardioOutcome(acc(0), ready.copy(held = 1), CardioChange.Repeat), held)
        assertEquals(CardioOutcome(acc(1), CardioRule(null, RunSession.ACCELERATION, steps = 1), CardioChange.Step),
            finish(held.block, assertNotNull(held.rule), context = ctx(other = 0)))
        var rule = CardioRule(null, RunSession.ACCELERATION, steps = 4, credits = 1)
        for (wait in 1..3) {
            val next = finish(acc(4), rule, context = ctx(other = 50))
            rule = rule.copy(held = wait)
            assertEquals(CardioOutcome(acc(4), rule, CardioChange.Repeat), next)
        }
        assertEquals(intervals(8, 20, 120), acc(5))
        assertEquals(CardioOutcome(acc(5), CardioRule(null, RunSession.ACCELERATION, steps = 5), CardioChange.Step),
            finish(acc(4), rule, context = ctx(other = 50)))
        val endurance = CardioRule(null, RunSession.SPRINT_ENDURANCE, credits = 1)
        val wait = finish(se(0), endurance, context = ctx(other = 100))
        assertEquals(CardioOutcome(se(0), endurance.copy(held = 1), CardioChange.Repeat), wait)
        assertEquals(intervals(4, 100, 480), se(1))
        assertEquals(CardioOutcome(se(1), CardioRule(null, RunSession.SPRINT_ENDURANCE, steps = 1), CardioChange.Step),
            finish(se(0), assertNotNull(wait.rule), context = ctx(other = 100)))
        assertEquals(CardioOutcome(tempo(1), CardioRule(null, RunSession.SPRINT_TEMPO, steps = 1), CardioChange.Step),
            finish(tempo(0), CardioRule(null, RunSession.SPRINT_TEMPO, credits = 1), CardioEffort.GOOD, ctx(other = 0)))
    }

    @Test fun `a layoff opens the intro block and Finish restarts the intro`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 5)
        assertEquals(acc(0), opening(acc(5), rule, ctx(1_021, 1_000)))
        for (last in listOf(1_001L, 1_007L, null)) assertEquals(acc(5), opening(acc(5), rule, ctx(1_021, last)))
        val ready = rule.copy(credits = 1, held = 2)
        assertEquals(CardioOutcome(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 2), CardioChange.Restart),
            finish(acc(0), ready, context = ctx(1_021, 1_000)))
        assertEquals(CardioOutcome(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 3), CardioChange.Restart),
            finish(acc(0), ready, context = ctx(1_021, 1_000), pain = true))
        assertEquals(intervals(8, 30, 180), acc(6))
        assertEquals(CardioOutcome(acc(6), CardioRule(null, RunSession.ACCELERATION, steps = 6), CardioChange.Step),
            finish(acc(5), ready, context = ctx(1_021, 1_001)))
        assertEquals(CardioOutcome(maxv(0), CardioRule(null, RunSession.MAX_VELOCITY, intro = 2), CardioChange.Restart),
            finish(maxv(0), CardioRule(null, RunSession.MAX_VELOCITY), context = ctx(1_021, 1_000)))
    }

    @Test fun `sprint days skip the run cap and require their context`() {
        val recent = CardioHistory(longest = RecentMaxima(100, 30))
        for (session in RunSession.entries.filter { it.sprint }) {
            val steps = if (session == RunSession.ACCELERATION) 5 else 0
            val block = sprintBlock(session, steps)
            val rule = CardioRule(null, session, steps = steps)
            for (runCap in listOf(true, false)) {
                assertEquals(block, Progression.cardioAtStart(block, rule, recent, PlanGoal.Sprint(100), ctx().copy(runCap = runCap)))
            }
            val missing = Progression.CardioContext(1_000, null, 0)
            assertFailsWith<IllegalArgumentException> { opening(block, rule, missing) }
            assertFailsWith<IllegalArgumentException> { finish(block, rule, context = missing) }
        }
    }

    @Test fun `every fifth rotation's Sprint A runs the time trial and changes nothing`() {
        for (metres in listOf(37, 60, 100, 200, 400)) {
            val trial = CardioBlock(CardioActivity.TRACK_RUN, listOf(
                CardioSetSpec(CardioRole.WARMUP, Measure.Time(600)),
                CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true),
            ))
            assertEquals(trial, SprintRules.timeTrial(metres, CardioActivity.TRACK_RUN))
        }
        val trial = SprintRules.timeTrial(100, CardioActivity.TRACK_RUN)
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 2, credits = 1)
        assertEquals(trial, opening(acc(2), rule, ctx(trial = 100)))
        assertEquals(acc(0), opening(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 1), ctx(trial = 100)))
        assertEquals(acc(0), opening(acc(2), rule, ctx(1_021, 1_000, trial = 100)))
        assertEquals(CardioOutcome(acc(2), rule, CardioChange.Repeat), finish(trial, rule, context = ctx(trial = 100)))
        assertEquals(CardioOutcome(acc(2), rule, CardioChange.Repeat),
            finish(trial, rule, context = ctx(trial = 100), pain = true))
        val waiting = rule.copy(held = 2)
        assertEquals(CardioOutcome(acc(2), waiting, CardioChange.Repeat), finish(trial, waiting, context = ctx(trial = 100)))
        assertEquals(CardioOutcome(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 2), CardioChange.Restart),
            finish(acc(0), rule, context = ctx(1_021, 1_000, trial = 100)))
    }

    @Test fun `rules hold sprint state only where it means something`() {
        val invalid = listOf<() -> CardioRule>(
            { CardioRule(null, RunSession.ACCELERATION, intro = 4) },
            { CardioRule(null, RunSession.ACCELERATION, intro = -1) },
            { CardioRule(null, RunSession.ACCELERATION, steps = 1, intro = 1) },
            { CardioRule(null, RunSession.ACCELERATION, credits = 1, intro = 1) },
            { CardioRule(null, RunSession.ACCELERATION, held = 1) },
            { CardioRule(null, RunSession.ACCELERATION, credits = 1, held = -1) },
            { CardioRule(null, RunSession.THRESHOLD, intro = 1) },
            { CardioRule(null, RunSession.EASY, held = 1) },
            { CardioRule(3, intro = 1) },
            { CardioRule(null, RunSession.ACCELERATION, alternate = RunSession.MAX_VELOCITY) },
            { CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.ACCELERATION) },
        )
        for (make in invalid) assertFailsWith<IllegalArgumentException> { make() }
        assertEquals(3, CardioRule(null, RunSession.SPRINT_TEMPO, intro = 3).intro)
        assertEquals(5, CardioRule(null, RunSession.ACCELERATION, credits = 1, held = 5).held)
    }

    @Test fun `sprint kinds are not quality`() {
        assertEquals(listOf(RunSession.THRESHOLD, RunSession.VO2MAX, RunSession.RACE_PACE, RunSession.TEST_INTERVALS,
            RunSession.TEMPO, RunSession.SHARPENING), RunSession.entries.filter { it.quality })
        assertEquals(listOf(RunSession.ACCELERATION, RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE,
            RunSession.SPRINT_TEMPO), RunSession.entries.filter { it.sprint })
    }
}
