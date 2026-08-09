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
    fun `the highest revision wins regardless of read order`() {
        val stale = phoneSnapshot(revision = 3)
        val current = phoneSnapshot(revision = 11)

        assertEquals(current, newest(listOf(stale, current)))
        assertEquals(current, newest(listOf(current, stale)))
    }

    @Test
    fun `nothing cached reads as nothing to show`() {
        assertNull(newest(emptyList()))
    }
}
