package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.standards.TestRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TestGoalJsonTest {
    @Test
    fun `a test goal round trips`() {
        val goal = TestGoal(listOf(
            TestEventGoal(TestRef("aft_hrp", 1, 120), 40),
            TestEventGoal(TestRef("aft_plank", 1), 220),
        ), 20_400)
        val json = TestGoalJson.encode(goal)
        TestGoalJson.validate(json)
        assertEquals(goal, TestGoalJson.decode(json))
        assertNull(TestGoalJson.decode(null))
        assertNull(TestGoalJson.decode("{"))
        assertFailsWith<IllegalArgumentException> { TestGoalJson.validate("""{"v":2,"events":[]}""") }
        assertNull(TestGoalJson.decode(json.replace("\"events\"", "\"v\":2,\"events\"")))
    }
}
