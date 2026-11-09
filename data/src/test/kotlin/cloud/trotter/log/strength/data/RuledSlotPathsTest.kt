package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuledSlotPathsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("ruled-slot-paths", ".preferences_pb")
        })
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        repo = repository()
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun repository(dao: ProgramDao = db.programDao()) =
        TrackerRepository(db, dao, db.sessionDao(), db.customExerciseDao(), settings)
    private fun rule(role: SlotRole, reps: Int, inc: Double, scheme: Scheme = Scheme.STRENGTH,
                     calibrating: Boolean = false) =
        SlotRule(role, RepTarget.Fixed(reps), 2, inc, 1, 0.05, scheme, state = SlotState(calibrating = calibrating))
    private fun work(weight: Double, reps: Int = 10) = List(3) { LoggedSet(weight, reps, SetKind.WORK) }
    private suspend fun authored(): Long = repo.plans.createAuthoredPlan("Mine", "Day A").also {
        repo.plans.publishCreatedAuthoredPlan(it)
    }
    private suspend fun assertRuledWithRows(planId: Long) {
        val slots = db.programDao().exercisesForPlan(planId)
        assertTrue(slots.isNotEmpty())
        for (slot in slots) {
            assertNotNull(SlotRuleJson.decode(slot.ruleJson))
            if (!slot.keepToPlan) assertNotNull(db.programDao().logForSlot(planId, slot.dayId, slot.id, Slot.MAIN))
        }
    }
    private suspend fun assertSlot(id: Long, expected: SlotRule, rows: List<LoggedSet>) {
        val slot = assertNotNull(db.programDao().exercise(id))
        assertEquals(expected, SlotRuleJson.decode(slot.ruleJson))
        assertEquals(rows, SetJson.decodeSets(assertNotNull(db.programDao().logForSlot(slot.planId, slot.dayId, id, Slot.MAIN)).setsJson))
    }

    @Test fun `an authored plan an added lift a swap and a reset generated day keep every slot ruled with rows`() = runTest {
        val plan = authored()
        assertTrue(db.planDao().plan(plan)!!.autoApply)
        assertTrue(db.programDao().exercisesForPlan(plan).isEmpty())
        repo.addExercise("A", ProgramExercise("hack_squat"), plan)
        val id = db.programDao().exercisesForPlan(plan).single().id
        assertSlot(id, rule(SlotRole.ACCESSORY, 10, 0.025, calibrating = true), work(20.0))
        assertRuledWithRows(plan)
        assertTrue(repo.swapExerciseById("A", id, "bb_back_squat"))
        assertSlot(id, rule(SlotRole.MAIN, 5, 0.025, calibrating = true), work(45.0, 5))
        assertRuledWithRows(plan)
        val answers = WizardAnswers()
        val gen = repo.plans.createGeneratedPlan("Gen", answers, ProgramGenerator.generate(answers))
        repo.plans.publishCreatedPlan(gen, answers)
        assertRuledWithRows(gen)
        val ids = db.programDao().exercisesForDay(gen, "A").map { it.id }
        repo.resetDayToTemplate("A", gen)
        assertTrue(db.programDao().exercisesForDay(gen, "A").none { it.id in ids })
        assertRuledWithRows(gen)
    }

    @Test fun `the old wizard's createGeneratedPlan produces ruled slots`() = runTest {
        val squat = ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF, 6)
        val hack = ProgramExercise("hack_squat")
        val pull = ProgramExercise("pullup")
        val bench = ProgramExercise("bb_bench", prescription = Prescription(PrescribedTarget.LoadReps(135.0, 5)))
        val gen = repo.plans.createGeneratedPlan("Gen", WizardAnswers(),
            Program(listOf(ProgramDay("A", "Day A", "", listOf(squat, hack, pull, bench)))))
        val ids = db.programDao().exercisesForPlan(gen).map { it.id }
        assertSlot(ids[0], rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE, true), listOf(
            LoggedSet(45.0, 5, SetKind.RAMP), LoggedSet(45.0, 5, SetKind.RAMP),
            LoggedSet(45.0, 5, SetKind.RAMP), LoggedSet(45.0, 3, SetKind.RAMP),
            LoggedSet(45.0, 5, SetKind.TOP), LoggedSet(45.0, 8, SetKind.BACKOFF)))
        assertSlot(ids[1], rule(SlotRole.ACCESSORY, 10, 0.025, calibrating = true), work(20.0))
        assertSlot(ids[2], rule(SlotRole.ACCESSORY, 6, 0.0125), work(0.0, 6))
        assertSlot(ids[3], rule(SlotRole.MAIN, 5, 0.0125), work(135.0, 5))
        assertFalse(db.planDao().plan(gen)!!.autoApply)
        val slots = db.programDao().allExercises()
        val logs = db.programDao().allLogs()
        repository().plans.runPlanSettingsFixupIfNeeded()
        assertEquals(slots, db.programDao().allExercises())
        assertEquals(logs, db.programDao().allLogs())
    }

    @Test fun `createGeneratedPlan rolls back the whole plan when a rule write fails`() = runTest {
        var writes = 0
        val failing = repository(object : ProgramDao by db.programDao() {
            override suspend fun setRule(id: Long, json: String) {
                if (++writes == 3) throw SQLiteFullException("Storage full")
                db.programDao().setRule(id, json)
            }
        })
        val answers = WizardAnswers()
        assertFailsWith<SQLiteFullException> {
            failing.plans.createGeneratedPlan("Gen", answers, ProgramGenerator.generate(answers))
        }
        assertTrue(db.planDao().allPlans().isEmpty())
        assertTrue(db.programDao().allDays().isEmpty())
        assertTrue(db.programDao().allExercises().isEmpty())
        assertTrue(db.programDao().allLogs().isEmpty())
    }

    @Test fun `a prescription edit re-derives the rule in the same transaction`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("bb_back_squat"), plan)
        val id = db.programDao().exercisesForPlan(plan).single().id
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(155.0, 8)))
        assertEquals(rule(SlotRole.MAIN, 8, 0.025), SlotRuleJson.decode(db.programDao().exercise(id)!!.ruleJson))
        assertNull(db.programDao().logForSlot(plan, "A", id, Slot.MAIN))
        repo.setStructure(id, SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(rule(SlotRole.MAIN, 8, 0.025, Scheme.PROTOTYPE), SlotRuleJson.decode(db.programDao().exercise(id)!!.ruleJson))
        val before = db.programDao().exercise(id)
        val logs = db.programDao().allLogs()
        val failing = repository(object : ProgramDao by db.programDao() {
            override suspend fun setRule(id: Long, json: String) { throw SQLiteFullException("Storage full") }
        })
        assertFailsWith<SQLiteFullException> {
            failing.setPrescription(id, Prescription(PrescribedTarget.LoadReps(165.0, 3)))
        }
        assertEquals(before, db.programDao().exercise(id))
        assertEquals(logs, db.programDao().allLogs())
    }

    @Test fun `an added lift with untrusted history opens at its load calibrating`() = runTest {
        val plan = authored()
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Day B", null, 1000, 235.0))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "db_row", "Row", Slot.MAIN, 0,
            "WORK", 60.0, 10, true)))
        repo.addExercise("A", ProgramExercise("db_row"), plan)
        assertSlot(db.programDao().exercisesForPlan(plan).single().id,
            rule(SlotRole.ACCESSORY, 10, 0.0125, calibrating = true), work(60.0))
    }

    @Test fun `prescribed bodyweight and timed adds store the rows lazy seeding wrote`() = runTest {
        val plan = authored()
        val expected = listOf(
            ProgramExercise("plank") to List(3) { LoggedSet(0.0, 0, SetKind.WORK, seconds = 45) },
            ProgramExercise("pullup") to work(0.0, 6),
            ProgramExercise("bb_bench", prescription = Prescription(PrescribedTarget.LoadReps(135.0, 5))) to work(135.0, 5),
        )
        val config = repo.plans.wizardAnswersFor(plan).first().config
        val catalog = repo.catalog.snapshot()
        for ((exercise, rows) in expected) {
            repo.addExercise("A", exercise, plan)
            val slot = db.programDao().exercisesForPlan(plan).last()
            val rule = assertNotNull(SlotRuleJson.decode(slot.ruleJson))
            assertFalse(rule.state.calibrating)
            assertSlot(slot.id, rule, rows)
            assertEquals(rows, DaySeeds.previewMain(ProgramSlot(slot.id, slot.position, slot.toDomain()), config, catalog))
        }
    }

    @Test fun `an add whose row write fails leaves no slot`() = runTest {
        val plan = authored()
        val failing = repository(object : ProgramDao by db.programDao() {
            override suspend fun upsertLog(log: ExerciseLogEntity) { throw SQLiteFullException("Storage full") }
        })
        assertFailsWith<SQLiteFullException> { failing.addExercise("A", ProgramExercise("hack_squat"), plan) }
        assertTrue(db.programDao().exercisesForPlan(plan).isEmpty())
    }
}
