package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.domain.routine.MoveKind
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class DayViewModelRoutineTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = WorkoutClock()
    private val vms = mutableListOf<DayViewModel>()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private var preferenceWriteGate: CompletableDeferred<Unit>? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("routine-vm", ".preferences_pb")
        }
        settings = SettingsStore(object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                preferenceWriteGate?.await()
                return dataStore.updateData(transform)
            }
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock)
        share = ShareCardService(context, repo.history, settings = settings)
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }
    private fun bench() = ProgramExercise("db_bench", targetSets = 3,
        prescription = Prescription(PrescribedTarget.LoadReps(80.0, 10)))
    private suspend fun prepare(exercises: List<ProgramExercise> = listOf(bench())) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Workout", "", exercises))))
        settings.setPhoneRestTimerEnabled(false)
    }
    private fun TestScope.readyVm(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val vm = DayViewModel(repo, SessionPublisher.NoOp, share, handle, clock, InertAlarm,
            PhoneRestController(settings, NoOpRestRuntime), clock = clock, settings = settings,
            plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history).also { vms += it }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }
    private fun TestScope.start(vm: DayViewModel) { vm.startWorkout(); runCurrent() }
    private fun TestScope.logAll(vm: DayViewModel) { repeat(3) { vm.logFocusedSet(); runCurrent() } }

    // (F)
    @Test fun `Start opens a warmup for the run`() = test {
        prepare(listOf(bench()))
        val vm = readyVm()
        start(vm)
        val sheet = requireNotNull(vm.uiState.value.warmUp)
        assertEquals(MoveKind.GENERAL, sheet.moves.first().kind)
        assertTrue(Move.PUSH_UPS in sheet.moves)
        assertEquals(vm.uiState.value.runId, sheet.runId)
    }

    // (F)
    @Test fun `ticks and timer survive process death`() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        start(first)
        first.toggleRoutineMove(RoutineKind.WARM_UP, Move.PUSH_UPS)
        runCurrent()
        first.startRoutineTimer(RoutineKind.WARM_UP)
        runCurrent()
        first.viewModelScope.cancel()
        val restored = readyVm(handle)
        val sheet = requireNotNull(restored.uiState.value.warmUp)
        assertTrue(Move.PUSH_UPS in sheet.checked)
        assertEquals(clock.millis(), sheet.timerStartedAt)
        restored.stopRoutineTimer(RoutineKind.WARM_UP)
        runCurrent()
        assertNull(restored.uiState.value.warmUp?.timerStartedAt)
    }

    // (F)
    @Test fun `Skip closes for the run without logging and first set remains available`() = test {
        prepare()
        val first = readyVm()
        start(first)
        val runId = first.uiState.value.runId
        first.closeRoutine(RoutineKind.WARM_UP)
        runCurrent()
        assertNull(first.uiState.value.warmUp)
        assertEquals(runId, settings.routinePrefsFlow.first().warmUpClosedRun)
        first.viewModelScope.cancel()
        val restored = readyVm()
        assertNull(restored.uiState.value.warmUp)
        assertTrue(repo.history.sessionsFlow.first().isEmpty())
        assertEquals(0, restored.uiState.value.doneSets)
        restored.logFocusedSet()
        runCurrent()
        assertEquals(1, restored.uiState.value.doneSets)
    }

    // (F)
    @Test fun `first logged set hides warmup without closing it`() = test {
        prepare()
        val vm = readyVm()
        start(vm)
        vm.logFocusedSet()
        runCurrent()
        assertNull(vm.uiState.value.warmUp)
        assertNull(settings.routinePrefsFlow.first().warmUpClosedRun)
    }

    @Test fun `disabled warmup stays hidden after Start`() = test {
        prepare()
        settings.setWarmUpEnabled(false)
        val vm = readyVm()
        start(vm)
        assertNull(vm.uiState.value.warmUp)
    }

    @Test fun `closing routines hides them before persistence and keeps progress until the write completes`() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        start(vm)
        for (kind in RoutineKind.entries) {
            if (kind == RoutineKind.COOL_DOWN) logAll(vm)
            val sheet = requireNotNull(when (kind) {
                RoutineKind.WARM_UP -> vm.uiState.value.warmUp
                RoutineKind.COOL_DOWN -> vm.uiState.value.coolDown
            })
            val key = if (kind == RoutineKind.WARM_UP) "day_warm_up" else "day_cool_down"
            vm.toggleRoutineMove(kind, sheet.moves.first())
            runCurrent()
            vm.startRoutineTimer(kind)
            runCurrent()
            val progress = requireNotNull(handle.get<RoutineProgress>(key))
            val gate = CompletableDeferred<Unit>()
            preferenceWriteGate = gate
            vm.closeRoutine(kind)
            runCurrent()
            assertNull(when (kind) {
                RoutineKind.WARM_UP -> vm.uiState.value.warmUp
                RoutineKind.COOL_DOWN -> vm.uiState.value.coolDown
            })
            assertEquals(progress, handle.get<RoutineProgress>(key))
            val pendingPrefs = settings.routinePrefsFlow.first()
            assertNull(if (kind == RoutineKind.WARM_UP) pendingPrefs.warmUpClosedRun else pendingPrefs.coolDownClosedRun)
            gate.complete(Unit)
            runCurrent()
            assertNull(handle.get<RoutineProgress>(key))
            val closedPrefs = settings.routinePrefsFlow.first()
            assertEquals(sheet.runId,
                if (kind == RoutineKind.WARM_UP) closedPrefs.warmUpClosedRun else closedPrefs.coolDownClosedRun)
            preferenceWriteGate = null
        }
    }

    @Test fun `local dismissal survives recreation while its preference write is pending`() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        start(first)
        preferenceWriteGate = CompletableDeferred()
        first.closeRoutine(RoutineKind.WARM_UP)
        runCurrent()
        first.viewModelScope.cancel()
        val restored = readyVm(handle)
        assertNull(restored.uiState.value.warmUp)
        assertNull(settings.routinePrefsFlow.first().warmUpClosedRun)
    }

    @Test fun `tick before Start bypasses warmup after confirmation`() = test {
        prepare()
        val vm = readyVm()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 0, true, Slot.MAIN)
        runCurrent()
        assertTrue(vm.uiState.value.startSheet)
        vm.confirmStart()
        runCurrent()
        assertEquals(1, vm.uiState.value.doneSets)
        assertNull(vm.uiState.value.warmUp)
    }

    // (F)
    @Test fun `cooldown closes once per run and Finish still yields receipt`() = test {
        prepare()
        val vm = readyVm()
        start(vm)
        logAll(vm)
        assertTrue(Move.DOORWAY_CHEST in requireNotNull(vm.uiState.value.coolDown).moves)
        vm.closeRoutine(RoutineKind.COOL_DOWN)
        runCurrent()
        assertNull(vm.uiState.value.coolDown)
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 2, false, Slot.MAIN)
        runCurrent()
        vm.toggleDone(id, 2, true, Slot.MAIN)
        runCurrent()
        assertNull(vm.uiState.value.coolDown)
        vm.completeDay()
        runCurrent()
        assertNotNull(vm.finisher.receipt.value)
    }

    @Test fun `partial and disabled cooldown stay hidden`() = test {
        prepare()
        val vm = readyVm()
        start(vm)
        repeat(2) { vm.logFocusedSet(); runCurrent() }
        assertNull(vm.uiState.value.coolDown)
        settings.setCoolDownEnabled(false)
        runCurrent()
        vm.logFocusedSet()
        runCurrent()
        assertTrue(vm.uiState.value.workoutAllLogged)
        assertNull(vm.uiState.value.coolDown)
    }

    private class WorkoutClock(var now: Long = 100_000) : Clock(), CardioClock {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long = now
        override fun wallMillis(): Long = now
        override fun elapsedRealtimeMillis(): Long = now
        override fun bootCount(): Int = 1
    }
    private object InertAlarm : CardioAlarm {
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = Unit
        override fun cancel() = Unit
        override fun buzz() = Unit
    }
}
