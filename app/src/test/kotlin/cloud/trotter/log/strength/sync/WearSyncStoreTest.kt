package cloud.trotter.log.strength.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
 * file resumes counting up. Also pins the dedupe markers' persistence.
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
    private fun newStore(): Pair<WearSyncStore, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.IO + Job()).also { openScopes += it }
        return WearSyncStore(PreferenceDataStoreFactory.create(scope = scope) { file }) to scope
    }

    @Test
    fun `revision increments monotonically`() = runTest {
        val store = newStore().first
        assertEquals(1L, store.nextRevision())
        assertEquals(2L, store.nextRevision())
        assertEquals(3L, store.nextRevision())
    }

    @Test
    fun `revision does not regress across a restart`() = runTest {
        // One store hands out 1, 2 then the process "dies" (its scope is cancelled).
        val (store1, scope1) = newStore()
        store1.nextRevision()
        val last = store1.nextRevision()
        assertEquals(2L, last)
        scope1.release()

        // A fresh store over the same file must continue above the last value.
        val resumed = newStore().first.nextRevision()
        assertTrue("revision regressed across restart: $resumed <= $last", resumed > last)
        assertEquals(3L, resumed)
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
