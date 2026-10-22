package cloud.trotter.log.strength.transfer.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
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
class CardioHistoryRestoreTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: TrackerRepository
    private lateinit var service: BackupService
    private val session = CardioSessionEntity(
        dayId = "A", mode = "LOW_IMPACT", hard = false, label = "Row",
        startedAt = 1_000, completedAt = 61_000, seconds = 60, stepsCompleted = 1, activity = "ROWER",
    )
    private val sets = listOf(CardioSetResult(
        CardioSetSpec(CardioRole.WORK, Measure.Time(60)), CardioSetStatus.FINISHED, 60,
    ))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-backup", ".preferences_pb")
        })
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-backup-journal", ".preferences_pb")
        }, settings)
        repository = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        service = BackupService(repository, journal)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `v12 service round trip restores exact rows replacing later results`() = runTest {
        val id = repository.logCardioSession(session, sets)
        val before = repository.exportSnapshot()
        val backup = service.export()
        repository.logCardioSession(session.copy(startedAt = 2_000), sets)
        service.import(backup)
        assertEquals(before.cardioSessions, db.cardioSessionDao().all())
        assertEquals(before.cardioSetResults, db.cardioSetResultDao().all())
        assertEquals(sets, repository.cardioSetsFor(id))
        assertEquals(backup, service.export())
    }

    @Test fun `v12 restores negative session id with two ordered linked set results`() = runTest {
        val expectedSets = sets + CardioSetResult(
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(30)), CardioSetStatus.FINISHED, 30,
        )
        val summary = CardioSessionBackup(
            id = -1, dayId = session.dayId, mode = session.mode, hard = session.hard, label = session.label,
            startedAt = session.startedAt, completedAt = 91_000, seconds = 90, stepsCompleted = 2,
            activity = session.activity,
            sets = expectedSets.mapIndexed { index, result ->
                CardioSetResultBackup(
                    // Reverse ids so the read must follow position rather than primary key order.
                    id = 12L - index,
                    specJson = CardioBlockJson.encodeSet(result.spec),
                    status = result.status.name,
                    elapsedSeconds = result.elapsedSeconds,
                )
            },
        )
        val document = BackupDocument(
            schemaVersion = 12,
            settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
            cardioSessions = listOf(summary),
        )

        service.import(BackupCodec().encode(document))

        assertEquals(
            listOf(session.copy(id = -1, completedAt = 91_000, seconds = 90, stepsCompleted = 2)),
            db.cardioSessionDao().all(),
        )
        val rows = db.cardioSetResultDao().all()
        assertEquals(listOf(12L, 11L), rows.map { it.id })
        assertEquals(listOf(-1L, -1L), rows.map { it.cardioSessionId })
        assertEquals(listOf(0, 1), rows.map { it.position })
        assertEquals(rows, db.cardioSetResultDao().forSession(-1))
        assertEquals(expectedSets, repository.cardioSetsFor(-1))
    }

    @Test fun `v12 service restores a twenty second shortened cardio session`() = runTest {
        assertShortSessionRestores(20)
    }

    @Test fun `v12 service restores a zero second shortened cardio session`() = runTest {
        assertShortSessionRestores(0)
    }

    private suspend fun assertShortSessionRestores(seconds: Int) {
        val shortSets = listOf(sets.single().copy(status = CardioSetStatus.SHORTENED, elapsedSeconds = seconds))
        val shortSession = session.copy(
            completedAt = session.startedAt + maxOf(1L, seconds * 1_000L), seconds = seconds, stepsCompleted = 0,
        )
        val id = repository.logCardioSession(shortSession, shortSets)
        val before = repository.exportSnapshot()
        val backup = service.export()
        repository.logCardioSession(session.copy(startedAt = 2_000), sets)

        service.import(backup)

        assertEquals(before.cardioSessions, db.cardioSessionDao().all())
        assertEquals(before.cardioSetResults, db.cardioSetResultDao().all())
        assertEquals(shortSets, repository.cardioSetsFor(id))
        assertEquals(backup, service.export())
    }

    @Test fun `restoring v11 summary clears existing results without inventing sets`() = runTest {
        repository.logCardioSession(session, sets)
        val summary = CardioSessionBackup(
            id = 7, dayId = "A", mode = "LOW_IMPACT", hard = false, label = "Easy",
            startedAt = 1_000, completedAt = 91_000, seconds = 90, stepsCompleted = 1,
        )
        val document = BackupDocument(
            schemaVersion = 11,
            settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
            cardioSessions = listOf(summary),
        )
        val legacy = BackupCodec().encode(document).replace(",\"activity\":null", "").replace(",\"sets\":[]", "")
        service.import(legacy)
        assertEquals(document.toSnapshot().cardioSessions, db.cardioSessionDao().all())
        assertNull(repository.cardioSession(7)!!.activity)
        assertTrue(repository.cardioSetsFor(7).isEmpty())
        assertTrue(db.cardioSetResultDao().all().isEmpty())
    }
}
