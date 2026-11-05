package cloud.trotter.log.strength.playtest

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/** runTest intercepts global coroutine failures before the thread's uncaught exception handler. */
internal class AppCrashes : AutoCloseable, IdlingResource {
    private val pending = AtomicReference<Throwable?>()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    // The coroutine test collector has no public observer API. Fail on an incompatible dependency
    // update instead of silently losing app exceptions again.
    private val collectorClass = Class.forName("kotlinx.coroutines.test.internal.ExceptionCollector")
    private val collector = collectorClass.getField("INSTANCE").get(null)

    init {
        val callback: (Throwable) -> Unit = ::record
        collectorClass.getMethod("addOnExceptionCallback", Any::class.java, Function1::class.java)
            .invoke(collector, this, callback)
        Thread.setDefaultUncaughtExceptionHandler { _, error -> record(error) }
    }

    fun record(error: Throwable) {
        pending.compareAndSet(null, error)
    }

    override val isIdleNow: Boolean get() {
        check()
        return true
    }

    fun check() {
        pending.get()?.let { throw it.appCrash() }
    }

    fun <T> step(block: () -> T): T {
        try {
            check()
            val result = block()
            check()
            return result
        } catch (error: Throwable) {
            val uncaught = pending.getAndSet(null)
            throw uncaught?.appCrash() ?: error
        }
    }

    fun <T> step(compose: ComposeTestRule, block: () -> T): T {
        compose.registerIdlingResource(this)
        try {
            return step(block)
        } finally {
            compose.unregisterIdlingResource(this)
        }
    }

    override fun close() {
        try {
            collectorClass.getMethod("removeOnExceptionCallback", Any::class.java).invoke(collector, this)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }
}

/** Paused Robolectric loopers rethrow callback failures on the test thread without an uncaught handler. */
@Implements(Handler::class)
internal class PlaytestHandler {
    @RealObject private lateinit var handler: Handler

    @Implementation
    fun dispatchMessage(message: Message) {
        try {
            reflector(HandlerReflector::class.java, handler).dispatchMessage(message)
        } catch (error: Throwable) {
            if (handler.looper == Looper.getMainLooper() && error.playtestError().kind == "crash") {
                throw error.appCrash()
            }
            throw error
        }
    }

    @ForType(Handler::class)
    interface HandlerReflector {
        @Direct fun dispatchMessage(message: Message)
    }
}

private fun Throwable.appCrash(): PlaytestError = PlaytestError(
    "app-crash", message ?: javaClass.name,
    buildJsonObject {
        put("exception_class", this@appCrash.javaClass.name)
        put("exception_message", message)
        put("exception_stack", JsonArray(stackTrace.take(12).map { JsonPrimitive(it.toString()) }))
    },
    cause = this,
)
