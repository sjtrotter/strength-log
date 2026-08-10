package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.CardioDelta
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
     * The `programExerciseId`s with a **swap** still unacked (#90). Narrower than
     * [pendingExercisesFlow] because it drives a different decision: a lift whose
     * replacement the phone hasn't confirmed can't be logged against — the rows on
     * screen still belong to the exercise being replaced — whereas a lift with a
     * pending tick is perfectly loggable.
     */
    fun pendingSwapsFlow(): Flow<Set<Long>>

    /**
     * Sends an edit toward the phone. Cascade/seeding run phone-side only, so the
     * caller renders optimistically and reconciles against the next snapshot (higher
     * `revision`), never against this call's return.
     *
     * Deliberately **not** a suspend function: the durable enqueue is the
     * implementation's own responsibility, on a scope that outlives the caller. The
     * dial acknowledges a tick with a haptic the instant it is tapped, and the Activity
     * it was tapped in can be destroyed a millisecond later (the day-done disc's own
     * dismiss does exactly that) — a tick the lifter felt must not be able to die with
     * the composition that sent it (#174).
     */
    fun sendEdit(delta: SetEditDelta)

    /**
     * Asks the phone to use one of the alternates it prescribed for a slot (#90).
     * Same fire-and-forget-plus-queue contract as [sendEdit], and the same ack: the
     * next snapshot, carrying the new name and the sets the phone reseeded. The watch
     * echoes the name and nothing else — it cannot seed.
     */
    fun sendSwap(swap: ExerciseSwapDelta)

    /** Queues a completed cardio session until its LOGGED snapshot acknowledgement. */
    fun sendCardio(delta: CardioDelta)
}
