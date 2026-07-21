package io.github.sjtrotter.strengthlog.wear.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the ambient-punctual half of the rest timer: the **single** buzz at zero
 * and the bounded wake lock that keeps it on time even if the watch dozes mid-rest
 * (redesign §2.3 / R5, Fable risk #2). It lives at the [WearApp] root — above the
 * ambient screen swap — so its countdown coroutine is *not* torn down when the
 * system drops into ambient and disposes the interactive tree; the visual
 * countdown is a separate, disposable composable ([RestCountdownScreen]) that this
 * controller does not depend on.
 *
 * Pure timing/decisions live in [RestTimer]; this class only does the Android IO
 * (Vibrator, PowerManager) and hosts the coroutine.
 *
 * Fire-once is structural: [arm] cancels any running timer before starting a new
 * one (one timer at a time), the coroutine buzzes exactly once when it reaches the
 * deadline, and a cancelled coroutine (skip / replace) never buzzes.
 *
 * Each job owns its wake lock as a coroutine-local and releases *that* instance in
 * its own `finally` — never a shared field. This matters when [arm] replaces a
 * still-active job: `cancel()` only queues the old coroutine's cancellation, so its
 * `finally` runs *after* the replacement has already acquired a fresh lock; a
 * shared field would let the departing job release the newcomer's lock and silently
 * void its ambient-punctuality.
 */
class RestTimerController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /** The rest currently pending, or null when idle. Read by [WearApp] to render
     *  the dim ambient REST line — survives the ambient swap because this
     *  controller does. */
    var activeRest by mutableStateOf<ActiveRest?>(null)
        private set

    data class ActiveRest(val deadlineMillis: Long, val nextLabel: String)

    private var job: Job? = null

    /**
     * Start (or idempotently keep) a deadline-anchored single-buzz timer for
     * [deadlineMillis] (an `elapsedRealtime()` instant). Safe to call from a
     * recomposing effect: re-arming for the same still-running deadline is a no-op,
     * which is also how the timer resumes after process death — the restored
     * [rememberSaveable][androidx.compose.runtime.saveable.rememberSaveable]
     * deadline re-arms the buzz for whatever time is left.
     */
    fun arm(deadlineMillis: Long, nextLabel: String) {
        if (job?.isActive == true && activeRest?.deadlineMillis == deadlineMillis) return
        job?.cancel()
        job = null

        val remaining = RestTimer.remainingMillis(deadlineMillis, SystemClock.elapsedRealtime())
        if (remaining <= 0L) {
            // Already past (e.g. restored long after the deadline) — nothing to
            // buzz; the caller advances the UI on its own.
            activeRest = null
            return
        }

        activeRest = ActiveRest(deadlineMillis, nextLabel)
        // Acquire the lock as a LOCAL and release that same instance in this job's
        // own finally — never a shared field. Replacing a still-active job only
        // *queues* the old coroutine's cancellation, so its finally runs after this
        // new lock is already acquired; a shared field would let the old job release
        // the new lock, silently voiding the replacement's ambient-punctuality.
        val wakeLock = acquireWakeLock(RestTimer.wakeLockTimeoutMillis(remaining))
        job = scope.launch {
            try {
                while (isActive && SystemClock.elapsedRealtime() < deadlineMillis) {
                    val left = deadlineMillis - SystemClock.elapsedRealtime()
                    delay(left.coerceIn(1L, 1_000L))
                }
                if (isActive) {
                    vibrateOnce()
                    if (activeRest?.deadlineMillis == deadlineMillis) activeRest = null
                }
            } finally {
                releaseWakeLock(wakeLock)
            }
        }
    }

    /**
     * Cancel the pending rest with **no** buzz (tap-to-skip, early advance, untick).
     * The cancelled job's own `finally` releases its lock; the bounded
     * [acquire][PowerManager.WakeLock.acquire] timeout is the backstop.
     */
    fun skip() {
        job?.cancel()
        job = null
        activeRest = null
    }

    private fun vibrateOnce() {
        val effect = VibrationEffect.createOneShot(BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator()?.vibrate(effect)
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun acquireWakeLock(timeoutMillis: Long): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
        // Bounded acquire: the OS force-releases at the timeout even if a code path
        // ever failed to — belt to the try/finally's braces.
        wl.acquire(timeoutMillis)
        return wl
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private companion object {
        const val BUZZ_MILLIS = 400L
        const val WAKE_LOCK_TAG = "strengthlog:rest-timer"
    }
}
