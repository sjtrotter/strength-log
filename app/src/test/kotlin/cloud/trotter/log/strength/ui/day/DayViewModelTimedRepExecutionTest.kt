package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.generator.CardioBlocks
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
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.rest.RestRuntime
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
class DayViewModelTimedRepExecutionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
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
            File.createTempFile("timed-rep-vm", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
        share = ShareCardService(context, repo)
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
        repo, SessionPublisher.NoOp, share, handle, clock, alarm, rest,
    ).also { vms += it }

    private suspend fun prepare() {
        repo.setWizardAnswers(repo.wizardAnswersFlow.first().copy(cardio = CardioPrefs(CardioMode.OUTDOOR_RUN)))
        repo.replaceProgram(
            Program(listOf(ProgramDay(
                "A", "Timed reps", "", listOf(ProgramExercise(
                    "pushup", targetSets = 2, prescription = Prescription(PrescribedTarget.RepsInTime(120, 40)),
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

    private fun DayViewModel.start() {
        val card = uiState.value.exercises.single()
        startTimedRep(card.programExerciseId, card.focusIndex)
    }

    private fun TestScope.tick(millis: Long) {
        clock.advance(millis)
        advanceTimeBy(1_001)
        runCurrent()
    }

    private suspend fun TestScope.awaitingVm(): DayViewModel {
        prepare()
        val vm = readyVm()
        vm.start()
        runCurrent()
        tick(120_000)
        return vm
    }

    private fun recreateHandle(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    @Test
    fun countsDownThenRequestsEntryOnceAndBuzzesOnce() = test {
        prepare()
        val vm = readyVm()
        vm.start()
        runCurrent()
        assertTrue(vm.uiState.value.timedRepRunning)
        assertTrue(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(clock.elapsed + 120_000, alarm.deadline)
        tick(1_000)
        assertEquals(119, vm.uiState.value.timedRep?.remainingSeconds)
        tick(119_000)
        val state = vm.uiState.value
        assertEquals(0, state.timedRep?.remainingSeconds)
        assertTrue(state.timedRep!!.event.awaitingCount)
        assertTrue(state.timedRep.requestsSheet)
        assertFalse(state.executionKeepsScreenOn)
        assertFalse(state.exercises.single().rows[0].done)
        assertEquals(1, alarm.buzzes)

        vm.openTimedRepCount()
        runCurrent()
        assertEquals(OpenSheet.Kind.TIMED_REP_COUNT, vm.uiState.value.openSheet?.kind)
        assertFalse(vm.uiState.value.timedRep!!.requestsSheet)
        vm.openTimedRepCount()
        tick(60_000)
        assertEquals(1, alarm.buzzes)
        assertEquals(0, vm.uiState.value.timedRep?.remainingSeconds)
    }

    @Test
    fun exactAlarmBoundaryAndTickerCannotBuzzTwice() = test {
        prepare()
        val vm = readyVm()
        vm.start()
        runCurrent()
        clock.advance(120_000)
        alarm.fire()
        runCurrent()
        tick(1_000)
        assertEquals(1, alarm.buzzes)
        assertTrue(vm.uiState.value.timedRep!!.requestsSheet)
        assertNull(alarm.deadline)
    }

    /** [F] */
    @Test fun discardCancelsTheTimedRepAndItsAlarm() = test {
        prepare()
        val vm = readyVm()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 0, true, false)
        runCurrent()
        vm.start()
        runCurrent()
        assertTrue(vm.uiState.value.timedRepRunning)
        vm.discardWorkout()
        runCurrent()
        assertNull(repo.openRunFlow.first())
        assertTimedRepCleared(vm)
    }

    @Test
    fun stopRestoresFullDurationWithoutWritingOrStartingRest() = test {
        prepare()
        val vm = readyVm()
        val seeded = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first()
        vm.start()
        runCurrent()
        tick(42_000)
        vm.stopTimedRep()
        runCurrent()
        assertNull(vm.uiState.value.timedRep)
        assertEquals(120, vm.uiState.value.exercises.single().timedRepDurationSeconds)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(seeded, repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first())
        assertNull(repo.openRunFlow.map { it?.takeIf { run -> run.planId == repo.selectedPlanIdFlow.first()!! }?.startedAt }.first())
        assertNull(repo.phoneRestFlow.first())
        assertNull(alarm.deadline)
        assertEquals(0, alarm.buzzes)
    }

    @Test
    fun removingRunningSetCancelsInsteadOfRetargetingItsSuccessor() = test {
        prepare()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.start()
        runCurrent()
        val staleBoundary = requireNotNull(alarm.callback)
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.removeSet(id, 0, false)
        runCurrent()
        assertTimedRepCleared(vm)
        assertNull(handle.get<TimedRepEvent>("day_timed_rep_event"))
        staleBoundary()
        tick(120_000)
        assertTimedRepCleared(vm)
        assertEquals(0, alarm.buzzes)
        val remaining = vm.uiState.value.exercises.single().rows.single()
        assertFalse(remaining.done)
        assertEquals(0, remaining.reps)
        vm.start()
        runCurrent()
        assertEquals(120, vm.uiState.value.timedRep?.remainingSeconds)
    }

    @Test
    fun removingAwaitingSetDiscardsCountInsteadOfLoggingItsSuccessor() = test {
        val vm = awaitingVm()
        vm.openTimedRepCount()
        vm.changeTimedRepDraft("38")
        runCurrent()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.removeSet(id, 0, false)
        // Queue a submission that still holds the old event behind the removal.
        vm.logTimedRepCount()
        runCurrent()
        assertTimedRepCleared(vm)
        vm.logTimedRepCount()
        runCurrent()
        val remaining = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single { it.slot == Slot.MAIN }.sets.single()
        assertFalse(remaining.done)
        assertEquals(0, remaining.reps)
    }

    @Test
    fun removingSetAfterRunningEventAlsoCancels() = test {
        prepare()
        val vm = readyVm()
        vm.start()
        runCurrent()
        vm.removeSet(vm.uiState.value.exercises.single().programExerciseId, 1, false)
        runCurrent()
        assertTimedRepCleared(vm)
        assertFalse(vm.uiState.value.exercises.single().rows.single().done)
    }

    @Test
    fun removingSetAfterAwaitingEventAlsoDiscardsCount() = test {
        val vm = awaitingVm()
        vm.openTimedRepCount()
        vm.changeTimedRepDraft("38")
        vm.closeSheet()
        vm.removeSet(vm.uiState.value.exercises.single().programExerciseId, 1, false)
        runCurrent()
        assertTimedRepCleared(vm)
        vm.logTimedRepCount()
        runCurrent()
        assertEquals(0, vm.uiState.value.exercises.single().rows.single().reps)
    }

    @Test
    fun addingSetCancelsRunningEvent() = test {
        prepare()
        val vm = readyVm()
        vm.start()
        runCurrent()
        vm.addSet(vm.uiState.value.exercises.single().programExerciseId, false)
        runCurrent()
        assertEquals(3, vm.uiState.value.exercises.single().rows.size)
        assertTimedRepCleared(vm)
    }

    @Test
    fun addingSetDiscardsAwaitingCount() = test {
        val vm = awaitingVm()
        vm.openTimedRepCount()
        vm.changeTimedRepDraft("38")
        vm.addSet(vm.uiState.value.exercises.single().programExerciseId, false)
        runCurrent()
        assertEquals(3, vm.uiState.value.exercises.single().rows.size)
        assertTimedRepCleared(vm)
        vm.logTimedRepCount()
        runCurrent()
        assertTrue(vm.uiState.value.exercises.single().rows.all { !it.done && it.reps == 0 })
    }

    @Test
    fun undoingRemovalCancelsRunningEvent() = test {
        prepare()
        val vm = readyVm()
        vm.removeSet(vm.uiState.value.exercises.single().programExerciseId, 0, false)
        runCurrent()
        vm.start()
        runCurrent()
        vm.undoRemoveSet()
        runCurrent()
        assertEquals(2, vm.uiState.value.exercises.single().rows.size)
        assertTimedRepCleared(vm)
    }

    @Test
    fun undoingRemovalDiscardsAwaitingCount() = test {
        prepare()
        val vm = readyVm()
        vm.removeSet(vm.uiState.value.exercises.single().programExerciseId, 0, false)
        runCurrent()
        vm.start()
        runCurrent()
        // Reach the deadline without advancing past the undo window.
        tick(120_000)
        vm.openTimedRepCount()
        vm.changeTimedRepDraft("38")
        vm.undoRemoveSet()
        runCurrent()
        assertEquals(2, vm.uiState.value.exercises.single().rows.size)
        assertTimedRepCleared(vm)
        vm.logTimedRepCount()
        runCurrent()
        assertTrue(vm.uiState.value.exercises.single().rows.all { !it.done && it.reps == 0 })
    }

    @Test
    fun uncheckingEarlierSetPreservesRunningEventAndLogsCountToItsOriginalSet() = test {
        prepare()
        val vm = readyVm()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 0, true, false)
        runCurrent()
        vm.start()
        runCurrent()
        vm.toggleDone(id, 0, false, false)
        runCurrent()
        assertEquals(0, vm.uiState.value.exercises.single().focusIndex)
        assertEquals(1, vm.uiState.value.timedRep?.event?.setIndex)
        assertTrue(vm.uiState.value.timedRepRunning)
        tick(120_000)
        vm.openTimedRepCount()
        vm.changeTimedRepDraft("38")
        vm.closeSheet()
        runCurrent()
        assertTrue(vm.uiState.value.timedRep!!.event.sheetDismissed)
        vm.requestTimedRepCount()
        vm.openTimedRepCount()
        vm.logTimedRepCount()
        runCurrent()
        val rows = vm.uiState.value.exercises.single().rows
        assertFalse(rows[0].done)
        assertEquals(0, rows[0].reps)
        assertTrue(rows[1].done)
        assertEquals(38, rows[1].reps)
        assertTimedRepCleared(vm)
        vm.start()
        runCurrent()
        assertEquals(0, vm.uiState.value.timedRep?.event?.setIndex)
        assertTrue(vm.uiState.value.timedRepRunning)
    }

    private fun assertTimedRepCleared(vm: DayViewModel) {
        assertNull(vm.uiState.value.timedRep)
        assertNull(vm.uiState.value.openSheet)
        assertNull(alarm.deadline)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
        assertFalse(vm.uiState.value.executionBusy)
    }

    @Test
    fun logCountUsesRoundTickAndStartsUsualRestWithNoLoggedSeconds() = test {
        val vm = awaitingVm()
        repo.setPhoneRestTimerEnabled(true)
        vm.openTimedRepCount()
        vm.changeTimedRepDraft("38")
        vm.logTimedRepCount()
        vm.logTimedRepCount()
        runCurrent()
        val sets = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single { it.slot == Slot.MAIN }.sets
        assertEquals(38, sets[0].reps)
        assertTrue(sets[0].done)
        assertEquals(0, sets[0].seconds)
        assertFalse(sets[1].done)
        assertEquals(1, vm.uiState.value.exercises.single().focusIndex)
        assertNull(vm.uiState.value.timedRep)
        assertNull(vm.uiState.value.openSheet)
        assertTrue(repo.openRunFlow.map { it?.takeIf { run -> run.planId == repo.selectedPlanIdFlow.first()!! }?.startedAt }.first() != null)
        assertTrue(repo.phoneRestFlow.first() != null)
        assertEquals(1, rest.armed.size)
    }

    @Test
    fun blankCannotLogButExplicitZeroCan() = test {
        val vm = awaitingVm()
        vm.changeTimedRepDraft("")
        vm.logTimedRepCount()
        runCurrent()
        assertNull(vm.uiState.value.timedRep!!.event.count)
        assertFalse(repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single().sets[0].done)
        vm.changeTimedRepDraft("0")
        vm.logTimedRepCount()
        runCurrent()
        val set = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single().sets[0]
        assertEquals(0, set.reps)
        assertEquals(0, set.seconds)
        assertTrue(set.done)
        assertNull(vm.uiState.value.timedRep)
    }

    @Test
    fun laterRepsEditAcceptsZeroWithoutChangingCompletionOrSeconds() = test {
        val vm = awaitingVm()
        vm.changeTimedRepDraft("38")
        vm.logTimedRepCount()
        runCurrent()
        vm.changeReps(vm.uiState.value.exercises.single().programExerciseId, Slot.MAIN, 0, 0)
        runCurrent()
        val set = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single().sets[0]
        assertEquals(0, set.reps)
        assertEquals(0, set.seconds)
        assertTrue(set.done)
    }

    @Test
    fun processDeathMidClockRestoresMonotonicRemainingAndRearms() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.start()
        runCurrent()
        tick(35_000)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val saved = recreateHandle(handle)
        clock.advance(25_000)
        clock.wall += 3_600_000
        val restored = readyVm(saved)
        assertEquals(60, restored.uiState.value.timedRep?.remainingSeconds)
        assertTrue(restored.uiState.value.executionKeepsScreenOn)
        assertEquals(clock.elapsed + 60_000, alarm.deadline)
        assertEquals(0, alarm.buzzes)
    }

    @Test
    fun expiredOnRestorePresentsEntrySilentlyAndRebootUsesWallDelta() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.start()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val saved = recreateHandle(handle)
        clock.wall += 130_000
        clock.elapsed = 2_000
        clock.boot++
        val restored = readyVm(saved)
        assertTrue(restored.uiState.value.timedRep!!.requestsSheet)
        assertFalse(restored.uiState.value.executionKeepsScreenOn)
        assertNull(alarm.deadline)
        assertEquals(0, alarm.buzzes)
        restored.openTimedRepCount()
        runCurrent()
        assertEquals(OpenSheet.Kind.TIMED_REP_COUNT, restored.uiState.value.openSheet?.kind)
    }

    @Test
    fun expiredAfterOneBuzzDoesNotBuzzOnRestore() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.start()
        runCurrent()
        tick(120_000)
        assertEquals(1, alarm.buzzes)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val restored = readyVm(recreateHandle(handle))
        assertTrue(restored.uiState.value.timedRep!!.requestsSheet)
        assertEquals(1, alarm.buzzes)
    }

    @Test
    fun dismissalAndDraftSurviveProcessDeathUntilEnterReps() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.start()
        runCurrent()
        tick(120_000)
        first.openTimedRepCount()
        first.changeTimedRepDraft("38")
        first.closeSheet()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val restored = readyVm(recreateHandle(handle))
        assertEquals("38", restored.uiState.value.timedRep?.event?.draft)
        assertTrue(restored.uiState.value.timedRep!!.event.sheetDismissed)
        assertFalse(restored.uiState.value.timedRep!!.requestsSheet)
        assertNull(restored.uiState.value.openSheet)
        restored.requestTimedRepCount()
        runCurrent()
        assertTrue(restored.uiState.value.timedRep!!.requestsSheet)
        restored.openTimedRepCount()
        runCurrent()
        assertEquals(OpenSheet.Kind.TIMED_REP_COUNT, restored.uiState.value.openSheet?.kind)
        assertEquals("38", restored.uiState.value.timedRep?.event?.draft)
    }

    @Test
    fun backgroundCancelsAndReturnRearmsOnlyFutureDeadlineWithoutMissedBuzz() = test {
        prepare()
        val vm = readyVm()
        vm.start()
        runCurrent()
        vm.setCardioScreenLive(false)
        assertNull(alarm.deadline)
        tick(20_000)
        assertNull(alarm.deadline)
        vm.setCardioScreenLive(true)
        runCurrent()
        assertEquals(clock.elapsed + 100_000, alarm.deadline)
        vm.setCardioScreenLive(false)
        clock.advance(110_000)
        vm.setCardioScreenLive(true)
        runCurrent()
        assertTrue(vm.uiState.value.timedRep!!.requestsSheet)
        assertNull(alarm.deadline)
        assertEquals(0, alarm.buzzes)
    }

    @Test
    fun cardioAndTimedRepsRefuseConcurrentStartsInEitherOrder() = test {
        prepare()
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        val cardioDeadline = alarm.deadline
        vm.start()
        runCurrent()
        assertNull(vm.uiState.value.timedRep)
        assertEquals(CardioPhase.EXECUTING, vm.uiState.value.cardio?.phase)
        assertEquals(cardioDeadline, alarm.deadline)
        vm.endCardioBlock()
        runCurrent()
        vm.start()
        runCurrent()
        val repDeadline = alarm.deadline
        vm.startCardio()
        runCurrent()
        assertTrue(vm.uiState.value.timedRepRunning)
        assertEquals(CardioPhase.SUGGESTION, vm.uiState.value.cardio?.phase)
        assertEquals(repDeadline, alarm.deadline)
    }

    @Test
    fun queuedCardioStartCannotOvertakeTimedRepReservation() = test {
        prepare()
        val vm = readyVm()
        vm.startCardio()
        vm.start()
        runCurrent()
        assertTrue(vm.uiState.value.timedRepRunning)
        assertEquals(CardioPhase.SUGGESTION, vm.uiState.value.cardio?.phase)
    }

    @Test
    fun entryQueuesBehindExistingSetSheet() = test {
        prepare()
        val vm = readyVm()
        vm.start()
        runCurrent()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.openSetEditor(id, 1)
        tick(120_000)
        vm.openTimedRepCount()
        runCurrent()
        assertEquals(OpenSheet.Kind.EDITOR, vm.uiState.value.openSheet?.kind)
        assertTrue(vm.uiState.value.timedRep!!.requestsSheet)
        vm.closeSheet()
        vm.openTimedRepCount()
        runCurrent()
        assertEquals(OpenSheet.Kind.TIMED_REP_COUNT, vm.uiState.value.openSheet?.kind)
        assertFalse(vm.uiState.value.timedRep!!.requestsSheet)
    }

    @Test
    fun invalidSavedEditorDoesNotBlockThePendingCountSheet() = test {
        val vm = awaitingVm()
        vm.openSetEditor(Long.MAX_VALUE, 0)
        runCurrent()
        assertNull(vm.uiState.value.openSheet)
        vm.openTimedRepCount()
        runCurrent()
        assertEquals(OpenSheet.Kind.TIMED_REP_COUNT, vm.uiState.value.openSheet?.kind)
    }

    @Test
    fun loggingPendingRepsDoesNotCancelCardioStartedAfterTimeUp() = test {
        val vm = awaitingVm()
        vm.startCardio()
        runCurrent()
        val deadline = alarm.deadline
        vm.changeTimedRepDraft("38")
        vm.logTimedRepCount()
        runCurrent()
        assertNull(vm.uiState.value.timedRep)
        assertEquals(CardioPhase.EXECUTING, vm.uiState.value.cardio?.phase)
        assertEquals(deadline, alarm.deadline)
    }

    @Test
    fun finishingTheDayClearsTheRemainingTimedRepAttempt() = test {
        val vm = awaitingVm()
        vm.logTimedRepCount()
        runCurrent()
        vm.start()
        runCurrent()
        assertTrue(vm.uiState.value.timedRepRunning)
        vm.completeDay()
        runCurrent()
        assertNull(vm.uiState.value.timedRep)
        assertNull(alarm.deadline)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
    }

    @Test
    fun mismatchedSlotIdentityOnRestoreDiscardsExecution() = test {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.start()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val saved = recreateHandle(handle)
        val id = repo.daySlotsFlow("A").first().single().programExerciseId
        repo.swapExerciseById("A", id, "plank")
        val restored = readyVm(saved)
        assertNull(restored.uiState.value.timedRep)
        assertNull(saved.get<TimedRepEvent>("day_timed_rep_event"))
        assertNull(alarm.deadline)
        assertFalse(restored.uiState.value.executionKeepsScreenOn)
    }

    @Test
    fun mismatchedDurationOnRestoreDiscardsAwaitingEntry() = test {
        val planId = repo.createAuthoredPlan("Timed reps", "Day A")
        repo.publishCreatedAuthoredPlan(planId)
        repo.addExercise("A", ProgramExercise("pushup"))
        val id = repo.daySlotsFlow("A").first().single().programExerciseId
        repo.setPrescription(id, Prescription(PrescribedTarget.RepsInTime(120, 40)), 2)
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.start()
        runCurrent()
        tick(120_000)
        first.openTimedRepCount()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val saved = recreateHandle(handle)
        repo.setPrescription(id, Prescription(PrescribedTarget.RepsInTime(60, 40)))
        assertEquals(
            PrescribedTarget.RepsInTime(60, 40),
            repo.daySlotsFlow("A").first().single().exercise.prescription?.target,
        )
        val restored = readyVm(saved)
        assertNull(restored.uiState.value.timedRep)
        assertNull(restored.uiState.value.openSheet)
        assertEquals(1, alarm.buzzes)
    }

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
        var callback: (() -> Unit)? = null
        var buzzes = 0
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
            deadline = deadlineElapsedMillis
            callback = onBoundary
        }
        override fun cancel() { deadline = null; callback = null }
        override fun buzz() { buzzes++ }
        fun fire() {
            val boundary = callback ?: return
            cancel()
            buzz()
            boundary()
        }
    }

    private class FakeRestRuntime : RestRuntime {
        override val available = true
        val armed = mutableListOf<PhoneRest>()
        override fun arm(rest: PhoneRest) { armed += rest }
        override fun cancel() = Unit
        override fun complete() = true
    }
}
