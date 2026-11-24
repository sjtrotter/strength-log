package cloud.trotter.log.strength.transfer.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.TestEventJson
import cloud.trotter.log.strength.data.serialization.TestGoalJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TestGoalBackupTest {
    private val codec = BackupCodec()
    private val hrp = TestRef("aft_hrp", 1, 120)
    private val goal = TestGoal(listOf(TestEventGoal(hrp, 40)), 20_400)
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private lateinit var service: BackupService

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("test-goal-backup", ".preferences_pb")
        })
        val cardioPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("test-goal-backup-cardio", ".preferences_pb")
        })
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("test-goal-backup-journal", ".preferences_pb")
        }, settings, cardioRunPrefs = cardioPrefs)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        service = BackupService(BackupRepository(db, settings, repo.plans, repo.history, cardioRunPrefs = cardioPrefs), journal)
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun result(id: Long = 71) = TestResultBackup(id, 1000, planId = 1, dayId = "A", runId = "run",
        programExerciseId = 7, setIndex = 0, exerciseId = "hand_release_pushup", eventId = "aft_hrp",
        eventVersion = 1, windowSeconds = 120, count = 27, stop = "TIME_UP", rulesJson = TestEventJson.encode(TestEvents.get("aft_hrp")))

    private fun document(plan: PlanBackup = PlanBackup(1, "Mine", "AUTHORED", 1000)) = BackupDocument(
        settings = SettingsBackup(200, 40, weightUnit = "LB", wizardComplete = true), plans = listOf(plan),
    )

    private fun atVersion(document: BackupDocument, version: Int): String {
        val root = Json.parseToJsonElement(codec.encode(document)).jsonObject
        return JsonObject(root + ("schemaVersion" to JsonPrimitive(version))).toString()
    }

    @Test fun `a test goal and its results round trip through the document and snapshot`() = runTest {
        val plan = repo.plans.createAuthoredPlan("Mine", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.plans.setTestGoal(plan, goal)
        val rows = listOf(
            TestResultEntity(71, 1000, plan, "A", "run", 7, 0, "hand_release_pushup", "aft_hrp", 1,
                120, 27, null, "TIME_UP", TestEventJson.encode(TestEvents.get("aft_hrp"))),
            TestResultEntity(72, 2000, plan, "A", "run", 8, 0, "plank", "aft_plank", 1,
                null, null, 192, "STOPPED", TestEventJson.encode(TestEvents.get("aft_plank"))),
        )
        rows.forEach { repo.history.logTestResult(it) }
        val backup = service.export()
        val decoded = codec.decode(backup)
        assertEquals(35, decoded.schemaVersion)
        val snapshot = decoded.toSnapshot()
        assertEquals(TestGoalJson.encode(goal), snapshot.plans.single().testGoalJson)
        assertEquals(rows, snapshot.testResults)
        assertEquals(decoded, snapshot.toDocument())

        repo.plans.setTestGoal(plan, null)
        // Restore must replace existing results, including rows absent from the document.
        db.testResultDao().insert(rows.first().copy(id = 73, completedAt = 3000))
        service.import(backup)
        assertEquals(rows, db.testResultDao().all())
        assertEquals(goal, TestGoalJson.decode(db.planDao().plan(plan)!!.testGoalJson))
        assertEquals(backup, service.export())
        repo.plans.setTestGoal(plan, null)
        assertNull(db.planDao().plan(plan)!!.testGoalJson)
    }

    @Test fun `custom window results round trip at 60 seconds through export and restore`() = runTest {
        val plan = repo.plans.createAuthoredPlan("Mine", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val rows = listOf("custom_pushup_window", "custom_situp_window").mapIndexed { index, id ->
            val event = TestEvents.get(id)
            assertNull(event.windowSeconds)
            TestResultEntity(71L + index, 1000, plan, "A", "run", 7L + index, 0, event.exerciseId, event.id,
                event.version, TestEvents.window(event, 60), 27, null, "TIME_UP", TestEventJson.encode(event))
        }
        rows.forEach { repo.history.logTestResult(it) }
        val backup = service.export()
        val decoded = codec.decode(backup)
        assertEquals(35, decoded.schemaVersion)
        assertEquals(listOf(60, 60), decoded.testResults.map { it.windowSeconds })
        assertEquals(rows, decoded.toSnapshot().testResults)

        db.testResultDao().deleteAll()
        service.import(backup)
        assertEquals(rows, db.testResultDao().all())
        assertEquals(backup, service.export())
    }

    @Test fun `a v34 document with test results answers or links is inconsistent`() {
        val plain = document()
        val plan = plain.plans.single()
        val linked = ProgramExerciseBackup(7, "hand_release_pushup", targetSets = 3, note = "",
            prescriptionJson = PrescriptionJson.encode(Prescription(PrescribedTarget.RepsInTime(120), test = hrp)))
        val cases = listOf(
            plain.copy(testResults = listOf(result())) to "test results",
            plain.copy(plans = listOf(plan.copy(recipeJson = """{"tests":[{"eventId":"aft_hrp","version":1,"windowSeconds":120,"required":40}]}"""))) to "test answers",
            plain.copy(plans = listOf(plan.copy(days = listOf(ProgramDayBackup("A", "Day A", "", exercises = listOf(linked)))))) to "test link",
        )
        for ((source, fact) in cases) for (version in listOf(1, 25, 32, 33, 34)) {
            val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(atVersion(source, version)) }
            assertTrue(error.message.orEmpty().contains(fact))
            assertTrue(error.message.orEmpty().contains("schema v35"))
        }
    }

    @Test fun `a v34 document without them restores with no test goal and no results`() {
        val source = document()
        val root = Json.parseToJsonElement(atVersion(source, 34)).jsonObject
        val old = JsonObject((root - "testResults") + ("plans" to JsonArray(root.getValue("plans").jsonArray.map {
            JsonObject(it.jsonObject - "testGoalJson")
        })))
        val decoded = codec.decode(old.toString())
        assertEquals(34, decoded.schemaVersion)
        assertNull(decoded.plans.single().testGoalJson)
        assertEquals(emptyList<TestResultBackup>(), decoded.testResults)
        assertNull(decoded.toSnapshot().plans.single().testGoalJson)
        assertEquals(emptyList<TestResultEntity>(), decoded.toSnapshot().testResults)
    }

    @Test fun `a v34 document ignores plan test goals regardless of type on restore`() = runTest {
        val root = Json.parseToJsonElement(atVersion(document(), 34)).jsonObject
        val futureGoals = listOf(JsonPrimitive(TestGoalJson.encode(goal)), JsonPrimitive("future"),
            JsonObject(emptyMap()), JsonArray(emptyList()), JsonPrimitive(1), JsonPrimitive(true))
        for (future in futureGoals) {
            val plans = root.getValue("plans").jsonArray.map {
                JsonObject(it.jsonObject + ("testGoalJson" to future))
            }
            val backup = JsonObject(root + ("plans" to JsonArray(plans))).toString()
            val decoded = codec.decode(backup)
            assertEquals(34, decoded.schemaVersion)
            assertNull(decoded.plans.single().testGoalJson)
            assertNull(decoded.toSnapshot().plans.single().testGoalJson)
            service.import(backup)
            assertNull(db.planDao().plan(1)!!.testGoalJson)
            assertNull(codec.decode(service.export()).plans.single().testGoalJson)
        }
    }

    @Test fun `pre v35 documents ignore wrong typed test results`() {
        for (version in listOf(1, 25, 32, 33, 34)) {
            val source = if (version == 1) {
                checkNotNull(javaClass.getResourceAsStream("/backup/v1_backup.json")).use { it.readBytes().toString(Charsets.UTF_8) }
            } else atVersion(document(), version)
            val root = Json.parseToJsonElement(source).jsonObject
            for (future in listOf(JsonObject(emptyMap()), JsonPrimitive("future"), JsonPrimitive(1))) {
                val decoded = codec.decode(JsonObject(root + ("testResults" to future)).toString())
                assertEquals(version, decoded.schemaVersion)
                assertEquals(emptyList<TestResultBackup>(), decoded.testResults)
                assertEquals(emptyList<TestResultEntity>(), decoded.toSnapshot().testResults)
                assertTrue(decoded.plans.all { it.testGoalJson == null })
            }
        }
    }

    @Test fun `a result whose identity differs from its stored rules is inconsistent`() {
        val row = result()
        val mismatches = listOf(
            row.copy(eventId = "other_event"),
            row.copy(eventVersion = 2),
            row.copy(exerciseId = "plank"),
            row.copy(windowSeconds = 60),
            row.copy(windowSeconds = null),
            row.copy(count = null, seconds = 192, rulesJson = TestEventJson.encode(TestEvents.get("aft_plank"))),
        )
        for (mismatch in mismatches) {
            assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(document().copy(testResults = listOf(mismatch))))
            }
        }
    }

    @Test fun `historical rules with an unknown set type and non https source restore verbatim`() = runTest {
        val rules = Json.parseToJsonElement(result().rulesJson).jsonObject
        val historical = JsonObject(rules + mapOf(
            "version" to JsonPrimitive(2),
            "setType" to JsonPrimitive("HISTORICAL_WINDOW"),
            "sourceUrl" to JsonPrimitive("http://example.com/rules"),
            "sourceDate" to JsonPrimitive("June 2025"),
            "futureRule" to JsonPrimitive("preserved"),
        ))
        val row = result().copy(eventVersion = 2, rulesJson = "\n$historical\n")
        val backup = codec.encode(document().copy(testResults = listOf(row)))
        val decoded = codec.decode(backup)
        assertEquals(row, decoded.testResults.single())
        assertEquals(row.rulesJson, decoded.toSnapshot().testResults.single().rulesJson)
        service.import(backup)
        assertEquals(row.rulesJson, db.testResultDao().all().single().rulesJson)
        assertEquals(row, codec.decode(service.export()).testResults.single())
    }

    @Test fun `rules snapshots require display fields and typed values`() {
        val rules = Json.parseToJsonElement(result().rulesJson).jsonObject
        val invalid = listOf("name", "setType", "form", "faults", "rest", "scoring", "sourceUrl", "sourceDate")
            .map { JsonObject(rules - it) } + listOf(
                JsonObject(rules + ("setType" to JsonObject(emptyMap()))),
                JsonObject(rules + ("windowSeconds" to JsonPrimitive("later"))),
            )
        for (snapshot in invalid) {
            assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(document().copy(testResults = listOf(result().copy(rulesJson = snapshot.toString())))))
            }
        }
    }

    @Test fun `negative test counts and seconds are inconsistent and zero restores`() {
        val hold = result().copy(exerciseId = "plank", eventId = "aft_plank", windowSeconds = null,
            count = null, seconds = 0, stop = "STOPPED", rulesJson = TestEventJson.encode(TestEvents.get("aft_plank")))
        for (row in listOf(result().copy(count = -3), hold.copy(seconds = -1))) {
            assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(document().copy(testResults = listOf(row))))
            }
        }
        for (row in listOf(result().copy(count = 0), hold)) {
            assertEquals(row, codec.decode(codec.encode(document().copy(testResults = listOf(row)))).testResults.single())
        }
    }

    @Test fun `an unknown stop or a result with two numbers is inconsistent`() {
        for (row in listOf(result().copy(stop = "FELL"), result().copy(seconds = 192), result().copy(count = null))) {
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document().copy(testResults = listOf(row)))) }
        }
    }

    @Test fun `unreadable test goals and rules are inconsistent`() {
        val plan = document().plans.single()
        for (json in listOf("{", """{"v":2,"events":[]}""")) {
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(plan.copy(testGoalJson = json)))) }
        }
        assertFailsWith<BackupError.Inconsistent> {
            codec.decode(codec.encode(document().copy(testResults = listOf(result().copy(rulesJson = "{")))))
        }
    }

    @Test fun `invalid test result ids and identities are inconsistent`() {
        for (rows in listOf(listOf(result(0)), listOf(result(), result()), listOf(result().copy(eventVersion = 0)))) {
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document().copy(testResults = rows))) }
        }
    }
}
