package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
class NeverOpenedSeedingTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private val plan = PlanEntity(1, "Plan", "GENERATED", 0, 1000, onToday = true)

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("never-opened", ".preferences_pb")
        })
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        db.planDao().insertPlan(plan)
        db.programDao().upsertDay(ProgramDayEntity(1, "A", 0, "Day A", "", null))
        repo = repository()
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun repository() = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    private suspend fun slot(exercise: String, structure: String = "STRAIGHT", sets: Int = 3,
                             kept: Boolean = false, partner: String? = null): Long =
        db.programDao().insertExercise(ProgramExerciseEntity(0, 1, "A", db.programDao().allExercises().size,
            exercise, structure, sets, partner, "", keepToPlan = kept))
    private fun work(weight: Double, reps: Int = 10) = List(3) { LoggedSet(weight, reps, SetKind.WORK) }
    private fun rule(role: SlotRole = SlotRole.ACCESSORY, reps: Int = 10, inc: Double = 0.025,
                     scheme: Scheme = Scheme.STRENGTH, calibrating: Boolean = false) =
        SlotRule(role, RepTarget.Fixed(reps), 2, inc, 1, 0.05, scheme, state = SlotState(calibrating = calibrating))
    private suspend fun rows(id: Long) = SetJson.decodeSets(assertNotNull(db.programDao().logForSlot(1, "A", id, Slot.MAIN)).setsJson)
    private suspend fun assertRule(id: Long, expected: SlotRule) =
        assertEquals(expected, SlotRuleJson.decode(db.programDao().exercise(id)!!.ruleJson))
    private suspend fun history(exercise: String, weight: Double, reps: Int, at: Long = 1000,
                                kind: SetKind = SetKind.WORK, done: Boolean = true, session: Long? = null): Long {
        val sessionId = session ?: db.sessionDao().insertSession(WorkoutSessionEntity(
            0, "B", "Day B", null, at, 235.0, planId = 1))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, sessionId, exercise, exercise, Slot.MAIN, 0,
            kind.name, weight, reps, done)))
        return sessionId
    }

    @Test fun `case 28 a never opened hack squat opens at twenty pounds calibrating`() = runTest {
        val id = slot("hack_squat")
        val sessions = db.sessionDao().allSessions()
        val sets = db.sessionDao().allSessionSets()
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertRule(id, rule(calibrating = true))
        assertEquals(work(20.0), rows(id))
        assertNull(db.programDao().logForSlot(1, "A", id, Slot.SS))
        assertEquals(sessions, db.sessionDao().allSessions())
        assertEquals(sets, db.sessionDao().allSessionSets())
    }

    @Test fun `case 28 a never opened hack squat with history opens at the estimate`() = runTest {
        val id = slot("hack_squat")
        history("hack_squat", 200.0, 8, at = 500)
        val latest = history("hack_squat", 150.0, 8)
        history("hack_squat", 160.0, 5, kind = SetKind.RAMP, session = latest)
        history("hack_squat", 300.0, 8, done = false, session = latest)
        val sessions = db.sessionDao().allSessions()
        val sets = db.sessionDao().allSessionSets()
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(work(140.0), rows(id))
        assertRule(id, rule())
        assertEquals(sessions, db.sessionDao().allSessions())
        assertEquals(sets, db.sessionDao().allSessionSets())
    }

    @Test fun `case 28 history in kg rounds to the kg step`() = runTest {
        settings.setUnit(WeightUnit.KG)
        val hack = slot("hack_squat")
        val press = slot("leg_press")
        history("hack_squat", 150.0, 8)
        history("leg_press", 100.0, 12)
        repo.plans.runPlanSettingsFixupIfNeeded()
        for ((id, kg) in listOf(hack to 65.0, press to 45.0)) {
            val expected = WeightUnit.KG.toLb(kg)
            val actual = rows(id)
            assertEquals(3, actual.size)
            actual.forEach {
                assertEquals(expected, it.weightLb, 1e-9)
                assertEquals(LoggedSet(expected, 10, SetKind.WORK), it.copy(weightLb = expected))
            }
        }
        assertRule(hack, rule())
        assertRule(press, rule(calibrating = true))
    }

    @Test fun `a never opened squat with a 245 by 5 history opens on the pinned cascade`() = runTest {
        val id = slot("bb_back_squat", "RAMP_TOP_BACKOFF", 6)
        history("bb_back_squat", 245.0, 5, kind = SetKind.TOP)
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(listOf(LoggedSet(135.0, 5, SetKind.RAMP), LoggedSet(170.0, 5, SetKind.RAMP),
            LoggedSet(195.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
            LoggedSet(245.0, 5, SetKind.TOP), LoggedSet(185.0, 8, SetKind.BACKOFF)), rows(id))
        assertRule(id, rule(SlotRole.MAIN, 5, scheme = Scheme.PROTOTYPE))
    }

    @Test fun `a never opened pull-up keeps its library rep target`() = runTest {
        val id = slot("pullup")
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertRule(id, rule(reps = 6, inc = 0.0125))
        assertEquals(work(0.0, 6), rows(id))
    }

    @Test fun `kept to plan unknown and partner tracks get no new rows`() = runTest {
        val kept = slot("hammer_curl", kept = true)
        val unknown = slot("custom_gone")
        val paired = slot("ez_curl", partner = "rope_pushdown")
        repo.plans.runPlanSettingsFixupIfNeeded()
        for (id in listOf(kept, unknown)) {
            assertRule(id, rule(inc = 0.0125))
            assertNull(db.programDao().logForSlot(1, "A", id, Slot.MAIN))
        }
        assertRule(paired, rule(inc = 0.0125, calibrating = true))
        assertEquals(work(20.0), rows(paired))
        assertNull(db.programDao().logForSlot(1, "A", paired, Slot.SS))
    }

    @Test fun `an open run keeps its track while its never opened slot is seeded`() = runTest {
        val id = slot("hack_squat")
        db.runDao().insertRun(WorkoutRunEntity("open", 1, "A", "Day A", "Plan", 500))
        val json = SetJson.encodeSets(work(180.0))
        db.runDao().insertTrack(RunTrackEntity(0, "open", 0, id, Slot.MAIN, "hack_squat",
            plannedJson = json, setsJson = json, updatedAt = 500))
        val runs = db.runDao().allRuns()
        val tracks = db.runDao().allTracks()
        repo.plans.runPlanSettingsFixupIfNeeded()
        assertEquals(runs, db.runDao().allRuns())
        assertEquals(tracks, db.runDao().allTracks())
        assertEquals(work(20.0), rows(id))
    }

    @Test fun `seeding is idempotent`() = runTest {
        slot("hack_squat")
        slot("hack_squat").also { history("hack_squat", 150.0, 8) }
        slot("leg_press").also { history("leg_press", 100.0, 12) }
        slot("bb_back_squat", "RAMP_TOP_BACKOFF", 6).also { history("bb_back_squat", 245.0, 5, kind = SetKind.TOP) }
        slot("pullup")
        slot("hammer_curl", kept = true)
        slot("custom_gone")
        slot("ez_curl", partner = "rope_pushdown")
        repo.plans.runPlanSettingsFixupIfNeeded()
        val slots = db.programDao().allExercises()
        val logs = db.programDao().allLogs()
        repeat(2) { repo.plans.convertPlans() }
        repository().plans.runPlanSettingsFixupIfNeeded()
        assertEquals(slots, db.programDao().allExercises())
        assertEquals(logs, db.programDao().allLogs())
    }

    @Test fun `a storage failure while writing rows rolls back the rule and a later call converts`() = runTest {
        val id = slot("hack_squat")
        var failing = true
        val dao = object : ProgramDao by db.programDao() {
            override suspend fun upsertLog(log: ExerciseLogEntity) {
                if (failing) throw SQLiteFullException("Storage full")
                db.programDao().upsertLog(log)
            }
        }
        val plans = PlanRepository(db, settings, programDao = dao)
        assertEquals(listOf(plan), plans.todayPlans.first())
        assertNull(db.programDao().exercise(id)!!.ruleJson)
        assertNull(db.programDao().logForSlot(1, "A", id, Slot.MAIN))
        failing = false
        assertEquals(listOf(plan), plans.todayPlans.first())
        assertRule(id, rule(calibrating = true))
        assertEquals(work(20.0), rows(id))
    }
}
