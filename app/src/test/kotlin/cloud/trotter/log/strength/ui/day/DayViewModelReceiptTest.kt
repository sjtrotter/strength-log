package cloud.trotter.log.strength.ui.day

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
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.units.WeightStepper
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
 * The session receipt's *wiring* (#126): that it comes off the DONE-advance
 * transition, reports the session that transition just committed, and dies the
 * way a moment dies. [SessionReceiptBuilderTest] pins what it contains.
 *
 * The SHARE render itself is not re-tested here. [ShareCardService] hops onto
 * `Dispatchers.IO` for the Canvas work, which this class's virtual-time
 * dispatcher cannot observe, and
 * [cloud.trotter.log.strength.ui.log.share.ShareCardServiceTest] already pins
 * that end of the path for a session id. What matters here is the handle: the
 * receipt names the id of the row [TrackerRepository.advanceDay] wrote, and
 * that is the only thing this screen ever hands the share service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelReceiptTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
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
            File.createTempFile("receipt-vm-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
        shareCardService = ShareCardService(context, repo)
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    // --- it appears, once, and only on a DONE --------------------------------

    @Test
    fun thereIsNoReceiptUntilDoneCommitsSomething() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()

        tickWholeTrack(vm)
        advanceUntilIdle()

        assertNull("a ticked-but-unfinished day is not a receipt", vm.sessionReceipt.value)
    }

    @Test
    fun reportsTheDayThatWasCompletedAndTheOneTheRotationMovedTo() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        val receipt = vm.sessionReceipt.value
        assertNotNull(receipt)
        assertEquals("DAY A COMPLETE", receipt!!.headline)
        assertEquals("Lower", receipt.dayTitle)
        assertEquals("DAY B · UPPER", receipt.nextDayLine)
        // The completed day's accent, not the one the pointer now stands on.
        assertEquals(0, receipt.dayIndex)
    }

    @Test
    fun countsTheSetsTheSessionActuallyRecorded() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val rows = mainTrackRowCount()
        completeOneSession(vm)

        assertEquals(rows, vm.sessionReceipt.value?.setCount)
    }

    /** Derived from the live log rather than a hard-coded number: this test
     *  pins the wiring, and §11's seeded weights are pinned where they belong. */
    @Test
    fun namesTheHeaviestSetTheSessionRecorded() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val heaviest = mainTrack().maxByOrNull { it.weightLb }!!
        completeOneSession(vm)

        val receipt = vm.sessionReceipt.value
        assertEquals("Barbell Back Squat", receipt?.strongest?.name)
        assertEquals("${WeightStepper.format(heaviest.weightLb)}×${heaviest.reps}", receipt?.strongest?.value)
    }

    /**
     * The cross-surface pin: a superset day writes two rows per tick, and the
     * receipt must still report the number the header was showing a second
     * earlier. Anything else and the lifter watches "3 OF 3 SETS" become
     * "SETS 6" as the receipt lands.
     */
    @Test
    fun aSupersetDaysReceiptCountsWhatTheHeaderCounted() = runVmTest {
        insertSupersetProgram()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()

        val headerCount = vm.uiState.value.doneSets
        val partnerRows = repo.logFlow("A").first().first { it.slot == Slot.SS }.sets.size
        vm.completeDay()
        advanceUntilIdle()

        // The partner track really is populated, so this isn't passing by having
        // nothing to over-count.
        assertEquals(headerCount, partnerRows)
        assertEquals(headerCount, vm.sessionReceipt.value?.setCount)
    }

    /** The receipt's only live handle is the row the advance wrote — the same id
     *  the share path consumes. */
    @Test
    fun holdsTheIdOfTheSessionTheAdvanceJustWrote() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        assertEquals(repo.sessionsFlow.first().single().id, vm.sessionReceipt.value?.sessionId)
    }

    /** Both post-DONE surfaces come off one read of the committed session, so
     *  they can never describe different workouts. */
    @Test
    fun theCascadeAndTheReceiptDescribeTheSameCompletedDay() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        // A first session establishes the standing high; a second one at a
        // heavier TOP is what makes the cascade fire alongside the receipt.
        completeOneSession(vm)
        vm.dismissCascadeCeremony()
        vm.dismissSessionReceipt()
        raiseTopSet(vm)
        completeOneSession(vm)

        val cascade = vm.cascadeCeremony.value
        val receipt = vm.sessionReceipt.value
        assertNotNull("the heavier TOP should have cascaded", cascade)
        assertNotNull(receipt)
        assertEquals(cascade!!.dayIndex, receipt!!.dayIndex)
    }

    // --- and it goes ---------------------------------------------------------

    @Test
    fun dismissingItClearsIt() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        vm.dismissSessionReceipt()

        assertNull(vm.sessionReceipt.value)
    }

    /** A configuration change keeps the ViewModel, so it keeps the receipt. */
    @Test
    fun survivesAStateReEmission() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        vm.selectDay("B")
        advanceUntilIdle()

        assertNotNull(vm.sessionReceipt.value)
    }

    /** Process death does not. A restored ViewModel comes back to a saved
     *  workout and no summary of it — the same rule the cascade follows. */
    @Test
    fun doesNotComeBackFromProcessDeath() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        assertNotNull(vm.sessionReceipt.value)

        val restored = newViewModel(handle)
        advanceUntilIdle()

        assertNull(restored.sessionReceipt.value)
    }

    // --- share ---------------------------------------------------------------

    @Test
    fun shareIsANoOpWithNoReceiptToShare() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()

        vm.shareSession()
        advanceUntilIdle()

        assertNull(vm.pendingShare.value)
    }

    // --- fixtures ------------------------------------------------------------

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): DayViewModel =
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, handle, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), FixedCardioClock(), InertCardioAlarm).also { vms += it }

    /** [DayViewModel.uiState] is `WhileSubscribed`: without a live collector its
     *  `.value` never leaves the initial state, and the header count would read
     *  zero no matter what was ticked. */
    private fun newCollectingViewModel(): DayViewModel =
        newViewModel().also { vm -> vm.viewModelScope.launch { vm.uiState.collect {} } }

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
                    ProgramDay(
                        id = "B",
                        title = "Upper",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_bench", isMain = true, targetSets = 6)),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    /** One day, one superset slot: every tick writes a MAIN row and an SS row. */
    private suspend fun insertSupersetProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Arms",
                        emphasisLine = "",
                        exercises = listOf(
                            ProgramExercise("ez_curl", targetSets = 3, superset = SupersetPartner("rope_pushdown")),
                        ),
                        cardio = null,
                    ),
                    ProgramDay(
                        id = "B",
                        title = "Upper",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_bench", isMain = true, targetSets = 6)),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(): Long = repo.daySlotsFlow("A").first().first().programExerciseId

    private suspend fun mainTrack() = repo.logFlow("A").first().first { it.slot == Slot.MAIN }.sets

    private suspend fun mainTrackRowCount(): Int = mainTrack().size

    private suspend fun tickWholeTrack(vm: DayViewModel) {
        val id = slotId()
        // Derived, not passed in: a tick on a superset slot has to flip both
        // tracks, and a fixture that forgot would silently under-write the SS
        // rows the count test exists to catch.
        val isSuperset = repo.logFlow("A").first().any { it.slot == Slot.SS }
        repeat(mainTrackRowCount()) { index -> vm.toggleDone(id, index, checked = true, isSuperset = isSuperset) }
    }

    /** Edits the TOP set up a notch so the next advance records a new all-time
     *  high, which is what the cascade needs to fire. */
    private suspend fun TestScope.raiseTopSet(vm: DayViewModel) {
        vm.selectDay("A")
        advanceUntilIdle()
        val sets = mainTrack()
        val topIndex = sets.indices.maxByOrNull { sets[it].weightLb }!!
        vm.changeWeight(slotId(), Slot.MAIN, topIndex, sets[topIndex].weightLb + 10.0)
        advanceUntilIdle()
    }

    private suspend fun TestScope.completeOneSession(vm: DayViewModel) {
        vm.selectDay("A")
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
    }
}
