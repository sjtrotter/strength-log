package io.github.sjtrotter.strengthlog.wear.data

import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * The module seam PLAN prescribes (m5-wear.md #19): the two screens code
 * against this interface only, never against a transport. [FakeWatchClient]
 * satisfies it here so `:wear` runs standalone; #20 swaps in the real
 * implementation over the Wearable Data Layer (`DataClient` for
 * [snapshotFlow], `MessageClient` for [sendEdit]) without the UI changing.
 */
interface WatchTrackerClient {

    /** The current [WatchSnapshot], replayed to new collectors and updated in place. */
    fun snapshotFlow(): Flow<WatchSnapshot>

    /**
     * Sends an edit toward the phone. This call does not itself update
     * [snapshotFlow] — cascade/seeding run phone-side only, so the caller
     * renders optimistically and reconciles against the next snapshot
     * (higher `revision`), never against this call's return.
     */
    suspend fun sendEdit(delta: SetEditDelta)
}
