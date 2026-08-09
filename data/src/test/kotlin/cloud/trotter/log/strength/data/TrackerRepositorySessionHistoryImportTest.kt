package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.SetKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TrackerRepository.importSessionHistory] and [TrackerRepository.exportSessionHistory]
 * (CSV import/export, issue #16): additive appends that must link each
 * imported session to its own freshly generated id, leave the program and
 * live logs untouched, and read back in the same deterministic order the A2
 * backup uses. Robolectric + in-memory Room, same pattern as
 * [TrackerRepositoryPairedWriteTest] — the transaction is the thing under
 * test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositorySessionHistoryImportTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("csv-import-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun session(dayTitle: String, completedAt: Long) =
        WorkoutSessionEntity(id = 0, dayId = "csv:$dayTitle", dayTitle = dayTitle, startedAt = null, completedAt = completedAt, bodyweightLb = null)

    private fun set(exerciseId: String, exerciseName: String, setIndex: Int) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = exerciseName,
        slot = Slot.MAIN, setIndex = setIndex, kind = SetKind.WORK.name, weightLb = 100.0, reps = 8, done = true,
    )

    @Test
    fun importSessionHistoryAssignsEachSessionItsOwnIdAndLinksItsOwnSets() = runTest {
        val importedA = ImportedSession(session("Day A", 1_000L), listOf(set("bb_back_squat", "Barbell Back Squat", 0)))
        val importedB = ImportedSession(session("Day B", 2_000L), listOf(set("bb_bench", "Barbell Bench Press", 0)))

        repo.importSessionHistory(listOf(importedA, importedB), newCustomExercises = emptyList())

        val sessions = repo.sessionsFlow.first()
        assertEquals(2, sessions.size)
        assertNotEquals(sessions[0].id, sessions[1].id)

        val history = repo.exportSessionHistory()
        assertEquals(2, history.sessions.size)
        assertEquals(2, history.sessionSets.size)
        for (s in history.sessionSets) {
            assertTrue(history.sessions.any { it.id == s.sessionId })
        }
    }

    @Test
    fun importedSessionsKeepTheirUnknownBodyweight() = runTest {
        // A CSV has no bodyweight column (#171); the column must round-trip as
        // NULL rather than the 0 that used to make later backups unrestorable.
        repo.importSessionHistory(
            listOf(ImportedSession(session("Day A", 1_000L), listOf(set("bb_back_squat", "Barbell Back Squat", 0)))),
            newCustomExercises = emptyList(),
        )

        assertNull(repo.sessionsFlow.first().single().bodyweightLb)
    }

    @Test
    fun importSessionHistoryUpsertsNewCustomExercisesInTheSameTransaction() = runTest {
        val custom = CustomExerciseEntity(
            id = "custom_abc123", name = "Mystery Move", pattern = "H_PULL",
            equipmentCsv = "", perHand = false, goalStartLb = 0.0,
        )
        val imported = ImportedSession(session("Day A", 1_000L), listOf(set(custom.id, custom.name, 0)))

        repo.importSessionHistory(listOf(imported), newCustomExercises = listOf(custom))

        val catalog = repo.catalogFlow.first()
        assertTrue(catalog.entries.any { it.id == custom.id })
    }

    @Test
    fun importSessionHistoryDoesNotTouchProgramOrLiveLogs() = runTest {
        val imported = ImportedSession(session("Day A", 1_000L), listOf(set("bb_back_squat", "Barbell Back Squat", 0)))
        repo.importSessionHistory(listOf(imported), newCustomExercises = emptyList())

        assertEquals(0, repo.programFlow.first().days.size)
    }

    @Test
    fun exportSessionHistoryReadsBackTheCurrentUnitAndAllSessions() = runTest {
        repo.setUnit(cloud.trotter.log.strength.domain.units.WeightUnit.KG)
        val imported = ImportedSession(session("Day A", 1_000L), listOf(set("bb_back_squat", "Barbell Back Squat", 0)))
        repo.importSessionHistory(listOf(imported), newCustomExercises = emptyList())

        val history = repo.exportSessionHistory()
        assertEquals(cloud.trotter.log.strength.domain.units.WeightUnit.KG, history.unit)
        assertEquals(1, history.sessions.size)
        assertEquals(1, history.sessionSets.size)
    }
}
