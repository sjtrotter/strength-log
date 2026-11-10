package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import java.io.File
import kotlin.test.assertEquals
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
class RunOnlySwapWritesTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private var plan = 0L
    private var h = 0L
    private var b = 0L
    private var e = 0L
    private lateinit var beforeSlots: List<ProgramExerciseEntity>
    private lateinit var beforeLogs: List<ExerciseLogEntity>
    private lateinit var beforeSessions: List<WorkoutSessionEntity>
    private lateinit var beforeSets: List<SessionSetEntity>

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("run-only-swap-writes", ".preferences_pb")
        })
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        plan = repo.plans.createAuthoredPlan("Mine", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        assertEquals("B", repo.addDay(plan, "Day B"))
        h = add("A", ProgramExercise("hack_squat"))
        lifter(h, 140.0)
        b = add("A", ProgramExercise("bb_bench"))
        repo.setStructure(b, SetStructure.RAMP_TOP_BACKOFF)
        e = add("A", ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown")))
        val l = add("B", ProgramExercise("leg_press"))
        lifter(l, 230.0)
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Day B", null, 1000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "db_row", "Row", Slot.MAIN, 0,
            "WORK", 60.0, 8, true)))
        repo.plans.runPlanSettingsFixupIfNeeded()
        beforeSlots = db.programDao().allExercises()
        beforeLogs = db.programDao().allLogs()
        beforeSessions = db.sessionDao().allSessions()
        beforeSets = db.sessionDao().allSessionSets()
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun work(n: Int, weight: Double, reps: Int) = List(n) { LoggedSet(weight, reps, SetKind.WORK) }
    private suspend fun add(day: String, exercise: ProgramExercise): Long {
        repo.addExercise(day, exercise, plan)
        return db.programDao().exercisesForDay(plan, day).last().id
    }
    private suspend fun lifter(id: Long, weight: Double) {
        val slot = assertNotNull(db.programDao().exercise(id))
        db.programDao().upsertLog(ExerciseLogEntity(plan, slot.dayId, id, Slot.MAIN,
            SetJson.encodeSets(work(3, weight, 10)), 1000))
        db.programDao().setRule(id, SlotRuleJson.encode(
            SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(10), 2, 0.025, 1, scheme = Scheme.STRENGTH)))
    }
    private suspend fun seedDay() {
        repo.ensureSeeded(plan, "A")
        beforeSlots = db.programDao().allExercises()
        beforeLogs = db.programDao().allLogs()
    }
    private suspend fun unchanged() {
        assertEquals(beforeSlots, db.programDao().allExercises())
        assertEquals(beforeLogs, db.programDao().allLogs())
        assertEquals(beforeSessions, db.sessionDao().allSessions())
        assertEquals(beforeSets, db.sessionDao().allSessionSets())
    }
    private suspend fun active(id: Long, slot: String = Slot.MAIN) =
        assertNotNull(repo.openRunFlow.first()).tracks.single { it.programExerciseId == id && it.slot == slot && !it.retired }

    @Test fun `a today only swap on an open run writes run tracks and nothing else`() = runTest {
        seedDay()
        val run = (repo.startRun(plan, "A") as RunStartResult.Started).runId
        assertTrue(repo.swapInRun(run, h, Slot.MAIN, "leg_press"))
        assertEquals(work(3, 230.0, 10), active(h).planned)
        unchanged()
        assertTrue(repo.swapInRun(run, b, Slot.MAIN, "db_row"))
        assertEquals(work(3, 55.0, 10), active(b).planned)
        unchanged()
        assertTrue(repo.swapInRun(run, e, Slot.SS, "face_pull"))
        assertEquals(work(3, 20.0, 10), active(e, Slot.SS).planned)
        unchanged()
        val retired = db.runDao().tracks(run).single { it.programExerciseId == h && it.exerciseId == "hack_squat" }
        assertTrue(retired.retired)
        db.runDao().deleteTrack(retired.id)
        assertTrue(repo.swapInRun(run, h, Slot.MAIN, "hack_squat"))
        assertEquals(work(3, 140.0, 10), active(h).planned)
        unchanged()
    }

    @Test fun `a today only swap that starts the next run writes run tracks and nothing else`() = runTest {
        seedDay()
        val next = assertNotNull(db.programDao().day(plan, "A")).nextRunId
        assertEquals(ExerciseSwapResult.CHANGED, repo.applyExerciseSwap(plan, "A", b, "db_row", next, allowChange = true))
        assertEquals(next, repo.openRunFlow.first()!!.id)
        assertEquals("db_row", active(b).exerciseId)
        assertEquals(work(3, 55.0, 10), active(b).planned)
        assertTrue(db.runDao().tracks(next).single { it.programExerciseId == b && it.exerciseId == "bb_bench" }.retired)
        unchanged()
        assertEquals(ExerciseSwapResult.CHANGED, repo.applyExerciseSwap(plan, "A", h, "leg_press", next, allowChange = true))
        assertEquals(work(3, 230.0, 10), active(h).planned)
        unchanged()
    }

    @Test fun `keeping the superset half writes run tracks and nothing else`() = runTest {
        seedDay()
        val result = repo.dropSupersetHalf(plan, "A", e, Slot.SS, "ez_curl", "rope_pushdown")
        assertTrue(result is RunWriteResult.InRun && result.started)
        assertEquals("rope_pushdown", active(e).exerciseId)
        assertEquals(work(3, 20.0, 10), active(e).planned)
        unchanged()
    }

    @Test fun `a pre start today only swap rejects an unknown substitute with an undecodable MAIN log`() = runTest {
        val corrupt = assertNotNull(db.programDao().logForSlot(plan, "A", h, Slot.MAIN)).copy(setsJson = "unreadable")
        db.programDao().upsertLog(corrupt)
        val slots = db.programDao().allExercises()
        val logs = db.programDao().allLogs()
        val day = assertNotNull(db.programDao().day(plan, "A"))

        assertEquals(ExerciseSwapResult.REJECTED,
            repo.applyExerciseSwap(plan, "A", h, "unknown", day.nextRunId, allowChange = true))

        assertNull(db.runDao().openRun())
        assertEquals(slots, db.programDao().allExercises())
        assertEquals(logs, db.programDao().allLogs())
        assertEquals(day, db.programDao().day(plan, "A"))
    }
}
