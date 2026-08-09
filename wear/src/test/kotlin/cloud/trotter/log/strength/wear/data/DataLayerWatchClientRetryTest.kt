package cloud.trotter.log.strength.wear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Listener registration, when Play services says no (#173).
 *
 * The old client awaited `addListener` inside a `callbackFlow` collected on the
 * application scope, which has no `CoroutineExceptionHandler` — a transient refusal
 * didn't leave the watch listener-less, it killed the app mid-workout. These run on
 * virtual time, so the whole capped backoff costs nothing, and they assert the
 * schedule rather than just the count: a retry that fired immediately would hammer a
 * Play-services process that is already unhappy.
 *
 * The load-bearing assertion is the one that isn't written down — anything escaping
 * the client's scope fails these tests outright.
 */
class DataLayerWatchClientRetryTest {

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @AfterTest
    fun tearDown() = storeScope.cancel()

    private fun store() = PendingEditStore(
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("watch-client", ".preferences_pb").also { it.delete() }
        },
    )

    @Test
    fun `a refused registration is retried with exponential backoff`() = runTest {
        val link = FakePhoneLink { testScheduler.currentTime }
        link.registrationsToRefuse = 2
        val client = DataLayerWatchClient(link, store(), backgroundScope)

        advanceUntilIdle()
        assertEquals(listOf(0L, 1_000L, 3_000L), link.registrationAttempts)

        // And the listener that finally registered is a working one.
        link.snapshotEvents.emit(phoneSnapshot(revision = 4))
        advanceUntilIdle()
        assertEquals(4L, client.snapshotFlow().first().revision)
    }

    @Test
    fun `registration gives up quietly once the attempts are spent`() = runTest {
        val link = FakePhoneLink { testScheduler.currentTime }
        link.registrationsToRefuse = Int.MAX_VALUE
        DataLayerWatchClient(link, store(), backgroundScope)

        advanceUntilIdle()
        assertEquals(
            DataLayerWatchClient.REGISTRATION_ATTEMPTS.toInt(),
            link.registrationAttempts.size,
            "capped at REGISTRATION_ATTEMPTS tries",
        )
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L), link.registrationAttempts)

        // Given up for the session, not retrying forever in the background: the next
        // launch of the app is what tries again.
        advanceTimeBy(10 * 60_000L)
        advanceUntilIdle()
        assertEquals(DataLayerWatchClient.REGISTRATION_ATTEMPTS.toInt(), link.registrationAttempts.size)
    }
}
