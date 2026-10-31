package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
class TrackerRepositoryNotesTest {
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var settings: SettingsStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + Job())
        store = PreferenceDataStoreFactory.create(scope = scope) { File.createTempFile("notes", ".preferences_pb") }
        settings = SettingsStore(store)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun exerciseNoteRoundTripsThroughRoom() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "LOWER", "", listOf(ProgramExercise("bb_back_squat", true, 4, "5/5/5/3", true)), cardio = null))))
        val slot = repo.daySlotsFlow("A").first().single()
        repo.setExerciseNote(slot.programExerciseId, "Belt on top")
        assertEquals("Belt on top", repo.daySlotsFlow("A").first().single().exercise.note)
    }

    @Test fun sessionNoteRoundTripsThroughRoom() = runTest {
        insertSession(42)
        repo.setSessionNote(42L, "  Felt smooth  ")
        assertEquals("Felt smooth", repo.session(42L)!!.note)
        assertEquals(mapOf(42L to "Felt smooth"), repo.sessionNotesFlow.first())
        repo.setSessionNote(42L, "x".repeat(130))
        assertEquals("x".repeat(120), repo.session(42L)!!.note)
        repo.setSessionNote(42L, "   ")
        assertEquals("", repo.session(42L)!!.note)
        assertTrue(repo.sessionNotesFlow.first().isEmpty())
    }

    @Test fun legacyNotesMoveOnceDropsUnknownIdsAndClearsTheKey() = runTest {
        insertSession(11)
        insertSession(12)
        store.edit { it[stringPreferencesKey("session_notes")] = """{"11":"  Felt smooth  ","12":"${"x".repeat(130)}","999":"Orphan"}""" }

        assertEquals(mapOf(11L to "Felt smooth", 12L to "x".repeat(120)), repo.sessionNotesFlow.first())

        assertNull(repo.session(999))
        assertNull(store.data.first()[stringPreferencesKey("session_notes")])
        assertTrue(settings.legacySessionNotesFlow.first().isEmpty())
        repo.setSessionNote(11, "New note")
        val rows = db.sessionDao().allSessions()
        repo.ensureSessionNotesMoved()
        assertEquals(rows, db.sessionDao().allSessions())
    }

    @Test fun legacyNotesMoveWaitsUnderTheRestoreMarker() = runTest {
        insertSession(11)
        store.edit { it[stringPreferencesKey("session_notes")] = """{"11":"Legacy"}""" }
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "pending"))

        repo.ensureSessionNotesMoved()

        assertEquals("", repo.session(11)!!.note)
        assertEquals(mapOf(11L to "Legacy"), settings.legacySessionNotesFlow.first())
        assertTrue(repo.sessionNotesFlow.first().isEmpty())
        db.restoreMarkerDao().clear()
        repo.ensureSessionNotesMoved()
        assertEquals("Legacy", repo.session(11)!!.note)
        assertTrue(settings.legacySessionNotesFlow.first().isEmpty())
    }

    @Test fun deletingAndUndoingASessionRestoresItsNote() = runTest {
        insertSession(11)
        store.edit { it[stringPreferencesKey("session_notes")] = """{"11":"Keep this note"}""" }

        val deleted = repo.deleteSession(11)!!

        assertEquals("Keep this note", deleted.session.note)
        assertNull(repo.session(11))
        assertTrue(repo.sessionNotesFlow.first().isEmpty())
        repo.restoreSession(deleted)
        assertEquals(deleted.session, repo.session(11))
        assertEquals(mapOf(11L to "Keep this note"), repo.sessionNotesFlow.first())
    }

    @Test fun exportWaitsForTheLegacyNotesMove() = runTest {
        insertSession(11)
        store.edit { it[stringPreferencesKey("session_notes")] = """{"11":"Export me"}""" }
        assertEquals("Export me", repo.exportSnapshot().sessions.single().note)
        assertTrue(settings.legacySessionNotesFlow.first().isEmpty())
    }

    private suspend fun insertSession(id: Long) {
        db.sessionDao().insertSession(WorkoutSessionEntity(id, "A", "Day A", null, 1_000, 235))
    }
}
