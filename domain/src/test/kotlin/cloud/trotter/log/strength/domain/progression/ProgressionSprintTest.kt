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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProgressionSprintTest {
    private val history = CardioHistory()
    private fun ctx(layoff: Boolean = false, trial: Int? = null, cycle: Int = 0, tempo: Boolean = false) =
        Progression.CardioContext(1_000, null, 0, sprint = SprintContext(layoff, trial, cycle, tempo))
    private fun result(spec: CardioSetSpec, status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
        spec, status, (spec.end as? Measure.Time)?.seconds ?: 0,
        (spec.end as? Measure.Distance)?.metres,
        if (spec.end is Measure.Distance) DistanceSource.MANUAL else null,
    )
    private fun finished(block: CardioBlock) = block.sets.map { result(it) }
    private fun finish(rule: CardioRule, effort: CardioEffort? = CardioEffort.HARD,
                       context: Progression.CardioContext = ctx(), pain: Boolean = false,
                       block: CardioBlock = sprintBlock(requireNotNull(rule.session), rule.steps)) =
        Progression.cardioAfterSession(block, rule, finished(block), effort, history, context, pain)
    private fun opening(rule: CardioRule, context: Progression.CardioContext = ctx()) =
        Progression.cardioAtStart(sprintBlock(requireNotNull(rule.session), rule.steps), rule, history, PlanGoal.Sprint(100), context)
    private fun reps(block: CardioBlock) = block.sets.filter { it.role == CardioRole.WORK }.map { (it.end as Measure.Distance).metres }

    @Test fun `two counted sessions add one total unit whatever the maximal effort word`() {
        for (session in RunSession.entries.filter { it.sprint }) {
            for (effort in listOf(CardioEffort.EASY, CardioEffort.GOOD, null, CardioEffort.HARD)) {
                if (session == RunSession.SPRINT_TEMPO && effort == CardioEffort.HARD) continue
                val rule = CardioRule(null, session)
                val first = finish(rule, effort)
                assertEquals(rule.copy(credits = 1), first.rule)
                val second = finish(assertNotNull(first.rule), effort)
                assertEquals(CardioOutcome(sprintBlock(session, 1), rule.copy(steps = 1, stepCycle = 0), CardioChange.Step), second)
            }
        }
    }

    @Test fun `an unfinished WORK set or Pain does not count and Hard tempo keeps its credit`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, credits = 1)
        val block = sprintBlock(RunSession.ACCELERATION, 0)
        val incomplete = finished(block).mapIndexed { index, row ->
            if (index == block.sets.indexOfLast { it.role == CardioRole.WORK }) row.copy(status = CardioSetStatus.SHORTENED) else row
        }
        assertEquals(CardioOutcome(block, rule, CardioChange.Repeat),
            Progression.cardioAfterSession(block, rule, incomplete, CardioEffort.HARD, history, ctx()))
        assertEquals(CardioOutcome(block, rule, CardioChange.Repeat), finish(rule, pain = true))
        val tempo = CardioRule(null, RunSession.SPRINT_TEMPO, credits = 1)
        assertEquals(tempo, finish(tempo).rule)
        assertEquals(listOf(200, 100, 100, 100, 100, 100), reps(finish(tempo, CardioEffort.GOOD).block))
    }

    @Test fun `intro ends after three counted sessions without earning credit`() {
        for (session in RunSession.entries.filter { it.sprint }) {
            var rule = CardioRule(null, session, intro = 3)
            repeat(3) { index ->
                val out = finish(rule, CardioEffort.GOOD)
                rule = rule.copy(intro = 2 - index)
                assertEquals(CardioOutcome(sprintBlock(session, 0), rule, CardioChange.Repeat), out)
            }
            rule = assertNotNull(finish(rule, CardioEffort.GOOD).rule)
            assertEquals(1, rule.credits)
            assertEquals(CardioChange.Step, finish(rule, CardioEffort.GOOD).change)
        }
    }

    @Test fun `nine repeats allow one step and keep the next credit including tempo`() {
        for (session in RunSession.entries.filter { it.sprint }) {
            var rule = CardioRule(null, session)
            repeat(9) { rule = assertNotNull(finish(rule, CardioEffort.GOOD).rule) }
            assertEquals(CardioRule(null, session, steps = 1, credits = 1, stepCycle = 0), rule)
            val next = finish(rule, CardioEffort.GOOD, ctx(cycle = 1))
            assertEquals(rule.copy(steps = 2, credits = 0, stepCycle = 1), next.rule)
        }
    }

    @Test fun `one-day low volumes step from 40 to 50 and 30 to 40 meters`() {
        for ((session, before, after) in listOf(Triple(RunSession.ACCELERATION, 40, 50), Triple(RunSession.MAX_VELOCITY, 30, 40))) {
            val rule = CardioRule(null, session, credits = 1)
            assertEquals(before, SprintRules.workMetres(opening(rule)))
            val out = finish(rule)
            assertEquals(CardioChange.Step, out.change)
            assertEquals(after, SprintRules.workMetres(out.block))
        }
        assertTrue(SprintRules.withinRise(40, 50))
        assertTrue(SprintRules.withinRise(30, 40))
        assertTrue(SprintRules.withinRise(70, 90))
        assertFalse(SprintRules.withinRise(70, 91))
    }

    @Test fun `sprint endurance rests ten minutes between reps`() {
        for (steps in 0..20) {
            val recovery = sprintBlock(RunSession.SPRINT_ENDURANCE, steps).sets.filter { it.role == CardioRole.RECOVERY }
            assertTrue(recovery.isNotEmpty())
            assertTrue(recovery.all { it.end == Measure.Time(600) }, "step $steps")
        }
    }

    @Test fun `sprint endurance crosses 280 to 300 meters`() {
        val rule = CardioRule(null, RunSession.SPRINT_ENDURANCE, steps = 4, credits = 1)
        assertEquals(listOf(140, 140), reps(opening(rule)))
        val out = finish(rule)
        assertEquals(CardioChange.Step, out.change)
        assertEquals(5, requireNotNull(out.rule).steps)
        assertEquals(listOf(100, 100, 100), reps(out.block))
    }

    @Test fun `sprint endurance crosses 380 to 400 meters without a volume spike`() {
        // audit 2026-11-13, row 7: 200+9*20=380 m; next = 200+10*20=400 m.
        val rule = CardioRule(null, RunSession.SPRINT_ENDURANCE, steps = 9, credits = 1)
        assertEquals(listOf(130, 130, 120), reps(opening(rule)))
        assertEquals(listOf(100, 100, 100, 100), reps(finish(rule).block))
    }

    @Test fun `acceleration lengthens one rep and rests for the preceding rep`() {
        val out = finish(CardioRule(null, RunSession.ACCELERATION, steps = 4, credits = 1))
        assertEquals(listOf(20) + List(7) { 10 }, reps(out.block))
        assertEquals(Measure.Time(120), out.block.sets[2].end)
        assertEquals(Measure.Time(60), out.block.sets[4].end)
        assertEquals(List(8) { 20 }, reps(sprintBlock(RunSession.ACCELERATION, 12)))
        assertEquals(List(8) { 30 }, reps(sprintBlock(RunSession.ACCELERATION, 20)))
    }

    @Test fun `restart precedes trial and B tempo substitution and counts intro session one`() {
        for (session in listOf(RunSession.ACCELERATION, RunSession.SPRINT_ENDURANCE)) {
            val rule = CardioRule(null, session, steps = 4, credits = 1, stepCycle = 3)
            val context = ctx(layoff = true, trial = if (session == RunSession.ACCELERATION) 100 else null,
                cycle = 4, tempo = session == RunSession.SPRINT_ENDURANCE)
            val low = sprintBlock(session, 0)
            assertEquals(low, opening(rule, context))
            assertEquals(CardioOutcome(low, CardioRule(null, session, intro = 2, stepCycle = 3), CardioChange.Restart),
                finish(rule, context = context, block = low))
            assertEquals(CardioRule(null, session, intro = 3, stepCycle = 3),
                finish(rule, context = context, pain = true, block = low).rule)
        }
    }

    @Test fun `restart keeps cycle markers when a rotation spans a layoff`() {
        val old = CardioRule(null, RunSession.ACCELERATION, steps = 12, stepCycle = 4, sprintTrialCycle = 4)
        val low = sprintBlock(RunSession.ACCELERATION, 0)
        var rule = assertNotNull(finish(old, context = ctx(layoff = true, cycle = 4), block = low).rule)
        repeat(4) { rule = assertNotNull(finish(rule, context = ctx(cycle = 4)).rule) }
        assertEquals(old.copy(steps = 0, credits = 1), rule)
        assertEquals(rule.copy(steps = 1, credits = 0, stepCycle = 5), finish(rule, context = ctx(cycle = 5)).rule)
    }

    @Test fun `admitted trial records its rotation and preserves step credit and intro`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 12, credits = 1)
        val context = ctx(trial = 100, cycle = 4)
        val trial = SprintRules.timeTrial(100, CardioActivity.TRACK_RUN)
        assertEquals(trial, opening(rule, context))
        for (pain in listOf(false, true)) {
            assertEquals(CardioOutcome(sprintBlock(RunSession.ACCELERATION, 12), rule.copy(sprintTrialCycle = 4), CardioChange.Repeat),
                finish(rule, context = context, pain = pain, block = trial))
        }
        val intro = CardioRule(null, RunSession.ACCELERATION, intro = 1)
        assertEquals(sprintBlock(RunSession.ACCELERATION, 0), opening(intro, context))
    }

    @Test fun `short trials have two reps and full acceleration recovery`() {
        for ((metres, recovery) in listOf(37 to 240, 60 to 360)) {
            val block = SprintRules.timeTrial(metres, CardioActivity.TRACK_RUN)
            assertEquals(listOf(metres, metres), reps(block))
            assertEquals(Measure.Time(recovery), block.sets[2].end)
        }
        for (metres in listOf(100, 200, 400)) assertEquals(listOf(metres), reps(SprintRules.timeTrial(metres, CardioActivity.TRACK_RUN)))
    }

    @Test fun `tempo never restarts and a trial substitute leaves B rule untouched`() {
        val tempo = CardioRule(null, RunSession.SPRINT_TEMPO, steps = 1, credits = 1)
        assertEquals(sprintBlock(RunSession.SPRINT_TEMPO, 1), opening(tempo, ctx(layoff = true)))
        assertEquals(tempo.copy(steps = 2, credits = 0, stepCycle = 0),
            finish(tempo, CardioEffort.GOOD, ctx(layoff = true)).rule)
        val b = CardioRule(null, RunSession.SPRINT_ENDURANCE, steps = 4, credits = 1)
        val context = ctx(tempo = true)
        val substitute = sprintBlock(RunSession.SPRINT_TEMPO, 0)
        assertEquals(substitute, opening(b, context))
        assertEquals(CardioOutcome(sprintBlock(RunSession.SPRINT_ENDURANCE, 4), b, CardioChange.Repeat),
            finish(b, context = context, block = substitute))
    }

    @Test fun `range tops hold and sprint days bypass the run cap and require context`() {
        for (session in RunSession.entries.filter { it.sprint }) {
            val rule = CardioRule(null, session, steps = requireNotNull(RunRange.of(session)).top)
            val block = sprintBlock(session, rule.steps)
            assertEquals(CardioOutcome(block, rule, CardioChange.Repeat), finish(rule, CardioEffort.GOOD))
            for (runCap in listOf(true, false)) {
                assertEquals(block, Progression.cardioAtStart(block, rule, CardioHistory(longest = RecentMaxima(100, 30)),
                    PlanGoal.Sprint(100), ctx().copy(runCap = runCap)))
            }
            val missing = Progression.CardioContext(1_000, null, 0)
            assertFailsWith<IllegalArgumentException> { opening(rule, missing) }
            assertFailsWith<IllegalArgumentException> { finish(rule, context = missing) }
        }
    }

    @Test fun `rules reject sprint markers on run days and invalid sprint state`() {
        val invalid = listOf<() -> CardioRule>(
            { CardioRule(null, RunSession.ACCELERATION, intro = 4) },
            { CardioRule(null, RunSession.ACCELERATION, intro = -1) },
            { CardioRule(null, RunSession.ACCELERATION, steps = 1, intro = 1) },
            { CardioRule(null, RunSession.ACCELERATION, credits = 1, intro = 1) },
            { CardioRule(null, RunSession.THRESHOLD, intro = 1) },
            { CardioRule(3, intro = 1) },
            { CardioRule(null, RunSession.ACCELERATION, stepCycle = -1) },
            { CardioRule(null, RunSession.THRESHOLD, stepCycle = 0) },
            { CardioRule(null, RunSession.ACCELERATION, sprintTrialCycle = -1) },
            { CardioRule(null, RunSession.SPRINT_ENDURANCE, sprintTrialCycle = 0) },
            { CardioRule(null, RunSession.ACCELERATION, alternate = RunSession.MAX_VELOCITY) },
            { CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.ACCELERATION) },
        )
        for (make in invalid) assertFailsWith<IllegalArgumentException> { make() }
        assertEquals(0, CardioRule(null, RunSession.SPRINT_TEMPO, stepCycle = 0).stepCycle)
    }

    @Test fun `sprint kinds are not quality`() {
        assertEquals(listOf(RunSession.THRESHOLD, RunSession.VO2MAX, RunSession.RACE_PACE, RunSession.TEST_INTERVALS,
            RunSession.TEMPO, RunSession.SHARPENING), RunSession.entries.filter { it.quality })
        assertEquals(listOf(RunSession.ACCELERATION, RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE,
            RunSession.SPRINT_TEMPO), RunSession.entries.filter { it.sprint })
    }
}
