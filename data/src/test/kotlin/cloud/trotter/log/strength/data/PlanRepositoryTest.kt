package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.serialization.TestGoalJson
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.LiftTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.domain.standards.TestRef
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanRepositoryTest : FinishProgressionFixture() {
    @Test fun `case 54 the date write moves both goals in one transaction`() = runTest {
        val today = LocalDate.now(clock).toEpochDay()
        val answers = SetupAnswers(branch = PlanBranch.BOTH, goal = LiftGoal.STRENGTH,
            both = BothAnswers(true, BothAnswers.Together.SAME_VISIT),
            run = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, test = FitnessTest.ONE_AND_HALF_MILE,
                goalSeconds = 780, longest = Measure.Time(1500), dateEpochDay = today + 84),
            tests = listOf(TestAnswer(TestEventGoal(TestRef("custom_pushup_window", 1, 60), 30)),
                TestAnswer(TestEventGoal(TestRef("custom_situp_window", 1, 60), 35))), testDateEpochDay = today + 84)
        val plan = repo.plans.createPlannedPlan("Test", answers)
        val initial = assertNotNull(db.planDao().plan(plan))
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 2414, 780, today + 84), PlanGoalJson.decode(initial.goalJson))
        val testGoal = assertNotNull(TestGoalJson.decode(initial.testGoalJson))
        assertEquals(answers.testGoal, testGoal)
        db.planDao().updateTestGoal(plan, TestGoalJson.encode(testGoal.copy(byEpochDay = today + 70)))
        for (date in listOf(today + 90, null, today + 20)) {
            repo.plans.setGoalDate(plan, date)
            val stored = assertNotNull(db.planDao().plan(plan))
            assertEquals(date, PlanGoalJson.decode(stored.goalJson)?.byEpochDay)
            assertEquals(date, TestGoalJson.decode(stored.testGoalJson)?.byEpochDay)
        }
        val before = assertNotNull(db.planDao().plan(plan))
        assertFailsWith<IllegalArgumentException> { repo.plans.setGoalDate(plan, PlanGoalDate.epochDays.last + 1) }
        assertEquals(before, db.planDao().plan(plan))
        val plain = repo.plans.createAuthoredPlan("Run", "Day A")
        repo.plans.setGoal(plain, PlanGoal.Timed(CardioActivity.ROAD_RUN, 2414, 780))
        repo.plans.setGoalDate(plain, today + 84)
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 2414, 780),
            PlanGoalJson.decode(db.planDao().plan(plain)!!.goalJson))
    }

    @Test fun `a test date edit keeps a 5K race goal and its date`() = runTest {
        val today = LocalDate.now(clock).toEpochDay()
        val answers = SetupAnswers(branch = PlanBranch.BOTH,
            run = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME,
                goalSeconds = 1500, longest = Measure.Time(1800), dateEpochDay = today + 84),
            tests = listOf(TestAnswer(TestEventGoal(TestRef("custom_pushup_window", 1, 60), 30))),
            testDateEpochDay = today + 70)
        val plan = repo.plans.createPlannedPlan("Race and push-ups", answers)
        val initial = assertNotNull(db.planDao().plan(plan))
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 5000, 1500, today + 84),
            PlanGoalJson.decode(initial.goalJson))
        val testGoal = assertNotNull(TestGoalJson.decode(initial.testGoalJson))
        for (date in listOf(today + 90, null, today + 20)) {
            repo.plans.setGoalDate(plan, date)
            val stored = assertNotNull(db.planDao().plan(plan))
            assertEquals(initial.goalJson, stored.goalJson)
            assertEquals(testGoal.copy(byEpochDay = date), TestGoalJson.decode(stored.testGoalJson))
        }
    }

    @Test fun `case 55 and case 61 creation writes the lift recipe and the typed goals only in settings`() = runTest {
        val lifts = listOf(LiftTest("trap_dl", 3), LiftTest("bb_bench", 5))
        val plan = repo.plans.createPlannedPlan("Test lifts", SetupAnswers(lifts = lifts),
            goalsLb = mapOf("trap_dl" to 340.0, "bb_bench" to 225.0))
        val stored = assertNotNull(db.planDao().plan(plan))
        assertEquals(mapOf("trap_dl" to 340.0, "bb_bench" to 225.0), stored.planSettings.goalsLb)
        assertEquals(lifts, SetupRecipeJson.lifts(assertNotNull(stored.recipeJson)))
        repo.plans.setPlanGoal(plan, "bb_bench", 230.0)
        assertEquals(230.0, db.planDao().plan(plan)!!.planSettings.goalsLb["bb_bench"])
        assertEquals(stored.recipeJson, db.planDao().plan(plan)!!.recipeJson)
    }
}
