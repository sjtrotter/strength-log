package cloud.trotter.log.strength.wear.ui

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WallClockTest {

    @Test
    fun `pill time follows device hour preference without meridiem`() {
        val worstCase = LocalTime.of(12, 58)
        assertEquals("12:58", timePillTimeText(worstCase, is24Hour = false))
        assertEquals("12:58", timePillTimeText(worstCase, is24Hour = true))
        assertEquals("11:58", timePillTimeText(LocalTime.of(23, 58), is24Hour = false))
        assertEquals("23:58", timePillTimeText(LocalTime.of(23, 58), is24Hour = true))
    }

    @Test
    fun `minute delay lands on the next boundary`() {
        assertEquals(60_000L, millisUntilNextMinute(0L))
        assertEquals(59_999L, millisUntilNextMinute(1L))
        assertEquals(1L, millisUntilNextMinute(59_999L))
        assertEquals(60_000L, millisUntilNextMinute(120_000L))
    }
}
