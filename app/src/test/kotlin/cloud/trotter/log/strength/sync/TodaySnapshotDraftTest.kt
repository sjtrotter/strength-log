package cloud.trotter.log.strength.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.TrackEdit
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.seeding.SetEdit
import cloud.trotter.log.strength.domain.seeding.SetEditor
import java.io.File
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodaySnapshotDraftTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private val clock = Clock.systemDefaultZone()
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var syncStore: WearSyncStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("pub-rest-settings", ".preferences_pb")
        }
        syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("pub-wear-sync", ".preferences_pb")
        })
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test fun `a draft has no session clock until Start promotes it`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(ProgramExercise("bb_bench"))))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val b = db.programDao().exercisesForDay(plan, "A").single().id
        val next = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(RunWriteResult.InRun(next, false),
            repo.applyToRun(next, plan, "A", listOf(TrackEdit(b, Slot.MAIN) { SetEditor.edit(it, 0, SetEdit(reps = 4)) })))
        assertTrue(assertNotNull(db.runDao().openRun()).draft)
        val source = TodaySnapshotSource(repo, flowOf(LocalDate.now(clock)), syncStore, syncStore, settings, repo.plans, repo.history)
        assertNull(assertNotNull(source.snapshots.first()).runStartedAt)
        assertEquals(RunStartResult.Started(next), repo.startRun(plan, "A"))
        assertEquals(db.runDao().openRun()!!.startedAt, assertNotNull(source.snapshots.first()).runStartedAt)
    }
}
