package cloud.trotter.log.strength.wear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The watch client's sync contract against a [FakePhoneLink]: when the queue drains,
 * what makes an inbound snapshot authoritative, and who owns the coroutine an edit
 * rides on (#173, #174).
 *
 * Real dispatchers and a real (temp-file) DataStore rather than virtual time: the
 * properties under test are about work outliving its caller and about two drains
 * colliding, and both stop meaning anything the moment a single test thread is doing
 * everything in turn. [eventually] is the price of that.
 */
class DataLayerWatchClientTest {

    /** Stands in for StrengthLogWearApp.appScope — outlives every "Activity" below. */
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        storeScope.cancel()
    }

    private fun store() = PendingEditStore(
        PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("watch-client", ".preferences_pb").also { it.delete() }
        },
    )

    @Test
    fun `a phone becoming reachable drains both queues`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        queue.enqueue(tickDelta(stamp = 7L))
        queue.enqueueSwap(swapRequest(stamp = 8L))
        DataLayerWatchClient(link, queue, appScope)
        // The start-up prime drains too; let it settle so the reconnect drain is the
        // one being measured.
        eventually("the start-up drain") { link.sentDeltas.size == 1 && link.sentSwaps.size == 1 }
        eventually("the reachability listener") { link.reachability.subscriptionCount.value > 0 }

        link.reachability.emit(false)
        link.reachability.emit(true)

        eventually("the reconnect drain") { link.sentDeltas.size == 2 && link.sentSwaps.size == 2 }
        // Reachability re-asserted without having changed is not a reconnect.
        link.reachability.emit(true)
        delay(SETTLE_MILLIS)
        assertEquals(2, link.sentDeltas.size)
    }

    @Test
    fun `two drain signals at once never overlap on the wire`() = runBlocking {
        val link = FakePhoneLink()
        link.sendDurationMillis = 60L
        val queue = store()
        queue.enqueue(tickDelta(stamp = 7L))
        DataLayerWatchClient(link, queue, appScope)
        eventually("both listeners") {
            link.reachability.subscriptionCount.value > 0 && link.snapshotEvents.subscriptionCount.value > 0
        }

        // A reconnect and an inbound snapshot land together — two independent
        // pipelines, both of which want to flush the same queue.
        link.reachability.emit(true)
        link.snapshotEvents.emit(phoneSnapshot(revision = 9))

        eventually("all three drains") { link.sentDeltas.size == 3 }
        delay(SETTLE_MILLIS)
        assertEquals(1, link.peakConcurrentSends, "drains must serialize")
    }

    @Test
    fun `a snapshot older than the one held is refused, and still flushes the queue`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        queue.enqueue(tickDelta(setIndex = 1, stamp = 7L))
        val client = DataLayerWatchClient(link, queue, appScope)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }

        // One send from the start-up drain, one more from revision 9's.
        link.snapshotEvents.emit(phoneSnapshot(revision = 9))
        eventually("the phone's snapshot") { link.sentDeltas.size == 2 }
        assertEquals(9L, client.snapshotFlow().first().revision)

        link.snapshotEvents.emit(phoneSnapshot(revision = 4))

        eventually("the stale item's drain") { link.sentDeltas.size == 3 }
        assertEquals(9L, client.snapshotFlow().first().revision, "revision 4 must not install")
    }

    @Test
    fun `a redelivered snapshot does not un-tick the set the lifter just ticked`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        val client = DataLayerWatchClient(link, queue, appScope)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }
        val published = phoneSnapshot(revision = 9)
        link.snapshotEvents.emit(published)
        eventually("the phone's snapshot") { client.snapshotFlow().first().revision == 9L }

        client.sendEdit(tickDelta(setIndex = 0, done = true, stamp = 7L))
        eventually("the optimistic echo") { client.snapshotFlow().first().day.exercises[0].sets[0].done }
        assertEquals(9L, client.snapshotFlow().first().revision, "the echo must not spend a revision")
        eventually("the edit on the wire") { link.sentDeltas.size == 1 }

        // What a reconnect resync looks like: the same item, all over again.
        link.snapshotEvents.emit(published)

        eventually("the redelivery's drain") { link.sentDeltas.size == 2 }
        assertTrue(client.snapshotFlow().first().day.exercises[0].sets[0].done, "the echo survives")
    }

    @Test
    fun `an edit reaches the durable queue after the caller's scope is cancelled`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        val client = DataLayerWatchClient(link, queue, appScope)
        // The dial's composition scope: cancelled on Activity destroy, which the app's
        // own day-done dismiss triggers a haptic's worth of time after the tick (#174).
        val composition = CoroutineScope(Dispatchers.Unconfined + Job())

        composition.launch { client.sendEdit(tickDelta(setIndex = 0, done = true, stamp = 7L)) }
        composition.cancel()

        eventually("the durable enqueue") { queue.all().size == 1 }
        assertEquals(true, queue.all().single().done)
        eventually("the send") { link.sentDeltas.size == 1 }
    }

    /** Real time, because the work being waited on is on real threads. */
    private suspend fun eventually(what: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(TIMEOUT_MILLIS) {
                while (!condition()) delay(POLL_MILLIS)
            }
        } catch (e: TimeoutCancellationException) {
            fail("timed out waiting for $what")
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L

        /** Long enough for work that must NOT happen to have happened. */
        const val SETTLE_MILLIS = 150L
    }
}
