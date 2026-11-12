package cloud.trotter.log.strength.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.day.cardioActivityNameRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class RunNotification @Inject constructor(@ApplicationContext private val context: Context) {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.run_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun build(notice: RunNotice, setting: DistanceUnit): Notification {
        val text = when (notice.live.status) {
            GpsLiveStatus.FINDING -> context.getString(R.string.gps_finding)
            GpsLiveStatus.NO_SIGNAL -> context.getString(R.string.gps_no_signal)
            else -> notice.live.metres?.let { DistanceUnit.forActivity(setting, notice.activity).format(it) }
                ?: context.getString(R.string.gps_finding)
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_barbell_monochrome)
            .setContentTitle(context.getString(cardioActivityNameRes(notice.activity)))
            .setContentText(text)
            .setUsesChronometer(true)
            .setWhen(notice.startedAtWall)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        const val CHANNEL = "run"
        const val ID = 7101
    }
}
