package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.WeekLoad
import cloud.trotter.log.strength.domain.progression.plannedSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunReplanTest {
    private val ladderAnswers = run(RunGoal.FIVE_TO_TEN_K, days = 6, experience = RunExperience.NEW)
    private fun ladder() = Planner.plan(ladderAnswers).program.let { program ->
        program.copy(days = program.days.map { day ->
            if (day.cardioRule?.ladderLevel == null) day else day.copy(cardioRule = CardioRule(null),
                cardioBlock = RunWalkLadder.block(12, CardioActivity.ROAD_RUN))
        })
    }
    private fun week(total: Int, longest: Int = 1800) = WeekLoad(RecentMaxima(null, total), RecentMaxima(null, longest))
    private fun nextLadder(stored: Program = ladder(), total: Int = 4500) =
        RunReplan.next(stored, ladderAnswers, RunPhase.LADDER, known(null, 1800), TODAY, week(total), null)
    private fun day(program: Program, id: String) = program.days.single { it.id == id }
    private fun end(day: ProgramDay) = requireNotNull(day.cardioToRun).sets.first { it.role == CardioRole.WORK }.end
    private fun planned(program: Program) = program.rotationDays.sumOf { it.cardioToRun?.let { b -> plannedSeconds(b) } ?: 0 }
    private val case30Answers = run(RunGoal.MARATHON, experience = RunExperience.NEW,
        runAnswers = RunAnswers(RunGoal.MARATHON, RunExperience.NEW, dateEpochDay = TODAY + 210))
    private fun case30() = Planner.plan(case30Answers, known(5000, 1800), TODAY + 84).program.let { program ->
        program.copy(days = program.days.map { day ->
            if (day.id != "D") day else day.copy(cardioBlock = requireNotNull(day.cardioBlock).let { block ->
                block.copy(sets = block.sets.map { it.copy(end = Measure.Distance(10_371)) })
            })
        })
    }
    private fun nextCase30(stored: Program = case30()) = RunReplan.next(stored, case30Answers, RunPhase.BASE,
        known(10_371, 3733), TODAY + 175, WeekLoad(RecentMaxima(null, 7814), RecentMaxima(10_371, 3733)), 0.36)

    @Test fun `the ladder exit waits for every ladder day`() {
        val stored = ladder().let { p -> p.copy(days = p.days.map {
            if (it.id == "C") it.copy(cardioRule = CardioRule(12, credits = 1)) else it
        }) }
        assertNull(nextLadder(stored))
        val kept = stored.copy(days = stored.days.map {
            if (it.id == "C") it.copy(cardioProgress = it.cardioProgress.copy(keepToPlan = true)) else it
        })
        assertNull(RunReplan.next(kept, ladderAnswers, RunPhase.LADDER, known(30_000, 10_800), TODAY, week(20_000), null))
    }

    @Test fun `the ladder exit plans base within a fifth over the week run`() {
        val result = assertNotNull(nextLadder())
        assertEquals(RunPhase.BASE, result.phase)
        val program = result.program
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G"), program.days.map { it.id })
        for (id in listOf("A", "C", "D", "E")) assertEquals(Measure.Time(600), end(day(program, id)))
        assertEquals(Measure.Time(1200), end(day(program, "B")))
        assertEquals(CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.VO2MAX), day(program, "B").cardioRule)
        assertEquals(Measure.Time(1800), end(day(program, "F")))
        assertEquals(CardioRule(null, RunSession.LONG, longSeconds = 5400), day(program, "F").cardioRule)
        assertNull(day(program, "G").cardioToRun)
        assertEquals(5400, planned(program))
    }

    @Test fun `a sparse ladder exit waits`() {
        assertNull(nextLadder(total = 1800))
    }

    @Test fun `the re-plan runs at the first wrap whose week fits`() {
        assertNull(nextLadder(total = 1800))
        val result = assertNotNull(nextLadder(total = 5400))
        assertEquals(RunPhase.BASE, result.phase)
        assertEquals(Measure.Time(780), end(day(result.program, "A")))
        for (id in listOf("C", "D", "E")) assertEquals(Measure.Time(840), end(day(result.program, id)))
        assertEquals(Measure.Time(1200), end(day(result.program, "B")))
        assertEquals(Measure.Time(1980), end(day(result.program, "F")))
        assertEquals(6480, planned(result.program))
    }

    @Test fun `case 30 the half build opens at 11400 m through the re-plan`() {
        val stored = case30()
        assertEquals(7814, planned(stored))
        val result = assertNotNull(nextCase30(stored))
        assertEquals(RunPhase.HALF_BUILD, result.phase)
        assertEquals(Measure.Distance(11_400), end(day(result.program, "D")))
        assertEquals(CardioRule(null, RunSession.LONG, longMetres = 21_000), day(result.program, "D").cardioRule)
        assertEquals(CardioRule(null, RunSession.THRESHOLD), day(result.program, "B").cardioRule)
        for (id in listOf("A", "C")) assertEquals(Measure.Time(1440), end(day(result.program, id)))
        assertEquals(8184, planned(result.program))
    }

    @Test fun `phases move forward only`() {
        val answers = run(RunGoal.HALF_MARATHON)
        val half = Planner.plan(answers, known(12_000, 4320), TODAY).program
        assertNull(RunReplan.next(half, answers, RunPhase.HALF_BUILD, known(30_000, 10_800), TODAY, week(20_000), 0.36))
        assertNull(RunReplan.next(case30(), case30Answers, RunPhase.BASE, known(5000, 1800), TODAY, week(7814), 0.36))
    }

    @Test fun `a day kept to plan is left as stored`() {
        val stored = case30().let { p -> p.copy(days = p.days.map {
            if (it.id == "D") it.copy(cardioProgress = it.cardioProgress.copy(keepToPlan = true)) else it
        }) }
        val result = assertNotNull(nextCase30(stored))
        assertEquals(RunPhase.HALF_BUILD, result.phase)
        assertEquals(day(stored, "D"), day(result.program, "D"))
    }

    @Test fun `a kept day can make the re-plan wait`() {
        val stored = case30().let { p -> p.copy(days = p.days.map { day ->
            if (day.id != "D") day else day.copy(cardioProgress = day.cardioProgress.copy(keepToPlan = true),
                cardioBlock = requireNotNull(day.cardioBlock).let { b -> b.copy(sets = b.sets.map { it.copy(end = Measure.Time(9600)) }) })
        }) }
        assertNull(nextCase30(stored))
    }

    @Test fun `slots stay where they are and new days take none`() {
        val stored = ladder()
        val result = assertNotNull(nextLadder(stored)).program
        for (id in listOf("A", "B")) assertEquals(day(stored, id).exercises, day(result, id).exercises)
        for (id in listOf("E", "F", "G")) assertTrue(day(result, id).exercises.isEmpty())
    }
}
