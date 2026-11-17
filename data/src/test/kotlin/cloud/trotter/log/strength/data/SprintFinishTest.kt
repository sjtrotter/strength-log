package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.data.serialization.CardioRuleUndoDto
import cloud.trotter.log.strength.data.serialization.CardioUndoDto
import cloud.trotter.log.strength.data.serialization.CardioUndoJson
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SprintFinishTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private var plan = 0L
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 11, 17)
    private val clock = Clock.fixed(Instant.parse("2026-11-18T08:10:00Z"), zone)
    private fun at(offset: Long = 0, time: String = "21:30") =
        date.plusDays(offset).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("sprint-finish", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings, clock)
        val history = HistoryRepository(db, settings, plans)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings,
            clock = clock, plans = plans, history = history)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun prepare(cycles: Int = 1, days: Int = 2) {
        val id = repo.plans.createAuthoredPlan("Sprints", "Sprint")
        repo.plans.publishCreatedAuthoredPlan(id)
        repo.replaceProgram(Planner.plan(SetupAnswers(branch = PlanBranch.SPRINT, days = days, minutes = 60,
            sprint = SprintAnswers(sprintedRecently = true))).program)
        plan = assertNotNull(repo.plans.selectedPlanIdFlow.first())
        db.planDao().updateGoal(plan, PlanGoalJson.encode(PlanGoal.Sprint(100)))
        db.planDao().updateLightCycle(plan, 0, cycles)
    }
    private fun block(rounds: Int, metres: Int, recovery: Int = 120, hard: Boolean = true) =
        CardioBlock(CardioActivity.TRACK_RUN, buildList<CardioSetSpec> {
            if (hard) add(CardioSetSpec(CardioRole.WARMUP, Measure.Time(600)))
            repeat(rounds) { index ->
                if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(recovery)))
                add(CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = hard))
            }
        })
    private fun results(block: CardioBlock) = block.sets.map { spec ->
        CardioSetResult(spec, CardioSetStatus.FINISHED, (spec.end as? Measure.Time)?.seconds ?: 5,
            (spec.end as? Measure.Distance)?.metres, if (spec.end is Measure.Distance) DistanceSource.MANUAL else null)
    }
    private fun entity(dayId: String, started: Long = at(), completed: Long = started + Duration.ofMinutes(40).toMillis()) =
        CardioSessionEntity(planId = plan, dayId = dayId, mode = "OUTDOOR_RUN", hard = true, label = "Sprint",
            startedAt = started, completedAt = completed, seconds = ((completed - started) / 1000).toInt(),
            stepsCompleted = 1, activity = CardioActivity.TRACK_RUN.name, effort = CardioEffort.GOOD.name)
    private suspend fun seed(dayId: String, offset: Long, block: CardioBlock, owner: Long = plan) {
        val id = db.cardioSessionDao().insert(entity(dayId, at(offset)).copy(planId = owner))
        db.cardioSetResultDao().insertAll(results(block).mapIndexed { index, row -> row.toEntity(id, index) })
    }
    private suspend fun seedWindow() {
        seed("A", -6, block(8, 20))
        seed("C", -4, block(3, 10, 240))
        seed("C", -2, block(3, 10, 240))
    }
    private suspend fun log(dayId: String = "A", block: CardioBlock = block(8, 20), started: Long = at(),
                            completed: Long = started + Duration.ofMinutes(40).toMillis(), finishDay: Boolean = false) =
        repo.logCardioSession(entity(dayId, started, completed), results(block), finishDay = finishDay)
    private suspend fun rule(dayId: String = "A") =
        assertNotNull(CardioRuleJson.decode(db.programDao().day(plan, dayId)!!.cardioRuleJson))
    private suspend fun undo(id: Long) = assertNotNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)!!.progressionUndoJson))
    private suspend fun opening(started: Long = at(), dayId: String = "A"): CardioBlock? {
        val program = assemble(db.programDao().daysForPlan(plan), emptyList())
        val storedPlan = assertNotNull(db.planDao().plan(plan))
        val sets = db.cardioSetResultDao().all().groupBy { it.cardioSessionId }.mapValues { (_, rows) -> rows.map { it.toDomain() } }
        return CardioJudge.opening(program.days.single { it.id == dayId }, storedPlan, db.cardioSessionDao().all(), sets,
            started, Instant.ofEpochMilli(started).atZone(zone).toLocalDate().toEpochDay(),
            SprintPlan(program, storedPlan.cyclesCompleted, PlanGoal.Sprint(100), false, zone))
    }
    private suspend fun assertShortenedFinish(started: Long, completed: Long) {
        prepare()
        seedWindow()
        val before = rule()
        val stored = db.programDao().day(plan, "A")!!
        val short = block(6, 20)
        assertEquals(short, opening(started))
        val id = log(block = short, started = started, completed = completed)
        assertEquals(before.copy(maximalCycle = 1), rule())
        assertEquals(CardioRuleJson.encode(before), undo(id).rule?.before)
        assertEquals(1, undo(id).rule?.cycle)
        assertFalse(db.cardioSessionDao().byId(id)!!.increaseApplied)
        assertEquals(stored.cardioBlockJson, db.programDao().day(plan, "A")!!.cardioBlockJson)
        assertEquals(stored.cardioWorkingJson, db.programDao().day(plan, "A")!!.cardioWorkingJson)
    }

    @Test fun `a sprint Finish replays Start across midnight`() = runTest {
        assertShortenedFinish(at(time = "23:50"), at(1, "00:10"))
    }

    @Test fun `an evening sprint run is dated by its local start`() = runTest {
        assertShortenedFinish(at(time = "21:30"), at(time = "22:10"))
    }

    @Test fun `trial restart and shortened outcomes are written`() = runTest {
        prepare(cycles = 4)
        val trial = log(block = block(1, 100))
        assertEquals(CardioRule(null, RunSession.ACCELERATION, steps = 12, sprintTrialCycle = 4, maximalCycle = 4), rule())
        assertEquals(4, undo(trial).rule?.cycle)
        prepare()
        seed("A", -20, block(8, 20))
        for (offset in -3L..-1L) seed("C", offset, block(3, 10, 240))
        assertEquals(block(4, 10, 60), opening())
        log(block = block(4, 10, 60), started = at(time = "21:31"))
        assertEquals(CardioRule(null, RunSession.ACCELERATION, intro = 2, maximalCycle = 1), rule())
        assertShortenedFinish(at(time = "23:50"), at(1, "00:10"))
    }

    @Test fun `a re-judgment replays the recorded pre-write rule`() = runTest {
        prepare()
        val before = rule()
        val id = log()
        assertEquals(before.copy(credits = 1, maximalCycle = 1), rule())
        repo.setCardioEffort(id, CardioEffort.HARD)
        assertEquals(before.copy(credits = 1, maximalCycle = 1), rule())
        assertEquals(CardioRuleJson.encode(before), undo(id).rule?.before)
        log("C", block(3, 10, 240), at(1))
        assertNull(db.cardioSessionDao().byId(id)!!.progressionUndoJson)
        repo.setCardioEffort(id, CardioEffort.EASY)
        assertEquals(before.copy(credits = 1, maximalCycle = 1), rule())
    }

    @Test fun `a tempo amendment takes back its credit`() = runTest {
        prepare(days = 5)
        val before = rule("F")
        val id = log("F", block(6, 100, 60, hard = false))
        assertEquals(before.copy(credits = 1), rule("F"))
        repo.setCardioEffort(id, CardioEffort.HARD)
        assertEquals(before, rule("F"))
        assertEquals(CardioRuleJson.encode(before), undo(id).rule?.before)
    }

    @Test fun `a sprint step is final`() = runTest {
        prepare()
        val before = rule().copy(credits = 1)
        db.programDao().setCardioRule(plan, "A", CardioRuleJson.encode(before))
        val id = log()
        assertEquals(before.copy(steps = 13, credits = 0, stepCycle = 1, maximalCycle = 1), rule())
        assertTrue(db.cardioSessionDao().byId(id)!!.increaseApplied)
        val after = rule()
        repo.setCardioEffort(id, CardioEffort.HARD)
        assertEquals(after, rule())
    }

    @Test fun `a sprint day swapped to a stationary bike earns no step or rule write`() = runTest {
        prepare()
        val before = rule().copy(credits = 1)
        db.programDao().setCardioRule(plan, "A", CardioRuleJson.encode(before))
        val storedDay = assertNotNull(db.programDao().day(plan, "A"))
        val swapped = block(8, 20).copy(activity = CardioActivity.STATIONARY_BIKE)
        val id = repo.logCardioSession(entity("A").copy(activity = swapped.activity.name), results(swapped))
        val session = assertNotNull(db.cardioSessionDao().byId(id))
        assertEquals(CardioActivity.STATIONARY_BIKE.name, session.activity)
        assertEquals(results(swapped).map { it.spec }, db.cardioSetResultDao().forSession(id).map { it.toDomain().spec })
        assertFalse(session.increaseApplied)
        assertNull(CardioUndoJson.decode(session.progressionUndoJson)?.rule)
        assertEquals(before, rule())
        assertEquals(storedDay, db.programDao().day(plan, "A"))
        repo.setCardioEffort(id, CardioEffort.EASY)
        assertEquals(storedDay, db.programDao().day(plan, "A"))
        assertNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)!!.progressionUndoJson)?.rule)
    }

    @Test fun `undo restores the pre-write sprint rule`() = runTest {
        prepare()
        val before = rule()
        val id = log()
        assertTrue(repo.finishUndoState(null, id).restorable)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertEquals(before, rule())
        assertNull(db.cardioSessionDao().byId(id)!!.progressionUndoJson)
        repo.setCardioEffort(id, CardioEffort.GOOD)
        assertEquals(before, rule())
    }

    @Test fun `a sprint day without plan inputs opens and judges nothing`() = runTest {
        prepare()
        val day = db.programDao().day(plan, "A")!!.toDomain(emptyList())
        val storedPlan = db.planDao().plan(plan)!!
        val session = entity("A").copy(id = 99)
        assertNull(CardioJudge.opening(day, storedPlan, emptyList(), emptyMap(), at(), date.toEpochDay()))
        assertNull(CardioJudge.judge(day, session, listOf(session), mapOf(99L to results(block(8, 20))), true))
    }

    @Test fun `an Ask plan still writes sprint state`() = runTest {
        prepare()
        repo.plans.setAutoApply(plan, false)
        val before = rule()
        log()
        assertEquals(before.copy(credits = 1, maximalCycle = 1), rule())
    }

    @Test fun `a changed rule or cleared undo prevents sprint replay`() = runTest {
        prepare()
        val id = log()
        val edited = rule().copy(credits = 0)
        db.programDao().setCardioRule(plan, "A", CardioRuleJson.encode(edited))
        repo.setCardioEffort(id, CardioEffort.HARD)
        assertEquals(edited, rule())
        assertFalse(repo.finishUndoState(null, id).restorable)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertEquals(edited, rule())
        val next = log(started = at(1))
        val after = rule()
        repo.clearFinishUndoForDay(plan, "A")
        repo.setCardioEffort(next, CardioEffort.EASY)
        assertEquals(after, rule())
    }

    @Test fun `a distance amendment replays without adding a second credit`() = runTest {
        prepare()
        val id = log()
        val after = rule()
        repo.amendCardioSetDistance(id, 1, 23)
        assertEquals(after, rule())
        assertEquals(0, CardioRuleJson.decode(undo(id).rule?.before)?.credits)
    }

    @Test fun `a substitute records unchanged state for replay`() = runTest {
        prepare()
        seed("A", -1, block(8, 20))
        seed("A", -1, block(8, 20))
        val before = rule()
        assertEquals(block(6, 100, 60, hard = false), opening())
        val id = log(block = block(6, 100, 60, hard = false))
        assertEquals(before, rule())
        assertEquals(CardioRuleJson.encode(before), undo(id).rule?.after)
        assertFalse(repo.finishUndoState(null, id).restorable)
        repo.setCardioEffort(id, CardioEffort.EASY)
        assertEquals(before, rule())
    }

    @Test fun `the sprint ledger uses only prior sessions from its own plan`() = runTest {
        prepare()
        seed("A", -2, block(8, 20))
        seed("C", -1, block(3, 10, 240))
        seed("A", -1, block(8, 20), owner = plan + 99)
        val before = rule()
        assertEquals(block(8, 20), opening())
        val id = log()
        // 350 - (160+30) = 160; including this session would instead force a substitute.
        assertEquals(before.copy(credits = 1, maximalCycle = 1), rule())
        assertEquals(CardioRuleJson.encode(before), undo(id).rule?.before)
    }

    @Test fun `a replay keeps the rotation recorded before Finish wraps`() = runTest {
        prepare(cycles = 4)
        val before = rule("C")
        db.planDao().updateSuggestedDay(plan, "C")
        val id = log("C", block(3, 10, 240), finishDay = true)
        assertEquals(5, db.planDao().plan(plan)!!.cyclesCompleted)
        assertEquals(4, undo(id).rule?.cycle)
        assertEquals(before.copy(credits = 1, maximalCycle = 4), rule("C"))
        repo.setCardioEffort(id, CardioEffort.HARD)
        assertEquals(before.copy(credits = 1, maximalCycle = 4), rule("C"))
        assertNotNull(undo(id).plan)
    }

    @Test fun `old cardio undo rows decode and rule records round trip`() {
        val old = assertNotNull(CardioUndoJson.decode("""{"block":{"before":null,"after":"working"}}"""))
        assertNull(old.rule)
        val dto = CardioUndoDto(rule = CardioRuleUndoDto("before", "after", 4))
        assertEquals(dto, CardioUndoJson.decode(CardioUndoJson.encode(dto)))
    }
}
