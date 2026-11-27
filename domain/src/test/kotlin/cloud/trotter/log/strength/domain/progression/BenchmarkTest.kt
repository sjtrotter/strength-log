package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.HoldClock
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestStop
import cloud.trotter.log.strength.domain.standards.TestRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkTest {
    private val hrp = TestRef("aft_hrp", 1, 120)
    private val plank = TestRef("aft_plank", 1)

    @Test fun `case 59 a max unbroken benchmark row is one empty WORK row`() {
        val event = TestEvents.get("pullup:REPS_CONTINUOUS")
        val ref = TestRef(event.id, 1)
        assertTrue(Benchmark.runnable(event))
        assertEquals(PrescribedTarget.Reps(0), Benchmark.target(event, ref))
        assertEquals(LoggedSet(0.0, 0, SetKind.WORK), Benchmark.row(event, ref))
        assertEquals(TestStop.STOPPED, Benchmark.stop(event, false, LoggedSet(0.0, 14, SetKind.WORK, done = true)))
    }

    @Test fun `case 60 the hold limit is the cap on a pressure caution entry and ten minutes otherwise`() {
        val wall = TestEvents.get("wall_sit:MAX_HOLD")
        val aft = TestEvents.get("aft_plank")
        assertEquals(120, Benchmark.holdLimit(wall, true))
        assertEquals(600, Benchmark.holdLimit(aft, false))
        assertEquals(120, Benchmark.row(wall, TestRef(wall.id, 1)).seconds)
        fun stop(seconds: Int, capped: Boolean) = Benchmark.stop(if (capped) wall else aft, capped,
            LoggedSet(0.0, 0, SetKind.WORK, seconds = seconds))
        assertEquals(TestStop.LIMIT, stop(120, true))
        assertEquals(TestStop.STOPPED, stop(95, true))
        assertEquals(TestStop.LIMIT, stop(600, false))
        for (seconds in listOf(192, 300, 590)) assertEquals(TestStop.STOPPED, stop(seconds, false))
    }

    @Test
    fun `case 49 a benchmark is due at twenty one days and not in the last week`() {
        assertTrue(Benchmark.due(null, 100, null))
        assertFalse(Benchmark.due(80, 100, null))
        assertTrue(Benchmark.due(79, 100, null))
        assertFalse(Benchmark.due(null, 100, 106))
        assertTrue(Benchmark.due(null, 100, 107))
        assertTrue(Benchmark.due(70, 100, 107))
        assertFalse(Benchmark.due(null, 100, 90))
    }

    @Test
    fun `case 49 the benchmark row is one window or one hold at the ceiling`() {
        val hrpEvent = TestEvents.get("aft_hrp")
        val plankEvent = TestEvents.get("aft_plank")
        assertEquals(LoggedSet(0.0, 0, SetKind.WORK), Benchmark.row(hrpEvent, hrp))
        assertEquals(LoggedSet(0.0, 0, SetKind.WORK, seconds = 220), Benchmark.row(plankEvent, plank))
        assertEquals(PrescribedTarget.RepsInTime(120), Benchmark.target(hrpEvent, hrp))
        assertEquals(PrescribedTarget.Hold(220), Benchmark.target(plankEvent, plank))
        assertEquals(PrescribedTarget.Hold(HoldClock.PAST_TARGET_LIMIT_SECONDS),
            Benchmark.target(plankEvent.copy(ceilingSeconds = null), plank))
    }
}
