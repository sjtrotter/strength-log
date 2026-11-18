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
import cloud.trotter.log.strength.data.serialization.ProgressionUndoJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
class RunReplanFinishTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private var plan = 0L
    private var sequence = 0L
    private val start = Instant.parse("2026-11-17T12:00:00Z")
    private var now = start
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private val d = start.atZone(clock.zone).toLocalDate().toEpochDay()
    private val credited = CardioRule(12, credits = 1, ladderCounted = listOf(d - 2), ladderRaised = d - 9)
    private fun run(goal: RunGoal = RunGoal.FIVE_TO_TEN_K, days: Int = 4,
                    experience: RunExperience = RunExperience.NEW, longest: Measure? = null) =
        SetupAnswers(branch = PlanBranch.RUN, days = days, minutes = 60, run = RunAnswers(goal, experience, longest = longest))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("run-replan-finish", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings, clock)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings,
            clock = clock, plans = plans, history = HistoryRepository(db, settings, plans))
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun prepare(answers: SetupAnswers = run()) {
        plan = repo.plans.createPlannedPlan("Run", answers)
        repo.plans.publishPlannedPlan(plan)
    }
    private suspend fun day(id: String) = assertNotNull(db.programDao().day(plan, id))
    private suspend fun block(id: String) = assertNotNull(day(id).toDomain(emptyList()).cardioToRun)
    private suspend fun rule(id: String) = CardioRuleJson.decode(day(id).cardioRuleJson)
    private suspend fun recipe() = assertNotNull(db.planDao().plan(plan)?.recipeJson)
    private suspend fun phase() = SetupRecipeJson.phase(recipe())
    private suspend fun undo(id: Long) = assertNotNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)?.progressionUndoJson))
    private fun time(seconds: Int) = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds))))
    private suspend fun ladder() {
        for (entity in db.programDao().daysForPlan(plan).filter { CardioRuleJson.decode(it.cardioRuleJson)?.ladderLevel != null }) {
            db.programDao().upsertDay(entity.copy(cardioBlockJson = CardioBlockJson.encode(RunWalkLadder.block(12, CardioActivity.ROAD_RUN)),
                cardioRuleJson = CardioRuleJson.encode(credited)))
        }
    }
    private suspend fun log(id: String? = null, atDay: Int, current: CardioBlock? = null,
                            effort: CardioEffort? = CardioEffort.EASY, finished: Boolean = true): Long {
        now = start.plusSeconds(atDay * 86_400L + sequence++)
        val actual = current ?: block(requireNotNull(id))
        val rows = actual.sets.map { spec ->
            val seconds = (spec.end as? Measure.Time)?.seconds ?: (spec.aim as? Measure.Time)?.seconds
                ?: ((spec.end as Measure.Distance).metres * 0.36).toInt()
            val metres = (spec.end as? Measure.Distance)?.metres
            CardioSetResult(spec, if (finished) CardioSetStatus.FINISHED else CardioSetStatus.SHORTENED,
                if (finished) seconds else 0, metres, metres?.let { DistanceSource.MANUAL })
        }
        return repo.logCardioSession(CardioSessionEntity(planId = if (id == null) null else plan, dayId = id,
            mode = "OUTDOOR_RUN", hard = false, label = "Run", startedAt = clock.millis() - 3_600_000,
            completedAt = clock.millis(), seconds = rows.sumOf { it.elapsedSeconds }, stepsCompleted = actual.sets.size,
            activity = actual.activity.name, effort = effort?.name), rows, finishDay = id != null)
    }
    private suspend fun wrap(good: Boolean = false): Long {
        prepare()
        ladder()
        log("A", 0, effort = if (good) CardioEffort.GOOD else CardioEffort.EASY)
        log("B", 1, effort = if (good) CardioEffort.GOOD else CardioEffort.EASY)
        return log("C", 2)
    }
    private suspend fun assertBase() {
        assertEquals(RunPhase.BASE, phase())
        assertEquals(listOf("A", "B", "C", "D", "E"), db.programDao().daysForPlan(plan).map { it.dayId })
        for ((id, seconds) in listOf("A" to 1440, "C" to 1440, "D" to 1980)) {
            assertEquals(time(seconds), block(id))
        }
        // RunRange: a threshold block's WORK sets are hard.
        assertEquals(time(1200).copy(sets = time(1200).sets.map { it.copy(hard = true) }), block("B"))
        assertEquals(CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.VO2MAX), rule("B"))
        assertEquals(CardioRule(null, RunSession.LONG, longSeconds = 5400), rule("D"))
        assertEquals(ProgramDayKind.REST.name, day("E").kind)
        assertEquals("A", db.planDao().plan(plan)?.suggestedDayId)
    }

    @Test fun `the wrap after the ladder ends re-plans to base in the same Finish`() = runTest {
        prepare()
        assertEquals(RunPhase.LADDER, phase())
        ladder()
        val exercises = db.programDao().exercisesForPlan(plan)
        log("A", 0)
        for (id in listOf("A", "B", "C")) assertEquals(CardioRule(null), rule(id))
        assertEquals(RunPhase.LADDER, phase())
        log("B", 1)
        assertEquals(time(1890), block("B"))
        val session = log("C", 2)
        assertBase()
        assertEquals(exercises, db.programDao().exercisesForPlan(plan))
        val record = assertNotNull(undo(session).plan?.replan)
        assertEquals(listOf("A", "B", "C", "D", "E"), record.days.map { it.dayId })
        assertTrue(record.days.take(4).all { it.before != null })
        assertNull(record.days.last().before)
        assertTrue(repo.finishUndoState(null, session).restorable)
    }

    @Test fun `undo of the wrapping Finish restores the ladder program and phase`() = runTest {
        prepare()
        ladder()
        log("A", 0)
        log("B", 1)
        val before = db.programDao().daysForPlan(plan)
        val oldRecipe = recipe()
        val cycles = db.planDao().plan(plan)!!.cyclesCompleted
        val session = log("C", 2)
        assertBase()
        assertEquals(UndoResult.Undone, repo.undoFinish(null, session))
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertEquals(oldRecipe, recipe())
        assertEquals(RunPhase.LADDER, phase())
        assertEquals(cycles, db.planDao().plan(plan)!!.cyclesCompleted)
        assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
    }

    @Test fun `undo of a wrap that left the ladder restores the ladder on every day`() = runTest {
        val session = wrap(good = true)
        assertBase()
        assertEquals(UndoResult.Undone, repo.undoFinish(null, session))
        assertEquals(RunPhase.LADDER, phase())
        assertNull(db.programDao().day(plan, "E"))
        for (id in listOf("A", "B", "C")) {
            assertEquals(credited, rule(id))
            assertEquals(RunWalkLadder.block(12, CardioActivity.ROAD_RUN), block(id))
        }
    }

    @Test fun `undo leaves a re-plan in place once a day changed`() = runTest {
        val session = wrap()
        assertTrue(repo.setCardioBlock(plan, "A", time(900)))
        assertEquals(UndoResult.Undone, repo.undoFinish(null, session))
        assertEquals(RunPhase.BASE, phase())
        assertEquals(time(900), block("A"))
        assertNotNull(db.programDao().day(plan, "E"))
        assertEquals(0, db.planDao().plan(plan)!!.cyclesCompleted)
    }

    @Test fun `the gate reads the log at the wrap`() = runTest {
        prepare(run(RunGoal.HALF_MARATHON, 3, RunExperience.SOME, Measure.Time(1800)))
        log("A", 0)
        log(atDay = 1, current = time(3600))
        assertEquals(RunPhase.BASE, phase())
        log("B", 2)
        val session = log("C", 3)
        assertEquals(RunPhase.HALF_BUILD, phase())
        assertNotNull(undo(session).plan?.replan)
    }

    @Test fun `an Ask plan does not re-plan`() = runTest {
        prepare(run(RunGoal.HALF_MARATHON, 3, RunExperience.SOME, Measure.Time(1800)))
        repo.plans.setAutoApply(plan, false)
        log(atDay = 0, current = time(3600))
        log("A", 1)
        log("B", 2)
        val session = log("C", 3)
        assertEquals(RunPhase.BASE, phase())
        assertNull(undo(session).plan?.replan)
    }

    @Test fun `a second wrap at the same phase changes nothing`() = runTest {
        prepare(run(RunGoal.HALF_MARATHON, 3, RunExperience.SOME, Measure.Time(1800)))
        log(atDay = 0, current = time(3600))
        log("A", 1)
        log("B", 2)
        log("C", 3)
        assertEquals(RunPhase.HALF_BUILD, phase())
        val before = db.programDao().daysForPlan(plan)
        val recipe = recipe()
        log("A", 8, finished = false)
        log("B", 9, finished = false)
        val session = log("C", 10, finished = false)
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertEquals(recipe, recipe())
        assertNull(undo(session).plan?.replan)
    }

    @Test fun `a wrap with no finished run in its week waits`() = runTest {
        prepare(run(RunGoal.HALF_MARATHON, 1, RunExperience.SOME, Measure.Time(1800)))
        log(atDay = -10, current = time(3600))
        val first = log("A", 0, finished = false)
        assertEquals(RunPhase.BASE, phase())
        assertNull(undo(first).plan?.replan)
        val next = log("A", 7)
        assertEquals(RunPhase.HALF_BUILD, phase())
        assertNotNull(undo(next).plan?.replan)
    }

    @Test fun `a lift plan wraps as before`() = runTest {
        prepare(SetupAnswers())
        var last = 0L
        for (day in db.programDao().daysForPlan(plan).filter { it.kind == ProgramDayKind.STRENGTH.name }) {
            val run = repo.startRun(plan, day.dayId) as RunStartResult.Started
            last = assertNotNull(repo.finishRun(run.runId))
        }
        val record = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(last)?.progressionUndoJson))
        assertNull(record.plan?.replan)
        assertEquals(1, db.planDao().plan(plan)!!.cyclesCompleted)
        assertNull(phase())
    }

    @Test fun `a strength Finish can wrap and undo a run phase gate`() = runTest {
        prepare(run(days = 6).copy(branch = PlanBranch.BOTH, both = BothAnswers(true, BothAnswers.Together.SEPARATE_DAYS)))
        ladder()
        val days = db.programDao().daysForPlan(plan)
        var last = 0L
        for ((index, day) in days.filter { it.kind != ProgramDayKind.REST.name }.withIndex()) {
            if (day.kind == ProgramDayKind.CARDIO.name) log(day.dayId, index)
            else {
                now = start.plusSeconds(index * 86_400L)
                val run = repo.startRun(plan, day.dayId) as RunStartResult.Started
                last = assertNotNull(repo.finishRun(run.runId))
            }
        }
        assertEquals(RunPhase.BASE, phase())
        assertEquals("A", db.planDao().plan(plan)?.suggestedDayId)
        val record = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(last)?.progressionUndoJson))
        assertNotNull(record.plan?.replan)
        assertTrue(repo.finishUndoState(last, null).restorable)
        assertEquals(UndoResult.Undone, repo.undoFinish(last, null))
        assertEquals(RunPhase.LADDER, phase())
        assertFalse(db.programDao().daysForPlan(plan).any { it.dayId !in days.map { old -> old.dayId } })
    }
}
