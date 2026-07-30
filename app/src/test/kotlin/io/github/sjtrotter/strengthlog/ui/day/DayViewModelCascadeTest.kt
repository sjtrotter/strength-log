package io.github.sjtrotter.strengthlog.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.transfer.health.SessionPublisher
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cascade ceremony's *wiring* (docs/briefs/journal.md §2): that it comes off
 * the DONE-advance transition and nothing else. [CascadeCeremonyTest] pins what
 * the event contains; this pins when it exists — and, mostly, when it must not:
 * a restored ViewModel, a re-entered screen, a state re-emission, and a plain
 * advance that beat nothing all have to stay silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelCascadeTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
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
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
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
        DayViewModel(repo, SessionPublisher.NoOp, handle).also { vms += it }

    private suspend fun insertProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Lower",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", isMain = true, targetSets = 6)),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(): Long =
        repo.daySlotsFlow("A").first().first().programExerciseId

    private suspend fun topIndex(): Int =
        repo.logFlow("A").first().first { it.slot == Slot.MAIN }.sets.indexOfFirst { it.kind == SetKind.TOP }

    /** Ticks every set of the seeded squat track, so the advance records a
     *  completed TOP set at whatever weight it currently carries. */
    private suspend fun tickWholeTrack(vm: DayViewModel) {
        val id = slotId()
        val rows = repo.logFlow("A").first().first { it.slot == Slot.MAIN }.sets.size
        repeat(rows) { index -> vm.toggleDone(id, index, checked = true, isSuperset = false) }
    }

    /** One full session at the current prescription, from a seeded program. */
    private suspend fun TestScope.completeOneSession(vm: DayViewModel) {
        tickWholeTrack(vm)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
    }

    // --- it fires ------------------------------------------------------------

    @Test
    fun firesOnceWhenAnAdvanceRecordsATopSetAboveTheStandingHigh() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()

        // A first session at the seeded 235 establishes the standing high.
        completeOneSession(vm)
        assertNull("a lift's first session is a starting point, not a cascade", vm.cascadeCeremony.value)

        // Cascade the TOP to spec §11's 245 and earn it.
        vm.changeWeight(slotId(), Slot.MAIN, topIndex(), 245.0)
        advanceUntilIdle()
        completeOneSession(vm)

        val ceremony = requireNotNull(vm.cascadeCeremony.value) { "expected a cascade ceremony" }
        assertEquals(1, ceremony.lifts.size)
        assertEquals("235", ceremony.lifts.single().metDisplay)
        assertEquals("245", ceremony.lifts.single().newDisplay)
        assertEquals(0, ceremony.dayIndex)
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
        assertNotNull(vm.cascadeCeremony.value)

        vm.dismissCascadeCeremony()
        assertNull(vm.cascadeCeremony.value)

        // Re-collecting state, re-reading the day, re-emitting uiState: all inert.
        vm.selectDay("A")
        advanceUntilIdle()
        assertNull("a state re-emission must not resurrect the moment", vm.cascadeCeremony.value)
    }

    // --- it stays silent -----------------------------------------------------

    @Test
    fun neverFiresOnAnAdvanceThatBeatNothing() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        completeOneSession(vm) // same prescription a second time

        assertNull(vm.cascadeCeremony.value)
    }

    @Test
    fun neverFiresOnProcessDeathRestore() = runVmTest {
        insertProgram()
        val first = newViewModel()
        advanceUntilIdle()
        completeOneSession(first)
        first.changeWeight(slotId(), Slot.MAIN, topIndex(), 245.0)
        advanceUntilIdle()
        completeOneSession(first)
        assertNotNull(first.cascadeCeremony.value)

        // Process death: the SavedStateHandle survives, the ViewModel doesn't.
        // The cascade is in the history now, and history is not a trigger.
        val restored = newViewModel(SavedStateHandle(mapOf("day_keep_screen_on" to true)))
        advanceUntilIdle()

        assertNull("a restored ViewModel must not replay the moment", restored.cascadeCeremony.value)
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
        first.dismissCascadeCeremony()

        // Navigating away and back builds a fresh ViewModel over the same DB.
        val reEntered = newViewModel()
        advanceUntilIdle()
        reEntered.selectDay("A")
        advanceUntilIdle()

        assertNull(reEntered.cascadeCeremony.value)
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
        val rows = repo.logFlow("A").first().first { it.slot == Slot.MAIN }.sets.size
        repeat(rows) { index -> if (index != top) vm.toggleDone(id, index, checked = true, isSuperset = false) }
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()

        assertNull(vm.cascadeCeremony.value)
    }
}
