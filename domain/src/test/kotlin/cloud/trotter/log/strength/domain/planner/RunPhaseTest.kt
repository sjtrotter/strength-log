package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RunCap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunPhaseTest {
    @Test fun `single run gates include their distance and time boundaries`() {
        for ((phase, metres, seconds) in listOf(
            Triple(RunPhase.LADDER, 5000, 1800), Triple(RunPhase.BASE, 10_000, 3600),
            Triple(RunPhase.HALF_BUILD, 21_000, 7200), Triple(RunPhase.MARATHON_BUILD, 30_000, 10_800),
        )) {
            assertFalse(phase.met(RecentMaxima(metres - 1, null), false), "$phase distance below")
            assertTrue(phase.met(RecentMaxima(metres, null), false), "$phase distance gate")
            assertFalse(phase.met(RecentMaxima(null, seconds - 1), false), "$phase time below")
            assertTrue(phase.met(RecentMaxima(null, seconds), false), "$phase time gate")
        }
        assertTrue(RunPhase.MARATHON_BUILD.met(RecentMaxima(1000, null), true))
        assertFalse(RunPhase.HALF_BUILD.met(RecentMaxima(1000, null), true))
        assertFalse(RunPhase.ULTRA_BUILD.met(RecentMaxima(160_934, 86_400), true))
    }

    @Test fun `remaining weeks stop at the events own readiness phase`() {
        assertEquals(30, weeksNeeded(RunGoal.MARATHON, RunPhase.LADDER))
        assertEquals(14, weeksNeeded(RunGoal.HALF_MARATHON, RunPhase.BASE))
        assertEquals(0, weeksNeeded(RunGoal.FIVE_TO_TEN_K, RunPhase.HALF_BUILD))
        assertEquals(8, weeksNeeded(RunGoal.FIRST_5K, RunPhase.LADDER))
        assertEquals(0, weeksNeeded(RunGoal.FIRST_5K, RunPhase.BASE))
    }

    @Test fun `ladder starts at the highest run interval the longest time fits`() {
        assertEquals(1, ladderStart(null, false))
        assertEquals(1, ladderStart(RecentMaxima(500, null), false))
        for ((seconds, level) in listOf(59 to 1, 60 to 1, 1200 to 10, 1799 to 11)) {
            assertEquals(level, ladderStart(RecentMaxima(null, seconds), false), "$seconds seconds")
        }
        assertEquals(1, ladderStart(RecentMaxima(null, 60), true))
        assertEquals(9, ladderStart(RecentMaxima(null, 1200), true))
    }

    @Test fun `case 30 existing five percent increases reach the base gate on step fifteen`() {
        fun block(end: Measure) = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, end)))
        var distance = block(Measure.Distance(5000))
        repeat(14) { distance = CardioMeasures.increased(distance, ProgressMeasure.DISTANCE, 5) }
        assertEquals(block(Measure.Distance(9900)), distance)
        distance = CardioMeasures.increased(distance, ProgressMeasure.DISTANCE, 5)
        assertEquals(block(Measure.Distance(10_395)), distance)
        var time = block(Measure.Time(1800))
        repeat(14) { time = CardioMeasures.increased(time, ProgressMeasure.TIME, 5) }
        assertEquals(block(Measure.Time(3566)), time)
        time = CardioMeasures.increased(time, ProgressMeasure.TIME, 5)
        assertEquals(block(Measure.Time(3744)), time)
        assertEquals(5500, RunCap.limit(5000, 100))
    }
}
