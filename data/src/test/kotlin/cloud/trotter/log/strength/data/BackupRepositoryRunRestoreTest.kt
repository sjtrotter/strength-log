package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class BackupRepositoryRunRestoreTest {
    private lateinit var db: StrengthDatabase
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository
    private lateinit var backupRepository: BackupRepository
    private lateinit var storeScope: CoroutineScope
    private val ids = ArrayDeque((1..100).map { "id-$it" })
    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("run-settings", ".preferences_pb")
        }
        val settings = SettingsStore(dataStore)
        journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("run-journal", ".preferences_pb")
        }, settings)
        val mintRunId = { ids.removeFirst() }
        repo = TrackerRepository(
            db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock, mintRunId,
        )
        backupRepository = BackupRepository(db, settings, repo.plans, repo.history, clock, mintRunId)
    }

    @After fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    /** [F] */
    @Test fun `legacy restore splits on the repository clock and attaches minted identity`() = runTest {
        val plan = authored()
        val snapshot = backupRepository.exportSnapshot()
        val legacy = cloud.trotter.log.strength.data.run.LegacyLog(
            plan, "A", slot(plan), Slot.MAIN, work().map { it.copy(done = true) }, "2026-10-19", 6_000,
        )
        backupRepository.importSnapshot(snapshot.copy(days = snapshot.days.map { it.copy(nextRunId = "") },
            legacyLogs = listOf(legacy)), journal)
        val state = repo.dayTracksFlow(plan, "A").first()
        assertTrue(state.runOpen)
        assertEquals(db.programDao().day(plan, "A")!!.nextRunId, state.runId)
        assertTrue(state.tracks.single().sets.all { it.done })
        assertTrue(db.programDao().allLogs().flatMap { SetJson.decodeSets(it.setsJson) }.none { it.done })

        backupRepository.importSnapshot(snapshot.copy(legacyLogs = listOf(legacy.copy(checkDate = "2026-10-18"))), journal)
        assertNull(repo.openRunFlow.first())
        assertTrue(repo.history.sessionsFlow.first().isEmpty())
        assertTrue(repo.dayTracksFlow(plan, "A").first().tracks.single().sets.none { it.done })
    }

    /** [F] */
    @Test fun `legacy restore leaves an existing run attached to its own day`() = runTest {
        val plan = authored()
        val runId = start(plan)
        val snapshot = backupRepository.exportSnapshot()
        val legacy = listOf("A", "B").map { day ->
            cloud.trotter.log.strength.data.run.LegacyLog(plan, day, slot(plan, day), Slot.MAIN,
                work().map { it.copy(done = true) }, "2026-10-19", 6_000)
        }
        backupRepository.importSnapshot(snapshot.copy(legacyLogs = legacy), journal)
        assertEquals(snapshot.runs, db.runDao().allRuns())
        assertEquals(snapshot.runTracks, db.runDao().allTracks())
        assertEquals(runId, day(plan).nextRunId)
        assertNull(day(plan).closedAt)
        assertEquals(listOf("B"), db.sessionDao().allSessions().map { it.dayId })
        assertTrue(db.programDao().allLogs().flatMap { SetJson.decodeSets(it.setsJson) }.none { it.done })
    }

    /** [F] */
    @Test fun `snapshot restores runs tracks and mints only blank day identities`() = runTest {
        val plan = authored()
        start(plan)
        val before = backupRepository.exportSnapshot()
        backupRepository.importSnapshot(before, journal)
        val restored = backupRepository.exportSnapshot()
        assertEquals(before.runs, restored.runs)
        assertEquals(before.runTracks, restored.runTracks)
        assertEquals(before.days, restored.days)
        val legacyDay = before.days.last().copy(nextRunId = "", closedAt = 1000)
        backupRepository.importSnapshot(before.copy(days = before.days.dropLast(1) + legacyDay), journal)
        assertTrue(day(plan, "B").nextRunId.isNotBlank())
        assertNull(day(plan, "B").closedAt)
        assertEquals(before.days.first(), day(plan))
    }

    @Test fun `snapshot preserves parked working values for a later Progress choice`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setKeepToPlan(slot, false)
        db.storeTestRows("A", slot, Slot.MAIN, work())
        repo.setKeepToPlan(slot, true)
        val before = backupRepository.exportSnapshot()
        assertTrue(before.exercises.single { it.id == slot }.parkedLogsJson != null)

        backupRepository.importSnapshot(before, journal)

        assertEquals(before.exercises.map {
            it.copy(ruleJson = SlotRuleJson.encode(PlanConversion.rule(it,
                if (it.id == slot) work() else null, ExerciseLibrary.get(it.exerciseId))))
        }, db.programDao().allExercises())
        repo.setKeepToPlan(slot, false)
        assertEquals(work(), SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson))
        assertNull(db.programDao().exercise(slot)!!.parkedLogsJson)
    }

    private fun work() = List(3) { LoggedSet(50.0, 10, SetKind.WORK) }
    private fun tick(id: Long, index: Int = 0) = TrackEdit(id, Slot.MAIN) { rows ->
        rows.mapIndexed { i, row -> if (i == index) row.copy(done = true) else row }
    }
    private suspend fun day(planId: Long, dayId: String = "A") = checkNotNull(db.programDao().day(planId, dayId))
    private suspend fun slot(planId: Long, dayId: String = "A") = db.programDao().exercisesForDay(planId, dayId).first().id
    private suspend fun authored(): Long {
        val plan = repo.plans.createAuthoredPlan("Authored", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("bb_back_squat", prescription = Prescription(PrescribedTarget.LoadReps(50.0, 10)), keepToPlan = true))
        repo.addDay(plan, "Day B")
        repo.addExercise("B", ProgramExercise("bb_bench", prescription = Prescription(PrescribedTarget.LoadReps(50.0, 10)), keepToPlan = true))
        return plan
    }
    private suspend fun start(plan: Long, dayId: String = "A"): String {
        val id = day(plan, dayId).nextRunId
        val slot = slot(plan, dayId)
        assertEquals(RunWriteResult.InRun(id, true), repo.applyToRun(id, plan, dayId, listOf(tick(slot))))
        return id
    }

}
