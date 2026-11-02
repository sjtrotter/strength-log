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
}
