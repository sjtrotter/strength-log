package cloud.trotter.log.strength.domain.model

import cloud.trotter.log.strength.domain.standards.TestRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TestGoalTest {
    @Test
    fun `a test goal needs distinct events and a valid date`() {
        val hrp = TestEventGoal(TestRef("aft_hrp", 1, 120), 40)
        val plank = TestEventGoal(TestRef("aft_plank", 1), 220)
        assertFailsWith<IllegalArgumentException> { TestGoal(listOf(hrp, hrp.copy(required = 45))) }
        assertFailsWith<IllegalArgumentException> { TestGoal(emptyList()) }
        for (date in listOf(PlanGoalDate.epochDays.first - 1, PlanGoalDate.epochDays.last + 1)) {
            assertFailsWith<IllegalArgumentException> { TestGoal(listOf(hrp), date) }
        }
        assertFailsWith<IllegalArgumentException> { TestEventGoal(hrp.ref, 0) }
        val goal = TestGoal(listOf(hrp, plank), PlanGoalDate.epochDays.first)
        assertEquals(hrp, goal.forRef(hrp.ref))
        assertEquals(plank, goal.forRef(plank.ref))
        assertNull(goal.forRef(hrp.ref.copy(version = 2)))
    }
}
