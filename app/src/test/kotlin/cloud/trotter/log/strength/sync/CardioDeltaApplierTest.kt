package cloud.trotter.log.strength.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardioDeltaApplierTest {
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var scope: CoroutineScope
    private val markers = mutableMapOf<String, Long>()

    private val markerStore = object : AppliedEditMarkers {
        override suspend fun lastApplied(rowKey: String) = markers[rowKey] ?: 0L
        override suspend fun markApplied(rowKey: String, editedAtMillis: Long) { markers[rowKey] = editedAtMillis }
    }
    private val publisher = object : SessionPublisher {
        var cardioPublishes = 0
        override suspend fun publish(sessionId: Long) = Unit
        override suspend fun publishAll(sessionIds: List<Long>) = true
        override suspend fun publishCardio(sessionId: Long) { cardioPublishes++ }
    }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File.createTempFile("cardio-applier", ".preferences_pb") }
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), SettingsStore(dataStore))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun delta(stamp: Long = 10L, startedAt: Long = 1_000L) = CardioDelta(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Easy Zone 2",
        startedAt = startedAt, completedAt = startedAt + 60_000L,
        seconds = 60, stepsCompleted = 0, stamp = stamp,
    )

    @Test fun `stamp replay inserts and publishes once`() = runTest {
        val applier = CardioDeltaApplier(repo, markerStore, publisher)
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(delta()))
        assertEquals(CardioDeltaApplier.Outcome.STALE, applier.apply(delta()))
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(1, publisher.cardioPublishes)
    }

    @Test fun `day and start guard dedupes a phone-local session under a new stamp`() = runTest {
        val applier = CardioDeltaApplier(repo, markerStore, publisher)
        applier.apply(delta(stamp = 10L))
        assertEquals(CardioDeltaApplier.Outcome.APPLIED, applier.apply(delta(stamp = 11L)))
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(1, publisher.cardioPublishes)
    }
}
