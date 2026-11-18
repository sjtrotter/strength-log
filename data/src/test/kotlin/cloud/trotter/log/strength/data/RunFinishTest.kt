package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioResultDto
import cloud.trotter.log.strength.data.serialization.runResult
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.LadderHold
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
    private val d = 20_774L
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
                            owner: Long? = plan, at: Long = clock.millis() + sequence++ * 1000, day: Int = 0): Long {
        val actual = current ?: block(id)
        val rows = results(actual)
        val completed = at + day * 86_400_000L
        return repo.logCardioSession(CardioSessionEntity(planId = owner, dayId = id, mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = completed - 3_600_000, completedAt = completed, seconds = rows.sumOf { it.elapsedSeconds },
            stepsCompleted = actual.sets.size, activity = actual.activity.name, effort = effort?.name), rows)
    }
    private suspend fun ladder(rule: CardioRule) {
        val level = requireNotNull(rule.ladderLevel)
        for (id in listOf("A", "B", "C")) {
            val row = day(id)
            db.programDao().upsertDay(row.copy(cardioBlockJson = CardioBlockJson.encode(RunWalkLadder.block(level, CardioActivity.ROAD_RUN)),
                cardioRuleJson = CardioRuleJson.encode(rule)))
        }
    }
    private suspend fun assertLadder(level: Int, rule: CardioRule = CardioRule(level), done: Boolean = false) {
        for (id in listOf("A", "B", "C")) {
            assertEquals(RunWalkLadder.block(level, CardioActivity.ROAD_RUN), block(id))
            assertEquals(if (done) CardioRule(null) else rule, rule(id))
            assertNull(day(id).cardioWorkingJson)
        }
    }
    private suspend fun quality(minutes: Int = 60) {
        prepare(run(days = 5, minutes = minutes, experience = RunExperience.SOME, longest = Measure.Time(3600)))
        log(current = time(3600), owner = null, at = clock.millis() - 86_400_000)
        assertEquals(RunSession.VO2MAX, rule("D").session)
    }

    @Test fun `two ladder Easy finishes move every ladder day up one level`() = runTest {
        prepare()
        assertLadder(1)
        assertEquals(ProgramDayKind.REST.name, day("D").kind)
        val first = log()
        val credit = CardioRule(1, credits = 1, ladderCounted = listOf(d))
        // ladder 2026-11-17: the first Easy earns a shared credit at level 1 instead of raising to 2.
        assertLadder(1, credit)
        assertFalse(db.cardioSessionDao().byId(first)!!.increaseApplied)
        assertEquals(setOf("A", "B", "C"), undo(first).days.map { it.dayId }.toSet())
        assertTrue(repo.finishUndoState(null, first).restorable)
        val second = log("B", day = 2)
        assertLadder(2, CardioRule(2, ladderCounted = listOf(d, d + 2), ladderRaised = d + 2))
        assertTrue(repo.finishUndoState(null, second).restorable)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, second))
        // ladder 2026-11-17: Undo of the second Finish restores the first credit and its date, not an empty rule.
        assertLadder(1, credit)
    }

    @Test fun `an unrated ladder finish repeats until a rating replays it`() = runTest {
        prepare()
        val before = db.programDao().daysForPlan(plan)
        val id = log(effort = null)
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertEquals(undo(id).rule?.before, undo(id).rule?.after)
        assertTrue(undo(id).days.isEmpty())
        repo.setCardioEffort(id, CardioEffort.EASY)
        val credit = CardioRule(1, credits = 1, ladderCounted = listOf(d))
        // ladder 2026-11-17: one Easy replay earns credit on level 1, previously level 2.
        assertLadder(1, credit)
        repo.setCardioEffort(id, CardioEffort.GOOD)
        assertLadder(1)
        repo.setCardioEffort(id, CardioEffort.EASY)
        // ladder 2026-11-17: replay restores the recorded befores before adding the same dated credit.
        assertLadder(1, credit)
        for (record in undo(id).days) {
            assertEquals(CardioRule(1), CardioRuleJson.decode(record.ruleBefore))
            assertEquals(RunWalkLadder.block(1, CardioActivity.ROAD_RUN), CardioBlockJson.decode(record.blockBefore))
        }
    }

    @Test fun `Hard drops every ladder day one level`() = runTest {
        prepare()
        ladder(CardioRule(3, credits = 1, ladderCounted = listOf(d - 1), ladderRaised = d - 1))
        log(effort = CardioEffort.HARD)
        // ladder 2026-11-17: Hard clears the credit but retains counted and raised dates on the lower level.
        assertLadder(2, CardioRule(2, ladderCounted = listOf(d - 1), ladderRaised = d - 1))
    }

    @Test fun `the last ladder level leaves the ladder on every day`() = runTest {
        prepare()
        // ladder 2026-11-17: the second Easy at level 12 completes; dates are old enough to allow the step.
        ladder(CardioRule(12, credits = 1, ladderCounted = listOf(d - 2), ladderRaised = d - 9))
        log()
        assertLadder(12, done = true)
    }

    @Test fun `an Ask plan holds the last ladder level on every day`() = runTest {
        prepare()
        val credited = CardioRule(12, credits = 1, ladderCounted = listOf(d - 2), ladderRaised = d - 9)
        ladder(credited)
        repo.plans.setAutoApply(plan, false)
        val before = db.programDao().daysForPlan(plan)
        val id = log()
        // ladder 2026-11-17: held LadderDone keeps the original level, credit and dates.
        assertLadder(12, credited)
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertEquals(undo(id).rule?.before, undo(id).rule?.after)
        assertTrue(undo(id).days.isEmpty())
        assertFalse(repo.finishUndoState(null, id).restorable)
        repo.setCardioEffort(id, CardioEffort.HARD)
        // ladder 2026-11-17: Ask holds the drop but clears the shared credit, previously writing nothing.
        assertLadder(12, credited.copy(credits = 0))
        for (effort in listOf(CardioEffort.GOOD, CardioEffort.EASY)) {
            repo.setCardioEffort(id, effort)
            assertEquals(before, db.programDao().daysForPlan(plan))
        }
    }

    @Test fun `an Ask ladder completion holds a sibling returned to Progress`() = runTest {
        prepare()
        ladder(CardioRule(11, credits = 1))
        repo.setCardioKeepToPlan(plan, "B", true)
        log()
        val raised = CardioRule(12, ladderCounted = listOf(d), ladderRaised = d)
        // ladder 2026-11-17: kept B shares the raised rule instead of lagging at 11; its block stays 11.
        assertEquals(raised, rule())
        assertEquals(raised, rule("B"))
        assertEquals(RunWalkLadder.block(11, CardioActivity.ROAD_RUN), block("B"))
        repo.plans.setAutoApply(plan, false)
        repo.setCardioKeepToPlan(plan, "B", false)
        // ladder 2026-11-17: leaving Keep synchronizes B to the current level 12 even on Ask.
        assertLadder(12, raised)
        log()
        val credit = raised.copy(credits = 1, ladderCounted = listOf(d, d))
        // ladder 2026-11-17: Ask writes the first Easy credit to all ladder days before holding completion.
        assertLadder(12, credit)
        val before = db.programDao().daysForPlan(plan)
        val id = log("C")
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertTrue(undo(id).days.isEmpty())
    }

    @Test fun `a ladder replay back to its original state offers no Undo and can replay again`() = runTest {
        prepare()
        val id = log()
        val credit = CardioRule(1, credits = 1, ladderCounted = listOf(d))
        assertLadder(1, credit)
        assertTrue(repo.finishUndoState(null, id).restorable)
        repo.setCardioEffort(id, CardioEffort.GOOD)
        assertLadder(1)
        assertEquals(undo(id).rule?.before, undo(id).rule?.after)
        assertEquals(setOf("A", "B", "C"), undo(id).days.map { it.dayId }.toSet())
        assertFalse(repo.finishUndoState(null, id).restorable)
        repo.setCardioEffort(id, CardioEffort.EASY)
        // ladder 2026-11-17: replay gives the first dated credit, previously level 2.
        assertLadder(1, credit)
        assertTrue(repo.finishUndoState(null, id).restorable)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertLadder(1)
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
        // PR 7a: on foot this Some runner now starts the ladder; a rider keeps revision 42's worked example.
        // PR 7a: floor(900 * 1.1 / 60) * 60 = 960; round(960 * 1.05) = 1008, once per rotation.
        val answers = run(RunGoal.CARDIO_FITNESS, 3, 60, RunExperience.SOME, Measure.Time(900))
        prepare(answers.copy(run = answers.run.copy(activity = CardioActivity.STATIONARY_BIKE)))
        assertEquals(time(960).copy(activity = CardioActivity.STATIONARY_BIKE), block())
        log()
        assertEquals(time(1008).copy(activity = CardioActivity.STATIONARY_BIKE), block())
        assertEquals(0, rule().stepCycle)
        log()
        assertEquals(time(1008).copy(activity = CardioActivity.STATIONARY_BIKE), block())
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
        val credit = CardioRule(1, credits = 1, ladderCounted = listOf(d))
        // ladder 2026-11-17: one Easy leaves level 1 with a dated credit instead of level 2.
        assertEquals(credit, rule())
        assertEquals(edited, day("B").cardioBlockJson)
        assertFalse(repo.finishUndoState(null, id).restorable)
        // PR 7b: Undo reports that the changed ladder was kept
        assertEquals(UndoResult.UndoneLadderKept, repo.undoFinish(null, id))
        // ladder 2026-11-17: a mismatching B prevents every ladder restore, including A and C.
        for (day in listOf("A", "B", "C")) assertEquals(credit, rule(day))
        assertEquals(edited, day("B").cardioBlockJson)
    }

    @Test fun `ladder changes keep the kept block and each sibling activity`() = runTest {
        prepare()
        ladder(CardioRule(1, credits = 1))
        repo.setCardioKeepToPlan(plan, "B", true)
        val sibling = day("C")
        db.programDao().upsertDay(sibling.copy(cardioBlockJson = CardioBlockJson.encode(RunWalkLadder.block(1, CardioActivity.TRAIL_RUN))))
        log()
        val raised = CardioRule(2, ladderCounted = listOf(d), ladderRaised = d)
        // ladder 2026-11-17: kept B takes the runner rule at 2 instead of retaining rule 1.
        assertEquals(raised, rule("B"))
        assertEquals(RunWalkLadder.block(1, CardioActivity.ROAD_RUN), block("B"))
        // ladder 2026-11-17: C keeps TRAIL activity and now records the counted and raised date.
        assertEquals(raised, rule("C"))
        assertEquals(RunWalkLadder.block(2, CardioActivity.TRAIL_RUN), block("C"))
    }

    @Test fun `a fourth Easy in a week repeats`() = runTest {
        prepare()
        log("A", day = 0)
        log("B", day = 1)
        log("C", day = 2)
        val credit = CardioRule(2, credits = 1, ladderCounted = listOf(d, d + 1, d + 2), ladderRaised = d + 1)
        assertLadder(2, credit)
        val before = db.programDao().daysForPlan(plan)
        val id = log("A", day = 3)
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertTrue(undo(id).days.isEmpty())
    }

    @Test fun `a late rating cannot add a credit`() = runTest {
        prepare()
        val first = log(effort = null)
        log("B", day = 1)
        log("C", day = 2)
        log("A", day = 3)
        val credit = CardioRule(2, credits = 1, ladderCounted = listOf(d + 1, d + 2, d + 3), ladderRaised = d + 2)
        assertLadder(2, credit)
        val before = db.programDao().daysForPlan(plan)
        repo.setCardioEffort(first, CardioEffort.EASY)
        assertEquals(before, db.programDao().daysForPlan(plan))
    }

    @Test fun `Undo restores credit and date on every ladder day`() = runTest {
        prepare()
        log()
        val second = log("B", day = 2)
        assertEquals(UndoResult.Undone, repo.undoFinish(null, second))
        assertLadder(1, CardioRule(1, credits = 1, ladderCounted = listOf(d)))
        log("C", day = 2)
        assertLadder(2, CardioRule(2, ladderCounted = listOf(d, d + 2), ladderRaised = d + 2))
    }

    @Test fun `on Ask a drop clears the credit and keeps the level`() = runTest {
        prepare()
        ladder(CardioRule(5, credits = 1))
        repo.plans.setAutoApply(plan, false)
        val id = log(effort = CardioEffort.HARD)
        assertLadder(5)
        assertEquals(setOf("A", "B", "C"), undo(id).days.map { it.dayId }.toSet())
    }

    @Test fun `a kept ladder day follows the runner`() = runTest {
        prepare()
        repo.setCardioKeepToPlan(plan, "B", true)
        log("A")
        log("C", day = 1)
        log("A", day = 2)
        val credit = CardioRule(2, credits = 1, ladderCounted = listOf(d, d + 1, d + 2), ladderRaised = d + 1)
        for (day in listOf("A", "B", "C")) assertEquals(credit, rule(day))
        assertEquals(RunWalkLadder.block(1, CardioActivity.ROAD_RUN), block("B"))
        repo.setCardioKeepToPlan(plan, "B", false)
        assertLadder(2, credit)
        val before = db.programDao().daysForPlan(plan)
        val id = log("B", day = 3)
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertTrue(undo(id).days.isEmpty())
    }

    @Test fun `LadderDone clears a kept day level`() = runTest {
        prepare()
        ladder(CardioRule(12, credits = 1, ladderCounted = listOf(d - 2), ladderRaised = d - 9))
        val kept = RunWalkLadder.block(11, CardioActivity.ROAD_RUN)
        db.programDao().upsertDay(day("B").copy(cardioBlockJson = CardioBlockJson.encode(kept)))
        repo.setCardioKeepToPlan(plan, "B", true)
        log()
        for (day in listOf("A", "B", "C")) assertEquals(CardioRule(null), rule(day))
        assertEquals(kept, block("B"))
        for (day in listOf("A", "C")) assertEquals(RunWalkLadder.block(12, CardioActivity.ROAD_RUN), block(day))
    }

    @Test fun `a partial ladder Undo restores nothing on the ladder`() = runTest {
        prepare()
        repo.setCardioKeepToPlan(plan, "B", true)
        log()
        val second = log("C", day = 1)
        val edited = CardioBlockJson.encode(time(900))
        db.programDao().upsertDay(day("B").copy(cardioBlockJson = edited))
        assertFalse(repo.finishUndoState(null, second).restorable)
        // PR 7b: Undo reports that the changed ladder was kept
        assertEquals(UndoResult.UndoneLadderKept, repo.undoFinish(null, second))
        val raised = CardioRule(2, ladderCounted = listOf(d, d + 1), ladderRaised = d + 1)
        for (day in listOf("A", "B", "C")) assertEquals(raised, rule(day))
        for (day in listOf("A", "C")) assertEquals(RunWalkLadder.block(2, CardioActivity.ROAD_RUN), block(day))
        assertEquals(edited, day("B").cardioBlockJson)
        assertNull(db.cardioSessionDao().byId(second)!!.progressionUndoJson)
    }

    @Test fun `diverged ladder days are not judged`() = runTest {
        prepare()
        ladder(CardioRule(2))
        db.programDao().setCardioRule(plan, "B", CardioRuleJson.encode(CardioRule(3)))
        val before = db.programDao().daysForPlan(plan)
        val id = log()
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertNull(db.cardioSessionDao().byId(id)!!.progressionUndoJson)
    }

    @Test fun `ladder days share one rule through Keep Undo replay and the editor`() = runTest {
        prepare()
        suspend fun shared() {
            val rules = db.programDao().daysForPlan(plan).mapNotNull { CardioRuleJson.decode(it.cardioRuleJson) }
                .filter { it.ladderLevel != null }.distinct()
            assertEquals(1, rules.size)
        }
        log()
        shared()
        repo.setCardioKeepToPlan(plan, "B", true)
        shared()
        val second = log("C", day = 1)
        shared()
        repo.setCardioEffort(second, CardioEffort.GOOD)
        shared()
        repo.setCardioEffort(second, CardioEffort.EASY)
        shared()
        assertEquals(UndoResult.Undone, repo.undoFinish(null, second))
        shared()
        repo.setCardioKeepToPlan(plan, "B", false)
        shared()
        assertTrue(repo.setCardioBlock(plan, "A", time(900)))
        shared()
        assertNull(day().cardioRuleJson)
    }

    @Test fun `run result decoding tolerates older and unreadable records`() {
        val entry = CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run", startedAt = 1,
            completedAt = 2, seconds = 1800, stepsCompleted = 1)
        for (json in listOf(null, "{}", "broken", """{"result":{"change":"UNKNOWN"}}""",
            """{"result":{"change":"LEVEL_TO"}}""", """{"result":{"change":"REPEAT","hold":"UNKNOWN"}}""")) {
            assertNull(entry.copy(progressionUndoJson = json).runResult())
        }
        val block = RunWalkLadder.block(12, CardioActivity.ROAD_RUN)
        val record = CardioResultDto("LADDER_DONE", from = 12, held = true, block = CardioBlockJson.encode(block))
        val encoded = CardioUndoJson.encode(CardioUndoDto(result = record))
        val expected = """{"result":{"change":"LADDER_DONE","from":12,"held":true,"block":"{\"activity\":\"ROAD_RUN\",\"sets\":[{\"role\":\"WORK\",\"end\":{\"type\":\"time\",\"seconds\":1800}}]}"}}"""
        assertEquals(expected, encoded)
        val result = assertNotNull(entry.copy(progressionUndoJson = encoded).runResult())
        assertEquals(CardioChange.LadderDone, result.change)
        assertEquals(12, result.from)
        assertEquals(block, result.block)
    }

    private suspend fun result(id: Long) = assertNotNull(db.cardioSessionDao().byId(id)?.runResult())

    @Test fun `Finish records what a ladder session earned`() = runTest {
        prepare()
        val first = result(log())
        assertTrue(first.credit)
        assertEquals(1, first.from)
        assertEquals(CardioChange.Repeat, first.change)
        assertEquals(block(), first.block)
        val second = result(log("B", day = 2))
        assertEquals(CardioChange.LevelTo(2), second.change)
        assertEquals(1, second.from)
        ladder(CardioRule(3, credits = 1))
        assertEquals(CardioChange.LevelTo(2), result(log(effort = CardioEffort.HARD)).change)
        ladder(CardioRule(2, ladderCounted = listOf(d - 2, d - 1, d)))
        assertEquals(LadderHold.WEEK_CAP, result(log()).hold)
        ladder(CardioRule(2, ladderCounted = listOf(d + 1)))
        assertEquals(LadderHold.BACKDATED, result(log()).hold)
        ladder(CardioRule(2, credits = 1, ladderRaised = d - 1))
        assertEquals(LadderHold.BUSY, result(log()).hold)
        ladder(CardioRule(12, credits = 1, ladderCounted = listOf(d - 2), ladderRaised = d - 9))
        assertEquals(CardioChange.LadderDone, result(log()).change)
    }

    @Test fun `Finish records quality steps alternates and easy growth`() = runTest {
        quality()
        log("D")
        val stepped = result(log("D"))
        assertEquals(CardioChange.Step, stepped.change)
        assertEquals(block("D"), stepped.block)
        val row = day("D")
        val threshold = time(1200).let { it.copy(sets = it.sets.map { set -> set.copy(hard = true) }) }
        db.programDao().upsertDay(row.copy(cardioBlockJson = CardioBlockJson.encode(threshold),
            cardioRuleJson = CardioRuleJson.encode(CardioRule(null, RunSession.THRESHOLD, alternate = RunSession.VO2MAX))))
        val alternate = result(log("D"))
        assertEquals(CardioChange.Alternate(RunSession.VO2MAX), alternate.change)
        assertEquals(block("D"), alternate.block)
        val answers = run(RunGoal.CARDIO_FITNESS, experience = RunExperience.SOME, longest = Measure.Time(900))
        prepare(answers.copy(run = answers.run.copy(activity = CardioActivity.STATIONARY_BIKE)))
        val increase = result(log())
        assertTrue(increase.change is CardioChange.Increase)
        assertEquals(block(), increase.block)
    }

    @Test fun `Ask holds a level up and the chip accepts it`() = runTest {
        prepare()
        repo.plans.setAutoApply(plan, false)
        repo.setCardioKeepToPlan(plan, "C", true)
        log()
        val credit = rule()
        val id = log("B", day = 2)
        assertTrue(result(id).held)
        for (day in listOf("A", "B", "C")) assertEquals(1, rule(day).ladderLevel)
        assertTrue(repo.setRunOfferApplied(id, true))
        for (day in listOf("A", "B", "C")) assertEquals(2, rule(day).ladderLevel)
        assertEquals(RunWalkLadder.block(1, CardioActivity.ROAD_RUN), block("C"))
        assertFalse(result(id).held)
        assertEquals(true, undo(id).result?.choice)
        assertTrue(repo.setRunOfferApplied(id, false))
        for (day in listOf("A", "B", "C")) assertEquals(credit, rule(day))
        assertTrue(result(id).held)
        assertTrue(repo.setRunOfferApplied(id, true))
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertLadder(1, credit)
    }

    @Test fun `Ask accepts and declines a quality step and easy growth`() = runTest {
        quality()
        repo.plans.setAutoApply(plan, false)
        log("D")
        val before = day("D")
        val id = log("D")
        assertTrue(result(id).held)
        assertTrue(repo.setRunOfferApplied(id, true))
        assertEquals(vo2(6), block("D"))
        assertFalse(result(id).held)
        assertTrue(repo.setRunOfferApplied(id, false))
        assertEquals(before, day("D"))
        val answers = run(RunGoal.CARDIO_FITNESS, experience = RunExperience.SOME, longest = Measure.Time(900))
        prepare(answers.copy(run = answers.run.copy(activity = CardioActivity.STATIONARY_BIKE)))
        repo.plans.setAutoApply(plan, false)
        val old = day()
        val growth = log()
        assertTrue(result(growth).held)
        assertTrue(repo.setRunOfferApplied(growth, true))
        assertEquals(result(growth).block, block())
        assertTrue(repo.setRunOfferApplied(growth, false))
        assertEquals(old, day())
    }

    @Test fun `a re-rating keeps the chip choice`() = runTest {
        prepare()
        repo.plans.setAutoApply(plan, false)
        log()
        val id = log("B", day = 2)
        assertTrue(repo.setRunOfferApplied(id, true))
        repo.setCardioEffort(id, CardioEffort.GOOD)
        assertEquals(1, rule().ladderLevel)
        assertEquals(CardioChange.Repeat, result(id).change)
        assertEquals(true, undo(id).result?.choice)
        repo.setCardioEffort(id, CardioEffort.EASY)
        assertEquals(2, rule().ladderLevel)
        assertFalse(result(id).held)
        assertEquals(true, undo(id).result?.choice)
    }

    @Test fun `Ask accepts a held drop and a held LadderDone`() = runTest {
        prepare()
        repo.plans.setAutoApply(plan, false)
        ladder(CardioRule(3, credits = 1))
        val drop = log(effort = CardioEffort.HARD)
        assertLadder(3)
        assertTrue(result(drop).held)
        assertTrue(repo.setRunOfferApplied(drop, true))
        assertLadder(2)
        ladder(CardioRule(12, credits = 1, ladderCounted = listOf(d - 2), ladderRaised = d - 9))
        val done = log()
        assertTrue(result(done).held)
        assertTrue(repo.setRunOfferApplied(done, true))
        assertLadder(12, done = true)
    }

    @Test fun `run offers refuse when the session no longer judges`() = runTest {
        prepare()
        val older = log()
        val newest = log()
        val before = db.programDao().daysForPlan(plan)
        assertFalse(repo.setRunOfferApplied(older, true))
        repo.clearFinishUndoForDay(plan, "A")
        assertFalse(repo.setRunOfferApplied(newest, true))
        assertEquals(before, db.programDao().daysForPlan(plan))
        assertTrue(repo.setCardioBlock(plan, "A", time(900)))
        val ruleless = log()
        assertFalse(repo.setRunOfferApplied(ruleless, true))
        db.programDao().setCardioRule(plan, "A", CardioRuleJson.encode(CardioRule(null, RunSession.ACCELERATION)))
        assertFalse(repo.setRunOfferApplied(log(), true))
    }

    @Test fun `undo reports a ladder it kept`() = runTest {
        prepare()
        repo.setCardioKeepToPlan(plan, "B", true)
        log()
        val id = log("C", day = 2)
        repo.setCardioKeepToPlan(plan, "B", false)
        assertEquals(UndoResult.UndoneLadderKept, repo.undoFinish(null, id))
        for (day in listOf("A", "B", "C")) assertEquals(2, rule(day).ladderLevel)
        assertNull(db.cardioSessionDao().byId(id)?.progressionUndoJson)
    }
}
