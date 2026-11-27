package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.standards.TestRef
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

class CardioJudgeTest {
    @Test fun `case 54 day A at full success keeps its 1,200 s stop`() {
        val setup = SetupAnswers(branch = PlanBranch.BOTH, goal = LiftGoal.STRENGTH,
            both = BothAnswers(true, BothAnswers.Together.SAME_VISIT),
            run = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, test = FitnessTest.ONE_AND_HALF_MILE,
                goalSeconds = 780, longest = Measure.Time(1500)),
            tests = listOf(TestAnswer(TestEventGoal(TestRef("custom_pushup_window", 1, 60), 30)),
                TestAnswer(TestEventGoal(TestRef("custom_situp_window", 1, 60), 35))))
        val history = KnownLoads.NONE.copy(longestRecent = mapOf(CardioFamily.RUN to RecentMaxima(null, 3300)),
            longestRunning = mapOf(CardioFamily.RUN to 1500),
            longestBout = mapOf(CardioFamily.RUN to RecentMaxima(null, 1500)))
        val draft = Planner.plan(setup, history)
        val day = draft.program.days.first()
        val block = assertNotNull(day.cardioBlock)
        val session = CardioSessionEntity(id = 1, planId = 7, dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = 86_400_000_000L, completedAt = 86_401_200_000L, seconds = 1200,
            stepsCompleted = 1, activity = block.activity.name, effort = "EASY")
        val sets = block.sets.map { spec -> CardioSetResult(spec, CardioSetStatus.FINISHED,
            (spec.end as Measure.Time).seconds) }
        val inputs = CardioPlan(draft.program, 0, draft.goal, false, ZoneOffset.UTC, setup)
        val outcome = assertNotNull(CardioJudge.judge(day, session, listOf(session), mapOf(session.id to sets), false, inputs)).outcome
        assertEquals(CardioChange.Repeat, outcome.change)
        assertEquals(Measure.Time(1200), outcome.block.sets.single { it.role == CardioRole.WORK }.end)
        val grown = day.copy(cardioBlock = block.copy(sets = block.sets.map { it.copy(end = Measure.Time(1800)) }))
        val grownSets = grown.cardioBlock!!.sets.map { spec -> CardioSetResult(spec, CardioSetStatus.FINISHED, 1800) }
        val kept = assertNotNull(CardioJudge.judge(grown, session, listOf(session), mapOf(session.id to grownSets), false,
            inputs.copy(program = draft.program.copy(days = draft.program.days.map { if (it.id == "A") grown else it })))).outcome
        assertEquals(Measure.Time(1800), kept.block.sets.single().end)
    }
}
