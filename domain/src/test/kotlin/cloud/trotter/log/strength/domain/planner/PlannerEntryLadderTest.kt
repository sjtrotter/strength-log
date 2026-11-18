package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDayKind
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
        val answers = RunAnswers(RunGoal.FITNESS_TEST, goalSeconds = 960, dateEpochDay = TODAY + 49)
        assertEquals(8, ladderWeeks(1, 3))
        assertTrue(PlanReason.DateTooClose in plan(answers).reasons)
        assertFalse(PlanReason.DateTooClose in plan(answers.copy(dateEpochDay = TODAY + 56)).reasons)
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
}
