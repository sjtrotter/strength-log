package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelCardioPersistTest {
    @Test fun `an added set survives leaving the day`() = test {
        prepareLadder()
        val vm = readyVm()
        assertEquals(20, vm.uiState.value.cardio?.block?.sets?.size)
        vm.addCardioSet()
        runCurrent()
        assertEquals(22, vm.uiState.value.cardio?.run?.block?.sets?.size)

        leave(vm)
        val returned = readyVm()
        val cardio = requireNotNull(returned.uiState.value.cardio)
        assertEquals(22, cardio.run?.block?.sets?.size)
        assertNull(cardio.run?.startedAt)
        assertTrue(cardio.canAddSet)
    }

    @Test fun `a swapped activity survives leaving the day`() = test {
        prepareLadder()
        val vm = readyVm()
        vm.swapCardio(CardioActivity.ELLIPTICAL, null)
        runCurrent()
        leave(vm)

        val returned = readyVm()
        assertEquals(CardioActivity.ELLIPTICAL, returned.uiState.value.cardio?.run?.block?.activity)
        assertTrue(requireNotNull(returned.uiState.value.cardio).canSwap)
    }

    @Test fun `a stopped run keeps its finished intervals after leaving the day`() = test {
        prepareIntervals()
        val plan = plan()
        val vm = readyVm()
        startAndStopRecovery(vm)
        leave(vm)

        val returned = readyVm()
        val run = requireNotNull(returned.uiState.value.cardio?.run)
        assertEquals(2, run.position)
        assertFalse(run.running)
        assertEquals(listOf(SavedCardioResult("FINISHED", 60), SavedCardioResult("SHORTENED", 10)), run.results)
        returned.endCardioBlock()
        runCurrent()
        assertLoggedIntervals()
        assertNull(runStore.load(plan, "A"))
    }

    @Test fun `a running run keeps its clock after leaving the day`() = test {
        prepareIntervals()
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(30_000)
        val startedAt = requireNotNull(vm.uiState.value.cardio?.run?.startedAt)
        leave(vm)
        assertNull(alarm.deadline)

        clock.advance(45_000)
        val returned = readyVm()
        val run = requireNotNull(returned.uiState.value.cardio?.run)
        assertEquals(1, run.position)
        assertTrue(run.running)
        assertEquals(startedAt + 60_000L, run.wallStart)
        assertEquals(clock.elapsed + 75_000L, alarm.deadline)
    }

    @Test fun `a logged run leaves no saved copy`() = test {
        prepareBlock(time(CardioRole.WORK, 10))
        val plan = plan()
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertNull(runStore.load(plan, "A"))
        leave(vm)

        val returned = readyVm()
        assertNull(returned.uiState.value.cardio?.run)
        assertEquals(10, returned.uiState.value.cardio?.loggedSeconds)
    }

    @Test fun `a copy whose session is already logged is not restored`() = test {
        prepareBlock(time(CardioRole.WORK, 10))
        val plan = plan()
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        val copy = requireNotNull(runStore.load(plan, "A"))
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        runStore.save(copy)
        leave(vm)

        val returned = readyVm()
        assertNull(returned.uiState.value.cardio?.run)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertNull(returned.finisher.receipt.value)
        assertNull(runStore.load(plan, "A"))
    }

    @Test fun `Finish logs a restored run's rounds`() = test {
        prepareIntervals()
        val plan = plan()
        repo.startRun(plan, "A")
        val vm = readyVm()
        startAndStopRecovery(vm)
        leave(vm)

        val returned = readyVm()
        assertEquals(2, returned.uiState.value.cardio?.run?.position)
        returned.completeDay()
        runCurrent()
        assertLoggedIntervals()
        assertNull(runStore.load(plan, "A"))
    }

    @Test fun `Discard keeps the saved cardio run`() = test {
        prepareLadder()
        val plan = plan()
        repo.addExercise("A", ProgramExercise("bb_back_squat"))
        repo.startRun(plan, "A")
        val vm = readyVm()
        assertTrue(vm.uiState.value.runOpen)
        vm.addCardioSet()
        runCurrent()
        vm.discardWorkout()
        runCurrent()
        assertNull(repo.openRunFlow.first())
        assertEquals(22, vm.uiState.value.cardio?.run?.block?.sets?.size)
        assertEquals(22, runStore.load(plan, "A")?.run?.block?.sets?.size)
        leave(vm)

        val returned = readyVm()
        assertEquals(22, returned.uiState.value.cardio?.run?.block?.sets?.size)
    }

    @Test fun `edits stay with their own day`() = test {
        prepareLadder()
        val plan = plan()
        val vm = readyVm()
        vm.addCardioSet()
        runCurrent()
        leave(vm)

        val other = readyVm(SavedStateHandle(mapOf(
            Routes.DAY_ID_ARG to "B", Routes.DAY_PLAN_ID_ARG to plan.toString(),
        )))
        val cardio = requireNotNull(other.uiState.value.cardio)
        assertNull(cardio.run)
        assertEquals(20, cardio.block?.sets?.size)
        assertTrue(other.uiState.value.cardioStartEnabled)
        assertTrue(cardio.canAddSet)
        leave(other)

        val returned = readyVm()
        assertEquals(22, returned.uiState.value.cardio?.run?.block?.sets?.size)
    }

    @Test fun `a plan edit drops an unstarted copy`() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK, 1200), activity = CardioActivity.ROAD_RUN)
        val plan = plan()
        val vm = readyVm()
        vm.swapCardio(CardioActivity.ELLIPTICAL, null)
        runCurrent()
        leave(vm)
        repo.setCardioWorking(plan, "A", CardioBlock(CardioActivity.ROAD_RUN, listOf(time(CardioRole.WORK, 1500))))

        val returned = readyVm()
        assertNull(returned.uiState.value.cardio?.run)
        assertEquals(CardioActivity.ROAD_RUN, returned.uiState.value.cardio?.block?.activity)
        assertEquals(Measure.Time(1500), returned.uiState.value.cardio?.block?.sets?.single()?.end)
        assertNull(runStore.load(plan, "A"))
    }

    @Test fun `a copy from an earlier day is dropped`() = test {
        prepareLadder()
        val plan = plan()
        val vm = readyVm()
        vm.addCardioSet()
        runCurrent()
        leave(vm)
        clock.advance(25 * 3_600_000L)

        val returned = readyVm()
        assertNull(returned.uiState.value.cardio?.run)
        assertEquals(20, returned.uiState.value.cardio?.block?.sets?.size)
        assertNull(runStore.load(plan, "A"))
    }

    @Test fun `the plan editor does not take the run`() = test {
        prepareLadder()
        val plan = plan()
        val vm = readyVm()
        vm.addCardioSet()
        runCurrent()

        val editor = readyVm(SavedStateHandle(mapOf(
            Routes.DAY_ID_ARG to "A", Routes.DAY_PLAN_ID_ARG to plan.toString(), Routes.DAY_EDITOR_ARG to true,
        )))
        assertNull(editor.uiState.value.cardio?.run)
        assertEquals(22, runStore.load(plan, "A")?.run?.block?.sets?.size)
        assertEquals(22, vm.uiState.value.cardio?.run?.block?.sets?.size)
    }

    @Test fun `a saved copy restores its stamps, sprint times and readings`() = test {
        prepareIntervals()
        val plan = plan()
        val block = requireNotNull(repo.plans.programFor(plan).first().days.first { it.id == "A" }.cardioToRun)
        val json = CardioBlockJson.encode(block)
        val run = CardioRun(plan, "Running", "A", 0, json, json, false, null,
            results = listOf(SavedCardioResult("FINISHED", 60, 400)), startedAt = clock.wall - 60_000L,
            runId = "restored-siblings")
        val stamp = CardioGpsSet(run.runId, 0, 400, 400, 60, true, false)
        val sprint = CardioSprintTime(run.runId, 0, 6000, "HAND", "STANDING")
        val reading = CardioReadingInput(run.runId, 0, "400", DistanceUnit.M)
        val distance = CardioRunDistance(run.runId, 1, "500")
        runStore.save(StoredCardioRun(clock.wall, run, listOf(stamp), listOf(sprint), listOf(reading), distance))

        val handle = SavedStateHandle()
        readyVm(handle)
        assertEquals(listOf(stamp), handle.get<ArrayList<CardioGpsSet>>("day_cardio_gps_sets"))
        assertEquals(listOf(sprint), handle.get<ArrayList<CardioSprintTime>>("day_sprint_times"))
        assertEquals(listOf(reading), handle.get<ArrayList<CardioReadingInput>>("day_cardio_reading_inputs"))
        assertEquals(distance, handle.get<CardioRunDistance>("day_cardio_run_distance"))
        assertEquals(run, handle.get<CardioRun>("day_cardio_run"))
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private lateinit var runStore: PrefsCardioRunStore
    private val clock = FakeCardioClock()
    private val alarm = FakeCardioAlarm()
    private val stores = mutableMapOf<DayViewModel, ViewModelStore>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-persist-settings", ".preferences_pb")
        })
        runStore = PrefsCardioRunStore(CardioRunPrefs(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-persist-runs", ".preferences_pb")
        }), storeScope)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        share = ShareCardService(context, repo.history, settings = settings)
    }

    @After fun tearDown() {
        stores.values.forEach { it.clear() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(DayViewModel(
                repo, SessionPublisher.NoOp, share, handle, clock, alarm, PhoneRestController(settings, NoOpRestRuntime),
                settings = settings, plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history,
                cardioRunStore = runStore,
            ))
        }
        return ViewModelProvider(store, factory)[DayViewModel::class.java].also { stores[it] = store }
    }

    private suspend fun TestScope.readyVm(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val vm = vm(handle)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.setCardioScreenLive(true)
        runCurrent()
        return vm
    }

    private fun TestScope.leave(vm: DayViewModel) {
        requireNotNull(stores.remove(vm)).clear()
        runCurrent()
    }

    private fun TestScope.tick(millis: Long) {
        clock.advance(millis)
        advanceTimeBy(1_001)
        runCurrent()
    }

    private fun TestScope.startAndStopRecovery(vm: DayViewModel) {
        vm.startCardio()
        runCurrent()
        tick(60_000)
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
    }

    private suspend fun assertLoggedIntervals() {
        val session = repo.history.cardioSessionsFlow.first().single()
        val rows = repo.history.cardioSetsFor(session.id)
        assertEquals(listOf(CardioSetStatus.FINISHED, CardioSetStatus.SHORTENED,
            CardioSetStatus.SKIPPED, CardioSetStatus.SKIPPED), rows.map { it.status })
        assertEquals(listOf(60, 10, 0, 0), rows.map { it.elapsedSeconds })
    }

    private suspend fun plan(): Long = requireNotNull(repo.plans.selectedPlanIdFlow.first())

    private suspend fun prepareIntervals() {
        prepareBlock(time(CardioRole.WORK, 60), time(CardioRole.RECOVERY, 90),
            time(CardioRole.WORK, 60), time(CardioRole.RECOVERY, 90))
    }

    private suspend fun prepareBlock(vararg sets: CardioSetSpec) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Running", "",
            listOf(ProgramExercise("bb_back_squat")),
            cardioBlock = CardioBlock(CardioActivity.TRACK_RUN, sets.toList())))))
    }

    private suspend fun prepareLadder() {
        prepareAuthoredCardioDay(*RunWalkLadder.block(1, CardioActivity.ROAD_RUN).sets.toTypedArray(),
            activity = CardioActivity.ROAD_RUN)
    }

    private suspend fun prepareAuthoredCardioDay(
        vararg sets: CardioSetSpec,
        activity: CardioActivity = CardioActivity.TRACK_RUN,
    ) {
        val planId = repo.plans.createAuthoredPlan("Running plan", "Running")
        repo.plans.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(activity, sets.toList())
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), cardioBlock = block)
        }))
    }

    private fun time(role: CardioRole, seconds: Int = 10, aim: Int? = null) =
        CardioSetSpec(role, Measure.Time(seconds), aim?.let { Measure.Distance(it) }, hard = role == CardioRole.WORK)

    private class FakeCardioClock(
        var wall: Long = 1_800_000_000_000L,
        var elapsed: Long = 9_000_000L,
        var boot: Int = 7,
    ) : CardioClock {
        override fun wallMillis() = wall
        override fun elapsedRealtimeMillis() = elapsed
        override fun bootCount() = boot
        fun advance(millis: Long) { wall += millis; elapsed += millis }
    }

    private class FakeCardioAlarm : CardioAlarm {
        var deadline: Long? = null
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
            deadline = deadlineElapsedMillis
        }
        override fun cancel() { deadline = null }
    }
}
