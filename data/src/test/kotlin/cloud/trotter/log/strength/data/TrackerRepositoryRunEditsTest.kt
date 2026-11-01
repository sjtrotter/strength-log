package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.standards.GoalCalculator
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
            )),
            ProgramDay("B", "Day B", "", listOf(ProgramExercise("bb_bench"))),
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
        repo.swapExerciseById("A", slot, "hack_squat")
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
        repo.swapExerciseById("A", slot(plan), "hack_squat")
        assertTrue(db.runDao().tracks(runId).isEmpty())
        assertTrue(repo.dayTracksFlow(plan, "A").first().retired.isEmpty())
    }

    /** [F] */
    @Test fun `swapping back reopens earlier rows and drops an undone alternate`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val runId = start(plan)
        val before = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot)
        val swapped = db.runDao().tracks(runId)
        assertEquals(before.copy(retired = true), swapped.single { it.exerciseId == "bb_back_squat" })
        val alternate = swapped.single { it.exerciseId == "hack_squat" }
        assertFalse(alternate.retired)
        assertTrue(SetJson.decodeSets(alternate.setsJson).none { it.done })
        repo.swapExerciseById("A", slot, "bb_back_squat")
        seed(runId, slot, squat())
        assertEquals(listOf(before), db.runDao().tracks(runId))
        assertTrue(repo.dayTracksFlow(plan, "A").first().retired.isEmpty())
    }

    /** [F] */
    @Test fun `swapping back retires a done alternate archives it and carries restored values forward`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val restoredRows = squat().mapIndexed { i, row ->
            if (i == 0) row.copy(weightLb = 155.0, reps = 7) else row
        }
        val runId = start(plan, sets = restoredRows)
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot)
        val swapped = db.runDao().tracks(runId)
        assertEquals(original.copy(retired = true), swapped.single { it.exerciseId == "bb_back_squat" })
        assertFalse(swapped.single { it.exerciseId == "hack_squat" }.retired)
        repo.updateSets(runId, "A", slot, Slot.MAIN, work().mapIndexed { i, row -> row.copy(done = i < 2) })
        val alternate = db.runDao().tracks(runId).single { it.exerciseId == "hack_squat" }
        repo.swapExerciseById("A", slot, "bb_back_squat")
        seed(runId, slot, squat())
        val restored = db.runDao().tracks(runId)
        assertEquals(original, restored.single { it.exerciseId == "bb_back_squat" })
        assertEquals(alternate.copy(retired = true), restored.single { it.exerciseId == "hack_squat" })
        val session = repo.finishRun(runId)!!
        val archived = repo.sessionSets(session)
        val alternateRows = archived.filter { it.exerciseId == "hack_squat" }
        assertEquals(2, alternateRows.size)
        assertTrue(alternateRows.all { it.done })
        assertEquals(restoredRows.size, archived.count { it.exerciseId == "bb_back_squat" })
        val carried = SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson)
        assertEquals(restoredRows, carried)
        assertEquals("bb_back_squat", db.programDao().exercise(slot)!!.exerciseId)
    }

    /** [F] */
    @Test fun `zero load prescription edit replans undone rows without changing captured prescription`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val zeroLoad = Prescription(PrescribedTarget.LoadReps(0.0, 10))
        repo.setPrescription(slot, zeroLoad, sets = 3)
        val seeded = SetSeeder.seedPrescribed(3, zeroLoad)
        repo.seedIfEmpty("A", slot, Slot.MAIN, seeded)
        val runId = start(plan, sets = seeded)
        val before = db.runDao().tracks(runId).single()
        val changed = Prescription(PrescribedTarget.LoadReps(135.0, 8))
        repo.setPrescription(slot, changed, sets = 4)
        val after = db.runDao().tracks(runId).single()
        val expected = listOf(SetJson.decodeSets(before.setsJson)[0]) +
            SetSeeder.seedPrescribed(3, changed).mapIndexed { i, row -> row.copy(plannedIndex = (i + 1).takeIf { it < 3 }) }
        assertEquals(expected, SetJson.decodeSets(after.setsJson))
        assertEquals(listOf(0, 1, 2, null), SetJson.decodeSets(after.setsJson).map { it.plannedIndex })
        assertEquals(before.plannedJson, after.plannedJson)
        assertNull(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN))
    }

    @Test fun `authored prescribed pair shrink keeps both rows when only SS is done past the new count`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setSupersetPartner("A", slot, "ez_curl")
        assertEquals("ez_curl", db.programDao().exercise(slot)!!.supersetExerciseId)
        val partner = SetSeeder.seedPartner(3, 45.0)
        repo.seedIfEmpty("A", slot, Slot.MAIN, work())
        repo.seedIfEmpty("A", slot, Slot.SS, partner)
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        val completed = partner.mapIndexed { i, row -> row.copy(done = i == 2, watchEditedAt = 123L) }
        assertEquals(RunWriteResult.InRun(runId, true), repo.updateSets(runId, "A", slot, Slot.SS, completed))
        val before = db.runDao().tracks(runId).associateBy { it.slot }
        val changed = Prescription(PrescribedTarget.LoadReps(60.0, 8))

        repo.setPrescription(slot, changed, sets = 2)

        val after = db.runDao().tracks(runId).associateBy { it.slot }
        val main = SetJson.decodeSets(after.getValue(Slot.MAIN).setsJson)
        val ss = SetJson.decodeSets(after.getValue(Slot.SS).setsJson)
        assertEquals(3, main.size)
        assertEquals(3, ss.size)
        assertTrue(main.none { it.done })
        assertEquals(listOf(false, false, true), ss.map { it.done })
        assertEquals(SetJson.decodeSets(before.getValue(Slot.MAIN).setsJson)[2], main[2])
        assertEquals(SetJson.decodeSets(before.getValue(Slot.SS).setsJson), ss)
        assertEquals(listOf(0, 1, 2), main.map { it.plannedIndex })
        assertEquals(listOf(0, 1, 2), ss.map { it.plannedIndex })
        assertEquals(SetSeeder.seedPrescribed(2, changed).map { it.weightLb to it.reps }, main.take(2).map { it.weightLb to it.reps })
        assertEquals(before.mapValues { it.value.plannedJson }, after.mapValues { it.value.plannedJson })
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `authored prescribed pair growth seeds new SS rows from the partners goal`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setSupersetPartner("A", slot, "ez_curl")
        val partner = SetSeeder.seedPartner(3, 45.0)
        repo.seedIfEmpty("A", slot, Slot.MAIN, work())
        repo.seedIfEmpty("A", slot, Slot.SS, partner)
        val runId = start(plan, sets = work())
        val before = db.runDao().tracks(runId).associateBy { it.slot }
        val changed = Prescription(PrescribedTarget.LoadReps(60.0, 8))
        val config = repo.wizardAnswersFor(plan).first().config
        val goal = GoalCalculator.targetFor(repo.catalogFlow.first().get("ez_curl"), config)

        repo.setPrescription(slot, changed, sets = 4)

        val after = db.runDao().tracks(runId).associateBy { it.slot }
        val main = SetJson.decodeSets(after.getValue(Slot.MAIN).setsJson)
        val ss = SetJson.decodeSets(after.getValue(Slot.SS).setsJson)
        assertEquals(4, main.size)
        assertEquals(4, ss.size)
        assertEquals(SetJson.decodeSets(before.getValue(Slot.MAIN).setsJson)[0], main[0])
        assertEquals(SetSeeder.seedPrescribed(4, changed).last(), main.last())
        assertEquals(SetJson.decodeSets(before.getValue(Slot.SS).setsJson), ss.take(3))
        assertEquals(SetSeeder.seedPartner(4, goal).last(), ss.last())
        assertTrue(ss.last().weightLb != partner.first().weightLb)
        assertEquals(listOf(0, 1, 2, null), main.map { it.plannedIndex })
        assertEquals(listOf(0, 1, 2, null), ss.map { it.plannedIndex })
        assertEquals(before.mapValues { it.value.plannedJson }, after.mapValues { it.value.plannedJson })
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `prescription edit leaves a missing catalog partners run track unchanged`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setSupersetPartner("A", slot, "missing_partner")
        val partner = SetSeeder.seedPartner(3, 45.0)
        repo.seedIfEmpty("A", slot, Slot.MAIN, work())
        repo.seedIfEmpty("A", slot, Slot.SS, partner)
        val runId = start(plan, sets = work())
        val before = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        val changed = Prescription(PrescribedTarget.LoadReps(60.0, 8))

        repo.setPrescription(slot, changed, sets = 4)

        assertEquals(before, db.runDao().tracks(runId).single { it.slot == Slot.SS })
        val main = SetJson.decodeSets(db.runDao().tracks(runId).single { it.slot == Slot.MAIN }.setsJson)
        assertEquals(4, main.size)
        assertEquals(SetSeeder.seedPrescribed(4, changed).last(), main.last())
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `paired tracking type change leaves both run tracks byte identical and clears working values`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setSupersetPartner("A", slot, "ez_curl")
        repo.seedIfEmpty("A", slot, Slot.MAIN, work())
        repo.seedIfEmpty("A", slot, Slot.SS, SetSeeder.seedPartner(3, 35.0))
        val runId = start(plan, sets = work())
        val before = db.runDao().tracks(runId)
        assertEquals(2, before.size)
        repo.setPrescription(slot, Prescription(PrescribedTarget.Hold(45)), sets = 4)
        assertEquals(before, db.runDao().tracks(runId))
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    /** Tracking-type changes are deferred to future runs. */
    @Test fun `tracking type change leaves the run track byte identical`() = runTest {
        val plan = authored()
        val runId = start(plan, sets = work())
        val before = db.runDao().tracks(runId)
        val hold = Prescription(PrescribedTarget.Hold(45))
        repo.setPrescription(slot(plan), hold)
        assertEquals(hold, PrescriptionJson.decode(db.programDao().exercise(slot(plan))!!.prescriptionJson))
        assertEquals(before, db.runDao().tracks(runId))
        val longerHold = Prescription(PrescribedTarget.Hold(60))
        repo.setPrescription(slot(plan), longerHold)
        assertEquals(longerHold, PrescriptionJson.decode(db.programDao().exercise(slot(plan))!!.prescriptionJson))
        assertEquals(before, db.runDao().tracks(runId))
    }

    /** [F] */
    @Test fun `tick before lazy seed reopens the swapped back track`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val runId = start(plan)
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot)
        assertTrue(db.runDao().tracks(runId).single { it.exerciseId == "bb_back_squat" }.retired)
        repo.swapExerciseById("A", slot, "bb_back_squat")
        val result = repo.applyToRun(runId, plan, "A", listOf(TrackEdit(slot, Slot.MAIN) { rows ->
            rows.mapIndexed { i, row -> if (i == 1) row.copy(done = true) else row }
        }))
        assertEquals(RunWriteResult.InRun(runId, false), result)
        val reopened = db.runDao().tracks(runId).single()
        assertEquals(original.id, reopened.id)
        assertEquals(original.plannedJson, reopened.plannedJson)
        assertFalse(reopened.retired)
        assertEquals(SetJson.decodeSets(original.setsJson).mapIndexed { i, row ->
            if (i == 1) row.copy(done = true) else row
        }, SetJson.decodeSets(reopened.setsJson))
    }

    @Test fun `whole list writes before lazy seed cannot drop reopened done rows`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val runId = start(plan, doneCount = 2)
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot)
        repo.swapExerciseById("A", slot, "bb_back_squat")
        val retired = db.runDao().tracks(runId)
        assertEquals(listOf(original.copy(retired = true)), retired)
        val originalRows = SetJson.decodeSets(original.setsJson)
        assertEquals(2, originalRows.count { it.done })
        // The last replacement keeps the done count but swaps which row is done (an Undo of
        // another removed row): it must be refused too.
        val shuffled = originalRows.mapIndexed { i, row -> if (i == 0) row.copy(done = false) else if (i == 2) row.copy(done = true) else row }
        for (replacement in listOf(emptyList(), originalRows.take(1), shuffled)) {
            assertEquals(RunWriteResult.Rejected, repo.updateSets(runId, "A", slot, Slot.MAIN, replacement))
            assertEquals(retired, db.runDao().tracks(runId))
        }
        seed(runId, slot, squat())
        val ticked = SetJson.decodeSets(db.runDao().tracks(runId).single().setsJson).mapIndexed { i, row ->
            if (i == 2) row.copy(done = true) else row
        }
        assertEquals(RunWriteResult.InRun(runId, false), repo.updateSets(runId, "A", slot, Slot.MAIN, ticked))
        val after = db.runDao().tracks(runId).single()
        assertFalse(after.retired)
        assertEquals(ticked, SetJson.decodeSets(after.setsJson))
        assertEquals(originalRows.take(2), ticked.take(2))
        assertEquals(3, ticked.count { it.done })
    }

    @Test fun `reopening after a timed prescription edit preserves rep shaped rows`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot)
        val hold = Prescription(PrescribedTarget.Hold(45))
        repo.setPrescription(slot, hold)
        assertEquals(hold, PrescriptionJson.decode(db.programDao().exercise(slot)!!.prescriptionJson))
        repo.swapExerciseById("A", slot, "bb_back_squat")
        val holds = SetSeeder.seedPrescribed(3, hold)
        seed(runId, slot, holds)
        assertEquals(listOf(original), db.runDao().tracks(runId))
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot, holds)
        repo.swapExerciseById("A", slot, "bb_back_squat")
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(
            runId, plan, "A", listOf(TrackEdit(slot, Slot.MAIN) { it }),
            seeds = listOf(RunSeed(slot, Slot.MAIN, holds)),
        ))
        assertEquals(listOf(original), db.runDao().tracks(runId))
    }

    /** [F] */
    @Test fun `reopening by lazy seed follows a prescription edited while retired`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot)
        assertTrue(db.runDao().tracks(runId).single { it.exerciseId == "bb_back_squat" }.retired)
        val changed = Prescription(PrescribedTarget.LoadReps(55.0, 8))
        repo.setPrescription(slot, changed, sets = 4)
        assertEquals(original.copy(retired = true), db.runDao().tracks(runId).single { it.exerciseId == "bb_back_squat" })
        repo.swapExerciseById("A", slot, "bb_back_squat")
        seed(runId, slot, SetSeeder.seedPrescribed(4, changed))
        val reopened = db.runDao().tracks(runId).single()
        assertFalse(reopened.retired)
        assertEquals(original.id, reopened.id)
        assertEquals(original.plannedJson, reopened.plannedJson)
        val rows = SetJson.decodeSets(reopened.setsJson)
        assertEquals(SetJson.decodeSets(original.setsJson).first(), rows.first())
        assertEquals(listOf(55.0, 55.0, 55.0), rows.drop(1).map { it.weightLb })
        assertEquals(listOf(8, 8, 8), rows.drop(1).map { it.reps })
        assertTrue(rows.drop(1).none { it.done })
        assertEquals(listOf(0, 1, 2, null), rows.map { it.plannedIndex })
    }

    /** [F] */
    @Test fun `tick with a seed reopens and replans before applying the edit`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId, slot)
        assertTrue(db.runDao().tracks(runId).single { it.exerciseId == "bb_back_squat" }.retired)
        val changed = Prescription(PrescribedTarget.LoadReps(55.0, 8))
        repo.setPrescription(slot, changed, sets = 4)
        repo.swapExerciseById("A", slot, "bb_back_squat")
        val result = repo.applyToRun(runId, plan, "A", listOf(TrackEdit(slot, Slot.MAIN, "bb_back_squat") { rows ->
            rows.mapIndexed { i, row -> if (i == 1) row.copy(done = true) else row }
        }), seeds = listOf(RunSeed(slot, Slot.MAIN, SetSeeder.seedPrescribed(4, changed))))
        assertEquals(RunWriteResult.InRun(runId, false), result)
        val reopened = db.runDao().tracks(runId).single()
        assertFalse(reopened.retired)
        assertEquals(original.id, reopened.id)
        assertEquals(original.plannedJson, reopened.plannedJson)
        val rows = SetJson.decodeSets(reopened.setsJson)
        assertEquals(SetJson.decodeSets(original.setsJson).first(), rows.first())
        assertEquals(listOf(55.0, 55.0, 55.0), rows.drop(1).map { it.weightLb })
        assertEquals(listOf(8, 8, 8), rows.drop(1).map { it.reps })
        assertEquals(listOf(true, true, false, false), rows.map { it.done })
        assertEquals(listOf(0, 1, 2, null), rows.map { it.plannedIndex })
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
        repo.setSupersetPartner("A", slot, "ez_curl")
        val runId = start(plan)
        seed(runId, slot, work(), Slot.SS)
        repo.updateSets(runId, "A", slot, Slot.SS, work().mapIndexed { i, row -> row.copy(done = i == 0) })
        val before = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        repo.setSupersetPartner("A", slot, "rope_pushdown")
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
        repo.setSupersetPartner("A", slot, "ez_curl")
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

    private suspend fun explicitStart(plan: Long, rows: List<LoggedSet> = work()): String {
        val id = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(RunStartResult.Started(id), repo.startRun(plan, "A", listOf(RunSeed(slot(plan), Slot.MAIN, rows))))
        return id
    }

    private suspend fun substituteSeed(plan: Long, exerciseId: String): RunSeed {
        val pe = repo.slotsFor(plan, "A").first().first().exercise.copy(exerciseId = exerciseId, prescription = null)
        val config = repo.wizardAnswersFor(plan).first().config
        val target = GoalCalculator.targetFor(repo.catalogFlow.first().get(exerciseId), config)
        return RunSeed(slot(plan), Slot.MAIN, SetSeeder.seed(pe, target, config))
    }

    @Test fun `workout swap before any set retires the original and swap back restores its prescription rows`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.seedIfEmpty("A", id, Slot.MAIN, work())
        val runId = explicitStart(plan)
        val original = db.runDao().tracks(runId).single()
        val seed = substituteSeed(plan, "hack_squat")
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", seed))
        val tracks = repo.openRunFlow.first()!!.tracks
        assertTrue(tracks.single { it.exerciseId == "bb_back_squat" }.retired)
        assertEquals(seed.sets, tracks.single { !it.retired }.planned)
        assertEquals("bb_back_squat", db.programDao().exercise(id)!!.exerciseId)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat", null))
        assertEquals(original, db.runDao().tracks(runId).single { !it.retired })
        assertEquals(work(), SetJson.decodeSets(db.programDao().logForSlot(plan, "A", id, Slot.MAIN)!!.setsJson))
    }

    @Test fun `workout swap refuses an unknown substitute without a seed without retiring anything`() = runTest {
        val plan = authored()
        val runId = explicitStart(plan)
        val before = db.runDao().tracks(runId)
        assertFalse(repo.swapInRun(runId, slot(plan), Slot.MAIN, "hack_squat", null))
        assertEquals(before, db.runDao().tracks(runId))
        assertFalse(repo.swapInRun("closed", slot(plan), Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        assertEquals(before, db.runDao().tracks(runId))
    }

    @Test fun `workout swap archives each performed exercise and leaves the slots remembered values alone`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.seedIfEmpty("A", id, Slot.MAIN, work())
        val before = db.programDao().logsForDay(plan, "A")
        val runId = start(plan, sets = work())
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        val active = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        repo.updateSets(runId, "A", id, Slot.MAIN, active.sets.mapIndexed { index, set -> set.copy(done = index == 0) })
        val session = repo.finishRun(runId)!!
        val done = repo.sessionSets(session).filter { it.done }
        assertEquals(setOf("bb_back_squat", "hack_squat"), done.map { it.exerciseId }.toSet())
        assertEquals(1, repo.sessionSets(session).count { it.exerciseId == "bb_back_squat" })
        assertEquals(before, db.programDao().logsForDay(plan, "A"))
    }

    @Test fun `workout MAIN swap preserves partner rows and reopening a longer MAIN pads the partner`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val partner = SetSeeder.seedPartner(3, 45.0)
        repo.seedIfEmpty("A", id, Slot.SS, partner)
        val longMain = work() + work().take(2)
        val runId = explicitStart(plan, longMain)
        repo.updateSets(runId, "A", id, Slot.SS, partner.mapIndexed { index, set -> set.copy(done = index == 2) })
        val before = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }.sets
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", RunSeed(id, Slot.MAIN, work())))
        assertEquals(before, repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }.sets)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat", null))
        val padded = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }.sets
        assertEquals(5, padded.size)
        assertEquals(before, padded.take(3))
        assertTrue(padded[2].done)
        val config = repo.wizardAnswersFor(plan).first().config
        val goal = GoalCalculator.targetFor(repo.catalogFlow.first().get("ez_curl"), config)
        assertEquals(SetSeeder.seedPartner(5, goal).takeLast(2), padded.takeLast(2))
    }

    @Test fun `workout SS swap keeps MAIN values and uses the new partners goal at the live row count`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan, work() + work().take(1))
        seed(runId, id, SetSeeder.seedPartner(4, 45.0), Slot.SS)
        val main = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN }
        val config = repo.wizardAnswersFor(plan).first().config
        val goal = GoalCalculator.targetFor(repo.catalogFlow.first().get("rope_pushdown"), config)
        val rows = SetSeeder.seedPartner(4, goal)
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "rope_pushdown", RunSeed(id, Slot.SS, rows)))
        val tracks = repo.openRunFlow.first()!!.tracks
        assertEquals(main, tracks.single { it.slot == Slot.MAIN })
        assertEquals(rows, tracks.single { it.slot == Slot.SS && !it.retired }.planned)
        assertEquals("ez_curl", db.programDao().exercise(id)!!.supersetExerciseId)
    }

    @Test fun `prescription edit on a substituted slot leaves the substitute unchanged and clears working values`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.seedIfEmpty("A", id, Slot.MAIN, work())
        val runId = explicitStart(plan)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        val before = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(80.0, 8)), sets = 4)
        assertEquals(before, repo.openRunFlow.first()!!.tracks.single { !it.retired })
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `plan swap on a substituted slot retires the substitutes done work and seeds the new plan exercise`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val runId = explicitStart(plan)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        val rows = repo.openRunFlow.first()!!.tracks.single { !it.retired }.sets
        repo.updateSets(runId, "A", id, Slot.MAIN, rows.mapIndexed { i, row -> row.copy(done = i == 0) })
        val substitute = db.runDao().tracks(runId).single { !it.retired }
        repo.swapExerciseById("A", id, "leg_press")
        seed(runId, id)
        assertEquals(substitute.copy(retired = true), db.runDao().tracks(runId).single { it.exerciseId == "hack_squat" })
        assertEquals("leg_press", repo.openRunFlow.first()!!.tracks.single { !it.retired }.exerciseId)
    }

    @Test fun `carry forward shape check finds a retired original MAIN and clears both working tracks`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setKeepToPlan(id, false)
        repo.setSupersetPartner("A", id, "ez_curl")
        repo.seedIfEmpty("A", id, Slot.MAIN, work())
        repo.seedIfEmpty("A", id, Slot.SS, work())
        val runId = explicitStart(plan)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(45)))
        // New working values can arrive while the original track remains retired.
        repo.seedIfEmpty("A", id, Slot.MAIN, work())
        repo.seedIfEmpty("A", id, Slot.SS, work())
        repo.finishRun(runId)
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `same exercise plan swap during a workout reopens the original without clearing working values`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.seedIfEmpty("A", id, Slot.MAIN, work())
        val logs = db.programDao().logsForDay(plan, "A")
        val runId = explicitStart(plan)
        val original = repo.openRunFlow.first()!!.tracks.single()
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        assertTrue(repo.swapExerciseById("A", id, "bb_back_squat"))
        assertEquals(original, repo.openRunFlow.first()!!.tracks.single { !it.retired })
        assertEquals(logs, db.programDao().logsForDay(plan, "A"))
    }

    @Test fun `reopening a shorter prescribed MAIN pads from its captured prescription instead of the swap seed`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan, work())
        seed(runId, id, SetSeeder.seedPartner(3, 45.0), Slot.SS)
        val original = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN }
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        repo.updateSets(runId, "A", id, Slot.SS, SetSeeder.seedPartner(5, 45.0))
        val partner = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }
        val goalSeed = RunSeed(id, Slot.MAIN, squat())
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat", goalSeed))
        val open = repo.openRunFlow.first()!!
        val main = open.tracks.single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(original.sets, main.sets.take(3))
        assertEquals(SetSeeder.seedPrescribed(2, load), main.sets.drop(3))
        assertEquals(partner, open.tracks.single { it.slot == Slot.SS })
    }

    @Test fun `prescription edit with a substituted SS preserves its rows and keeps its done tail reachable`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId, id, SetSeeder.seedPartner(3, 45.0), Slot.SS)
        val substitute = SetSeeder.seedPartner(5, 35.0)
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "rope_pushdown", RunSeed(id, Slot.SS, substitute)))
        repo.updateSets(runId, "A", id, Slot.SS, substitute.mapIndexed { index, row -> row.copy(done = index == 4) })
        val mainRows = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN && !it.retired }.sets
        repo.updateSets(runId, "A", id, Slot.MAIN, mainRows.mapIndexed { index, row -> row.copy(done = index == 3) })
        val before = db.runDao().tracks(runId)
        val partner = before.single { it.slot == Slot.SS && !it.retired }
        val prescription = Prescription(PrescribedTarget.LoadReps(80.0, 6))
        repo.setPrescription(id, prescription, 2)
        val after = db.runDao().tracks(runId)
        assertEquals(partner, after.single { it.id == partner.id })
        assertEquals(before.filter { it.retired }, after.filter { it.retired })
        val main = SetJson.decodeSets(after.single { it.slot == Slot.MAIN && !it.retired }.setsJson)
        val originalMain = SetJson.decodeSets(before.single { it.slot == Slot.MAIN && !it.retired }.setsJson)
        val expected = SetSeeder.seedPrescribed(5, prescription).mapIndexed { index, row ->
            if (originalMain[index].done) originalMain[index] else row.copy(plannedIndex = originalMain[index].plannedIndex)
        }
        assertEquals(expected, main)
        assertTrue(SetJson.decodeSets(partner.setsJson).last().done)
        assertEquals("ez_curl", db.programDao().exercise(id)!!.supersetExerciseId)
    }
    @Test fun `drop before partner work retains the row and reports the run fact`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val result = repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl")
        assertTrue(result is RunWriteResult.InRun && result.started)
        val partner = db.runDao().tracks((result as RunWriteResult.InRun).runId).single { it.slot == Slot.SS }
        assertTrue(partner.retired && partner.dropped)
        assertTrue(SetJson.decodeSets(partner.setsJson).none { it.done })
        val day = repo.dayTracksFlow(plan, "A").first()
        assertEquals(setOf(id), day.droppedPartners)
        assertTrue(day.retired.isEmpty())
        assertEquals("ez_curl", db.programDao().exercise(id)!!.supersetExerciseId)
    }

    @Test fun `seeding after a drop cannot reopen or insert a partner`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val result = repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        val before = db.runDao().tracks(result.runId)
        seed(result.runId, id, work(), Slot.SS)
        assertEquals(before, db.runDao().tracks(result.runId))
    }

    @Test fun `dropped done partner archives without carrying its values forward`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        repo.seedIfEmpty("A", id, Slot.SS, work())
        val working = db.programDao().logForSlot(plan, "A", id, Slot.SS)
        val runId = explicitStart(plan)
        seed(runId, id, work(), Slot.SS)
        repo.updateSets(runId, "A", id, Slot.SS, work().mapIndexed { i, set -> set.copy(weightLb = 175.0, done = i == 0) })
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        assertEquals(0, repo.dayTracksFlow(plan, "A").first().retiredRoundsDone)
        val session = repo.finishRun(runId)!!
        val archived = repo.sessionSets(session).filter { it.slot == Slot.SS }
        assertEquals(1, archived.size)
        assertTrue(archived.single().done)
        assertEquals(175.0, archived.single().weightLb, 0.0)
        assertEquals(working, db.programDao().logForSlot(plan, "A", id, Slot.SS))
        assertTrue(repo.dayTracksFlow(plan, "A").first().droppedPartners.isEmpty())
    }

    @Test fun `keeping partner swaps MAIN and undo restores both original tracks`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId, id, work(), Slot.SS)
        repo.updateSets(runId, "A", id, Slot.SS, work().mapIndexed { i, set -> set.copy(done = i == 0) })
        val original = repo.openRunFlow.first()!!.tracks
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.SS, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val dropped = repo.openRunFlow.first()!!.tracks
        assertEquals("ez_curl", dropped.single { !it.retired && it.slot == Slot.MAIN }.exerciseId)
        assertTrue(dropped.single { it.slot == Slot.SS }.dropped)
        assertTrue(dropped.single { it.slot == Slot.MAIN && it.exerciseId == "bb_back_squat" }.let { it.retired && it.dropped })
        assertTrue(repo.restoreSkipped(runId, id, Slot.MAIN) is RunWriteResult.InRun)
        val restored = repo.openRunFlow.first()!!.tracks.filter { !it.retired }
        assertEquals(original, restored)
        assertTrue(repo.dayTracksFlow(plan, "A").first().droppedPartners.isEmpty())
    }

    @Test fun `drop mismatches write nothing and intervening MAIN swap rejects undo`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        assertEquals(RunWriteResult.Rejected, repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "hack_squat", "ez_curl"))
        assertNull(repo.openRunFlow.first())
        assertEquals(RunWriteResult.Rejected, repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "rope_pushdown"))
        assertNull(repo.openRunFlow.first())
        val result = repo.dropSupersetHalf(plan, "A", id, Slot.SS, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        assertTrue(repo.swapInRun(result.runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        val before = db.runDao().tracks(result.runId)
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(result.runId, id, Slot.MAIN))
        assertEquals(before, db.runDao().tracks(result.runId))
    }

    @Test fun `drop undo rejects a missing partner row`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val result = repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        db.runDao().deleteTrack(db.runDao().tracks(result.runId).single { it.slot == Slot.SS }.id)
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(result.runId, id, Slot.SS))
    }

    @Test fun `drop undo rejects a removed plan partner without writing tracks`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val drop = repo.dropSupersetHalf(plan, "A", id, Slot.SS, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        repo.removeSupersetPartner("A", id)
        val before = db.runDao().tracks(drop.runId)
        assertTrue(before.isEmpty())
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(drop.runId, id, Slot.MAIN))
        assertEquals(before, db.runDao().tracks(drop.runId))
    }

    @Test fun `drop undo rejects a different plan partner without writing tracks`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val drop = repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        repo.setSupersetPartner("A", id, "rope_pushdown")
        val before = db.runDao().tracks(drop.runId)
        assertTrue(before.none { it.dropped })
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(drop.runId, id, Slot.SS))
        assertEquals(before, db.runDao().tracks(drop.runId))
    }

    @Test fun `drop undo pads SS after adding a MAIN row and preserves existing work`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId, id, SetSeeder.seedPartner(3, 45.0), Slot.SS)
        val mainRows = work().mapIndexed { index, row ->
            row.copy(weightLb = 60.0 + index * 5, reps = 8 + index, done = index != 1,
                plannedIndex = index, watchEditedAt = 100L + index)
        }
        val partnerRows = SetSeeder.seedPartner(3, 45.0).mapIndexed { index, row ->
            row.copy(weightLb = 40.0 + index * 5, done = index == 0, plannedIndex = index)
        }
        repo.updateSetsPaired(runId, "A", id, mainRows, partnerRows)
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val extended = mainRows + work().first().copy(weightLb = 75.0, reps = 7)
        assertTrue(repo.updateSets(runId, "A", id, Slot.MAIN, extended) is RunWriteResult.InRun)
        assertTrue(repo.restoreSkipped(runId, id, Slot.SS) is RunWriteResult.InRun)
        val restored = repo.openRunFlow.first()!!.tracks
        assertEquals(extended, restored.single { it.slot == Slot.MAIN && !it.retired }.sets)
        val partner = restored.single { it.slot == Slot.SS }
        assertFalse(partner.retired || partner.dropped)
        assertEquals(4, partner.sets.size)
        assertEquals(partnerRows, partner.sets.take(3))
        assertEquals(SetSeeder.seedPartner(1, 45.0).single(), partner.sets.last())
    }

    @Test fun `drop undo pads a shortened MAIN to the preserved SS row count`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId, id, SetSeeder.seedPartner(3, 45.0), Slot.SS)
        val mainRows = work().mapIndexed { index, row ->
            row.copy(weightLb = 60.0 + index * 5, reps = 8 + index, done = index == 0, plannedIndex = index)
        }
        val partnerRows = SetSeeder.seedPartner(3, 45.0).mapIndexed { index, row ->
            row.copy(done = index == 2, plannedIndex = index)
        }
        repo.updateSetsPaired(runId, "A", id, mainRows, partnerRows)
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val shortened = mainRows.take(2)
        assertTrue(repo.updateSets(runId, "A", id, Slot.MAIN, shortened) is RunWriteResult.InRun)
        assertTrue(repo.restoreSkipped(runId, id, Slot.SS) is RunWriteResult.InRun)
        val restored = repo.openRunFlow.first()!!.tracks
        val main = restored.single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(3, main.sets.size)
        assertEquals(shortened, main.sets.take(2))
        assertEquals(work().last(), main.sets.last())
        val partner = restored.single { it.slot == Slot.SS }
        assertFalse(partner.retired || partner.dropped)
        assertEquals(partnerRows, partner.sets)
    }

    @Test fun `stale paired write cannot edit MAIN or reopen dropped SS`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val drop = repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        val before = db.runDao().tracks(drop.runId)
        assertEquals(RunWriteResult.Rejected, repo.updateSetsPaired(drop.runId, "A", id, work().take(1), work().take(1)))
        assertEquals(before, db.runDao().tracks(drop.runId))
        assertTrue(repo.restoreSkipped(drop.runId, id, Slot.SS) is RunWriteResult.InRun)
        assertEquals(before.single { it.slot == Slot.SS }.copy(retired = false, dropped = false),
            db.runDao().tracks(drop.runId).single { it.slot == Slot.SS })
    }

    @Test fun `restore uses the dropped MAIN identity after earlier workout swaps`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId, id, work(), Slot.SS)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", substituteSeed(plan, "hack_squat")))
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.SS, "hack_squat", "ez_curl") is RunWriteResult.InRun)
        assertTrue(repo.restoreSkipped(runId, id, Slot.MAIN) is RunWriteResult.InRun)
        assertEquals("hack_squat", repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN && !it.retired }.exerciseId)
        assertFalse(repo.openRunFlow.first()!!.isPartnerDropped(id))
    }

    @Test fun `keeping SS with the same exercise as MAIN undoes without a swap`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "bb_back_squat")
        val drop = repo.dropSupersetHalf(plan, "A", id, Slot.SS, "bb_back_squat", "bb_back_squat") as RunWriteResult.InRun
        assertTrue(repo.restoreSkipped(drop.runId, id, Slot.MAIN) is RunWriteResult.InRun)
        assertEquals(setOf(Slot.MAIN, Slot.SS), repo.dayTracksFlow(plan, "A").first().tracks.map { it.slot }.toSet())
    }


    @Test fun `skip empty plain lift retains MAIN blocks reseeding and edits and restores its rows`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        val runId = explicitStart(plan)
        val rows = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && it.slot == Slot.MAIN }.sets
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null) is RunWriteResult.InRun)
        val before = db.runDao().tracks(runId)
        assertTrue(before.single { it.programExerciseId == id && it.slot == Slot.MAIN }.let { it.retired && it.dropped })
        assertEquals(setOf(id), repo.dayTracksFlow(plan, "A").first().skippedLifts)
        assertTrue(repo.dayTracksFlow(plan, "A").first().retired.isEmpty())
        seed(runId, id, work())
        assertEquals(RunWriteResult.Rejected, repo.updateSets(runId, "A", id, Slot.MAIN, work().take(1)))
        assertEquals(before, db.runDao().tracks(runId))
        assertTrue(repo.restoreSkippedLift(runId, id) is RunWriteResult.InRun)
        repo.seedRunTracksIfMissing(runId, listOf(
            RunSeed(id, Slot.MAIN, work()),
        ), alignPairs = true)
        assertTrue(repo.dayTracksFlow(plan, "A").first().skippedLifts.isEmpty())
        assertEquals(rows, repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && !it.retired }.sets)
    }

    @Test fun `skip SS then remaining plain lift clears skips and reseeds the planned pair`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val drop = repo.skipLift(plan, "A", id, Slot.SS, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null) is RunWriteResult.InRun)
        assertEquals(setOf(id), repo.dayTracksFlow(plan, "A").first().skippedLifts)
        assertTrue(repo.restoreSkippedLift(drop.runId, id) is RunWriteResult.InRun)
        repo.seedRunTracksIfMissing(drop.runId, listOf(
            RunSeed(id, Slot.MAIN, work()), RunSeed(id, Slot.SS, work()),
        ), alignPairs = true)
        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertFalse(tracks.single { it.slot == Slot.MAIN }.retired)
        assertFalse(tracks.single { it.slot == Slot.SS }.retired)
        assertTrue(tracks.none { it.dropped })
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(drop.runId, id, Slot.SS))
    }

    @Test fun `skip MAIN then promoted MAIN and clear checks reseeds the aligned plan with tied timestamps`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan, work() + work().take(1))
        seed(runId, id, work(), Slot.SS)
        val drop = repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "ez_curl", null) is RunWriteResult.InRun)
        assertTrue(repo.clearRunChecks(runId))
        assertEquals(setOf(clock.millis()), db.runDao().tracks(runId).map { it.updatedAt }.toSet())
        assertTrue(repo.restoreSkippedLift(drop.runId, id) is RunWriteResult.InRun)
        repo.seedRunTracksIfMissing(drop.runId, listOf(
            RunSeed(id, Slot.MAIN, work()), RunSeed(id, Slot.SS, work()),
        ), alignPairs = true)
        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertEquals(2, tracks.size)
        assertTrue(tracks.none { it.retired || it.dropped })
        assertEquals("bb_back_squat", tracks.single { it.slot == Slot.MAIN }.exerciseId)
        assertEquals("ez_curl", tracks.single { it.slot == Slot.SS }.exerciseId)
        assertEquals(listOf(3, 3), tracks.map { it.sets.size })
        assertTrue(tracks.flatMap { it.sets }.none { it.done })
        assertEquals(setOf(Slot.MAIN, Slot.SS), repo.dayTracksFlow(plan, "A").first().tracks.filter { it.programExerciseId == id }.map { it.slot }.toSet())
    }

    @Test fun `whole slot restore keeps done promoted MAIN work retired and clears its skip`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val drop = repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        val promoted = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && !it.retired }
        val doneRows = promoted.sets.mapIndexed { index, set -> set.copy(done = index == 0) }
        assertTrue(repo.updateSets(drop.runId, "A", id, Slot.MAIN, doneRows) is RunWriteResult.InRun)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "ez_curl", null) is RunWriteResult.InRun)
        assertTrue(repo.restoreSkippedLift(drop.runId, id) is RunWriteResult.InRun)
        repo.seedRunTracksIfMissing(drop.runId, listOf(
            RunSeed(id, Slot.MAIN, work()), RunSeed(id, Slot.SS, work()),
        ), alignPairs = true)
        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertEquals("bb_back_squat", tracks.single { it.slot == Slot.MAIN && !it.retired }.exerciseId)
        assertEquals(doneRows, tracks.single { it.id == promoted.id }.sets)
        assertTrue(tracks.single { it.id == promoted.id }.retired)
        assertTrue(tracks.none { it.dropped })
    }

    @Test fun `last active plain lift skip is rejected before starting and after another lift was skipped`() = runTest {
        val plan = authored()
        val id = slot(plan)
        assertEquals(RunWriteResult.Rejected, repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null))
        assertNull(repo.openRunFlow.first())
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val other = db.programDao().exercisesForDay(plan, "A").last().id
        val skipped = repo.skipLift(plan, "A", other, Slot.MAIN, "bb_bench", null) as RunWriteResult.InRun
        val before = db.runDao().tracks(skipped.runId)
        assertEquals(RunWriteResult.Rejected, repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null))
        assertEquals(before, db.runDao().tracks(skipped.runId))
    }

    @Test fun `plan MAIN swap clears skips reseeds the new pair and rejects stale Undo`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val skip = repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        assertTrue(repo.swapExerciseById("A", id, "hack_squat"))
        seed(skip.runId, id)
        seed(skip.runId, id, work(), Slot.SS)
        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertTrue(tracks.none { it.dropped })
        assertEquals("hack_squat", tracks.single { it.slot == Slot.MAIN && !it.retired }.exerciseId)
        assertEquals("ez_curl", tracks.single { it.slot == Slot.SS && !it.retired }.exerciseId)
        assertTrue(tracks.filterNot { it.retired }.all { it.sets.isNotEmpty() })
        assertTrue(repo.dayTracksFlow(plan, "A").first().skippedLifts.isEmpty())
        val before = db.runDao().tracks(skip.runId)
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(skip.runId, id, Slot.MAIN))
        assertEquals(before, db.runDao().tracks(skip.runId))
    }

    @Test fun `plan partner change clears skipped tracks preserves done work and retires promotion`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId, id, work(), Slot.SS)
        val done = work().mapIndexed { index, set -> set.copy(done = index == 0) }
        repo.updateSetsPaired(runId, "A", id, done, done)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val promoted = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        repo.updateSets(runId, "A", id, Slot.MAIN, done)
        repo.setSupersetPartner("A", id, "rope_pushdown")
        val retired = repo.openRunFlow.first()!!.tracks
        assertEquals(3, retired.size)
        assertTrue(retired.all { it.retired && !it.dropped })
        assertTrue(retired.all { it.sets == done })
        assertTrue(retired.any { it.id == promoted.id })
        seed(runId, id)
        seed(runId, id, work(), Slot.SS)
        val active = repo.openRunFlow.first()!!.tracks.filterNot { it.retired }
        assertEquals("bb_back_squat", active.single { it.slot == Slot.MAIN }.exerciseId)
        assertEquals("rope_pushdown", active.single { it.slot == Slot.SS }.exerciseId)
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(runId, id, Slot.MAIN))
    }

    @Test fun `plan exercise removal clears its skip and promotion while preserving done work`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.addExercise("A", ProgramExercise("bb_bench"))
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId, id, work(), Slot.SS)
        val done = work().mapIndexed { index, set -> set.copy(done = index == 0) }
        repo.updateSetsPaired(runId, "A", id, done, done)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        assertTrue(repo.removeExercise(id))
        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.retired && !it.dropped && it.sets == done })
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(runId, id, Slot.MAIN))
    }
}
