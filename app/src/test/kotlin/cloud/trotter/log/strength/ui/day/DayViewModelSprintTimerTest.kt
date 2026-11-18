package cloud.trotter.log.strength.ui.day

import android.content.Context
import android.os.Parcel
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.data.serialization.runResult
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.SprintAttempt
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.records.GoalProgress
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelSprintTimerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<DayViewModel>()
    private val clock = FakeClock()
    private val beeps = mutableListOf<Long>()
    private var planId = 0L

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("sprint-timer", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings,
            clock = Clock.fixed(Instant.ofEpochMilli(clock.wall), ZoneOffset.UTC))
        share = ShareCardService(context, repo.history, settings = settings)
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun prepare(trial: Boolean = false) {
        // Sprint recipe encoding stays closed until 7f, as in SprintFinishTest.
        planId = repo.plans.createAuthoredPlan("Sprint", "Sprint")
        repo.plans.publishCreatedAuthoredPlan(planId)
        repo.replaceProgram(Planner.plan(SetupAnswers(branch = PlanBranch.SPRINT, days = 2, minutes = 60,
            sprint = SprintAnswers(event = SprintEvent.M60, sprintedRecently = true))).program)
        repo.plans.setGoal(planId, PlanGoal.Sprint(60))
        db.planDao().updateLightCycle(planId, 0, if (trial) 4 else 0)
    }

    private suspend fun TestScope.ready(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val vm = DayViewModel(repo, SessionPublisher.NoOp, share, handle, clock, object : CardioAlarm {
            override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = Unit
            override fun cancel() = Unit
        }, PhoneRestController(settings, NoOpRestRuntime), settings = settings, plans = repo.plans,
            catalog = repo.catalog, historyRepository = repo.history,
            sprintBeeper = object : SprintBeeper { override fun beep() { beeps += clock.elapsed } })
        vms += vm
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.setCardioScreenLive(true)
        runCurrent()
        return vm
    }

    private fun run(vm: DayViewModel) = assertNotNull(vm.uiState.value.cardio?.run)

    private fun TestScope.tick(millis: Long) {
        clock.wall += millis
        clock.elapsed += millis
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.firstRep(vm: DayViewModel) {
        vm.startCardio()
        runCurrent()
        val warmup = run(vm).block.sets.first().end as Measure.Time
        tick(warmup.seconds * 1000L)
        vm.startCardio()
        runCurrent()
        assertEquals(CardioRole.WORK, run(vm).block.sets[run(vm).position].role)
    }

    private suspend fun goal() = PlanGoalJson.decode(db.planDao().plan(planId)?.goalJson) as PlanGoal.Sprint

    @Test fun `the beep sounds five seconds after arming and the time runs from it`() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = ready(handle)
        firstRep(vm)
        clock.elapsed = 10_000L
        handle["day_cardio_run"] = run(vm).copy(elapsedStart = clock.elapsed)
        runCurrent()
        vm.armSprintTimer(run(vm).action())
        runCurrent()
        tick(4_999)
        assertTrue(beeps.isEmpty())
        tick(1)
        assertEquals(listOf(15_000L), beeps)
        tick(4_520)
        vm.stopSprintTimer(run(vm).action())
        runCurrent()
        val time = assertNotNull(handle.get<List<CardioSprintTime>>("day_sprint_times")).single()
        assertEquals(452, time.centis)
        assertEquals("PHONE", time.method)
        assertEquals("STANDING", time.protocol)
        assertEquals("FINISHED", run(vm).results.last().status)
        assertNull(handle.get<SprintTimerState>("day_sprint_timer"))
    }

    @Test fun `ending a ready sprint rep shortens only that rep and leaves recovery available`() = test {
        prepare()
        val vm = ready()
        firstRep(vm)
        val rep = run(vm)
        assertFalse(assertNotNull(vm.uiState.value.cardio?.sprintRep).armed)
        vm.stopCardio(rep.action())
        runCurrent()
        val next = run(vm)
        assertEquals(rep.position + 1, next.position)
        assertEquals(rep.results.size + 1, next.results.size)
        assertEquals("SHORTENED", next.results.last().status)
        assertTrue(next.results.none { it.status == "SKIPPED" })
        assertFalse(next.complete)
        assertEquals(CardioRole.RECOVERY, next.block.sets[next.position].role)
        vm.startCardio()
        runCurrent()
        assertEquals(next.position, run(vm).position)
        assertTrue(run(vm).running)
    }

    @Test fun `stop before the beep cancels without a time`() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = ready(handle)
        firstRep(vm)
        val action = run(vm).action()
        vm.armSprintTimer(action)
        runCurrent()
        tick(1_000)
        vm.stopSprintTimer(action)
        runCurrent()
        tick(5_000)
        assertTrue(run(vm).running)
        assertEquals(action.setIndex, run(vm).position)
        assertTrue(handle.get<List<CardioSprintTime>>("day_sprint_times").orEmpty().isEmpty())
        assertTrue(beeps.isEmpty())
    }

    @Test fun `the timer survives process death and refuses a reboot`() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = ready(handle)
        firstRep(vm)
        vm.armSprintTimer(run(vm).action())
        runCurrent()
        val timer = handle.get<SprintTimerState>("day_sprint_timer")
        vm.viewModelScope.cancel()
        val restoredHandle = parcelHandle(handle)
        val restored = ready(restoredHandle)
        assertEquals(timer, restoredHandle.get<SprintTimerState>("day_sprint_timer"))
        tick(5_000)
        assertEquals(1, beeps.size)
        restored.viewModelScope.cancel()
        clock.boot++
        ready(parcelHandle(restoredHandle)).also {
            assertFalse(assertNotNull(it.uiState.value.cardio?.sprintRep).armed)
        }
    }

    @Test fun `a restored timer past its beep does not beep again and expires after ten minutes`() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = ready(handle)
        firstRep(vm)
        vm.armSprintTimer(run(vm).action())
        runCurrent()
        vm.viewModelScope.cancel()
        clock.elapsed += 5_001
        val restored = ready(parcelHandle(handle))
        assertTrue(assertNotNull(restored.uiState.value.cardio?.sprintRep).armed)
        assertTrue(beeps.isEmpty())
        tick(600_000)
        assertFalse(assertNotNull(restored.uiState.value.cardio?.sprintRep).armed)
        assertTrue(beeps.isEmpty())
    }

    @Test fun `a typed time finishes the rep with its method and protocol`() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = ready(handle)
        firstRep(vm)
        vm.openSprintTime(run(vm).action())
        val draft = assertNotNull(vm.sprintTimeDraft.value)
        vm.changeSprintTimeDraft(draft.copy(text = "4.40", protocol = "THREE_POINT"))
        vm.saveSprintTimeDraft(requireNotNull(ClockText.parseCentis("4.40")), TimingMethod.HAND, StartProtocol.THREE_POINT)
        runCurrent()
        val saved = assertNotNull(handle.get<List<CardioSprintTime>>("day_sprint_times")).single()
        assertEquals(440, saved.centis)
        assertEquals("HAND", saved.method)
        assertEquals("THREE_POINT", saved.protocol)
        assertEquals("FINISHED", run(vm).results.last().status)
        assertNull(vm.sprintTimeDraft.value)
        assertEquals(440, ClockText.parseCentis("4.40"))
    }

    @Test fun `a half typed sprint time survives process death under its own key`() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = ready(handle)
        firstRep(vm)
        vm.openSprintTime(run(vm).action())
        val draft = assertNotNull(vm.sprintTimeDraft.value).copy(text = "8.", method = "GATES", protocol = "TWO_POINT")
        vm.changeSprintTimeDraft(draft)
        vm.viewModelScope.cancel()
        val restored = ready(parcelHandle(handle))
        assertEquals(draft, restored.sprintTimeDraft.value)
        restored.changeSprintTimeDraft(draft.copy(text = "8.30"))
        restored.saveSprintTimeDraft(830, TimingMethod.GATES, StartProtocol.TWO_POINT)
        runCurrent()
        assertEquals("FINISHED", run(restored).results.last().status)
    }

    @Test fun `start protocol defaults to the newest timed result on the plan day`() = test {
        prepare()
        val previous = repo.logCardioSession(entity("A", clock.wall - 3 * 86_400_000L), listOf(
            timedResult(30, 452).copy(timing = SprintTime(452, TimingMethod.HAND, StartProtocol.BLOCKS))))
        assertNotNull(db.cardioSessionDao().byId(previous))
        val vm = ready()
        firstRep(vm)
        assertEquals(StartProtocol.BLOCKS, vm.uiState.value.cardio?.sprintRep?.protocol)
        vm.setSprintProtocol("FLYING")
        runCurrent()
        assertEquals(StartProtocol.FLYING, vm.uiState.value.cardio?.sprintRep?.protocol)
    }

    private suspend fun TestScope.trial(): Long {
        prepare(trial = true)
        val handle = SavedStateHandle()
        val vm = ready(handle)
        assertEquals(SprintDose.TRIAL, vm.uiState.value.cardio?.sprint?.dose)
        firstRep(vm)
        vm.setSprintProtocol("THREE_POINT")
        runCurrent()
        vm.armSprintTimer(run(vm).action())
        runCurrent()
        tick(5_000)
        tick(8_520)
        vm.stopSprintTimer(run(vm).action())
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick((run(vm).block.sets[run(vm).position].end as Measure.Time).seconds * 1_000L)
        vm.startCardio()
        runCurrent()
        tick(8_300)
        vm.saveSprintTime(run(vm).action(), 830, TimingMethod.GATES, StartProtocol.THREE_POINT)
        runCurrent()
        val session = db.cardioSessionDao().all().single()
        assertEquals(SprintDose.TRIAL, session.runResult()?.dose)
        assertNull(handle.get<SprintTimerState>("day_sprint_timer"))
        assertNull(handle.get<String>("day_sprint_protocol"))
        assertTrue(handle.get<List<CardioSprintTime>>("day_sprint_times").orEmpty().isEmpty())
        return session.id
    }

    @Test fun `case 37 phone and typed reps store their series and a trial adds attempts`() = test {
        val id = trial()
        val rows = db.cardioSetResultDao().forSession(id).filter { it.centis != null }
        assertEquals(listOf(852, 830), rows.map { it.centis })
        assertEquals(listOf("PHONE", "GATES"), rows.map { it.timingMethod })
        assertEquals(listOf("THREE_POINT", "THREE_POINT"), rows.map { it.startProtocol })
        val completedAt = assertNotNull(db.cardioSessionDao().byId(id)).completedAt
        val attempts = listOf(SprintAttempt(completedAt, SprintTime(852, TimingMethod.PHONE, StartProtocol.THREE_POINT)),
            SprintAttempt(completedAt, SprintTime(830, TimingMethod.GATES, StartProtocol.THREE_POINT)))
        assertEquals(attempts, goal().attempts)
        assertEquals(attempts.last(), GoalProgress.bestSprint(goal()))
        val expectedJson = """{"type":"sprint","metres":60,"sprintAttempts":[{"completedAt":$completedAt,"centis":852,"timingMethod":"PHONE","startProtocol":"THREE_POINT"},{"completedAt":$completedAt,"centis":830,"timingMethod":"GATES","startProtocol":"THREE_POINT"}]}"""
        assertEquals(expectedJson, db.planDao().plan(planId)?.goalJson)

        db.planDao().updateLightCycle(planId, 0, 5)
        val ordinary = timedResult(30, 452)
        val next = repo.logCardioSession(entity("A", clock.wall + 4 * 86_400_000L), listOf(ordinary))
        assertEquals(452, db.cardioSetResultDao().forSession(next).single().centis)
        assertEquals(SprintDose.NORMAL, db.cardioSessionDao().byId(next)?.runResult()?.dose)
        assertEquals(attempts, goal().attempts)
    }

    @Test fun `undo removes the trial attempts`() = test {
        val id = trial()
        assertEquals(2, goal().attempts.size)
        repo.undoFinish(null, id)
        assertTrue(goal().attempts.isEmpty())
    }

    @Test fun `undo preserves a goal edited after the trial`() = test {
        val id = trial()
        repo.plans.setGoal(planId, goal().copy(targetCentis = 800))
        val edited = db.planDao().plan(planId)?.goalJson
        repo.undoFinish(null, id)
        assertEquals(edited, db.planDao().plan(planId)?.goalJson)
    }

    @Test fun `a re-rating does not add them twice`() = test {
        val id = trial()
        val before = goal().attempts
        repo.setCardioEffort(id, CardioEffort.GOOD)
        repo.setCardioEffort(id, CardioEffort.HARD)
        assertEquals(before, goal().attempts)
    }

    @Test fun `tempo and shortened sessions add no attempt`() = test {
        prepare(trial = true)
        repo.plans.setGoal(planId, PlanGoal.Sprint(200))
        db.programDao().setCardioRule(planId, "A", CardioRuleJson.encode(
            CardioRule(null, RunSession.ACCELERATION, steps = 12, sprintTrialCycle = 4)))
        val tempo = timedResult(200, 852).let { it.copy(spec = it.spec.copy(hard = false)) }
        val tempoId = repo.logCardioSession(entity("C", clock.wall), listOf(tempo))
        assertEquals(SprintDose.TEMPO, db.cardioSessionDao().byId(tempoId)?.runResult()?.dose)
        repo.plans.setGoal(planId, PlanGoal.Sprint(60))
        db.planDao().updateLightCycle(planId, 0, 1)
        for ((day, gap, count) in listOf(Triple("A", 6L, 8), Triple("C", 4L, 3), Triple("C", 2L, 3))) {
            val seed = db.cardioSessionDao().insert(entity(day, clock.wall - gap * 86_400_000L))
            db.cardioSetResultDao().insertAll(List(count) { index ->
                CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(if (day == "A") 20 else 10), hard = true),
                    CardioSetStatus.FINISHED, 5).toEntity(seed, index)
            })
        }
        val shortenedId = repo.logCardioSession(entity("A", clock.wall + 1_000), listOf(timedResult(60, 830)))
        assertEquals(SprintDose.SHORTENED, db.cardioSessionDao().byId(shortenedId)?.runResult()?.dose)
        assertEquals(830, db.cardioSetResultDao().forSession(shortenedId).single().centis)
        assertTrue(goal().attempts.isEmpty())
    }

    @Test fun `an attempt write failure leaves no times`() = test {
        prepare(trial = true)
        val ruleBefore = db.programDao().day(planId, "A")?.cardioRuleJson
        // A database trigger fails the goal write inside Finish's transaction.
        db.openHelper.writableDatabase.execSQL("""CREATE TEMP TRIGGER fail_sprint_goal BEFORE UPDATE OF goalJson ON plan
            BEGIN SELECT RAISE(ABORT, 'goal write failed'); END""")
        val failure = runCatching {
            repo.logCardioSession(entity("A", clock.wall), listOf(timedResult(60, 852)))
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(db.cardioSetResultDao().all().isEmpty())
        assertTrue(db.cardioSessionDao().all().isEmpty())
        assertEquals(ruleBefore, db.programDao().day(planId, "A")?.cardioRuleJson)
        assertTrue(goal().attempts.isEmpty())
    }

    private fun timedResult(metres: Int, centis: Int) = CardioSetResult(
        CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true), CardioSetStatus.FINISHED, 9,
        timing = SprintTime(centis, TimingMethod.PHONE, StartProtocol.THREE_POINT))

    private fun entity(day: String, startedAt: Long) = CardioSessionEntity(planId = planId, dayId = day,
        mode = "OUTDOOR_RUN", hard = true, label = "Sprint", startedAt = startedAt, completedAt = startedAt + 9_000,
        seconds = 9, stepsCompleted = 1, activity = "TRACK_RUN")

    private fun parcelHandle(handle: SavedStateHandle): SavedStateHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(CardioRun::class.java.classLoader), null)
        } finally { parcel.recycle() }
    }

    private class FakeClock : CardioClock {
        var wall = 1_800_000_000_000L
        var elapsed = 10_000L
        var boot = 7
        override fun wallMillis() = wall
        override fun elapsedRealtimeMillis() = elapsed
        override fun bootCount() = boot
    }
}
