package cloud.trotter.log.strength.time

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Android half of the civil-time source (#176): the system broadcasts are
 * really wired up, and a reading taken after a zone change is taken in the *new*
 * zone rather than in one captured when the source was built — the exact failure
 * a `@Singleton Clock.systemDefaultZone()` has.
 *
 * Collection is unconfined, so the broadcast the test delivers drives the flow
 * on the test's own thread and every assertion below is about ordering, not
 * about waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemCivilTimeSourceTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val originalZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun aTimezoneChangeBroadcastProducesAReadingInTheNewZone() = runBlocking {
        val readings = Channel<CivilTime>(Channel.UNLIMITED)
        val source = SystemCivilTimeSource(app)
        val collecting = collectReadings(source, readings)

        val first = withTimeout(TIMEOUT_MS) { readings.receive() }
        assertEquals("America/New_York", first.zone.id)

        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        broadcast(Intent.ACTION_TIMEZONE_CHANGED)

        val second = withTimeout(TIMEOUT_MS) { readings.receive() }
        assertEquals("Pacific/Auckland", second.zone.id)
        // The reading agrees with itself: its date is the one its instant and
        // its zone imply, not one derived against some other zone.
        assertEquals(second.instant.atZone(second.zone).toLocalDate(), second.date)
        collecting.cancel()
    }

    /** A time change that leaves the device on the same civil day is not a new
     *  day, and rebuilding a screen for it would be waste. */
    @Test
    fun aBroadcastThatLeavesTheDayAloneProducesNoNewReading() = runBlocking {
        val readings = Channel<CivilTime>(Channel.UNLIMITED)
        val source = SystemCivilTimeSource(app)
        val collecting = collectReadings(source, readings)
        withTimeout(TIMEOUT_MS) { readings.receive() }

        broadcast(Intent.ACTION_TIME_CHANGED)
        broadcast(Intent.ACTION_DATE_CHANGED)

        assertTrue(readings.tryReceive().isFailure)
        collecting.cancel()
    }

    /** The receiver lives exactly as long as the collection does: a screen that
     *  isn't being watched leaves no receiver and no timer behind. */
    @Test
    fun theReceiverIsUnregisteredWhenCollectionStops() = runBlocking {
        val readings = Channel<CivilTime>(Channel.UNLIMITED)
        val collecting = collectReadings(SystemCivilTimeSource(app), readings)
        withTimeout(TIMEOUT_MS) { readings.receive() }
        assertTrue(hasTimezoneReceiver())

        collecting.cancel()
        collecting.join()

        assertFalse(hasTimezoneReceiver())
    }

    private fun hasTimezoneReceiver(): Boolean =
        shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED) }

    private fun CoroutineScope.collectReadings(source: SystemCivilTimeSource, into: Channel<CivilTime>): Job =
        launch(Dispatchers.Unconfined) { source.civilTime.collect { into.send(it) } }

    /** Robolectric queues broadcasts on the main looper; idling delivers them. */
    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
