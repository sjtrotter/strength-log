package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.seeding.SetSeeder
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mid-run plan edits preserve done work and leave working-value clearing intact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryRunEditsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val ids = ArrayDeque((1..100).map { "id-$it" })
    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)
    private val load = Prescription(PrescribedTarget.LoadReps(50.0, 10))

    /** Creates an isolated repository with deterministic run identities and time. */
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("run-edits-settings", ".preferences_pb")
        }
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(),
            SettingsStore(dataStore), clock, { ids.removeFirst() })
    }

    /** Releases the database and settings scope. */
    @After fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun work() = SetSeeder.seedPrescribed(3, load)
    private fun squat() = SetSeeder.seed(ProgramExercise("bb_back_squat", isMain = true), 235.0, LifterConfig())
    private suspend fun generated(): Long {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Day A", "", listOf(
                ProgramExercise("bb_back_squat", isMain = true),
                ProgramExercise("bb_bench"), ProgramExercise("ez_curl"), ProgramExercise("face_pull"),
                ProgramExercise("db_lateral"),
            ), null),
            ProgramDay("B", "Day B", "", listOf(ProgramExercise("bb_bench")), null),
        )))
        return repo.selectedPlanIdFlow.first()!!
    }
    private suspend fun authored(): Long {
        val plan = repo.createAuthoredPlan("Authored", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("bb_back_squat"))
        repo.setPrescription(slot(plan), load, 3)
        return plan
    }
    private suspend fun slot(plan: Long) = db.programDao().exercisesForDay(plan, "A").first().id
    private suspend fun start(plan: Long, doneCount: Int = 1, sets: List<LoggedSet> = squat()): String {
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(RunWriteResult.InRun(runId, true), repo.updateSets(runId, "A", slot(plan), Slot.MAIN,
            sets.mapIndexed { i, row -> row.copy(done = i < doneCount) }))
        return runId
    }
    private suspend fun seed(runId: String, slotId: Long, sets: List<LoggedSet> = work(), trackSlot: String = Slot.MAIN) {
        repo.seedRunTracksIfMissing(runId, listOf(RunSeed(slotId, trackSlot, sets)))
    }

    /** [F] */
    @Test fun `swap retires done work and finish archives both captured prescriptions`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        repo.seedIfEmpty("A", slot, Slot.MAIN, squat())
        val runId = start(plan, doneCount = 2)
        val before = db.runDao().tracks(runId).single()
        repo.swapExercise("A", 0, "hack_squat")
        assertEquals(before.copy(retired = true), db.runDao().tracks(runId).single())
        assertEquals("hack_squat", db.programDao().exercise(slot)!!.exerciseId)
        assertNull(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN))
        val tracks = repo.dayTracksFlow(plan, "A").first()
        assertTrue(tracks.tracks.none { it.programExerciseId == slot && it.slot == Slot.MAIN })
        assertEquals(listOf("bb_back_squat"), tracks.retired.map { it.exerciseId })
        seed(runId, slot)
        val active = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        assertEquals("hack_squat", active.exerciseId)
        val session = repo.finishRun(runId)!!
        val rows = repo.sessionSets(session)
        val squatRows = rows.filter { it.exerciseId == "bb_back_squat" }
        assertEquals(2, squatRows.size)
        assertTrue(squatRows.all { it.done })
        assertEquals(active.sets.size, rows.count { it.exerciseId == "hack_squat" })
        val planned = SessionPlanJson.decode(db.sessionDao().sessionByRunId(runId)!!.plannedJson!!)
        assertEquals(2, planned.size)
        assertTrue(planned.single { it.exerciseId == "bb_back_squat" }.retired)
        assertFalse(planned.single { it.exerciseId == "hack_squat" }.retired)
        assertEquals(SetJson.decodeSets(before.plannedJson), planned.first().sets.map { it.toDomain() })
    }

    /** [F] */
    @Test fun `swap deletes an old track with no done rows`() = runTest {
        val plan = generated()
        val runId = start(plan)
        repo.updateSets(runId, "A", slot(plan), Slot.MAIN, squat())
        repo.swapExercise("A", 0, "hack_squat")
        assertTrue(db.runDao().tracks(runId).isEmpty())
        assertTrue(repo.dayTracksFlow(plan, "A").first().retired.isEmpty())
    }

    /** [F] */
    @Test fun `swapping back reopens earlier rows and drops an undone alternate`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val runId = start(plan)
        val before = db.runDao().tracks(runId).single()
        repo.swapExercise("A", 0, "hack_squat")
        seed(runId, slot)
        repo.swapExercise("A", 0, "bb_back_squat")
        seed(runId, slot, work())
        assertEquals(listOf(before), db.runDao().tracks(runId))
        assertTrue(repo.dayTracksFlow(plan, "A").first().retired.isEmpty())
    }

    /** [F] */
    @Test fun `prescription edit reseeds undone rows without changing captured prescription`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.seedIfEmpty("A", slot, Slot.MAIN, work())
        val runId = start(plan, sets = work())
        val before = db.runDao().tracks(runId).single()
        val changed = Prescription(PrescribedTarget.LoadReps(55.0, 8))
        repo.setPrescription(slot, changed, sets = 4)
        val after = db.runDao().tracks(runId).single()
        val expected = listOf(SetJson.decodeSets(before.setsJson)[0]) +
            SetSeeder.seedPrescribed(3, changed).mapIndexed { i, row -> row.copy(plannedIndex = (i + 1).takeIf { it < 3 }) }
        assertEquals(expected, SetJson.decodeSets(after.setsJson))
        assertEquals(listOf(0, 1, 2, null), SetJson.decodeSets(after.setsJson).map { it.plannedIndex })
        assertEquals(before.plannedJson, after.plannedJson)
        assertNull(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN))
    }

    /** Tracking-type changes are deferred to future runs. */
    @Test fun `tracking type change leaves the run track byte identical`() = runTest {
        val plan = authored()
        val runId = start(plan, sets = work())
        val before = db.runDao().tracks(runId)
        repo.setPrescription(slot(plan), Prescription(PrescribedTarget.Hold(45)))
        assertEquals(before, db.runDao().tracks(runId))
    }

    /** [F] */
    @Test fun `remove retires done tracks skips carry forward and deletes undone tracks`() = runTest {
        val plan = generated()
        val slots = db.programDao().exercisesForDay(plan, "A")
        val runId = start(plan)
        seed(runId, slots[1].id)
        assertTrue(repo.removeExercise(slots[0].id))
        assertTrue(repo.removeExercise(slots[1].id))
        val retired = db.runDao().tracks(runId).single()
        assertTrue(retired.retired)
        assertEquals(slots[0].id, retired.programExerciseId)
        val session = repo.finishRun(runId)!!
        val archived = repo.sessionSets(session)
        assertEquals(1, archived.size)
        assertTrue(archived.single().done)
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    /** Added slots seed into the open run at their plan position. */
    @Test fun `add exercise seeds a new active track at its position`() = runTest {
        val plan = generated()
        val runId = start(plan)
        repo.addExercise("A", ProgramExercise("rdl"))
        val added = db.programDao().exercisesForDay(plan, "A").last()
        seed(runId, added.id)
        val track = db.runDao().tracks(runId).single { it.programExerciseId == added.id }
        assertEquals(added.position, track.position)
        assertEquals("rdl", track.exerciseId)
        assertFalse(track.retired)
    }

    /** [F] */
    @Test fun `partner change retires done SS work and seeds aligned to six main rows`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        repo.setSupersetPartner("A", 0, "ez_curl")
        val runId = start(plan)
        seed(runId, slot, work(), Slot.SS)
        repo.updateSets(runId, "A", slot, Slot.SS, work().mapIndexed { i, row -> row.copy(done = i == 0) })
        val before = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        repo.setSupersetPartner("A", 0, "rope_pushdown")
        assertEquals(before.copy(retired = true), db.runDao().tracks(runId).single { it.slot == Slot.SS })
        val existing = repo.dayTracksFlow(plan, "A").first().tracks
            .associate { (it.programExerciseId to it.slot) to it.sets.size }
        assertEquals(6, existing[slot to Slot.MAIN])
        seed(runId, slot, SetSeeder.seedPartner(existing.getValue(slot to Slot.MAIN), 50.0), Slot.SS)
        val partner = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS && !it.retired }
        assertEquals("rope_pushdown", partner.exerciseId)
        assertEquals(6, partner.sets.size)
    }

    /** [F] */
    @Test fun `reset retires done work drops undone tracks and preserves run identity through finish`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        repo.setSupersetPartner("A", 0, "ez_curl")
        val runId = start(plan)
        seed(runId, slot, work(), Slot.SS)
        val beforeDay = db.programDao().day(plan, "A")!!
        val before = db.runDao().tracks(runId).single { it.slot == Slot.MAIN }
        repo.resetDayToTemplate("A")
        assertEquals(listOf(before.copy(retired = true)), db.runDao().tracks(runId))
        val afterDay = db.programDao().day(plan, "A")!!
        assertEquals(beforeDay.nextRunId, afterDay.nextRunId)
        assertEquals(beforeDay.closedAt, afterDay.closedAt)
        assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
        repo.finishRun(runId)
        assertEquals("B", db.planDao().plan(plan)!!.suggestedDayId)
    }
}
