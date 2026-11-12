package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioRouteEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioRouteStorageTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private lateinit var history: HistoryRepository
    private lateinit var backup: BackupRepository
    private lateinit var journal: RestoreJournal
    private val route = """{"v":1,"p":[[0,0,0],[4,11,0]]}"""
    private val session = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run",
        startedAt = 1_000, completedAt = 61_000, seconds = 60, stepsCompleted = 1,
        activity = "ROAD_RUN",
    )
    private val sets = listOf(ManualCardio.result(60, 180))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-route", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings)
        history = HistoryRepository(db, settings, plans)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, plans = plans, history = history)
        backup = BackupRepository(db, settings, plans, history)
        journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-route-journal", ".preferences_pb")
        }, settings)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `logging stores an optional valid route in the session transaction`() = runTest {
        val id = repo.logCardioSession(session, sets, route = route)
        assertEquals(listOf(CardioRouteEntity(id, route)), db.cardioRouteDao().all())
        assertEquals(id, repo.logCardioSession(session, sets, route = "x"))
        val without = repo.logCardioSession(session.copy(startedAt = 2_000), sets, route = null)
        assertNull(db.cardioRouteDao().forSession(without))
        assertFailsWith<IllegalArgumentException> {
            repo.logCardioSession(session.copy(startedAt = 3_000), sets, route = "x")
        }
        assertNull(db.cardioSessionDao().forRun("A", 3_000))
        assertEquals(2, db.cardioSessionDao().all().size)
        assertEquals(2, db.cardioSetResultDao().all().size)
        assertEquals(listOf(CardioRouteEntity(id, route)), db.cardioRouteDao().all())
    }

    @Test fun `delete and undo carry the exact saved route`() = runTest {
        val id = repo.logCardioSession(session, sets, route = route)
        val deleted = assertNotNull(history.deleteCardioSession(id))
        assertEquals(CardioRouteEntity(id, route), deleted.route)
        assertTrue(db.cardioRouteDao().all().isEmpty())
        history.restoreCardioSession(deleted)
        assertEquals(CardioRouteEntity(id, route), db.cardioRouteDao().forSession(id))
    }

    @Test fun `snapshot restore keeps routes and an older snapshot clears them`() = runTest {
        val id = repo.logCardioSession(session, sets, route = route)
        val snapshot = backup.exportSnapshot()
        assertEquals(listOf(CardioRouteEntity(id, route)), snapshot.cardioRoutes)
        backup.importSnapshot(snapshot, journal)
        assertEquals(snapshot.cardioRoutes, backup.exportSnapshot().cardioRoutes)
        backup.importSnapshot(snapshot.copy(cardioRoutes = emptyList()), journal)
        assertTrue(db.cardioRouteDao().all().isEmpty())
        assertEquals(snapshot.cardioSessions, db.cardioSessionDao().all())
    }
}
