package cloud.trotter.log.strength.wear

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import cloud.trotter.log.strength.wear.data.DataLayerPhoneLink
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

    /**
     * The scope every outbound edit runs on. It has no `CoroutineExceptionHandler` on
     * purpose — there is nothing sensible to do with a failure here beyond logging it,
     * and [DataLayerWatchClient] does that itself rather than letting anything reach
     * the default handler, which would kill the app mid-workout (#173).
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val watchClient: WatchTrackerClient by lazy {
        DataLayerWatchClient(
            link = DataLayerPhoneLink(
                dataClient = Wearable.getDataClient(this),
                messageClient = Wearable.getMessageClient(this),
                nodeClient = Wearable.getNodeClient(this),
                capabilityClient = Wearable.getCapabilityClient(this),
            ),
            queue = PendingEditStore(wearSyncDataStore),
            scope = appScope,
        )
    }
}
