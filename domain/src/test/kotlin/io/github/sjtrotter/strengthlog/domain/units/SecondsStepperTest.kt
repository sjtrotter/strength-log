package io.github.sjtrotter.strengthlog.domain.units

import kotlin.test.Test
import kotlin.test.assertEquals

class SecondsStepperTest {

    @Test
    fun `increment is always five seconds`() {
        assertEquals(5, SecondsStepper.increment(0))
        assertEquals(5, SecondsStepper.increment(45))
        assertEquals(5, SecondsStepper.increment(300))
    }

    @Test
    fun `formats under 90 seconds as a bare count`() {
        assertEquals("0s", SecondsStepper.format(0))
        assertEquals("45s", SecondsStepper.format(45))
        assertEquals("89s", SecondsStepper.format(89))
    }

    @Test
    fun `formats 90 seconds and above as m colon ss`() {
        assertEquals("1:30", SecondsStepper.format(90))
        assertEquals("1:35", SecondsStepper.format(95))
        assertEquals("2:00", SecondsStepper.format(120))
        assertEquals("2:05", SecondsStepper.format(125))
    }
}
