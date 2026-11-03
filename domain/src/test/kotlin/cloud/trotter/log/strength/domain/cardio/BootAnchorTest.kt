package cloud.trotter.log.strength.domain.cardio

import kotlin.test.Test
import kotlin.test.assertEquals

class BootAnchorTest {
    private val anchor = BootAnchor(1_000_000L, 9_000_000L, 7)

    @Test
    fun `same boot keeps exact deadlines through wall jumps and expiry`() {
        listOf(100L, 4_640_000L).forEach { wall ->
            listOf(8_999_000L, 9_040_000L, 9_200_000L).forEach { elapsed ->
                val now = Clocks(wall, elapsed, 7)
                assertEquals(9_120_000L, anchor.deadlineElapsedMillis(120_000L, now))
                assertEquals(9_120_000L, anchor.deadlineElapsedMillis(120_000L, now, clampAfterReboot = true))
                assertEquals(anchor, anchor.rebase(now))
            }
        }
    }

    @Test
    fun `reboot projects exact deadlines even into the past`() {
        val now = Clocks(1_045_000L, 309_000_000L, 8)
        assertEquals(309_075_000L, anchor.deadlineElapsedMillis(120_000L, now))
        assertEquals(308_985_000L, anchor.deadlineElapsedMillis(30_000L, now))
        assertEquals(309_075_000L, anchor.deadlineElapsedMillis(120_000L, now, clampAfterReboot = true))
        assertEquals(309_000_000L, anchor.deadlineElapsedMillis(30_000L, now, clampAfterReboot = true))
    }

    @Test
    fun `reboot countdown clamps wall rollback but exact boundaries retain it`() {
        val now = Clocks(999_000L, 500L, 8)
        assertEquals(121_500L, anchor.deadlineElapsedMillis(120_000L, now))
        assertEquals(120_500L, anchor.deadlineElapsedMillis(120_000L, now, clampAfterReboot = true))
    }

    @Test
    fun `unknown boot and missing elapsed anchor project from wall time`() {
        val now = Clocks(1_045_000L, 500L, 8)
        listOf(anchor.copy(bootCount = null), anchor.copy(elapsedMillis = null, bootCount = 8)).forEach {
            assertEquals(75_500L, it.deadlineElapsedMillis(120_000L, now))
            assertEquals(500L, it.deadlineElapsedMillis(30_000L, now, clampAfterReboot = true))
        }
        assertEquals(75_500L, anchor.deadlineElapsedMillis(120_000L, now.copy(bootCount = null)))
    }

    @Test
    fun `rebased anchor retains elapsed time and follows the new boot clock`() {
        val now = Clocks(1_045_000L, 309_000_000L, 8)
        val rebased = anchor.rebase(now)
        assertEquals(BootAnchor(1_000_000L, 308_955_000L, 8), rebased)
        assertEquals(anchor.elapsedSince(now), rebased.elapsedSince(now))
        assertEquals(46_000L, rebased.elapsedSince(Clocks(100L, 309_001_000L, 8)))
        assertEquals(309_075_000L, rebased.deadlineElapsedMillis(120_000L, now))
    }

    @Test
    fun `rebasing retains negative elapsed epochs and future wall anchors`() {
        assertEquals(BootAnchor(1_000_000L, -44_500L, 8), anchor.rebase(Clocks(1_045_000L, 500L, 8)))
        val now = Clocks(999_000L, 500L, 8)
        val rebased = anchor.rebase(now)
        assertEquals(BootAnchor(1_000_000L, 1_500L, 8), rebased)
        assertEquals(0L, rebased.elapsedSince(now))
        assertEquals(121_500L, rebased.deadlineElapsedMillis(120_000L, now))
    }
}
