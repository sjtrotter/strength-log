package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.progression.WorkingRows
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

abstract class FinishProgressionFixture {
    protected lateinit var db: StrengthDatabase
    protected lateinit var scope: CoroutineScope
    protected lateinit var settings: SettingsStore
    protected lateinit var repo: TrackerRepository
    protected open val fileBacked = false
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseName = "progression-undo-test.db"
    private lateinit var settingsFile: File
    private var now = Instant.parse("2026-11-09T12:00:00Z")
    protected val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    @Before fun setUp() = runBlocking {
        if (fileBacked) context.deleteDatabase(databaseName)
        db = openDatabase()
        settingsFile = File.createTempFile("finish-progression", ".preferences_pb")
        openSettings()
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        repo = repository()
    }

    private fun openDatabase(): StrengthDatabase = (if (fileBacked)
        Room.databaseBuilder(context, StrengthDatabase::class.java, databaseName)
        else Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)).allowMainThreadQueries().build()

    private fun openSettings() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })
    }

    protected suspend fun restart() {
        db.close()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db = openDatabase()
        openSettings()
        repo = repository()
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
        context.deleteDatabase(databaseName)
        settingsFile.delete()
    }

    protected fun repository(dao: ProgramDao = db.programDao()) =
        TrackerRepository(db, dao, db.sessionDao(), db.customExerciseDao(), settings, clock)
    protected fun work(n: Int = 3, w: Double = 185.0, r: Int = 5) = List(n) { LoggedSet(w, r, SetKind.WORK) }
    protected fun rule(role: SlotRole = SlotRole.MAIN, reps: Int = 5, inc: Double = 0.0125,
                       scheme: Scheme = Scheme.STRENGTH, calibrating: Boolean = false) =
        SlotRule(role, RepTarget.Fixed(reps), 2, inc, 1, 0.05, scheme, state = SlotState(calibrating = calibrating))
    protected suspend fun converted(vararg days: ProgramDay): Long {
        repo.replaceProgram(Program(days.toList()))
        repo.plans.convertPlans()
        return repo.plans.selectedPlanIdFlow.first()!!
    }
    protected fun day(vararg exercises: String, id: String = "A") =
        ProgramDay(id, "Day $id", "", exercises.map { ProgramExercise(it, targetSets = 3) })
    protected suspend fun authored(): Long = repo.plans.createAuthoredPlan("Mine", "Day A").also {
        repo.plans.publishCreatedAuthoredPlan(it)
    }
    protected suspend fun add(plan: Long, exercise: String, day: String = "A"): Long {
        repo.addExercise(day, ProgramExercise(exercise, targetSets = 3), plan)
        return id(plan, exercise, day)
    }
    protected suspend fun id(plan: Long, exercise: String, day: String = "A") =
        db.programDao().exercisesForDay(plan, day).single { it.exerciseId == exercise }.id
    protected suspend fun lifter(plan: Long, id: Long, rows: List<LoggedSet> = work(), rule: SlotRule = rule(), day: String = "A") {
        db.programDao().upsertLog(ExerciseLogEntity(plan, day, id, Slot.MAIN, SetJson.encodeSets(rows), 1000))
        db.programDao().setRule(id, SlotRuleJson.encode(rule))
    }
    protected suspend fun log(plan: Long, id: Long, day: String = "A") =
        assertNotNull(db.programDao().logForSlot(plan, day, id, Slot.MAIN))
    protected suspend fun rows(plan: Long, id: Long, day: String = "A") = SetJson.decodeSets(log(plan, id, day).setsJson)
    protected suspend fun rule(id: Long) = assertNotNull(SlotRuleJson.decode(db.programDao().exercise(id)!!.ruleJson))
    protected suspend fun start(plan: Long, day: String = "A") = (repo.startRun(plan, day) as RunStartResult.Started).runId
    protected suspend fun perform(run: String, plan: Long, id: Long, weight: Double, reps: List<Int> = listOf(5, 5, 5), day: String = "A") {
        repo.applyToRun(run, plan, day, listOf(TrackEdit(id, Slot.MAIN) { rows ->
            rows.mapIndexed { i, row -> row.copy(weightLb = weight, reps = reps[i], done = true) }
        }))
    }
    protected suspend fun finish(plan: Long, id: Long, weight: Double = 185.0, reps: List<Int> = listOf(5, 5, 5), day: String = "A"): Long {
        val run = start(plan, day)
        perform(run, plan, id, weight, reps, day)
        return assertNotNull(repo.finishRun(run)).also { now = now.plusSeconds(1) }
    }
    protected suspend fun offer(plan: Long, exercise: String, day: String = "A") =
        repo.history.slotSessions(plan, day).getValue(exercise to Slot.MAIN).offer
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishProgressionTest : FinishProgressionFixture() {
    @Test fun `a converted plan keeps the opening load and offers the increase`() = runTest {
        val plan = converted(day("bb_bench"))
        val id = id(plan, "bb_bench")
        lifter(plan, id)
        finish(plan, id)
        assertEquals(work(), rows(plan, id))
        assertEquals(1, rule(id).state.successStreak)
        assertEquals(1, rule(id).state.exposures)
        assertEquals(NextOffer(185.0, 190.0), offer(plan, "bb_bench"))
        val run = db.programDao().day(plan, "A")!!.nextRunId
        repo.setWorkingWeight(run, plan, "A", id, Slot.MAIN, 190.0)
        assertEquals(work(w = 190.0), rows(plan, id))
    }

    @Test fun `an authored plan applies the increase`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 95.0), rule(inc = 0.025))
        finish(plan, id, 95.0)
        assertEquals(work(w = 100.0), rows(plan, id))
        assertEquals(NextOffer(95.0, 100.0), offer(plan, "bb_back_squat"))
        assertEquals(1, rule(id).state.successStreak)
    }

    @Test fun `a lowered weight is offered in Ask and becomes the opening after acceptance`() = runTest {
        val plan = converted(day("bb_bench"))
        val id = id(plan, "bb_bench")
        lifter(plan, id)
        finish(plan, id, 175.0)
        assertEquals(work(), rows(plan, id))
        assertEquals(listOf(0, 0, 0), rule(id).state.let { listOf(it.successStreak, it.repMissStreak, it.effortMissStreak) })
        assertEquals(NextOffer(185.0, 175.0), offer(plan, "bb_bench"))
        repo.setWorkingWeight(db.programDao().day(plan, "A")!!.nextRunId, plan, "A", id, Slot.MAIN, 175.0)
        finish(plan, id, 175.0)
        assertEquals(work(w = 175.0), rows(plan, id))
        assertEquals(NextOffer(175.0, 180.0), offer(plan, "bb_bench"))
    }

    @Test fun `two rep misses hold then offer the drop in Ask and apply it when Automatic`() = runTest {
        for (automatic in listOf(false, true)) {
            val plan = if (automatic) authored() else converted(day("bb_bench"))
            val id = if (automatic) add(plan, "bb_bench") else id(plan, "bb_bench")
            lifter(plan, id)
            finish(plan, id, reps = listOf(5, 5, 3))
            assertEquals(work(), rows(plan, id))
            assertEquals(1, rule(id).state.repMissStreak)
            assertNull(offer(plan, "bb_bench"))
            finish(plan, id, reps = listOf(5, 4, 4))
            assertEquals(work(w = if (automatic) 175.0 else 185.0), rows(plan, id))
            assertEquals(NextOffer(185.0, 175.0), offer(plan, "bb_bench"))
            assertEquals(0, rule(id).state.repMissStreak)
            assertEquals(listOf(java.time.LocalDate.of(2026, 11, 9).toEpochDay()), rule(id).state.resetEpochDays)
        }
    }

    @Test fun `reps come first on a coarse step and on a bodyweight lift`() = runTest {
        val plan = converted(day("db_row", "pushup"))
        val row = id(plan, "db_row")
        val pushup = id(plan, "pushup")
        lifter(plan, row, work(w = 40.0, r = 10), rule(SlotRole.ACCESSORY, 10))
        lifter(plan, pushup, work(w = 0.0, r = 15), rule(SlotRole.ACCESSORY, 15))
        val run = start(plan)
        perform(run, plan, row, 40.0, listOf(10, 10, 10))
        perform(run, plan, pushup, 0.0, listOf(15, 15, 15))
        repo.finishRun(run)
        assertEquals(work(w = 40.0, r = 10), rows(plan, row))
        assertEquals(RepTarget.Fixed(10), rule(row).target)
        assertEquals(NextOffer(40.0, 40.0, 10, 11), offer(plan, "db_row"))
        val next = db.programDao().day(plan, "A")!!.nextRunId
        repo.setWorkingWeight(next, plan, "A", row, Slot.MAIN, 40.0)
        repo.setWorkingWeight(next, plan, "A", pushup, Slot.MAIN, 0.0)
        assertEquals(work(w = 40.0, r = 11), rows(plan, row))
        assertEquals(RepTarget.Range(10, 14, 11), rule(row).target)
        assertEquals(work(w = 0.0, r = 16), rows(plan, pushup))
        assertEquals(RepTarget.Fixed(16), rule(pushup).target)
    }

    @Test fun `plan mode, a partner, a today-only swap and a slot without a rule write what they did before`() = runTest {
        val plan = converted(day("bb_bench", "ez_curl", "hack_squat", "leg_press"))
        val b = id(plan, "bb_bench")
        val c = id(plan, "ez_curl")
        val h = id(plan, "hack_squat")
        val l = id(plan, "leg_press")
        lifter(plan, b)
        repo.setSupersetPartner("A", b, "cable_row")
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", b, Slot.SS, SetJson.encodeSets(work(w = 120.0, r = 10)), 1000))
        lifter(plan, c, work(w = 60.0, r = 10), rule(SlotRole.ACCESSORY, 10))
        repo.setKeepToPlan(c, true)
        repo.ensureSeeded(plan, "A")
        lifter(plan, h, work(w = 140.0, r = 10), rule(SlotRole.ACCESSORY, 10))
        lifter(plan, l, work(w = 230.0, r = 10), rule(SlotRole.ACCESSORY, 10))
        db.programDao().setRule(l, "junk")
        val before = log(plan, h)
        val beforeRule = db.programDao().exercise(h)!!.ruleJson
        val run = start(plan)
        repo.swapInRun(run, h, Slot.MAIN, "leg_press")
        val tracks = repo.openRunFlow.first()!!.tracks.filter { !it.retired }
        repo.applyToRun(run, plan, "A", tracks.map { track -> TrackEdit(track.programExerciseId!!, track.slot) { rows -> rows.map { it.copy(done = true) } } })
        val finished = repo.openRunFlow.first()!!.tracks
        repo.finishRun(run)
        val ss = finished.single { it.programExerciseId == b && it.slot == Slot.SS && !it.retired }
        assertEquals(WorkingRows.carried(ss.planned, ss.sets), SetJson.decodeSets(db.programDao().logForSlot(plan, "A", b, Slot.SS)!!.setsJson))
        assertNull(repo.history.slotSessions(plan, "A").getValue("cable_row" to Slot.SS).offer)
        assertNull(db.programDao().logForSlot(plan, "A", c, Slot.MAIN))
        assertEquals(before, log(plan, h))
        assertEquals(beforeRule, db.programDao().exercise(h)!!.ruleJson)
        val unruled = finished.single { it.programExerciseId == l && !it.retired }
        assertEquals(WorkingRows.carried(unruled.planned, unruled.sets), rows(plan, l))
        assertEquals("junk", db.programDao().exercise(l)!!.ruleJson)
        assertNull(offer(plan, "leg_press"))
    }

    @Test fun `accepting the chip during a run judges from the accepted weight`() = runTest {
        val plan = converted(day("bb_bench"))
        val id = id(plan, "bb_bench")
        lifter(plan, id)
        val run = start(plan)
        repo.setWorkingWeight(run, plan, "A", id, Slot.MAIN, 190.0)
        assertEquals(work(w = 190.0), rows(plan, id))
        perform(run, plan, id, 190.0)
        repo.finishRun(run)
        assertEquals(work(w = 190.0), rows(plan, id))
        assertEquals(NextOffer(190.0, 195.0), offer(plan, "bb_bench"))
    }

    @Test fun `a Finish that fails writes nothing`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 95.0), rule(inc = 0.025))
        val run = start(plan)
        perform(run, plan, id, 95.0)
        val beforeRun = repo.openRunFlow.first()
        val beforeLogs = db.programDao().allLogs()
        val beforeRules = db.programDao().allExercises()
        val failing = repository(object : ProgramDao by db.programDao() {
            override suspend fun setRule(id: Long, json: String) { throw SQLiteFullException("full") }
        })
        assertFailsWith<SQLiteFullException> { failing.finishRun(run) }
        assertEquals(beforeRun, repo.openRunFlow.first())
        assertEquals(beforeLogs, db.programDao().allLogs())
        assertEquals(beforeRules, db.programDao().allExercises())
        assertTrue(db.sessionDao().allSessions().isEmpty())
        assertNotNull(repo.finishRun(run))
    }

    @Test fun `success credits matching targets across days without changing their load`() = runTest {
        val plan = converted(day("bb_back_squat"), day("bb_back_squat", id = "B"), day("bb_back_squat", id = "C"))
        val a = id(plan, "bb_back_squat")
        val b = id(plan, "bb_back_squat", "B")
        val c = id(plan, "bb_back_squat", "C")
        val cadence = rule(inc = 0.025).copy(everyExposures = 2)
        lifter(plan, a, work(w = 95.0), cadence)
        lifter(plan, b, work(w = 95.0, r = 10), cadence.copy(target = RepTarget.Fixed(10)), "B")
        lifter(plan, c, work(w = 95.0), cadence, "C")
        finish(plan, a, 95.0)
        assertEquals(1, rule(c).state.successStreak)
        assertEquals(0, rule(b).state.successStreak)
        assertEquals(work(w = 95.0), rows(plan, c, "C"))
        finish(plan, c, 95.0, day = "C")
        assertEquals(NextOffer(95.0, 100.0), offer(plan, "bb_back_squat", "C"))
    }

    @Test fun `a lowered set gives no cross-day credit and Plan slots receive none`() = runTest {
        val plan = converted(day("bb_back_squat"), day("bb_back_squat", id = "C"))
        val a = id(plan, "bb_back_squat")
        val c = id(plan, "bb_back_squat", "C")
        lifter(plan, a)
        lifter(plan, c, day = "C")
        val run = start(plan)
        repo.applyToRun(run, plan, "A", listOf(TrackEdit(a, Slot.MAIN) { rows -> rows.mapIndexed { i, row ->
            row.copy(done = true, weightLb = if (i == 0) 175.0 else 185.0)
        } }))
        repo.finishRun(run)
        assertEquals(0, rule(c).state.successStreak)
        repo.setKeepToPlan(c, true)
        val before = rule(c)
        finish(plan, a)
        assertEquals(before, rule(c))
    }
    @Test fun `Ask calibration waits for acceptance and an in-run acceptance survives Discard`() = runTest {
        val plan = converted(day("bb_back_squat"))
        val id = id(plan, "bb_back_squat")
        val original = rule(inc = 0.025, calibrating = true)
        lifter(plan, id, work(w = 45.0), original)
        finish(plan, id, 45.0)
        assertEquals(work(w = 45.0), rows(plan, id))
        assertTrue(rule(id).state.calibrating)
        val proposed = assertNotNull(offer(plan, "bb_back_squat"))
        assertTrue(proposed.calibration)
        val run = start(plan)
        repo.setWorkingWeight(run, plan, "A", id, Slot.MAIN, proposed.toLb)
        assertEquals(work(w = proposed.toLb), rows(plan, id))
        assertEquals(false, rule(id).state.calibrating)
        repo.discardRun(run)
        assertEquals(work(w = proposed.toLb), rows(plan, id))
        assertEquals(false, rule(id).state.calibrating)
    }

    @Test fun `Ask range top-out accepts and restores its rows and target together`() = runTest {
        val plan = converted(day("db_row"))
        val id = id(plan, "db_row")
        lifter(plan, id, work(w = 60.0, r = 12), rule(SlotRole.ACCESSORY, 12).copy(target = RepTarget.Range(8, 12, 12)))
        finish(plan, id, 60.0, listOf(12, 12, 12))
        assertEquals(work(w = 60.0, r = 12), rows(plan, id))
        assertEquals(RepTarget.Range(8, 12, 12), rule(id).target)
        val run = db.programDao().day(plan, "A")!!.nextRunId
        repo.setWorkingWeight(run, plan, "A", id, Slot.MAIN, 65.0)
        assertEquals(work(w = 65.0, r = 8), rows(plan, id))
        assertEquals(RepTarget.Range(8, 12, 8), rule(id).target)
        repo.setWorkingWeight(run, plan, "A", id, Slot.MAIN, 60.0)
        assertEquals(work(w = 60.0, r = 12), rows(plan, id))
        assertEquals(RepTarget.Range(8, 12, 12), rule(id).target)
    }

    @Test fun `Automatic coarse and bodyweight successes apply their rep targets`() = runTest {
        val plan = authored()
        val row = add(plan, "db_row")
        val pushup = add(plan, "pushup")
        lifter(plan, row, work(w = 40.0, r = 10), rule(SlotRole.ACCESSORY, 10))
        lifter(plan, pushup, work(w = 0.0, r = 15), rule(SlotRole.ACCESSORY, 15))
        val run = start(plan)
        perform(run, plan, row, 40.0, listOf(10, 10, 10))
        perform(run, plan, pushup, 0.0, listOf(15, 15, 15))
        repo.finishRun(run)
        assertEquals(work(w = 40.0, r = 11), rows(plan, row))
        assertEquals(RepTarget.Range(10, 14, 11), rule(row).target)
        assertEquals(work(w = 0.0, r = 16), rows(plan, pushup))
        assertEquals(RepTarget.Fixed(16), rule(pushup).target)
    }

    @Test fun `a malformed working row carries forward without changing its rule`() = runTest {
        val plan = converted(day("bb_bench"))
        val id = id(plan, "bb_bench")
        lifter(plan, id)
        val run = start(plan)
        perform(run, plan, id, 185.0)
        val track = repo.openRunFlow.first()!!.tracks.single()
        val before = db.programDao().exercise(id)!!.ruleJson
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", id, Slot.MAIN,
            SetJson.encodeSets(listOf(LoggedSet(45.0, 5, SetKind.RAMP))), 1000))
        repo.finishRun(run)
        assertEquals(WorkingRows.carried(track.planned, track.sets), rows(plan, id))
        assertEquals(before, db.programDao().exercise(id)!!.ruleJson)
        assertNull(offer(plan, "bb_bench"))
    }

    @Test fun `Automatic adopts a majority lowered load and holds without a majority`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_bench")
        lifter(plan, id)
        suspend fun performWeights(weights: List<Double>) {
            val run = start(plan)
            repo.applyToRun(run, plan, "A", listOf(TrackEdit(id, Slot.MAIN) { rows ->
                rows.mapIndexed { i, row -> row.copy(done = true, weightLb = weights[i]) }
            }))
            repo.finishRun(run)
        }
        performWeights(listOf(175.0, 175.0, 185.0))
        assertEquals(work(w = 175.0), rows(plan, id))
        assertEquals(0, rule(id).state.successStreak)
        assertEquals(NextOffer(185.0, 175.0), offer(plan, "bb_bench"))
        performWeights(listOf(170.0, 175.0, 180.0))
        assertEquals(work(w = 175.0), rows(plan, id))
        assertEquals(1, rule(id).state.repMissStreak)
        assertNull(offer(plan, "bb_bench"))
    }

    @Test fun `a stale rep offer cannot apply its load without its target`() = runTest {
        val plan = converted(day("db_row"))
        val id = id(plan, "db_row")
        lifter(plan, id, work(w = 40.0, r = 10), rule(SlotRole.ACCESSORY, 10))
        finish(plan, id, 40.0, listOf(10, 10, 10))
        val edited = log(plan, id).copy(setsJson = SetJson.encodeSets(work(w = 40.0, r = 9)), updatedAt = 2000)
        db.programDao().upsertLog(edited)
        val beforeRule = db.programDao().exercise(id)!!.ruleJson
        assertEquals(RunWriteResult.Rejected, repo.setWorkingWeight(db.programDao().day(plan, "A")!!.nextRunId,
            plan, "A", id, Slot.MAIN, 40.0))
        assertEquals(edited, log(plan, id))
        assertEquals(beforeRule, db.programDao().exercise(id)!!.ruleJson)
    }

}
