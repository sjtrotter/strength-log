package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishArchiveRirTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("finish-rir", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `finish archives a done TOP row with rir and preserves absent rir`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 2),
        )))))
        val slot = db.programDao().allExercises().single()
        val day = db.programDao().allDays().single()
        val result = repo.writeTestRows(day.nextRunId, "A", slot.id, Slot.MAIN, listOf(
            LoggedSet(235.0, 5, SetKind.TOP, done = true, rir = 1),
            LoggedSet(175.0, 8, SetKind.BACKOFF, done = true),
        ))
        check(result is RunWriteResult.InRun)
        val sessionId = requireNotNull(repo.finishRun(repo.openRunFlow.first()!!.id))
        val sets = db.sessionDao().setsForSession(sessionId)
        assertEquals(listOf(1, null), sets.map { it.rir })
    }
}
