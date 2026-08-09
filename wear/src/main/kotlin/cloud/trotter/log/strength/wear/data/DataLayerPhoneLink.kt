package cloud.trotter.log.strength.wear.data

import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

private const val TAG = "PhoneLink"

/**
 * [PhoneLink] over Google Play services — the only place in `:wear` that names a
 * Data Layer client. Holds no state of its own: every method is a straight
 * translation of a Play-services call into the vocabulary [DataLayerWatchClient]
 * speaks.
 */
class DataLayerPhoneLink(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
    private val nodeClient: NodeClient,
    private val capabilityClient: CapabilityClient,
) : PhoneLink {

    override suspend fun cachedSnapshot(): WatchSnapshot? = SnapshotItem.latest(dataClient)

    override fun snapshotChanges(): Flow<WatchSnapshot> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return@forEach
                SnapshotItem.decode(event.dataItem)?.let { trySend(it) }
            }
            events.release()
        }
        // Deliberately host-less: the filter is a match-all on the path, which is what
        // the whole settle/ack flow already runs on. A `wear://*/...` authority would
        // be the same match-all wearing a disguise (#173 refutes pinning it here).
        dataClient.addListener(listener, SnapshotItem.uri, DataClient.FILTER_LITERAL).await()
        awaitClose { dataClient.removeListener(listener) }
    }

    override fun phoneReachability(): Flow<Boolean> = callbackFlow {
        val listener = CapabilityClient.OnCapabilityChangedListener { info ->
            trySend(info.nodes.isNotEmpty())
        }
        capabilityClient.addListener(listener, PHONE_CAPABILITY).await()
        // The listener reports transitions only, and a phone already in range when the
        // watch app starts is a state, not an event — the queue has to drain against
        // that too. Queried *after* registering so a change in the gap can't be missed.
        trySend(phoneNodeIds().isNotEmpty())
        awaitClose { capabilityClient.removeListener(listener) }
    }

    override suspend fun send(path: String, bytes: ByteArray) {
        val targets = phoneNodeIds().ifEmpty { connectedNodeIds() }
        targets.forEach { id ->
            quietly("sending $path to $id", Unit) { messageClient.sendMessage(id, path, bytes).await() }
        }
    }

    /**
     * The reachable nodes running the phone app. Empty when Play services can't
     * answer, or when the phone build predates the capability declaration — [send]
     * then broadcasts at every connected node, which is exactly what shipped before
     * capabilities existed here.
     */
    private suspend fun phoneNodeIds(): List<String> =
        quietly("resolving the phone node", emptyList()) {
            capabilityClient.getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await().nodes.map { it.id }
        }

    private suspend fun connectedNodeIds(): List<String> =
        quietly("listing connected nodes", emptyList()) {
            nodeClient.connectedNodes.await().map { it.id }
        }
}

/**
 * [block]'s result, or [fallback] when Play services refuses. Cancellation still
 * propagates: a cancelled scope must not be mistaken for an absent phone, and
 * swallowing it would keep a torn-down send loop firing at every node.
 */
private suspend fun <T> quietly(what: String, fallback: T, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        fallback
    }
