package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.LoggedCardio
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RunCap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

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
        // ladder 2026-11-17: ceil(24 divided by 3) = 8 derived ladder weeks replaces the fixed 8; totals stay unchanged.
        assertEquals(30, weeksNeeded(RunGoal.MARATHON, RunPhase.LADDER, ladderWeeks = 8))
        assertEquals(14, weeksNeeded(RunGoal.HALF_MARATHON, RunPhase.BASE, ladderWeeks = 8))
        assertEquals(0, weeksNeeded(RunGoal.FIVE_TO_TEN_K, RunPhase.HALF_BUILD, ladderWeeks = 8))
        assertEquals(8, weeksNeeded(RunGoal.FIRST_5K, RunPhase.LADDER, ladderWeeks = 8))
        assertEquals(0, weeksNeeded(RunGoal.FIRST_5K, RunPhase.BASE, ladderWeeks = 8))
        assertNull(weeksNeeded(RunGoal.FIRST_5K, RunPhase.LADDER, null))
        assertEquals(14, weeksNeeded(RunGoal.HALF_MARATHON, RunPhase.BASE, null))
    }

    @Test fun `ladder starts at the highest level the longest running time covers`() {
        // ladder 2026-11-17: no WORK time replaces a distance-only maximum; the start stays at 1.
        assertEquals(1, ladderStart(null, false))
        // ladder 2026-11-17: running totals place 900 at 5 and 1200 at 8 instead of interval-based level 10.
        for ((seconds, level) in listOf(59 to 1, 60 to 1, 900 to 5, 1200 to 8, 1799 to 11)) {
            assertEquals(level, ladderStart(seconds, false), "$seconds seconds")
        }
        assertEquals(1, ladderStart(60, true))
        // ladder 2026-11-17: injury subtracts one from 8, giving 7 instead of 9.
        assertEquals(7, ladderStart(1200, true))
    }

    @Test fun `ladder weeks follow the sessions the plan runs`() {
        assertEquals(8, ladderWeeks(1, 3))
        assertEquals(12, ladderWeeks(1, 2))
        assertEquals(24, ladderWeeks(1, 1))
        assertEquals(4, ladderWeeks(8, 3))
        assertNull(ladderWeeks(1, 0))
    }

    @Test fun `the longest running time counts work only`() {
        val block = RunWalkLadder.block(1, CardioActivity.ROAD_RUN)
        val results = block.sets.map { CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as Measure.Time).seconds) }
        val log = listOf(LoggedCardio(100, results))
        assertEquals(600, CardioHistory.longestRunning(log, 101))
        assertEquals(1500, CardioHistory.of(log, 101).longest?.seconds)
        assertEquals(1, ladderStart(600, false))
        assertEquals(10, ladderStart(1500, false))
        val unfinished = results.map { if (it.spec.role == CardioRole.WORK) it.copy(status = CardioSetStatus.SHORTENED) else it }
        assertNull(CardioHistory.longestRunning(listOf(LoggedCardio(100, unfinished)), 101))
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
    @Test fun `the ladder gate counts running and never recoveries`() {
        assertFalse(ladderMet(RecentMaxima(5500, 3600), 900, 2500))
        assertTrue(ladderMet(RecentMaxima(5000, 1700), 1700, 5000))
        assertTrue(ladderMet(RecentMaxima(null, 2000), 2000, null))
        assertTrue(ladderMet(RecentMaxima(5000, null), null, null))
        assertFalse(ladderMet(null, null, null))
    }

}
