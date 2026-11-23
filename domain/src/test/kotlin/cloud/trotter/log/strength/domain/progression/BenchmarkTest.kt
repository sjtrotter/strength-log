package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.HoldClock
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkTest {
    private val hrp = TestRef("aft_hrp", 1, 120)
    private val plank = TestRef("aft_plank", 1)

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
