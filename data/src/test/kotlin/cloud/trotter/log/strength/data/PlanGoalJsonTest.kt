package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertFailsWith

class PlanGoalJsonTest {
    @Test fun `both goal types accept absent dates and dates from 2000 through 2100`() {
        for (day in listOf(null, LocalDate.of(2000, 1, 1).toEpochDay(), LocalDate.of(2100, 12, 31).toEpochDay())) {
            val goals = listOf(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960, day),
                PlanGoal.Shuttle("PACER", 52, byEpochDay = day))
            for (goal in goals) {
                val encoded = PlanGoalJson.encode(goal)
                PlanGoalJson.validate(encoded)
                assertEquals(goal, PlanGoalJson.decode(encoded))
            }
        }
    }

    @Test fun `both goal types reject dates outside 2000 through 2100`() {
        for (day in listOf(LocalDate.of(1999, 12, 31).toEpochDay(), LocalDate.of(2101, 1, 1).toEpochDay(),
            0L, LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay(),
            Long.MIN_VALUE, Long.MAX_VALUE)) {
            val goals = listOf(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960, day),
                PlanGoal.Shuttle("PACER", 52, byEpochDay = day))
            for (goal in goals) {
                val encoded = PlanGoalJson.encode(goal)
                assertNull(PlanGoalJson.decode(encoded))
                assertFailsWith<IllegalArgumentException>(encoded) { PlanGoalJson.validate(encoded) }
            }
        }
    }

    @Test fun `both goal types round trip with dates and attempts`() {
        val goals = listOf(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960, 21000),
            PlanGoal.Shuttle("PACER", 52, listOf(ShuttleAttempt(1234, 42)), 21001))
        for (goal in goals) {
            val encoded = PlanGoalJson.encode(goal)
            PlanGoalJson.validate(encoded)
            assertEquals(goal, PlanGoalJson.decode(encoded))
        }
        assertEquals(PlanGoal.Shuttle("HAMR", 50), PlanGoalJson.decode(PlanGoalJson.encode(PlanGoal.Shuttle("HAMR", 50))))
    }

    @Test fun `invalid goals decode as null and validation rejects them`() {
        val timed = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960))
        val shuttle = PlanGoalJson.encode(PlanGoal.Shuttle("PACER", 52))
        val invalid = listOf("junk", "{}", timed.replace("timed", "unknown"), timed.replace("3219", "0"),
            timed.replace("ROAD_RUN", "UNKNOWN"), timed.replace("960", "0"),
            shuttle.replace("PACER", "x".repeat(31)), shuttle.replace("52", "1000"),
            """{"type":"shuttle","protocol":"PACER","targetShuttles":52,"attempts":[{"completedAt":1,"shuttles":1000}]}""")
        assertNull(PlanGoalJson.decode(null))
        for (json in invalid) {
            assertNull(PlanGoalJson.decode(json))
            assertFailsWith<IllegalArgumentException>(json) { PlanGoalJson.validate(json) }
        }
    }
}
