package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.SprintAttempt
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class GoalProgressSprintTest {
    private fun attempt(
        at: Long,
        centis: Int,
        method: TimingMethod,
        start: StartProtocol = StartProtocol.THREE_POINT,
    ) = SprintAttempt(at, SprintTime(centis, method, start))

    @Test fun `best sprint uses the newest attempts series`() {
        val phone = attempt(1, 452, TimingMethod.PHONE)
        val hand = attempt(2, 440, TimingMethod.HAND)
        val faster = attempt(3, 445, TimingMethod.PHONE)
        assertSame(faster, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(phone, hand, faster))))
        assertSame(hand, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(phone, hand))))
    }

    @Test fun `same hand times leave the standing best`() {
        val standing = attempt(1, 452, TimingMethod.HAND)
        val same = attempt(2, 445, TimingMethod.HAND)
        val faster = attempt(2, 441, TimingMethod.HAND)
        assertSame(standing, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(standing, same))))
        assertSame(faster, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(standing, faster))))
    }

    @Test fun `attempts fold oldest first with the last listed newest on a timestamp tie`() {
        val standing = attempt(1, 452, TimingMethod.HAND)
        val same = attempt(2, 445, TimingMethod.HAND)
        val faster = attempt(3, 441, TimingMethod.HAND)
        assertSame(faster, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(faster, same, standing))))
        val phone = attempt(3, 430, TimingMethod.PHONE)
        assertSame(faster, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(phone, standing, faster))))
        assertSame(phone, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(standing, faster, phone))))
    }

    @Test fun `start protocols have separate goal bests`() {
        val threePoint = attempt(1, 430, TimingMethod.PHONE)
        val standing = attempt(2, 452, TimingMethod.PHONE, StartProtocol.STANDING)
        assertSame(standing, GoalProgress.bestSprint(PlanGoal.Sprint(30, attempts = listOf(threePoint, standing))))
    }

    @Test fun `a sprint goal without attempts has no best`() {
        assertNull(GoalProgress.bestSprint(PlanGoal.Sprint(30)))
    }

    @Test fun `sprint goal and time bounds are enforced`() {
        for (metres in listOf(0, 1_001)) {
            assertFailsWith<IllegalArgumentException> { PlanGoal.Sprint(metres) }
        }
        for (centis in listOf(0, SprintTime.MAX_CENTIS + 1)) {
            assertFailsWith<IllegalArgumentException> { PlanGoal.Sprint(30, targetCentis = centis) }
            assertFailsWith<IllegalArgumentException> { SprintTime(centis, TimingMethod.PHONE, StartProtocol.THREE_POINT) }
        }
        assertEquals(360_000, SprintTime(360_000, TimingMethod.PHONE, StartProtocol.THREE_POINT).centis)
        assertEquals(1_000, PlanGoal.Sprint(1_000).metres)
    }

    @Test fun `timed sprint reps leave the Timed goal's best`() {
        fun rep(metres: Int, centis: Int, method: TimingMethod) = CardioSetResult(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true),
            CardioSetStatus.FINISHED, (centis + 99) / 100,
            timing = SprintTime(centis, method, StartProtocol.THREE_POINT),
        )
        val goal = PlanGoal.Timed(CardioActivity.TRACK_RUN, 100, 20)
        val timed = rep(100, 1_250, TimingMethod.PHONE)
        assertNull(GoalProgress.bestTimed(goal, listOf(CardioActivity.TRACK_RUN to listOf(timed))))
        assertEquals(13, GoalProgress.bestTimed(goal, listOf(CardioActivity.TRACK_RUN to listOf(timed.copy(timing = null)))))
    }
}
