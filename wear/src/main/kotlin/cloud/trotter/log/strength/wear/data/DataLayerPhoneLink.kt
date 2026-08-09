package cloud.trotter.log.strength.wear.data

import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.tasks.await

private const val TAG = "PhoneLink"

/**
 * [PhoneLink] over Google Play services — the only place in `:wear` that names a
 * Data Layer client. Holds no state of its own: every method is a straight
 * translation of a Play-services call into the vocabulary [DataLayerWatchClient]
 * speaks.
 *
 * Both listener flows are [conflate]d, because both carry *state* rather than
 * events — the current snapshot, and whether a phone is there. Conflating makes
 * latest-wins structural: a burst arriving faster than the client drains it collapses
 * to the newest value instead of overflowing a buffer and silently dropping whichever
 * one the channel happened to refuse.
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
                SnapshotItem.decode(event.dataItem)?.let { offer("a snapshot", it) }
            }
            events.release()
        }
        // Deliberately host-less: the filter is a match-all on the path, which is what
        // the whole settle/ack flow already runs on. A `wear://*/...` authority would
        // be the same match-all wearing a disguise (#173 refutes pinning it here).
        dataClient.addListener(listener, SnapshotItem.uri, DataClient.FILTER_LITERAL).await()
        awaitClose { dataClient.removeListener(listener) }
    }.conflate()

    override fun phoneReachability(): Flow<Boolean> = callbackFlow {
        val listener = CapabilityClient.OnCapabilityChangedListener { info ->
            offer("phone reachability", info.nodes.isNotEmpty())
        }
        capabilityClient.addListener(listener, PHONE_CAPABILITY).await()
        // The listener reports transitions only, and a phone already in range when the
        // watch app starts is a state, not an event — the queue has to drain against
        // that too. Queried *after* registering so a change in the gap can't be missed;
        // the client treats every `true` as a drain signal, so the two sources racing
        // costs a duplicate drain at worst.
        offer("phone reachability", phoneNodeIds().isNotEmpty())
        // The capability overload, not the listener-only one: that removes URI-filter
        // registrations, would leave this one live, and every reconstruction of this
        // flow would then stack another callback on the same listener object.
        awaitClose { capabilityClient.removeListener(listener, PHONE_CAPABILITY) }
    }.conflate()

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
 * Hands [value] to the collector, and says so when it can't. The flows above are
 * conflated, so the only way this fails is a closed channel — the collector is gone
 * and the listener is about to be unregistered, which is not worth a line in the log.
 * Anything else would mean a value went missing, which is.
 */
private fun <T> ProducerScope<T>.offer(what: String, value: T) {
    val result = trySend(value)
    if (result.isFailure && !result.isClosed) {
        Log.w(TAG, "dropped $what before the watch could read it: ${result.exceptionOrNull()}")
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
