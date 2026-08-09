package cloud.trotter.log.strength.wear.ui

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Schedules one in-process wakeup alarm at an `elapsedRealtime()` instant. */
interface RestAlarmScheduler {
    fun schedule(deadlineMillis: Long, onAlarm: () -> Unit)
    fun cancel()
}

/**
 * [AlarmManager] adapter for [RestTimerController]. The listener overload is exempt
 * from exact-alarm permission checks and is valid only while this process lives.
 */
class AndroidRestAlarmScheduler(context: Context) : RestAlarmScheduler {
    private val alarmManager = context.applicationContext
        .getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: AlarmManager.OnAlarmListener? = null

    override fun schedule(deadlineMillis: Long, onAlarm: () -> Unit) {
        cancel()
        lateinit var next: AlarmManager.OnAlarmListener
        next = AlarmManager.OnAlarmListener {
            // A callback already handed to the handler outlives cancel(); a
            // stale one must not null out a NEWER listener or that alarm
            // becomes uncancellable (it would still wake the device).
            if (listener === next) listener = null
            onAlarm()
        }
        listener = next
        alarmManager.setExact(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            deadlineMillis,
            ALARM_TAG,
            next,
            handler,
        )
    }

    override fun cancel() {
        listener?.let(alarmManager::cancel)
        listener = null
    }

    private companion object {
        const val ALARM_TAG = "strengthlog:rest-timer"
    }
}

/**
 * Owns the foreground-lifetime, ambient-punctual buzz used by rests and timed
 * holds. [arm] installs one exact elapsed-realtime wakeup alarm; replacing or
 * disarming it cancels the previous alarm. The interactive countdown is driven by
 * composition and does not depend on this controller.
 *
 * Delivery is guaranteed only while the app process and root [WearApp] are alive
 * and the display is interactive or ambient (a lit or dim screen never dozes).
 * True Doze — screen off, device still — defers setExact like any exact alarm;
 * the departed wake-lock design fared no better there, because Doze ignores
 * partial wake locks too. [close] is called when that root is disposed, including
 * Activity destruction, and cancels the alarm. Delivery after process death is not
 * part of the contract; the phone remains the workout source of truth.
 */
class RestTimerController(
    private val alarmScheduler: RestAlarmScheduler,
    private val elapsedRealtime: () -> Long,
    private val buzz: () -> Unit,
) : AutoCloseable {

    /** The timer currently pending, or null when idle. */
    var activeRest by mutableStateOf<ActiveRest?>(null)
        private set

    data class ActiveRest(val deadlineMillis: Long, val totalSeconds: Int)

    /**
     * Arms a single buzz for [deadlineMillis], an `elapsedRealtime()` instant.
     * Re-arming the same live deadline is idempotent; any other arm replaces it.
     * A deadline already reached is ignored without buzzing.
     */
    fun arm(deadlineMillis: Long, totalSeconds: Int) {
        if (activeRest?.deadlineMillis == deadlineMillis) return
        disarm()
        if (RestTimer.isExpired(deadlineMillis, elapsedRealtime())) return

        val armed = ActiveRest(deadlineMillis, totalSeconds)
        activeRest = armed
        alarmScheduler.schedule(deadlineMillis) {
            if (activeRest == armed) {
                activeRest = null
                buzz()
            }
        }
    }

    /** Cancels the pending rest or timed-hold buzz without firing it. */
    fun disarm() {
        alarmScheduler.cancel()
        activeRest = null
    }

    override fun close() = disarm()
}

/** Creates the Android-backed controller used by the app root. */
fun restTimerController(context: Context): RestTimerController {
    val appContext = context.applicationContext
    return RestTimerController(
        alarmScheduler = AndroidRestAlarmScheduler(appContext),
        elapsedRealtime = SystemClock::elapsedRealtime,
        buzz = {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createOneShot(BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        },
    )
}

private const val BUZZ_MILLIS = 400L
