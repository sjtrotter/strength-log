package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryAuthoredPlanTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("authored-plan", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `cardio block writes and removal belong to the authored day even after selection changes`() = runTest {
        val plan = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("plank"))
        val other = repo.createAuthoredPlan("Other", "Other day")
        repo.publishCreatedAuthoredPlan(other)
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        ))
        assertTrue(repo.setCardioBlock(plan, "A", block))
        assertEquals(block, repo.programFor(plan).first().days.single().cardioBlock)
        assertNull(repo.programFor(other).first().days.single().cardioBlock)
        assertTrue(repo.setCardioBlock(plan, "A", null))
        assertNull(repo.programFor(plan).first().days.single().cardioBlock)
        assertEquals("plank", repo.programFor(plan).first().days.single().exercises.single().exerciseId)
    }

    @Test
    fun `generated cardio block cannot be edited or removed`() = runTest {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", emptyList(), null, cardioBlock = block))))
        val plan = repo.selectedPlanIdFlow.first()!!
        assertFalse(repo.setCardioBlock(plan, "A", block.copy(activity = CardioActivity.ROWER)))
        assertEquals(block, repo.programFor(plan).first().days.single().cardioBlock)
        assertFalse(repo.setCardioBlock(plan, "A", null))
        assertEquals(block, repo.programFor(plan).first().days.single().cardioBlock)
    }

    @Test
    fun `removing the last exercise prevents removal of the remaining cardio block`() = runTest {
        val plan = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("plank"))
        val slot = repo.daySlotsFlow("A").first().single().programExerciseId
        assertFalse(repo.removeExercise(slot))
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        assertTrue(repo.setCardioBlock(plan, "A", block))
        assertTrue(repo.removeExercise(slot))
        assertFalse(repo.setCardioBlock(plan, "A", null))
        assertTrue(repo.programFor(plan).first().days.single().exercises.isEmpty())
        assertEquals(block, repo.programFor(plan).first().days.single().cardioBlock)
    }

    @Test
    fun `removing cardio prevents removal of the last remaining exercise`() = runTest {
        val plan = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("plank"))
        val slot = repo.daySlotsFlow("A").first().single().programExerciseId
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        assertTrue(repo.setCardioBlock(plan, "A", block))
        assertTrue(repo.setCardioBlock(plan, "A", null))
        assertFalse(repo.removeExercise(slot))
        val day = repo.programFor(plan).first().days.single()
        assertNull(day.cardioBlock)
        assertEquals("plank", day.exercises.single().exerciseId)
    }

    @Test
    fun `cardio writes revalidate domain requirements before storing`() = runTest {
        val plan = repo.createAuthoredPlan("Running", "Run")
        val sets = mutableListOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500)))
        val block = CardioBlock(CardioActivity.ROAD_RUN, sets)
        sets.clear()
        val result = runCatching { repo.setCardioBlock(plan, "A", block) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertNull(repo.programFor(plan).first().days.single().cardioBlock)
    }

    @Test
    fun `create and publish an empty authored plan preserve the global lifter and existing selection`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", emptyList(), null))))
        repo.setConfig(LifterConfig(bodyweightLb = 193, age = 47))
        val global = settings.globalLifterFlow.first()
        val selected = settings.selectedPlanIdFlow.first()
        val id = repo.createAuthoredPlan("  Physio  ", "Day A")
        val plan = db.planDao().plan(id)!!
        assertEquals("Physio", plan.name)
        assertEquals(PlanOrigin.AUTHORED.name, plan.origin)
        assertNull(plan.recipeJson)
        assertEquals(1, plan.position)
        assertEquals("A", plan.suggestedDayId)
        assertEquals(selected, settings.selectedPlanIdFlow.first())
        val day = repo.programFor(id).first().days.single()
        assertEquals("A", day.id)
        assertEquals("Day A", day.title)
        assertTrue(day.exercises.isEmpty())

        repo.publishCreatedAuthoredPlan(id)

        assertEquals(id, settings.selectedPlanIdFlow.first())
        assertEquals(global, settings.globalLifterFlow.first())
    }

    @Test
    fun `add days uses unused letters appends positions and stops at seven`() = runTest {
        val id = repo.createAuthoredPlan("Prep", "Day A")
        for (letter in 'B'..'G') assertEquals(letter.toString(), repo.addDay(id, "Day $letter"))
        assertNull(repo.addDay(id, "Day H"))
        assertEquals((0..6).toList(), db.programDao().daysForPlan(id).map { it.position })
        assertTrue(repo.removeDay(id, "C"))
        assertEquals("C", repo.addDay(id, "Day C"))
        assertEquals("C", db.programDao().daysForPlan(id).last().dayId)
    }

    @Test
    fun `rename accepts trimmed one to thirty characters and ignores invalid titles`() = runTest {
        val id = repo.createAuthoredPlan("Prep", "Day A")
        for (invalid in listOf("", "   ", "x".repeat(31))) repo.renameDay(id, "A", invalid)
        assertEquals("Day A", db.programDao().day(id, "A")!!.title)
        repo.renameDay(id, "A", " X ")
        assertEquals("X", db.programDao().day(id, "A")!!.title)
        repo.renameDay(id, "A", "x".repeat(30))
        assertEquals(30, db.programDao().day(id, "A")!!.title.length)
    }

    @Test
    fun `remove refuses the last day deletes live work and keeps session history`() = runTest {
        val id = repo.createAuthoredPlan("Physio", "Day A")
        repo.publishCreatedAuthoredPlan(id)
        assertFalse(repo.removeDay(id, "A"))
        repo.addDay(id, "Day B")
        repo.addDay(id, "Day C")
        repo.addExercise("B", ProgramExercise("plank"))
        val slot = repo.daySlotsFlow("B").first().single().programExerciseId
        val sets = listOf(LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 30))
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot) ?: -1, "B").first().runId.orEmpty(), "B", slot, Slot.MAIN, sets)
        val session = repo.finishRun(repo.openRunFlow.first()!!.id)!!
        val archivedSets = repo.sessionSets(session)
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot) ?: -1, "B").first().runId.orEmpty(), "B", slot, Slot.MAIN, sets)
        db.planDao().updateSuggestedDay(id, "B")
        repo.addExercise("A", ProgramExercise("plank"))
        val otherDay = db.programDao().exercisesForDay(id, "A")

        assertTrue(repo.removeDay(id, "B"))

        assertEquals("C", db.planDao().plan(id)!!.suggestedDayId)
        assertTrue(db.programDao().logsForDay(id, "B").isEmpty())
        assertTrue(db.programDao().exercisesForDay(id, "B").isEmpty())
        assertNull(db.programDao().day(id, "B"))
        assertEquals(otherDay, db.programDao().exercisesForDay(id, "A"))
        assertEquals(archivedSets, repo.sessionSets(session))
        assertEquals("Day B", db.sessionDao().sessionById(session)!!.dayTitle)
        assertTrue(repo.removeDay(id, "C"))
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
        assertFalse(repo.removeDay(id, "A"))
    }

    @Test
    fun `prescription is slot owned writes sets and json and clears only its logs`() = runTest {
        val id = repo.createAuthoredPlan("Physio", "Day A")
        repo.publishCreatedAuthoredPlan(id)
        repeat(2) { repo.addExercise("A", ProgramExercise("plank")) }
        val slots = repo.daySlotsFlow("A").first()
        for (slot in slots) repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot.programExerciseId) ?: -1, "A").first().runId.orEmpty(), "A", slot.programExerciseId, Slot.MAIN, listOf(LoggedSet(0.0, 10, SetKind.WORK)))
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slots.first().programExerciseId) ?: -1, "A").first().runId.orEmpty(), "A", slots.first().programExerciseId, Slot.SS, emptyList())
        val other = repo.createAuthoredPlan("Other", "Day A")
        repo.publishCreatedAuthoredPlan(other)
        val prescription = Prescription(PrescribedTarget.RepsInTime(120, 40), true, "Red band")

        repo.setPrescription(slots.first().programExerciseId, prescription, 5)

        val row = db.programDao().exercise(slots.first().programExerciseId)!!
        assertEquals(5, row.targetSets)
        assertEquals(prescription, PrescriptionJson.decode(row.prescriptionJson))
        assertEquals(listOf(slots.last().programExerciseId), db.programDao().logsForPlan(id).map { it.programExerciseId })
    }

    @Test
    fun `generated plan refuses authored day mutations and prescriptions`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", listOf(ProgramExercise("plank")), null))))
        val id = settings.selectedPlanIdFlow.first()!!
        val slot = repo.daySlotsFlow("A").first().single().programExerciseId
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot) ?: -1, "A").first().runId.orEmpty(), "A", slot, Slot.MAIN, listOf(LoggedSet(0.0, 10, SetKind.WORK)))
        val before = db.programDao().exercise(slot)
        val logs = db.programDao().allLogs()
        assertNull(repo.addDay(id, "Day B"))
        repo.renameDay(id, "A", "Renamed")
        assertFalse(repo.removeDay(id, "A"))
        repo.setPrescription(slot, Prescription(PrescribedTarget.Reps(15)), 4)
        assertEquals(before, db.programDao().exercise(slot))
        assertEquals(logs, db.programDao().allLogs())
        assertEquals("Generated", db.programDao().day(id, "A")!!.title)
    }

    @Test
    fun `move swaps neighbours in either origin and stops at ends without changing logs`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", List(3) { ProgramExercise("plank") }, null))))
        val generated = settings.selectedPlanIdFlow.first()!!
        val authored = repo.createAuthoredPlan("Physio", "Day A")
        repo.publishCreatedAuthoredPlan(authored)
        repeat(3) { repo.addExercise("A", ProgramExercise("plank")) }
        for (id in listOf(generated, authored)) {
            val slots = db.programDao().exercisesForDay(id, "A").map { it.id }
            repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slots[1]) ?: -1, "A").first().runId.orEmpty(), "A", slots[1], Slot.MAIN, listOf(LoggedSet(0.0, 10, SetKind.WORK)))
            val logs = db.programDao().allLogs()
            repo.moveExercise(slots.first(), up = true)
            repo.moveExercise(slots.last(), up = false)
            assertEquals(slots, db.programDao().exercisesForDay(id, "A").map { it.id })
            repo.moveExercise(slots[1], up = true)
            assertEquals(listOf(slots[1], slots[0], slots[2]), db.programDao().exercisesForDay(id, "A").map { it.id })
            repo.moveExercise(slots[1], up = false)
            assertEquals(slots, db.programDao().exercisesForDay(id, "A").map { it.id })
            assertEquals(logs, db.programDao().allLogs())
        }
    }

    @Test
    fun `added authored slots use catalog defaults for every tracking type`() = runTest {
        val id = repo.createAuthoredPlan("Physio", "Day A")
        repo.publishCreatedAuthoredPlan(id)
        val flat = repo.addCustomExercise("Flat", cloud.trotter.log.strength.domain.model.MovementPattern.H_PUSH, emptyList(), false, 17.5)
        val reps = repo.addCustomExercise("Reps", cloud.trotter.log.strength.domain.model.MovementPattern.H_PUSH, emptyList(), false, 0.0, TrackingType.REPS, targetReps = 14)
        val timed = repo.addCustomExercise("Hold", cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_EXT, emptyList(), false, 5.0, TrackingType.TIMED, targetSeconds = 45)
        for (exercise in listOf(flat, reps, timed, "bb_back_squat")) repo.addExercise("A", ProgramExercise(exercise))
        val slots = repo.daySlotsFlow("A").first()
        assertEquals(listOf(
            PrescribedTarget.LoadReps(17.5, 10), PrescribedTarget.Reps(14), PrescribedTarget.Hold(45, 5.0), PrescribedTarget.LoadReps(0.0, 10),
        ), slots.map { it.exercise.prescription!!.target })
        assertTrue(slots.all { it.exercise.targetSets == 3 })
        assertTrue(repo.catalogFlow.first().find(flat)!!.goal is GoalSource.Flat)
    }
}
