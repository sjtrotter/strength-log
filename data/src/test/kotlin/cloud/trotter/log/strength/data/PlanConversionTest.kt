package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_24_25
import cloud.trotter.log.strength.data.db.MIGRATION_25_26
import cloud.trotter.log.strength.data.db.MIGRATION_26_27
import cloud.trotter.log.strength.data.db.MIGRATION_27_28
import cloud.trotter.log.strength.data.db.MIGRATION_28_29
import cloud.trotter.log.strength.data.db.MIGRATION_29_30
import cloud.trotter.log.strength.data.db.MIGRATION_30_31
import cloud.trotter.log.strength.data.db.MIGRATION_31_32
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
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
class PlanConversionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "plan-conversion-test.db"
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var before: Map<String, List<List<String?>>>
    private val answers = WizardAnswers(config = LifterConfig(goalsLb = mapOf("bb_back_squat" to 315.0)))
    private val squatRows = SetJson.encodeSets(listOf(
        LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
        LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
        LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
    ))
    private val curlRows = SetJson.encodeSets(List(3) { LoggedSet(30.0, 12, SetKind.WORK) })
    private val parked = ParkedLogsJson.encode(listOf(ParkedTrackDto(Slot.MAIN, "hammer_curl", curlRows)))
    private val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900))

    @Before fun setUp() = runBlocking {
        val helper = V24Fixture.create(context, name) { sql ->
            V24Fixture.populate(sql)
            sql.execSQL("UPDATE plan SET recipeJson = ?", arrayOf(PlanRecipeJson.encode(answers)))
            sql.execSQL("UPDATE program_day SET cardioBlockJson = ? WHERE dayId = 'C'",
                arrayOf(CardioBlockJson.encode(CardioBlock(CardioActivity.ROAD_RUN, listOf(spec)))))
            sql.execSQL("INSERT INTO program_day VALUES (1,'R',2,'Rest','',NULL,'REST',NULL,'rest',NULL,NULL,0,NULL,NULL)")
            sql.execSQL("UPDATE exercise_log SET setsJson = ? WHERE programExerciseId = 7", arrayOf(squatRows))
            sql.execSQL("UPDATE program_exercise SET parkedLogsJson = ? WHERE id = 9", arrayOf(parked))
            sql.execSQL("INSERT INTO program_exercise VALUES (8,1,'A',1,'hack_squat','STRAIGHT',3,NULL,'',NULL,0,NULL,NULL)")
            sql.execSQL("INSERT INTO program_exercise VALUES (10,1,'A',3,'bb_bench','STRAIGHT',3,'db_row','',NULL,0,NULL,NULL)")
            val bench = SetJson.encodeSets(List(3) { LoggedSet(185.0, 5, SetKind.WORK) })
            val partner = SetJson.encodeSets(List(3) { LoggedSet(60.0, 10, SetKind.WORK) })
            sql.execSQL("INSERT INTO exercise_log VALUES (1,'A',10,'main',?,3000)", arrayOf(bench))
            sql.execSQL("INSERT INTO exercise_log VALUES (1,'A',10,'ss',?,3000)", arrayOf(partner))
            sql.execSQL("UPDATE run_track SET plannedJson = ?, setsJson = ? WHERE id = 11", arrayOf(squatRows, squatRows))
            sql.execSQL("INSERT INTO run_track VALUES (12,'open-run',3,10,'main','bb_bench',0,?,?,3000,0,'')", arrayOf(bench, bench))
            sql.execSQL("INSERT INTO workout_session VALUES (10,'A','Day A',600,900,210.5,1,'Plan','finished-2','[]','Second note')")
            sql.execSQL("INSERT INTO session_set VALUES (11,10,'incline_db','Incline','main',0,'WORK',185.0,5,1,0,600,700,0,0)")
            sql.execSQL("UPDATE cardio_set_result SET specJson = ?", arrayOf(CardioBlockJson.encodeSet(spec)))
            sql.execSQL("INSERT INTO cardio_set_result VALUES (14,12,1,?,'SHORTENED',30,NULL,NULL)", arrayOf(CardioBlockJson.encodeSet(spec)))
            val prescription = PrescriptionJson.encode(Prescription(PrescribedTarget.LoadReps(155.0, 6)))
            sql.execSQL("INSERT INTO program_exercise VALUES (20,1,'A',4,'bb_row','RAMP_TOP_BACKOFF',6,NULL,'',?,1,NULL,NULL)", arrayOf(prescription))
            listOf(21 to "pullup", 22 to "plank", 23 to "custom_hinge", 24 to "custom_gone", 25 to "bb_back_squat")
                .forEach { (id, exercise) ->
                    sql.execSQL("INSERT INTO program_exercise VALUES (?,1,'A',? ,?,'STRAIGHT',3,NULL,'',NULL,0,NULL,NULL)",
                        arrayOf<Any>(id, id - 16, exercise))
                    val rows = when (id) {
                        21 -> List(3) { LoggedSet(0.0, 8, SetKind.WORK) }
                        22 -> List(3) { LoggedSet(0.0, 0, SetKind.WORK, seconds = 45) }
                        23 -> listOf(LoggedSet(100.0, 8, SetKind.WORK))
                        else -> listOf(LoggedSet(50.0, 10, SetKind.WORK))
                    }
                    sql.execSQL("INSERT INTO exercise_log VALUES (1,'A',?,'main',?,3000)",
                        arrayOf<Any>(id, if (id == 25) "junk" else SetJson.encodeSets(rows)))
                }
        }
        before = V24Fixture.dump(helper.writableDatabase)
        helper.close()
        db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
            .addMigrations(MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("plan-conversion", ".preferences_pb")
        })
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        repo = repository()
    }

    private fun repository() = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    private fun dump() = V24Fixture.dump(db.openHelper.writableDatabase)
    private fun rule(role: SlotRole, reps: Int, increment: Double, scheme: Scheme = Scheme.STRENGTH) =
        SlotRule(role, RepTarget.Fixed(reps), 2, increment, 1, 0.05, scheme)
    private suspend fun assertRule(id: Long, expected: SlotRule) {
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(expected, SlotRuleJson.decode(db.programDao().exercise(id)!!.ruleJson))
    }

    @After fun tearDown() { db.close(); scope.cancel(); context.deleteDatabase(name) }

    @Test fun `case 28 converts the generated plan and keeps every row`() = runTest {
        assertRule(7, rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE))
        assertRule(10, rule(SlotRole.MAIN, 5, 0.0125))
        assertRule(9, rule(SlotRole.ACCESSORY, 12, 0.0125))
        assertNull(db.programDao().logForSlot(1, "A", 9, Slot.MAIN))
        assertEquals(parked, db.programDao().exercise(9)!!.parkedLogsJson)
        assertTrue(db.programDao().exercise(9)!!.keepToPlan)
        assertEquals(rule(SlotRole.ACCESSORY, 10, 0.025).copy(state = SlotState(calibrating = true)),
            SlotRuleJson.decode(db.programDao().exercise(8)!!.ruleJson))
        assertEquals(List(3) { LoggedSet(20.0, 10, SetKind.WORK) },
            SetJson.decodeSets(db.programDao().logForSlot(1, "A", 8, Slot.MAIN)!!.setsJson))
        val after = dump()
        for (table in V24Fixture.tables) {
            assertEquals(before.getValue(table), after.getValue(table).filterNot { table == "exercise_log" && it[2] == "8" }.map {
                it.dropLast(V24Fixture.defaults[table].orEmpty().size)
            }, table)
        }
        for (table in listOf("plan", "program_day")) {
            val defaults = V24Fixture.defaults.getValue(table)
            assertEquals(after.getValue(table).map { defaults }, after.getValue(table).map { it.takeLast(defaults.size) })
        }
        assertEquals(squatRows, db.programDao().logForSlot(1, "A", 7, Slot.MAIN)!!.setsJson)
    }

    @Test fun `case 28 history is byte identical after upgrade and conversion`() = runTest {
        repo.plans.runPlanSettingsFixupIfNeeded()
        val tables = listOf("workout_session", "session_set", "cardio_session", "cardio_set_result", "exercise_log")
        V24Fixture.assertAppendedDefaults(before.filterKeys { it in tables }, dump().mapValues { (table, rows) ->
            rows.filterNot { table == "exercise_log" && it[2] == "8" }
        })
    }

    @Test fun `conversion is idempotent`() = runTest {
        repo.plans.runPlanSettingsFixupIfNeeded()
        val converted = dump()
        assertTrue(repo.plans.convertPlans())
        assertTrue(repo.plans.convertPlans())
        repository().plans.runPlanSettingsFixupIfNeeded()
        assertEquals(converted, dump())
    }

    @Test fun `a ramped prescription supplies reps without parked rows`() = runTest {
        assertRule(20, rule(SlotRole.MAIN, 6, 0.0125, Scheme.PROTOTYPE))
    }
    @Test fun `a pullup uses its working reps as ACCESSORY`() = runTest {
        assertRule(21, rule(SlotRole.ACCESSORY, 8, 0.0125))
    }
    @Test fun `a timed plank uses the Strength accessory default`() = runTest {
        assertRule(22, rule(SlotRole.ACCESSORY, 10, 0.0125))
    }
    @Test fun `a custom hinge uses ACCESSORY with the lower-body increment`() = runTest {
        assertRule(23, rule(SlotRole.ACCESSORY, 8, 0.025))
    }
    @Test fun `a deleted custom exercise uses the upper-body increment`() = runTest {
        assertRule(24, rule(SlotRole.ACCESSORY, 10, 0.0125))
    }
    @Test fun `an unreadable custom exercise is omitted from the conversion catalog`() = runTest {
        val custom = db.customExerciseDao().getAll().single()
        db.customExerciseDao().upsert(custom.copy(pattern = "UNKNOWN"))
        assertRule(23, rule(SlotRole.ACCESSORY, 8, 0.0125))
    }
    @Test fun `malformed squat rows use defaults and keep their bytes`() = runTest {
        assertRule(25, rule(SlotRole.MAIN, 5, 0.025))
        assertEquals("junk", db.programDao().logForSlot(1, "A", 25, Slot.MAIN)!!.setsJson)
    }

    @Test fun `an empty stored MAIN track wins over parked rows and keeps its bytes`() = runTest {
        val empty = ExerciseLogEntity(1, "A", 9, Slot.MAIN, "  [ ]  ", 5000)
        db.programDao().upsertLog(empty)
        assertRule(9, rule(SlotRole.ACCESSORY, 10, 0.0125))
        assertEquals(empty, db.programDao().logForSlot(1, "A", 9, Slot.MAIN))
        assertEquals(parked, db.programDao().exercise(9)!!.parkedLogsJson)
    }

    @Test fun `malformed parked rows fall back without rewriting them`() = runTest {
        db.programDao().setParkedLogs(9, "junk")
        assertRule(9, rule(SlotRole.ACCESSORY, 10, 0.0125))
        assertEquals("junk", db.programDao().exercise(9)!!.parkedLogsJson)
    }

    @Test fun `a storage failure rolls back the pass and the same repository retries`() = runTest {
        var writes = 0
        var fail = true
        val dao = object : ProgramDao by db.programDao() {
            override suspend fun setRule(id: Long, json: String) {
                writes++
                if (fail && writes == 2) {
                    fail = false
                    throw SQLiteException("Storage failure")
                }
                db.programDao().setRule(id, json)
            }
        }
        val plans = PlanRepository(db, settings, programDao = dao)
        val original = dump()
        assertFailsWith<SQLiteException> { plans.convertPlans() }
        assertEquals(original, dump())
        plans.runPlanSettingsFixupIfNeeded()
        assertEquals(rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE),
            SlotRuleJson.decode(db.programDao().exercise(7)!!.ruleJson))
    }

    @Test fun `a plan of cardio extras only converts to nothing`() = runTest {
        val sql = db.openHelper.writableDatabase
        V24Fixture.tables.forEach { sql.execSQL("DELETE FROM $it") }
        sql.execSQL("INSERT INTO plan (id,name,origin,position,createdAt,archived) VALUES (1,'Cardio','GENERATED',0,1000,0)")
        for ((position, day) in listOf("C1", "C2").withIndex()) {
            sql.execSQL("INSERT INTO program_day (planId,dayId,position,title,emphasisLine,kind,nextRunId) VALUES (1,?,?,'Cardio','','CARDIO',?)",
                arrayOf<Any>(day, position, day))
        }
        val original = dump()
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(original, dump())
    }

    @Test fun `a pending restore defers conversion`() = runTest {
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "pending"))
        val original = dump()
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(original, dump())
        assertFalse(repo.plans.convertPlans())
        db.restoreMarkerDao().clear()
        assertRule(7, rule(SlotRole.MAIN, 5, 0.025, Scheme.PROTOTYPE))
    }

    @Test fun `a swap or a structure change derives the rule in the same transaction`() = runTest {
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertTrue(repo.swapExerciseById("A", 10, "incline_db"))
        val slot = db.programDao().exercise(10)!!
        val rows = SetJson.decodeSets(db.programDao().logForSlot(1, "A", 10, Slot.MAIN)!!.setsJson)
        assertEquals(rule(SlotRole.MAIN, PlanConversion.firstWorkingReps(rows)!!, 0.0125), SlotRuleJson.decode(slot.ruleJson))
        repo.setStructure(7, SetStructure.STRAIGHT)
        assertEquals(rule(SlotRole.MAIN, 5, 0.025), SlotRuleJson.decode(db.programDao().exercise(7)!!.ruleJson))
        assertNull(db.programDao().logForSlot(1, "A", 7, Slot.MAIN))
        val converted = dump()
        repository().plans.runPlanSettingsFixupIfNeeded()
        assertEquals(converted, dump())
    }

    @Test fun `a plan built by hand inserts Automatic and a generated plan Ask`() = runTest {
        val authored = repo.plans.createAuthoredPlan("My plan", "Day A")
        val generated = repo.plans.createGeneratedPlan("Generated", answers, Program(emptyList()))
        assertTrue(db.planDao().plan(authored)!!.autoApply)
        assertFalse(db.planDao().plan(generated)!!.autoApply)
    }
    @Test fun `conversion rules the superset partner from its stored rows`() = runTest {
        val before = db.programDao().logForSlot(1, "A", 10, Slot.SS)
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(rule(SlotRole.ACCESSORY, 10, 0.0125),
            SlotRuleJson.decode(db.programDao().exercise(10)!!.partnerRuleJson))
        assertEquals(before, db.programDao().logForSlot(1, "A", 10, Slot.SS))
    }

}
