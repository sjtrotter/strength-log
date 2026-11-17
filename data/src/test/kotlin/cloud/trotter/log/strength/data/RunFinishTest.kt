package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.data.serialization.CardioUndoJson
import cloud.trotter.log.strength.data.serialization.CardioUndoDto
import cloud.trotter.log.strength.data.serialization.CardioBlockUndoDto
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunFinishTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private var plan = 0L
    private var sequence = 0L
    private val clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC)
    private fun run(goal: RunGoal = RunGoal.FIVE_TO_TEN_K, days: Int = 3, minutes: Int = 60,
                    experience: RunExperience = RunExperience.NEW, longest: Measure? = null) =
        SetupAnswers(branch = PlanBranch.RUN, days = days, minutes = minutes,
            run = RunAnswers(goal, experience, longest = longest))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("run-finish", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings, clock)
        val history = HistoryRepository(db, settings, plans)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings,
            clock = clock, plans = plans, history = history)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun prepare(answers: SetupAnswers = run()) {
        plan = repo.plans.createPlannedPlan("Run", answers)
        repo.plans.publishPlannedPlan(plan)
    }
    private suspend fun day(id: String = "A") = assertNotNull(db.programDao().day(plan, id))
    private suspend fun block(id: String = "A") = assertNotNull(CardioBlockJson.decode(day(id).cardioBlockJson))
    private suspend fun rule(id: String = "A") = assertNotNull(CardioRuleJson.decode(day(id).cardioRuleJson))
    private suspend fun undo(id: Long) = assertNotNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)!!.progressionUndoJson))
    private fun time(seconds: Int) = CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds))))
    private fun vo2(rounds: Int) = CardioBlock(CardioActivity.ROAD_RUN, buildList<CardioSetSpec> {
        repeat(rounds) { index ->
            if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(180)))
            add(CardioSetSpec(CardioRole.WORK, Measure.Time(180), hard = true))
        }
    })
    private fun results(block: CardioBlock) = block.sets.map { spec ->
        CardioSetResult(spec, CardioSetStatus.FINISHED, (spec.end as? Measure.Time)?.seconds ?: 2400,
            (spec.end as? Measure.Distance)?.metres, if (spec.end is Measure.Distance) DistanceSource.MANUAL else null)
    }
    private suspend fun log(id: String = "A", current: CardioBlock? = null, effort: CardioEffort? = CardioEffort.EASY,
                            owner: Long? = plan, at: Long = clock.millis() + sequence++ * 1000): Long {
        val actual = current ?: block(id)
        val rows = results(actual)
        return repo.logCardioSession(CardioSessionEntity(planId = owner, dayId = id, mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = at - 3_600_000, completedAt = at, seconds = rows.sumOf { it.elapsedSeconds },
            stepsCompleted = actual.sets.size, activity = actual.activity.name, effort = effort?.name), rows)
    }
    private suspend fun ladder(level: Int) {
        for (id in listOf("A", "B", "C")) {
            val row = day(id)
            db.programDao().upsertDay(row.copy(cardioBlockJson = CardioBlockJson.encode(RunWalkLadder.block(level, CardioActivity.ROAD_RUN)),
                cardioRuleJson = CardioRuleJson.encode(CardioRule(level))))
        }
    }
    private suspend fun assertLadder(level: Int, done: Boolean = false) {
        for (id in listOf("A", "B", "C")) {
            assertEquals(RunWalkLadder.block(level, CardioActivity.ROAD_RUN), block(id))
            assertEquals(CardioRule(if (done) null else level), rule(id))
            assertNull(day(id).cardioWorkingJson)
        }
    }
    private suspend fun quality(minutes: Int = 60) {
        prepare(run(days = 5, minutes = minutes, experience = RunExperience.SOME, longest = Measure.Time(3600)))
        log(current = time(3600), owner = null, at = clock.millis() - 86_400_000)
        assertEquals(RunSession.VO2MAX, rule("D").session)
    }

    @Test fun `a ladder Easy finish moves every ladder day up one level`() = runTest {
        prepare()
        assertLadder(1)
        assertEquals(ProgramDayKind.REST.name, day("D").kind)
        val id = log()
        assertLadder(2)
        assertFalse(db.cardioSessionDao().byId(id)!!.increaseApplied)
        assertEquals(setOf("A", "B", "C"), undo(id).days.map { it.dayId }.toSet())
        assertTrue(repo.finishUndoState(null, id).restorable)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertLadder(1)
    }

    @Test fun `an unrated ladder finish repeats until a rating replays it`() = runTest {
        prepare()
        val before = db.programDao().daysForPlan(plan)
        val id = log(effort = null)
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertEquals(undo(id).rule?.before, undo(id).rule?.after)
        assertTrue(undo(id).days.isEmpty())
        repo.setCardioEffort(id, CardioEffort.EASY)
        assertLadder(2)
        repo.setCardioEffort(id, CardioEffort.GOOD)
        assertLadder(1)
        repo.setCardioEffort(id, CardioEffort.EASY)
        assertLadder(2)
        for (record in undo(id).days) {
            assertEquals(CardioRule(1), CardioRuleJson.decode(record.ruleBefore))
            assertEquals(RunWalkLadder.block(1, CardioActivity.ROAD_RUN), CardioBlockJson.decode(record.blockBefore))
        }
    }

    @Test fun `Hard drops every ladder day one level`() = runTest {
        prepare()
        ladder(3)
        log(effort = CardioEffort.HARD)
        assertLadder(2)
    }

    @Test fun `the last ladder level leaves the ladder on every day`() = runTest {
        prepare()
        ladder(12)
        log()
        assertLadder(12, done = true)
    }

    @Test fun `a quality step that adds a round writes the plan block and rule together`() = runTest {
        quality()
        assertEquals(vo2(5), block("D"))
        log("D")
        assertEquals(1, rule("D").credits)
        val id = log("D")
        assertEquals(vo2(6), block("D"))
        assertEquals(1, rule("D").steps)
        assertEquals(0, rule("D").credits)
        assertNull(day("D").cardioWorkingJson)
        assertFalse(db.cardioSessionDao().byId(id)!!.increaseApplied)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertEquals(vo2(5), block("D"))
        assertEquals(0, rule("D").steps)
        assertEquals(1, rule("D").credits)
    }

    @Test fun `a step past the session minutes waits`() = runTest {
        quality(minutes = 30)
        assertEquals(vo2(4), block("D"))
        log("D")
        log("D")
        assertEquals(vo2(4), block("D"))
        assertEquals(1, rule("D").credits)
        assertEquals(0, rule("D").steps)
    }

    @Test fun `an Ask plan moves credits but holds blocks`() = runTest {
        quality()
        repo.plans.setAutoApply(plan, false)
        log("D")
        assertEquals(1, rule("D").credits)
        val before = day("D")
        val id = log("D")
        assertEquals(before, day("D"))
        assertEquals(vo2(5), block("D"))
        assertEquals(undo(id).rule?.before, undo(id).rule?.after)
    }

    @Test fun `a tapered long run earns nothing`() = runTest {
        val answers = run(RunGoal.HALF_MARATHON, 4, 60, RunExperience.SOME, Measure.Distance(10_000))
        prepare(answers.copy(run = answers.run.copy(goalSeconds = 7200, dateEpochDay = LocalDate.now(clock).plusDays(12).toEpochDay())))
        val before = day("D")
        assertEquals(RunSession.LONG, rule("D").session)
        assertEquals(Measure.Distance(11_000), block("D").sets.single().end)
        val opening = assertNotNull(CardioJudge.opening(before.toDomain(emptyList()), db.planDao().plan(plan)!!,
            emptyList(), emptyMap(), clock.millis(), LocalDate.now(clock).toEpochDay()))
        assertEquals(Measure.Distance(6600), opening.sets.single().end)
        val id = log("D", opening)
        assertEquals(before, day("D"))
        assertNull(db.cardioSessionDao().byId(id)!!.progressionUndoJson)
    }

    @Test fun `Just cardio easy runs grow once a rotation at Finish`() = runTest {
        prepare(run(RunGoal.CARDIO_FITNESS, 3, 60, RunExperience.SOME, Measure.Time(900)))
        assertEquals(time(960), block())
        log()
        assertEquals(time(1008), block())
        assertEquals(0, rule().stepCycle)
        log()
        assertEquals(time(1008), block())
        assertEquals(0, rule().stepCycle)
    }

    @Test fun `editing a ruled run day clears its rule`() = runTest {
        quality()
        assertTrue(repo.setCardioBlock(plan, "D", vo2(4)))
        assertNull(day("D").cardioRuleJson)
        assertTrue(repo.setCardioBlock(plan, "A", null))
        assertNull(day().cardioRuleJson)
        val sprint = CardioRule(null, RunSession.ACCELERATION, steps = 12)
        db.programDao().setCardioRule(plan, "D", CardioRuleJson.encode(sprint))
        assertTrue(repo.setCardioBlock(plan, "D", vo2(3)))
        assertEquals(sprint, rule("D"))
    }

    @Test fun `a ruled run day never takes a working block`() = runTest {
        prepare()
        assertFalse(repo.setCardioWorking(plan, "A", block()))
        assertFalse(repo.setCardioWorking(plan, "A", RunWalkLadder.block(2, CardioActivity.ROAD_RUN)))
        assertNull(day().cardioWorkingJson)
    }

    @Test fun `reset day leaves run plans alone`() = runTest {
        prepare()
        log()
        val before = db.programDao().daysForPlan(plan)
        val exercises = db.programDao().exercisesForPlan(plan)
        repo.resetDayToTemplate("A", plan)
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertEquals(exercises, db.programDao().exercisesForPlan(plan))
    }

    @Test fun `old cardio undo keeps its bytes and days default empty`() {
        val json = """{"block":{"before":null,"after":"working"}}"""
        val decoded = assertNotNull(CardioUndoJson.decode(json))
        assertTrue(decoded.days.isEmpty())
        assertEquals(json, CardioUndoJson.encode(CardioUndoDto(block = CardioBlockUndoDto(null, "working"))))
    }

    @Test fun `a changed ladder sibling prevents replay and survives Undo`() = runTest {
        prepare()
        val id = log()
        val sibling = day("B")
        val edited = CardioBlockJson.encode(time(900))
        db.programDao().upsertDay(sibling.copy(cardioBlockJson = edited))
        repo.setCardioEffort(id, CardioEffort.GOOD)
        assertEquals(CardioRule(2), rule())
        assertEquals(edited, day("B").cardioBlockJson)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertEquals(CardioRule(1), rule())
        assertEquals(edited, day("B").cardioBlockJson)
        assertEquals(CardioRule(2), rule("B"))
        assertEquals(CardioRule(1), rule("C"))
    }

    @Test fun `ladder changes preserve kept days and sibling activities`() = runTest {
        prepare()
        repo.setCardioKeepToPlan(plan, "B", true)
        val sibling = day("C")
        db.programDao().upsertDay(sibling.copy(cardioBlockJson = CardioBlockJson.encode(RunWalkLadder.block(1, CardioActivity.TRAIL_RUN))))
        log()
        assertEquals(CardioRule(1), rule("B"))
        assertEquals(RunWalkLadder.block(1, CardioActivity.ROAD_RUN), block("B"))
        assertEquals(CardioRule(2), rule("C"))
        assertEquals(RunWalkLadder.block(2, CardioActivity.TRAIL_RUN), block("C"))
    }

}
