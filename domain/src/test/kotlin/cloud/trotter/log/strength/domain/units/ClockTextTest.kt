package cloud.trotter.log.strength.domain.units

import kotlin.test.Test
import kotlin.test.assertEquals

class ClockTextTest {
    @Test
    fun `formats minutes and zero padded seconds without an hour field`() {
        assertEquals("0:00", ClockText.minutesSeconds(0))
        assertEquals("0:45", ClockText.minutesSeconds(45))
        assertEquals("1:05", ClockText.minutesSeconds(65))
        assertEquals("12:00", ClockText.minutesSeconds(720))
        assertEquals("60:00", ClockText.minutesSeconds(3600))
    }
    @Test fun `duration includes hours from sixty minutes`() {
        listOf(59 to "0:59", 3599 to "59:59", 3600 to "1:00:00", 14449 to "4:00:49",
            86399 to "23:59:59").forEach { (seconds, text) -> assertEquals(text, ClockText.duration(seconds)) }
    }

    @Test fun `parse accepts clocks and rejects invalid parts`() {
        listOf("52:07" to 3127, "4:00:49" to 14449, "1:00:00" to 3600).forEach { (text, seconds) ->
            assertEquals(seconds, ClockText.parse(text))
            assertEquals(text, ClockText.duration(seconds))
        }
        listOf("4:60:00", "60:00:00:00", "-1:00", "abc", "1:60", "999999999999:00",
            "2147483647:00", "1::00", "1:00:-1").forEach { assertEquals(null, ClockText.parse(it)) }
    }
}
