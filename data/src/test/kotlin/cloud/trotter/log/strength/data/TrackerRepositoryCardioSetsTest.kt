package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryCardioSetsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private lateinit var journal: RestoreJournal
    private val session = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = true, label = "Track",
        startedAt = 1_000, completedAt = 451_000, seconds = 450, stepsCompleted = 1,
        activity = "TRACK_RUN",
    )
    private val sets = listOf(
        CardioSetResult(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)), CardioSetStatus.FINISHED, 120),
        CardioSetResult(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
            CardioSetStatus.SHORTENED, 100, 380, DistanceSource.MANUAL,
        ),
        CardioSetResult(CardioSetSpec(CardioRole.COOLDOWN, Measure.Time(300)), CardioSetStatus.SKIPPED, 0),
    )

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-sets", ".preferences_pb")
        })
        journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-sets-journal", ".preferences_pb")
        }, settings)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `three results round trip in order with null skipped distance`() = runTest {
        val id = repo.logCardioSession(session, sets)
        assertEquals(session.copy(id = id), repo.cardioSession(id))
        assertEquals(sets, repo.cardioSetsFor(id))
        assertEquals(listOf(0, 1, 2), db.cardioSetResultDao().forSession(id).map { it.position })
    }

    @Test fun `summary callers keep exactly one session and no sets`() = runTest {
        val legacy = session.copy(activity = null)
        val id = repo.logCardioSession(legacy)
        assertEquals(listOf(legacy.copy(id = id)), db.cardioSessionDao().all())
        assertEquals(emptyList<CardioSetResult>(), repo.cardioSetsFor(id))
        assertNull(repo.cardioSession(id)?.activity)
    }

    @Test fun `delete and undo carry exact rows without touching another session`() = runTest {
        val id = repo.logCardioSession(session, sets)
        val other = repo.logCardioSession(session.copy(startedAt = 2_000), sets)
        val originalRows = db.cardioSetResultDao().forSession(id)
        val deleted = checkNotNull(repo.deleteCardioSession(id))
        assertEquals(originalRows, deleted.sets)
        assertNull(repo.cardioSession(id))
        assertEquals(emptyList<CardioSetResult>(), repo.cardioSetsFor(id))
        assertEquals(sets, repo.cardioSetsFor(other))
        assertNull(repo.deleteCardioSession(id))
        repo.restoreCardioSession(deleted)
        assertEquals(deleted.session, repo.cardioSession(id))
        assertEquals(originalRows, db.cardioSetResultDao().forSession(id))
        assertEquals(sets, repo.cardioSetsFor(id))
    }

    @Test fun `full restore replaces sets and restores their original ids`() = runTest {
        val id = repo.logCardioSession(session, sets)
        val snapshot = repo.exportSnapshot()
        repo.logCardioSession(session.copy(startedAt = 2_000), sets)
        repo.importSnapshot(snapshot, journal)
        assertEquals(snapshot.cardioSessions, db.cardioSessionDao().all())
        assertEquals(snapshot.cardioSetResults, db.cardioSetResultDao().all())
        assertEquals(sets, repo.cardioSetsFor(id))
        repo.importSnapshot(snapshot.copy(cardioSetResults = emptyList()), journal)
        assertEquals(snapshot.cardioSessions, db.cardioSessionDao().all())
        assertEquals(emptyList<CardioSetResult>(), repo.cardioSetsFor(id))
        assertTrue(db.cardioSetResultDao().all().isEmpty())
    }

    @Test fun `a set insert failure rolls back both the header and preceding sets`() = runTest {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_second_set BEFORE INSERT ON cardio_set_result " +
                "WHEN NEW.position = 1 BEGIN SELECT RAISE(ABORT, 'set write failed'); END",
        )
        val failure = runCatching { repo.logCardioSession(session, sets) }.exceptionOrNull()
        assertTrue(checkNotNull(failure).message.orEmpty().contains("set write failed"))
        assertTrue(db.cardioSessionDao().all().isEmpty())
        assertTrue(db.cardioSetResultDao().all().isEmpty())
    }
}
