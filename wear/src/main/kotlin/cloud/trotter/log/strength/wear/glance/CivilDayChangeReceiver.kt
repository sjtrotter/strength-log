package cloud.trotter.log.strength.wear.glance

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Zone changes arrive as the exempt implicit broadcast; midnight arrives as the
 * explicit rollover alarm each glance render schedules ([CivilDayFreshness]).
 * Either way both surfaces re-render and the next rollover is re-armed, so the
 * chain self-heals across reboots on the first glance after boot.
 */
class CivilDayChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != ACTION_CIVIL_DAY_ROLLOVER) return
        CivilDayFreshness.scheduleNextRollover(context)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, DayComplicationService::class.java))
            .requestUpdateAll()
        TileService.getUpdater(context).requestUpdate(DayTileService::class.java)
    }

    companion object {
        const val ACTION_CIVIL_DAY_ROLLOVER = "cloud.trotter.log.strength.wear.CIVIL_DAY_ROLLOVER"
    }
}
