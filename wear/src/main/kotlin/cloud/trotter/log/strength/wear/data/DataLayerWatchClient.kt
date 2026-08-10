package cloud.trotter.log.strength.wear.data

import android.util.Log
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.WearSyncPaths
import cloud.trotter.log.strength.domain.sync.applyDelta
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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
 * signals drain the queue: the first prime on start, every inbound snapshot, and every
 * report that a phone is reachable ([PhoneLink.phoneReachability]; the flow conflates,
 * so rapid flaps may collapse — the LATEST state always lands, which is all the
 * drain needs) — the last of these
 * is what makes "deltas flush on reconnect" true rather than "flush next time the
 * lifter opens the app" (#173). The phone dedupes replays, so blind re-sends are safe.
 *
 * Everything here runs on the application [scope], which has no
 * `CoroutineExceptionHandler`: anything that escapes takes the whole watch app down.
 * That is the constraint behind [guarded] and [retryingRegistration], and the reason
 * [sendEdit] launches its own work instead of borrowing the caller's scope (#174 — a
 * composition scope dies with the Activity, mid-enqueue).
 *
 * @param onWarning where this client's warnings go. Injectable because `:wear`'s tests
 *   are plain JVM, where `android.util.Log` is a stub that throws — a seam here is
 *   cheaper and more honest than making the whole module return default values for
 *   every unmocked Android call.
 * @param retryJitter the only randomness in the retry cadence, injectable so the
 *   schedule can be asserted exactly.
 * @param afterEnqueueBeforeEcho test seam at the race boundary; it runs while
 *   [installing] is held.
 * @param beforeInstallLock test seam for proving that an install is waiting on
 *   [installing].
 */
class DataLayerWatchClient(
    private val link: PhoneLink,
    private val queue: PendingEditStore,
    private val scope: CoroutineScope,
    private val onWarning: (String, Throwable) -> Unit = { message, cause -> Log.w(TAG, message, cause) },
    private val retryJitter: () -> Double = { Random.nextDouble() },
    private val afterEnqueueBeforeEcho: suspend () -> Unit = {},
    private val beforeInstallLock: suspend () -> Unit = {},
) : WatchTrackerClient {

    private val snapshots = MutableStateFlow<WatchSnapshot?>(null)

    /** One drain at a time. Concurrent drains would double every queued message on
     *  the wire for no gain — the queue they both read is the same one. */
    private val draining = Mutex()

    /** Serializes the cache prime and live listener through one freshness decision. */
    private val installing = Mutex()

    init {
        scope.launch { prime() }
        link.snapshotChanges()
            .retryingRegistration("snapshots")
            .onEach { guarded("handling a snapshot") { onSnapshot(it) } }
            .launchIn(scope)
        link.phoneReachability()
            .retryingRegistration("phone reachability")
            // Every "reachable" is a drain, not just the rising edge. Deduplicating
            // would mean trusting the order of two independent sources — the initial
            // capability query and the change callbacks — and a query result that
            // lands after a disconnect callback would latch `true` and swallow the
            // real reconnect for the rest of the session. Drains are serialized and
            // the phone dedupes replays, so an extra one costs a message nobody reads.
            .filter { reachable -> reachable }
            .onEach { guarded("draining on reconnect") { drainQueue() } }
            .launchIn(scope)
    }

    override fun snapshotFlow(): Flow<WatchSnapshot> = snapshots.filterNotNull()

    override fun pendingCountFlow(): Flow<Int> = queue.countFlow()

    override fun pendingExercisesFlow(): Flow<Set<Long>> = queue.exerciseIdsFlow()

    override fun pendingSwapsFlow(): Flow<Set<Long>> = queue.swapExerciseIdsFlow()

    override fun sendEdit(delta: SetEditDelta) = launchSend("sending an edit") {
        val stamped = installing.withLock {
            // Re-stamp with a strictly monotonic, persisted editedAtMillis: the caller's
            // wall clock can stamp two distinct edits into the same millisecond, and the
            // phone's per-row dedupe would then drop the second as a replay.
            val edit = delta.copy(editedAtMillis = queue.issueStamp(delta.editedAtMillis))
            queue.enqueue(edit)
            afterEnqueueBeforeEcho()
            // Display-only: applyDelta preserves the phone-owned epoch and revision.
            snapshots.update { held -> held?.let { applyDelta(it, edit) } }
            edit
        }
        send(stamped)
    }

    /** Same shape as [sendEdit], one path over. The echo is the name only — seeding
     *  is the phone's (see [applyDelta]). */
    override fun sendSwap(swap: ExerciseSwapDelta) = launchSend("sending a swap") {
        val stamped = installing.withLock {
            val edit = swap.copy(editedAtMillis = queue.issueStamp(swap.editedAtMillis))
            queue.enqueueSwap(edit)
            afterEnqueueBeforeEcho()
            // Display-only: applyDelta preserves the phone-owned epoch and revision.
            snapshots.update { held -> held?.let { applyDelta(it, edit) } }
            edit
        }
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
        install(snapshot)
        // Drained either way: a redelivery of the stamp we already hold is what a
        // reconnect resync looks like, and it is a perfectly good flush signal.
        drainQueue()
    }

    /** Installs [snapshot] if it [supersedes] the held one, and says whether it did. */
    private suspend fun install(snapshot: WatchSnapshot): Boolean {
        beforeInstallLock()
        return installing.withLock {
            val held = snapshots.value
            if (held != null && !supersedes(snapshot, held)) return@withLock false

            // Reconciliation always reads the phone's wire truth. Only the remaining
            // durable deltas are then folded over a fresh in-memory copy for display.
            queue.reconcileAgainst(snapshot)
            var displayed = snapshot
            queue.all().forEach { delta -> displayed = applyDelta(displayed, delta) }
            queue.allSwaps().forEach { delta -> displayed = applyDelta(displayed, delta) }
            snapshots.value = displayed
            true
        }
    }

    /**
     * THE FRESHNESS CONTRACT, and the only place it is decided.
     *
     * **Same epoch — strictly greater revision wins, and equal loses.** The phone
     * spends a fresh revision on every publish, so an equal revision is never new
     * content: it is a redelivery, which the Data Layer emits when it resyncs a
     * reconnected node, and which [prime] can also race against the listener.
     * Installing one would be worse than a no-op — the optimistic echo deliberately
     * leaves the stamp alone, so the held snapshot at revision R can be *ahead* of the
     * phone's R in exactly the way the lifter can see, and re-installing R would
     * visibly un-tick a set they just ticked. Out-of-order and stale items are refused
     * by the same comparison.
     *
     * **Different epoch — always adopt.** Revisions only order within the generation
     * that issued them ([WatchSnapshot.epoch]). Clearing the phone app's data restarts
     * the count at 1 while this watch may hold 500, and refusing that would wedge sync
     * permanently: this client is a process singleton, so reopening the app doesn't
     * rebuild it, and even a fresh process can re-prime the stale baseline from a
     * cached item an obsolete node left behind. Adopting on epoch change is what makes
     * the wedge impossible.
     *
     * Two same-package handheld nodes publishing under different epochs at once would
     * flap between them — the parked two-phone topology (#173), not a shape a
     * one-phone-one-watch lifter reaches.
     */
    private fun supersedes(incoming: WatchSnapshot, held: WatchSnapshot): Boolean =
        incoming.epoch != held.epoch || incoming.revision > held.revision

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
     * (#173).
     *
     * Retried for as long as the process lives, never abandoned. This client is a
     * process singleton built once from the Application, so "give up and let the next
     * app open try again" would have been a lie: giving up means no reconnect flush and
     * no inbound snapshot for the rest of the process's life. The cadence pays for that
     * promise — see [retryDelayMillis].
     */
    private fun <T> Flow<T>.retryingRegistration(what: String): Flow<T> =
        retryWhen { cause, priorFailures ->
            onWarning("registering for $what failed; retrying", cause)
            delay(retryDelayMillis(priorFailures))
            true
        }

    /**
     * Exponential from [RETRY_BASE_MILLIS] (1s, 2s, 4s…) up to a [RETRY_CAP_MILLIS]
     * ceiling, then that ceiling forever — quick enough to catch a Play-services
     * hiccup within seconds, slow enough that a genuinely broken node is polled a few
     * times an hour rather than continuously. Scattered ±25% so a watch and its
     * neighbours don't settle into one metronome.
     */
    private fun retryDelayMillis(priorFailures: Long): Long {
        val shift = priorFailures.coerceAtMost(MAX_BACKOFF_SHIFT).toInt()
        val backoff = minOf(RETRY_BASE_MILLIS shl shift, RETRY_CAP_MILLIS)
        return (backoff * (0.75 + retryJitter() / 2)).toLong()
    }

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
            onWarning("$what failed", e)
        }
    }

    internal companion object {
        private const val TAG = "WatchClient"

        /** First backoff; each failure doubles it. */
        const val RETRY_BASE_MILLIS = 1_000L

        /** The ceiling the backoff settles at, and stays at. */
        const val RETRY_CAP_MILLIS = 300_000L

        /** Keeps the doubling inside a Long however long a phone stays broken. */
        private const val MAX_BACKOFF_SHIFT = 20L
    }
}
