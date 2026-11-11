package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioProgress
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.progression.CardioRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardioOpeningTest {
    private val plan = PlanEntity(id = 7, name = "Running", origin = "AUTHORED", position = 0, createdAt = NOW - DAY)
    private val half = PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_097, 7200, byEpochDay = TODAY + 12)

    private fun distance(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres))
    private fun block(metres: Int) = CardioBlock(CardioActivity.ROAD_RUN, listOf(distance(metres)))
    private fun day(metres: Int) = ProgramDay("A", "Run", "", emptyList(), cardioBlock = block(metres))
    private fun session(id: Long, completedAt: Long, activity: CardioActivity = CardioActivity.ROAD_RUN) =
        CardioSessionEntity(id = id, planId = null, dayId = null, mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = completedAt - 2_900_000, completedAt = completedAt, seconds = 2900, stepsCompleted = 1,
            activity = activity.name)
    private fun result(metres: Int) = listOf(CardioSetResult(distance(metres), CardioSetStatus.FINISHED, 2900, metres, DistanceSource.MANUAL))
    private fun opening(day: ProgramDay, sessions: List<CardioSessionEntity> = emptyList(),
                        sets: Map<Long, List<CardioSetResult>> = emptyMap(), plan: PlanEntity = this.plan) =
        CardioJudge.opening(day, plan, sessions, sets, NOW, TODAY)

    @Test fun `case 17 caps the stored long run by the family's last 30 days`() {
        val trail = session(1, NOW - 2 * DAY, CardioActivity.TRAIL_RUN).copy(planId = 99)
        assertEquals(block(8800), opening(day(10000), listOf(trail), mapOf(1L to result(8050))))
        val sessions = listOf(session(4, NOW + DAY), trail, session(3, NOW - DAY, CardioActivity.ROWER),
            session(2, NOW - 31 * DAY).copy(planId = plan.id))
        val sets = mapOf(1L to result(8050), 2L to result(9000), 3L to result(20000), 4L to result(12000))
        assertEquals(block(8800), opening(day(10000), sessions, sets))
    }

    @Test fun `the cap climbs back to the stored block`() {
        val sessions = listOf(session(1, NOW - 2 * DAY, CardioActivity.TRAIL_RUN), session(2, NOW - DAY))
        val sets = mapOf(1L to result(8050), 2L to result(8800))
        assertEquals(block(9600), opening(day(10000), sessions, sets))
        assertEquals(block(10000), opening(day(10000), sessions + session(3, NOW - 1000),
            sets + (3L to result(9600))))
    }

    @Test fun `case 19 tapers from the plan goal`() {
        val day = day(18000)
        assertEquals(block(12600), opening(day, plan = plan.copy(goalJson = PlanGoalJson.encode(half))))
        assertEquals(block(9000), opening(day, plan = plan.copy(goalJson = PlanGoalJson.encode(half.copy(byEpochDay = TODAY + 5)))))
        assertEquals(block(18000), opening(day, plan = plan.copy(goalJson = PlanGoalJson.encode(half.copy(byEpochDay = TODAY + 15)))))
        assertEquals(block(18000), opening(day, plan = plan.copy(goalJson = null)))
        val rower = day.copy(cardioBlock = block(18000).copy(activity = CardioActivity.ROWER))
        assertEquals(block(12600).copy(activity = CardioActivity.ROWER),
            opening(rower, plan = plan.copy(goalJson = PlanGoalJson.encode(half))))
    }

    @Test fun `the taper comes before the cap`() {
        assertEquals(block(8800), opening(day(18000), listOf(session(1, NOW - 2 * DAY)), mapOf(1L to result(8050)),
            plan.copy(goalJson = PlanGoalJson.encode(half))))
    }

    @Test fun `plan mode and the ladder open as written`() {
        val day = day(10000)
        val sessions = listOf(session(1, NOW - 2 * DAY))
        val sets = mapOf(1L to result(8050))
        val kept = day.copy(cardioProgress = CardioProgress(keepToPlan = true))
        assertEquals(block(10000), opening(kept, sessions, sets))
        assertEquals(block(10000), opening(kept, sessions, sets, plan.copy(goalJson = PlanGoalJson.encode(half))))
        assertEquals(block(10000), opening(day.copy(cardioRule = CardioRule(ladderLevel = 3)), sessions, sets,
            plan.copy(goalJson = PlanGoalJson.encode(half))))
        assertNull(opening(day.copy(cardioBlock = null), sessions, sets))
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val TODAY = 20_833L
        private const val DAY = 86_400_000L
    }
}
