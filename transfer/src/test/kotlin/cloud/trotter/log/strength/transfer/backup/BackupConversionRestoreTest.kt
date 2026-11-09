package cloud.trotter.log.strength.transfer.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupConversionRestoreTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var service: BackupService
    private val codec = BackupCodec()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("conversion-restore", ".preferences_pb")
        })
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("conversion-restore-journal", ".preferences_pb")
        }, settings)
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        service = BackupService(BackupRepository(db, settings, repo.plans, repo.history), journal)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun document(version: Int): BackupDocument {
        val squat = SetJson.encodeSets(listOf(
            LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
            LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
            LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        ))
        val curls = SetJson.encodeSets(List(3) { LoggedSet(30.0, 12, SetKind.WORK) })
        val spec = CardioBlockJson.encodeSet(CardioSetSpec(CardioRole.WORK, Measure.Time(900)))
        return BackupDocument(
            schemaVersion = version,
            settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
                selectedPlanId = 1, bodyweightLbPrecise = 200.0),
            plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, onToday = true, suggestedDay = "A",
                recipeJson = PlanRecipeJson.encode(WizardAnswers(config = LifterConfig(goalsLb = mapOf("bb_back_squat" to 315.0)))),
                days = listOf(ProgramDayBackup("A", "Day A", "", nextRunId = "next", exercises = listOf(
                    ProgramExerciseBackup(7, "bb_back_squat", "RAMP_TOP_BACKOFF", 6, note = "", keepToPlan = false),
                    ProgramExerciseBackup(8, "hack_squat", "STRAIGHT", 3, note = "", keepToPlan = false),
                    ProgramExerciseBackup(9, "hammer_curl", "STRAIGHT", 3, note = "", keepToPlan = true,
                        parkedLogsJson = ParkedLogsJson.encode(listOf(ParkedTrackDto("main", "hammer_curl", curls)))),
                ))))),
            liveLogs = listOf(LiveLogBackup(1, "A", 7, "main", squat, updatedAt = 3000)),
            sessions = listOf(9L, 10L).map { id ->
                SessionBackup(id, "A", "Day A", startedAt = 100, completedAt = id * 1000,
                    bodyweightLb = 200, bodyweightLbPrecise = 200.0, plannedJson = if (id == 9L) "  [ ]  " else "[]",
                    note = "Session note", sets = listOf(SessionSetBackup(id + 100, "bb_back_squat", "Squat",
                        "main", 0, "TOP", 235.0, 5, true)))
            },
            cardioSessions = listOf(CardioSessionBackup(12, "C", "OUTDOOR_RUN", false, "Easy", 1000, 901000, 900, 1,
                activity = "ROAD_RUN", sets = listOf(
                    CardioSetResultBackup(13, spec, "FINISHED", 900, 3470, "MANUAL"),
                    CardioSetResultBackup(14, spec, "SHORTENED", 30),
                ))),
        )
    }

    @Test fun `v25 and v26 backups restore with history unchanged, rules on every slot and rows on the never-opened one`() = runTest {
        for (version in listOf(25, 26)) {
            val source = document(version)
            val decoded = codec.decode(codec.encode(source))
            val expected = decoded.toSnapshot()
            service.import(codec.encode(source))
            assertEquals(expected.sessions, db.sessionDao().allSessions())
            assertEquals(expected.sessionSets, db.sessionDao().allSessionSets())
            assertEquals(expected.cardioSessions, db.cardioSessionDao().all())
            assertEquals(expected.cardioSetResults, db.cardioSetResultDao().all())
            assertEquals(expected.logs, db.programDao().allLogs().filterNot { it.programExerciseId == 8L })
            assertEquals(SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, 0.05, Scheme.PROTOTYPE),
                SlotRuleJson.decode(db.programDao().exercise(7)!!.ruleJson))
            assertEquals(SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(12), 2, 0.0125, 1, 0.05, Scheme.STRENGTH),
                SlotRuleJson.decode(db.programDao().exercise(9)!!.ruleJson))
            assertEquals(SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(10), 2, 0.025, 1, 0.05, Scheme.STRENGTH,
                state = SlotState(calibrating = true)), SlotRuleJson.decode(db.programDao().exercise(8)!!.ruleJson))
            assertEquals(List(3) { LoggedSet(20.0, 10, SetKind.WORK) },
                SetJson.decodeSets(db.programDao().logForSlot(1, "A", 8, "main")!!.setsJson))
            assertEquals(source.plans.single().recipeJson, db.planDao().plan(1)!!.recipeJson)
            val exported = codec.decode(service.export())
            assertEquals(27, exported.schemaVersion)
            assertEquals(source.sessions, exported.sessions)
            assertEquals(source.cardioSessions, exported.cardioSessions)
        }
    }

    @Test fun `a v27 backup of a converted device restores unchanged`() = runTest {
        service.import(codec.encode(document(26)))
        val converted = service.export()
        service.import(converted)
        assertEquals(converted, service.export())
    }
}
