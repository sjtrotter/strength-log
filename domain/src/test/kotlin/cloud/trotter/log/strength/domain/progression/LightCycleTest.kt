package cloud.trotter.log.strength.domain.progression

import kotlin.test.Test
import kotlin.test.assertEquals

class LightCycleTest {
    @Test fun `case 14 a fired light cycle lasts one rotation`() {
        var cycle = LightWeek.afterDay(LightCycle(0, 5), 4, true, 6, false)
        assertEquals(LightCycle(4, 6), cycle)
        for (remaining in 3 downTo 1) {
            cycle = LightWeek.afterDay(cycle, 4, false, 6, false)
            assertEquals(LightCycle(remaining, 6), cycle)
        }
        assertEquals(LightCycle(0, 7), LightWeek.afterDay(cycle, 4, true, 6, false))
    }

    @Test fun `case 35 the planned trigger fires at every sixth wrap`() {
        for (deload in listOf(6, null)) {
            var cycle = LightCycle(0, 0)
            val remaining = if (deload == null) List(12) { 0 } else listOf(0, 0, 0, 0, 0, 4, 2, 0, 0, 0, 0, 4)
            val afterUnwrapped = if (deload == null) List(12) { 0 } else listOf(0, 0, 0, 0, 0, 3, 1, 0, 0, 0, 0, 3)
            for (index in 0 until 12) {
                cycle = LightWeek.afterDay(cycle, 4, true, deload, false)
                assertEquals(LightCycle(remaining[index], index + 1), cycle)
                cycle = LightWeek.afterDay(cycle, 4, false, deload, false)
                assertEquals(LightCycle(afterUnwrapped[index], index + 1), cycle)
            }
        }
    }

    @Test fun `a reactive trigger fires only outside a light cycle`() {
        assertEquals(LightCycle(4, 3), LightWeek.afterDay(LightCycle(0, 3), 4, false, null, true))
        assertEquals(LightCycle(1, 3), LightWeek.afterDay(LightCycle(2, 3), 4, false, null, true))
        assertEquals(LightCycle(0, 3), LightWeek.afterDay(LightCycle(0, 3), 4, false, null, false))
        assertEquals(LightCycle(1, 3), LightWeek.afterDay(LightCycle(0, 3), 0, false, null, true))
    }
}
