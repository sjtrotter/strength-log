package cloud.trotter.log.strength.wear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.Collections
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
 * which inbound snapshot is authoritative, and who owns the coroutine an edit rides on
 * (#173, #174).
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
    private val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

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

    private fun watchClient(link: FakePhoneLink, queue: PendingEditStore = store()) =
        DataLayerWatchClient(
            link = link,
            queue = queue,
            scope = appScope,
            // The module doesn't return default values for unmocked Android calls, so
            // android.util.Log would throw here. Recorded instead: a warning in a
            // happy-path test means something failed quietly.
            onWarning = { message, cause -> warnings += "$message: $cause" },
        )

    @Test
    fun `a phone reported reachable drains both queues, every time it is reported`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        queue.enqueue(tickDelta(stamp = 7L))
        queue.enqueueSwap(swapRequest(stamp = 8L))
        watchClient(link, queue)
        // The start-up prime drains too; let it settle so the reconnect drain is the
        // one being measured.
        eventually("the start-up drain") { link.sentDeltas.size == 1 && link.sentSwaps.size == 1 }
        eventually("the reachability listener") { link.reachability.subscriptionCount.value > 0 }

        link.reachability.emit(false)
        link.reachability.emit(true)
        eventually("the reconnect drain") { link.sentDeltas.size == 2 && link.sentSwaps.size == 2 }

        // A second `true` with no `false` between drains again, deliberately: the
        // initial capability query and the change callbacks are independent sources,
        // and deduplicating would let a late query result latch `true` over a real
        // disconnect and swallow the reconnect that follows it.
        link.reachability.emit(true)

        eventually("the repeated report's drain") { link.sentDeltas.size == 3 }
        assertTrue(warnings.isEmpty(), "unexpected warnings: $warnings")
    }

    @Test
    fun `two drain signals at once never overlap on the wire`() = runBlocking {
        val link = FakePhoneLink()
        link.sendDurationMillis = 60L
        val queue = store()
        queue.enqueue(tickDelta(stamp = 7L))
        watchClient(link, queue)
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
    fun `a lower revision of the epoch we hold is refused, and still flushes the queue`() = runBlocking {
        val link = FakePhoneLink()
        // Primed from the cache, which is where a fresh process gets its baseline.
        link.cached = phoneSnapshot(revision = 9, epoch = PHONE_EPOCH)
        val queue = store()
        queue.enqueue(tickDelta(setIndex = 1, stamp = 7L))
        val client = watchClient(link, queue)
        eventually("the primed snapshot") { link.sentDeltas.size == 1 }
        assertEquals(9L, client.snapshotFlow().first().revision)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }

        link.snapshotEvents.emit(phoneSnapshot(revision = 4, epoch = PHONE_EPOCH))

        eventually("the stale item's drain") { link.sentDeltas.size == 2 }
        assertEquals(9L, client.snapshotFlow().first().revision, "revision 4 must not install")
    }

    @Test
    fun `a redelivered snapshot does not un-tick the set the lifter just ticked`() = runBlocking {
        val link = FakePhoneLink()
        val client = watchClient(link)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }
        val published = phoneSnapshot(revision = 9, epoch = PHONE_EPOCH)
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
    fun `a newer pre-edit snapshot keeps an unacked tick overlaid`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        queue.enqueue(tickDelta(setIndex = 0, done = true, stamp = 7L))
        val client = watchClient(link, queue)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }

        link.snapshotEvents.emit(phoneSnapshot(revision = 9, epoch = PHONE_EPOCH))

        eventually("the overlaid snapshot") {
            client.snapshotFlow().first().day.exercises.single().sets[0].done
        }
        assertEquals(1, queue.all().size, "display overlay must not acknowledge the edit")
    }

    @Test
    fun `the confirming snapshot removes the overlay and converges with zero pending`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        queue.enqueue(tickDelta(setIndex = 0, done = true, stamp = 7L))
        val client = watchClient(link, queue)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }
        link.snapshotEvents.emit(phoneSnapshot(revision = 9, epoch = PHONE_EPOCH))
        eventually("the pending overlay") { client.snapshotFlow().first().day.exercises[0].sets[0].done }

        val confirmed = phoneSnapshot(revision = 10, epoch = PHONE_EPOCH).let { snapshot ->
            snapshot.copy(day = snapshot.day.copy(exercises = snapshot.day.exercises.map { exercise ->
                exercise.copy(sets = exercise.sets.mapIndexed { index, set ->
                    if (index == 0) set.copy(done = true) else set
                })
            }))
        }
        link.snapshotEvents.emit(confirmed)

        eventually("the acknowledgement") {
            queue.all().isEmpty() && client.snapshotFlow().first().revision == 10L
        }
        assertEquals(confirmed, client.snapshotFlow().first())
        assertEquals(0, client.pendingCountFlow().first())
    }

    @Test
    fun `a pending delta for a missing exercise is skipped without changing the snapshot`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        queue.enqueue(tickDelta(done = true, stamp = 7L))
        val client = watchClient(link, queue)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }
        val withoutTarget = phoneSnapshot(revision = 9, epoch = PHONE_EPOCH).let { snapshot ->
            snapshot.copy(day = snapshot.day.copy(exercises = emptyList()))
        }

        link.snapshotEvents.emit(withoutTarget)

        eventually("the unchanged snapshot") { client.snapshotFlow().first().revision == 9L }
        assertEquals(withoutTarget, client.snapshotFlow().first())
        assertEquals(1, queue.all().size, "a missing target remains visibly queued")
    }

    @Test
    fun `all remaining deltas for one exercise are overlaid`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        queue.enqueue(tickDelta(done = true, stamp = 7L))
        queue.enqueue(tickDelta(done = null, stamp = 8L).copy(reps = 9))
        val client = watchClient(link, queue)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }

        link.snapshotEvents.emit(phoneSnapshot(revision = 9, epoch = PHONE_EPOCH))

        eventually("the ordered overlay") { client.snapshotFlow().first().revision == 9L }
        val row = client.snapshotFlow().first().day.exercises[0].sets[0]
        assertEquals(true, row.done)
        assertEquals(9, row.reps)
        assertEquals(listOf(7L, 8L), queue.all().map { it.editedAtMillis })
    }

    @Test
    fun `cached snapshot prime receives the pending overlay`() = runBlocking {
        val link = FakePhoneLink().apply { cached = phoneSnapshot(revision = 9, epoch = PHONE_EPOCH) }
        val queue = store()
        queue.enqueue(tickDelta(done = true, stamp = 7L))

        val client = watchClient(link, queue)

        eventually("the overlaid cache prime") {
            client.snapshotFlow().first().day.exercises[0].sets[0].done
        }
        assertEquals(1, queue.all().size)
    }

    @Test
    fun `a count that restarted under a new epoch is adopted, not refused as stale`() = runBlocking {
        val link = FakePhoneLink()
        val client = watchClient(link)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }
        link.snapshotEvents.emit(phoneSnapshot(revision = 500, epoch = PHONE_EPOCH))
        eventually("the phone's snapshot") { client.snapshotFlow().first().revision == 500L }

        // The lifter cleared the phone app's data: same phone, new generation, and its
        // revisions start over. Refusing this is the wedge #173's guard could have
        // created — no restart on either side recovers from it.
        link.snapshotEvents.emit(phoneSnapshot(revision = 1, epoch = PHONE_EPOCH + 1))

        eventually("the new generation") { client.snapshotFlow().first().epoch == PHONE_EPOCH + 1 }
        assertEquals(1L, client.snapshotFlow().first().revision)
    }

    @Test
    fun `a stale cached item does not outrank a live snapshot of a newer epoch`() = runBlocking {
        val link = FakePhoneLink()
        // What an obsolete node leaves behind: a high revision of a dead generation.
        link.cached = phoneSnapshot(revision = 500, epoch = PHONE_EPOCH)
        val client = watchClient(link)
        eventually("the primed baseline") { client.snapshotFlow().first().revision == 500L }
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }

        link.snapshotEvents.emit(phoneSnapshot(revision = 1, epoch = PHONE_EPOCH + 1))

        eventually("the live generation") { client.snapshotFlow().first().epoch == PHONE_EPOCH + 1 }
    }

    @Test
    fun `a snapshot from a publisher too old to send an epoch still installs`() = runBlocking {
        val link = FakePhoneLink()
        val client = watchClient(link)
        eventually("the snapshot listener") { link.snapshotEvents.subscriptionCount.value > 0 }

        // Legacy decodes as epoch 0 — the oldest generation, and its own.
        link.snapshotEvents.emit(phoneSnapshot(revision = 5))
        eventually("the legacy snapshot") { client.snapshotFlow().first().revision == 5L }
        assertEquals(0L, client.snapshotFlow().first().epoch)

        // The publisher is upgraded: revision 1 of a real epoch supersedes it.
        link.snapshotEvents.emit(phoneSnapshot(revision = 1, epoch = PHONE_EPOCH))
        eventually("the epoched snapshot") { client.snapshotFlow().first().epoch == PHONE_EPOCH }
        assertEquals(1L, client.snapshotFlow().first().revision)
    }

    @Test
    fun `an edit reaches the durable queue after the caller's scope is cancelled`() = runBlocking {
        val link = FakePhoneLink()
        val queue = store()
        val client = watchClient(link, queue)
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

        /** A phone generation, as [cloud.trotter.log.strength.domain.sync.WatchSnapshot.epoch]
         *  carries it: the wall-clock millis its sync state was created. */
        const val PHONE_EPOCH = 1_700_000_000_000L
    }
}
