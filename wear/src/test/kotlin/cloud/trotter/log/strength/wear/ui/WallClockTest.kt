package cloud.trotter.log.strength.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WallClockTest {

    @Test
    fun `minute delay lands on the next boundary`() {
        assertEquals(60_000L, millisUntilNextMinute(0L))
        assertEquals(59_999L, millisUntilNextMinute(1L))
        assertEquals(1L, millisUntilNextMinute(59_999L))
        assertEquals(60_000L, millisUntilNextMinute(120_000L))
    }
}
