package cloud.trotter.log.strength.ui.day

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cloud.trotter.log.strength.ui.components.AppHaptics
import cloud.trotter.log.strength.domain.cardio.Clocks
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The clocks cardio needs, plus the boot counter that arbitrates between them.
 *  Keeping all three behind one seam makes reboot derivation testable. */
interface CardioClock {
    fun wallMillis(): Long
    fun elapsedRealtimeMillis(): Long
    fun bootCount(): Int
}

internal fun CardioClock.clocks(): Clocks = Clocks(wallMillis(), elapsedRealtimeMillis(), bootCount())

@Singleton
class SystemCardioClock @Inject constructor(
    @ApplicationContext private val context: Context,
) : CardioClock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int =
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
}

/** One in-process exact elapsed-realtime alarm. Implementations replace any pending alarm. */
interface CardioAlarm {
    fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit)
    /** Same as [arm] without the buzz: a pocketed run's wait for its boundary to settle. */
    fun armQuiet(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = arm(deadlineElapsedMillis, identity, onBoundary)
    fun cancel()
    /** Same end-boundary feedback when the foreground ticker wins the alarm race. */
    fun buzz() = Unit
}

/**
 * Phone twin of the wear exact-alarm adapter. `OnAlarmListener` needs no exact-alarm permission.
 * Delivery holds while the process lives: screen on or off, on any screen, until the set ends
 * or the ViewModel that armed it clears. With the screen off the top app is never frozen,
 * so delivery is on time. A cached app can be frozen after switching to another app.
 * The buzz uses alarm usage because API 33+ drops a background vibration without it.
 * The alarm is exact, and light Doze, which applies while the phone moves, doesn't defer it.
 * Deep Doze needs the phone still for a long time; it can defer an alarm to its next maintenance window.
 * Process death loses the buzz, never the data: reconcile uses exact deadlines.
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

    override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) =
        schedule(deadlineElapsedMillis, identity, true, onBoundary)

    override fun armQuiet(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) =
        schedule(deadlineElapsedMillis, identity, false, onBoundary)

    private fun schedule(deadlineElapsedMillis: Long, identity: String, buzz: Boolean, onBoundary: () -> Unit) {
        val alarmIdentity = "$identity:$deadlineElapsedMillis"
        if (armedIdentity == alarmIdentity) return
        cancel()
        lateinit var next: AlarmManager.OnAlarmListener
        next = AlarmManager.OnAlarmListener {
            if (listener !== next || armedIdentity != alarmIdentity) return@OnAlarmListener
            listener = null
            armedIdentity = null
            if (buzz) vibrate()
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

    override fun buzz() = vibrate()

    private fun vibrate() = AppHaptics.timerEnd(appContext, alarm = true)

    private companion object {
        const val ALARM_TAG = "strengthlog:cardio-boundary"
    }
}
