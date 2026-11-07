package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalProgressTest {
    private val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)

    private fun result(
        end: Measure = Measure.Distance(3219),
        seconds: Int = 984,
        metres: Int? = null,
        role: CardioRole = CardioRole.WORK,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
    ) = CardioSetResult(CardioSetSpec(role, end), status, seconds, metres, metres?.let { DistanceSource.MANUAL })

    private fun best(vararg results: CardioSetResult) =
        GoalProgress.bestTimed(goal, listOf(CardioActivity.ROAD_RUN to results.toList()))

    @Test fun `under is strict`() {
        assertFalse(GoalProgress.met(goal, 960))
        assertTrue(GoalProgress.met(goal, 959))
        assertFalse(GoalProgress.met(goal, null))
    }

    @Test fun `a single distance set at the goal distance counts`() {
        assertEquals(984, best(result(Measure.Time(300), role = CardioRole.WARMUP), result(),
            result(Measure.Time(300), role = CardioRole.COOLDOWN)))
    }

    @Test fun `a longer open-ended run counts with its whole time`() {
        assertEquals(990, best(result(Measure.Time(1800), seconds = 990, metres = 3300)))
        assertEquals(990, best(result(Measure.Distance(4000), seconds = 990)))
    }

    @Test fun `too short does not count`() {
        assertNull(best(result(metres = 3000)))
        assertNull(best(result(Measure.Time(1800))))
        assertNull(best(result(Measure.Distance(3000))))
    }

    @Test fun `intervals never count`() {
        assertNull(best(result(Measure.Time(300), role = CardioRole.WARMUP), result(seconds = 900),
            result(Measure.Time(60), role = CardioRole.RECOVERY), result(Measure.Distance(400), 90)))
        assertNull(best(result(seconds = 900), result(Measure.Distance(400), 0, status = CardioSetStatus.SKIPPED)))
    }

    @Test fun `another activity does not count`() {
        assertNull(GoalProgress.bestTimed(goal, listOf(CardioActivity.TRACK_RUN to listOf(result()))))
        assertNull(GoalProgress.bestTimed(goal, listOf(null to listOf(result()))))
    }

    @Test fun `shortened does not count`() {
        assertNull(best(result(metres = 3219, status = CardioSetStatus.SHORTENED)))
        assertNull(best(result(status = CardioSetStatus.SKIPPED)))
        assertNull(best(result(seconds = 0)))
        assertNull(best(result(role = CardioRole.WARMUP)))
    }

    @Test fun `best is the fastest`() {
        assertEquals(984, GoalProgress.bestTimed(goal, listOf(1000, 984, 990).map {
            CardioActivity.ROAD_RUN to listOf(result(seconds = it))
        }))
        assertNull(GoalProgress.bestTimed(goal, emptyList()))
    }

    @Test fun `shuttles`() {
        val goal = PlanGoal.Shuttle("PACER", 52, listOf(ShuttleAttempt(1, 40), ShuttleAttempt(2, 42)))
        assertEquals(42, GoalProgress.bestShuttles(goal))
        assertFalse(GoalProgress.met(goal, 42))
        assertFalse(GoalProgress.met(goal, null))
        val met = goal.copy(attempts = goal.attempts + ShuttleAttempt(3, 55))
        assertTrue(GoalProgress.met(met, GoalProgress.bestShuttles(met)))
        assertTrue(GoalProgress.met(goal, 52))
        assertNull(GoalProgress.bestShuttles(goal.copy(attempts = emptyList())))
    }

    @Test fun `goal and attempt bounds are enforced`() {
        for (metres in listOf(0, 200001)) assertFailsWith<IllegalArgumentException> { goal.copy(metres = metres) }
        for (seconds in listOf(0, 86401)) assertFailsWith<IllegalArgumentException> { goal.copy(underSeconds = seconds) }
        for (protocol in listOf("", " ", "x".repeat(31))) {
            assertFailsWith<IllegalArgumentException> { PlanGoal.Shuttle(protocol, 52) }
        }
        for (count in listOf(0, 1000)) assertFailsWith<IllegalArgumentException> { PlanGoal.Shuttle("PACER", count) }
        for (count in listOf(-1, 1000)) assertFailsWith<IllegalArgumentException> { ShuttleAttempt(1, count) }
        assertEquals(0, ShuttleAttempt(1, 0).shuttles)
    }
}
