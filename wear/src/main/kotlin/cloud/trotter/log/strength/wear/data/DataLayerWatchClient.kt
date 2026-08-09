package cloud.trotter.log.strength.wear.data

import android.util.Log
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.WearSyncPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The real [WatchTrackerClient] over the Wearable Data Layer (#20), replacing
 * [FakeWatchClient] in the running app. Reads the phone's snapshot — which the Data
 * Layer persists on this node, so the last snapshot survives a phone-app (and
 * watch-app) restart — and sends edits as messages, both through [PhoneLink].
 *
 * The §11.4 mechanism lives here: messaging is fire-and-forget, so every edit is also
 * queued in [PendingEditStore] and re-sent until a later snapshot reflects it. Three
 * signals drain the queue: the first prime on start, every inbound snapshot, and a
 * phone becoming reachable again ([PhoneLink.phoneReachability]) — the last of these
 * is what makes "deltas flush on reconnect" true rather than "flush next time the
 * lifter opens the app" (#173). The phone dedupes replays, so blind re-sends are safe.
 *
 * Everything here runs on the application [scope], which has no
 * `CoroutineExceptionHandler`: anything that escapes takes the whole watch app down.
 * That is the constraint behind [guarded] and [withRetriedRegistration], and the
 * reason [sendEdit] launches its own work instead of borrowing the caller's scope
 * (#174 — a composition scope dies with the Activity, mid-enqueue).
 */
class DataLayerWatchClient(
    private val link: PhoneLink,
    private val queue: PendingEditStore,
    private val scope: CoroutineScope,
) : WatchTrackerClient {

    private val snapshots = MutableStateFlow<WatchSnapshot?>(null)

    /** One drain at a time. Concurrent drains would double every queued message on
     *  the wire for no gain — the queue they both read is the same one. */
    private val draining = Mutex()

    init {
        scope.launch { prime() }
        link.snapshotChanges()
            .withRetriedRegistration("snapshots")
            .onEach { guarded("handling a snapshot") { onSnapshot(it) } }
            .launchIn(scope)
        link.phoneReachability()
            .withRetriedRegistration("phone reachability")
            .distinctUntilChanged()
            .filter { reachable -> reachable }
            .onEach { guarded("draining on reconnect") { drainQueue() } }
            .launchIn(scope)
    }

    override fun snapshotFlow(): Flow<WatchSnapshot> = snapshots.filterNotNull()

    override fun pendingCountFlow(): Flow<Int> = queue.countFlow()

    override fun pendingExercisesFlow(): Flow<Set<Long>> = queue.exerciseIdsFlow()

    override fun pendingSwapsFlow(): Flow<Set<Long>> = queue.swapExerciseIdsFlow()

    override fun sendEdit(delta: SetEditDelta) = launchSend("sending an edit") {
        // Re-stamp with a strictly monotonic, persisted editedAtMillis: the caller's
        // wall clock can stamp two distinct edits into the same millisecond, and the
        // phone's per-row dedupe would then drop the second as a replay.
        val stamped = delta.copy(editedAtMillis = queue.issueStamp(delta.editedAtMillis))
        echo { exercises -> WatchEditOptimism.apply(exercises, stamped) }
        queue.enqueue(stamped)
        send(stamped)
    }

    /** Same shape as [sendEdit], one path over. The echo is the name only — seeding
     *  is the phone's (see [WatchEditOptimism.applySwap]). */
    override fun sendSwap(swap: ExerciseSwapDelta) = launchSend("sending a swap") {
        val stamped = swap.copy(editedAtMillis = queue.issueStamp(swap.editedAtMillis))
        echo { exercises -> WatchEditOptimism.applySwap(exercises, stamped) }
        queue.enqueueSwap(stamped)
        sendSwapMessage(stamped)
    }

    /**
     * The lifter felt the CONFIRM buzz before this call returned, so from here on the
     * tick must reach the durable queue no matter what happens to the caller. Running
     * on the application scope is what makes that true: the dial calls this from a
     * composition scope that the app's own dismiss path cancels, and it used to do so
     * with two DataStore writes still ahead of the edit (#174).
     */
    private fun launchSend(what: String, block: suspend () -> Unit) {
        scope.launch { guarded(what) { block() } }
    }

    /**
     * The optimistic on-wrist echo (spec §9), applied in place to whatever snapshot is
     * held.
     *
     * INVARIANT: the echo must NOT bump `revision`. Revision means "the phone has said
     * something new"; an echo that advanced it would let the lifter's own edit pass
     * itself off as a phone-side change, and — since [install] compares against the
     * held revision — would make the phone's real answer look stale.
     */
    private fun echo(apply: (List<WatchExercise>) -> List<WatchExercise>) {
        snapshots.update { held ->
            held?.copy(day = held.day.copy(exercises = apply(held.day.exercises)))
        }
    }

    /** The last snapshot the Data Layer cached on this node (survives restarts). */
    private suspend fun prime() {
        guarded("priming the snapshot") { link.cachedSnapshot()?.let(::install) }
        guarded("draining on start") { drainQueue() }
    }

    private suspend fun onSnapshot(snapshot: WatchSnapshot) {
        // Reconcile only against a snapshot new enough to install. Settling reads the
        // snapshot as the phone's current authority — PendingEdits.isRefusedByDrift
        // drops a swap whose target has fallen out of the prescription — and a stale
        // or replayed item is not that document.
        if (install(snapshot)) {
            // Drop-settled runs atomically inside the store (one DataStore.edit), so a
            // sendEdit enqueuing concurrently can't be wiped between read and write —
            // no lock needed here.
            queue.reconcileAgainst(snapshot)
        }
        // Drained either way: a redelivery of the revision we already hold is what a
        // reconnect resync looks like, and it is a perfectly good flush signal.
        drainQueue()
    }

    /**
     * Installs [snapshot] if it is strictly newer than the one held, and says whether
     * it did.
     *
     * THE REVISION CONTRACT (`>`, not `>=`). The phone spends a fresh revision on every
     * publish (`WearSyncPublisher` conflates on content, then bumps), so an equal
     * revision is never new content — it is a redelivery, which the Data Layer does
     * emit when it resyncs a reconnected node, and which [prime] can also race against
     * the listener. Installing one would be worse than a no-op: the optimistic echo
     * deliberately leaves `revision` alone, so the held snapshot at revision R can be
     * *ahead* of the phone's R in exactly the way the lifter can see, and re-installing
     * R would visibly un-tick a set they just ticked. Out-of-order and stale items are
     * refused by the same comparison.
     *
     * The guard is per-process by design: it holds only against the revision this
     * process has seen, so a phone that legitimately regresses its counter (app data
     * cleared) is picked up on the next watch-app start, when [prime] installs the
     * cached item against nothing.
     */
    private fun install(snapshot: WatchSnapshot): Boolean {
        val held = snapshots.getAndUpdate { current ->
            if (current != null && snapshot.revision <= current.revision) current else snapshot
        }
        return held == null || held.revision < snapshot.revision
    }

    /**
     * Both queues, on every drain signal. The order between them doesn't matter and
     * can't: the dial only offers a swap on a lift with nothing logged, and a lift
     * with a swap in flight can't be logged against, so a set edit and a swap for the
     * *same* slot never coexist here. Across slots they don't interact at all.
     */
    private suspend fun drainQueue() = draining.withLock {
        queue.all().forEach { send(it) }
        queue.allSwaps().forEach { sendSwapMessage(it) }
    }

    private suspend fun send(delta: SetEditDelta) =
        link.send(WearSyncPaths.SET_EDIT, SyncCodec.encodeDelta(delta))

    private suspend fun sendSwapMessage(swap: ExerciseSwapDelta) =
        link.send(WearSyncPaths.EXERCISE_SWAP, SyncCodec.encodeSwap(swap))

    /**
     * Registering a Data Layer listener can fail transiently — Play services updating,
     * a cold GMS process — and the failure surfaces when the flow is collected. It used
     * to throw straight into the handler-less application scope and take the app down
     * (#173). Retry a bounded number of times with exponential backoff, then stop
     * listening quietly for this session; the next launch registers again.
     */
    private fun <T> Flow<T>.withRetriedRegistration(what: String): Flow<T> =
        retryWhen { cause, priorFailures ->
            val retrying = priorFailures < REGISTRATION_ATTEMPTS - 1
            if (retrying) {
                Log.w(TAG, "registering for $what failed; retrying", cause)
                delay(RETRY_BASE_MILLIS shl priorFailures.toInt())
            }
            retrying
        }.catch { Log.w(TAG, "gave up listening for $what this session", it) }

    /**
     * Runs [block], logging anything it throws instead of letting it reach the
     * application scope. Cancellation still propagates — it is how the scope shuts
     * down, not a failure.
     */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    internal companion object {
        private const val TAG = "WatchClient"

        /** Registration attempts in all — the first, plus four retries. */
        const val REGISTRATION_ATTEMPTS = 5L

        /** First backoff; each retry doubles it (1s, 2s, 4s, 8s). */
        const val RETRY_BASE_MILLIS = 1_000L
    }
}
