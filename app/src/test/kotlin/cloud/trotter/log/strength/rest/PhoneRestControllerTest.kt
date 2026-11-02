package cloud.trotter.log.strength.rest

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.standards.PhoneRest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PhoneRestControllerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var runtime: RecordingRestRuntime
    private lateinit var controller: PhoneRestController

    @Before fun setUp() {
        scope = CoroutineScope(dispatcher + Job())
        val file = temporaryFolder.newFile("rest.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        settings = SettingsStore(store)
        runtime = RecordingRestRuntime()
        controller = PhoneRestController(settings, runtime)
    }

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun adjustAddsFifteenSecondsAndRearmsThePersistedRest() = runTest(dispatcher) {
        val rest = PhoneRest(60_000, 60, "Squat 190 × 5")
        controller.locked { start(rest) }
        runtime.events.clear()

        controller.adjust(now = 10_000, seconds = 15)

        val adjusted = rest.copy(deadlineEpochMillis = 75_000, totalSeconds = 65)
        assertEquals(adjusted, controller.state.first())
        assertEquals(adjusted, runtime.armed.last())
        assertEquals(listOf("arm"), runtime.events)
    }

    @Test fun adjustThatReachesZeroClearsAndCancels() = runTest(dispatcher) {
        controller.locked { start(PhoneRest(15_000, 15, "Squat")) }
        runtime.events.clear()

        controller.adjust(now = 10_000, seconds = -15)

        assertNull(controller.state.first())
        assertEquals(listOf("cancel"), runtime.events)
    }

    @Test fun skipClearsAndCancels() = runTest(dispatcher) {
        controller.locked { start(PhoneRest(60_000, 60, "Squat")) }
        runtime.events.clear()

        controller.skip()

        assertNull(controller.state.first())
        assertEquals(listOf("cancel"), runtime.events)
    }

    @Test fun alarmDoesNothingWhenTheDeadlineMovedLater() = runTest(dispatcher) {
        controller.locked { start(PhoneRest(60_000, 60, "Squat")) }
        controller.adjust(now = 60_000, seconds = 15)
        val extended = controller.state.first()
        runtime.events.clear()

        controller.expireFromAlarm { 60_000 }

        assertEquals(extended, controller.state.first())
        assertTrue(runtime.events.isEmpty())
    }

    @Test fun backgroundAlarmArmsCompletesAndClearsWithoutCancelingTheCompletion() = runTest(dispatcher) {
        val rest = PhoneRest(60_000, 60, "Squat")
        settings.setPhoneRest(rest)

        controller.expireFromAlarm { 60_000 }

        assertEquals(listOf(rest), runtime.armed)
        assertEquals(listOf("arm", "complete"), runtime.events)
        assertNull(controller.state.first())
    }

    @Test fun foregroundAlarmCompletesAndKeepsThePersistedRest() = runTest(dispatcher) {
        val rest = PhoneRest(60_000, 60, "Squat")
        controller.locked { start(rest) }
        runtime.isForeground = true
        runtime.events.clear()

        controller.expireFromAlarm { 60_000 }

        assertEquals(listOf("complete"), runtime.events)
        assertEquals(rest, controller.state.first())
    }

    @Test fun foregroundExpiryCompletesOnceAndClearsAfterTheGracePeriodEvenWhenCanceled() = runTest(dispatcher) {
        val rest = PhoneRest(0, 60, "Squat")
        controller.locked { start(rest) }
        runtime.events.clear()
        val expiry = launch { controller.expireForeground(rest.deadlineEpochMillis) { testScheduler.currentTime } }
        runCurrent()

        assertEquals(listOf("complete"), runtime.events)
        assertEquals(rest, controller.state.first())
        expiry.cancel()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(rest, controller.state.first())
        assertFalse(expiry.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        expiry.join()

        assertNull(controller.state.first())
        controller.expireForeground(rest.deadlineEpochMillis) { testScheduler.currentTime }
        assertEquals(listOf("complete"), runtime.events)
    }

    @Test fun foregroundExpiryRequiresTheSamePersistedDeadlineToBeDue() = runTest(dispatcher) {
        val rest = PhoneRest(60_000, 60, "Squat")
        controller.locked { start(rest) }
        runtime.events.clear()

        controller.expireForeground(deadline = 45_000) { 60_000 }
        controller.expireForeground(deadline = 60_000) { 59_999 }

        assertEquals(rest, controller.state.first())
        assertTrue(runtime.events.isEmpty())
        controller.skip()
        runtime.events.clear()
        controller.expireForeground(deadline = 60_000) { 60_000 }
        controller.expireFromAlarm { 60_000 }
        assertTrue(runtime.events.isEmpty())
    }

    @Test fun foregroundGracePeriodDoesNotClearAReplacementRest() = runTest(dispatcher) {
        val expired = PhoneRest(0, 60, "Squat")
        controller.locked { start(expired) }
        runtime.events.clear()
        val expiry = launch { controller.expireForeground(expired.deadlineEpochMillis) { testScheduler.currentTime } }
        runCurrent()
        val replacement = PhoneRest(60_000, 60, "Bench")
        controller.locked { start(replacement) }

        advanceTimeBy(2_000)
        runCurrent()
        expiry.join()

        assertEquals(replacement, controller.state.first())
        assertEquals(listOf("complete", "arm"), runtime.events)
    }

    @Test fun alarmReadsTheClockAfterTakingTheLock() = runTest(dispatcher) {
        val rest = PhoneRest(60_000, 60, "Squat")
        controller.locked { start(rest) }
        runtime.events.clear()
        var clock = 59_000L
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            controller.locked {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val alarm = launch { controller.expireFromAlarm { clock } }
        runCurrent()
        clock = 60_000L
        release.complete(Unit)
        holder.join()
        alarm.join()

        assertEquals(listOf("arm", "complete"), runtime.events)
        assertNull(controller.state.first())
    }

    @Test fun lockedWritesSerializeControlsAndExpiry() = runTest(dispatcher) {
        val rest = PhoneRest(60_000, 60, "Squat")
        val mutations: List<suspend () -> Unit> = listOf(
            { controller.adjust(now = 0, seconds = 15) },
            { controller.skip() },
            { controller.expireFromAlarm { 60_000 } },
            { controller.expireForeground(deadline = 60_000) { 60_000 } },
        )
        for (mutate in mutations) {
            controller.locked { start(rest) }
            runtime.events.clear()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer = launch {
                controller.locked {
                    assertEquals(rest, current())
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val mutation = launch { mutate() }
            runCurrent()

            assertFalse(mutation.isCompleted)
            assertTrue(runtime.events.isEmpty())
            assertEquals(rest, controller.state.first())
            release.complete(Unit)
            writer.join()
            mutation.join()
            assertTrue(runtime.events.isNotEmpty())
        }
    }

    private class RecordingRestRuntime : RestRuntime {
        override val available = true
        override var isForeground = false
        val armed = mutableListOf<PhoneRest>()
        val events = mutableListOf<String>()

        override fun arm(rest: PhoneRest) {
            armed += rest
            events += "arm"
        }

        override fun cancel() {
            events += "cancel"
        }

        override fun complete(): Boolean {
            events += "complete"
            return true
        }
    }
}
