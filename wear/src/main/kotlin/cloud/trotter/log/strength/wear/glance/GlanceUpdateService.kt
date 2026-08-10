package cloud.trotter.log.strength.wear.glance

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import cloud.trotter.log.strength.domain.sync.WearSyncPaths
import cloud.trotter.log.strength.wear.StrengthLogWearApp

/**
 * The whole freshness story for the two glance surfaces: when the phone publishes a
 * new snapshot, Play Services starts this service (waking the process if it has to)
 * and we ask the face and the carousel to come back for the new numbers.
 *
 * That is the only surface-refresh trigger. Neither surface polls or schedules work — the
 * complication pins UPDATE_PERIOD_SECONDS=0 in the manifest to say so out loud.
 * A set ticked on the phone is what makes today's count change, and that event
 * already travels over the Data Layer.
 *
 * The services do their own reading; this only says "ask again". Requesting an
 * update for a complication nobody has installed, or a tile nobody has added, is a
 * no-op, so there is nothing to track on this side.
 *
 * Capability changes also start this service in a cold process. Touching
 * [StrengthLogWearApp.watchClient] constructs the process singleton, whose initial
 * prime and reachability listener own queue draining; this service must not create a
 * second drain path or weaken the client's serialization.
 */
class GlanceUpdateService : WearableListenerService() {

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        (application as StrengthLogWearApp).watchClient
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val snapshotChanged = dataEvents.any {
            it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearSyncPaths.SNAPSHOT
        }
        if (!snapshotChanged) return

        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, DayComplicationService::class.java))
            .requestUpdateAll()
        TileService.getUpdater(this).requestUpdate(DayTileService::class.java)
    }
}
