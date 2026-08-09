package cloud.trotter.log.strength.wear.data

import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.WearSyncPaths
import kotlinx.coroutines.tasks.await

/**
 * The one place the watch turns the phone's snapshot DataItem into a
 * [WatchSnapshot]. Three readers share it now — the app's [DataLayerWatchClient],
 * the tile and the watch-face complication — and only the first of them has an
 * Activity, so the read has to work from a bare Service with no app state.
 *
 * The Data Layer persists the item on this node, which is what makes the glance
 * surfaces honest offline: [latest] still answers from the last sync when the phone
 * is out of range and the phone app is dead.
 *
 * Decoding never throws: an item from a mismatched schema or a truncated payload
 * reads as "nothing to show" rather than taking a service down with it.
 */
object SnapshotItem {

    /** The DataClient listener filter that matches the snapshot item and nothing else. */
    val uri: Uri = Uri.Builder().scheme("wear").path(WearSyncPaths.SNAPSHOT).build()

    /** [item] decoded, or null when it isn't the snapshot or its bytes don't parse. */
    fun decode(item: DataItem): WatchSnapshot? {
        if (item.uri.path != WearSyncPaths.SNAPSHOT) return null
        return runCatching { SyncCodec.decodeSnapshot(item.data ?: ByteArray(0)) }.getOrNull()
    }

    /** The snapshot cached on this node; null when the phone has never published one. */
    suspend fun latest(dataClient: DataClient): WatchSnapshot? {
        val items = dataClient.dataItems.await()
        return try {
            newest(items.mapNotNull(::decode))
        } finally {
            items.release()
        }
    }

}

/**
 * Which of several cached snapshots is *the* snapshot: highest epoch, then highest
 * revision within it.
 *
 * There is normally exactly one item, but the cache is a set keyed by creator node and
 * path, and nothing about the read guarantees an order — taking whichever decoded
 * first could hand a glance surface a stale day. Revision alone can't decide it
 * either: an item an obsolete node left behind can carry a *higher* revision than the
 * phone's current one, because clearing the phone app's data restarts the count
 * ([WatchSnapshot.epoch]). Comparing the epoch first is what stops that stale item
 * from winning — on the tile, on the complication, and as the baseline the app's
 * client primes from.
 *
 * Top-level rather than a member so a plain JVM test can reach it: [SnapshotItem]'s
 * own initializer builds an `android.net.Uri`, which no unit test has.
 */
internal fun newest(candidates: List<WatchSnapshot>): WatchSnapshot? =
    candidates.maxWithOrNull(compareBy({ it.epoch }, { it.revision }))
