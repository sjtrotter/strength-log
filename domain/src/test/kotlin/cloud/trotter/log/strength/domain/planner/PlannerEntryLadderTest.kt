package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.progression.LoggedCardio
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerEntryLadderTest {
    private val goals = listOf(RunGoal.CARDIO_FITNESS, RunGoal.MIDDLE_DISTANCE, RunGoal.FITNESS_TEST, RunGoal.FIVE_TO_TEN_K)
    private val underGate = known(null, 1200).copy(longestRunning = mapOf(CardioFamily.RUN to 1200))
    private fun plan(answers: RunAnswers, known: KnownLoads = KnownLoads.NONE, days: Int = 3) =
        Planner.plan(run(answers.goal, days = days, runAnswers = answers), known, TODAY)
    private fun rule(draft: PlanDraft) = draft.program.days.first().cardioRule
    private fun assertLadder(draft: PlanDraft, level: Int = 1) {
        assertEquals(RunPhase.LADDER, draft.phase)
        assertEquals(listOf("A", "B", "C", "D"), draft.program.days.map { it.id })
        for (day in draft.program.days.take(3)) {
            assertEquals(RunSession.LADDER, draft.sessions[day.id])
            assertEquals(CardioRule(level), day.cardioRule)
            assertEquals(RunWalkLadder.block(level, CardioActivity.ROAD_RUN), day.cardioBlock)
        }
        assertEquals(ProgramDayKind.REST, draft.program.days.last().kind)
    }
    private fun assertNoLadder(draft: PlanDraft) = assertFalse(RunSession.LADDER in draft.sessions.values)

    @Test fun `a New runner on Just cardio fitness starts on the run walk ladder`() {
        val draft = Planner.plan(run(RunGoal.CARDIO_FITNESS, days = 3, experience = RunExperience.NEW), KnownLoads.NONE, TODAY)
        assertLadder(draft)
        assertEquals(listOf("A", "B"), draft.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        assertNull(draft.goal)
    }

    @Test fun `New runners on middle distance and field tests start on the ladder too`() {
        for ((goal, metres) in listOf(RunGoal.MIDDLE_DISTANCE to 1500, RunGoal.FITNESS_TEST to 3219)) {
            val draft = plan(RunAnswers(goal), days = 4)
            assertLadder(draft)
            assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, metres, null, null), draft.goal)
        }
    }

    @Test fun `Some runners under the gate start on the ladder on every goal`() {
        for (goal in goals) assertLadder(plan(RunAnswers(goal, RunExperience.SOME), underGate), 8)
    }

    @Test fun `Racing runners never start on the ladder`() {
        for (goal in goals) {
            val draft = plan(RunAnswers(goal, RunExperience.RACING), underGate)
            assertEquals(if (goal == RunGoal.FIVE_TO_TEN_K) RunPhase.BASE else null, draft.phase)
            assertNoLadder(draft)
        }
        val draft = plan(RunAnswers(RunGoal.MIDDLE_DISTANCE, RunExperience.RACING, RunEvent.M800,
            longest = Measure.Time(1200)), days = 4)
        assertNull(draft.phase)
        assertEquals(listOf(RunSession.EASY, RunSession.RACE_PACE, RunSession.EASY, RunSession.LONG), draft.sessions.values.toList())
        assertEquals(ProgramDayKind.REST, draft.program.days.last().kind)
        for ((id, seconds) in listOf("A" to 960, "C" to 960, "D" to 1320)) {
            assertEquals(Measure.Time(seconds), draft.program.days.single { it.id == id }.cardioBlock?.sets?.single()?.end)
        }
        assertTrue(PlanReason.RunCapped("D") in draft.reasons)
        val reps = requireNotNull(draft.program.days.single { it.id == "B" }.cardioBlock).sets
        assertEquals(List(6) { CardioSetSpec(CardioRole.WORK, Measure.Distance(400), hard = true) }, reps.filter { it.role == CardioRole.WORK })
        assertEquals(List(5) { CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)) }, reps.filter { it.role == CardioRole.RECOVERY })
    }

    @Test fun `Some and Racing runners with no log or typed longest start past the ladder`() {
        for (experience in listOf(RunExperience.SOME, RunExperience.RACING)) for (goal in goals) {
            val draft = plan(RunAnswers(goal, experience))
            assertEquals(if (goal == RunGoal.FIVE_TO_TEN_K) RunPhase.BASE else null, draft.phase)
            assertNoLadder(draft)
        }
    }

    @Test fun `a typed distance starts the ladder at a fast pace`() {
        val answers = RunAnswers(RunGoal.CARDIO_FITNESS, RunExperience.SOME, longest = Measure.Distance(4828))
        assertEquals(1158, runningSeconds(answers, KnownLoads.NONE))
        assertLadder(plan(answers), 7)
        assertEquals(CardioRule(6), rule(plan(answers.copy(runningInjury = true))))
        val logged = known(null, 900).copy(longestRunning = mapOf(CardioFamily.RUN to 900))
        assertEquals(CardioRule(5), rule(plan(answers, logged)))
    }

    @Test fun `a runner whose own running meets the gate starts past the ladder`() {
        for (longest in listOf(Measure.Time(1800), Measure.Distance(5000))) {
            val draft = plan(RunAnswers(longest = longest))
            assertNull(draft.phase)
            assertNoLadder(draft)
        }
    }

    @Test fun `a New walker rider or rower on Just cardio fitness gets no ladder`() {
        for (activity in listOf(CardioActivity.OUTDOOR_WALK, CardioActivity.STATIONARY_BIKE, CardioActivity.ROWER, CardioActivity.ELLIPTICAL)) {
            val draft = plan(RunAnswers(activity = activity))
            assertNull(draft.phase)
            assertNoLadder(draft)
        }
    }

    @Test fun `the ladder gate reads running time`() {
        val answers = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME)
        val logged = known(null, 1900).copy(longestRunning = mapOf(CardioFamily.RUN to 1300))
        assertLadder(plan(answers, logged), 8)
        assertEquals(RunPhase.BASE, plan(answers, logged.copy(longestRunning = mapOf(CardioFamily.RUN to 1800))).phase)
        assertEquals(RunPhase.BASE, plan(answers, known(null, 1900)).phase)
    }

    @Test fun `the ladder gate reads WORK metres`() {
        val answers = RunAnswers(RunGoal.FIVE_TO_TEN_K)
        val logged = known(5500, 3600).copy(longestRunning = mapOf(CardioFamily.RUN to 900),
            longestRunningMetres = mapOf(CardioFamily.RUN to 2500))
        assertLadder(plan(answers, logged), 5)
        assertEquals(RunPhase.BASE, plan(answers, logged.copy(longestRunningMetres = mapOf(CardioFamily.RUN to 5000))).phase)
    }

    @Test fun `a dated New field test counts the ladder weeks`() {
        // ladder-date: eight ladder weeks plus four event weeks put the boundary at 84 days, previously 56.
        val answers = RunAnswers(RunGoal.FITNESS_TEST, goalSeconds = 960, dateEpochDay = TODAY + 83)
        assertEquals(8, ladderWeeks(1, 3))
        assertTrue(PlanReason.DateTooClose in plan(answers).reasons)
        // ladder-date: 56 days becomes 84 so the event block fits after the ladder.
        assertFalse(PlanReason.DateTooClose in plan(answers.copy(dateEpochDay = TODAY + 84)).reasons)
    }

    @Test fun `the BASE floor on a ladder only path gives the goal's own plan`() {
        val answers = run(RunGoal.CARDIO_FITNESS, days = 3, experience = RunExperience.NEW)
        val draft = Planner.plan(answers, atLeast = RunPhase.BASE)
        assertNull(draft.phase)
        assertEquals(List(3) { RunSession.EASY }, draft.sessions.values.toList())
        assertEquals(ProgramDayKind.REST, draft.program.days.last().kind)
        assertFailsWith<IllegalArgumentException> {
            Planner.plan(answers.copy(run = answers.run.copy(activity = CardioActivity.STATIONARY_BIKE)), atLeast = RunPhase.BASE)
        }
    }

    @Test fun `P8 skips the ladder on a nonstop 22 minutes`() {
        val answers = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, test = FitnessTest.ONE_AND_HALF_MILE,
            goalSeconds = 779, recent = RecentRace(2414, 845), longest = Measure.Time(1320), dateEpochDay = TODAY + 34)
        val draft = Planner.plan(run(answers.goal, days = 4, minutes = 45, runAnswers = answers), todayEpochDay = TODAY)
        assertNull(draft.phase)
        assertEquals(listOf("A", "B", "C", "D", "E"), draft.program.days.map { it.id })
        assertEquals(mapOf("A" to RunSession.EASY, "B" to RunSession.TEST_INTERVALS,
            "C" to RunSession.EASY, "D" to RunSession.EASY), draft.sessions)
        assertEquals(ProgramDayKind.REST, draft.program.days.last().kind)
        val reps = requireNotNull(draft.program.days.single { it.id == "B" }.cardioBlock).sets
        assertEquals(List(4) { CardioSetSpec(CardioRole.WORK, Measure.Distance(400), aim = Measure.Time(123), hard = true) },
            reps.filter { it.role == CardioRole.WORK })
        assertEquals(List(3) { CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)) },
            reps.filter { it.role == CardioRole.RECOVERY })
        for (id in listOf("A", "C")) {
            val day = draft.program.days.single { it.id == id }
            assertEquals(listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))), day.cardioBlock?.sets)
            assertTrue(day.exercises.isNotEmpty())
        }
        assertEquals(listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1440))),
            draft.program.days.single { it.id == "D" }.cardioBlock?.sets)
        assertTrue(PlanReason.RunCapped("D") in draft.reasons)
        assertFalse(PlanReason.DateTooClose in draft.reasons)
        assertTrue(draft.reasons.none { it is PlanReason.LadderWeeks })
    }

    @Test fun `the short gate reads typed minutes`() {
        val answers = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, longest = Measure.Time(1199))
        assertLadder(plan(answers), 7)
        val past = plan(answers.copy(longest = Measure.Time(1200)))
        assertNull(past.phase)
        assertNoLadder(past)
    }

    @Test fun `the short gate reads typed distance`() {
        val answers = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, longest = Measure.Distance(3218))
        assertLadder(plan(answers), 3)
        val past = plan(answers.copy(longest = Measure.Distance(3219)))
        assertNull(past.phase)
        assertNoLadder(past)
    }

    @Test fun `the short gate reads the logged bout`() {
        val answers = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME)
        fun seconds(value: Int) = known(null, value).copy(longestRunning = mapOf(CardioFamily.RUN to value),
            longestBout = mapOf(CardioFamily.RUN to RecentMaxima(null, value)))
        assertLadder(plan(answers, seconds(1199)), 7)
        assertLadder(plan(answers.copy(longest = Measure.Time(1800)), seconds(1199)), 7)
        assertLadder(plan(answers.copy(longest = Measure.Time(1800)), underGate), 8)
        assertNull(plan(answers, seconds(1200)).phase)
        fun metres(value: Int) = known(value, 1000).copy(longestRunning = mapOf(CardioFamily.RUN to 1000),
            longestRunningMetres = mapOf(CardioFamily.RUN to value),
            longestBout = mapOf(CardioFamily.RUN to RecentMaxima(value, 1000)))
        assertLadder(plan(answers, metres(3218)), 6)
        assertNull(plan(answers, metres(3219)).phase)
    }

    @Test fun `ladder bouts do not clear the short gate until level 11`() {
        fun history(level: Int): KnownLoads {
            val results = RunWalkLadder.block(level, CardioActivity.ROAD_RUN).sets.map {
                FinishedRule.atEnd(CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as Measure.Time).seconds))
            }
            return CardioHistory.known(CardioFamily.RUN, listOf(LoggedCardio(100, results)), 101)
        }
        val answers = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME)
        assertLadder(plan(answers, history(10)), 10)
        assertNull(plan(answers, history(11)).phase)
    }

    @Test fun `a New tester keeps the five K gate`() {
        val answers = RunAnswers(RunGoal.FITNESS_TEST, longest = Measure.Time(1500))
        assertLadder(plan(answers), 10)
        val logged = known(null, 1500).copy(longestRunning = mapOf(CardioFamily.RUN to 1500),
            longestBout = mapOf(CardioFamily.RUN to RecentMaxima(null, 1500)))
        assertLadder(plan(answers, logged), 10)
        assertNull(plan(answers.copy(longest = Measure.Time(1800))).phase)
    }

    @Test fun `other goals keep the five K gate on a continuous run`() {
        val logged = known(null, 1500).copy(longestRunning = mapOf(CardioFamily.RUN to 1500),
            longestBout = mapOf(CardioFamily.RUN to RecentMaxima(null, 1500)))
        for (goal in listOf(RunGoal.FIVE_TO_TEN_K, RunGoal.CARDIO_FITNESS)) {
            val answers = RunAnswers(goal, RunExperience.SOME, longest = Measure.Time(1500))
            assertLadder(plan(answers), 10)
            assertLadder(plan(answers, logged), 10)
        }
    }

    @Test fun `middle distance shares the short gate`() {
        val answers = RunAnswers(RunGoal.MIDDLE_DISTANCE, RunExperience.SOME, RunEvent.M1500, longest = Measure.Time(1200))
        val past = plan(answers, days = 4)
        assertNull(past.phase)
        assertNoLadder(past)
        assertLadder(plan(answers.copy(longest = Measure.Time(1199)), days = 4), 7)
    }

    @Test fun `Racing is unchanged`() {
        val draft = plan(RunAnswers(RunGoal.FITNESS_TEST, RunExperience.RACING, longest = Measure.Time(600)))
        assertNull(draft.phase)
        assertNoLadder(draft)
    }

    @Test fun `a near gate tester counts ladder and event weeks`() {
        val answers = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, longest = Measure.Time(1199), dateEpochDay = TODAY + 55)
        assertTrue(PlanReason.DateTooClose in plan(answers).reasons)
        assertFalse(PlanReason.DateTooClose in plan(answers.copy(dateEpochDay = TODAY + 56)).reasons)
    }

    @Test fun `a plan past the short gate reports no date reason`() {
        val draft = plan(RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, longest = Measure.Time(1200), dateEpochDay = TODAY + 7))
        assertFalse(PlanReason.DateTooClose in draft.reasons)
        assertTrue(draft.reasons.none { it is PlanReason.LadderWeeks })
    }

    @Test fun `ladder plans report their length`() {
        val answers = run(RunGoal.MARATHON, days = 3, experience = RunExperience.NEW)
        assertTrue(PlanReason.LadderWeeks(8) in Planner.plan(answers).reasons)
        val half = plan(RunAnswers(RunGoal.HALF_MARATHON, RunExperience.SOME),
            known(null, 1000).copy(longestRunning = mapOf(CardioFamily.RUN to 1000)), days = 4)
        assertTrue(PlanReason.LadderWeeks(5) in half.reasons)
        assertTrue(plan(RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME), known(8050, 2900))
            .reasons.none { it is PlanReason.LadderWeeks })
        for (day in Planner.plan(answers).program.days) {
            assertTrue(requireNotNull(Planner.day(answers, day.id)).reasons.none { it is PlanReason.LadderWeeks })
        }
    }

    @Test fun `phased dates are unchanged`() {
        val first = RunAnswers(RunGoal.FIRST_5K, RunExperience.NEW, dateEpochDay = TODAY + 56)
        assertFalse(PlanReason.DateTooClose in plan(first).reasons)
        assertTrue(PlanReason.DateTooClose in plan(first.copy(dateEpochDay = TODAY + 55)).reasons)
        val half = RunAnswers(RunGoal.HALF_MARATHON, RunExperience.SOME, dateEpochDay = TODAY + 84)
        assertTrue(PlanReason.DateTooClose in plan(half,
            known(null, 1000).copy(longestRunning = mapOf(CardioFamily.RUN to 1000)), days = 4).reasons)
    }

}
