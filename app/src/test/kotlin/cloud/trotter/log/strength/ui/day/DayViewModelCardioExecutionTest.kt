package cloud.trotter.log.strength.ui.day

import android.content.Context
import android.os.Parcel
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelCardioExecutionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val clock = FakeCardioClock()
    private val alarm = FakeCardioAlarm()
    private val vms = mutableListOf<DayViewModel>()
    private val publishedCardio = mutableListOf<Long>()
    private val publisher = object : SessionPublisher by SessionPublisher.NoOp {
        override suspend fun publishCardio(cardioSessionId: Long) { publishedCardio += cardioSessionId }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-vm", ".preferences_pb")
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
        repo, publisher, share, handle, MutableStateFlow(repo.currentDate()), clock, alarm,
    ).also { vms += it }

    private suspend fun prepare(fiveK: Boolean = true) {
        repo.setWizardAnswers(repo.wizardAnswersFlow.first().copy(cardio = CardioPrefs(CardioMode.OUTDOOR_RUN, fiveKGoal = fiveK)))
        repo.replaceProgram(
            Program(listOf(ProgramDay(
                "A", "Intervals", "", listOf(ProgramExercise("bb_back_squat")),
                CardioSuggestion("Hard cardio — intervals", "Run hard, recover easy.", hard = true),
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

    private fun TestScope.tick(millis: Long) {
        clock.advance(millis)
        advanceTimeBy(1_001)
        runCurrent()
    }

    private suspend fun prepareBlock(vararg sets: CardioSetSpec) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Running", "",
            listOf(ProgramExercise("bb_back_squat")), null,
            cardioBlock = CardioBlock(CardioActivity.TRACK_RUN, sets.toList())))))
    }

    private fun time(role: CardioRole, seconds: Int = 10, aim: Int? = null) =
        CardioSetSpec(role, Measure.Time(seconds), aim?.let { Measure.Distance(it) }, hard = role == CardioRole.WORK)

    private fun distance() = CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true)

    private fun DayViewModel.distanceAction(): CardioSetAction {
        val sheet = requireNotNull(uiState.value.openSheet)
        return CardioSetAction(sheet.dayId, sheet.cardioRunId, sheet.setIndex)
    }

    private fun recreateHandle(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private suspend fun prepareAuthoredCardioDay(vararg sets: CardioSetSpec) {
        val planId = repo.createAuthoredPlan("Running plan", "Running")
        repo.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(CardioActivity.TRACK_RUN, sets.toList())
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
    }

    private suspend fun TestScope.stopFirstRunAndDismissDistance(vm: DayViewModel): CardioRun {
        vm.startCardio()
        runCurrent()
        tick(1_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        val run = vm.uiState.value.cardio!!.distanceRuns.single()
        assertTrue(run.finishesDay)
        vm.closeSheet()
        runCurrent()
        return run
    }

    private fun TestScope.finishOldDistance(vm: DayViewModel, run: CardioRun) {
        vm.requestCardioDistance(run.action(run.drafts.single().position))
        vm.openCardioDistance()
        runCurrent()
        vm.changeCardioDistance("390")
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
    }

    @Test
    fun addDayThenAuthorCardioRunsAndFinishesWithoutExercises() = test {
        val plan = repo.createAuthoredPlan("Running", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
        assertEquals("B", repo.addDay(plan, "Run"))
        val vm = readyVm()
        backgroundScope.launch { vm.dayEditState.collect {} }
        vm.selectDay("B")
        runCurrent()
        vm.setDayEditorOpen(true)
        vm.openCardioBlock()
        runCurrent()
        val draft = vm.dayEditState.value.cardioDraft!!
        vm.changeCardioDraft(draft.copy(sets = listOf(CardioSetDraft(seconds = 10, hasAim = true))))
        vm.saveCardioBlock()
        runCurrent()
        vm.setDayEditorOpen(false)
        assertTrue(vm.uiState.value.exercises.isEmpty())
        assertTrue(vm.uiState.value.cardio!!.finishesDay)
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction()) // Blank distance is allowed by #280.
        runCurrent()
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(plan, session.planId)
        assertEquals("B", session.dayId)
        assertEquals(CardioSetStatus.FINISHED, repo.cardioSetsFor(session.id).single().status)
        assertEquals(session.id, vm.sessionReceipt.value?.sessionId)
        assertEquals("A", repo.suggestedDayFlow.first())
        assertEquals(listOf(session.id), publishedCardio)
    }

    @Test
    fun oldDistanceReceiptWaitsForNewRunToLogAndKeepsTheFirstReceipt() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val vm = readyVm()
        val first = stopFirstRunAndDismissDistance(vm)
        vm.startCardio()
        runCurrent()
        val second = requireNotNull(vm.uiState.value.cardio?.run)
        val deadline = alarm.deadline

        finishOldDistance(vm, first)

        assertNull(vm.sessionReceipt.value)
        assertFalse(vm.dismissSessionReceipt())
        assertEquals(second.runId, vm.uiState.value.cardio?.run?.runId)
        assertEquals(CardioPhase.EXECUTING, vm.uiState.value.cardio?.phase)
        assertTrue(vm.uiState.value.executionBusy)
        assertTrue(vm.executionActive)
        assertTrue(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(deadline, alarm.deadline)
        assertEquals("A", vm.uiState.value.viewDayId)
        assertEquals(390, repo.cardioSetsFor(requireNotNull(first.sessionId)).single().metres)

        tick(10_000)

        assertNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.uiState.value.executionBusy)
        assertFalse(vm.executionActive)
        assertEquals(2, repo.cardioSessionsFlow.first().size)
        assertEquals(first.sessionId, vm.sessionReceipt.value?.sessionId)
        assertEquals(first.runId, vm.sessionReceipt.value?.cardio?.runId)
        assertTrue(vm.dismissSessionReceipt())
        runCurrent()
        assertNull(vm.sessionReceipt.value)
    }

    @Test
    fun heldReceiptAndNewRunSurviveSavedStateParcelUntilNewRunIsEnded() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        val first = stopFirstRunAndDismissDistance(vm)
        vm.startCardio()
        runCurrent()
        val secondId = vm.uiState.value.cardio?.run?.runId
        finishOldDistance(vm, first)
        vm.setCardioScreenLive(false)
        vm.viewModelScope.cancel()
        val parcel = Parcel.obtain()
        val restoredHandle = try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(CardioRun::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }

        val restored = readyVm(restoredHandle)

        assertNull(restored.sessionReceipt.value)
        assertEquals(secondId, restored.uiState.value.cardio?.run?.runId)
        assertTrue(restored.uiState.value.cardio!!.run!!.running)
        assertFalse(restored.dismissSessionReceipt())
        restored.endCardioBlock()
        runCurrent()
        assertEquals(first.sessionId, restored.sessionReceipt.value?.sessionId)
        assertEquals(2, repo.cardioSessionsFlow.first().size)
    }

    @Test
    fun heldReceiptWaitsBetweenSetsAndReleasesWhenNewRunIsStoppedAndLogged() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK), time(CardioRole.WORK))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.startCardio()
        runCurrent()
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        vm.closeSheet()
        runCurrent()
        val first = vm.uiState.value.cardio!!.distanceRuns.single()
        vm.startCardio()
        runCurrent()
        finishOldDistance(vm, first)
        tick(10_000)
        assertNull(vm.sessionReceipt.value)
        assertTrue(vm.uiState.value.executionBusy)
        assertFalse(vm.uiState.value.cardio!!.run!!.running)

        vm.startCardio()
        runCurrent()
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()

        assertEquals(first.sessionId, vm.sessionReceipt.value?.sessionId)
        assertNull(vm.uiState.value.cardio?.run)
    }

    @Test
    fun heldReceiptReleasesWhenAnUnstartedBlockIsEndedWithoutLogging() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val vm = readyVm()
        val first = stopFirstRunAndDismissDistance(vm)
        vm.addCardioSet()
        runCurrent()
        finishOldDistance(vm, first)
        assertNull(vm.sessionReceipt.value)
        assertTrue(vm.uiState.value.executionBusy)

        vm.endCardioBlock()
        runCurrent()

        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(first.sessionId, vm.sessionReceipt.value?.sessionId)
    }

    @Test
    fun heldReceiptWaitsForTimedRepCountToBeLogged() = test {
        val (vm, first) = receiptDueDuringTimedRep()
        tick(120_000)
        assertTrue(vm.uiState.value.timedRep!!.event.awaitingCount)
        assertNull(vm.sessionReceipt.value)
        assertFalse(vm.dismissSessionReceipt())

        vm.changeTimedRepDraft("12")
        vm.logTimedRepCount()
        runCurrent()

        assertNull(vm.uiState.value.timedRep)
        assertEquals(first.sessionId, vm.sessionReceipt.value?.sessionId)
        assertEquals(12, repo.logFlow("A").first().single().sets.first().reps)
    }

    @Test
    fun heldReceiptReleasesWhenTimedRepIsStopped() = test {
        val (vm, first) = receiptDueDuringTimedRep()

        vm.stopTimedRep()
        runCurrent()

        assertNull(vm.uiState.value.timedRep)
        assertEquals(first.sessionId, vm.sessionReceipt.value?.sessionId)
    }

    private suspend fun TestScope.receiptDueDuringTimedRep(): Pair<DayViewModel, CardioRun> {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val vm = readyVm()
        val first = stopFirstRunAndDismissDistance(vm)
        // An authored day can gain a timed-rep slot while its logged distance is deferred.
        repo.replaceProgram(Program(repo.programFlow.first().days.map { day ->
            if (day.id != "A") day else day.copy(exercises = listOf(ProgramExercise(
                "pushup", targetSets = 2, prescription = Prescription(PrescribedTarget.RepsInTime(120, 40)),
            )))
        }))
        runCurrent()
        val card = vm.uiState.value.exercises.single()
        vm.startTimedRep(card.programExerciseId, card.focusIndex)
        runCurrent()
        finishOldDistance(vm, first)
        assertNull(vm.sessionReceipt.value)
        assertTrue(vm.uiState.value.timedRepRunning)
        assertFalse(vm.dismissSessionReceipt())
        return vm to first
    }

    @Test
    fun editingIntervalsKeepsTheFrozenRunAndDistanceDraftAndNextRunUsesTheEdit() = test {
        val intervals = CardioBlockDraft.new(1, "A").startFrom(CardioTemplate.INTERVALS)
        val sets = intervals.sets.mapIndexed { index, set ->
            if (index == 1) set.copy(hasAim = true) else set
        }.map(CardioSetDraft::spec)
        prepareAuthoredCardioDay(*sets.toTypedArray())
        val vm = readyVm()
        backgroundScope.launch { vm.dayEditState.collect {} }
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(300_000)
        vm.startCardio()
        runCurrent()
        tick(120_000)
        vm.openCardioDistance()
        vm.changeCardioDistance("123")
        vm.closeSheet()
        runCurrent()
        tick(1_000)
        val frozen = requireNotNull(vm.uiState.value.cardio?.run)
        assertEquals(2, frozen.position)
        assertTrue(frozen.running)
        assertEquals("123", frozen.drafts.single().text)
        val deadline = alarm.deadline

        vm.setDayEditorOpen(true)
        vm.openCardioBlock()
        runCurrent()
        val draft = requireNotNull(vm.dayEditState.value.cardioDraft)
        val edited = draft.copy(sets = draft.sets.mapIndexed { index, set ->
            if (index == 3) set.copy(seconds = 60) else set
        }, editingSet = 3)
        vm.changeCardioDraft(edited)
        vm.saveCardioBlock() // Set-page Save commits while keeping the block page open.
        runCurrent()
        assertEquals(edited.block(), repo.programFlow.first().days.first().cardioBlock)
        assertEquals(frozen, vm.uiState.value.cardio?.run)
        assertEquals(deadline, alarm.deadline)
        vm.saveCardioBlock() // Block-page Save must preserve the same execution too.
        runCurrent()
        vm.setDayEditorOpen(false)
        assertEquals(frozen, vm.uiState.value.cardio?.run)
        assertEquals(sets, vm.uiState.value.cardio?.block?.sets)

        tick(119_000)
        repeat(4) {
            vm.startCardio()
            runCurrent()
            tick(120_000)
            tick(120_000)
        }
        val session = repo.cardioSessionsFlow.first().single()
        val logged = repo.cardioSetsFor(session.id)
        assertEquals(sets, logged.map { it.spec })
        assertTrue(logged.all { it.status == CardioSetStatus.FINISHED })
        assertEquals(1_500, session.seconds)
        assertNull(vm.uiState.value.cardio?.run)
        val pending = vm.uiState.value.cardio!!.distanceRuns.single()
        assertEquals("123", pending.drafts.single().text)
        vm.requestCardioDistance(pending.action(1))
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertEquals(123, repo.cardioSetsFor(session.id)[1].metres)
        assertTrue(vm.dismissSessionReceipt())

        vm.selectDay("A")
        runCurrent()
        vm.startCardio()
        runCurrent()
        val next = requireNotNull(vm.uiState.value.cardio?.run)
        assertFalse(frozen.runId == next.runId)
        assertEquals(edited.block(), next.block)
    }

    @Test
    fun legacyHardFiveKWaitsForWorkAndLogsElevenSetsIncludingFinalRecovery() = test {
        prepare()
        val vm = readyVm()
        val block = requireNotNull(vm.uiState.value.cardio?.block)
        assertEquals(11, block.sets.size)
        tick(30_000)
        assertNull(vm.uiState.value.cardio?.run)
        vm.startCardio()
        runCurrent()
        val startedAt = clock.wall
        tick(300_000)
        assertEquals(1, vm.uiState.value.cardio?.run?.position)
        assertFalse(vm.uiState.value.cardio!!.run!!.running)
        repeat(5) { index ->
            tick(20_000) // Waiting never becomes elapsed work.
            vm.startCardio()
            runCurrent()
            tick(120_000)
            assertEquals(2 + index * 2, vm.uiState.value.cardio?.run?.position)
            assertTrue(vm.uiState.value.cardio!!.run!!.running)
            assertTrue(vm.uiState.value.executionKeepsScreenOn)
            tick(120_000)
        }
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(startedAt, session.startedAt)
        assertEquals(1_500, session.seconds)
        assertEquals(11, session.stepsCompleted)
        assertEquals("ROAD_RUN", session.activity)
        assertEquals("OUTDOOR_RUN", session.mode)
        assertTrue(session.hard)
        assertEquals(repo.selectedPlanIdFlow.first(), session.planId)
        assertEquals(block.sets, repo.cardioSetsFor(session.id).map { it.spec })
        assertTrue(repo.cardioSetsFor(session.id).all { it.status == CardioSetStatus.FINISHED })
        assertNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(11, alarm.buzzes)
        assertNull(alarm.identity)
    }

    @Test
    fun alarmAndTickerCannotBuzzTheSameBoundaryTwice() = test {
        prepareBlock(time(CardioRole.WORK), time(CardioRole.RECOVERY))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        clock.advance(10_000)
        alarm.fire()
        runCurrent()
        tick(1_000)
        assertEquals(1, alarm.buzzes)
        assertEquals(1, vm.uiState.value.cardio?.run?.position)
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
    }

    @Test
    fun distanceNeedsReachedTapEvenAfterTimeAimAndKeepsScreenOn() = test {
        prepareBlock(distance())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(127_000)
        assertTrue(vm.uiState.value.executionKeepsScreenOn)
        assertNull(alarm.deadline)
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.cardioSessionsFlow.first().single()
        val set = repo.cardioSetsFor(session.id).single()
        assertEquals(CardioSetStatus.FINISHED, set.status)
        assertEquals(127, set.elapsedSeconds)
        assertEquals(400, set.metres)
        assertEquals(DistanceSource.MANUAL, set.distanceSource)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(400, vm.uiState.value.cardio?.loggedSets?.single()?.metres)
    }

    @Test
    fun repeatedReachedDeliveryFinishesOnlyTheRenderedDistanceSet() = test {
        prepareBlock(distance(), CardioSetSpec(CardioRole.RECOVERY, Measure.Distance(100)), distance())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(105_000)
        val rendered = requireNotNull(vm.uiState.value.cardio?.run).action()
        vm.reachCardioTarget(rendered)
        vm.reachCardioTarget(rendered)
        runCurrent()
        val run = requireNotNull(vm.uiState.value.cardio?.run)
        assertEquals(1, run.position)
        assertEquals(400, run.results.single().metres)
        assertTrue(run.running)
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
    }

    @Test
    fun repeatedStopCannotShortenTheNextSetAfterItStarts() = test {
        prepareBlock(distance(), time(CardioRole.RECOVERY))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        val rendered = requireNotNull(vm.uiState.value.cardio?.run).action()
        vm.stopCardio(rendered)
        vm.startCardio()
        vm.stopCardio(rendered)
        runCurrent()
        assertEquals(1, vm.uiState.value.cardio?.run?.position)
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
        assertEquals(1, vm.uiState.value.cardio!!.run!!.drafts.size)
    }

    @Test
    fun repeatedSkipCannotSkipTheNextReadySet() = test {
        prepareBlock(distance(), distance(), distance())
        val vm = readyVm()
        val rendered = CardioSetAction("A", null, 0)
        vm.skipCardioSet(rendered)
        vm.skipCardioSet(rendered)
        runCurrent()
        assertEquals(1, vm.uiState.value.cardio?.run?.position)
        val nextRendered = requireNotNull(vm.uiState.value.cardio?.run).action()
        vm.skipCardioSet(nextRendered)
        vm.skipCardioSet(nextRendered)
        runCurrent()
        assertEquals(2, vm.uiState.value.cardio?.run?.position)
    }

    @Test
    fun staleRunIdentityCannotStopANewRunAtTheSamePosition() = test {
        prepareBlock(distance())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        val rendered = requireNotNull(vm.uiState.value.cardio?.run).action()
        tick(1_000)
        vm.reachCardioTarget(rendered)
        runCurrent()
        vm.startCardio()
        runCurrent()
        vm.stopCardio(rendered)
        runCurrent()
        assertEquals(0, vm.uiState.value.cardio?.run?.position)
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
    }

    @Test
    fun addSetOnAnotherDisplayedDayCannotChangeTheRunningBlock() = test {
        prepareBlock(distance(), time(CardioRole.RECOVERY))
        val day = repo.programFlow.first().days.single()
        repo.replaceProgram(Program(listOf(day, day.copy(id = "B", title = "Other run"))))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        val original = requireNotNull(handle.get<CardioRun>("day_cardio_run"))
        vm.selectDay("B")
        runCurrent()
        assertNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.uiState.value.cardio!!.canAddSet)
        vm.openCardioSets()
        vm.addCardioSet()
        runCurrent()
        assertEquals(original, handle.get<CardioRun>("day_cardio_run"))
        vm.selectDay("A")
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.canAddSet)
        assertEquals(original.block, vm.uiState.value.cardio?.block)
    }

    @Test
    fun dismissedFinalDistanceReleasesExecutionPublishesOnceAndLaterDoneAmends() = test {
        prepareBlock(time(CardioRole.WORK, aim = 400))
        val day = repo.programFlow.first().days.single()
        repo.replaceProgram(Program(listOf(day.copy(exercises = listOf(ProgramExercise(
            "pushup", targetSets = 2, prescription = Prescription(PrescribedTarget.RepsInTime(120, 40)),
        ))), day.copy(id = "B", title = "Other run"))))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        val session = repo.cardioSessionsFlow.first().single()
        // Publication happens at commit, even before the entry is dismissed.
        assertEquals(listOf(session.id), publishedCardio)
        vm.openCardioDistance()
        vm.changeCardioDistance("390")
        vm.closeSheet()
        runCurrent()
        assertNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.uiState.value.executionBusy)
        val entry = vm.uiState.value.cardio!!.distanceRuns.single().action(0)
        val card = vm.uiState.value.exercises.single()
        vm.startTimedRep(card.programExerciseId, card.focusIndex)
        runCurrent()
        assertTrue(vm.uiState.value.timedRepRunning)
        vm.stopTimedRep()
        runCurrent()
        vm.selectDay("B")
        runCurrent()
        vm.startCardio()
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
        vm.selectDay("A")
        runCurrent()
        vm.requestCardioDistance(entry)
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(entry)
        vm.saveCardioDistance(entry)
        runCurrent()
        assertEquals(390, repo.cardioSetsFor(session.id).single().metres)
        assertEquals(listOf(session.id), publishedCardio)
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.isEmpty())
        assertTrue(vm.uiState.value.executionBusy) // The other day's run still owns execution.
    }

    @Test
    fun staleDoneCannotSaveTheNextDistanceSheet() = test {
        prepareBlock(time(CardioRole.WORK, aim = 400), time(CardioRole.RECOVERY, aim = 100))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(20_000)
        val session = repo.cardioSessionsFlow.first().single()
        vm.openCardioDistance()
        vm.changeCardioDistance("390")
        runCurrent()
        val first = vm.distanceAction()
        vm.saveCardioDistance(first)
        runCurrent()
        vm.openCardioDistance()
        vm.changeCardioDistance("95")
        runCurrent()
        val second = vm.distanceAction()
        assertEquals(1, second.setIndex)
        vm.saveCardioDistance(first)
        runCurrent()
        assertEquals(390, repo.cardioSetsFor(session.id)[0].metres)
        assertNull(repo.cardioSetsFor(session.id)[1].metres)
        assertEquals(1, vm.uiState.value.openSheet?.setIndex)
        vm.saveCardioDistance(second)
        runCurrent()
        assertEquals(95, repo.cardioSetsFor(session.id)[1].metres)
        assertEquals(listOf(session.id), publishedCardio)
    }

    @Test
    fun staleDoneCannotSaveANewerRunsDraftAtTheSameSetIndex() = test {
        prepareBlock(time(CardioRole.WORK, aim = 400))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        val firstSession = repo.cardioSessionsFlow.first().single()
        vm.openCardioDistance()
        runCurrent()
        val first = vm.distanceAction()
        vm.closeSheet()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        val secondSession = repo.cardioSessionsFlow.first().first { it.id != firstSession.id }
        vm.openCardioDistance()
        vm.changeCardioDistance("395")
        runCurrent()
        val second = vm.distanceAction()
        assertEquals(first.setIndex, second.setIndex)
        assertFalse(first.runId == second.runId)
        vm.saveCardioDistance(first)
        runCurrent()
        assertNull(repo.cardioSetsFor(firstSession.id).single().metres)
        assertNull(repo.cardioSetsFor(secondSession.id).single().metres)
        assertEquals(second.runId, vm.uiState.value.openSheet?.cardioRunId)
        vm.saveCardioDistance(second)
        runCurrent()
        assertNull(repo.cardioSetsFor(firstSession.id).single().metres)
        assertEquals(395, repo.cardioSetsFor(secondSession.id).single().metres)
        assertEquals(2, publishedCardio.size)
    }

    @Test
    fun stoppingDistanceLogsShortenedAndAmendsOptionalDistance() = test {
        prepareBlock(distance())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(23_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(CardioSetStatus.SHORTENED, repo.cardioSetsFor(session.id).single().status)
        assertNull(repo.cardioSetsFor(session.id).single().metres)
        vm.openCardioDistance()
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.single().drafts.single().stopped)
        vm.changeCardioDistance("380")
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertEquals(380, repo.cardioSetsFor(session.id).single().metres)
        assertEquals(DistanceSource.MANUAL, repo.cardioSetsFor(session.id).single().distanceSource)
        assertEquals(23, session.seconds)
        assertEquals(1, repo.cardioSessionsFlow.first().size)
    }

    @Test
    fun blankStoppedDistanceStaysUnknownAndIntentionalShortWorkCounts() = test {
        prepareBlock(distance())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(1_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(1, session.seconds)
        assertNull(repo.cardioSetsFor(session.id).single().metres)
        assertNull(repo.cardioSetsFor(session.id).single().distanceSource)
    }

    @Test
    fun timeDistanceEntryQueuesOnceAndRecoveryRunsBehindDismissedDraft() = test {
        prepareBlock(time(CardioRole.WORK, aim = 400), time(CardioRole.RECOVERY, 120))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(10_000)
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
        assertEquals(CardioRole.RECOVERY, vm.uiState.value.cardio!!.block!!.sets[1].role)
        vm.openCardioDistance()
        runCurrent()
        assertEquals(OpenSheet.Kind.CARDIO_DISTANCE, vm.uiState.value.openSheet?.kind)
        assertEquals("", vm.uiState.value.cardio!!.run!!.drafts.single().text)
        vm.changeCardioDistance("380")
        vm.closeSheet()
        vm.openCardioDistance()
        runCurrent()
        assertNull(vm.uiState.value.openSheet)
        tick(1_000)
        assertTrue(vm.uiState.value.executionKeepsScreenOn)
        vm.requestCardioDistance(vm.uiState.value.cardio!!.distanceRuns.first().action(0))
        vm.openCardioDistance()
        vm.changeCardioDistance("")
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        tick(119_000)
        val session = repo.cardioSessionsFlow.first().single()
        assertNull(repo.cardioSetsFor(session.id).first().metres)
        assertEquals(130, session.seconds)
    }

    @Test
    fun dismissedEarlierDraftSurvivesBlockLoggingAndCanAmendItsOwnSet() = test {
        prepareBlock(time(CardioRole.WORK, aim = 400), time(CardioRole.RECOVERY))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.openCardioDistance()
        vm.changeCardioDistance("380")
        vm.closeSheet()
        tick(10_000)
        val session = repo.cardioSessionsFlow.first().single()
        assertNull(vm.uiState.value.cardio!!.run)
        assertFalse(vm.uiState.value.executionBusy)
        assertEquals("380", vm.uiState.value.cardio!!.distanceRuns.single().drafts.single().text)
        assertNull(repo.cardioSetsFor(session.id).first().metres)
        vm.requestCardioDistance(vm.uiState.value.cardio!!.distanceRuns.first().action(0))
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertEquals(380, repo.cardioSetsFor(session.id).first().metres)
        assertNull(vm.uiState.value.cardio?.run)
    }

    @Test
    fun skipBeforeStartAndEndShortenActiveAndSkipUntouchedSets() = test {
        prepareBlock(time(CardioRole.WARMUP), distance(), time(CardioRole.RECOVERY), time(CardioRole.WORK))
        val vm = readyVm()
        vm.skipCardioSet(vm.uiState.value.cardio?.run?.action() ?: CardioSetAction("A", null, 0))
        runCurrent()
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
        vm.startCardio()
        runCurrent()
        vm.skipCardioSet(vm.uiState.value.cardio?.run?.action() ?: CardioSetAction("A", null, 0)) // A running set cannot be skipped.
        runCurrent()
        tick(4_000)
        vm.endCardioBlock()
        vm.endCardioBlock()
        runCurrent()
        val session = repo.cardioSessionsFlow.first().single()
        val sets = repo.cardioSetsFor(session.id)
        assertEquals(listOf(CardioSetStatus.SKIPPED, CardioSetStatus.SHORTENED,
            CardioSetStatus.SKIPPED, CardioSetStatus.SKIPPED), sets.map { it.status })
        assertEquals(listOf(0, 4, 0, 0), sets.map { it.elapsedSeconds })
        assertTrue(sets.all { it.metres == null })
    }

    @Test
    fun entirelyUnstartedBlockDoesNotLogOrAdvance() = test {
        prepareBlock(time(CardioRole.WORK), time(CardioRole.COOLDOWN))
        val vm = readyVm()
        val suggested = repo.suggestedDayFlow.first()
        vm.endCardioBlock()
        runCurrent()
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
        assertEquals(suggested, repo.suggestedDayFlow.first())
        assertNull(vm.uiState.value.cardio?.run)
    }

    @Test
    fun processDeathRestoresMonotonicAnchorsAndIgnoresWallJump() = test {
        prepareBlock(distance())
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        tick(40_000)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        clock.wall += 3_600_000
        val restored = readyVm(recreateHandle(handle))
        assertEquals(40, restored.uiState.value.cardio?.elapsedSeconds)
        assertTrue(restored.uiState.value.executionKeepsScreenOn)
        assertEquals(0, alarm.buzzes)
    }

    @Test
    fun changedBootUsesWallTimeAndExpiredRestoreStopsAtUnstartedWorkSilently() = test {
        prepareBlock(time(CardioRole.WARMUP), time(CardioRole.RECOVERY), time(CardioRole.WORK))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        clock.wall += 45_000
        clock.elapsed += 300_000_000
        clock.boot++
        val restored = readyVm(recreateHandle(handle))
        assertEquals(2, restored.uiState.value.cardio?.run?.position)
        assertFalse(restored.uiState.value.cardio!!.run!!.running)
        assertEquals(listOf(10, 10), restored.uiState.value.cardio!!.run!!.results.map { it.seconds })
        assertEquals(0, alarm.buzzes)
        assertNull(alarm.deadline)
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
    }

    @Test
    fun expiredFinalRestoreLogsSilentlyWithoutOverrunOrDuplicate() = test {
        prepareBlock(time(CardioRole.WORK))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        clock.advance(60_000)
        val stale = recreateHandle(handle)
        val restored = readyVm(recreateHandle(stale))
        assertEquals(10, repo.cardioSessionsFlow.first().single().seconds)
        assertNull(restored.uiState.value.cardio?.run)
        assertEquals(0, alarm.buzzes)
        restored.viewModelScope.cancel()
        readyVm(recreateHandle(stale))
        assertEquals(1, repo.cardioSessionsFlow.first().size)
    }

    @Test
    fun missingRunDayDiscardsInsteadOfRetargeting() = test {
        prepareBlock(distance())
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val day = repo.programFlow.first().days.single()
        repo.replaceProgram(Program(listOf(day.copy(id = "B"))))
        val restored = readyVm(recreateHandle(handle))
        assertNull(restored.uiState.value.cardio?.run)
        assertNull(alarm.deadline)
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
    }

    @Test
    fun differentPlanDiscardsRunEvenWhenDayAndBlockMatch() = test {
        prepareAuthoredCardioDay(distance())
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
        val program = repo.programFlow.first()
        val other = repo.createAuthoredPlan("Other plan", "Running")
        repo.publishCreatedAuthoredPlan(other)
        repo.replaceProgram(program)
        runCurrent()
        assertNull(handle.get<CardioRun>("day_cardio_run"))
        assertNull(vm.uiState.value.cardio?.run)
        assertNull(alarm.deadline)
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
    }

    @Test
    fun removedSourceBlockLeavesFrozenRunAccessibleUntilDistanceIsSaved() = test {
        val plan = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("plank"))
        assertTrue(repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN,
            listOf(time(CardioRole.WORK, aim = 400)))))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        val frozen = requireNotNull(vm.uiState.value.cardio?.run)
        assertTrue(repo.setCardioBlock(plan, "A", null))
        runCurrent()
        assertEquals(frozen, vm.uiState.value.cardio?.run)
        tick(10_000)
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(frozen.block.sets, repo.cardioSetsFor(session.id).map { it.spec })
        assertFalse(vm.uiState.value.executionBusy)
        assertNull(vm.uiState.value.cardio?.run)
        assertEquals(frozen.block, vm.uiState.value.cardio?.block)
        val pending = requireNotNull(vm.uiState.value.cardio).distanceRuns.single()
        assertEquals(frozen.runId, pending.runId)
        assertFalse(pending.drafts.single().saved) // The card offers "Enter distance".
        vm.openCardioDistance()
        runCurrent()
        vm.closeSheet()
        runCurrent()
        vm.requestCardioDistance(pending.action(0))
        vm.openCardioDistance()
        runCurrent()
        assertEquals(pending.action(0), vm.distanceAction())
        vm.changeCardioDistance("390")
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertEquals(390, repo.cardioSetsFor(session.id).single().metres)
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertNull(vm.uiState.value.cardio)
    }

    @Test
    fun finalDistanceEntryAmendsBeforeCardioOnlyReceiptAndPlanAdvancesOnce() = test {
        val planId = repo.createAuthoredPlan("Running plan", "Running")
        repo.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(time(CardioRole.WORK, aim = 400)))
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Running", "", emptyList(), null, cardioBlock = block),
            ProgramDay("B", "Next run", "", emptyList(), null, cardioBlock = block),
            ProgramDay("C", "Third run", "", emptyList(), null, cardioBlock = block),
        )))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(10_000)
        assertEquals("B", repo.suggestedDayFlow.first())
        assertNull(vm.sessionReceipt.value)
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        vm.openCardioDistance()
        vm.changeCardioDistance("401")
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        val session = repo.cardioSessionsFlow.first().single()
        assertEquals(planId, session.planId)
        assertEquals("Running plan", session.planName)
        assertEquals(401, repo.cardioSetsFor(session.id).single().metres)
        assertEquals(401, vm.sessionReceipt.value?.cardio?.resultSets?.single()?.metres)
        vm.retryCardioSave()
        runCurrent()
        assertEquals("B", repo.suggestedDayFlow.first())
        assertEquals(1, repo.cardioSessionsFlow.first().size)
    }

    @Test
    fun finalSheetDraftSurvivesProcessDeathAfterLogAndBlankEntryStillFinishesDay() = test {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(time(CardioRole.WORK, aim = 400)))
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        tick(10_000)
        first.openCardioDistance()
        first.changeCardioDistance("123")
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val restored = readyVm(recreateHandle(handle))
        assertEquals("A", restored.uiState.value.viewDayId)
        assertEquals("123", restored.uiState.value.cardio?.distanceRuns?.single()?.drafts?.single()?.text)
        assertNull(restored.uiState.value.cardio?.run)
        assertFalse(restored.uiState.value.executionBusy)
        assertEquals(1, publishedCardio.size)
        assertEquals(OpenSheet.Kind.CARDIO_DISTANCE, restored.uiState.value.openSheet?.kind)
        restored.changeCardioDistance("")
        restored.saveCardioDistance(restored.distanceAction())
        runCurrent()
        assertNull(repo.cardioSetsFor(repo.cardioSessionsFlow.first().single().id).single().metres)
        assertEquals("B", repo.suggestedDayFlow.first())
        assertEquals("A", restored.sessionReceipt.value?.cardio?.dayId)
        assertEquals(1, alarm.buzzes)
    }

    @Test
    fun generatedCardioDayFinishesOutsideStrengthRotation() = test {
        prepare()
        val source = repo.programFlow.first().days.single()
        repo.replaceProgram(Program(listOf(source, source.copy(id = "B"),
            ProgramDay("C", "Running", "", emptyList(), CardioSuggestion("Easy", "", false), ProgramDayKind.CARDIO))))
        val vm = readyVm()
        vm.selectDay("C")
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(1_500_000)
        assertEquals("A", repo.suggestedDayFlow.first())
        assertEquals("C", vm.sessionReceipt.value?.cardio?.dayId)
        assertEquals(1, repo.cardioSessionsFlow.first().size)
    }

    @Test
    fun addWorkAndRecoveryBeforeReadyCooldownAndFirstRecoveryAlwaysWaits() = test {
        prepareBlock(time(CardioRole.RECOVERY), time(CardioRole.WORK), time(CardioRole.RECOVERY), time(CardioRole.COOLDOWN))
        val vm = readyVm()
        vm.addCardioSet()
        runCurrent()
        assertFalse(vm.uiState.value.cardio!!.run!!.running)
        assertEquals(listOf(CardioRole.RECOVERY, CardioRole.WORK, CardioRole.RECOVERY,
            CardioRole.WORK, CardioRole.RECOVERY, CardioRole.COOLDOWN), vm.uiState.value.cardio!!.block!!.sets.map { it.role })
        assertNull(alarm.deadline)
    }

    @Test
    fun failureRetainsFrozenResultsAndRetryCommitsOnlyOnce() = test {
        prepareBlock(time(CardioRole.WORK))
        val vm = readyVm()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        vm.startCardio()
        runCurrent()
        tick(10_000)
        assertTrue(vm.uiState.value.cardio!!.run!!.saveFailed)
        assertTrue(repo.cardioSessionsFlow.first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        vm.retryCardioSave()
        vm.retryCardioSave()
        runCurrent()
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(10, repo.cardioSessionsFlow.first().single().seconds)
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
        var identity: String? = null
        var callback: (() -> Unit)? = null
        var buzzes = 0
        override fun buzz() { buzzes++ }
        fun fire() {
            val boundary = callback ?: return
            cancel()
            buzz()
            boundary()
        }
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
            this.deadline = deadlineElapsedMillis
            this.identity = identity
            callback = onBoundary
        }
        override fun cancel() { deadline = null; identity = null; callback = null }
    }
}
