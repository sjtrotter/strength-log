package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
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
     * The number of outbound edits still unacked (backed by [PendingEditStore]
     * on the real client) — drives the "queued"/"synced" pills (design digest
     * §3): a persistent count while phone is unreachable, and the transition
     * to 0 is what triggers the transient "synced" confirmation.
     */
    fun pendingCountFlow(): Flow<Int>

    /**
     * The `programExerciseId`s with an edit still unacked. Narrower than the queue
     * itself on purpose: the UI never needs the deltas, only the answer to "is
     * anything of mine still in flight against this lift?" — which is how a local
     * decision about a lift avoids being made against a snapshot that hasn't
     * caught up with the edit yet.
     */
    fun pendingExercisesFlow(): Flow<Set<Long>>

    /**
     * Sends an edit toward the phone. This call does not itself update
     * [snapshotFlow] — cascade/seeding run phone-side only, so the caller
     * renders optimistically and reconciles against the next snapshot
     * (higher `revision`), never against this call's return.
     */
    suspend fun sendEdit(delta: SetEditDelta)
}
