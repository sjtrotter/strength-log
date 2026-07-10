package io.github.sjtrotter.strengthlog.wear.data

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.SyncCodec
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.sync.WearSyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The real [WatchTrackerClient] over the Wearable Data Layer (#20), replacing
 * [FakeWatchClient] in the running app. Reads the phone's snapshot from the
 * DataClient — which the Data Layer persists on this node, so the last snapshot
 * survives a phone-app (and watch-app) restart — and sends edits over the
 * MessageClient.
 *
 * The §11.4 mechanism lives here: MessageClient is fire-and-forget, so every edit
 * is also queued in [PendingEditStore] and re-sent until a later snapshot reflects
 * it. Triggers to drain the queue are (a) the very first prime on start and (b)
 * every snapshot change — when a dead phone app relaunches, its publisher emits a
 * bumped-revision snapshot, which lands here and re-sends the still-unapplied edit.
 * The phone dedupes replays, so blind re-sends are safe.
 */
class DataLayerWatchClient(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
    private val nodeClient: NodeClient,
    private val queue: PendingEditStore,
    private val scope: CoroutineScope,
) : WatchTrackerClient {

    private val snapshots = MutableStateFlow<WatchSnapshot?>(null)

    init {
        scope.launch { prime() }
        snapshotChanges()
            .filterNotNull()
            .onEach { onSnapshot(it) }
            .launchIn(scope)
    }

    override fun snapshotFlow(): Flow<WatchSnapshot> = snapshots.filterNotNull()

    override fun pendingCountFlow(): Flow<Int> = queue.countFlow()

    override suspend fun sendEdit(delta: SetEditDelta) {
        // Re-stamp with a strictly monotonic, persisted editedAtMillis: the caller's
        // wall clock can stamp two distinct edits into the same millisecond, and the
        // phone's per-row dedupe would then drop the second as a replay.
        val stamped = delta.copy(editedAtMillis = queue.issueStamp(delta.editedAtMillis))
        // Echo the edit on-wrist immediately (spec §9); the phone's next snapshot —
        // with cascade/seeding applied — overwrites this and is the real ack.
        snapshots.value?.let { current ->
            snapshots.value = current.copy(
                day = current.day.copy(exercises = WatchEditOptimism.apply(current.day.exercises, stamped)),
            )
        }
        queue.enqueue(stamped)
        send(stamped)
    }

    /** The last snapshot the Data Layer cached on this node (survives restarts). */
    private suspend fun prime() {
        try {
            val items = dataClient.dataItems.await()
            val latest = items.firstOrNull { it.uri.path == WearSyncPaths.SNAPSHOT }
            latest?.let { snapshots.value = SyncCodec.decodeSnapshot(it.data ?: ByteArray(0)) }
            items.release()
        } catch (e: Exception) {
            Log.w(TAG, "priming snapshot failed", e)
        }
        drainQueue()
    }

    private fun snapshotChanges(): Flow<WatchSnapshot?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WearSyncPaths.SNAPSHOT) {
                    val decoded = runCatching {
                        SyncCodec.decodeSnapshot(event.dataItem.data ?: ByteArray(0))
                    }.getOrNull()
                    if (decoded != null) trySend(decoded)
                }
            }
            events.release()
        }
        val uri = Uri.Builder().scheme("wear").path(WearSyncPaths.SNAPSHOT).build()
        dataClient.addListener(listener, uri, DataClient.FILTER_LITERAL).await()
        awaitClose { dataClient.removeListener(listener) }
    }

    private suspend fun onSnapshot(snapshot: WatchSnapshot) {
        snapshots.value = snapshot
        // Drop-settled runs atomically inside the store (one DataStore.edit), so a
        // sendEdit enqueuing concurrently can't be wiped between read and write —
        // no lock needed here.
        queue.reconcileAgainst(snapshot)
        drainQueue()
    }

    private suspend fun drainQueue() {
        queue.all().forEach { send(it) }
    }

    private suspend fun send(delta: SetEditDelta) {
        try {
            val bytes = SyncCodec.encodeDelta(delta)
            val nodes = nodeClient.connectedNodes.await()
            nodes.forEach { node ->
                runCatching { messageClient.sendMessage(node.id, WearSyncPaths.SET_EDIT, bytes).await() }
            }
        } catch (e: Exception) {
            // No reachable node right now — the delta stays queued and is re-sent
            // on the next snapshot/connectivity signal.
            Log.w(TAG, "set-edit send failed; keeping it queued", e)
        }
    }

    private companion object {
        const val TAG = "WatchClient"
    }
}
