package cloud.trotter.log.strength.wear.glance

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** Civil-day and zone changes invalidate both glance surfaces even without a DataItem push. */
class CivilDayChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_DATE_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, DayComplicationService::class.java))
            .requestUpdateAll()
        TileService.getUpdater(context).requestUpdate(DayTileService::class.java)
    }
}
