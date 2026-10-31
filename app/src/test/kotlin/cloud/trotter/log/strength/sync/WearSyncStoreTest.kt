package cloud.trotter.log.strength.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
    private lateinit var preferences: DataStore<Preferences>
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
        preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
        return WearSyncStore(preferences, nowMillis, entropy = { 0L }) to scope
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

    @Test
    fun `edit ack log retains the newest 32 distinct stamps in processing order`() = runTest {
        val store = newStore().first
        assertEquals(emptyList<Long>(), store.processedEditsFlow.first())
        for (stamp in 1L..40L) store.recordProcessedEdit(stamp)
        assertEquals((9L..40L).toList(), store.processedEditsFlow.first())
        store.recordProcessedEdit(20L)
        assertEquals((9L..40L).filterNot { it == 20L } + 20L, store.processedEditsFlow.first())
        store.recordProcessedEdit(5L)
        assertEquals((10L..40L).filterNot { it == 20L } + listOf(20L, 5L), store.processedEditsFlow.first())
    }

    @Test
    fun `processed edit stamps survive a restart`() = runTest {
        val (store, scope) = newStore()
        store.recordProcessedEdit(101L)
        store.recordProcessedEdit(100L)
        scope.release()
        assertEquals(listOf(101L, 100L), newStore().first.processedEditsFlow.first())
    }

    @Test
    fun `unreadable edit ack preferences acknowledge nothing and the next record repairs them`() = runTest {
        val store = newStore().first
        val key = stringPreferencesKey("processed_edit_stamps")
        for (garbage in listOf("garbage", "1,broken,2", "1,", "9223372036854775808", "")) {
            preferences.edit { it[key] = garbage }
            assertEquals(emptyList<Long>(), store.processedEditsFlow.first())
            store.recordProcessedEdit(5L)
            assertEquals(listOf(5L), store.processedEditsFlow.first())
        }
    }

    @Test
    fun `cardio ack log defaults empty and retains the newest 32 processed stamps in order`() = runTest {
        val store = newStore().first
        assertEquals(emptyList<Long>(), store.acksFlow.first().recent)
        for (stamp in 1L..40L) store.record(stamp)
        assertEquals((9L..40L).toList(), store.acksFlow.first().recent)
        assertEquals(40L, store.lastApplied(CardioDeltaApplier.MARKER_KEY))
    }

    @Test
    fun `re-recording a cardio stamp moves it to the end without duplicates`() = runTest {
        val store = newStore().first
        for (stamp in 1L..40L) store.record(stamp)
        store.record(20L)
        assertEquals((9L..40L).filterNot { it == 20L } + 20L, store.acksFlow.first().recent)
        assertEquals(40L, store.lastApplied(CardioDeltaApplier.MARKER_KEY))
    }

    @Test
    fun `late older cardio stamp is newest in the log without regressing the max`() = runTest {
        val store = newStore().first
        for (stamp in 1L..40L) store.record(stamp)
        store.record(5L)
        assertEquals((10L..40L).toList() + 5L, store.acksFlow.first().recent)
        assertEquals(40L, store.lastAppliedFlow(CardioDeltaApplier.MARKER_KEY).first())
        assertEquals(40L, store.acksFlow.first().highest)
    }

    @Test
    fun `unparsable cardio ack preference reads as the max alone and the next record repairs it`() = runTest {
        val store = newStore().first
        val key = stringPreferencesKey("cardio_acked_stamps")
        for (garbage in listOf("garbage", "1,broken,2", "1,", "9223372036854775808", "")) {
            preferences.edit { it[key] = garbage }
            val highest = store.lastApplied(CardioDeltaApplier.MARKER_KEY)
            assertEquals(listOf(highest).filter { it > 0L }, store.acksFlow.first().recent)
            store.record(5L)
            assertEquals(listOf(5L), store.acksFlow.first().recent)
        }
    }

    @Test
    fun `cardio acknowledgement list and max survive a restart`() = runTest {
        val (store, scope) = newStore()
        store.record(101L)
        store.record(100L)
        scope.release()
        val resumed = newStore().first
        assertEquals(listOf(101L, 100L), resumed.acksFlow.first().recent)
        assertEquals(101L, resumed.lastApplied(CardioDeltaApplier.MARKER_KEY))
    }
}
