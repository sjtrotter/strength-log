package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.progression.RunSession
import kotlin.test.Test
import kotlin.test.assertEquals

class RunAnswersTest {
    @Test fun `fitness test aims require the target used by that test`() {
        for (test in FitnessTest.entries) for (seconds in listOf(null, 960)) for (score in listOf(null, 50)) {
            val run = RunAnswers(goal = RunGoal.FITNESS_TEST, test = test, goalSeconds = seconds, testScore = score)
            val expected = when (test) {
                FitnessTest.TWO_MILE, FitnessTest.ONE_AND_HALF_MILE -> seconds != null
                FitnessTest.COOPER -> score != null
                FitnessTest.PACER, FitnessTest.HAMR -> false
            }
            assertEquals(expected, run.hasTestAim())
            val block = runBlock(RunSession.TEST_INTERVALS, run, phase = null, anchor = null,
                easyRunSeconds = 1200, ladderLevel = 0)
            assertEquals(expected, block.sets.filter { it.role == CardioRole.WORK }.all { it.aim != null })
        }
    }
}
