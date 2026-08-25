package cloud.trotter.log.strength.domain.standards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRestTimerTest {
    @Test fun `deadline math is epoch anchored and rounds remaining up`() {
        val rest = PhoneRestTimer.start(1_000L, 90, "Squat R2")!!
        assertEquals(91_000L, rest.deadlineEpochMillis)
        assertEquals(2, PhoneRestTimer.remainingSeconds(rest, 89_001L))
        assertEquals(0, PhoneRestTimer.remainingSeconds(rest, 91_000L))
    }

    @Test fun `another tick restarts from now`() {
        val restarted = PhoneRestTimer.start(20_000L, 180, "Bench TOP")!!
        assertEquals(200_000L, restarted.deadlineEpochMillis)
        assertEquals("Bench TOP", restarted.nextSetLabel)
    }

    @Test fun `adjustments clamp at zero and plus fifteen moves deadline`() {
        val rest = PhoneRestTimer.start(0L, 10, "next")!!
        assertNull(PhoneRestTimer.adjust(rest, 1_000L, -15))
        assertEquals(25_000L, PhoneRestTimer.adjust(rest, 0L, 15)!!.deadlineEpochMillis)
    }

    @Test fun `skip clears`() {
        assertNull(PhoneRestTimer.skip())
    }
}
