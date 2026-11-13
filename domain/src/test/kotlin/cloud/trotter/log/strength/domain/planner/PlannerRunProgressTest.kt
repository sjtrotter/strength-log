package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.CardioOutcome
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoggedCardio
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RunSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlannerRunProgressTest {
    private val ctx = Progression.CardioContext(1000, ProgressMeasure.DISTANCE, 5)
    private fun plan(answers: SetupAnswers, known: KnownLoads = KnownLoads.NONE, today: Long = TODAY) =
        Planner.plan(answers, known, today)
    private fun day(draft: PlanDraft, id: String) = draft.program.days.single { it.id == id }
    private fun block(draft: PlanDraft, id: String) = requireNotNull(day(draft, id).cardioBlock)
    private fun rule(draft: PlanDraft, id: String) = requireNotNull(day(draft, id).cardioRule)
    private fun end(draft: PlanDraft, id: String) = block(draft, id).sets.first { it.role == CardioRole.WORK }.end
    private fun middle() = run(RunGoal.MIDDLE_DISTANCE, runAnswers = RunAnswers(RunGoal.MIDDLE_DISTANCE,
        RunExperience.SOME, RunEvent.M800, dateEpochDay = TODAY + 30, goalSeconds = 120))
    private fun finished(block: CardioBlock, pace: Double? = null) = block.sets.map { spec ->
        val metres = (spec.end as? Measure.Distance)?.metres
        val seconds = ((spec.end as? Measure.Time) ?: (spec.aim as? Measure.Time))?.seconds
            ?: (requireNotNull(metres) * requireNotNull(pace)).toInt()
        FinishedRule.atEnd(CardioSetResult(spec, CardioSetStatus.FINISHED, seconds, metres, metres?.let { DistanceSource.MANUAL }))
    }

    @Test fun `creation rules follow session phase alternation and the stored long form`() {
        val half = plan(run(RunGoal.HALF_MARATHON), known(12_000, 4200))
        for (id in listOf("A", "C")) assertEquals(CardioRule(null, RunSession.EASY), rule(half, id))
        assertEquals(CardioRule(null, RunSession.THRESHOLD), rule(half, "B"))
        assertEquals(CardioRule(null, RunSession.LONG, longMetres = 21_000), rule(half, "D"))
        val base = plan(run(RunGoal.FIVE_TO_TEN_K, days = 3), known(8050, 2900))
        assertEquals(CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.VO2MAX), rule(base, "B"))
        assertEquals(CardioRule(null, RunSession.LONG, longSeconds = 5400), rule(base, "C"))
        val timed = plan(run(RunGoal.FIVE_TO_TEN_K, days = 3,
            runAnswers = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME, longest = Measure.Time(2900))))
        assertEquals(Measure.Time(3180), end(timed, "C"))
        assertEquals(rule(base, "C"), rule(timed, "C"))
        val five = plan(run(RunGoal.FIVE_TO_TEN_K, days = 5), known(8050, 2900))
        assertEquals(CardioRule(null, RunSession.THRESHOLD), rule(five, "B"))
        assertEquals(CardioRule(null, RunSession.VO2MAX), rule(five, "D"))
        val middle = plan(middle())
        assertEquals(RunSession.SHARPENING, middle.sessions["B"])
        assertEquals(CardioRule(null, RunSession.SHARPENING), rule(middle, "B"))
        assertEquals(CardioRule(null, RunSession.LONG, longSeconds = 4500), rule(middle, "D"))
        val ultra = run(RunGoal.ULTRA)
        val fifty = plan(ultra, known(30_000, 10_800))
        assertEquals(CardioRule(null, RunSession.LONG, longMetres = 40_000, longSeconds = 12_600), rule(fifty, "D"))
        val hundred = plan(ultra.copy(run = ultra.run.copy(event = RunEvent.K100)), known(30_000, 10_800))
        assertEquals(Measure.Time(11_880), end(hundred, "D"))
        assertEquals(CardioRule(null, RunSession.LONG, longSeconds = 21_600), rule(hundred, "D"))
        val miles = plan(ultra.copy(run = ultra.run.copy(event = RunEvent.MI50)), known(50_000, 20_000))
        assertEquals(CardioRule(null, RunSession.LONG, longMetres = 64_374), rule(miles, "D"))
        val test = plan(run(RunGoal.FITNESS_TEST, days = 3))
        assertEquals(CardioRule(null, RunSession.TEST_INTERVALS), rule(test, "B"))
        val ladder = plan(run(RunGoal.MARATHON, experience = RunExperience.NEW))
        for (day in ladder.program.cardioDays) assertEquals(CardioRule(1), day.cardioRule)
        val both = plan(run(RunGoal.FIVE_TO_TEN_K, days = 3).copy(branch = PlanBranch.BOTH,
            goal = LiftGoal.GENERAL, both = BothAnswers(false, BothAnswers.Together.SAME_VISIT)), known(8050, 2900))
        for (id in listOf("A", "C")) assertEquals(CardioRule(null, RunSession.EASY), rule(both, id))
        assertEquals(CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.VO2MAX), rule(both, "B"))
    }

    @Test fun `every run and lift first same visit block carries its final session rule`() {
        val variants = RunGoal.entries.flatMap { goal ->
            when (goal) {
                RunGoal.FITNESS_TEST -> FitnessTest.entries.map { RunAnswers(goal, test = it) }
                RunGoal.CARDIO_FITNESS -> listOf(RunAnswers(goal))
                else -> goal.events.map { RunAnswers(goal, event = it) }
            }
        }
        for (variant in variants) for (days in 1..6) for (experience in RunExperience.entries) {
            for (history in listOf(KnownLoads.NONE, known(32_000, 12_000))) for (branch in listOf(PlanBranch.RUN, PlanBranch.BOTH)) {
                val runAnswers = variant.copy(experience = experience)
                val answers = run(runAnswers.goal, days, 60, experience, runAnswers).copy(branch = branch,
                    goal = LiftGoal.GENERAL, both = BothAnswers(false, BothAnswers.Together.SAME_VISIT))
                val draft = plan(answers, history)
                val label = "$runAnswers $days $branch $history"
                for (day in draft.program.days.filter { it.cardioBlock != null }) {
                    val rule = assertNotNull(day.cardioRule, label)
                    val session = draft.sessions.getValue(day.id)
                    assertEquals(session == RunSession.LADDER, rule.ladderLevel != null, label)
                    if (session == RunSession.LADDER) assertEquals(CardioRule(rule.ladderLevel), rule, label)
                    else assertEquals(session, rule.session, label)
                    if (rule.longMetres != null) {
                        assertEquals(RunSession.LONG, session, label)
                        assertTrue(requireNotNull(day.cardioBlock).sets.first { it.role == CardioRole.WORK }.end is Measure.Distance, label)
                    }
                    if (rule.alternate != null) {
                        assertEquals(RunSession.THRESHOLD, session, label)
                        assertEquals(RunSession.VO2MAX, rule.alternate, label)
                        assertEquals(RunPhase.BASE, draft.phase, label)
                        assertFalse(RunSession.VO2MAX in draft.sessions.values, label)
                    }
                }
            }
        }
    }

    @Test fun `case 29 planner sharpening steps to four three hundred then four four hundred metre reps`() {
        val draft = plan(middle())
        var opening = block(draft, "B")
        var rule = rule(draft, "B")
        val roomy = CardioHistory(longest = RecentMaxima(null, 10_000))
        for (session in 1..4) {
            val next = Progression.cardioAfterSession(opening, rule, finished(opening), CardioEffort.HARD, roomy, ctx)
            val steps = session / 2
            val work = next.block.sets.filter { it.role == CardioRole.WORK }
            assertEquals(if (steps == 0) 3 else 4, work.size)
            assertTrue(work.all { it.end == Measure.Distance(if (steps < 2) 300 else 400) })
            assertTrue(work.all { it.aim == Measure.Time(if (steps < 2) 45 else 60) })
            assertEquals(if (session % 2 == 0) CardioChange.Step else CardioChange.Repeat, next.change)
            rule = assertNotNull(next.rule)
            opening = next.block
        }
    }

    @Test fun `case 30 ladder base half and marathon progress through replanning from the log`() {
        val answers = run(RunGoal.MARATHON, experience = RunExperience.NEW,
            runAnswers = RunAnswers(RunGoal.MARATHON, RunExperience.NEW, dateEpochDay = TODAY + 210))
        val ladder = plan(answers)
        assertEquals(RunPhase.LADDER, ladder.phase)
        assertEquals(CardioRule(1), rule(ladder, "A"))
        var ladderBlock = block(ladder, "A")
        var ladderRule = rule(ladder, "A")
        repeat(12) { index ->
            val next = Progression.cardioAfterSession(ladderBlock, ladderRule, finished(ladderBlock), CardioEffort.EASY,
                CardioHistory(), ctx)
            assertEquals(if (index == 11) CardioChange.LadderDone else CardioChange.LevelTo(index + 2), next.change)
            ladderBlock = next.block
            ladderRule = assertNotNull(next.rule)
        }
        assertEquals(CardioRule(null), ladderRule)
        fun secs(metres: Int) = metres * 36 / 100
        val log = mutableListOf<LoggedCardio>()
        fun logKnown(opening: CardioBlock): KnownLoads {
            log += LoggedCardio(log.size.toLong(), finished(opening, 0.36))
            val history = CardioHistory.of(log, log.size.toLong())
            return KnownLoads.NONE.copy(longestRecent = mapOf(CardioFamily.RUN to requireNotNull(history.longest)))
        }
        fun knownOf(metres: Int) = known(metres, secs(metres))
        val base = plan(answers, knownOf(5000), TODAY + 84)
        assertEquals(RunPhase.BASE, base.phase)
        assertEquals(Measure.Distance(5500), end(base, "D"))
        assertEquals(CardioRule(null, RunSession.LONG, longSeconds = 5400), rule(base, "D"))
        assertEquals(CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.VO2MAX), rule(base, "B"))
        fun climb(draft: PlanDraft, metres: List<Int>, date: Long, before: RunPhase, after: RunPhase): CardioBlock {
            var opening = block(draft, "D")
            val rule = rule(draft, "D")
            for ((index, expected) in metres.withIndex()) {
                val previous = (opening.sets.single().end as Measure.Distance).metres
                val next = Progression.cardioAfterSession(opening, rule, finished(opening, 0.36), CardioEffort.EASY,
                    CardioHistory(longest = RecentMaxima(previous, secs(previous)), secondsPerMetre = 0.36), ctx)
                assertEquals(CardioOutcome(next.block, rule, CardioChange.Increase(ProgressMeasure.DISTANCE, false)), next)
                assertEquals(Measure.Distance(expected), next.block.sets.single().end)
                opening = next.block
                val phase = plan(answers, logKnown(opening), date).phase
                assertEquals(if (index == metres.lastIndex) after else before, phase)
            }
            return opening
        }
        climb(base, listOf(5775, 6064, 6367, 6685, 7019, 7370, 7739, 8126, 8532, 8959, 9407, 9877, 10_371),
            TODAY + 84, RunPhase.BASE, RunPhase.HALF_BUILD)
        val half = plan(answers, knownOf(10_371), TODAY + 175)
        assertEquals(Measure.Distance(11_400), end(half, "D"))
        assertEquals(CardioRule(null, RunSession.LONG, longMetres = 21_000), rule(half, "D"))
        assertEquals(CardioRule(null, RunSession.THRESHOLD), rule(half, "B"))
        climb(half, listOf(11_970, 12_569, 13_197, 13_857, 14_550, 15_278, 16_042, 16_844, 17_686, 18_570, 19_499, 20_474),
            TODAY + 175, RunPhase.HALF_BUILD, RunPhase.MARATHON_BUILD)
        val marathon = plan(answers, knownOf(20_474), TODAY + 259)
        assertEquals(Measure.Distance(22_500), end(marathon, "D"))
        assertEquals(CardioRule(null, RunSession.LONG, longMetres = 32_000), rule(marathon, "D"))
        val last = climb(marathon, listOf(23_625, 24_806, 26_046, 27_348, 28_715, 30_151, 31_659, 32_000),
            TODAY + 259, RunPhase.MARATHON_BUILD, RunPhase.MARATHON_BUILD)
        assertEquals(CardioOutcome(last, rule(marathon, "D"), CardioChange.Repeat),
            Progression.cardioAfterSession(last, rule(marathon, "D"), finished(last, 0.36), CardioEffort.EASY,
                CardioHistory(longest = RecentMaxima(32_000, secs(32_000)), secondsPerMetre = 0.36), ctx))
    }

    @Test fun `only the half build gate reads the thirty kilometre or three hour week`() {
        val answers = run(RunGoal.MARATHON)
        val history = known(16_000, 5760)
        fun withWeek(metres: Int?, seconds: Int?) = history.copy(longestWeek = mapOf(CardioFamily.RUN to RecentMaxima(metres, seconds)))
        assertEquals(RunPhase.MARATHON_BUILD, plan(answers, withWeek(30_000, 10_800)).phase)
        assertEquals(RunPhase.HALF_BUILD, plan(answers, withWeek(29_999, 10_799)).phase)
        assertEquals(RunPhase.MARATHON_BUILD, plan(answers, withWeek(null, 10_800)).phase)
        assertEquals(RunPhase.HALF_BUILD, plan(run(RunGoal.HALF_MARATHON), withWeek(30_000, 10_800)).phase)
        val anchor = RecentMaxima(1000, null)
        val week = RecentMaxima(30_000, null)
        assertTrue(RunPhase.HALF_BUILD.met(anchor, false, week))
        assertFalse(RunPhase.BASE.met(anchor, false, week))
    }
}
