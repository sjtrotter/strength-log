package cloud.trotter.log.strength.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TimedRepClockCompatibilityTest {
    private val attempt = TimedRepState(TimedRepOwner(9L, "A", 7L, "push-ups", "main", 0),
        120, 100_000L, 10_000L, 4)

    @Test
    fun `same boot retains exact deadline through clock rollback and expiry`() {
        assertEquals(130_000L, attempt.deadline(9_000L, 9_000_000L, 4))
        assertEquals(130_000L, attempt.deadline(150_000L, 100L, 4))
    }

    @Test
    fun `reboot countdown clamps elapsed wall time and remaining duration`() {
        assertEquals(820_000L, attempt.deadline(700_000L, 90_000L, 5))
        assertEquals(760_000L, attempt.deadline(700_000L, 160_000L, 5))
        assertEquals(700_000L, attempt.deadline(700_000L, 230_000L, 5))
    }
}
