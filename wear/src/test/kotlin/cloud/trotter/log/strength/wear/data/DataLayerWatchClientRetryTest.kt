package cloud.trotter.log.strength.wear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Listener registration, when Play services says no (#173).
 *
 * The old client awaited `addListener` inside a `callbackFlow` collected on the
 * application scope, which has no `CoroutineExceptionHandler` — a transient refusal
 * didn't leave the watch listener-less, it killed the app mid-workout.
 *
 * Two things about the harness are deliberate:
 *  - the client runs on its own scope over the test scheduler, **not**
 *    `backgroundScope`. `advanceUntilIdle`/`advanceTimeBy` treat background work as
 *    something not to wait for, so a retry parked in a background `delay` never
 *    advances and the schedule can't be observed at all.
 *  - that scope is `SupervisorJob` + a recording handler, which is exactly the shape
 *    of `StrengthLogWearApp.appScope`. [escaped] staying empty *is* the "no crash"
 *    assertion: in production that handler is the platform's, and it kills the app.
 *
 * Time moves in explicit `advanceTimeBy` + `runCurrent` steps: `advanceTimeBy` stops
 * *before* a task scheduled at exactly the target instant, which is where every retry
 * lands. Nothing here calls `advanceUntilIdle` — the retry is unbounded by design, so
 * "advance until there is nothing left" would never return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataLayerWatchClientRetryTest {

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val clientScopes = mutableListOf<CoroutineScope>()
    private val escaped = mutableListOf<Throwable>()

    @AfterTest
    fun tearDown() {
        clientScopes.forEach { it.cancel() }
        storeScope.cancel()
    }

    private fun store() = PendingEditStore(
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("watch-client", ".preferences_pb").also { it.delete() }
        },
    )

    private fun TestScope.watchClient(link: FakePhoneLink): DataLayerWatchClient {
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) +
                SupervisorJob() +
                CoroutineExceptionHandler { _, cause -> escaped += cause },
        ).also { clientScopes += it }
        return DataLayerWatchClient(
            link = link,
            queue = store(),
            scope = scope,
            onWarning = { _, _ -> },
            // Mid-jitter: the schedule below is the un-scattered one, exactly.
            retryJitter = { 0.5 },
        )
    }

    /** Runs [body], then tears the client down *inside* the test so no unbounded retry
     *  is left scheduled when `runTest` drains what remains. */
    private fun retryTest(body: suspend TestScope.(FakePhoneLink) -> Unit) = runTest {
        val link = FakePhoneLink { testScheduler.currentTime }
        try {
            body(link)
        } finally {
            clientScopes.forEach { it.cancel() }
            runCurrent()
        }
    }

    /** Moves the clock on by [millis] **and runs what is scheduled at that instant** —
     *  `advanceTimeBy` alone stops just short of it, which is exactly where every
     *  retry lands. */
    private fun TestScope.advanceAndRun(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `a refused snapshot registration is retried on a doubling schedule`() = retryTest { link ->
        link.snapshotRegistrationsToRefuse = Int.MAX_VALUE
        watchClient(link)

        runCurrent()
        assertEquals(listOf(0L), link.snapshotRegistrations)

        advanceAndRun(1_000)
        advanceAndRun(2_000)
        advanceAndRun(4_000)
        advanceAndRun(8_000)

        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L), link.snapshotRegistrations)
        assertTrue(escaped.isEmpty(), "a registration failure reached the application scope: $escaped")
    }

    @Test
    fun `the retry settles at the cap and never gives up`() = retryTest { link ->
        link.snapshotRegistrationsToRefuse = Int.MAX_VALUE
        watchClient(link)

        // An hour of a phone that never answers. The client is a process singleton, so
        // "give up for this session" would mean no sync for the rest of the process.
        runCurrent()
        advanceAndRun(60 * 60_000L)

        val attempts = link.snapshotRegistrations.toList()
        assertTrue(attempts.size > 10, "still retrying after an hour, got ${attempts.size} attempts")
        val tailGaps = attempts.takeLast(4).zipWithNext { earlier, later -> later - earlier }
        assertEquals(
            listOf(
                DataLayerWatchClient.RETRY_CAP_MILLIS,
                DataLayerWatchClient.RETRY_CAP_MILLIS,
                DataLayerWatchClient.RETRY_CAP_MILLIS,
            ),
            tailGaps,
            "the backoff must settle at the cap, not keep doubling",
        )
        assertTrue(escaped.isEmpty(), "a registration failure reached the application scope: $escaped")
    }

    @Test
    fun `a refused capability registration is retried too, and stops once it takes`() = retryTest { link ->
        link.reachabilityRegistrationsToRefuse = 2
        watchClient(link)

        runCurrent()
        advanceAndRun(1_000)
        advanceAndRun(2_000)
        assertEquals(listOf(0L, 1_000L, 3_000L), link.reachabilityRegistrations)

        // The third took: no fourth attempt, however long nothing happens.
        advanceAndRun(60 * 60_000L)
        assertEquals(3, link.reachabilityRegistrations.size)
        assertTrue(escaped.isEmpty(), "a registration failure reached the application scope: $escaped")
    }
}
