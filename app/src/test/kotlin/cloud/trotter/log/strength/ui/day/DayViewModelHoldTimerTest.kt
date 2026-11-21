package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.cardio.CardioBlocks
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.rest.RestRuntime
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
class DayViewModelHoldTimerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val clock = FakeCardioClock()
    private val alarm = FakeCardioAlarm()
    private val rest = FakeRestRuntime()
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

    private suspend fun prepare(
        prescription: Prescription? = Prescription(PrescribedTarget.Hold(30)),
        exerciseId: String = "plank",
    ) {
        settings.setPhoneRestTimerEnabled(true)
        repo.replaceProgram(
            Program(listOf(ProgramDay(
                "A", "Holds", "", listOf(ProgramExercise(
                    exerciseId, targetSets = 2, prescription = prescription,
                )), cardioBlock = CardioBlocks.fromLegacy(CardioSuggestion("Easy cardio", "Run easy.", hard = false), true, CardioActivity.ROAD_RUN),
            ))),
        )
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

    private fun recreateHandle(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    @Test fun `start counts up from zero and keeps the screen on`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        val hold = requireNotNull(vm.uiState.value.hold)
        assertEquals(0, hold.heldSeconds)
        assertTrue(hold.event.running)
        assertTrue(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(clock.elapsed + 30_000, alarm.deadline)
        tick(12_000)
        assertEquals(12, vm.uiState.value.hold?.heldSeconds)
        assertFalse(row().done)
    }

    @Test fun `the hold logs its target at the target and buzzes once`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val start = clock.wall
        vm.startHold()
        runCurrent()
        tick(30_000)
        val row = row()
        assertTrue(row.done)
        assertEquals(30, row.seconds)
        assertEquals(start, row.startedAtMillis)
        assertEquals(start + 30_000, row.completedAtMillis)
        assertEquals(1, alarm.buzzes)
        assertNull(vm.uiState.value.hold)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(1, rest.armed.size)
        assertEquals(0, settings.workoutRecordFlow.first()?.undo?.index)
    }

    @Test fun `with the screen off the alarm logs the hold`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        vm.setCardioScreenLive(false)
        clock.advance(30_000)
        alarm.fire()
        runCurrent()
        assertTrue(row().done)
        assertEquals(30, row().seconds)
        assertEquals(1, alarm.buzzes)
    }

    @Test fun `stop early logs the shorter hold`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val start = clock.wall
        vm.startHold()
        runCurrent()
        tick(18_000)
        vm.stopHold()
        runCurrent()
        assertTrue(row().done)
        assertEquals(18, row().seconds)
        assertEquals(start, row().startedAtMillis)
        assertEquals(start + 18_000, row().completedAtMillis)
        assertEquals(0, alarm.buzzes)
        assertNull(alarm.deadline)
        assertEquals(1, rest.armed.size)
    }

    @Test fun `stop before a second logs nothing`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        clock.advance(400)
        vm.stopHold()
        runCurrent()
        assertNull(vm.uiState.value.hold)
        assertFalse(row().done)
        assertEquals(30, row().seconds)
        assertNull(alarm.deadline)
    }

    @Test fun `undo puts the planned target back`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        tick(18_000)
        vm.stopHold()
        runCurrent()
        assertEquals(18, row().seconds)
        vm.undoLastLog()
        runCurrent()
        assertFalse(row().done)
        assertEquals(30, row().seconds)
    }

    @Test fun `stop at a fractional second offers Undo and restores the planned target`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val start = clock.wall
        vm.startHold()
        runCurrent()
        tick(18_400)
        vm.stopHold()
        runCurrent()
        assertEquals(18, row().seconds)
        assertEquals(start, row().startedAtMillis)
        assertEquals(start + 18_000, row().completedAtMillis)
        val offer = requireNotNull(vm.uiState.value.workout?.undo)
        assertEquals(row(), offer.set)
        assertEquals(row().completedAtMillis, settings.workoutRecordFlow.first()?.undo?.loggedAtMillis)
        val startedRest = rest.armed.single()
        assertEquals(clock.wall + startedRest.totalSeconds * 1_000L, startedRest.deadlineEpochMillis)
        vm.undoLastLog()
        runCurrent()
        assertFalse(row().done)
        assertEquals(30, row().seconds)
        assertNull(row().startedAtMillis)
        assertNull(row().completedAtMillis)
    }

    @Test fun `a late auto log offers Undo and restores the planned target`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val start = clock.wall
        vm.startHold()
        runCurrent()
        vm.setCardioScreenLive(false)
        clock.advance(35_400)
        alarm.fire()
        runCurrent()
        assertEquals(30, row().seconds)
        assertEquals(start, row().startedAtMillis)
        assertEquals(start + 30_000, row().completedAtMillis)
        val offer = requireNotNull(vm.uiState.value.workout?.undo)
        assertEquals(row(), offer.set)
        assertEquals(row().completedAtMillis, settings.workoutRecordFlow.first()?.undo?.loggedAtMillis)
        val startedRest = rest.armed.single()
        assertEquals(clock.wall + startedRest.totalSeconds * 1_000L, startedRest.deadlineEpochMillis)
        assertEquals(1, alarm.buzzes)
        vm.undoLastLog()
        runCurrent()
        assertFalse(row().done)
        assertEquals(30, row().seconds)
        assertNull(row().startedAtMillis)
        assertNull(row().completedAtMillis)
    }

    @Test fun `each side runs two holds and logs the shorter`() = test {
        prepare(Prescription(PrescribedTarget.Hold(30), eachSide = true))
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val start = clock.wall
        vm.startHold()
        runCurrent()
        tick(30_000)
        val waiting = requireNotNull(vm.uiState.value.hold)
        assertFalse(waiting.event.running)
        assertEquals(0, waiting.heldSeconds) // #628: side two waits at 0:00 (spec §3)
        assertEquals(listOf(30), waiting.event.heldSeconds)
        assertEquals(2, waiting.event.sideNumber)
        assertFalse(row().done)
        assertEquals(1, alarm.buzzes)
        assertNull(alarm.deadline)
        assertTrue(vm.uiState.value.executionKeepsScreenOn)
        tick(60_000)
        assertEquals(waiting, vm.uiState.value.hold)
        vm.startHold()
        runCurrent()
        tick(20_000)
        vm.stopHold()
        runCurrent()
        assertTrue(row().done)
        assertEquals(20, row().seconds)
        assertEquals(start, row().startedAtMillis)
        assertEquals(start + 110_000, row().completedAtMillis)
        assertEquals(1, alarm.buzzes)
    }

    @Test fun `ending between sides logs side one and frees cardio Start`() = test {
        prepare(Prescription(PrescribedTarget.Hold(30), eachSide = true))
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val start = clock.wall
        vm.startHold()
        runCurrent()
        tick(30_000)
        assertFalse(requireNotNull(vm.uiState.value.hold).event.running)
        assertFalse(vm.uiState.value.cardioStartEnabled)
        tick(60_000)
        vm.stopHold()
        runCurrent()
        assertTrue(row().done)
        assertEquals(30, row().seconds)
        assertEquals(start, row().startedAtMillis)
        assertEquals(start + 30_000, row().completedAtMillis)
        assertNull(vm.uiState.value.hold)
        assertNull(alarm.deadline)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(1, alarm.buzzes)
        assertTrue(vm.uiState.value.cardioStartEnabled)
        vm.startCardio()
        runCurrent()
        assertNotNull(vm.uiState.value.cardio?.run)
    }

    @Test fun `ending between sides after Stop logs the whole seconds held`() = test {
        prepare(Prescription(PrescribedTarget.Hold(30), eachSide = true))
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val start = clock.wall
        vm.startHold()
        runCurrent()
        tick(18_400)
        vm.stopHold()
        runCurrent()
        assertEquals(0, vm.uiState.value.hold?.heldSeconds) // #628: side two waits at 0:00 (spec §3)
        assertEquals(listOf(18), vm.uiState.value.hold?.event?.heldSeconds)
        assertFalse(row().done)
        vm.stopHold()
        runCurrent()
        assertTrue(row().done)
        assertEquals(18, row().seconds)
        assertEquals(start + 18_000, row().completedAtMillis)
        assertNull(vm.uiState.value.hold)
        assertNotNull(vm.uiState.value.workout?.undo)
        assertTrue(vm.uiState.value.cardioStartEnabled)
        assertEquals(0, alarm.buzzes)
    }

    @Test fun `a catalog each side entry runs two holds`() = test {
        prepare(prescription = null, exerciseId = "side_plank")
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val card = vm.uiState.value.exercises.single()
        assertTrue(card.headerTarget.eachSide)
        vm.startHold()
        runCurrent()
        val event = requireNotNull(vm.uiState.value.hold).event
        assertEquals(2, event.sides)
        assertEquals(card.rows[0].seconds, event.targetSeconds)
    }

    @Test fun `a loaded hold keeps its weight`() = test {
        prepare(Prescription(PrescribedTarget.Hold(30, addedLb = 25.0)), "weighted_plank")
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        tick(30_000)
        assertTrue(row().done)
        assertEquals(30, row().seconds)
        assertEquals(25.0, row().weightLb, 0.0)
    }

    @Test fun `process death mid hold restores the clock and rearms`() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startWorkout()
        runCurrent()
        first.startHold()
        runCurrent()
        tick(10_000)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val saved = recreateHandle(handle)
        clock.advance(5_000)
        clock.wall += 3_600_000
        // #628: clear the old deadline so only the restored ViewModel can rearm it.
        alarm.cancel()
        val restored = readyVm(saved)
        assertEquals(15, restored.uiState.value.hold?.heldSeconds)
        assertEquals(clock.elapsed + 15_000, alarm.deadline)
        assertEquals(0, alarm.buzzes)
        assertTrue(restored.uiState.value.executionKeepsScreenOn)
    }

    @Test fun `a hold that ended while the process was gone logs silently`() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startWorkout()
        runCurrent()
        val start = clock.wall
        first.startHold()
        runCurrent()
        tick(10_000)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val saved = recreateHandle(handle)
        clock.advance(40_000)
        val restored = readyVm(saved)
        assertTrue(row().done)
        assertEquals(30, row().seconds)
        assertEquals(start + 30_000, row().completedAtMillis)
        assertEquals(0, alarm.buzzes)
        assertNull(restored.uiState.value.hold)
    }

    @Test fun `a hold blocks cardio and cardio blocks a hold`() = test {
        prepare()
        val first = readyVm()
        first.startWorkout()
        runCurrent()
        first.startHold()
        runCurrent()
        first.startCardio()
        runCurrent()
        assertNull(first.uiState.value.cardio?.run)
        assertFalse(first.uiState.value.cardioStartEnabled)
        first.stopHold()
        runCurrent()
        first.viewModelScope.cancel()
        val second = readyVm()
        second.startWorkout()
        runCurrent()
        second.startCardio()
        runCurrent()
        assertNotNull(second.uiState.value.cardio?.run)
        second.startHold()
        runCurrent()
        assertNull(second.uiState.value.hold)
    }

    @Test fun `a set logged elsewhere ends the hold without a buzz`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 0, true, Slot.MAIN)
        runCurrent()
        assertNull(vm.uiState.value.hold)
        assertNull(alarm.deadline)
        assertEquals(0, alarm.buzzes)
    }

    @Test fun `removing the set cancels the hold`() = test {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        vm.startHold()
        runCurrent()
        vm.removeSet(vm.uiState.value.exercises.single().programExerciseId, 0, false)
        runCurrent()
        assertNull(vm.uiState.value.hold)
        assertNull(alarm.deadline)
    }

    @Test fun `clearing the view model cancels a running hold's alarm`() = test {
        prepare()
        val store = ViewModelStore()
        try {
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(vm())
            }
            val vm = ViewModelProvider(store, factory)[DayViewModel::class.java]
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            vm.setCardioScreenLive(true)
            vm.startWorkout()
            runCurrent()
            vm.startHold()
            runCurrent()
            assertNotNull(alarm.deadline)
            store.clear()
            assertNull(alarm.deadline)
        } finally {
            store.clear()
        }
    }

    @Test fun `start before the workout reserves the hold behind the start sheet`() = test {
        prepare()
        val vm = readyVm()
        vm.startHold()
        runCurrent()
        assertTrue(vm.uiState.value.startSheet)
        assertNull(vm.uiState.value.hold)
        vm.confirmStart()
        runCurrent()
        assertTrue(requireNotNull(vm.uiState.value.hold).event.running)
        assertTrue(vm.uiState.value.runOpen)
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
        var deadline: Long? = null
        var identity: String? = null
        var callback: (() -> Unit)? = null
        var buzzes = 0
        var quiet = false
        override fun buzz() { buzzes++ }
        fun fire() {
            val boundary = callback ?: return
            cancel()
            if (!quiet) buzz()
            boundary()
        }
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
            this.deadline = deadlineElapsedMillis
            this.identity = identity
            callback = onBoundary
            quiet = false
        }
        override fun armQuiet(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
            arm(deadlineElapsedMillis, identity, onBoundary)
            quiet = true
        }
        override fun cancel() { deadline = null; identity = null; callback = null }
    }

    private class FakeRestRuntime : RestRuntime {
        override val available = true
        val armed = mutableListOf<PhoneRest>()
        override fun arm(rest: PhoneRest) { armed += rest }
        override fun cancel() = Unit
        override fun complete() = true
    }
}
