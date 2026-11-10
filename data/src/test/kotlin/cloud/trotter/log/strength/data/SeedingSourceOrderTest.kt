package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.units.WeightUnit
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
class SeedingSourceOrderTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private var plan = 0L

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("seeding-source-order", ".preferences_pb")
        })
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        repo = repository()
        plan = repo.plans.createAuthoredPlan("Mine", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        assertEquals("B", repo.addDay(plan, "Day B"))
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun repository(dao: ProgramDao = db.programDao()) =
        TrackerRepository(db, dao, db.sessionDao(), db.customExerciseDao(), settings)
    private fun rule(role: SlotRole, reps: Int, inc: Double, scheme: Scheme = Scheme.STRENGTH,
                     calibrating: Boolean = false) =
        SlotRule(role, RepTarget.Fixed(reps), 2, inc, 1, 0.05, scheme, state = SlotState(calibrating = calibrating))
    private fun work(n: Int, weight: Double, reps: Int) = List(n) { LoggedSet(weight, reps, SetKind.WORK) }
    private val cascade245 get() = listOf(
        LoggedSet(135.0, 5, SetKind.RAMP), LoggedSet(170.0, 5, SetKind.RAMP),
        LoggedSet(195.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
        LoggedSet(245.0, 5, SetKind.TOP), LoggedSet(185.0, 8, SetKind.BACKOFF),
    )
    private suspend fun add(day: String, exercise: ProgramExercise): Long {
        repo.addExercise(day, exercise, plan)
        return db.programDao().exercisesForDay(plan, day).last().id
    }
    private suspend fun history(exercise: String, weight: Double, reps: Int) {
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Day B", null, 1000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, exercise, exercise, Slot.MAIN, 0,
            "WORK", weight, reps, true)))
    }
    private suspend fun lifter(id: Long, rows: List<LoggedSet>, rule: SlotRule, at: Long): ExerciseLogEntity {
        val row = assertNotNull(db.programDao().exercise(id))
        val log = ExerciseLogEntity(row.planId, row.dayId, id, Slot.MAIN, SetJson.encodeSets(rows), at)
        db.programDao().upsertLog(log)
        db.programDao().setRule(id, SlotRuleJson.encode(rule))
        return log
    }
    private suspend fun log(id: Long, slot: String = Slot.MAIN): ExerciseLogEntity? {
        val row = assertNotNull(db.programDao().exercise(id))
        return db.programDao().logForSlot(row.planId, row.dayId, id, slot)
    }
    private suspend fun rows(id: Long, slot: String = Slot.MAIN) = SetJson.decodeSets(assertNotNull(log(id, slot)).setsJson)
    private suspend fun assertSlot(id: Long, expectedRows: List<LoggedSet>, expectedRule: SlotRule) {
        assertEquals(expectedRows, rows(id))
        assertEquals(expectedRule, SlotRuleJson.decode(db.programDao().exercise(id)!!.ruleJson))
    }

    @Test fun `an added lift opens at the weight another day carries ahead of history`() = runTest {
        val source = add("A", ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF, 6))
        val stored = lifter(source, cascade245, rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE), 1000)
        history("bb_back_squat", 100.0, 8)
        val sessions = db.sessionDao().allSessions()
        val sets = db.sessionDao().allSessionSets()
        val straight = add("B", ProgramExercise("bb_back_squat", targetSets = 3))
        assertSlot(straight, work(3, 245.0, 5), rule(SlotRole.MAIN, 5, 0.025))
        val ramped = add("B", ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF, 6))
        assertSlot(ramped, cascade245, rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE))
        assertEquals(stored, log(source))
        assertEquals(sessions, db.sessionDao().allSessions())
        assertEquals(sets, db.sessionDao().allSessionSets())
    }

    @Test fun `the latest written rows lead and calibrating rows are skipped`() = runTest {
        val a = add("A", ProgramExercise("bb_bench"))
        val b = add("B", ProgramExercise("bb_bench"))
        val c = add("B", ProgramExercise("bb_bench"))
        val h = add("A", ProgramExercise("hack_squat"))
        lifter(a, work(3, 185.0, 5), rule(SlotRole.MAIN, 5, 0.0125), 1000)
        lifter(b, work(3, 190.0, 5), rule(SlotRole.MAIN, 5, 0.0125), 2000)
        assertSlot(c, work(3, 45.0, 5), rule(SlotRole.MAIN, 5, 0.0125, calibrating = true))
        val beforeRows = listOf(a, b, c).map { log(it) }
        val beforeSlots = listOf(a, b, c).map { db.programDao().exercise(it) }
        assertTrue(repo.swapExerciseById("A", h, "bb_bench"))
        assertSlot(h, work(3, 190.0, 5), rule(SlotRole.MAIN, 5, 0.0125))
        assertEquals(beforeRows, listOf(a, b, c).map { log(it) })
        assertEquals(beforeSlots, listOf(a, b, c).map { db.programDao().exercise(it) })
    }

    @Test fun `with no plan rows a lift opens at a prescription elsewhere then the history estimate then the floor`() = runTest {
        val prescribed = add("B", ProgramExercise("cs_row"))
        repo.setPrescription(prescribed, Prescription(PrescribedTarget.LoadReps(90.0, 8)))
        assertNull(log(prescribed))
        val row = add("A", ProgramExercise("cs_row"))
        assertSlot(row, work(3, 80.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125))
        history("db_row", 60.0, 8)
        val dumbbell = add("A", ProgramExercise("db_row"))
        assertSlot(dumbbell, work(3, 55.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125))
        val hack = add("B", ProgramExercise("hack_squat"))
        assertSlot(hack, work(3, 20.0, 10), rule(SlotRole.ACCESSORY, 10, 0.025, calibrating = true))
    }

    @Test fun `conversion opens a never opened lift at the weight an opened day carries`() = runTest {
        val generated = db.planDao().insertPlan(PlanEntity(0, "Gen", "GENERATED", 1, 1000))
        for ((position, day) in listOf("A", "B").withIndex()) {
            db.programDao().upsertDay(ProgramDayEntity(generated, day, position, "Day $day", "", null))
        }
        suspend fun slot(day: String) = db.programDao().insertExercise(
            ProgramExerciseEntity(0, generated, day, 0, "bb_bench", "STRAIGHT", 3, null, ""))
        val a = slot("A")
        val b = slot("B")
        val stored = ExerciseLogEntity(generated, "A", a, Slot.MAIN, SetJson.encodeSets(work(3, 185.0, 5)), 1000)
        db.programDao().upsertLog(stored)
        history("bb_bench", 150.0, 8)
        assertTrue(repo.plans.convertPlans())
        assertSlot(b, work(3, 185.0, 5), rule(SlotRole.MAIN, 5, 0.0125))
        assertEquals(stored, log(a))
        assertEquals(rule(SlotRole.MAIN, 5, 0.0125), SlotRuleJson.decode(db.programDao().exercise(a)!!.ruleJson))
    }

    @Test fun `a partner add writes only the SS track at the live MAIN row count`() = runTest {
        val e = add("A", ProgramExercise("ez_curl", targetSets = 2))
        history("rope_pushdown", 50.0, 8)
        suspend fun partner(id: String, expected: List<LoggedSet>, expectedRule: SlotRule) {
            val main = log(e)
            val slot = assertNotNull(db.programDao().exercise(e))
            repo.setSupersetPartner("A", e, id)
            assertEquals(main, log(e))
            assertEquals(slot.copy(supersetExerciseId = id, partnerRuleJson = SlotRuleJson.encode(expectedRule)), db.programDao().exercise(e))
            assertEquals(expected, rows(e, Slot.SS))
        }
        partner("rope_pushdown", work(2, 50.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125))
        val face = add("B", ProgramExercise("face_pull"))
        lifter(face, work(3, 40.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125), 1000)
        partner("face_pull", work(2, 40.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125))
        partner("cable_curl", work(2, 20.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125, calibrating = true))
        val run = repo.startRun(plan, "A") as RunStartResult.Started
        repo.applyToRun(run.runId, plan, "A", listOf(TrackEdit(e, Slot.MAIN) { SetEditor.addSet(it) }))
        partner("rope_pushdown", work(3, 50.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125))
        val paired = add("A", ProgramExercise("ez_curl", targetSets = 2, superset = SupersetPartner("face_pull")))
        assertSlot(paired, work(2, 20.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125, calibrating = true))
        assertEquals(rows(e), rows(paired))
        assertEquals(db.programDao().exercise(e)!!.ruleJson, db.programDao().exercise(paired)!!.ruleJson)
        assertEquals(work(2, 40.0, 10), rows(paired, Slot.SS))
        val kept = add("A", ProgramExercise("ez_curl", targetSets = 2, superset = SupersetPartner("face_pull"), keepToPlan = true))
        assertNull(log(kept))
        assertNull(log(kept, Slot.SS))
    }

    @Test fun `a partner write that fails leaves the old partner in place`() = runTest {
        val e = add("A", ProgramExercise("ez_curl", superset = SupersetPartner("face_pull")))
        val run = repo.startRun(plan, "A") as RunStartResult.Started
        repo.applyToRun(run.runId, plan, "A", listOf(TrackEdit(e, Slot.SS) { rows -> rows.map { it.copy(done = true) } }))
        val slot = db.programDao().exercise(e)
        val logs = db.programDao().allLogs()
        val tracks = db.runDao().allTracks()
        val failing = repository(object : ProgramDao by db.programDao() {
            override suspend fun upsertLog(log: ExerciseLogEntity) {
                if (log.slot == Slot.SS) throw SQLiteFullException("Storage full")
                db.programDao().upsertLog(log)
            }
        })
        assertFailsWith<SQLiteFullException> { failing.setSupersetPartner("A", e, "rope_pushdown") }
        assertEquals(slot, db.programDao().exercise(e))
        assertEquals(logs, db.programDao().allLogs())
        assertEquals(tracks, db.runDao().allTracks())
    }

    @Test fun `reset day reads custom exercises the way conversion does`() = runTest {
        val custom = CustomExerciseEntity("custom_unreadable", "Unreadable", "UNKNOWN", "BARBELL", false, 45.0)
        db.customExerciseDao().upsert(custom)
        val answers = WizardAnswers()
        val gen = repo.plans.createGeneratedPlan("Gen", answers, ProgramGenerator.generate(answers))
        repo.plans.publishCreatedPlan(gen, answers)
        repo.resetDayToTemplate("A", gen)
        val slots = db.programDao().exercisesForPlan(gen)
        assertTrue(slots.isNotEmpty())
        for (slot in slots) {
            assertNotNull(SlotRuleJson.decode(slot.ruleJson))
            if (!slot.keepToPlan) assertNotNull(log(slot.id))
        }
        assertNull(repo.catalog.snapshot().find(custom.id))
    }

    @Test fun `a planned 245 by 5 top seeds a ten rep straight slot at 215 pounds`() = runTest {
        val source = add("A", ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF, 6))
        val stored = lifter(source, cascade245, rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE), 1000)
        val target = add("B", ProgramExercise("bb_back_squat"))
        db.programDao().deleteLogForSlot(plan, "B", target, Slot.MAIN)
        db.programDao().setRule(target, SlotRuleJson.encode(rule(SlotRole.MAIN, 10, 0.025)))
        val row = db.programDao().exercise(target)!!
        val opening = db.withTransaction {
            repo.plans.runSwapRows(row, Slot.MAIN, "bb_back_squat", 3, WeightUnit.LB, repo.catalog.snapshot())
        }
        assertEquals(work(3, 215.0, 10), opening)
        assertNull(log(target))
        assertEquals(row, db.programDao().exercise(target))
        val e = add("B", ProgramExercise("ez_curl"))
        repo.setSupersetPartner("B", e, "bb_back_squat")
        assertEquals(work(3, 215.0, 10), rows(e, Slot.SS))
        assertEquals(stored, log(source))
        assertFalse(SlotRuleJson.decode(db.programDao().exercise(source)!!.ruleJson)!!.state.calibrating)
    }

    @Test fun `a planned 225 by 5 straight slot seeds five rep sets at exactly 225 pounds`() = runTest {
        val source = add("A", ProgramExercise("bb_back_squat"))
        val stored = lifter(source, work(3, 225.0, 5), rule(SlotRole.MAIN, 5, 0.025), 1000)
        val target = add("B", ProgramExercise("bb_back_squat"))
        assertSlot(target, work(3, 225.0, 5), rule(SlotRole.MAIN, 5, 0.025))
        assertEquals(stored, log(source))
    }

    @Test fun `a ramp seeded from the latest ten rep plan row in an A to B to C chain calibrates`() = runTest {
        assertEquals("C", repo.addDay(plan, "Day C"))
        val a = add("A", ProgramExercise("db_row", SetStructure.RAMP_TOP, 5))
        val source = listOf(LoggedSet(70.0, 5, SetKind.TOP))
        val stored = lifter(a, source, rule(SlotRole.MAIN, 5, 0.0125, Scheme.PROTOTYPE), 1000)
        val b = add("B", ProgramExercise("db_row"))
        assertSlot(b, work(3, 60.0, 10), rule(SlotRole.ACCESSORY, 10, 0.0125))
        val latest = assertNotNull(log(b))
        assertTrue(latest.updatedAt > stored.updatedAt)

        val c = add("C", ProgramExercise("db_row", SetStructure.RAMP_TOP, 5))

        assertEquals(60.0, rows(c).single { it.kind == SetKind.TOP }.weightLb)
        assertEquals(rule(SlotRole.MAIN, 5, 0.0125, Scheme.PROTOTYPE, calibrating = true),
            SlotRuleJson.decode(db.programDao().exercise(c)!!.ruleJson))
        assertEquals(stored, log(a))
        assertEquals(latest, log(b))
    }

    @Test fun `turning keep to plan off restores an unreadable TIMED custom MAIN exactly`() = runTest {
        restoreUnreadableTimedTrack(Slot.MAIN)
    }

    @Test fun `turning keep to plan off restores an unreadable TIMED custom SS exactly`() = runTest {
        restoreUnreadableTimedTrack(Slot.SS)
    }

    private suspend fun restoreUnreadableTimedTrack(track: String) {
        val custom = CustomExerciseEntity("custom_timed", "Loaded hold", "CORE_FLEX", "DUMBBELL", false,
            35.0, tracking = "TIMED", targetSeconds = 45)
        db.customExerciseDao().upsert(custom)
        val id = add("A", ProgramExercise(if (track == Slot.MAIN) custom.id else "ez_curl", targetSets = 2,
            superset = SupersetPartner(if (track == Slot.SS) custom.id else "rope_pushdown")))
        val timed = listOf(
            LoggedSet(35.0, 0, SetKind.WORK, seconds = 60),
            LoggedSet(42.5, 0, SetKind.EXTRA, seconds = 75),
        )
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", id, track, SetJson.encodeSets(timed), 1000))
        val before = db.programDao().allLogs().associate { it.slot to it.setsJson }
        repo.setKeepToPlan(id, true)
        assertTrue(db.programDao().allLogs().isEmpty())
        assertNotNull(db.programDao().exercise(id)!!.parkedLogsJson)
        db.customExerciseDao().upsert(custom.copy(pattern = "UNKNOWN"))
        assertNull(repo.catalog.snapshot().find(custom.id))

        repo.setKeepToPlan(id, false)

        assertEquals(before, db.programDao().allLogs().associate { it.slot to it.setsJson })
        assertEquals(timed, rows(id, track))
        assertFalse(db.programDao().exercise(id)!!.keepToPlan)
        assertNull(db.programDao().exercise(id)!!.parkedLogsJson)
    }

    @Test fun `stored source ties keep plan order and unreadable rows never offer a prescription`() = runTest {
        val a = add("A", ProgramExercise("bb_bench"))
        val b = add("B", ProgramExercise("bb_bench"))
        lifter(a, work(3, 185.0, 5), rule(SlotRole.MAIN, 5, 0.0125), 1000)
        lifter(b, work(3, 190.0, 5), rule(SlotRole.MAIN, 5, 0.0125), 1000)
        db.programDao().setRule(a, "unreadable")
        val corrupt = add("B", ProgramExercise("bb_bench"))
        repo.setPrescription(corrupt, Prescription(PrescribedTarget.LoadReps(300.0, 5)))
        val source = db.programDao().exercise(corrupt)!!
        db.programDao().upsertLog(ExerciseLogEntity(plan, "B", corrupt, Slot.MAIN, "unreadable", Long.MAX_VALUE))
        db.programDao().upsertLog(ExerciseLogEntity(plan, "B", b, Slot.SS,
            SetJson.encodeSets(work(3, 400.0, 5)), Long.MAX_VALUE))
        val before = db.programDao().allLogs()
        val target = add("A", ProgramExercise("bb_bench"))
        assertSlot(target, work(3, 185.0, 5), rule(SlotRole.MAIN, 5, 0.0125))
        assertEquals(before, db.programDao().allLogs().filter { it.programExerciseId != target })
        assertEquals(source, db.programDao().exercise(corrupt))
    }

    @Test fun `a plan source uses its rule rir instead of the logged row rir`() = runTest {
        val source = add("A", ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF, 6))
        lifter(source, cascade245.map { it.copy(rir = 0) },
            rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE).copy(rir = 3), 1000)
        val e = add("B", ProgramExercise("ez_curl"))
        repo.setSupersetPartner("B", e, "bb_back_squat")
        assertEquals(work(3, 220.0, 10), rows(e, Slot.SS))
    }

    @Test fun `a failing partner write on add leaves no slot or rows`() = runTest {
        val before = db.programDao().allExercises()
        val logs = db.programDao().allLogs()
        val failing = repository(object : ProgramDao by db.programDao() {
            override suspend fun upsertLog(log: ExerciseLogEntity) {
                if (log.slot == Slot.SS) throw SQLiteFullException("Storage full")
                db.programDao().upsertLog(log)
            }
        })
        assertFailsWith<SQLiteFullException> {
            failing.addExercise("A", ProgramExercise("ez_curl", superset = SupersetPartner("face_pull")), plan)
        }
        assertEquals(before, db.programDao().allExercises())
        assertEquals(logs, db.programDao().allLogs())
    }

}
