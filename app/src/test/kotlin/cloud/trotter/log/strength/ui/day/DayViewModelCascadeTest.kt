package cloud.trotter.log.strength.ui.day

import kotlinx.coroutines.flow.map
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.storeTestRows
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelCascadeTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var shareCardService: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<DayViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cascade-vm-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        shareCardService = ShareCardService(context, repo.history, settings = settings)
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): DayViewModel =
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, handle, FixedCardioClock(), InertCardioAlarm, PhoneRestController(settings, NoOpRestRuntime), settings = settings, plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history).also { vms += it }

    private suspend fun insertProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Lower",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)),
                    ),
                ),
            ),
        )
        db.storeTestRows("A", slotId(), Slot.MAIN, listOf(
            LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
            LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
            LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        ))
    }

    private suspend fun slotId(): Long =
        repo.plans.daySlotsFlow("A").first().first().programExerciseId

    private suspend fun topIndex(): Int =
        repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().first { it.slot == Slot.MAIN }.sets.indexOfFirst { it.kind == SetKind.TOP }

    /** Ticks every set of the seeded squat track, so the advance records a
     *  completed TOP set at whatever weight it currently carries. */
    private suspend fun tickWholeTrack(vm: DayViewModel) {
        val id = slotId()
        val rows = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().first { it.slot == Slot.MAIN }.sets.size
        repeat(rows) { index -> vm.toggleDone(id, index, checked = true, slot = Slot.MAIN) }
    }

    /** One full session at the current prescription, from a seeded program. */
    private suspend fun TestScope.completeOneSession(vm: DayViewModel) {
        vm.startWorkout()
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
    }

    private fun DayViewModel.hasRecord(): Boolean =
        finisher.receipt.value?.groups.orEmpty().flatMap { it.rows }.any { it.record != null }

    @Test
    fun phoneWeightEditPassesUnitAndEquipmentToCascade() = runVmTest {
        settings.setUnit(WeightUnit.KG)
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        vm.changeWeight(slotId(), Slot.MAIN, topIndex(), 20.0)
        advanceUntilIdle()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val rows = repo.dayTracksFlow(plan, "A").first().tracks.single().sets
        rows.forEach { assertEquals(20.0, WeightUnit.KG.fromLb(it.weightLb), 1e-9) }
        vm.changeWeight(slotId(), Slot.MAIN, 0, 16.3)
        advanceUntilIdle()
        val manual = repo.dayTracksFlow(plan, "A").first().tracks.single().sets
        assertEquals(16.3, WeightUnit.KG.fromLb(manual[0].weightLb), 1e-9)
        assertEquals(rows.drop(1), manual.drop(1))
    }

    // --- it fires ------------------------------------------------------------

    @Test
    fun firesOnceWhenAnAdvanceRecordsATopSetAboveTheStandingHigh() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()

        // A first session at the seeded 235 establishes the standing high.
        completeOneSession(vm)
        assertTrue(!vm.hasRecord())

        // Cascade the TOP to spec §11's 245 and earn it.
        vm.changeWeight(slotId(), Slot.MAIN, topIndex(), 245.0)
        advanceUntilIdle()
        completeOneSession(vm)

        assertTrue(vm.hasRecord())
        val row = requireNotNull(vm.finisher.receipt.value).groups.single().rows.single()
        val record = requireNotNull(row.record)
        assertEquals(235.0, record.previous.weightLb, 0.0)
        assertEquals(245.0, row.sets[record.setIndex].weightLb, 0.0)
        vm.finisher.dismissReceipt()
        completeOneSession(vm)
        assertTrue(!vm.hasRecord())
        assertEquals(3, repo.history.sessionsFlow.first().size)
    }

    @Test
    fun dismissClearsItAndNothingBringsItBack() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        vm.changeWeight(slotId(), Slot.MAIN, topIndex(), 245.0)
        advanceUntilIdle()
        completeOneSession(vm)
        assertTrue(vm.hasRecord())

        vm.finisher.dismissReceipt()
        assertTrue(!vm.hasRecord())

        // Re-collecting state, re-reading the day, re-emitting uiState: all inert.
        vm.selectDay("A")
        advanceUntilIdle()
        assertTrue("a state re-emission must not resurrect the moment", !vm.hasRecord())
    }

    // --- it stays silent -----------------------------------------------------

    @Test
    fun neverFiresOnAnAdvanceThatBeatNothing() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        completeOneSession(vm) // same prescription a second time

        assertTrue(!vm.hasRecord())
    }

    @Test
    fun aFreshHandleDoesNotRecomputeTopSetsFromHistory() = runVmTest {
        insertProgram()
        val first = newViewModel()
        advanceUntilIdle()
        completeOneSession(first)
        first.changeWeight(slotId(), Slot.MAIN, topIndex(), 245.0)
        advanceUntilIdle()
        completeOneSession(first)
        assertTrue(first.hasRecord())

        // A fresh route has no pending receipt. History alone is not a trigger.
        val restored = newViewModel()
        advanceUntilIdle()

        assertTrue("a fresh route must not derive a moment from history", !restored.hasRecord())
    }

    @Test
    fun neverFiresOnReEnteringTheDayScreen() = runVmTest {
        insertProgram()
        val first = newViewModel()
        advanceUntilIdle()
        completeOneSession(first)
        first.changeWeight(slotId(), Slot.MAIN, topIndex(), 245.0)
        advanceUntilIdle()
        completeOneSession(first)
        first.finisher.dismissReceipt()

        // Navigating away and back builds a fresh ViewModel over the same DB.
        val reEntered = newViewModel()
        advanceUntilIdle()
        reEntered.selectDay("A")
        advanceUntilIdle()

        assertTrue(!reEntered.hasRecord())
    }

    @Test
    fun neverFiresWhenTheHeavierTopSetWasLeftUnticked() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        vm.changeWeight(slotId(), Slot.MAIN, topIndex(), 245.0)
        advanceUntilIdle()
        // Tick everything except the TOP row — the weight moved, the lift didn't.
        val id = slotId()
        val top = topIndex()
        val rows = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().first { it.slot == Slot.MAIN }.sets.size
        vm.startWorkout()
        advanceUntilIdle()
        repeat(rows) { index -> if (index != top) vm.toggleDone(id, index, checked = true, slot = Slot.MAIN) }
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()

        assertTrue(!vm.hasRecord())
    }
}
