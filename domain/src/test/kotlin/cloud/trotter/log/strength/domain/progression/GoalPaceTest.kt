package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalPaceTest {
    @Test
    fun `case 21 fitness test reps aim five percent faster than goal pace`() {
        val goal = PlanGoal.Timed(CardioActivity.TRACK_RUN, 3219, 960)
        assertEquals(113, GoalPace.testAimSeconds(goal, 400))
        assertEquals(227, GoalPace.testAimSeconds(goal, 800))
    }
}
