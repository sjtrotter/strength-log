package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.StandardLift
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.seeding.RunReplan
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.seeding.SetEdit
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
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
    private lateinit var settings: SettingsStore
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
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(),
            settings, clock, { ids.removeFirst() })
    }

    /** Releases the database and settings scope. */
    @After fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun work() = SetSeeder.seedPrescribed(3, load)
    private fun squat() = SetSeeder.seed(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF), 235.0, LifterConfig())
    private suspend fun generated(extraSlot: Boolean = false): Long {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Day A", "", listOf(
                ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF),
            ) + if (extraSlot) listOf(ProgramExercise("bb_bench"), ProgramExercise("missing_exercise")) else emptyList()),
            ProgramDay("B", "Day B", "", listOf(ProgramExercise("bb_bench"))),
        )))
        return repo.plans.selectedPlanIdFlow.first()!!
    }
    private suspend fun authored(): Long {
        val plan = repo.plans.createAuthoredPlan("Authored", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("bb_back_squat"))
        repo.setPrescription(slot(plan), load, 3)
        return plan
    }
    private suspend fun slot(plan: Long) = db.programDao().exercisesForDay(plan, "A").first().id
    private suspend fun start(plan: Long, doneCount: Int = 1, sets: List<LoggedSet> = squat()): String {
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        db.storeTestRows("A", slot(plan), Slot.MAIN, sets)
        assertEquals(RunWriteResult.InRun(runId, true), repo.applyToRun(runId, plan, "A", listOf(
            TrackEdit(slot(plan), Slot.MAIN) { rows -> rows.mapIndexed { i, row -> row.copy(done = i < doneCount) } },
        )))
        return runId
    }
    private suspend fun seed(runId: String) {
        val run = checkNotNull(db.runDao().openRun()).also { check(it.id == runId) }
        repo.ensureSeeded(requireNotNull(run.planId), requireNotNull(run.dayId))
    }

    private suspend fun loggedWeight(
        planId: Long,
        exerciseId: String,
        weight: Double,
        completedAt: Long,
        kind: SetKind = SetKind.WORK,
        done: Boolean = true,
    ) {
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(
            id = 0, dayId = "B", dayTitle = "Day B", startedAt = null,
            completedAt = completedAt, bodyweightLb = 235.0, planId = planId,
        ))
        db.sessionDao().insertSets(listOf(SessionSetEntity(
            id = 0, sessionId = session, exerciseId = exerciseId, exerciseName = exerciseId,
            slot = Slot.MAIN, setIndex = 0, kind = kind.name, weightLb = weight, reps = 8, done = done,
        )))
    }

    @Test fun `plan swap into Day B uses Day A squat prescription with only ramp history`() = runTest {
        val plan = generated()
        repo.setPrescription(slot(plan), Prescription(PrescribedTarget.LoadReps(45.0, 5)))
        loggedWeight(plan, "bb_back_squat", 50.0, 1_000, kind = SetKind.RAMP)
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        repo.setStructure(destination, SetStructure.RAMP_TOP_BACKOFF)

        assertTrue(repo.swapExerciseById("B", destination, "bb_back_squat"))
        repo.ensureSeeded(plan, "B")

        val rows = SetJson.decodeSets(db.programDao().logForSlot(plan, "B", destination, Slot.MAIN)!!.setsJson)
        assertEquals(List(3) { LoggedSet(45.0, 5, SetKind.WORK) }, rows)
        assertTrue(rows.none { it.done })
    }

    @Test fun `plan swap into a prescribed destination uses same plan working weight without changing goals`() = runTest {
        val plan = generated()
        val source = slot(plan)
        repo.setPrescription(source, Prescription(PrescribedTarget.LoadReps(65.0, 5)))
        db.storeTestRows("A", source, Slot.MAIN, squat().map { it.copy(weightLb = 45.0) })
        loggedWeight(plan, "bb_back_squat", 100.0, 1_000)
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        repo.setPrescription(destination, Prescription(PrescribedTarget.LoadReps(95.0, 8)), sets = 2)
        repo.setStructure(destination, SetStructure.RAMP_TOP_BACKOFF)
        val before = db.programDao().exercise(destination)!!

        assertTrue(repo.swapExerciseById("B", destination, "bb_back_squat"))
        repo.ensureSeeded(plan, "B")

        val rows = SetJson.decodeSets(db.programDao().logForSlot(plan, "B", destination, Slot.MAIN)!!.setsJson)
        assertEquals(List(3) { LoggedSet(45.0, 5, SetKind.WORK) }, rows)
        assertTrue(rows.none { it.done })
        assertEquals(before.copy(exerciseId = "bb_back_squat", structure = SetStructure.STRAIGHT.name,
            targetSets = 3, prescriptionJson = null, ruleJson = SlotRuleJson.encode(
                SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, 0.05, Scheme.STRENGTH))),
            db.programDao().exercise(destination))
    }

    @Test fun `a plan swap writes the straight shape at the nearby weight`() = runTest {
        val plan = generated()
        repo.setPrescription(slot(plan), Prescription(PrescribedTarget.LoadReps(45.0, 5)))
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        repo.setPrescription(destination, Prescription(PrescribedTarget.LoadReps(95.0, 5)), sets = 6)
        repo.setStructure(destination, SetStructure.RAMP_TOP_BACKOFF)

        assertTrue(repo.swapExerciseById("B", destination, "bb_back_squat"))
        repo.ensureSeeded(plan, "B")

        val exercise = db.programDao().exercise(destination)!!
        assertEquals(SetStructure.STRAIGHT.name, exercise.structure)
        assertEquals(3, exercise.targetSets)
        assertNull(exercise.prescriptionJson)
        val rows = SetJson.decodeSets(db.programDao().logForSlot(plan, "B", destination, Slot.MAIN)!!.setsJson)
        assertEquals(List(3) { LoggedSet(45.0, 5, SetKind.WORK) }, rows)
    }

    @Test fun `a workout-only swap seeds straight sets`() = runTest {
        val plan = generated()
        repo.setPrescription(slot(plan), Prescription(PrescribedTarget.LoadReps(45.0, 5)))
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        repo.setPrescription(destination, Prescription(PrescribedTarget.LoadReps(95.0, 5)), sets = 6)
        repo.setStructure(destination, SetStructure.RAMP_TOP_BACKOFF)
        val before = db.programDao().exercise(destination)!!
        val run = repo.startRun(plan, "B") as RunStartResult.Started

        assertTrue(repo.swapInRun(run.runId, destination, Slot.MAIN, "bb_back_squat"))

        val track = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        assertEquals(List(3) { LoggedSet(45.0, 5, SetKind.WORK) }, track.planned)
        assertEquals(List(3) { LoggedSet(45.0, 5, SetKind.WORK, plannedIndex = it) }, track.sets)
        assertEquals(before, db.programDao().exercise(destination))
    }

    @Test fun `a plan swap aligns replacement rows with completed partner tail rows`() = runTest {
        val plan = generated()
        repo.setPrescription(slot(plan), Prescription(PrescribedTarget.LoadReps(45.0, 5)))
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        repo.setStructure(destination, SetStructure.RAMP_TOP_BACKOFF)
        repo.setSupersetPartner("B", destination, "ez_curl")
        val run = repo.startRun(plan, "B") as RunStartResult.Started
        repo.applyToRun(run.runId, plan, "B", listOf(TrackEdit(destination, Slot.SS) { rows ->
            rows.mapIndexed { index, row -> row.copy(
                done = index >= 3, watchEditedAt = 100L + index,
                startedAtMillis = 200L + index, completedAtMillis = (300L + index).takeIf { index >= 3 },
            ) }
        }))
        val partner = db.runDao().tracks(run.runId).single { it.slot == Slot.SS }
        val partnerRows = SetJson.decodeSets(partner.setsJson)
        assertEquals(6, partnerRows.size)
        assertEquals(3, partnerRows.count { it.done })

        assertTrue(repo.swapExerciseById("B", destination, "bb_back_squat"))
        repo.ensureSeeded(plan, "B")

        val tracks = db.runDao().tracks(run.runId)
        val main = tracks.single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(List(3) { LoggedSet(45.0, 5, SetKind.WORK) }, SetJson.decodeSets(main.plannedJson))
        assertEquals(List(6) { LoggedSet(45.0, 5, SetKind.WORK, plannedIndex = it.takeIf { it < 3 }) },
            SetJson.decodeSets(main.setsJson))
        assertEquals(partner, tracks.single { it.slot == Slot.SS })
        assertEquals(3, db.programDao().exercise(destination)!!.targetSets)
        repo.ensureSeeded(plan, "B")
        assertEquals(tracks, db.runDao().tracks(run.runId))
        val finished = requireNotNull(repo.finishRunCapturing(run.runId))
        assertEquals(partnerRows, requireNotNull(finished.tracks).single { it.slot == Slot.SS }.sets)
    }

    @Test fun `a plan swap pads replacement rows at the history estimate`() = runTest {
        val plan = generated()
        loggedWeight(plan, "bb_back_squat", 45.0, 1_000)
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        repo.setStructure(destination, SetStructure.RAMP_TOP_BACKOFF)
        repo.setSupersetPartner("B", destination, "ez_curl")
        val run = repo.startRun(plan, "B") as RunStartResult.Started
        repo.applyToRun(run.runId, plan, "B", listOf(TrackEdit(destination, Slot.SS) { rows ->
            rows.mapIndexed { index, row -> row.copy(done = index == rows.lastIndex, watchEditedAt = 100L + index) }
        }))
        val partner = db.runDao().tracks(run.runId).single { it.slot == Slot.SS }
        assertEquals(6, SetJson.decodeSets(partner.setsJson).size)

        assertTrue(repo.swapExerciseById("B", destination, "bb_back_squat"))
        repo.ensureSeeded(plan, "B")

        val tracks = db.runDao().tracks(run.runId)
        val main = tracks.single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(List(3) { LoggedSet(50.0, 5, SetKind.WORK) }, SetJson.decodeSets(main.plannedJson))
        assertEquals(List(6) { LoggedSet(50.0, 5, SetKind.WORK, plannedIndex = it.takeIf { it < 3 }) },
            SetJson.decodeSets(main.setsJson))
        assertEquals(partner, tracks.single { it.slot == Slot.SS })
        repo.ensureSeeded(plan, "B")
        assertEquals(tracks, db.runDao().tracks(run.runId))
    }

    @Test fun `a workout swap pads all six replacement rows at the history estimate`() = runTest {
        val plan = generated()
        loggedWeight(plan, "bb_back_squat", 45.0, 1_000)
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        repo.setStructure(destination, SetStructure.RAMP_TOP_BACKOFF)
        repo.setSupersetPartner("B", destination, "ez_curl")
        val original = db.programDao().exercise(destination)
        val run = repo.startRun(plan, "B") as RunStartResult.Started
        repo.applyToRun(run.runId, plan, "B", listOf(TrackEdit(destination, Slot.SS) { rows ->
            rows.mapIndexed { index, row -> row.copy(done = index >= 3, watchEditedAt = 100L + index) }
        }))
        val partner = db.runDao().tracks(run.runId).single { it.slot == Slot.SS }
        assertEquals(6, SetJson.decodeSets(partner.setsJson).size)

        assertTrue(repo.swapInRun(run.runId, destination, Slot.MAIN, "bb_back_squat"))

        val tracks = db.runDao().tracks(run.runId)
        val main = tracks.single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(List(3) { LoggedSet(50.0, 5, SetKind.WORK) }, SetJson.decodeSets(main.plannedJson))
        assertEquals(List(6) { LoggedSet(50.0, 5, SetKind.WORK, plannedIndex = it.takeIf { it < 3 }) },
            SetJson.decodeSets(main.setsJson))
        assertEquals(partner, tracks.single { it.slot == Slot.SS })
        assertEquals(original, db.programDao().exercise(destination))
    }

    @Test fun `added exercise prefers same plan working rows over prescription and history`() = runTest {
        val plan = generated()
        val source = slot(plan)
        repo.setPrescription(source, Prescription(PrescribedTarget.LoadReps(65.0, 5)))
        val working = SetSeeder.seedPrescribed(3, Prescription(PrescribedTarget.LoadReps(45.0, 5)),
            SetStructure.RAMP_TOP_BACKOFF, equipment = listOf(Equipment.BARBELL))
        db.storeTestRows("A", source, Slot.MAIN, working)
        loggedWeight(plan, "bb_back_squat", 100.0, 1_000)

        repo.addExercise("B", ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF), plan)
        repo.ensureSeeded(plan, "B")

        val destination = db.programDao().exercisesForDay(plan, "B").last().id
        val rows = SetJson.decodeSets(db.programDao().logForSlot(plan, "B", destination, Slot.MAIN)!!.setsJson)
        assertEquals(45.0, rows.single { it.kind == SetKind.TOP }.weightLb, 0.0)
        assertEquals(working, SetJson.decodeSets(db.programDao().logForSlot(plan, "A", source, Slot.MAIN)!!.setsJson))
    }

    @Test fun `run swap prefers another slots prescription over history`() = runTest {
        val plan = generated()
        repo.setPrescription(slot(plan), Prescription(PrescribedTarget.LoadReps(45.0, 5)))
        loggedWeight(plan, "bb_back_squat", 100.0, 1_000)
        val destination = db.programDao().exercisesForDay(plan, "B").single().id
        val run = repo.startRun(plan, "B") as RunStartResult.Started

        assertTrue(repo.swapInRun(run.runId, destination, Slot.MAIN, "bb_back_squat"))

        val track = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        assertEquals(List(3) { 45.0 }, track.sets.map { it.weightLb })
    }

    @Test fun `plan swap uses latest completed working weight across plans and days`() = runTest {
        val historyPlan = generated()
        loggedWeight(historyPlan, "db_row", 60.0, 1_000)
        loggedWeight(historyPlan, "db_row", 10.0, 2_000)
        loggedWeight(historyPlan, "db_row", 300.0, 3_000, done = false)
        loggedWeight(historyPlan, "db_row", 200.0, 4_000, kind = SetKind.RAMP)
        loggedWeight(historyPlan, "cs_row", 150.0, 5_000)
        val plan = repo.plans.createAuthoredPlan("New plan", "Day A")
        repo.addExercise("A", ProgramExercise("ez_curl"), plan)
        val id = slot(plan)

        assertTrue(repo.swapExerciseById("A", id, "db_row"))
        repo.ensureSeeded(plan, "A")

        val rows = SetJson.decodeSets(db.programDao().logForSlot(plan, "A", id, Slot.MAIN)!!.setsJson)
        assertEquals(List(3) { 10.0 }, rows.map { it.weightLb })
        assertTrue(rows.none { it.done })
        assertNull(db.programDao().exercise(id)!!.prescriptionJson)
    }

    @Test fun `added exercises and partners seed from their own history and retain edits and prescriptions`() = runTest {
        val plan = generated()
        loggedWeight(plan, "db_row", 10.0, 1_000)
        loggedWeight(plan, "rope_pushdown", 15.0, 2_000)
        repo.addExercise("A", ProgramExercise("db_row"), plan)
        val added = db.programDao().exercisesForDay(plan, "A").last().id
        repo.setSupersetPartner("A", added, "rope_pushdown")
        repo.ensureSeeded(plan, "A")
        val rows = repo.dayTracksFlow(plan, "A").first().tracks.filter { it.programExerciseId == added }
        assertEquals(List(3) { 10.0 }, rows.single { it.slot == Slot.MAIN }.sets.map { it.weightLb })
        assertEquals(List(3) { 10.0 }, rows.single { it.slot == Slot.SS }.sets.map { it.weightLb })
        db.storeTestRows("A", added, Slot.MAIN, rows.single { it.slot == Slot.MAIN }.sets.map { it.copy(weightLb = 12.5) })
        repo.ensureSeeded(plan, "A")
        assertEquals(List(3) { 12.5 }, repo.dayTracksFlow(plan, "A").first().tracks
            .single { it.programExerciseId == added && it.slot == Slot.MAIN }.sets.map { it.weightLb })

        repo.addExercise("A", ProgramExercise("db_row", prescription = load), plan)
        val written = db.programDao().exercisesForDay(plan, "A").last().id
        repo.ensureSeeded(plan, "A")
        assertEquals(List(3) { 50.0 }, repo.dayTracksFlow(plan, "A").first().tracks
            .single { it.programExerciseId == written && it.slot == Slot.MAIN }.sets.map { it.weightLb })
    }

    @Test fun `run swap seeds remembered straight sets and restores retired work`() = runTest {
        val plan = generated()
        loggedWeight(plan, "db_row", 10.0, 1_000)
        val runId = start(plan)
        assertEquals(ExerciseSwapResult.CHANGED,
            repo.applyExerciseSwap(plan, "A", slot(plan), "db_row", runId, allowChange = true))
        val swapped = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        assertEquals(List(3) { LoggedSet(10.0, 10, SetKind.WORK, plannedIndex = it) }, swapped.sets)
        assertEquals(ExerciseSwapResult.CHANGED,
            repo.applyExerciseSwap(plan, "A", slot(plan), "bb_back_squat", runId, allowChange = true))
        assertEquals(235.0, repo.openRunFlow.first()!!.tracks.single { !it.retired }.sets[4].weightLb, 0.0)
    }

    @Test fun `loaded timed adds and swaps remember weight while preserving duration and row shape`() = runTest {
        val historyPlan = generated()
        repo.addExercise("A", ProgramExercise("weighted_plank"), historyPlan)
        val loaded = db.programDao().exercisesForDay(historyPlan, "A").last().id
        val runId = explicitStart(historyPlan, squat())
        repo.applyToRun(runId, historyPlan, "A", listOf(TrackEdit(loaded, Slot.MAIN) { rows ->
            rows.map { it.copy(weightLb = 10.0, seconds = 60, done = true) }
        }))
        repo.finishRun(runId)
        loggedWeight(historyPlan, "plank", 99.0, 1_000)

        val plan = repo.plans.createAuthoredPlan("New plan", "Day A")
        repo.addExercise("A", ProgramExercise("weighted_plank", targetSets = 4), plan)
        repo.addExercise("A", ProgramExercise("ez_curl", targetSets = 2), plan)
        val swapped = db.programDao().exercisesForDay(plan, "A").last().id
        assertTrue(repo.swapExerciseById("A", swapped, "weighted_plank"))
        repo.addExercise("A", ProgramExercise("plank"), plan)
        repo.ensureSeeded(plan, "A")

        val tracks = repo.dayTracksFlow(plan, "A").first().tracks
        val timed = tracks.filter { it.exerciseId == "weighted_plank" }
        assertEquals(listOf(4, 2), timed.map { it.sets.size })
        timed.forEach { track ->
            assertEquals(List(track.sets.size) { LoggedSet(10.0, 0, SetKind.WORK, seconds = 45) }, track.sets)
        }
        assertTrue(tracks.single { it.exerciseId == "plank" }.sets.all { it.weightLb == 0.0 && it.seconds == 45 })

        val active = repo.startRun(plan, "A") as RunStartResult.Started
        val id = slot(plan)
        assertTrue(repo.swapInRun(active.runId, id, Slot.MAIN, "hollow_hold"))
        assertTrue(repo.swapInRun(active.runId, id, Slot.MAIN, "weighted_plank"))
        val restored = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && !it.retired }
        assertEquals(List(4) { LoggedSet(10.0, 0, SetKind.WORK, seconds = 45) }, restored.planned)
    }

    @Test fun `watch edits before lazy seeding use remembered working rows`() = runTest {
        val plan = generated()
        loggedWeight(plan, "db_row", 10.0, 1_000)
        val runId = start(plan)
        for (legacy in listOf(false, true)) {
            repo.addExercise("A", ProgramExercise("db_row"), plan)
            val added = db.programDao().exercisesForDay(plan, "A").last().id
            val edits = listOf(TrackEdit(added, Slot.MAIN) { rows ->
                SetEditor.edit(rows, 0, SetEdit(done = true, watchEditedAt = 200L))
            })
            val result = if (legacy) repo.applyToOpenRun(plan, "A", clock.millis(), edits)
                else repo.applyToRun(runId, plan, "A", edits)
            assertEquals(RunWriteResult.InRun(runId, false), result)
            seed(runId)

            val track = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == added && !it.retired }
            assertEquals(List(3) { 10.0 }, track.planned.map { it.weightLb })
            assertEquals(List(3) { 10.0 }, track.sets.map { it.weightLb })
            assertEquals(listOf(true, false, false), track.sets.map { it.done })
            assertEquals(200L, track.sets.first().watchEditedAt)
        }
    }

    @Test fun `history seeding survives adding and plan swapping during a run with unknown history at the floor`() = runTest {
        val plan = generated()
        loggedWeight(plan, "db_row", 10.0, 1_000)
        val runId = start(plan)
        loggedWeight(plan, "rope_pushdown", 15.0, 2_000)
        repo.applyToRun(runId, plan, "A", listOf(TrackEdit(slot(plan), Slot.MAIN) { SetEditor.addSet(it) }))
        repo.setSupersetPartner("A", slot(plan), "rope_pushdown")
        repo.ensureSeeded(plan, "A")
        val partner = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS && !it.retired }
        assertEquals(List(7) { 10.0 }, partner.sets.map { it.weightLb })
        repo.removeSupersetPartner("A", slot(plan))
        repo.addExercise("A", ProgramExercise("db_row"), plan)
        val added = db.programDao().exercisesForDay(plan, "A").last().id
        repo.ensureSeeded(plan, "A")
        assertEquals(List(3) { 10.0 }, repo.openRunFlow.first()!!.tracks.single {
            it.programExerciseId == added && !it.retired
        }.sets.map { it.weightLb })
        assertTrue(repo.swapExerciseById("A", slot(plan), "db_row"))
        repo.ensureSeeded(plan, "A")
        assertEquals(List(3) { 10.0 }, repo.openRunFlow.first()!!.tracks.single {
            it.programExerciseId == slot(plan) && !it.retired
        }.sets.map { it.weightLb })
        assertEquals(ExerciseSwapResult.CHANGED,
            repo.applyExerciseSwap(plan, "A", slot(plan), "hack_squat", runId, allowChange = true))
        assertEquals(List(3) { 20.0 }, repo.openRunFlow.first()!!.tracks.single {
            it.programExerciseId == slot(plan) && !it.retired
        }.sets.map { it.weightLb })
    }

    @Test fun `kg preference reaches generated goals seeded rows and carry forward`() = runTest {
        settings.setUnit(WeightUnit.KG)
        val plan = generated()
        repo.ensureSeeded(plan, "A")
        val config = repo.plans.wizardAnswersFor(plan).first().config
        assertEquals(105.0, WeightUnit.KG.fromLb(GoalCalculator.goalForMain(
            StandardLift.SQUAT, false, config,
        )), 1e-9)
        val rows = repo.dayTracksFlow(plan, "A").first().tracks.single().sets
        assertEquals(20.0, WeightUnit.KG.fromLb(rows[4].weightLb), 1e-9)
        val runId = start(plan, sets = rows)
        repo.applyToRun(runId, plan, "A", listOf(TrackEdit(slot(plan), Slot.MAIN) { current ->
            current.mapIndexed { i, row -> row.copy(
                done = true, weightLb = if (i == 4) WeightUnit.KG.toLb(20.0) else row.weightLb,
            ) }
        }))
        repo.finishRun(runId)
        val next = SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot(plan), Slot.MAIN)!!.setsJson)
        assertEquals(rows, next)
    }

    /** [F] */
    @Test fun `swap retires done work and finish archives both captured prescriptions`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        db.storeTestRows("A", slot, Slot.MAIN, squat())
        val runId = start(plan, doneCount = 2)
        val before = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        assertEquals(before.copy(retired = true), db.runDao().tracks(runId).single())
        assertEquals("hack_squat", db.programDao().exercise(slot)!!.exerciseId)
        assertEquals(List(3) { LoggedSet(20.0, 10, SetKind.WORK) },
            SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson))
        val tracks = repo.dayTracksFlow(plan, "A").first()
        assertTrue(tracks.tracks.none { it.programExerciseId == slot && it.slot == Slot.MAIN })
        assertEquals(listOf("bb_back_squat"), tracks.retired.map { it.exerciseId })
        seed(runId)
        val active = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        assertEquals("hack_squat", active.exerciseId)
        val session = repo.finishRun(runId)!!
        val rows = repo.history.sessionSets(session)
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
        repo.writeTestRows(runId, "A", slot(plan), Slot.MAIN, squat())
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
        seed(runId)
        val swapped = db.runDao().tracks(runId)
        assertEquals(before.copy(retired = true), swapped.single { it.exerciseId == "bb_back_squat" })
        val alternate = swapped.single { it.exerciseId == "hack_squat" }
        assertFalse(alternate.retired)
        assertTrue(SetJson.decodeSets(alternate.setsJson).none { it.done })
        repo.swapExerciseById("A", slot, "bb_back_squat")
        seed(runId)
        val expected = RunReplan.represcribe(SetJson.decodeSets(before.setsJson), List(3) { LoggedSet(45.0, 5, SetKind.WORK) })
        assertEquals(listOf(before.copy(setsJson = SetJson.encodeSets(expected))), db.runDao().tracks(runId))
        assertTrue(repo.dayTracksFlow(plan, "A").first().retired.isEmpty())
    }

    /** [F] */
    @Test fun `swapping back retires a done alternate archives it and keeps the stored 45 lb rows`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val restoredRows = squat().mapIndexed { i, row ->
            if (i == 0) row.copy(weightLb = 155.0, reps = 7) else row
        }
        val runId = start(plan, sets = restoredRows)
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId)
        val swapped = db.runDao().tracks(runId)
        assertEquals(original.copy(retired = true), swapped.single { it.exerciseId == "bb_back_squat" })
        assertFalse(swapped.single { it.exerciseId == "hack_squat" }.retired)
        repo.writeTestRows(runId, "A", slot, Slot.MAIN, work().mapIndexed { i, row -> row.copy(done = i < 2) })
        val alternate = db.runDao().tracks(runId).single { it.exerciseId == "hack_squat" }
        repo.swapExerciseById("A", slot, "bb_back_squat")
        seed(runId)
        val restored = db.runDao().tracks(runId)
        val expected = RunReplan.represcribe(SetJson.decodeSets(original.setsJson), List(3) { LoggedSet(45.0, 5, SetKind.WORK) })
        assertEquals(original.copy(setsJson = SetJson.encodeSets(expected)), restored.single { it.exerciseId == "bb_back_squat" })
        assertEquals(alternate.copy(retired = true), restored.single { it.exerciseId == "hack_squat" })
        val session = repo.finishRun(runId)!!
        val archived = repo.history.sessionSets(session)
        val alternateRows = archived.filter { it.exerciseId == "hack_squat" }
        assertEquals(2, alternateRows.size)
        assertTrue(alternateRows.all { it.done })
        assertEquals(expected.size, archived.count { it.exerciseId == "bb_back_squat" })
        val carried = SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson)
        assertEquals(List(3) { LoggedSet(45.0, 5, SetKind.WORK) }, carried)
        assertEquals("bb_back_squat", db.programDao().exercise(slot)!!.exerciseId)
    }

    @Test fun `structure change keeps done rows and watch identities reshapes undone rows and clears only its working values`() = runTest {
        val plan = generated(extraSlot = true)
        val id = slot(plan)
        val other = db.programDao().exercisesForDay(plan, "A")[1].id
        val runId = start(plan, doneCount = 2)
        val main = db.runDao().tracks(runId).single { it.programExerciseId == id }
        val current = SetJson.decodeSets(main.setsJson).mapIndexed { index, row ->
            row.copy(watchEditedAt = 100L + index, startedAtMillis = 200L + index,
                completedAtMillis = (300L + index).takeIf { row.done })
        }
        db.runDao().updateTrack(main.copy(setsJson = SetJson.encodeSets(current)))
        db.storeTestRows("A", id, Slot.SS, work())
        db.storeTestRows("A", other, Slot.MAIN, work())
        val otherLog = db.programDao().logForSlot(plan, "A", other, Slot.MAIN)
        val expected = SetSeeder.shape(SetStructure.RAMP_TOP, 45.0, 5, 3, WeightUnit.LB,
            listOf(Equipment.BARBELL, Equipment.RACK), Scheme.PROTOTYPE)

        repo.setStructure(id, SetStructure.RAMP_TOP)

        val after = db.runDao().tracks(runId).single { it.id == main.id }
        val rows = SetJson.decodeSets(after.setsJson)
        assertEquals(RunReplan.represcribe(current, expected), rows)
        assertEquals(current.take(2), rows.take(2))
        assertEquals(5, rows.size)
        assertEquals(SetKind.TOP, rows.last().kind)
        assertEquals(main.plannedJson, after.plannedJson)
        assertEquals(SetStructure.RAMP_TOP, repo.plans.slotsFor(plan, "A").first().first().exercise.structure)
        assertTrue(db.programDao().logsForDay(plan, "A").none { it.programExerciseId == id })
        assertEquals(otherLog, db.programDao().logForSlot(plan, "A", other, Slot.MAIN))
    }

    @Test fun `structure shrink keeps a completed backoff beyond the new row count`() = runTest {
        val plan = generated()
        val id = slot(plan)
        val runId = start(plan, doneCount = 6)
        val before = db.runDao().tracks(runId).single()

        repo.setStructure(id, SetStructure.RAMP_TOP)

        assertEquals(before, db.runDao().tracks(runId).single())
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `setting a straight prescription with its structure reseeds three straight sets`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val id = slot(plan)
        repo.ensureSeeded(plan, "A")
        assertEquals(6, SetJson.decodeSets(db.programDao().logForSlot(plan, "A", id, Slot.MAIN)!!.setsJson).size)
        val prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5))

        repo.setPrescription(id, prescription, 3, SetStructure.STRAIGHT)

        val exercise = repo.plans.slotsFor(plan, "A").first().single().exercise
        assertEquals(SetStructure.STRAIGHT, exercise.structure)
        assertEquals(3, exercise.targetSets)
        assertEquals(prescription, exercise.prescription)
        repo.ensureSeeded(plan, "A")
        val rows = SetJson.decodeSets(db.programDao().logForSlot(plan, "A", id, Slot.MAIN)!!.setsJson)
        assertEquals(List(3) { LoggedSet(185.0, 5, SetKind.WORK) }, rows)
    }

    @Test fun `straight prescribed 225 by 5 changes to the pinned ramp top backoff column`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val prescription = Prescription(PrescribedTarget.LoadReps(225.0, 5))
        repo.setPrescription(id, prescription)
        val runId = explicitStart(plan, SetSeeder.seedPrescribed(3, prescription))
        val before = db.runDao().tracks(runId).single()

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        val after = db.runDao().tracks(runId).single()
        val rows = SetJson.decodeSets(after.setsJson)
        assertEquals(listOf(125.0, 160.0, 180.0, 205.0, 225.0, 170.0), rows.map { it.weightLb })
        assertEquals(listOf(5, 5, 5, 3, 5, 8), rows.map { it.reps })
        assertEquals(List(4) { SetKind.RAMP } + listOf(SetKind.TOP, SetKind.BACKOFF), rows.map { it.kind })
        assertEquals(listOf(0, 1, 2, null, null, null), rows.map { it.plannedIndex })
        assertEquals(before.plannedJson, after.plannedJson)
        assertTrue(rows.none { it.done })
        assertNull(db.programDao().logForSlot(plan, "A", id, Slot.MAIN))
    }

    @Test fun `structure growth aligns partners to ramp count and shrink retains a partners completed tail`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        val original = db.runDao().tracks(runId).associateBy { it.slot }
        val partnerBefore = SetJson.decodeSets(original.getValue(Slot.SS).setsJson)

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        val grown = db.runDao().tracks(runId).associateBy { it.slot }
        val partner = SetJson.decodeSets(grown.getValue(Slot.SS).setsJson)
        assertEquals(6, SetJson.decodeSets(grown.getValue(Slot.MAIN).setsJson).size)
        assertEquals(partnerBefore, partner.take(3))
        assertEquals(List(3) { LoggedSet(45.0, 10, SetKind.WORK) }, partner.drop(3))
        assertTrue(partner.all { it.kind == SetKind.WORK })
        assertEquals(original.mapValues { it.value.plannedJson }, grown.mapValues { it.value.plannedJson })
        val completedPartner = partner.mapIndexed { index, row -> row.copy(done = index == 5) }
        repo.writeTestRows(runId, "A", id, Slot.SS, completedPartner)
        val beforeShrink = db.runDao().tracks(runId).associateBy { it.slot }

        repo.setStructure(id, SetStructure.RAMP_TOP)

        val shrunk = db.runDao().tracks(runId).associateBy { it.slot }
        val main = SetJson.decodeSets(shrunk.getValue(Slot.MAIN).setsJson)
        assertEquals(6, main.size)
        assertEquals(SetJson.decodeSets(beforeShrink.getValue(Slot.MAIN).setsJson).last(), main.last())
        assertEquals(SetJson.decodeSets(beforeShrink.getValue(Slot.SS).setsJson),
            SetJson.decodeSets(shrunk.getValue(Slot.SS).setsJson))
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `prescription edits on ramped slots retain their structure and align the partner`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setStructure(id, SetStructure.RAMP_TOP)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan, SetSeeder.seedPrescribed(3, load, SetStructure.RAMP_TOP))
        val before = db.runDao().tracks(runId).single { it.slot == Slot.MAIN }
        val prescription = Prescription(PrescribedTarget.LoadReps(225.0, 5))

        repo.setPrescription(id, prescription, sets = 2)

        val tracks = db.runDao().tracks(runId).associateBy { it.slot }
        val main = SetJson.decodeSets(tracks.getValue(Slot.MAIN).setsJson)
        assertEquals(listOf(125.0, 160.0, 180.0, 205.0, 225.0), main.map { it.weightLb })
        assertEquals(SetKind.TOP, main.last().kind)
        assertEquals(5, SetJson.decodeSets(tracks.getValue(Slot.SS).setsJson).size)
        assertEquals(before.plannedJson, tracks.getValue(Slot.MAIN).plannedJson)
        assertEquals(2, db.programDao().exercise(id)!!.targetSets)
    }

    @Test fun `structure edits on a substituted main leave the substitute and reshape the retired original`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val runId = explicitStart(plan)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val before = db.runDao().tracks(runId)

        repo.setStructure(id, SetStructure.RAMP_TOP)

        val after = db.runDao().tracks(runId)
        assertEquals(before.filter { !it.retired }, after.filter { !it.retired })
        val original = before.single { it.retired }
        val reshaped = after.single { it.id == original.id }
        // #431: the retired original follows the plan edit, so swapping it back brings the new shape.
        assertTrue(reshaped.retired)
        assertEquals(original.plannedJson, reshaped.plannedJson)
        val rows = SetJson.decodeSets(reshaped.setsJson)
        assertEquals(5, rows.size)
        assertEquals(SetKind.TOP, rows.last().kind)
        assertEquals(SetStructure.RAMP_TOP.name, db.programDao().exercise(id)!!.structure)
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    /** [F] */
    @Test fun `zero load prescription edit replans undone rows without changing captured prescription`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val zeroLoad = Prescription(PrescribedTarget.LoadReps(0.0, 10))
        repo.setPrescription(slot, zeroLoad, sets = 3)
        val seeded = SetSeeder.seedPrescribed(3, zeroLoad)
        db.storeTestRows("A", slot, Slot.MAIN, seeded)
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
        db.storeTestRows("A", slot, Slot.MAIN, work())
        db.storeTestRows("A", slot, Slot.SS, partner)
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        val completed = partner.mapIndexed { i, row -> row.copy(done = i == 2, watchEditedAt = 123L) }
        assertEquals(RunWriteResult.InRun(runId, true), repo.writeTestRows(runId, "A", slot, Slot.SS, completed))
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

    @Test fun `authored prescribed pair growth pads new SS rows from its last row`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setSupersetPartner("A", slot, "ez_curl")
        val partner = SetSeeder.seedPartner(3, 45.0)
        db.storeTestRows("A", slot, Slot.MAIN, work())
        db.storeTestRows("A", slot, Slot.SS, partner)
        val runId = start(plan, sets = work())
        val before = db.runDao().tracks(runId).associateBy { it.slot }
        val changed = Prescription(PrescribedTarget.LoadReps(60.0, 8))

        repo.setPrescription(slot, changed, sets = 4)

        val after = db.runDao().tracks(runId).associateBy { it.slot }
        val main = SetJson.decodeSets(after.getValue(Slot.MAIN).setsJson)
        val ss = SetJson.decodeSets(after.getValue(Slot.SS).setsJson)
        assertEquals(4, main.size)
        assertEquals(4, ss.size)
        assertEquals(SetJson.decodeSets(before.getValue(Slot.MAIN).setsJson)[0], main[0])
        assertEquals(SetSeeder.seedPrescribed(4, changed).last(), main.last())
        assertEquals(SetJson.decodeSets(before.getValue(Slot.SS).setsJson), ss.take(3))
        assertEquals(LoggedSet(45.0, 10, SetKind.WORK), ss.last())
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
        db.storeTestRows("A", slot, Slot.MAIN, work())
        db.storeTestRows("A", slot, Slot.SS, partner)
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
        db.storeTestRows("A", slot, Slot.MAIN, work())
        db.storeTestRows("A", slot, Slot.SS, SetSeeder.seedPartner(3, 35.0))
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
        seed(runId)
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
        val expected = RunReplan.represcribe(SetJson.decodeSets(original.setsJson), List(3) { LoggedSet(45.0, 5, SetKind.WORK) })
        assertEquals(expected.mapIndexed { i, row ->
            if (i == 1) row.copy(done = true) else row
        }, SetJson.decodeSets(reopened.setsJson))
    }

    @Test fun `a value edit before lazy seed reopens the swapped back track on top of its done rows`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val runId = start(plan, doneCount = 2)
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId)
        repo.swapExerciseById("A", slot, "bb_back_squat")
        val originalRows = SetJson.decodeSets(original.setsJson)
        assertEquals(2, originalRows.count { it.done })
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, plan, "A", listOf(
            TrackEdit(slot, Slot.MAIN) { rows -> SetEditor.edit(rows, 2, SetEdit(weightLb = 200.0)) },
        )))
        val after = db.runDao().tracks(runId).single()
        val rows = SetJson.decodeSets(after.setsJson)
        assertFalse(after.retired)
        assertEquals(original.id, after.id)
        assertEquals(original.plannedJson, after.plannedJson)
        assertEquals(originalRows.take(2), rows.take(2))
        assertEquals(2, rows.count { it.done })
        assertEquals(200.0, rows[2].weightLb, 0.0)
    }

    @Test fun `plan swap back drops a timed substitute prescription before reopening rep rows`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId)
        val hold = Prescription(PrescribedTarget.Hold(45))
        repo.setPrescription(slot, hold)
        assertEquals(hold, PrescriptionJson.decode(db.programDao().exercise(slot)!!.prescriptionJson))
        repo.swapExerciseById("A", slot, "bb_back_squat")
        seed(runId)
        val expected = original.copy(setsJson = SetJson.encodeSets(RunReplan.represcribe(
            SetJson.decodeSets(original.setsJson), List(3) { LoggedSet(45.0, 5, SetKind.WORK) },
        )))
        assertNull(db.programDao().exercise(slot)!!.prescriptionJson)
        assertEquals(listOf(expected), db.runDao().tracks(runId))
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId)
        repo.swapExerciseById("A", slot, "bb_back_squat")
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, plan, "A", listOf(TrackEdit(slot, Slot.MAIN) { it })))
        assertEquals(listOf(expected), db.runDao().tracks(runId))
    }

    /** [F] */
    @Test fun `reopening by lazy seed drops the substitute prescription and keeps the straight count`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single()
        repo.swapExerciseById("A", slot, "hack_squat")
        seed(runId)
        assertTrue(db.runDao().tracks(runId).single { it.exerciseId == "bb_back_squat" }.retired)
        val changed = Prescription(PrescribedTarget.LoadReps(55.0, 8))
        repo.setPrescription(slot, changed, sets = 4)
        assertEquals(original.copy(retired = true), db.runDao().tracks(runId).single { it.exerciseId == "bb_back_squat" })
        repo.swapExerciseById("A", slot, "bb_back_squat")
        seed(runId)
        val reopened = db.runDao().tracks(runId).single()
        assertFalse(reopened.retired)
        assertEquals(original.id, reopened.id)
        assertEquals(original.plannedJson, reopened.plannedJson)
        val rows = SetJson.decodeSets(reopened.setsJson)
        assertEquals(SetJson.decodeSets(original.setsJson).first(), rows.first())
        assertNull(db.programDao().exercise(slot)!!.prescriptionJson)
        assertEquals(List(3) { 45.0 }, rows.drop(1).map { it.weightLb })
        assertEquals(List(3) { 5 }, rows.drop(1).map { it.reps })
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
        seed(runId)
        assertTrue(db.runDao().tracks(runId).single { it.exerciseId == "bb_back_squat" }.retired)
        val changed = Prescription(PrescribedTarget.LoadReps(55.0, 8))
        repo.setPrescription(slot, changed, sets = 4)
        repo.swapExerciseById("A", slot, "bb_back_squat")
        val result = repo.applyToRun(runId, plan, "A", listOf(TrackEdit(slot, Slot.MAIN, "bb_back_squat") { rows ->
            rows.mapIndexed { i, row -> if (i == 1) row.copy(done = true) else row }
        }))
        assertEquals(RunWriteResult.InRun(runId, false), result)
        val reopened = db.runDao().tracks(runId).single()
        assertFalse(reopened.retired)
        assertEquals(original.id, reopened.id)
        assertEquals(original.plannedJson, reopened.plannedJson)
        val rows = SetJson.decodeSets(reopened.setsJson)
        assertEquals(SetJson.decodeSets(original.setsJson).first(), rows.first())
        assertNull(db.programDao().exercise(slot)!!.prescriptionJson)
        assertEquals(List(3) { 45.0 }, rows.drop(1).map { it.weightLb })
        assertEquals(List(3) { 5 }, rows.drop(1).map { it.reps })
        assertEquals(listOf(true, true, false, false), rows.map { it.done })
        assertEquals(listOf(0, 1, 2, null), rows.map { it.plannedIndex })
    }

    /** [F] */
    @Test fun `remove retires done tracks skips carry forward and deletes undone tracks`() = runTest {
        val plan = generated(extraSlot = true)
        val slots = db.programDao().exercisesForDay(plan, "A")
        val runId = start(plan)
        seed(runId)
        assertTrue(repo.removeExercise(slots[0].id))
        assertTrue(repo.removeExercise(slots[1].id))
        val retired = db.runDao().tracks(runId).single()
        assertTrue(retired.retired)
        assertEquals(slots[0].id, retired.programExerciseId)
        val session = repo.finishRun(runId)!!
        val archived = repo.history.sessionSets(session)
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
        seed(runId)
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
        seed(runId)
        repo.writeTestRows(runId, "A", slot, Slot.SS, work().mapIndexed { i, row -> row.copy(done = i == 0) })
        val before = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        repo.setSupersetPartner("A", slot, "rope_pushdown")
        assertEquals(before.copy(retired = true), db.runDao().tracks(runId).single { it.slot == Slot.SS })
        val existing = repo.dayTracksFlow(plan, "A").first().tracks
            .associate { (it.programExerciseId to it.slot) to it.sets.size }
        assertEquals(6, existing[slot to Slot.MAIN])
        seed(runId)
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
        seed(runId)
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
        db.storeTestRows("A", slot(plan), Slot.MAIN, rows)
        val pe = db.programDao().exercise(slot(plan))!!
        if (pe.supersetExerciseId != null && db.programDao().logForSlot(plan, "A", pe.id, Slot.SS) == null) {
            db.storeTestRows("A", pe.id, Slot.SS, SetSeeder.seedPartner(3, 45.0))
        }
        assertEquals(RunStartResult.Started(id), repo.startRun(plan, "A"))
        return id
    }

    @Test fun `workout swap before any set retires the original and swap back restores its prescription rows`() = runTest {
        val plan = authored()
        val id = slot(plan)
        db.storeTestRows("A", id, Slot.MAIN, work())
        val runId = explicitStart(plan)
        val original = db.runDao().tracks(runId).single()
        val seed = List(3) { LoggedSet(20.0, 10, SetKind.WORK) }
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val tracks = repo.openRunFlow.first()!!.tracks
        assertTrue(tracks.single { it.exerciseId == "bb_back_squat" }.retired)
        assertEquals(seed, tracks.single { !it.retired }.planned)
        assertEquals("bb_back_squat", db.programDao().exercise(id)!!.exerciseId)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        assertEquals(original, db.runDao().tracks(runId).single { !it.retired })
        assertEquals(work(), SetJson.decodeSets(db.programDao().logForSlot(plan, "A", id, Slot.MAIN)!!.setsJson))
    }

    @Test fun `workout swap refuses an unknown substitute without retiring anything`() = runTest {
        val plan = authored()
        val runId = explicitStart(plan)
        val before = db.runDao().tracks(runId)
        assertFalse(repo.swapInRun(runId, slot(plan), Slot.MAIN, "unknown_substitute"))
        assertEquals(before, db.runDao().tracks(runId))
        assertFalse(repo.swapInRun("closed", slot(plan), Slot.MAIN, "hack_squat"))
        assertEquals(before, db.runDao().tracks(runId))
    }

    @Test fun `workout swap back follows a structure edited while substituted and aligns the partner`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single { it.slot == Slot.MAIN }
        val current = SetJson.decodeSets(original.setsJson).mapIndexed { index, row ->
            row.copy(watchEditedAt = 100L + index, startedAtMillis = 200L + index,
                completedAtMillis = 300L.takeIf { row.done })
        }
        db.runDao().updateTrack(original.copy(setsJson = SetJson.encodeSets(current)))
        val partner = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        val partnerRows = SetJson.decodeSets(partner.setsJson)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val substituted = db.runDao().tracks(runId)

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        val expected = SetSeeder.seedPrescribed(3, load, SetStructure.RAMP_TOP_BACKOFF, equipment = listOf(Equipment.BARBELL))
        val editedTracks = db.runDao().tracks(runId)
        assertEquals(substituted.filterNot { it.id == original.id }, editedTracks.filterNot { it.id == original.id })
        assertEquals(substituted.single { it.id == original.id }.copy(
            setsJson = SetJson.encodeSets(RunReplan.represcribe(current, expected)),
        ), editedTracks.single { it.id == original.id })
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        val restored = db.runDao().tracks(runId).filter { !it.retired }.associateBy { it.slot }
        val main = restored.getValue(Slot.MAIN)
        val rows = SetJson.decodeSets(main.setsJson)
        assertEquals(RunReplan.represcribe(current, expected), rows)
        assertEquals(6, rows.size)
        assertEquals(current.first(), rows.first())
        assertEquals(listOf(0, 1, 2, null, null, null), rows.map { it.plannedIndex })
        assertEquals(listOf(100L, 101L, 102L, null, null, null), rows.map { it.watchEditedAt })
        assertEquals(SetKind.BACKOFF, rows.last().kind)
        assertEquals(original.plannedJson, main.plannedJson)
        val alignedPartner = restored.getValue(Slot.SS)
        assertEquals(partnerRows, SetJson.decodeSets(alignedPartner.setsJson).take(3))
        assertEquals(6, SetJson.decodeSets(alignedPartner.setsJson).size)
        assertEquals(partner.plannedJson, alignedPartner.plannedJson)

        val edited = rows.mapIndexed { index, row ->
            if (index == 1) row.copy(weightLb = 85.0, watchEditedAt = 500L) else row
        }
        repo.writeTestRows(runId, "A", id, Slot.MAIN, edited)
        val beforeSecondSwap = db.runDao().tracks(runId).single { it.id == main.id }
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        assertEquals(beforeSecondSwap, db.runDao().tracks(runId).single { !it.retired && it.slot == Slot.MAIN })
    }

    @Test fun `workout swap back follows a prescription edited while substituted and keeps done rows`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single()
        val current = SetJson.decodeSets(original.setsJson).mapIndexed { index, row ->
            row.copy(watchEditedAt = 100L + index)
        }
        db.runDao().updateTrack(original.copy(setsJson = SetJson.encodeSets(current)))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val substitute = db.runDao().tracks(runId).single { !it.retired }
        val prescription = Prescription(PrescribedTarget.LoadReps(80.0, 8))

        repo.setPrescription(id, prescription, sets = 6)

        assertEquals(substitute, db.runDao().tracks(runId).single { !it.retired })
        assertEquals(original.copy(retired = true, setsJson = SetJson.encodeSets(
            RunReplan.represcribe(current, SetSeeder.seedPrescribed(6, prescription)),
        )), db.runDao().tracks(runId).single { it.retired })
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        val restored = db.runDao().tracks(runId).single { !it.retired }
        val rows = SetJson.decodeSets(restored.setsJson)
        assertEquals(RunReplan.represcribe(current, SetSeeder.seedPrescribed(6, prescription)), rows)
        assertEquals(6, rows.size)
        assertEquals(current.first(), rows.first())
        assertEquals(original.plannedJson, restored.plannedJson)
    }

    @Test fun `unchanged plan workout swap back restores the byte identical track that main restores`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val remembered = work().map { it.copy(weightLb = 65.0, reps = 12) }
        val runId = start(plan, sets = remembered)
        val rows = repo.openRunFlow.first()!!.tracks.single().sets.mapIndexed { index, row ->
            row.copy(weightLb = 70.0 + index, reps = 6 + index, watchEditedAt = 100L + index,
                startedAtMillis = 200L + index, completedAtMillis = 300L.takeIf { row.done })
        } + work().last().copy(weightLb = 90.0)
        repo.writeTestRows(runId, "A", id, Slot.MAIN, rows)
        val original = db.runDao().tracks(runId).single()

        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))

        assertEquals(original, db.runDao().tracks(runId).single { !it.retired })
    }

    @Test fun `bodyweight change while a kept to plan original is swapped out preserves its restored rows`() = runTest {
        val plan = generated()
        val id = slot(plan)
        repo.setKeepToPlan(id, true)
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(RunStartResult.Started(runId), repo.startRun(plan, "A"))
        val original = db.runDao().tracks(runId).single()
        val entry = repo.catalog.catalogFlow.first().get(original.exerciseId)
        val oldGoal = GoalCalculator.targetFor(entry, repo.plans.configFlow.first())
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val substituted = db.runDao().tracks(runId)

        repo.plans.updateLifter { it.copy(bodyweightLb = it.bodyweightLb + 100) }

        assertFalse(oldGoal == GoalCalculator.targetFor(entry, repo.plans.configFlow.first()))
        assertEquals(substituted, db.runDao().tracks(runId))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        assertEquals(original, db.runDao().tracks(runId).single { !it.retired })
    }

    @Test fun `timed prescription edit while substituted keeps the retired originals rep shaped rows`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single()
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val substituted = db.runDao().tracks(runId)

        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(45)), sets = 6)

        assertEquals(substituted, db.runDao().tracks(runId))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        assertEquals(original, db.runDao().tracks(runId).single { !it.retired })
    }

    @Test fun `structure edit replans a retired original SS and preserves the substituted partners rows`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        val partner = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        val current = SetJson.decodeSets(partner.setsJson).mapIndexed { index, row ->
            row.copy(weightLb = 60.0 + index, done = index == 0, watchEditedAt = 100L + index)
        }
        db.runDao().updateTrack(partner.copy(setsJson = SetJson.encodeSets(current)))
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "rope_pushdown"))
        val substitute = db.runDao().tracks(runId).single { it.slot == Slot.SS && !it.retired }

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        val expected = RunReplan.represcribe(current, List(6) { LoggedSet(20.0, 10, SetKind.WORK) })
        val retired = db.runDao().tracks(runId).single { it.id == partner.id }
        assertEquals(partner.copy(retired = true, setsJson = SetJson.encodeSets(expected)), retired)
        val activePartner = db.runDao().tracks(runId).single { it.id == substitute.id }
        assertEquals(SetJson.decodeSets(substitute.setsJson), SetJson.decodeSets(activePartner.setsJson).take(3))
        assertEquals(substitute.plannedJson, activePartner.plannedJson)
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "ez_curl"))
        assertEquals(retired.copy(retired = false), db.runDao().tracks(runId).single { it.id == partner.id })
    }

    @Test fun `undone row edit after an active replan survives workout swap out and back`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = start(plan, sets = work())
        val prescription = Prescription(PrescribedTarget.LoadReps(80.0, 8))
        repo.setPrescription(id, prescription, sets = 4)
        val rows = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN }.sets.mapIndexed { index, row ->
            if (index == 1) row.copy(weightLb = 85.0, watchEditedAt = 100L) else row
        }
        repo.writeTestRows(runId, "A", id, Slot.MAIN, rows)
        val partner = db.runDao().tracks(runId).single { it.slot == Slot.SS }

        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))

        val restored = db.runDao().tracks(runId).filter { !it.retired }.associateBy { it.slot }
        val restoredRows = SetJson.decodeSets(restored.getValue(Slot.MAIN).setsJson)
        assertEquals(rows.filter { it.done }, restoredRows.filter { it.done })
        assertEquals(rows.filterNot { it.done }, restoredRows.filterNot { it.done })
        assertEquals(rows, restoredRows)
        assertEquals(partner, restored.getValue(Slot.SS))
        assertEquals(restoredRows.size, SetJson.decodeSets(restored.getValue(Slot.SS).setsJson).size)
    }

    @Test fun `workout swap archives each performed exercise and leaves the slots remembered values alone`() = runTest {
        val plan = authored()
        val id = slot(plan)
        db.storeTestRows("A", id, Slot.MAIN, work())
        val before = db.programDao().logsForDay(plan, "A")
        val runId = start(plan, sets = work())
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val active = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        repo.writeTestRows(runId, "A", id, Slot.MAIN, active.sets.mapIndexed { index, set -> set.copy(done = index == 0) })
        val session = repo.finishRun(runId)!!
        val done = repo.history.sessionSets(session).filter { it.done }
        assertEquals(setOf("bb_back_squat", "hack_squat"), done.map { it.exerciseId }.toSet())
        assertEquals(1, repo.history.sessionSets(session).count { it.exerciseId == "bb_back_squat" })
        assertEquals(before, db.programDao().logsForDay(plan, "A"))
    }

    @Test fun `workout MAIN swap preserves partner rows and reopening a longer MAIN pads the partner`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val partner = SetSeeder.seedPartner(3, 45.0)
        db.storeTestRows("A", id, Slot.SS, partner)
        val longMain = work() + work().take(2)
        val runId = explicitStart(plan, longMain)
        repo.writeTestRows(runId, "A", id, Slot.SS, partner.mapIndexed { index, set -> set.copy(done = index == 2) })
        val before = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }.sets
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        assertEquals(before, repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }.sets)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        val padded = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }.sets
        assertEquals(5, padded.size)
        assertEquals(before, padded.take(3))
        assertTrue(padded[2].done)
        assertEquals(List(2) { LoggedSet(45.0, 10, SetKind.WORK) }, padded.takeLast(2))
    }

    @Test fun `workout SS swap keeps MAIN values and uses the new partner's floor at the live row count`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan, work() + work().take(1))
        seed(runId)
        val main = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN }
        val rows = List(4) { LoggedSet(20.0, 10, SetKind.WORK) }
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "rope_pushdown"))
        val tracks = repo.openRunFlow.first()!!.tracks
        assertEquals(main, tracks.single { it.slot == Slot.MAIN })
        assertEquals(rows, tracks.single { it.slot == Slot.SS && !it.retired }.planned)
        assertEquals("ez_curl", db.programDao().exercise(id)!!.supersetExerciseId)
    }

    @Test fun `prescription edit on a substituted slot leaves the substitute unchanged and clears working values`() = runTest {
        val plan = authored()
        val id = slot(plan)
        db.storeTestRows("A", id, Slot.MAIN, work())
        val runId = explicitStart(plan)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val before = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(80.0, 8)), sets = 4)
        assertEquals(before, repo.openRunFlow.first()!!.tracks.single { !it.retired })
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `plan swap on a substituted slot retires the substitutes done work and seeds the new plan exercise`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val runId = explicitStart(plan)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val rows = repo.openRunFlow.first()!!.tracks.single { !it.retired }.sets
        repo.writeTestRows(runId, "A", id, Slot.MAIN, rows.mapIndexed { i, row -> row.copy(done = i == 0) })
        val substitute = db.runDao().tracks(runId).single { !it.retired }
        repo.swapExerciseById("A", id, "leg_press")
        seed(runId)
        assertEquals(substitute.copy(retired = true), db.runDao().tracks(runId).single { it.exerciseId == "hack_squat" })
        assertEquals("leg_press", repo.openRunFlow.first()!!.tracks.single { !it.retired }.exerciseId)
    }

    @Test fun `carry forward shape check finds a retired original MAIN and clears both working tracks`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setKeepToPlan(id, false)
        repo.setSupersetPartner("A", id, "ez_curl")
        db.storeTestRows("A", id, Slot.MAIN, work())
        db.storeTestRows("A", id, Slot.SS, work())
        val runId = explicitStart(plan)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(45)))
        // New working values can arrive while the original track remains retired.
        db.storeTestRows("A", id, Slot.MAIN, work())
        db.storeTestRows("A", id, Slot.SS, work())
        repo.finishRun(runId)
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `same exercise plan swap during a workout reopens the original without clearing working values`() = runTest {
        val plan = authored()
        val id = slot(plan)
        db.storeTestRows("A", id, Slot.MAIN, work())
        val logs = db.programDao().logsForDay(plan, "A")
        val runId = explicitStart(plan)
        val original = repo.openRunFlow.first()!!.tracks.single()
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        assertTrue(repo.swapExerciseById("A", id, "bb_back_squat"))
        assertEquals(original, repo.openRunFlow.first()!!.tracks.single { !it.retired })
        assertEquals(logs, db.programDao().logsForDay(plan, "A"))
    }

    @Test fun `reopening a shorter prescribed MAIN pads from its last edited row`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan, work())
        seed(runId)
        val edited = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN }.sets.mapIndexed { index, row ->
            if (index == 2) row.copy(weightLb = 70.0, reps = 7, kind = SetKind.EXTRA, done = true,
                watchEditedAt = 100L, startedAtMillis = 200L, completedAtMillis = 300L) else row
        }
        assertTrue(repo.writeTestRows(runId, "A", id, Slot.MAIN, edited) is RunWriteResult.InRun)
        val original = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN }
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        repo.writeTestRows(runId, "A", id, Slot.SS, SetSeeder.seedPartner(5, 45.0))
        val partner = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.SS }
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat"))
        val open = repo.openRunFlow.first()!!
        val main = open.tracks.single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(original.sets, main.sets.take(3))
        assertEquals(List(2) { LoggedSet(70.0, 7, SetKind.EXTRA) }, main.sets.drop(3))
        assertEquals(partner, open.tracks.single { it.slot == Slot.SS })
    }

    @Test fun `prescription edit with a substituted SS preserves its rows and keeps its done tail reachable`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId)
        val substitute = SetSeeder.seedPartner(5, 35.0)
        val mainBeforeSwap = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN }.sets
        repo.writeTestRows(runId, "A", id, Slot.MAIN, mainBeforeSwap + work().take(2))
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "cable_curl"))
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "rope_pushdown"))
        repo.writeTestRows(runId, "A", id, Slot.SS, substitute.mapIndexed { index, row -> row.copy(done = index == 4) })
        val mainRows = repo.openRunFlow.first()!!.tracks.single { it.slot == Slot.MAIN && !it.retired }.sets
        repo.writeTestRows(runId, "A", id, Slot.MAIN, mainRows.mapIndexed { index, row -> row.copy(done = index == 3) })
        val before = db.runDao().tracks(runId)
        val partner = before.single { it.slot == Slot.SS && !it.retired }
        val prescription = Prescription(PrescribedTarget.LoadReps(80.0, 6))
        repo.setPrescription(id, prescription, 2)
        val after = db.runDao().tracks(runId)
        assertEquals(partner, after.single { it.id == partner.id })
        val originalPartner = before.single { it.slot == Slot.SS && it.exerciseId == "ez_curl" }
        val replannedPartner = RunReplan.represcribe(SetJson.decodeSets(originalPartner.setsJson),
            List(2) { LoggedSet(20.0, 10, SetKind.WORK) })
        assertEquals(2, replannedPartner.size)
        assertEquals(originalPartner.copy(setsJson = SetJson.encodeSets(replannedPartner)),
            after.single { it.id == originalPartner.id })
        val earlierSubstitute = before.single { it.slot == Slot.SS && it.exerciseId == "cable_curl" }
        assertTrue(earlierSubstitute.retired)
        assertEquals(earlierSubstitute, after.single { it.id == earlierSubstitute.id })
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
        seed(result.runId)
        assertEquals(before, db.runDao().tracks(result.runId))
    }

    @Test fun `dropped done partner archives work and carries opening rows`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        db.storeTestRows("A", id, Slot.SS, work())
        val working = db.programDao().logForSlot(plan, "A", id, Slot.SS)!!
        val runId = explicitStart(plan)
        seed(runId)
        repo.writeTestRows(runId, "A", id, Slot.SS, work().mapIndexed { i, set -> set.copy(weightLb = 175.0, done = i == 0) })
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        assertEquals(0, repo.dayTracksFlow(plan, "A").first().retiredRoundsDone)
        val session = repo.finishRun(runId)!!
        val archived = repo.history.sessionSets(session).filter { it.slot == Slot.SS }
        assertEquals(1, archived.size)
        assertTrue(archived.single().done)
        assertEquals(175.0, archived.single().weightLb, 0.0)
        assertEquals(SetJson.decodeSets(working.setsJson),
            SetJson.decodeSets(db.programDao().logForSlot(plan, "A", id, Slot.SS)!!.setsJson))
        assertTrue(repo.dayTracksFlow(plan, "A").first().droppedPartners.isEmpty())
    }

    @Test fun `keeping partner swaps MAIN and undo restores both original tracks`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId)
        repo.writeTestRows(runId, "A", id, Slot.SS, work().mapIndexed { i, set -> set.copy(done = i == 0) })
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
        assertTrue(repo.swapInRun(result.runId, id, Slot.MAIN, "hack_squat"))
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

    @Test fun `structure edit leaves dropped SS rows unchanged when restoring it to a substituted MAIN`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        val partner = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        val partnerRows = SetJson.decodeSets(partner.setsJson).mapIndexed { index, row ->
            row.copy(weightLb = 60.0 + index, done = index == 0, watchEditedAt = 100L + index)
        }
        db.runDao().updateTrack(partner.copy(setsJson = SetJson.encodeSets(partnerRows)))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        val substitute = db.runDao().tracks(runId).single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(3, SetJson.decodeSets(substitute.setsJson).size)
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "hack_squat", "ez_curl") is RunWriteResult.InRun)
        val droppedPartner = db.runDao().tracks(runId).single { it.id == partner.id }
        assertTrue(droppedPartner.retired && droppedPartner.dropped)

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        val afterEdit = db.runDao().tracks(runId)
        assertEquals(substitute, afterEdit.single { it.id == substitute.id })
        assertEquals(droppedPartner, afterEdit.single { it.id == partner.id })
        val originalMain = afterEdit.single { it.slot == Slot.MAIN && it.exerciseId == "bb_back_squat" }
        assertEquals(6, SetJson.decodeSets(originalMain.setsJson).size)
        assertTrue(repo.restoreSkipped(runId, id, Slot.SS) is RunWriteResult.InRun)

        val restored = db.runDao().tracks(runId)
        assertEquals(substitute, restored.single { it.id == substitute.id })
        assertEquals(droppedPartner.copy(retired = false, dropped = false), restored.single { it.id == partner.id })
        assertEquals(partnerRows, SetJson.decodeSets(restored.single { it.id == partner.id }.setsJson))
    }

    @Test fun `tracking type change rejected by active MAIN leaves dropped original SS untouched`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val before = db.runDao().tracks(runId)
        val main = before.single { it.slot == Slot.MAIN }
        val partner = before.single { it.slot == Slot.SS }
        assertTrue(partner.retired && partner.dropped)
        assertEquals(3, SetJson.decodeSets(main.setsJson).size)
        assertEquals(3, SetJson.decodeSets(partner.setsJson).size)

        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(45)), sets = 6)

        assertEquals(before, db.runDao().tracks(runId))
        assertTrue(repo.restoreSkipped(runId, id, Slot.SS) is RunWriteResult.InRun)
        val restored = db.runDao().tracks(runId)
        assertEquals(main, restored.single { it.id == main.id })
        assertEquals(partner.copy(retired = false, dropped = false), restored.single { it.id == partner.id })
    }

    @Test fun `tracking type change rejected by retired MAIN leaves dropped original SS untouched`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val before = db.runDao().tracks(runId)
        val main = before.single { it.slot == Slot.MAIN && it.exerciseId == "bb_back_squat" }
        val partner = before.single { it.slot == Slot.SS }
        assertTrue(main.retired && main.dropped)
        assertTrue(partner.retired && partner.dropped)

        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(45)), sets = 6)

        assertEquals(before, db.runDao().tracks(runId))
        assertTrue(repo.restoreSkipped(runId, id, Slot.MAIN) is RunWriteResult.InRun)
        val restored = db.runDao().tracks(runId)
        assertEquals(main.copy(retired = false, dropped = false), restored.single { it.id == main.id })
        assertEquals(partner.copy(retired = false, dropped = false), restored.single { it.id == partner.id })
    }

    @Test fun `drop undo pads SS after adding a MAIN row and preserves existing work`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId)
        val mainRows = work().mapIndexed { index, row ->
            row.copy(weightLb = 60.0 + index * 5, reps = 8 + index, done = index != 1,
                plannedIndex = index, watchEditedAt = 100L + index)
        }
        val partnerRows = SetSeeder.seedPartner(3, 45.0).mapIndexed { index, row ->
            row.copy(weightLb = 40.0 + index * 5, done = index == 0, plannedIndex = index)
        }
        repo.writeTestRowsPaired(runId, "A", id, mainRows, partnerRows)
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val extended = mainRows + work().first().copy(weightLb = 75.0, reps = 7)
        assertTrue(repo.writeTestRows(runId, "A", id, Slot.MAIN, extended) is RunWriteResult.InRun)
        assertTrue(repo.restoreSkipped(runId, id, Slot.SS) is RunWriteResult.InRun)
        val restored = repo.openRunFlow.first()!!.tracks
        assertEquals(extended, restored.single { it.slot == Slot.MAIN && !it.retired }.sets)
        val partner = restored.single { it.slot == Slot.SS }
        assertFalse(partner.retired || partner.dropped)
        assertEquals(4, partner.sets.size)
        assertEquals(partnerRows, partner.sets.take(3))
        assertEquals(LoggedSet(50.0, 10, SetKind.WORK), partner.sets.last())
    }

    @Test fun `drop undo pads a shortened MAIN to the preserved SS row count`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId)
        val mainRows = work().mapIndexed { index, row ->
            row.copy(weightLb = 60.0 + index * 5, reps = 8 + index, done = index == 0, plannedIndex = index)
        }
        val partnerRows = SetSeeder.seedPartner(3, 45.0).mapIndexed { index, row ->
            row.copy(done = index == 2, plannedIndex = index)
        }
        repo.writeTestRowsPaired(runId, "A", id, mainRows, partnerRows)
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val shortened = mainRows.take(2)
        assertTrue(repo.writeTestRows(runId, "A", id, Slot.MAIN, shortened) is RunWriteResult.InRun)
        assertTrue(repo.restoreSkipped(runId, id, Slot.SS) is RunWriteResult.InRun)
        val restored = repo.openRunFlow.first()!!.tracks
        val main = restored.single { it.slot == Slot.MAIN && !it.retired }
        assertEquals(3, main.sets.size)
        assertEquals(shortened, main.sets.take(2))
        assertEquals(LoggedSet(65.0, 9, SetKind.WORK), main.sets.last())
        val partner = restored.single { it.slot == Slot.SS }
        assertFalse(partner.retired || partner.dropped)
        assertEquals(partnerRows, partner.sets)
    }

    @Test fun `drop undo pads an empty MAIN from its current prescription`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(65.0, 8)), sets = 3)
        assertTrue(repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        assertTrue(repo.writeTestRows(runId, "A", id, Slot.MAIN, emptyList()) is RunWriteResult.InRun)
        val before = db.runDao().tracks(runId).associateBy { it.slot }

        assertTrue(repo.restoreSkipped(runId, id, Slot.SS) is RunWriteResult.InRun)

        val restored = db.runDao().tracks(runId).associateBy { it.slot }
        assertEquals(List(3) { LoggedSet(65.0, 8, SetKind.WORK) },
            SetJson.decodeSets(restored.getValue(Slot.MAIN).setsJson))
        assertEquals(before.getValue(Slot.MAIN).plannedJson, restored.getValue(Slot.MAIN).plannedJson)
        assertEquals(before.getValue(Slot.SS).copy(retired = false, dropped = false), restored.getValue(Slot.SS))
    }

    @Test fun `stale paired write cannot edit MAIN or reopen dropped SS`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val drop = repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        val before = db.runDao().tracks(drop.runId)
        assertEquals(RunWriteResult.Rejected, repo.writeTestRowsPaired(drop.runId, "A", id, work().take(1), work().take(1)))
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
        seed(runId)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
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
        seed(runId)
        assertEquals(RunWriteResult.Rejected, repo.writeTestRows(runId, "A", id, Slot.MAIN, work().take(1)))
        assertEquals(before, db.runDao().tracks(runId))
        assertTrue(repo.restoreSkippedLift(runId, id) is RunWriteResult.InRun)

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

        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertFalse(tracks.single { it.slot == Slot.MAIN }.retired)
        assertFalse(tracks.single { it.slot == Slot.SS }.retired)
        assertTrue(tracks.none { it.dropped })
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(drop.runId, id, Slot.SS))
    }

    @Test fun `half bring back of skipped MAIN follows the structure edited while it was retired`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single { it.slot == Slot.MAIN }
        val current = SetJson.decodeSets(original.setsJson).mapIndexed { index, row ->
            row.copy(watchEditedAt = 100L + index)
        }
        db.runDao().updateTrack(original.copy(setsJson = SetJson.encodeSets(current)))
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val promoted = db.runDao().tracks(runId).single { !it.retired }
        val droppedMain = db.runDao().tracks(runId).single { it.id == original.id }

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        val expected = RunReplan.represcribe(current,
            SetSeeder.seedPrescribed(3, load, SetStructure.RAMP_TOP_BACKOFF, equipment = listOf(Equipment.BARBELL)))
        assertEquals(promoted, db.runDao().tracks(runId).single { !it.retired })
        assertEquals(droppedMain.copy(setsJson = SetJson.encodeSets(expected)),
            db.runDao().tracks(runId).single { it.id == original.id })
        val partnerBeforeRestore = db.runDao().tracks(runId).single { it.slot == Slot.SS }
        assertTrue(repo.restoreSkipped(runId, id, Slot.MAIN) is RunWriteResult.InRun)

        val restored = db.runDao().tracks(runId).filterNot { it.retired }.associateBy { it.slot }
        assertEquals(expected, SetJson.decodeSets(restored.getValue(Slot.MAIN).setsJson))
        assertEquals(original.plannedJson, restored.getValue(Slot.MAIN).plannedJson)
        assertEquals(partnerBeforeRestore.copy(retired = false, dropped = false), restored.getValue(Slot.SS))
        assertEquals(6, SetJson.decodeSets(restored.getValue(Slot.SS).setsJson).size)
        assertTrue(restored.values.none { it.dropped })
    }

    @Test fun `unchanged plan whole lift bring back restores byte identical to main including its existing replan`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        val runId = start(plan, sets = work())
        val current = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id }.sets.mapIndexed { index, row ->
            row.copy(weightLb = 70.0 + index, watchEditedAt = 100L + index)
        } + work().last().copy(weightLb = 90.0)
        repo.writeTestRows(runId, "A", id, Slot.MAIN, current)
        val original = db.runDao().tracks(runId).single { it.programExerciseId == id }
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null) is RunWriteResult.InRun)

        assertTrue(repo.restoreSkippedLift(runId, id) is RunWriteResult.InRun)

        assertEquals(original.copy(setsJson = SetJson.encodeSets(RunReplan.represcribe(current, work()))),
            db.runDao().tracks(runId).single { it.programExerciseId == id && !it.retired })
    }

    @Test fun `whole lift bring back follows a structure edited while MAIN was substituted`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = start(plan, sets = work())
        val original = db.runDao().tracks(runId).single { it.programExerciseId == id && it.slot == Slot.MAIN }
        val current = SetJson.decodeSets(original.setsJson)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat"))
        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(original.copy(retired = true, setsJson = SetJson.encodeSets(RunReplan.represcribe(current,
            SetSeeder.seedPrescribed(3, load, SetStructure.RAMP_TOP_BACKOFF, equipment = listOf(Equipment.BARBELL))))),
            db.runDao().tracks(runId).single { it.id == original.id })
        assertTrue(repo.skipLift(plan, "A", id, Slot.SS, "hack_squat", "ez_curl") is RunWriteResult.InRun)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "hack_squat", null) is RunWriteResult.InRun)

        assertTrue(repo.restoreSkippedLift(runId, id) is RunWriteResult.InRun)

        val restored = db.runDao().tracks(runId).filter { it.programExerciseId == id && !it.retired }
        val main = restored.single { it.slot == Slot.MAIN }
        assertEquals(RunReplan.represcribe(current,
            SetSeeder.seedPrescribed(3, load, SetStructure.RAMP_TOP_BACKOFF, equipment = listOf(Equipment.BARBELL))), SetJson.decodeSets(main.setsJson))
        assertEquals(original.plannedJson, main.plannedJson)
        assertEquals(6, SetJson.decodeSets(restored.single { it.slot == Slot.SS }.setsJson).size)
        assertTrue(restored.none { it.dropped })
    }

    @Test fun `whole lift restore pads MAIN at the prescription edited while skipped`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.addExercise("A", ProgramExercise("bb_bench"))
        repo.setPrescription(id, load, sets = 6)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = start(plan, sets = SetSeeder.seedPrescribed(6, load))
        val original = db.runDao().tracks(runId).single { it.programExerciseId == id && it.slot == Slot.MAIN }
        val mainRows = SetJson.decodeSets(original.setsJson).mapIndexed { index, row ->
            row.copy(watchEditedAt = 100L + index, startedAtMillis = 200L + index,
                completedAtMillis = (300L + index).takeIf { row.done })
        }
        val partnerRows = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && it.slot == Slot.SS }
            .sets.mapIndexed { index, row -> row.copy(done = index >= 3, watchEditedAt = 400L + index,
                startedAtMillis = 500L + index, completedAtMillis = (600L + index).takeIf { index >= 3 }) }
        assertEquals(6, partnerRows.size)
        assertTrue(repo.writeTestRowsPaired(runId, "A", id, mainRows, partnerRows) is RunWriteResult.InRun)
        assertTrue(repo.skipLift(plan, "A", id, Slot.SS, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null) is RunWriteResult.InRun)
        val changed = Prescription(PrescribedTarget.LoadReps(65.0, 8))
        repo.setPrescription(id, changed, sets = 3)
        val beforeRestore = db.runDao().tracks(runId).filter { it.programExerciseId == id }.associateBy { it.slot }
        assertEquals(3, SetJson.decodeSets(beforeRestore.getValue(Slot.MAIN).setsJson).size)

        assertTrue(repo.restoreSkippedLift(runId, id) is RunWriteResult.InRun)

        val restored = db.runDao().tracks(runId).filter { it.programExerciseId == id && !it.retired }.associateBy { it.slot }
        val main = restored.getValue(Slot.MAIN)
        assertEquals(RunReplan.represcribe(mainRows, SetSeeder.seedPrescribed(3, changed)) +
            List(3) { LoggedSet(65.0, 8, SetKind.WORK) }, SetJson.decodeSets(main.setsJson))
        assertEquals(original.plannedJson, main.plannedJson)
        assertEquals(partnerRows.filter { it.done }, SetJson.decodeSets(restored.getValue(Slot.SS).setsJson).filter { it.done })
        assertEquals(6, SetJson.decodeSets(restored.getValue(Slot.SS).setsJson).size)
        assertTrue(restored.values.none { it.dropped })
    }

    @Test fun `skip MAIN then promoted MAIN and clear checks reseeds the aligned plan with tied timestamps`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan, work() + work().take(1))
        seed(runId)
        val drop = repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "ez_curl", null) is RunWriteResult.InRun)
        assertTrue(repo.clearRunChecks(runId))
        assertEquals(setOf(clock.millis()), db.runDao().tracks(runId).map { it.updatedAt }.toSet())
        assertTrue(repo.restoreSkippedLift(drop.runId, id) is RunWriteResult.InRun)

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
        assertTrue(repo.writeTestRows(drop.runId, "A", id, Slot.MAIN, doneRows) is RunWriteResult.InRun)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "ez_curl", null) is RunWriteResult.InRun)
        assertTrue(repo.restoreSkippedLift(drop.runId, id) is RunWriteResult.InRun)

        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertEquals("bb_back_squat", tracks.single { it.slot == Slot.MAIN && !it.retired }.exerciseId)
        assertEquals(doneRows, tracks.single { it.id == promoted.id }.sets)
        assertTrue(tracks.single { it.id == promoted.id }.retired)
        assertTrue(tracks.none { it.dropped })
    }

    @Test fun `whole slot restore aligns a reseeded partner with an extended done MAIN`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_bench"))
        val id = slot(plan)
        repo.setSupersetPartner("A", id, "ez_curl")
        val skip = repo.skipLift(plan, "A", id, Slot.SS, "bb_back_squat", "ez_curl") as RunWriteResult.InRun
        val main = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && !it.retired }
        val extended = (main.sets + main.sets.last()).map { it.copy(done = true) }
        assertTrue(repo.writeTestRows(skip.runId, "A", id, Slot.MAIN, extended) is RunWriteResult.InRun)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null) is RunWriteResult.InRun)
        assertTrue(repo.restoreSkippedLift(skip.runId, id) is RunWriteResult.InRun)

        val active = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id && !it.retired }
        assertEquals(extended, active.single { it.slot == Slot.MAIN }.sets)
        assertEquals(extended.size, active.single { it.slot == Slot.SS }.sets.size)
        assertTrue(repo.openRunFlow.first()!!.tracks.none { it.dropped })
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
        seed(skip.runId)
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
        seed(runId)
        val done = work().mapIndexed { index, set -> set.copy(done = index == 0) }
        repo.writeTestRowsPaired(runId, "A", id, done, done)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        val promoted = repo.openRunFlow.first()!!.tracks.single { !it.retired }
        repo.writeTestRows(runId, "A", id, Slot.MAIN, done)
        repo.setSupersetPartner("A", id, "rope_pushdown")
        val retired = repo.openRunFlow.first()!!.tracks
        assertEquals(3, retired.size)
        assertTrue(retired.all { it.retired && !it.dropped })
        assertTrue(retired.all { it.sets == done })
        assertTrue(retired.any { it.id == promoted.id })
        seed(runId)
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
        seed(runId)
        val done = work().mapIndexed { index, set -> set.copy(done = index == 0) }
        repo.writeTestRowsPaired(runId, "A", id, done, done)
        assertTrue(repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", "ez_curl") is RunWriteResult.InRun)
        assertTrue(repo.removeExercise(id))
        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == id }
        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.retired && !it.dropped && it.sets == done })
        assertEquals(RunWriteResult.Rejected, repo.restoreSkipped(runId, id, Slot.MAIN))
    }

    @Test fun `phone edit creates an unseedable track in an open run with a cleared planned snapshot`() = runTest {
        val plan = generated(extraSlot = true)
        val runId = start(plan)
        val unknown = db.programDao().exercisesForDay(plan, "A").last().id
        val row = work().first().copy(done = true, completedAtMillis = 70L, watchEditedAt = 100L)
        val before = db.runDao().tracks(runId)
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(runId, plan, "A", listOf(
            TrackEdit(unknown, Slot.MAIN) { it + row },
        )))
        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(runId, plan, "A", listOf(
            TrackEdit(unknown, Slot.MAIN, createsMissing = true) { rows ->
                if (0 !in rows.indices) null else SetEditor.edit(rows, 0, SetEdit(weightLb = 200.0))
            },
        )))
        assertEquals(before, db.runDao().tracks(runId))
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, plan, "A", listOf(
            TrackEdit(unknown, Slot.MAIN, createsMissing = true) { it + row },
        )))
        val created = db.runDao().tracks(runId).single { it.programExerciseId == unknown }
        assertEquals(listOf(row), SetJson.decodeSets(created.setsJson))
        assertEquals(WorkingRows.cleared(listOf(row)),
            SetJson.decodeSets(created.plannedJson))
    }

    @Test fun `phone edit creates unseedable preview working values that explicit Start captures`() = runTest {
        val plan = generated(extraSlot = true)
        val unknown = db.programDao().exercisesForDay(plan, "A").last().id
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val row = LoggedSet(0.0, 0, SetKind.EXTRA)
        assertEquals(RunWriteResult.WorkingValues, repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(unknown, Slot.MAIN, createsMissing = true) { it + row },
        )))
        assertNull(db.runDao().openRun())
        assertEquals(listOf(row), SetJson.decodeSets(db.programDao().logForSlot(plan, "A", unknown, Slot.MAIN)!!.setsJson))
        assertEquals(RunStartResult.Started(next), repo.startRun(plan, "A"))
        val created = db.runDao().tracks(next).single { it.programExerciseId == unknown }
        assertEquals(listOf(row), SetJson.decodeSets(created.plannedJson))
        assertEquals(listOf(row.copy(plannedIndex = 0)), SetJson.decodeSets(created.setsJson))
    }

    @Test fun `phone first tick creates an unseedable track and captures its cleared rows`() = runTest {
        val plan = generated(extraSlot = true)
        val unknown = db.programDao().exercisesForDay(plan, "A").last().id
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val row = work().first().copy(done = true, completedAtMillis = 70L)
        assertEquals(RunWriteResult.InRun(next, true), repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(unknown, Slot.MAIN, createsMissing = true) { it + row },
        )))
        val created = db.runDao().tracks(next).single { it.programExerciseId == unknown }
        assertEquals(listOf(row.copy(plannedIndex = 0)), SetJson.decodeSets(created.setsJson))
        assertEquals(WorkingRows.cleared(listOf(row)),
            SetJson.decodeSets(created.plannedJson))
        assertTrue(db.runDao().tracks(next).any { it.programExerciseId == slot(plan) })
    }

    /** #406: a phone edit written from a read taken before a watch tick must not undo the tick. */
    @Test fun `a phone edit written from a stale read keeps a watch tick that landed in between`() = runTest {
        val plan = generated()
        val slot = slot(plan)
        val runId = start(plan)
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, plan, "A", listOf(
            TrackEdit(slot, Slot.MAIN) { rows ->
                rows.mapIndexed { i, row -> if (i == 1) row.copy(done = true, completedAtMillis = 1_000L, watchEditedAt = 200L) else row }
            },
        )))
        repo.applyToRun(runId, plan, "A", listOf(
            TrackEdit(slot, Slot.MAIN) { rows -> SetEditor.edit(rows, 2, SetEdit(weightLb = 200.0)) },
        ))

        val after = SetJson.decodeSets(db.runDao().tracks(runId).single().setsJson)
        assertEquals(200.0, after[2].weightLb, 0.0)
        assertTrue(after[1].done)
        assertEquals(200L, after[1].watchEditedAt)
    }

    @Test fun `a phone edit that produces no rows does not create an unseedable track`() = runTest {
        val plan = generated(extraSlot = true)
        val unknown = db.programDao().exercisesForDay(plan, "A").last().id
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val empty = TrackEdit(unknown, Slot.MAIN, createsMissing = true) { it }

        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(next, plan, "A", listOf(empty)))
        assertNull(db.programDao().logForSlot(plan, "A", unknown, Slot.MAIN))

        val runId = start(plan)
        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(runId, plan, "A", listOf(empty)))
        assertTrue(db.runDao().tracks(runId).none { it.programExerciseId == unknown })
    }

    @Test fun `picking the current structure again changes nothing`() = runTest {
        val plan = generated()
        val id = slot(plan)
        val runId = start(plan)
        val tracks = db.runDao().tracks(runId)
        val logs = db.programDao().logsForDay(plan, "A")
        assertTrue(logs.any { it.programExerciseId == id })

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        assertEquals(tracks, db.runDao().tracks(runId))
        assertEquals(logs, db.programDao().logsForDay(plan, "A"))
    }

    @Test fun `structure growth pads a substituted partner to the new row count`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.setStructure(id, SetStructure.STRAIGHT)
        repo.setSupersetPartner("A", id, "ez_curl")
        val runId = explicitStart(plan)
        seed(runId)
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "rope_pushdown"))
        val partnerRows = SetJson.decodeSets(db.runDao().tracks(runId).single { it.slot == Slot.SS && !it.retired }.setsJson)
            .mapIndexed { index, row -> if (index == 2) row.copy(weightLb = 70.0, reps = 9, kind = SetKind.EXTRA,
                done = true, watchEditedAt = 100L, startedAtMillis = 200L, completedAtMillis = 300L) else row }
        assertTrue(repo.writeTestRows(runId, "A", id, Slot.SS, partnerRows) is RunWriteResult.InRun)

        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)

        val active = db.runDao().tracks(runId).filter { !it.retired }
        val main = SetJson.decodeSets(active.single { it.slot == Slot.MAIN }.setsJson)
        val partner = SetJson.decodeSets(active.single { it.slot == Slot.SS }.setsJson)
        assertEquals(6, main.size)
        assertEquals(main.size, partner.size)
        assertEquals(partnerRows, partner.take(partnerRows.size))
        assertEquals(List(3) { LoggedSet(70.0, 9, SetKind.EXTRA) }, partner.drop(partnerRows.size))
    }
}
