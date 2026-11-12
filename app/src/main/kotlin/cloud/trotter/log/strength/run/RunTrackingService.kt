package cloud.trotter.log.strength.run

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cloud.trotter.log.strength.data.prefs.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

class ServiceTrackingForeground @Inject constructor(@ApplicationContext private val context: Context) : TrackingForeground {
    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, RunTrackingService::class.java))
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    override fun stop() { context.stopService(Intent(context, RunTrackingService::class.java)) }
}

@OptIn(FlowPreview::class)
@AndroidEntryPoint
class RunTrackingService : Service() {
    @Inject lateinit var tracker: RunTracker
    @Inject lateinit var notification: RunNotification
    @Inject lateinit var settings: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = tracker.notice.value
        try {
            ServiceCompat.startForeground(this, RunNotification.ID, notification.build(initial, setting = null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: IllegalStateException) {
            tracker.foregroundRefused()
            stopSelf()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            tracker.foregroundRefused()
            stopSelf()
            return START_NOT_STICKY
        }
        if (initial == null) { stopSelf(); return START_NOT_STICKY }
        if (updates == null) updates = scope.launch {
            launch {
                while (true) { delay(5_000L); tracker.updateNotice() }
            }
            combine(tracker.notice, settings.distanceUnitFlow) { notice, setting -> notice to setting }
                .sample(5_000L).collect { (notice, setting) ->
                    if (notice == null) return@collect
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            this@RunTrackingService, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED) return@collect
                    NotificationManagerCompat.from(this@RunTrackingService).notify(RunNotification.ID, notification.build(notice, setting))
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        tracker.endAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
