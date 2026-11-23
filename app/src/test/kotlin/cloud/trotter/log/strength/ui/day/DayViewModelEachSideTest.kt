package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Side
import cloud.trotter.log.strength.domain.model.Sides
import cloud.trotter.log.strength.data.mapping.sides
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.health.SessionPublisher
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
class DayViewModelEachSideTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val clock = FixedCardioClock()
    private val alarm = InertCardioAlarm
    private val rest = cloud.trotter.log.strength.rest.NoOpRestRuntime
    private val vms = mutableListOf<DayViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("each-side-vm", ".preferences_pb")
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
        settings = settings,
        plans = repo.plans,
        catalog = repo.catalog,
        historyRepository = repo.history,
    ).also { vms += it }

    private suspend fun prepare(
        prescription: Prescription? = Prescription(PrescribedTarget.Reps(10), eachSide = true),
        exerciseId: String = "skater_squat",
    ) {
        settings.setPhoneRestTimerEnabled(true)
        repo.replaceProgram(
            Program(listOf(ProgramDay(
                "A", "Sides", "", listOf(ProgramExercise(
                    exerciseId, targetSets = 2, prescription = prescription,
                )),
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

    private suspend fun row() = repo.dayTracksFlow(requireNotNull(repo.plans.selectedPlanIdFlow.first()), "A")
        .first().tracks.single { it.slot == Slot.MAIN }.sets[0]

    private suspend fun TestScope.started(): Pair<DayViewModel, Long> {
        prepare()
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        return vm to vm.uiState.value.exercises.single().programExerciseId
    }

    @Test fun `Left and right seeds both sides from the set values`() = test {
        val (vm, id) = started()
        vm.setEachSide(id, 0, true)
        runCurrent()
        assertEquals(Sides(SideResult(0.0, 10, 0), SideResult(0.0, 10, 0)), row().sides)
        assertEquals(10, row().reps)
        assertEquals(row().sides, vm.uiState.value.exercises.single().rows.first().sides)
    }

    @Test fun `a side edit writes the weaker side as the set value`() = test {
        val (vm, id) = started()
        vm.setEachSide(id, 0, true)
        runCurrent()
        vm.changeSide(id, 0, Side.RIGHT, null, 8, null)
        runCurrent()
        assertEquals(8, row().reps)
        assertEquals(Sides(SideResult(0.0, 10, 0), SideResult(0.0, 8, 0)), row().sides)
    }

    @Test fun `Each side clears the sides and keeps the weaker value`() = test {
        val (vm, id) = started()
        vm.setEachSide(id, 0, true)
        runCurrent()
        vm.changeSide(id, 0, Side.RIGHT, null, 8, null)
        runCurrent()
        vm.setEachSide(id, 0, false)
        runCurrent()
        assertNull(row().sides)
        assertEquals(8, row().reps)
    }

    @Test fun `Log keeps the sides through Finish`() = test {
        val (vm, id) = started()
        vm.setEachSide(id, 0, true)
        runCurrent()
        vm.changeSide(id, 0, Side.RIGHT, null, 8, null)
        runCurrent()
        vm.logFocusedSet()
        runCurrent()
        vm.completeDay()
        runCurrent()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        val archived = repo.history.sessionSets(receipt.sessionId).first()
        assertEquals(8, archived.reps)
        assertEquals(Sides(SideResult(0.0, 10, 0), SideResult(0.0, 8, 0)), archived.sides())
    }

    @Test fun `a plain Log stores no sides`() = test {
        val (vm, _) = started()
        vm.logFocusedSet()
        runCurrent()
        assertTrue(row().done)
        assertNull(row().sides)
        vm.completeDay()
        runCurrent()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertNull(repo.history.sessionSets(receipt.sessionId).first().sides())
        assertTrue(requireNotNull(vm.finisher.receiptSides.value).sets.isEmpty())
    }
    @Test fun `loaded side edits convert the display unit and preserve the other side`() = test {
        prepare(Prescription(PrescribedTarget.LoadReps(25.0, 10), eachSide = true), "bss")
        settings.setUnit(cloud.trotter.log.strength.domain.units.WeightUnit.KG)
        val vm = readyVm()
        vm.startWorkout()
        runCurrent()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.setEachSide(id, 0, true)
        runCurrent()
        val before = requireNotNull(row().sides)
        vm.changeSide(id, 0, Side.RIGHT, 15.0, 8, null)
        runCurrent()
        val after = requireNotNull(row().sides)
        assertEquals(before.left, after.left)
        assertEquals(cloud.trotter.log.strength.domain.units.WeightUnit.KG.toLb(15.0), after.right.weightLb, 0.0)
        assertEquals(8, after.right.reps)
        assertEquals(minOf(before.left.weightLb, after.right.weightLb), row().weightLb, 0.0)
        assertEquals(8, row().reps)
    }

}
