package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The queued -> synced pill transition (design digest §3), pulled out pure. */
class SyncPillLogicTest {

    @Test
    fun `shows queued while the count is above zero`() {
        assertEquals(SyncPillKind.QUEUED, syncPillKind(previousCount = 0, currentCount = 3))
        assertEquals(SyncPillKind.QUEUED, syncPillKind(previousCount = 3, currentCount = 3))
        assertEquals(SyncPillKind.QUEUED, syncPillKind(previousCount = 3, currentCount = 1))
    }

    @Test
    fun `shows synced exactly on the transition from above zero to zero`() {
        assertEquals(SyncPillKind.SYNCED, syncPillKind(previousCount = 1, currentCount = 0))
        assertEquals(SyncPillKind.SYNCED, syncPillKind(previousCount = 5, currentCount = 0))
    }

    @Test
    fun `shows nothing when the count was already zero and stays zero`() {
        assertEquals(SyncPillKind.NONE, syncPillKind(previousCount = 0, currentCount = 0))
    }
}
