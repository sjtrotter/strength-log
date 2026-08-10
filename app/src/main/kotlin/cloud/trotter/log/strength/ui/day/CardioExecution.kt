package cloud.trotter.log.strength.ui.day

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The two clocks cardio needs. Keeping both behind one seam makes reboot derivation testable. */
interface CardioClock {
    fun wallMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

@Singleton
class SystemCardioClock @Inject constructor() : CardioClock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

/** One in-process exact elapsed-realtime alarm. Implementations replace any pending alarm. */
interface CardioAlarm {
    fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit)
    fun cancel()
}

/**
 * Phone twin of the wear exact-alarm adapter. `OnAlarmListener` needs no exact-alarm
 * permission. Delivery is guaranteed only while the app is foreground and the day
 * screen is live; C1 deliberately has no foreground service. Backgrounding cancels
 * the listener, and returning arms only the next future boundary.
 */
@Singleton
class AndroidCardioAlarm @Inject constructor(
    @ApplicationContext context: Context,
) : CardioAlarm {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: AlarmManager.OnAlarmListener? = null
    private var armedIdentity: String? = null

    override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
        val alarmIdentity = "$identity:$deadlineElapsedMillis"
        if (armedIdentity == alarmIdentity) return
        cancel()
        lateinit var next: AlarmManager.OnAlarmListener
        next = AlarmManager.OnAlarmListener {
            if (listener !== next || armedIdentity != alarmIdentity) return@OnAlarmListener
            listener = null
            armedIdentity = null
            vibrate()
            onBoundary()
        }
        listener = next
        armedIdentity = alarmIdentity
        alarmManager.setExact(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            deadlineElapsedMillis,
            ALARM_TAG,
            next,
            handler,
        )
    }

    override fun cancel() {
        listener?.let(alarmManager::cancel)
        listener = null
        armedIdentity = null
    }

    private fun vibrate() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val ALARM_TAG = "strengthlog:cardio-boundary"
        const val BUZZ_MILLIS = 400L
    }
}
