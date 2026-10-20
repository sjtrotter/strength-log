package cloud.trotter.log.strength.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.domain.sync.EditAck
import cloud.trotter.log.strength.domain.sync.SwapAck
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The snapshot revision must be monotonic *across process restarts* — a regressed
 * revision would make a live watch treat a genuinely newer snapshot as stale and
 * ignore it. The counter is persisted, so a fresh [WearSyncStore] over the same
 * file resumes counting up.
 *
 * The epoch is the other half of that contract, pinned here in the two ways that
 * matter: it survives a restart (same file), and it is *new* when the file isn't —
 * app data cleared, which is exactly when the counter starts over. Also pins the
 * dedupe markers' persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearSyncStoreTest {

    private lateinit var file: File
    private val openScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        file = File.createTempFile("wear-sync", ".preferences_pb").also { it.delete() }
    }

    @After
    fun tearDown() = runBlocking { openScopes.forEach { it.release() } }

    /**
     * Ends this store's scope the way a process death would, and waits for it:
     * DataStore removes the file from its process-wide active set in the scope's
     * completion handler, so a bare `cancel()` only *starts* the release and the
     * next [newStore] on the same file can still lose the race with it.
     */
    private suspend fun CoroutineScope.release() = coroutineContext.job.cancelAndJoin()

    /**
     * A store bound to its own scope. [Releasing][release] that scope releases
     * DataStore's hold on the file, which is exactly how a "process restart" is
     * simulated: the next [newStore] opens the same file fresh.
     */
    private fun newStore(nowMillis: () -> Long = { 1_000L }): Pair<WearSyncStore, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.IO + Job()).also { openScopes += it }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return WearSyncStore(dataStore, nowMillis, entropy = { 0L }) to scope
    }

    @Test
    fun `processed acks persist their per-row maximum without advancing applied markers`() = runTest {
        val (store, scope) = newStore()
        assertTrue(store.editAcksFlow.first().isEmpty())
        assertTrue(store.swapAcksFlow.first().isEmpty())
        store.markApplied("A|1|main|0", 5L)
        store.acknowledgeEdit("A|1|main|0", 20L)
        store.acknowledgeEdit("A|1|main|0", 10L)
        store.acknowledgeEdit("A|1|ss|0", 11L)
        store.acknowledgeEdit("A|1|main|1", 12L)
        store.acknowledgeSwap(1L, 30L)
        store.acknowledgeSwap(1L, 15L)
        scope.release()

        val resumed = newStore().first
        assertEquals(listOf(EditAck(1L, "main", 0, 20L), EditAck(1L, "main", 1, 12L), EditAck(1L, "ss", 0, 11L)),
            resumed.editAcksFlow.first())
        assertEquals(listOf(SwapAck(1L, 30L)), resumed.swapAcksFlow.first())
        assertEquals(5L, resumed.lastApplied("A|1|main|0"))
        assertEquals(0L, resumed.lastApplied("A|1|ss|0"))
        assertEquals(0L, resumed.lastApplied("A|1|swap"))
    }

    @Test
    fun `edit and swap acks each retain only the 64 newest row stamps`() = runTest {
        val store = newStore().first
        store.acknowledgeEdit("A|1|main|0", 1_000L)
        store.acknowledgeSwap(1L, 1_000L)
        for (id in 2L..66L) {
            store.markApplied("A|$id|main|0", id)
            store.acknowledgeEdit("A|$id|main|0", id)
            store.acknowledgeSwap(id, id)
        }
        // A late replay must not lower an ack or displace a newer row's ack.
        store.acknowledgeEdit("A|1|main|0", 1L)
        store.acknowledgeSwap(1L, 1L)
        store.acknowledgeEdit("A|2|main|0", 2L)
        store.acknowledgeSwap(2L, 2L)

        val edits = store.editAcksFlow.first()
        val swaps = store.swapAcksFlow.first()
        assertEquals(64, edits.size)
        assertEquals(64, swaps.size)
        val expectedIds = (4L..66L).toSet() + 1L
        assertEquals(expectedIds, edits.map { it.programExerciseId }.toSet())
        assertEquals(expectedIds, swaps.map { it.programExerciseId }.toSet())
        assertEquals(EditAck(1L, "main", 0, 1_000L), edits.first())
        assertEquals(SwapAck(1L, 1_000L), swaps.first())
        assertEquals(2L, store.lastApplied("A|2|main|0"))
    }

    @Test
    fun `revision increments monotonically`() = runTest {
        val store = newStore().first
        assertEquals(1L, store.nextStamp().revision)
        assertEquals(2L, store.nextStamp().revision)
        assertEquals(3L, store.nextStamp().revision)
    }

    @Test
    fun `revision does not regress across a restart, and the epoch holds`() = runTest {
        // One store hands out 1, 2 then the process "dies" (its scope is cancelled).
        val (store1, scope1) = newStore()
        store1.nextStamp()
        val last = store1.nextStamp()
        assertEquals(2L, last.revision)
        scope1.release()

        // A fresh store over the same file continues above the last value, under the
        // same epoch: a restart is not a new generation, however the clock reads.
        val resumed = newStore(nowMillis = { 9_999L }).first.nextStamp()
        assertTrue(
            "revision regressed across restart: ${resumed.revision} <= ${last.revision}",
            resumed.revision > last.revision,
        )
        assertEquals(3L, resumed.revision)
        assertEquals(last.epoch, resumed.epoch)
    }

    @Test
    fun `clearing the app's data mints a new epoch for the restarted count`() = runTest {
        val (store1, scope1) = newStore(nowMillis = { 1_000L })
        store1.nextStamp()
        val before = store1.nextStamp()
        assertEquals(2L, before.revision)
        assertEquals(1L shl 10, before.epoch)
        scope1.release()

        // App data cleared: the file goes, so the counter restarts at 1 — which is
        // exactly when the watch has to be told these numbers are a new series.
        file.delete()
        val after = newStore(nowMillis = { 2_000L }).first.nextStamp()
        assertEquals(1L, after.revision)
        assertTrue("epoch must change when the count restarts", after.epoch != before.epoch)
    }

    @Test
    fun `applied markers persist and default to zero`() = runTest {
        val store = newStore().first
        assertEquals(0L, store.lastApplied("A|1|main"))
        store.markApplied("A|1|main", 500L)
        assertEquals(500L, store.lastApplied("A|1|main"))
        // A different slot is independent.
        assertEquals(0L, store.lastApplied("A|1|ss"))
    }
}
