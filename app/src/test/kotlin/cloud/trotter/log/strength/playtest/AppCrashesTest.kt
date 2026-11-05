package cloud.trotter.log.strength.playtest

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PlaytestHandler::class])
class AppCrashesTest {
    @Test fun coroutineFailureFailsTheStepBeforeRunTestReturns() {
        val crash = IllegalStateException("App coroutine failed")
        var reported: PlaytestError? = null
        val deferred = assertFailsWith<IllegalStateException> {
            runTest {
                AppCrashes().use { crashes ->
                    reported = assertFailsWith<PlaytestError> {
                        crashes.step {
                            CoroutineScope(Dispatchers.Unconfined).launch { throw crash }
                            crashes.isIdleNow
                        }
                    }
                }
            }
        }
        assertSame(crash, deferred)
        val error = requireNotNull(reported)
        assertEquals("app-crash", error.kind)
        assertSame(crash, error.cause)
        val json = failureJson(error, 7)
        assertEquals(JsonPrimitive(7), json["i"])
        assertEquals(JsonPrimitive(crash.javaClass.name), json["exception_class"])
        assertEquals(JsonPrimitive(crash.message), json["exception_message"])
        assertTrue((json["exception_stack"] as JsonArray).size in 1..12)
        assertTrue((json["stack"] as JsonPrimitive).content.contains("App coroutine failed"))
    }

    @Test fun mainLooperFailureFailsTheStep() {
        val crash = IllegalArgumentException("Main looper failed")
        AppCrashes().use { crashes ->
            val error = assertFailsWith<PlaytestError> {
                crashes.step {
                    Handler(Looper.getMainLooper()).post { throw crash }
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
            assertEquals("app-crash", error.kind)
            assertSame(crash, error.cause)
        }
    }

    @Test fun uncaughtThreadFailureIsReportedAndHandlerIsRestored() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val crash = IllegalStateException("Background thread failed")
        AppCrashes().use { crashes ->
            val error = assertFailsWith<PlaytestError> {
                crashes.step {
                    Thread { throw crash }.apply { start(); join() }
                }
            }
            assertEquals("app-crash", error.kind)
            assertSame(crash, error.cause)
        }
        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun harnessErrorsKeepTheirClasses() {
        AppCrashes().use { crashes ->
            listOf("not-found", "timeout", "script").forEach { kind ->
                val original = PlaytestError(kind, "Harness failure")
                assertSame(original, assertFailsWith<PlaytestError> { crashes.step { throw original } })
            }
        }
    }

    @Test fun mainLooperHarnessErrorsKeepTheirClasses() {
        AppCrashes().use { crashes ->
            val original = PlaytestError("timeout", "Harness deadline")
            val error = assertFailsWith<PlaytestError> {
                crashes.step {
                    Handler(Looper.getMainLooper()).post { throw original }
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
            assertSame(original, error)
        }
    }
}
