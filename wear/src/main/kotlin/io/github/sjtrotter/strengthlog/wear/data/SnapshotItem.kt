package io.github.sjtrotter.strengthlog.wear.data

import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import io.github.sjtrotter.strengthlog.domain.sync.SyncCodec
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.sync.WearSyncPaths
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
            items.firstNotNullOfOrNull(::decode)
        } finally {
            items.release()
        }
    }
}
