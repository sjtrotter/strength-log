package cloud.trotter.log.strength.playtest

import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One monotonic, real-time budget shared by lookup, action, settling, and capture. */
internal class StepDeadline(private val timeoutMs: Long, private val index: Int? = null) : IdlingResource {
    private val started = System.nanoTime()
    private val remainingNanos: Long get() = timeoutMs * 1_000_000 - (System.nanoTime() - started)
    val expired: Boolean get() = remainingNanos <= 0

    // Compose also polls this during nested waits inside gestures and lazy scrolling.
    override val isIdleNow: Boolean get() {
        checkTime()
        return true
    }

    fun checkTime() {
        if (expired) throw timeout()
    }

    fun timeout(cause: Throwable? = null): PlaytestError = PlaytestError(
        "timeout", "${index?.let { "Step $it" } ?: "Launch"} exceeded its ${timeoutMs}ms real-time deadline",
        cause = cause,
    )

    fun <T> withWatchdog(onTimeout: (PlaytestError) -> Unit, block: () -> T): T {
        val lock = Any()
        var finished = false
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "playtest-watchdog").apply { isDaemon = true }
        }
        watchdog.schedule({
            synchronized(lock) {
                if (!finished) {
                    try {
                        onTimeout(timeout())
                    } finally {
                        Runtime.getRuntime().halt(1)
                    }
                }
            }
        }, TimeUnit.NANOSECONDS.toMillis(remainingNanos.coerceAtLeast(0)) + HANG_GRACE_MS, TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            synchronized(lock) { finished = true }
            watchdog.shutdownNow()
        }
    }

    fun <T> run(compose: ComposeTestRule, block: () -> T): T {
        val master = IdlingPolicies.getMasterIdlingPolicy()
        val resource = IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
        val runner = Thread.currentThread()
        val lock = Any()
        var finished = false
        var interrupted = false
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "playtest-deadline").apply { isDaemon = true }
        }
        // Compose's root latch and ActivityScenario's lifecycle waits do not use Espresso's policy.
        val alarm = watchdog.schedule({
            synchronized(lock) {
                if (!finished) {
                    interrupted = true
                    runner.interrupt()
                }
            }
        }, remainingNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
        var registered = false
        try {
            compose.registerIdlingResource(this)
            registered = true
            return call(block)
        } finally {
            synchronized(lock) {
                finished = true
                alarm.cancel(false)
                if (interrupted) Thread.interrupted()
            }
            watchdog.shutdownNow()
            try {
                if (registered) compose.unregisterIdlingResource(this)
            } finally {
                IdlingPolicies.setMasterPolicyTimeout(master.idleTimeout, master.idleTimeoutUnit)
                IdlingPolicies.setIdlingResourceTimeout(resource.idleTimeout, resource.idleTimeoutUnit)
            }
        }
    }

    fun <T> call(block: () -> T): T {
        checkTime()
        // Robolectric's Compose idling strategy reads Espresso's master policy.
        // Refresh both policies before each call, rather than giving each fetch a new budget.
        val millis = remainingNanos / 1_000_000
        if (millis < 1) throw timeout()
        IdlingPolicies.setMasterPolicyTimeout(millis, TimeUnit.MILLISECONDS)
        IdlingPolicies.setIdlingResourceTimeout(millis, TimeUnit.MILLISECONDS)
        val result = try {
            block()
        } catch (error: Throwable) {
            if (error.isWaitTimeout() || expired && error !is PlaytestError) throw timeout(error)
            throw error
        }
        checkTime()
        return result
    }

    fun <T> io(block: suspend () -> T): T = call {
        runBlocking {
            withTimeout((remainingNanos / 1_000_000).coerceAtLeast(1)) {
                withContext(Dispatchers.IO) { block() }
            }
        }
    }

    fun pause(compose: ComposeTestRule): Boolean {
        if (expired) return false
        call { compose.mainClock.advanceTimeByFrame() }
        call { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val nanos = remainingNanos.coerceAtMost(TimeUnit.MILLISECONDS.toNanos(10))
        try {
            if (nanos > 0) TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (error: InterruptedException) {
            if (!expired) throw error
            return false
        }
        return !expired
    }
}

internal fun Throwable.isWaitTimeout(): Boolean = generateSequence(this) { it.cause }.any {
    it is java.util.concurrent.TimeoutException || it is kotlinx.coroutines.TimeoutCancellationException ||
        it is androidx.test.espresso.AppNotIdleException || it is androidx.test.espresso.IdlingResourceTimeoutException ||
        it is androidx.compose.ui.test.ComposeTimeoutException ||
        it is androidx.compose.ui.test.junit4.android.ComposeNotIdleException
}

/** The watchdog only catches a step that cannot return. A slow but progressing slice of an
 *  advance can overrun the deadline by seconds, so the grace is generous. */
private const val HANG_GRACE_MS = 30_000L
