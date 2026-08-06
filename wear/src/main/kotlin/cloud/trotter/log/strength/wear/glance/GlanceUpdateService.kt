package cloud.trotter.log.strength.wear.glance

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import cloud.trotter.log.strength.domain.sync.WearSyncPaths

/**
 * The whole freshness story for the two glance surfaces: when the phone publishes a
 * new snapshot, Play Services starts this service (waking the process if it has to)
 * and we ask the face and the carousel to come back for the new numbers.
 *
 * That is the only trigger. Neither surface polls, schedules work, or declares an
 * update period — a set ticked on the phone is what makes today's count change, and
 * that event already travels over the Data Layer.
 *
 * The services do their own reading; this only says "ask again". Requesting an
 * update for a complication nobody has installed, or a tile nobody has added, is a
 * no-op, so there is nothing to track on this side.
 */
class GlanceUpdateService : WearableListenerService() {

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
