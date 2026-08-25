package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
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

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File.createTempFile("notes", ".preferences_pb") }
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), SettingsStore(store))
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun exerciseNoteRoundTripsThroughRoom() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "LOWER", "", listOf(ProgramExercise("bb_back_squat", true, 4, "5/5/5/3", true)), cardio = null))))
        val slot = repo.daySlotsFlow("A").first().single()
        repo.setExerciseNote(slot.programExerciseId, "Belt on top")
        assertEquals("Belt on top", repo.daySlotsFlow("A").first().single().exercise.note)
    }

    @Test fun sessionNoteRoundTripsThroughDataStore() = runTest {
        repo.setSessionNote(42L, "Felt smooth")
        assertEquals("Felt smooth", repo.sessionNoteFlow(42L).first())
        repo.setSessionNote(42L, "")
        assertEquals("", repo.sessionNoteFlow(42L).first())
    }
}
