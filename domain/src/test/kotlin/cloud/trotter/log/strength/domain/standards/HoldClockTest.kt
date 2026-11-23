package cloud.trotter.log.strength.domain.standards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoldClockTest {
    @Test fun `held seconds count whole seconds and stop at the target`() {
        assertEquals(0, HoldClock.heldSeconds(0, 30))
        assertEquals(0, HoldClock.heldSeconds(999, 30))
        assertEquals(12, HoldClock.heldSeconds(12_400, 30))
        assertEquals(30, HoldClock.heldSeconds(45_000, 30))
        assertEquals(0, HoldClock.heldSeconds(-5, 30))
    }

    @Test fun `the hold reaches its target on the exact millisecond`() {
        assertFalse(HoldClock.reached(29_999, 30))
        assertTrue(HoldClock.reached(30_000, 30))
    }

    @Test fun `the fill runs from empty to full`() {
        assertEquals(0f, HoldClock.fraction(0, 30))
        assertEquals(0.5f, HoldClock.fraction(15, 30))
        assertEquals(1f, HoldClock.fraction(30, 30))
        assertEquals(1f, HoldClock.fraction(40, 30))
        assertEquals(0f, HoldClock.fraction(5, 0))
    }

    @Test fun `a set held once per side logs its shorter side`() {
        assertEquals(22, HoldClock.loggedSeconds(listOf(30, 22)))
        assertEquals(30, HoldClock.loggedSeconds(listOf(30)))
        assertEquals(0, HoldClock.loggedSeconds(emptyList<Int>()))
    }
    @Test fun `a hold past its target counts to the ten minute limit`() {
        assertEquals(30, HoldClock.limitSeconds(30, false))
        assertEquals(600, HoldClock.limitSeconds(30, true))
        assertEquals(900, HoldClock.limitSeconds(900, true))
        assertEquals(47, HoldClock.heldSeconds(47_400, 600))
        assertEquals(600, HoldClock.heldSeconds(700_000, 600))
        assertEquals(1f, HoldClock.fraction(47, 30))
    }

}
