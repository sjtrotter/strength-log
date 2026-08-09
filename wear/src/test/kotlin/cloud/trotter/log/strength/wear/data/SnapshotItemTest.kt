package cloud.trotter.log.strength.wear.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which cached item wins when the node holds more than one (#173). The old read took
 * whichever decoded first, and the Data Layer cache is a set, not a list — so "first"
 * was whatever the buffer happened to hand back, and a glance surface could open on a
 * stale day.
 */
class SnapshotItemTest {

    @Test
    fun `within one generation the highest revision wins, in either read order`() {
        val stale = phoneSnapshot(revision = 3, epoch = EPOCH)
        val current = phoneSnapshot(revision = 11, epoch = EPOCH)

        assertEquals(current, newest(listOf(stale, current)))
        assertEquals(current, newest(listOf(current, stale)))
    }

    @Test
    fun `a newer generation outranks a higher revision of an older one`() {
        // What an obsolete node leaves in the cache after the phone's data is cleared:
        // a revision far above anything the restarted count will reach for weeks.
        val obsolete = phoneSnapshot(revision = 500, epoch = EPOCH)
        val current = phoneSnapshot(revision = 1, epoch = EPOCH + 1)

        assertEquals(current, newest(listOf(obsolete, current)))
        assertEquals(current, newest(listOf(current, obsolete)))
    }

    @Test
    fun `an epoched item outranks a legacy one that carries no epoch at all`() {
        val legacy = phoneSnapshot(revision = 900)
        val epoched = phoneSnapshot(revision = 1, epoch = EPOCH)

        assertEquals(epoched, newest(listOf(legacy, epoched)))
    }

    @Test
    fun `nothing cached reads as nothing to show`() {
        assertNull(newest(emptyList()))
    }

    private companion object {
        const val EPOCH = 1_700_000_000_000L
    }
}
