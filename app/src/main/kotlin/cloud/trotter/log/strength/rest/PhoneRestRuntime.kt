package cloud.trotter.log.strength.rest

import android.Manifest
import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.StrengthLogApp
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.ui.components.AppHaptics
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneRestRuntime @Inject constructor(@ApplicationContext private val context: Context) : RestRuntime {
    override val available = true
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private var foreground = false
    private var armedDeadline: Long? = null
    private var completedDeadline: Long? = null
    val isForeground: Boolean get() = foreground

    init { createChannel(context) }

    fun setForeground(value: Boolean, rest: PhoneRest?) {
        foreground = value
        if (value) NotificationManagerCompat.from(context).cancel(RUNNING_ID)
        else rest?.let(::showRunning)
    }

    override fun arm(rest: PhoneRest) {
        // A queued receiver or onStart can still see the completed rest during its UI grace period.
        if (rest.deadlineEpochMillis == completedDeadline) return
        alarms.cancel(alarmIntent(context))
        armedDeadline = rest.deadlineEpochMillis
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, rest.deadlineEpochMillis, alarmIntent(context))
        if (!foreground) showRunning(rest)
    }

    override fun cancel() {
        alarms.cancel(alarmIntent(context))
        armedDeadline = null
        NotificationManagerCompat.from(context).cancel(RUNNING_ID)
        NotificationManagerCompat.from(context).cancel(OVER_ID)
    }

    @Synchronized
    override fun complete(): Boolean {
        if (armedDeadline == null) return false
        completedDeadline = armedDeadline
        cancel()
        if (foreground) {
            AppHaptics.restComplete(context)
        } else if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(OVER_ID, overNotification())
        }
        return true
    }

    private fun showRunning(rest: PhoneRest) {
        if (rest.deadlineEpochMillis == completedDeadline || !canNotify(context)) return
        val notification = runningNotification(rest)
        // Lint wants the permission check beside the call, not behind a helper.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(RUNNING_ID, notification)
    }

    internal fun runningNotification(rest: PhoneRest): Notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_barbell_monochrome)
        .setContentTitle(context.getString(R.string.rest_notification_title))
        .setContentText(rest.nextSetLabel.takeIf { it.isNotBlank() }?.let { context.getString(R.string.rest_notification_next, it) })
        .setWhen(rest.deadlineEpochMillis)
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(openAppIntent(context))
        .addAction(0, context.getString(R.string.rest_notification_add), actionIntent(context, RestActionReceiver.ADD))
        .addAction(0, context.getString(R.string.rest_notification_skip), actionIntent(context, RestActionReceiver.SKIP))
        .build()

    internal fun overNotification(): Notification = NotificationCompat.Builder(context, OVER_CHANNEL)
        .setSmallIcon(R.drawable.ic_barbell_monochrome)
        .setContentTitle(context.getString(R.string.rest_over))
        .setAutoCancel(true)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setContentIntent(openAppIntent(context))
        .build()

    companion object {
        const val CHANNEL = "rest"
        const val OVER_CHANNEL = "rest_over"
        const val RUNNING_ID = 7001
        const val OVER_ID = 7002
        fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fun createChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.rest_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    enableVibration(true)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(OVER_CHANNEL, context.getString(R.string.rest_over_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(true)
                },
            )
        }
        private fun actionIntent(context: Context, action: String) = PendingIntent.getBroadcast(
            context, 0, Intent(context, RestActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
            context, 0, Intent(context, RestAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        private fun openAppIntent(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

interface RestRuntime {
    val available: Boolean
    fun arm(rest: PhoneRest)
    fun cancel()
    fun complete(): Boolean
}

object NoOpRestRuntime : RestRuntime {
    override val available = false
    override fun arm(rest: PhoneRest) = Unit
    override fun cancel() = Unit
    override fun complete() = false
}

class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val repo = EntryPointAccessors.fromApplication(context, StrengthLogApp.RepositoryEntryPoint::class.java).trackerRepository()
        val runtime = EntryPointAccessors.fromApplication(context, StrengthLogApp.RestRuntimeEntryPoint::class.java).phoneRestRuntime()
        val scope = EntryPointAccessors.fromApplication(context, StrengthLogApp.AppScopeEntryPoint::class.java).appScope()
        scope.launch {
            try {
                PhoneRestActions.locked {
                    val rest = repo.phoneRestFlow.first() ?: return@locked
                    // An alarm already in flight may predate +15 s or a replacement rest.
                    if (rest.deadlineEpochMillis > System.currentTimeMillis()) return@locked
                    if (!runtime.isForeground) runtime.arm(rest)
                    runtime.complete()
                    if (!runtime.isForeground) repo.setPhoneRest(null)
                }
            } finally { pending.finish() }
        }
    }
}
