package cloud.trotter.log.strength.ui.day

import kotlinx.coroutines.flow.map
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
import cloud.trotter.log.strength.domain.glance.DayProgress
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.sync.WatchSnapshotBuilder
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
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
 * The session receipt's *wiring*: that it comes off the DONE-advance
 * transition, reports the session that transition just committed, and dies the
 * way a moment dies. [SessionReceiptBuilderTest] pins what it contains.
 *
 * The SHARE render itself is not re-tested here. [ShareCardService] hops onto
 * `Dispatchers.IO` for the Canvas work, which this class's virtual-time
 * dispatcher cannot observe, and
 * [cloud.trotter.log.strength.ui.log.share.ShareCardServiceTest] already pins
 * that end of the path for a session id. What matters here is the handle: the
 * receipt names the id of the row [TrackerRepository.finishRun] wrote, and
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
    fun finishingAPausedNonpreferredRunReportsItsOwnNextDay() = runVmTest {
        insertProgram()
        val owner = repo.selectedPlanIdFlow.first()!!
        val other = repo.duplicatePlan(owner, "Preferred")
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        val slot = repo.slotsFor(owner, "A").first().first()
        vm.toggleDone(slot.programExerciseId, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        repo.useOnly(other)
        advanceUntilIdle()
        assertEquals(owner, vm.uiState.value.planId)
        vm.completeDay()
        advanceUntilIdle()
        assertEquals("B", db.planDao().plan(owner)!!.suggestedDayId)
        assertEquals("A", db.planDao().plan(other)!!.suggestedDayId)
        assertEquals("Day B, Upper", vm.sessionReceipt.value?.nextDayLine)
        assertEquals(owner, repo.session(vm.sessionReceipt.value!!.sessionId)?.planId)
    }

    @Test
    fun completingStandaloneCardioProducesAReceiptWithoutAdvancingStrength() = runVmTest {
        insertProgram()
        val cardio = ProgramDay(
            id = "C", title = "Cardio + Core", emphasisLine = "",
            exercises = listOf(ProgramExercise("plank", targetSets = 2)), kind = ProgramDayKind.CARDIO,
        )
        repo.replaceProgram(Program(repo.programFlow.first().days + cardio))
        db.planDao().updateSuggestedDay(repo.selectedPlanIdFlow.first()!!, "B")
        val vm = newCollectingViewModel()
        vm.selectDay("C")
        advanceUntilIdle()
        val slot = repo.daySlotsFlow("C").first().single()
        val rows = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "C").map { it.tracks }.first().single().sets
        rows.indices.forEach { vm.toggleDone(slot.programExerciseId, it, checked = true, slot = Slot.MAIN) }
        advanceUntilIdle()
        val completedSetCount = vm.uiState.value.doneSets

        vm.completeDay()
        advanceUntilIdle()

        val receipt = vm.sessionReceipt.value!!
        assertEquals("Cardio + Core", receipt.dayTitle)
        assertEquals(2, completedSetCount)
        assertEquals(completedSetCount, receipt.setCount)
        assertEquals("Day B, Upper", receipt.nextDayLine)
        assertEquals("B", repo.suggestedDayFlow.first())
        assertEquals("C", repo.session(receipt.sessionId)?.dayId)
        assertEquals(false, repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "C").map { it.tracks }.first().single().sets.any { it.done })
    }

    @Test
    fun authoredStrengthCompletionAndReceiptBothSelectTheFollowingCardioDay() = runVmTest {
        val planId = repo.createAuthoredPlan("Mixed plan", "Lower")
        repo.publishCreatedAuthoredPlan(planId)
        insertProgram()
        val days = repo.programFlow.first().days
        repo.replaceProgram(Program(listOf(
            days[0],
            ProgramDay("B", "Cardio", "", emptyList(), cardioBlock = cardioFixture().block),
            days[1].copy(id = "C"),
        )))
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        assertEquals("B", repo.suggestedDayFlow.first())
        assertEquals("Day B, Cardio", vm.sessionReceipt.value?.nextDayLine)
    }

    @Test
    fun generatedStrengthCompletionAndReceiptSkipTheFollowingStandaloneCardioDay() = runVmTest {
        insertProgram()
        val days = repo.programFlow.first().days
        repo.replaceProgram(Program(listOf(
            days[0],
            ProgramDay("C1", "Cardio", "", listOf(ProgramExercise("plank", targetSets = 2)),
                kind = ProgramDayKind.CARDIO, cardioBlock = cardioFixture().block),
            days[1],
        )))
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        assertEquals("B", repo.suggestedDayFlow.first())
        assertEquals("Day B, Upper", vm.sessionReceipt.value?.nextDayLine)
    }

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
        assertEquals("Day B, Upper", receipt.nextDayLine)
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
        val partnerRows = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().first { it.slot == Slot.SS }.sets.size
        vm.completeDay()
        advanceUntilIdle()

        // The partner track really is populated, so this isn't passing by having
        // nothing to over-count.
        assertEquals(headerCount, partnerRows)
        assertEquals(headerCount, vm.sessionReceipt.value?.setCount)
    }

    @Test
    fun partialAndLegacySupersetRowsKeepDaySnapshotAndReceiptCountsMainBased() = runVmTest {
        for ((mainDone, partnerDone) in listOf(true to false, false to true, true to true)) {
            repo.replaceProgram(Program(listOf(ProgramDay("A", "Arms", "", listOf(
                ProgramExercise("ez_curl", targetSets = 1, superset = SupersetPartner("rope_pushdown")),
            )))))
            val vm = newCollectingViewModel()
            advanceUntilIdle()
            val planId = requireNotNull(repo.selectedPlanIdFlow.first())
            val id = repo.slotsFor(planId, "A").first().single().programExerciseId
            if (mainDone) vm.toggleDone(id, 0, true, Slot.MAIN)
            if (partnerDone) vm.toggleDone(id, 0, true, Slot.SS)
            advanceUntilIdle()
            val expectedDone = if (mainDone) 1 else 0
            val tracks = repo.dayTracksFlow(planId, "A").first()
            assertEquals(mainDone, tracks.tracks.first { it.slot == Slot.MAIN }.sets.single().done)
            assertEquals(partnerDone, tracks.tracks.first { it.slot == Slot.SS }.sets.single().done)
            assertEquals(expectedDone, vm.uiState.value.doneSets)
            assertEquals(1, vm.uiState.value.totalSets)
            assertEquals(0, tracks.retiredRoundsDone)
            val snapshot = requireNotNull(WatchSnapshotBuilder.build(
                program = repo.programFor(planId).first(), suggestedDayId = "A",
                slots = repo.slotsFor(planId, "A").first(), logs = tracks.tracks,
                cfg = repo.wizardAnswersFor(planId).first().config, catalog = repo.catalogFlow.first(),
                unit = WeightUnit.LB, revision = 1, retiredRoundsDone = tracks.retiredRoundsDone,
            ))
            assertEquals(DayProgress(expectedDone, 1), DayProgress.of(snapshot))
            vm.completeDay()
            advanceUntilIdle()
            val receipt = requireNotNull(vm.sessionReceipt.value)
            assertEquals(expectedDone, receipt.completedSetCount)
            assertEquals(expectedDone, receipt.setCount)
            assertEquals(1, receipt.totalSetCount)
            vm.viewModelScope.cancel()
        }
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
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, handle, FixedCardioClock(), InertCardioAlarm).also { vms += it }

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
                    ),
                    ProgramDay(
                        id = "B",
                        title = "Upper",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_bench", isMain = true, targetSets = 6)),
                    ),
                ),
            ),
        )
    }

    /** One day with one superset slot. */
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
                    ),
                    ProgramDay(
                        id = "B",
                        title = "Upper",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_bench", isMain = true, targetSets = 6)),
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(): Long = repo.daySlotsFlow("A").first().first().programExerciseId

    private suspend fun mainTrack() = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().first { it.slot == Slot.MAIN }.sets

    private suspend fun mainTrackRowCount(): Int = mainTrack().size

    private suspend fun tickWholeTrack(vm: DayViewModel) {
        val id = slotId()
        val hasPartner = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().any { it.slot == Slot.SS }
        repeat(mainTrackRowCount()) { index ->
            vm.toggleDone(id, index, checked = true, slot = Slot.MAIN)
            if (hasPartner) vm.toggleDone(id, index, checked = true, slot = Slot.SS)
        }
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
