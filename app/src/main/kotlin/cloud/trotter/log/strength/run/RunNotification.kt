package cloud.trotter.log.strength.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.openAppPendingIntent
import cloud.trotter.log.strength.ui.day.cardioActivityNameRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class RunNotification @Inject constructor(@ApplicationContext private val context: Context) {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.run_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun build(notice: RunNotice?, setting: DistanceUnit?): Notification {
        val text = if (setting == null) context.getString(R.string.gps_finding) else when (notice?.live?.status) {
            GpsLiveStatus.FINDING -> context.getString(R.string.gps_finding)
            GpsLiveStatus.NO_SIGNAL -> context.getString(R.string.gps_no_signal)
            else -> notice?.let { current ->
                current.live.metres?.let { DistanceUnit.forActivity(setting, current.activity).format(it) }
            }
                ?: context.getString(R.string.gps_finding)
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_barbell_monochrome)
            .setContentTitle(context.getString(notice?.let { cardioActivityNameRes(it.activity) } ?: R.string.gps_finding))
            .setContentText(text)
            .setUsesChronometer(notice != null)
            .setWhen(notice?.startedAtWall ?: 0L)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPendingIntent(context, 0))
            .build()
    }

    companion object {
        const val CHANNEL = "run"
        const val ID = 7101
    }
}
