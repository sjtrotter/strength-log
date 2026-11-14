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
    private fun ctx(today: Long = 1_000, last: Long? = 993, other: Int = 30, trial: Int? = null, planned: Int = other + 40) =
        Progression.CardioContext(today, ProgressMeasure.DISTANCE, 5, sprint = SprintContext(last, other, trial, planned))
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
            assertEquals(CardioOutcome(acc(1), CardioRule(null, RunSession.ACCELERATION, steps = 1, sprintStepCycle = 0), CardioChange.Step),
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
        val rule = CardioRule(null, RunSession.MAX_VELOCITY, steps = 7)
        assertEquals(intervals(5, 20, 240), maxv(7))
        repeat(2) { assertEquals(CardioOutcome(maxv(7), rule, CardioChange.Repeat), finish(maxv(7), rule, context = ctx(planned = 130))) }
    }

    @Test fun `the intro holds the low end for three counted sessions`() {
        for (session in RunSession.entries.filter { it.sprint }) {
            var rule = CardioRule(null, session, intro = 3)
            val block = sprintBlock(session, 0)
            val effort = if (session == RunSession.SPRINT_TEMPO) CardioEffort.GOOD else CardioEffort.HARD
            val context = ctx(other = 1_000, planned = 2_000)
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
            assertEquals(CardioOutcome(sprintBlock(session, 1), rule.copy(steps = 1, sprintStepCycle = if (session == RunSession.SPRINT_TEMPO) null else 0), CardioChange.Step),
                finish(block, assertNotNull(fourth.rule), effort, context))
        }
        var rule = CardioRule(null, RunSession.ACCELERATION, intro = 3)
        repeat(4) { rule = assertNotNull(finish(acc(0), rule).rule) }
        assertEquals(CardioOutcome(acc(1), CardioRule(null, RunSession.ACCELERATION, steps = 1, sprintStepCycle = 0), CardioChange.Step),
            finish(acc(0), rule))
        val tempoRule = CardioRule(null, RunSession.SPRINT_TEMPO, intro = 2)
        assertEquals(CardioOutcome(tempo(0), tempoRule, CardioChange.Repeat), finish(tempo(0), tempoRule))
    }

    @Test fun `the weekly cap never compounds and a waiting step keeps its credit`() {
        for ((before, after) in listOf(70 to 80, 100 to 115, 130 to 140)) {
            assertTrue(SprintRules.withinRise(before, after), "$before -> $after")
        }
        for ((before, after) in listOf(100 to 116, 130 to 210, 400 to 500, 40 to 50)) {
            assertFalse(SprintRules.withinRise(before, after), "$before -> $after")
        }
        val ready = CardioRule(null, RunSession.ACCELERATION, credits = 1)
        var waiting = ready
        repeat(16) { index ->
            val out = finish(acc(0), waiting, context = ctx(other = 0))
            waiting = ready.copy(sprintWait = index + 1)
            assertEquals(CardioOutcome(acc(0), waiting, CardioChange.Repeat), out)
        }
        val endurance = CardioRule(null, RunSession.SPRINT_ENDURANCE, credits = 1)
        assertEquals(CardioOutcome(se(0), endurance.copy(sprintWait = 1), CardioChange.Repeat),
            finish(se(0), endurance, context = ctx(other = 100, planned = 400)))
        assertEquals(CardioOutcome(tempo(1), CardioRule(null, RunSession.SPRINT_TEMPO, steps = 1), CardioChange.Step),
            finish(tempo(0), CardioRule(null, RunSession.SPRINT_TEMPO, credits = 1), CardioEffort.GOOD, ctx(other = 0)))
    }

    @Test fun `a capped distance step lengthens one rep by 10 meters`() {
        val ready = CardioRule(null, RunSession.ACCELERATION, steps = 4, credits = 1)
        val next = finish(acc(4), ready, context = ctx(other = 50, planned = 130))
        assertEquals(CardioOutcome(acc(5), ready.copy(steps = 5, credits = 0, sprintStepCycle = 0, sprintStepFrom = 4), CardioChange.Step), next)
        assertEquals(listOf(20) + List(7) { 10 }, next.block.sets.filter { it.role == CardioRole.WORK }
            .map { (it.end as Measure.Distance).metres })
        assertEquals(Measure.Time(120), next.block.sets[2].end)
        assertEquals(Measure.Time(60), next.block.sets[4].end)
        assertEquals(intervals(8, 20, 120), acc(12))
        assertEquals(intervals(8, 30, 180), acc(20))
        assertEquals(CardioOutcome(acc(5), ready.copy(steps = 5, credits = 0,
            sprintStepCycle = 0, sprintStepFrom = 4), CardioChange.Step),
            finish(acc(4), ready, context = ctx(other = 0, planned = 80)))
    }

    @Test fun `13 days since the last maximal session holds and 14 resets`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 12, credits = 1,
            sprintWait = 2, sprintStepCycle = 0, sprintStepFrom = 4)
        val before = ctx(1_014, 1_001, planned = 190)
        val gap = ctx(1_014, 1_000, planned = 190)
        assertEquals(acc(12), opening(acc(12), rule, before))
        assertEquals(acc(0), opening(acc(12), rule, gap))
        assertEquals(CardioOutcome(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 2), CardioChange.Restart),
            finish(acc(0), rule, context = gap))
        assertEquals(CardioOutcome(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 3), CardioChange.Restart),
            finish(acc(0), rule, context = gap, pain = true))
        assertEquals(CardioChange.Step, finish(acc(12), rule, context = before).change)
        assertEquals(acc(12), opening(acc(12), rule, before.copy(sprint = SprintContext(null, 30, null, 190))))
    }

    @Test fun `sprint days skip the run cap and require their context`() {
        val recent = CardioHistory(longest = RecentMaxima(100, 30))
        for (session in RunSession.entries.filter { it.sprint }) {
            val steps = if (session == RunSession.ACCELERATION) 12 else 0
            val block = sprintBlock(session, steps)
            val rule = CardioRule(null, session, steps = steps)
            for (runCap in listOf(true, false)) {
                assertEquals(block, Progression.cardioAtStart(block, rule, recent, PlanGoal.Sprint(100), ctx(planned = 1_000).copy(runCap = runCap)))
            }
            val missing = Progression.CardioContext(1_000, null, 0)
            assertFailsWith<IllegalArgumentException> { opening(block, rule, missing) }
            assertFailsWith<IllegalArgumentException> { finish(block, rule, context = missing) }
        }
    }

    @Test fun `short time trials have two reps with full acceleration recovery`() {
        for ((metres, recovery) in listOf(37 to 240, 60 to 360)) {
            assertEquals(intervals(2, metres, recovery), SprintRules.timeTrial(metres, CardioActivity.TRACK_RUN))
        }
        for (metres in listOf(100, 200, 400)) {
            assertEquals(intervals(1, metres, 0), SprintRules.timeTrial(metres, CardioActivity.TRACK_RUN))
        }
    }

    @Test fun `an admitted trial changes no progression state and gap takes priority`() {
        val trial = SprintRules.timeTrial(100, CardioActivity.TRACK_RUN)
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 12, credits = 1)
        val context = ctx(trial = 100, planned = 190)
        assertEquals(trial, opening(acc(12), rule, context))
        assertEquals(acc(0), opening(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 1), context))
        assertEquals(CardioOutcome(acc(12), rule, CardioChange.Repeat), finish(trial, rule, context = context))
        assertEquals(CardioOutcome(acc(12), rule, CardioChange.Repeat), finish(trial, rule, context = context, pain = true))
        val gap = ctx(1_014, 1_000, trial = 100, planned = 190)
        assertEquals(acc(0), opening(acc(12), rule, gap))
        assertEquals(CardioOutcome(acc(0), CardioRule(null, RunSession.ACCELERATION, intro = 2), CardioChange.Restart),
            finish(acc(0), rule, context = gap))
    }

    @Test fun `tempo counts down its intro during a maximal sprint layoff`() {
        var rule = CardioRule(null, RunSession.SPRINT_TEMPO, intro = 3)
        val context = ctx(1_014, 1_000)
        repeat(3) { index ->
            assertEquals(tempo(0), opening(tempo(0), rule, context))
            val out = finish(tempo(0), rule, CardioEffort.GOOD, context)
            rule = rule.copy(intro = 2 - index)
            assertEquals(CardioOutcome(tempo(0), rule, CardioChange.Repeat), out)
        }
        val ready = CardioRule(null, RunSession.SPRINT_TEMPO, steps = 1, credits = 1)
        assertEquals(tempo(1), opening(tempo(1), ready, context))
        assertEquals(CardioOutcome(tempo(2), ready.copy(steps = 2, credits = 0), CardioChange.Step),
            finish(tempo(1), ready, CardioEffort.GOOD, context))
    }

    @Test fun `a longer waiting sprint day gets the next step`() {
        val ready = CardioRule(null, RunSession.ACCELERATION, credits = 1)
        val context = ctx(other = 100, planned = 140).copy(
            sprint = SprintContext(993, 100, null, 140, otherWait = 2))
        val waiting = ready.copy(sprintWait = 1)
        assertEquals(CardioOutcome(acc(0), waiting, CardioChange.Repeat), finish(acc(0), ready, context = context))
        assertEquals(CardioOutcome(acc(0), waiting, CardioChange.Repeat),
            finish(acc(0), waiting, context = context, pain = true))
        val oldest = ready.copy(sprintWait = 2)
        assertEquals(CardioOutcome(acc(1), ready.copy(steps = 1, credits = 0, sprintStepCycle = 0), CardioChange.Step),
            finish(acc(0), oldest, context = context))
    }

    @Test fun `rules hold sprint state only where it means something`() {
        val invalid = listOf<() -> CardioRule>(
            { CardioRule(null, RunSession.ACCELERATION, intro = 4) },
            { CardioRule(null, RunSession.ACCELERATION, intro = -1) },
            { CardioRule(null, RunSession.ACCELERATION, steps = 1, intro = 1) },
            { CardioRule(null, RunSession.ACCELERATION, credits = 1, intro = 1) },
            { CardioRule(null, RunSession.THRESHOLD, intro = 1) },
            { CardioRule(3, intro = 1) },
            { CardioRule(null, RunSession.ACCELERATION, sprintWait = -1) },
            { CardioRule(null, RunSession.ACCELERATION, sprintWait = 1) },
            { CardioRule(null, RunSession.SPRINT_TEMPO, credits = 1, sprintWait = 1) },
            { CardioRule(null, RunSession.ACCELERATION, sprintStepCycle = -1) },
            { CardioRule(null, RunSession.THRESHOLD, sprintStepCycle = 0) },
            { CardioRule(null, RunSession.ACCELERATION, steps = 1, sprintStepFrom = 1) },
            { CardioRule(null, RunSession.ACCELERATION, sprintStepCycle = 0, sprintStepFrom = 1) },
            { CardioRule(null, RunSession.ACCELERATION, alternate = RunSession.MAX_VELOCITY) },
            { CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.ACCELERATION) },
        )
        for (make in invalid) assertFailsWith<IllegalArgumentException> { make() }
        assertEquals(3, CardioRule(null, RunSession.SPRINT_TEMPO, intro = 3).intro)
    }

    @Test fun `sprint kinds are not quality`() {
        assertEquals(listOf(RunSession.THRESHOLD, RunSession.VO2MAX, RunSession.RACE_PACE, RunSession.TEST_INTERVALS,
            RunSession.TEMPO, RunSession.SHARPENING), RunSession.entries.filter { it.quality })
        assertEquals(listOf(RunSession.ACCELERATION, RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE,
            RunSession.SPRINT_TEMPO), RunSession.entries.filter { it.sprint })
    }
}
