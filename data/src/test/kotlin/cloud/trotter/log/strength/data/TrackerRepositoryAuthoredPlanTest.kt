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
import cloud.trotter.log.strength.domain.library.TrackingType
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
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
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
        val plan = repo.plans.createAuthoredPlan("Running", "Run")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("plank"))
        val other = repo.plans.createAuthoredPlan("Other", "Other day")
        repo.plans.publishCreatedAuthoredPlan(other)
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        ))
        assertTrue(repo.setCardioBlock(plan, "A", block))
        assertEquals(block, repo.plans.programFor(plan).first().days.single().cardioBlock)
        assertNull(repo.plans.programFor(other).first().days.single().cardioBlock)
        assertTrue(repo.setCardioBlock(plan, "A", null))
        assertNull(repo.plans.programFor(plan).first().days.single().cardioBlock)
        assertEquals("plank", repo.plans.programFor(plan).first().days.single().exercises.single().exerciseId)
    }

    @Test
    fun `generated cardio block can be edited and removed when an exercise remains`() = runTest {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", listOf(ProgramExercise("plank")), cardioBlock = block))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        assertTrue(repo.setCardioBlock(plan, "A", block.copy(activity = CardioActivity.ROWER)))
        assertEquals(block.copy(activity = CardioActivity.ROWER), repo.plans.programFor(plan).first().days.single().cardioBlock)
        assertTrue(repo.setCardioBlock(plan, "A", null))
        assertNull(repo.plans.programFor(plan).first().days.single().cardioBlock)
    }

    @Test
    fun `removing the last exercise prevents removal of the remaining cardio block`() = runTest {
        val plan = repo.plans.createAuthoredPlan("Running", "Run")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("plank"))
        val slot = repo.plans.daySlotsFlow("A").first().single().programExerciseId
        assertFalse(repo.removeExercise(slot))
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        assertTrue(repo.setCardioBlock(plan, "A", block))
        assertTrue(repo.removeExercise(slot))
        assertFalse(repo.setCardioBlock(plan, "A", null))
        assertTrue(repo.plans.programFor(plan).first().days.single().exercises.isEmpty())
        assertEquals(block, repo.plans.programFor(plan).first().days.single().cardioBlock)
    }

    @Test
    fun `removing cardio prevents removal of the last remaining exercise`() = runTest {
        val plan = repo.plans.createAuthoredPlan("Running", "Run")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("plank"))
        val slot = repo.plans.daySlotsFlow("A").first().single().programExerciseId
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        assertTrue(repo.setCardioBlock(plan, "A", block))
        assertTrue(repo.setCardioBlock(plan, "A", null))
        assertFalse(repo.removeExercise(slot))
        val day = repo.plans.programFor(plan).first().days.single()
        assertNull(day.cardioBlock)
        assertEquals("plank", day.exercises.single().exerciseId)
    }

    @Test
    fun `cardio writes revalidate domain requirements before storing`() = runTest {
        val plan = repo.plans.createAuthoredPlan("Running", "Run")
        val sets = mutableListOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500)))
        val block = CardioBlock(CardioActivity.ROAD_RUN, sets)
        sets.clear()
        val result = runCatching { repo.setCardioBlock(plan, "A", block) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertNull(repo.plans.programFor(plan).first().days.single().cardioBlock)
    }

    @Test
    fun `create and publish an empty authored plan preserve the global lifter and existing selection`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", emptyList()))))
        repo.plans.updateLifter { it.copy(bodyweightLb = 193.0, age = 47) }
        val global = settings.globalLifterFlow.first()
        val selected = settings.selectedPlanIdFlow.first()
        val id = repo.plans.createAuthoredPlan("  Physio  ", "Day A")
        val plan = db.planDao().plan(id)!!
        assertEquals("Physio", plan.name)
        assertEquals(PlanOrigin.AUTHORED.name, plan.origin)
        assertNull(plan.recipeJson)
        assertEquals(1, plan.position)
        assertEquals("A", plan.suggestedDayId)
        assertEquals(selected, settings.selectedPlanIdFlow.first())
        val day = repo.plans.programFor(id).first().days.single()
        assertEquals("A", day.id)
        assertEquals("Day A", day.title)
        assertTrue(day.exercises.isEmpty())

        repo.plans.publishCreatedAuthoredPlan(id)

        assertEquals(id, settings.selectedPlanIdFlow.first())
        assertEquals(global, settings.globalLifterFlow.first())
    }

    @Test
    fun `new plans start at the first cardio training day in mixed and cardio-only programs`() = runTest {
        val mixed = mixedProgram()
        val cardioOnly = Program(mixed.days.filter { it.exercises.isEmpty() })
        for (program in listOf(mixed, cardioOnly)) {
            val id = repo.plans.createGeneratedPlan("Training", WizardAnswers(), program)

            assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
            assertEquals(program, repo.plans.programFor(id).first())
        }
    }

    @Test
    fun `filling a settings-only plan starts at the first cardio training day`() = runTest {
        repo.plans.setWizardAnswers(WizardAnswers())
        val id = settings.selectedPlanIdFlow.first()!!

        assertEquals(id, repo.plans.createGeneratedPlan("Training", WizardAnswers(), mixedProgram()))

        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
    }

    @Test
    fun `replacement starts at the first cardio training day and skips extras`() = runTest {
        repo.replaceProgram(mixedProgram())
        val id = settings.selectedPlanIdFlow.first()!!
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
        val cardioOnly = Program(mixedProgram().days.filter { it.id == "C" })

        repo.replaceProgram(cardioOnly)

        assertEquals("C", db.planDao().plan(id)!!.suggestedDayId)
    }

    @Test
    fun `removing a suggested lift selects the next cardio training day and skips extras`() = runTest {
        repo.replaceProgram(mixedProgram())
        val id = settings.selectedPlanIdFlow.first()!!
        db.planDao().updateSuggestedDay(id, "B")

        assertTrue(repo.removeDay(id, "B"))
        assertEquals("C", db.planDao().plan(id)!!.suggestedDayId)
        assertTrue(repo.removeDay(id, "C"))
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
    }

    @Test
    fun `add days uses unused letters appends positions and stops at seven`() = runTest {
        val id = repo.plans.createAuthoredPlan("Prep", "Day A")
        for (letter in 'B'..'G') assertEquals(letter.toString(), repo.addDay(id, "Day $letter"))
        assertNull(repo.addDay(id, "Day H"))
        assertEquals((0..6).toList(), db.programDao().daysForPlan(id).map { it.position })
        assertTrue(repo.removeDay(id, "C"))
        assertEquals("C", repo.addDay(id, "Day C"))
        assertEquals("C", db.programDao().daysForPlan(id).last().dayId)
    }

    @Test
    fun `rename accepts trimmed one to thirty characters and ignores invalid titles`() = runTest {
        val id = repo.plans.createAuthoredPlan("Prep", "Day A")
        for (invalid in listOf("", "   ", "x".repeat(31))) repo.renameDay(id, "A", invalid)
        assertEquals("Day A", db.programDao().day(id, "A")!!.title)
        repo.renameDay(id, "A", " X ")
        assertEquals("X", db.programDao().day(id, "A")!!.title)
        repo.renameDay(id, "A", "x".repeat(30))
        assertEquals(30, db.programDao().day(id, "A")!!.title.length)
    }

    @Test
    fun `remove refuses the last day deletes live work and keeps session history`() = runTest {
        val id = repo.plans.createAuthoredPlan("Physio", "Day A")
        repo.plans.publishCreatedAuthoredPlan(id)
        assertFalse(repo.removeDay(id, "A"))
        repo.addDay(id, "Day B")
        repo.addDay(id, "Day C")
        repo.addExercise("B", ProgramExercise("plank"))
        val slot = repo.plans.daySlotsFlow("B").first().single().programExerciseId
        val sets = listOf(LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 30))
        repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slot) ?: -1, "B").first().runId.orEmpty(), "B", slot, Slot.MAIN, sets)
        val session = repo.finishRun(repo.openRunFlow.first()!!.id)!!
        val archivedSets = repo.history.sessionSets(session)
        repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slot) ?: -1, "B").first().runId.orEmpty(), "B", slot, Slot.MAIN, sets)
        db.planDao().updateSuggestedDay(id, "B")
        repo.addExercise("A", ProgramExercise("plank"))
        val otherDay = db.programDao().exercisesForDay(id, "A")

        assertTrue(repo.removeDay(id, "B"))

        assertEquals("C", db.planDao().plan(id)!!.suggestedDayId)
        assertTrue(db.programDao().logsForDay(id, "B").isEmpty())
        assertTrue(db.programDao().exercisesForDay(id, "B").isEmpty())
        assertNull(db.programDao().day(id, "B"))
        assertEquals(otherDay, db.programDao().exercisesForDay(id, "A"))
        assertEquals(archivedSets, repo.history.sessionSets(session))
        assertEquals("Day B", db.sessionDao().sessionById(session)!!.dayTitle)
        assertTrue(repo.removeDay(id, "C"))
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
        assertFalse(repo.removeDay(id, "A"))
    }

    @Test
    fun `prescription is slot owned writes sets and json and clears only its logs`() = runTest {
        val id = repo.plans.createAuthoredPlan("Physio", "Day A")
        repo.plans.publishCreatedAuthoredPlan(id)
        repeat(2) { repo.addExercise("A", ProgramExercise("plank")) }
        val slots = repo.plans.daySlotsFlow("A").first()
        for (slot in slots) repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slot.programExerciseId) ?: -1, "A").first().runId.orEmpty(), "A", slot.programExerciseId, Slot.MAIN, listOf(LoggedSet(0.0, 10, SetKind.WORK)))
        repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slots.first().programExerciseId) ?: -1, "A").first().runId.orEmpty(), "A", slots.first().programExerciseId, Slot.SS, emptyList())
        val other = repo.plans.createAuthoredPlan("Other", "Day A")
        repo.plans.publishCreatedAuthoredPlan(other)
        val prescription = Prescription(PrescribedTarget.RepsInTime(120, 40), true, "Red band")

        repo.setPrescription(slots.first().programExerciseId, prescription, 5)

        val row = db.programDao().exercise(slots.first().programExerciseId)!!
        assertEquals(5, row.targetSets)
        assertEquals(prescription, PrescriptionJson.decode(row.prescriptionJson))
        assertEquals(listOf(slots.last().programExerciseId), db.programDao().logsForPlan(id).map { it.programExerciseId })
    }

    @Test
    fun `generated plan supports day mutations prescriptions and removal down to one exercise`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", List(3) { ProgramExercise("plank") }))))
        val id = settings.selectedPlanIdFlow.first()!!
        val slots = repo.plans.daySlotsFlow("A").first()
        assertEquals("B", repo.addDay(id, "Day B"))
        repo.renameDay(id, "A", "Renamed")
        assertEquals("Renamed", db.programDao().day(id, "A")!!.title)
        val prescription = Prescription(PrescribedTarget.Reps(15))
        repo.setPrescription(slots[0].programExerciseId, prescription, 4)
        assertEquals(prescription, repo.plans.daySlotsFlow("A").first()[0].exercise.prescription)
        assertEquals(4, repo.plans.daySlotsFlow("A").first()[0].exercise.targetSets)
        assertTrue(repo.removeExercise(slots[1].programExerciseId))
        assertTrue(repo.removeExercise(slots[2].programExerciseId))
        assertFalse(repo.removeExercise(slots[0].programExerciseId))
        assertTrue(repo.removeDay(id, "B"))
        assertFalse(repo.removeDay(id, "A"))
    }

    @Test
    fun `six generated strength days plus two cardio days can add a seventh strength day`() = runTest {
        val program = ProgramGenerator.generate(WizardAnswers(
            daysPerWeek = 6, cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        ))
        assertEquals(6, program.strengthDays.size)
        assertEquals(listOf("C1", "C2"), program.cardioDays.map { it.id })
        repo.replaceProgram(program)
        val id = settings.selectedPlanIdFlow.first()!!
        assertEquals("G", repo.addDay(id, "Day G"))
        assertNull(repo.addDay(id, "Day H"))
        assertEquals(9, repo.plans.programFor(id).first().days.size)
        assertEquals(7, repo.plans.programFor(id).first().strengthDays.size)
    }

    @Test
    fun `removing suggested B skips standalone cardio days`() = runTest {
        val strength = listOf("A", "B").map { ProgramDay(it, "Day $it", "", emptyList()) }
        val cardio = listOf("C1", "C2").map {
            ProgramDay(it, "Cardio", "", emptyList(), kind = ProgramDayKind.CARDIO)
        }
        repo.replaceProgram(Program(strength + cardio))
        val id = settings.selectedPlanIdFlow.first()!!
        db.planDao().updateSuggestedDay(id, "B")
        assertTrue(repo.removeDay(id, "B"))
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
        assertTrue(repo.removeDay(id, "A"))
        assertEquals("C1", db.planDao().plan(id)!!.suggestedDayId)
        assertTrue(repo.removeDay(id, "C1"))
        assertEquals("C2", db.planDao().plan(id)!!.suggestedDayId)
    }

    @Test
    fun `move swaps neighbours in either origin and stops at ends without changing logs`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", List(3) { ProgramExercise("plank") }))))
        val generated = settings.selectedPlanIdFlow.first()!!
        val authored = repo.plans.createAuthoredPlan("Physio", "Day A")
        repo.plans.publishCreatedAuthoredPlan(authored)
        repeat(3) { repo.addExercise("A", ProgramExercise("plank")) }
        for (id in listOf(generated, authored)) {
            val slots = db.programDao().exercisesForDay(id, "A").map { it.id }
            repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slots[1]) ?: -1, "A").first().runId.orEmpty(), "A", slots[1], Slot.MAIN, listOf(LoggedSet(0.0, 10, SetKind.WORK)))
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
    fun `added authored slots keep supplied counts and seed from goal math without prescriptions`() = runTest {
        val id = repo.plans.createAuthoredPlan("Physio", "Day A")
        repo.plans.publishCreatedAuthoredPlan(id)
        val flat = repo.catalog.addCustomExercise("Flat", cloud.trotter.log.strength.domain.model.MovementPattern.H_PUSH, emptyList(), false, 17.5)
        val reps = repo.catalog.addCustomExercise("Reps", cloud.trotter.log.strength.domain.model.MovementPattern.H_PUSH, emptyList(), false, 0.0, TrackingType.REPS, targetReps = 14)
        val timed = repo.catalog.addCustomExercise("Hold", cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_EXT, emptyList(), false, 5.0, TrackingType.TIMED, targetSeconds = 45)
        val exercises = listOf(flat, reps, timed, "bb_back_squat").mapIndexed { index, exercise ->
            ProgramExercise(exercise, targetSets = index + 1)
        }
        exercises.forEach { repo.addExercise("A", it) }
        val slots = repo.plans.daySlotsFlow("A").first()
        assertEquals(exercises, slots.map { it.exercise })
        assertTrue(slots.all { it.exercise.prescription == null && !it.exercise.keepToPlan })
        val config = repo.plans.configFlow.first()
        val catalog = repo.catalog.catalogFlow.first()
        repo.ensureSeeded(id, "A")
        for (slot in slots) {
            val expected = SetSeeder.seed(slot.exercise, GoalCalculator.targetFor(catalog.get(slot.exercise.exerciseId), config), config)
            val actual = repo.dayTracksFlow(id, "A").first().tracks.single { it.programExerciseId == slot.programExerciseId }.sets
            assertEquals(expected, actual)
            assertTrue(actual.first().weightLb > 0.0 || actual.first().reps > 0 || actual.first().seconds > 0)
        }
    }

    private fun mixedProgram(): Program {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        val extras = ProgramGenerator.generate(WizardAnswers(
            cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        )).cardioDays
        return Program(listOf(
            extras[0],
            ProgramDay("A", "Run", "", emptyList(), kind = ProgramDayKind.CARDIO, cardioBlock = block),
            ProgramDay("B", "Lift", "", listOf(ProgramExercise("bb_back_squat"))),
            extras[1],
            ProgramDay("C", "Run", "", emptyList(), kind = ProgramDayKind.CARDIO, cardioBlock = block),
        ))
    }
}
