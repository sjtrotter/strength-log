package cloud.trotter.log.strength.wear

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import cloud.trotter.log.strength.wear.data.DataLayerWatchClient
import cloud.trotter.log.strength.wear.data.PendingEditStore
import cloud.trotter.log.strength.wear.data.WatchTrackerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The watch's single Preferences DataStore, holding the pending-edit queue. */
private val Context.wearSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_sync")

/**
 * The watch's Application root (`:wear` has no Hilt — DI here stays a single
 * hand-built graph, spec principle 1). It owns the one process-wide
 * [WatchTrackerClient] so the Data Layer listener and the pending-edit queue are
 * registered exactly once and outlive Activity recreation (rotation, ambient exit)
 * — a per-Activity client would leak DataClient listeners and re-prime needlessly.
 */
class StrengthLogWearApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val watchClient: WatchTrackerClient by lazy {
        DataLayerWatchClient(
            dataClient = Wearable.getDataClient(this),
            messageClient = Wearable.getMessageClient(this),
            nodeClient = Wearable.getNodeClient(this),
            queue = PendingEditStore(wearSyncDataStore),
            scope = appScope,
        )
    }
}
