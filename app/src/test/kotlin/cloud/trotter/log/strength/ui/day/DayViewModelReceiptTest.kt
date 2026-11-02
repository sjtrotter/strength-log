package cloud.trotter.log.strength.ui.day

import kotlinx.coroutines.flow.map
import android.content.Context
import android.os.Parcel
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.SessionDao
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
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The session receipt's *wiring*: that it comes off the DONE-advance
 * transition, reports the committed session and survives process death until
 * dismissed. [SessionReceiptBuilderTest] pins what it contains.
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
    private var topSetHistoryReads = 0
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
            sessionDao = object : SessionDao by db.sessionDao() {
                override fun observeTopSets(topKind: String) = db.sessionDao().observeTopSets(topKind)
                    .onStart { topSetHistoryReads++ }
            },
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
        assertEquals("C", receipt.dayId)
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
        assertEquals("A", receipt!!.dayId)
        assertEquals(repo.session(receipt.sessionId)?.completedAt, receipt.completedAt)
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

    /** One Finish captures the new top set inside the committed day's receipt. */
    @Test
    fun theReceiptCarriesTheNewTopSetFromTheCompletedDay() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        vm.dismissSessionReceipt()
        raiseTopSet(vm)
        completeOneSession(vm)

        val receipt = requireNotNull(vm.sessionReceipt.value)
        assertEquals("A", receipt.dayId)
        assertEquals(0, receipt.dayIndex)
        assertEquals(listOf(CascadeLift("Barbell Back Squat", "235", "245")), receipt.newTopSets)
    }

    // --- and it goes ---------------------------------------------------------

    @Test
    fun dismissingItClearsIt() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        assertNotNull(handle.get<SessionReceipt>("day_session_receipt"))

        assertTrue(vm.dismissSessionReceipt())

        assertNull(vm.sessionReceipt.value)
        assertNull(handle.get<SessionReceipt>("day_session_receipt"))
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

    /** Restoring a frozen receipt must not re-read history to derive its top sets. */
    @Test
    fun aNewViewModelKeepsTheReceiptAndTopSetsFromTheSameHandleWithoutReadingHistory() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        vm.dismissSessionReceipt()
        raiseTopSet(vm)
        completeOneSession(vm)
        val receipt = requireNotNull(vm.sessionReceipt.value)
        assertEquals(listOf(CascadeLift("Barbell Back Squat", "235", "245")), receipt.newTopSets)
        vm.viewModelScope.cancel()
        val readsAtFinish = topSetHistoryReads
        assertTrue("the counter must observe Finish's history fetch", readsAtFinish > 0)

        val restored = newViewModel(handle)
        assertEquals(receipt, restored.sessionReceipt.value)
        advanceUntilIdle()

        assertEquals(receipt, restored.sessionReceipt.value)
        assertEquals("restore must not derive new top sets", readsAtFinish, topSetHistoryReads)
    }

    @Test
    fun theWholeReceiptSurvivesAParcelRoundTrip() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        vm.dismissSessionReceipt()
        raiseTopSet(vm)
        completeOneSession(vm)
        vm.setReceiptNote("  Felt steady  ")
        advanceUntilIdle()
        val receipt = requireNotNull(vm.sessionReceipt.value)
        assertEquals("Felt steady", receipt.note)
        assertEquals(receipt.note, repo.session(receipt.sessionId)?.note)
        vm.viewModelScope.cancel()
        val parcel = Parcel.obtain()
        val restoredHandle = try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(SessionReceipt::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }

        val restored = newViewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals(receipt, restored.sessionReceipt.value)
        assertEquals(receipt, restoredHandle.get<SessionReceipt>("day_session_receipt"))
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
