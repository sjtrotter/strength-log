package cloud.trotter.log.strength.playtest

import android.os.Looper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf

/** Storage may await main-thread work, so the caller must keep pumping the paused looper. */
internal fun <T> waitForStorage(
    timeoutMs: Long,
    timeout: () -> PlaytestError = { PlaytestError("timeout", "Storage exceeded its ${timeoutMs}ms real-time deadline") },
    block: suspend () -> T,
): T {
    val started = System.nanoTime()
    val limitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val result = AtomicReference<Result<T>?>(null)
    val job = Job()
    try {
        CoroutineScope(Dispatchers.IO + job).launch {
            result.set(runCatching { block() })
        }
        while (true) {
            if (System.nanoTime() - started >= limitNanos) throw timeout()
            result.get()?.let { return it.getOrThrow() }
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = limitNanos - (System.nanoTime() - started)
            if (remaining <= 0) throw timeout()
            try {
                TimeUnit.NANOSECONDS.sleep(remaining.coerceAtMost(TimeUnit.MILLISECONDS.toNanos(10)))
            } catch (error: InterruptedException) {
                if (System.nanoTime() - started >= limitNanos) throw timeout()
                throw error
            }
        }
    } finally {
        job.cancel()
    }
}
