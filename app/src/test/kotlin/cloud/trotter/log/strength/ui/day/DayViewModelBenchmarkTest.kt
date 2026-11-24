package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.TestEventJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.Side
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
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
class DayViewModelBenchmarkTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val clock = FakeCardioClock()
    private val alarm = FakeCardioAlarm()
    private val rest = NoOpRestRuntime
    private val vms = mutableListOf<DayViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("hold-vm", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
            clock = clock,
        )
        share = ShareCardService(context, repo.history, settings = settings)
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = DayViewModel(
        repo, SessionPublisher.NoOp, share, handle, clock, alarm, PhoneRestController(settings, rest),
        clock = clock,
        settings = settings,
        plans = repo.plans,
        catalog = repo.catalog,
        historyRepository = repo.history,
    ).also { vms += it }

    private val hrp = TestRef("aft_hrp", 1, 120)
    private val plank = TestRef("aft_plank", 1)

    private suspend fun prepare(
        ref: TestRef = hrp,
        goal: Boolean = true,
        target: PrescribedTarget = if (ref == plank) PrescribedTarget.Hold(60) else PrescribedTarget.RepsInTime(120, 20),
        eachSide: Boolean = false,
    ) {
        val event = TestEvents.get(ref.eventId)
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Benchmarks", "", listOf(
            ProgramExercise(event.exerciseId, targetSets = 3),
        )))))
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val id = db.programDao().exercisesForDay(plan, "A").single().id
        repo.setPrescription(id, Prescription(target, eachSide = eachSide, test = ref))
        repo.plans.setTestGoal(plan, if (goal) TestGoal(listOf(TestEventGoal(ref, if (event.timed) 220 else 40))) else null)
    }

    private suspend fun TestScope.readyVm(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val vm = vm(handle)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.setCardioScreenLive(true)
        runCurrent()
        return vm
    }

    private fun DayViewModel.startHold() {
        val card = uiState.value.exercises.single()
        startHold(card.programExerciseId, card.focusIndex)
    }

    private fun TestScope.tick(millis: Long) {
        clock.advance(millis)
        advanceTimeBy(1_001)
        runCurrent()
    }

    private suspend fun row() = repo.dayTracksFlow(requireNotNull(repo.plans.selectedPlanIdFlow.first()), "A")
        .first().tracks.single { it.slot == Slot.MAIN }.sets[0]

    private suspend fun TestScope.logWindow(vm: DayViewModel, seconds: Int = 120) {
        val card = vm.uiState.value.exercises.single()
        vm.startTimedRep(card.programExerciseId, 0)
        runCurrent()
        tick(seconds * 1_000L)
        vm.changeTimedRepDraft("27")
        vm.logTimedRepCount()
        runCurrent()
    }

    @Test fun `case 51 a window benchmark writes its count and a hold benchmark its seconds`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val card = vm.uiState.value.exercises.single()
        assertEquals("AFT hand-release push-up", card.benchmark?.name)
        assertEquals(LiftMarker.BENCHMARK, card.marker)
        assertNull(card.timedRepAim)
        assertEquals(120, card.timedRepDurationSeconds)
        assertEquals(1, card.rows.size)
        logWindow(vm)
        assertTrue(row().done)
        assertEquals(27, row().reps)
        val result = repo.history.testResultsFlow.first().single()
        assertEquals("aft_hrp", result.eventId)
        assertEquals(1, result.eventVersion)
        assertEquals(120, result.windowSeconds)
        assertEquals(27, result.count)
        assertNull(result.seconds)
        assertEquals("TIME_UP", result.stop)
        assertEquals(TestEvents.get("aft_hrp"), TestEventJson.decode(result.rulesJson))
        TestEventJson.validateSnapshot(result.rulesJson, hrp, result.exerciseId)
        assertEquals(vm.uiState.value.runId, result.runId)
        assertEquals(card.programExerciseId, result.programExerciseId)
        assertEquals(0, result.setIndex)
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        assertEquals(plan, result.planId)
        assertEquals("A", result.dayId)
        assertEquals(card.exerciseId, result.exerciseId)
        vm.viewModelScope.cancel()
        repo.discardRun(requireNotNull(result.runId))
        assertEquals(listOf(result), repo.history.testResultsFlow.first())
        prepare(plank)
        val holdVm = readyVm()
        holdVm.startWorkout()
        runCurrent()
        holdVm.startHold()
        runCurrent()
        assertEquals(220, holdVm.uiState.value.hold?.event?.targetSeconds)
        tick(192_000)
        holdVm.stopHold()
        runCurrent()
        assertEquals(192, row().seconds)
        val held = repo.history.testResultsFlow.first().last()
        assertEquals(192, held.seconds)
        assertNull(held.count)
        assertNull(held.windowSeconds)
        assertEquals("STOPPED", held.stop)
        assertEquals(TestEvents.get("aft_plank"), TestEventJson.decode(held.rulesJson))
    }

    @Test fun `editing a done window benchmark updates its result without adding a row`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        logWindow(vm)
        val before = repo.history.testResultsFlow.first().single()
        assertEquals(27, before.count)
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.changeReps(id, Slot.MAIN, 0, 37)
        runCurrent()
        assertTrue(row().done)
        assertEquals(37, row().reps)
        assertEquals(listOf(before.copy(count = 37)), repo.history.testResultsFlow.first())
    }

    @Test fun `editing a done hold benchmark updates its seconds without adding a row`() = test {
        prepare(plank)
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        tick(192_000)
        vm.stopHold()
        runCurrent()
        val before = repo.history.testResultsFlow.first().single()
        assertEquals(192, before.seconds)
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.changeSeconds(id, Slot.MAIN, 0, 202)
        runCurrent()
        assertTrue(row().done)
        assertEquals(202, row().seconds)
        assertEquals(listOf(before.copy(seconds = 202)), repo.history.testResultsFlow.first())
    }

    @Test fun `editing a done benchmark side keeps its result at the weaker value`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        logWindow(vm)
        val before = repo.history.testResultsFlow.first().single()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.setEachSide(id, 0, true)
        runCurrent()
        vm.changeSide(id, 0, Side.RIGHT, null, 17, null)
        runCurrent()
        assertTrue(row().done)
        assertEquals(17, row().reps)
        assertEquals(listOf(before.copy(count = 17)), repo.history.testResultsFlow.first())
    }

    @Test fun `an unticked hold benchmark retries at the event ceiling`() = test {
        prepare(plank)
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        tick(192_000)
        vm.stopHold()
        runCurrent()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 0, false, Slot.MAIN)
        runCurrent()
        assertFalse(row().done)
        assertEquals(192, row().seconds)
        assertTrue(repo.history.testResultsFlow.first().isEmpty())
        vm.startHold()
        runCurrent()
        val ceiling = requireNotNull(TestEvents.get(plank.eventId).ceilingSeconds)
        assertEquals(ceiling, vm.uiState.value.hold?.event?.targetSeconds)
        tick(192_000)
        assertEquals(0, alarm.buzzes)
        assertTrue(requireNotNull(vm.uiState.value.hold).event.running)
        tick((ceiling - 192) * 1_000L)
        assertEquals(1, alarm.buzzes)
        assertTrue(requireNotNull(vm.uiState.value.hold).event.running)
        vm.stopHold()
        runCurrent()
        assertEquals(ceiling, repo.history.testResultsFlow.first().single().seconds)
    }

    @Test fun `removing a done benchmark row deletes its result before a row shifts into its index`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        logWindow(vm)
        val id = vm.uiState.value.exercises.single().programExerciseId
        assertEquals(1, repo.history.testResultsFlow.first().size)
        vm.removeSet(id, 0, false)
        runCurrent()
        assertEquals(1, vm.uiState.value.exercises.single().rows.size)
        assertEquals(1, repo.history.testResultsFlow.first().size)
        vm.addSet(id, false)
        runCurrent()
        assertEquals(2, vm.uiState.value.exercises.single().rows.size)
        vm.removeSet(id, 0, false)
        runCurrent()
        assertEquals(1, vm.uiState.value.exercises.single().rows.size)
        assertFalse(row().done)
        assertTrue(repo.history.testResultsFlow.first().isEmpty())
    }

    @Test fun `a hold benchmark buzzes once at the ceiling and ends itself at ten minutes`() = test {
        prepare(plank, eachSide = true)
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        assertFalse(vm.uiState.value.exercises.single().headerTarget.eachSide)
        vm.startHold()
        runCurrent()
        assertEquals(1, vm.uiState.value.hold?.event?.sides)
        tick(220_000)
        assertEquals(1, alarm.buzzes)
        assertTrue(requireNotNull(vm.uiState.value.hold).event.running)
        assertEquals(220, vm.uiState.value.hold?.heldSeconds)
        tick(380_000)
        assertTrue(row().done)
        assertEquals(600, row().seconds)
        assertEquals("LIMIT", repo.history.testResultsFlow.first().single().stop)
        assertEquals(1, alarm.buzzes)
    }

    @Test fun `undo of a benchmark row deletes its result`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        logWindow(vm)
        assertEquals(1, repo.history.testResultsFlow.first().size)
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 0, false, Slot.MAIN)
        runCurrent()
        assertFalse(row().done)
        assertTrue(repo.history.testResultsFlow.first().isEmpty())
    }

    @Test fun `snackbar Undo deletes the benchmark result and permits another attempt`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        logWindow(vm)
        assertEquals(1, repo.history.testResultsFlow.first().size)
        vm.undoLastLog()
        runCurrent()
        assertFalse(row().done)
        assertTrue(repo.history.testResultsFlow.first().isEmpty())
        logWindow(vm)
        assertEquals(1, repo.history.testResultsFlow.first().size)
    }

    @Test fun `a training window writes no result`() = test {
        prepare(goal = false)
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val card = vm.uiState.value.exercises.single()
        assertNull(card.benchmark)
        assertNull(card.prescriptionTest)
        assertEquals(20, card.timedRepAim)
        logWindow(vm)
        assertTrue(repo.history.testResultsFlow.first().isEmpty())
    }

    @Test fun `Finish after a benchmark keeps the slot rows and archives MEASURED`() = test {
        prepare()
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val id = db.programDao().exercisesForDay(plan, "A").single().id
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val rule = db.programDao().exercise(id)!!.ruleJson
        val before = db.programDao().logForSlot(plan, "A", id, Slot.MAIN)!!
        assertEquals(1, vm.uiState.value.exercises.single().rows.size)
        logWindow(vm)
        assertEquals(1, repo.history.testResultsFlow.first().size)
        vm.completeDay()
        runCurrent()
        assertEquals(List(3) { LoggedSet(0.0, 20, SetKind.WORK) }, SetJson.decodeSets(before.setsJson))
        assertEquals(before.setsJson, db.programDao().logForSlot(plan, "A", id, Slot.MAIN)!!.setsJson)
        assertEquals(rule, db.programDao().exercise(id)!!.ruleJson)
        assertEquals("MEASURED", repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).reason)
    }

    @Test fun `a custom benchmark records its typed window and full custom rules`() = test {
        val ref = TestRef("custom_pushup_window", 1, 60)
        prepare(ref, target = PrescribedTarget.RepsInTime(90, 20))
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        assertEquals(60, vm.uiState.value.exercises.single().timedRepDurationSeconds)
        logWindow(vm, 60)
        val result = repo.history.testResultsFlow.first().single()
        assertEquals(60, result.windowSeconds)
        assertEquals(27, result.count)
        assertEquals("TIME_UP", result.stop)
        assertEquals(TestEvents.get(ref.eventId), TestEventJson.decode(result.rulesJson))
        TestEventJson.validateSnapshot(result.rulesJson, ref, result.exerciseId)
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val id = db.programDao().exercisesForDay(plan, "A").single().id
        assertEquals(90, (PrescriptionJson.decode(db.programDao().exercise(id)!!.prescriptionJson)!!.target as PrescribedTarget.RepsInTime).seconds)
    }

    private class FakeCardioClock(
        var wall: Long = 1_800_000_000_000L,
        var elapsed: Long = 9_000_000L,
        var boot: Int = 7,
    ) : Clock(), CardioClock {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(wall)
        override fun millis(): Long = wall
        override fun wallMillis() = wall
        override fun elapsedRealtimeMillis() = elapsed
        override fun bootCount() = boot
        fun advance(millis: Long) { wall += millis; elapsed += millis }
    }

    private class FakeCardioAlarm : CardioAlarm {
        var buzzes = 0
        override fun buzz() { buzzes++ }
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = Unit
        override fun cancel() = Unit
    }
}
