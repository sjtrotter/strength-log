package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.cardio.CardioNextTime
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import kotlinx.coroutines.flow.map
import android.content.Context
import android.os.Parcel
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
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.day.receipt.CardioRecordMark
import cloud.trotter.log.strength.ui.day.receipt.CardioInterval
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    @Test fun cardioEditorPersistsProgressionAndParksWorkingValues() = test {
        prepareAuthoredCardioDay(CardioSetSpec(CardioRole.WORK, Measure.Distance(1600), Measure.Time(480)))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = readyVm()
        backgroundScope.launch { vm.dayEditState.collect {} }
        runCurrent()
        assertEquals(setOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE), vm.dayEditState.value.cardioMeasures)
        vm.editor.setCardioProgressBy(ProgressMeasure.PACE)
        runCurrent()
        assertEquals(ProgressMeasure.PACE, vm.dayEditState.value.cardioProgress.measure)
        assertEquals(1, vm.dayEditState.value.cardioProgress.increasePercent)
        vm.editor.setCardioIncrease(7)
        runCurrent()
        assertEquals(7, vm.dayEditState.value.cardioProgress.increasePercent)
        val working = CardioBlock(CardioActivity.TRACK_RUN,
            listOf(CardioSetSpec(CardioRole.WORK, Measure.Distance(1600), Measure.Time(475))))
        repo.setCardioWorking(plan, "A", working)
        runCurrent()
        vm.editor.setCardioKeepToPlan(true)
        runCurrent()
        assertTrue(vm.dayEditState.value.cardioProgress.keepToPlan)
        assertEquals(working, vm.dayEditState.value.cardioProgress.working)
        assertEquals(Measure.Time(480), vm.uiState.value.cardio?.block?.sets?.single()?.aim)
        vm.editor.setCardioKeepToPlan(false)
        runCurrent()
        assertEquals(working, vm.uiState.value.cardio?.block)
        vm.editor.setCardioProgressBy(ProgressMeasure.DISTANCE)
        runCurrent()
        assertEquals(5, vm.dayEditState.value.cardioProgress.increasePercent)
        assertEquals("DISTANCE", db.programDao().day(plan, "A")?.cardioProgressBy)
    }

    @Test fun cardioOfferAppliesBeforeStartAndTheRunFreezesItsWorkingBlock() = test {
        val spec = time(CardioRole.WORK, 1200)
        prepareAuthoredCardioDay(spec)
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.plans.setOfferCardioIncreases(plan, true)
        repo.logCardioSession(CardioSessionEntity(planId = plan, dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = clock.wall - 100_000_000, completedAt = clock.wall - 90_000_000,
            seconds = 1200, stepsCompleted = 1, activity = CardioActivity.TRACK_RUN.name),
            listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 1200)))
        val vm = readyVm()
        val offer = vm.uiState.value.cardio?.nextTime as CardioNextTime.Offer
        vm.applyCardioNextTime(offer.offered)
        runCurrent()
        assertTrue((vm.uiState.value.cardio?.nextTime as CardioNextTime.Offer).applied)
        vm.startCardio()
        runCurrent()
        assertNull(vm.uiState.value.cardio?.nextTime)
        val run = vm.uiState.value.cardio?.run!!
        assertEquals(offer.offered, CardioBlockJson.decode(run.sourceBlockJson))
        assertEquals(Measure.Time(1260), run.block.sets.single().end)
        vm.applyCardioNextTime(offer.opening)
        runCurrent()
        assertEquals(offer.offered, vm.uiState.value.cardio?.run?.block)
    }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
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
        repo, publisher, share, handle, clock, alarm, PhoneRestController(settings, NoOpRestRuntime),
        settings = settings,
        plans = repo.plans,
        catalog = repo.catalog,
        historyRepository = repo.history,
    ).also { vms += it }

    private suspend fun prepare(fiveK: Boolean = true) {
        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first().copy(cardio = CardioPrefs(CardioMode.OUTDOOR_RUN, fiveKGoal = fiveK)))
        repo.replaceProgram(
            Program(listOf(ProgramDay(
                "A", "Intervals", "", listOf(ProgramExercise("bb_back_squat")),
                cardioBlock = CardioBlocks.fromLegacy(CardioSuggestion("Hard cardio — intervals", "Run hard, recover easy.", hard = true), fiveK, CardioActivity.ROAD_RUN),
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
            listOf(ProgramExercise("bb_back_squat")),
            cardioBlock = CardioBlock(CardioActivity.TRACK_RUN, sets.toList())))))
    }

    private fun time(role: CardioRole, seconds: Int = 10, aim: Int? = null) =
        CardioSetSpec(role, Measure.Time(seconds), aim?.let { Measure.Distance(it) }, hard = role == CardioRole.WORK)

    private fun distance() = CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true)

    private suspend fun TestScope.assertTypedRunDistance(unit: DistanceUnit, activity: CardioActivity, text: String, metres: Int) {
        settings.setDistanceUnit(unit)
        prepareAuthoredCardioDay(time(CardioRole.WORK, 1500), activity = activity)
        val vm = readyVm()
        assertEquals(unit, vm.uiState.value.cardio?.distanceUnit)
        vm.startCardio()
        runCurrent()
        tick(60_000)
        vm.changeCardioRunDistance(text)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(metres, repo.history.cardioSetsFor(session.id).single().metres)
    }

    @Test fun milesOnRoadLogWholeMetres() = test {
        assertTypedRunDistance(DistanceUnit.MI, CardioActivity.ROAD_RUN, "3.1", 4989)
    }

    private suspend fun TestScope.restoreLegacyRunDistanceAndStop(
        meters: Int,
        activity: CardioActivity = CardioActivity.ROAD_RUN,
    ) {
        prepareAuthoredCardioDay(time(CardioRole.WORK), activity = activity)
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        tick(60_000)
        val run = requireNotNull(handle.get<CardioRun>("day_cardio_run"))
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        handle["day_cardio_run"] = MainCardioRunParcel(run)
        handle["day_cardio_run_distance"] = CardioRunDistance(run.runId, run.position, meters.toString())

        val restored = readyVm(parcelHandle(handle))
        assertEquals(DistanceUnit.MI, restored.uiState.value.cardio?.distanceUnit)
        assertEquals(DistanceUnit.MI.inputText(meters), restored.uiState.value.cardio?.runDistanceText)
        restored.stopCardio(requireNotNull(restored.uiState.value.cardio?.run).action())
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(meters, repo.history.cardioSetsFor(session.id).single().metres)
        assertTrue(restored.uiState.value.cardio!!.distanceRuns.isEmpty())
    }

    @Test fun legacyHundredMeterRunDraftStopsWithOriginalDistance() = test {
        restoreLegacyRunDistanceAndStop(100)
    }

    @Test fun legacyFiveThousandMeterRunDraftStopsWithOriginalDistance() = test {
        restoreLegacyRunDistanceAndStop(5000)
    }

    @Test fun legacyRideAboveEntryLimitStopsWithOriginalDistance() = test {
        restoreLegacyRunDistanceAndStop(250_000, CardioActivity.OUTDOOR_RIDE)
    }

    private suspend fun TestScope.restoreLegacyPendingDistanceAndSave(meters: Int) {
        prepareAuthoredCardioDay(time(CardioRole.WORK), activity = CardioActivity.ROAD_RUN)
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        tick(60_000)
        first.stopCardio(requireNotNull(first.uiState.value.cardio?.run).action())
        runCurrent()
        val pending = requireNotNull(handle.get<ArrayList<CardioRun>>("day_cardio_distances")).single()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val legacy = pending.copy(drafts = pending.drafts.map { it.copy(text = meters.toString()) })
        handle["day_cardio_distances"] = arrayListOf(MainCardioRunParcel(legacy))

        val restored = readyVm(parcelHandle(handle))
        restored.openCardioDistance()
        runCurrent()
        restored.saveCardioDistance(restored.distanceAction())
        runCurrent()

        assertEquals(meters, repo.history.cardioSetsFor(requireNotNull(pending.sessionId)).single().metres)
        assertTrue(restored.uiState.value.cardio!!.distanceRuns.isEmpty())
    }

    @Test fun legacyHundredMeterPendingDraftSavesOriginalDistance() = test {
        restoreLegacyPendingDistanceAndSave(100)
    }

    @Test fun legacyFiveThousandMeterPendingDraftSavesOriginalDistance() = test {
        restoreLegacyPendingDistanceAndSave(5000)
    }

    @Test fun legacyPendingDraftAboveEntryLimitSavesOriginalDistance() = test {
        restoreLegacyPendingDistanceAndSave(250_000)
    }

    @Test fun displayInputRestoresPartialTextWhileExistingKeyKeepsMeters() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK), activity = CardioActivity.ROAD_RUN)
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        tick(60_000)
        first.changeCardioRunDistance("3.")
        runCurrent()
        assertEquals("4828", handle.get<CardioRunDistance>("day_cardio_run_distance")?.text)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()

        val restoredHandle = parcelHandle(handle)
        val restored = readyVm(restoredHandle)
        assertEquals("3.", restored.uiState.value.cardio?.runDistanceText)
        restored.changeCardioRunDistance("3.1")
        assertEquals("4989", restoredHandle.get<CardioRunDistance>("day_cardio_run_distance")?.text)
        restored.stopCardio(requireNotNull(restored.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(4989, repo.history.cardioSetsFor(session.id).single().metres)
        assertTrue(restoredHandle.get<ArrayList<CardioDistanceInput>>("day_cardio_distance_inputs").orEmpty().isEmpty())
    }

    @Test fun kilometersOnRoadLogWholeMetres() = test {
        assertTypedRunDistance(DistanceUnit.KM, CardioActivity.ROAD_RUN, "5", 5000)
    }

    @Test fun trackEntryKeepsMetresUnderMiles() = test {
        assertTypedRunDistance(DistanceUnit.MI, CardioActivity.TRACK_RUN, "400", 400)
    }

    @Test fun runDistanceRejectsExtraDecimalPlaces() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK), activity = CardioActivity.ROAD_RUN)
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        vm.changeCardioRunDistance("3.1")
        vm.changeCardioRunDistance("3.123")
        runCurrent()
        assertEquals("3.1", vm.uiState.value.cardio?.runDistanceText)
        vm.changeCardioRunDistance("")
        runCurrent()
        assertEquals("", vm.uiState.value.cardio?.runDistanceText)
    }

    @Test fun trackRejectsFractionalEntry() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        vm.changeCardioRunDistance("400")
        vm.changeCardioRunDistance("400.1")
        runCurrent()
        assertEquals("400", vm.uiState.value.cardio?.runDistanceText)
    }

    @Test fun endingRoadRunConvertsMiles() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK), activity = CardioActivity.ROAD_RUN)
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(60_000)
        vm.changeCardioRunDistance("3.1")
        vm.endCardioBlock()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(4989, repo.history.cardioSetsFor(session.id).single().metres)
    }

    @Test fun distanceSheetConvertsMilesAndRejectsExtraDecimals() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK), activity = CardioActivity.ROAD_RUN)
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(60_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        vm.changeCardioDistance("3.1")
        vm.changeCardioDistance("3.123")
        runCurrent()
        assertEquals("4989", vm.uiState.value.cardio?.distanceRuns?.single()?.drafts?.single()?.text)
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(4989, repo.history.cardioSetsFor(session.id).single().metres)
    }

    @Test fun distanceAmendmentRetryKeepsSavedMetresAfterSettingChanges() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK), activity = CardioActivity.ROAD_RUN)
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(60_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        vm.openCardioDistance()
        runCurrent()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_distance BEFORE UPDATE ON cardio_set_result BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        vm.changeCardioDistance("3.1")
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.single().saveFailed)
        settings.setDistanceUnit(DistanceUnit.KM)
        runCurrent()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_distance")
        vm.retryCardioSave()
        runCurrent()
        assertEquals(4989, repo.history.cardioSetsFor(session.id).single().metres)
    }

    @Test
    fun runningLoneTimedSetRefusesAddSetAndKeepsElapsedAndTypedDistance() = test {
        settings.setDistanceUnit(DistanceUnit.KM)
        prepareAuthoredCardioDay(time(CardioRole.WORK, 1500), activity = CardioActivity.ROAD_RUN)
        val vm = readyVm()
        assertTrue(vm.uiState.value.cardio!!.canAddSet)
        vm.startCardio()
        runCurrent()
        val original = requireNotNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.uiState.value.cardio!!.canAddSet)
        vm.addCardioSet()
        runCurrent()
        assertEquals(original, vm.uiState.value.cardio?.run)
        tick(1_690_000)
        vm.changeCardioRunDistance("5")
        runCurrent()

        vm.openCardioSets()
        vm.addCardioSet()
        runCurrent()

        val cardio = requireNotNull(vm.uiState.value.cardio)
        assertFalse(cardio.canAddSet)
        assertEquals(original, cardio.run)
        assertEquals(1690, cardio.elapsedSeconds)
        assertEquals("5", cardio.runDistanceText)
        assertNull(alarm.deadline)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        vm.closeSheet()
        vm.stopCardio(original.action())
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        val result = repo.history.cardioSetsFor(session.id).single()
        assertEquals(1690, session.seconds)
        assertEquals(CardioSetStatus.FINISHED, result.status)
        assertEquals(1690, result.elapsedSeconds)
        assertEquals(5000, result.metres)
        assertEquals(original.block.sets.single(), result.spec)
    }

    @Test
    fun loneTimedRunCountsUpAndStopLogsFinishedWithTheTypedDistance() = test {
        settings.setDistanceUnit(DistanceUnit.KM)
        prepareAuthoredCardioDay(time(CardioRole.WORK, 1500), activity = CardioActivity.ROAD_RUN)
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(1_690_000)

        val cardio = requireNotNull(vm.uiState.value.cardio)
        assertTrue(requireNotNull(cardio.run).running)
        assertEquals(1690, cardio.elapsedSeconds)
        assertNull(alarm.deadline)
        assertEquals(0, alarm.buzzes)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        vm.changeCardioRunDistance("5")
        runCurrent()
        assertEquals("5", vm.uiState.value.cardio?.runDistanceText)
        vm.stopCardio(requireNotNull(cardio.run).action())
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        val result = repo.history.cardioSetsFor(session.id).single()
        assertEquals(1690, session.seconds)
        assertEquals(CardioSetStatus.FINISHED, result.status)
        assertEquals(1690, result.elapsedSeconds)
        assertEquals(5000, result.metres)
        assertEquals(DistanceSource.MANUAL, result.distanceSource)
        assertNull(vm.uiState.value.openSheet)
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.isEmpty())
        assertEquals(CardioInterval(CardioSetStatus.FINISHED, 1690, 5000),
            requireNotNull(vm.finisher.receipt.value).cardioRows.single().intervals.single())
        assertEquals(listOf(session.id), publishedCardio)
        assertNull(handle.get<CardioRunDistance>("day_cardio_run_distance"))
    }

    @Test
    fun loneTimedRunStoppedBlankOpensTheDistanceSheetAndStaysFinished() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK, 1500), activity = CardioActivity.ROAD_RUN)
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(600_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        assertEquals(OpenSheet.Kind.CARDIO_DISTANCE, vm.uiState.value.openSheet?.kind)
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        val result = repo.history.cardioSetsFor(session.id).single()
        assertEquals(CardioSetStatus.FINISHED, result.status)
        assertEquals(600, result.elapsedSeconds)
        assertNull(result.metres)
        assertNotNull(vm.finisher.receipt.value)
    }

    @Test
    fun runDistanceDraftSurvivesProcessDeathWithTheRun() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK, 10))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        first.changeCardioRunDistance("3000")
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        clock.advance(60_000)
        val restored = readyVm(recreateHandle(handle))
        assertEquals("3000", restored.uiState.value.cardio?.runDistanceText)
        assertTrue(requireNotNull(restored.uiState.value.cardio?.run).running)
        assertEquals(60, restored.uiState.value.cardio?.elapsedSeconds)
        tick(60_000)
        assertTrue(requireNotNull(restored.uiState.value.cardio?.run).running)
        assertEquals(120, restored.uiState.value.cardio?.elapsedSeconds)
        restored.stopCardio(requireNotNull(restored.uiState.value.cardio?.run).action())
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(3000, repo.history.cardioSetsFor(session.id).single().metres)
    }

    @Test
    fun endCardioOnALoneRunLogsFinishedWithTheTypedDistance() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK, 1500))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(600_000)
        vm.changeCardioRunDistance("4200")
        vm.endCardioBlock()
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        val result = repo.history.cardioSetsFor(session.id).single()
        assertEquals(CardioSetStatus.FINISHED, result.status)
        assertEquals(600, result.elapsedSeconds)
        assertEquals(4200, result.metres)
        assertNull(vm.uiState.value.openSheet)
        assertNotNull(vm.finisher.receipt.value)
        assertNull(handle.get<CardioRunDistance>("day_cardio_run_distance"))
    }

    @Test
    fun aTypedDistanceIsIgnoredOnceTheRunMovesOn() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(4_000)
        vm.changeCardioRunDistance("900")
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(900, repo.history.cardioSetsFor(session.id).single().metres)
        assertTrue(vm.finisher.dismissReceipt())
        runCurrent()
        vm.startCardio()
        runCurrent()
        assertTrue(requireNotNull(vm.uiState.value.cardio?.run).running)
        assertEquals("", vm.uiState.value.cardio?.runDistanceText)
    }

    @Test
    fun intervalWorkStillStopsAsShortened() = test {
        prepareBlock(time(CardioRole.WORK), time(CardioRole.RECOVERY))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(4_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val result = requireNotNull(vm.uiState.value.cardio?.run).results.single()
        assertEquals("SHORTENED", result.status)
        assertEquals(4, result.seconds)
    }

    @Test
    fun loggedCardioOnAMixedDayEnablesFinishAndTheReceiptListsTheRun() = test {
        prepareBlock(time(CardioRole.WORK), time(CardioRole.COOLDOWN))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "A")
        val vm = readyVm()
        assertFalse(vm.uiState.value.canFinish)
        vm.startCardio()
        runCurrent()
        tick(10_000)
        tick(10_000)

        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertNull(vm.finisher.receipt.value)
        assertTrue(vm.uiState.value.canFinish)
        vm.completeDay()
        runCurrent()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertFalse(receipt.cardio)
        assertEquals(CardioActivity.TRACK_RUN, receipt.activity)
        assertEquals(listOf(CardioRole.WORK, CardioRole.COOLDOWN), receipt.cardioRows.map { it.role })
        assertEquals(CardioInterval(CardioSetStatus.FINISHED, 10, null), receipt.cardioRows.first().intervals.single())
        assertEquals(ReceiptRow.Shape.SKIPPED, receipt.groups.single().rows.single().shape)
        assertEquals(1, repo.history.sessionsFlow.first().size)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertEquals(1, publishedCardio.size)
        assertTrue(vm.finisher.dismissReceipt())
    }

    @Test
    fun mixedReceiptKeepsLiveLiftCountsAndFinishPrescriptionAlongsideCardio() = test {
        prepareBlock(time(CardioRole.WORK), time(CardioRole.COOLDOWN))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slotId = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        repo.setPrescription(slotId, Prescription(PrescribedTarget.LoadReps(40.0, 5)), sets = 3)
        repo.startRun(plan, "A")
        val vm = readyVm()
        vm.toggleDone(slotId, 0, true, Slot.MAIN)
        runCurrent()
        repo.setPrescription(slotId, Prescription(PrescribedTarget.LoadReps(30.0, 8)), sets = 2)
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        tick(10_000)

        assertEquals(1, vm.uiState.value.doneSets)
        assertEquals(2, vm.uiState.value.totalSets)
        vm.completeDay()
        runCurrent()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        val counts = requireNotNull(vm.finisher.receiptSetCounts.value)
        assertEquals(receipt.sessionId, counts.sessionId)
        assertEquals(2, counts.total)
        assertEquals(1, counts.remaining)
        val row = receipt.groups.single().rows.single()
        assertEquals(40.0, row.sets.single().weightLb, 0.0)
        val planned = requireNotNull(row.planned)
        assertEquals(2, planned.count)
        assertEquals(30.0, planned.weightLb, 0.0)
        assertEquals(8, planned.reps)
        assertEquals(listOf(CardioRole.WORK, CardioRole.COOLDOWN), receipt.cardioRows.map { it.role })
        assertEquals(CardioActivity.TRACK_RUN, receipt.activity)
        assertEquals(1, repo.history.sessionSummariesFlow.first().single().setCount)
    }

    @Test
    fun finishingMidIntervalEndsTheRunLogsRiddenRoundsAndLetsTheReceiptClose() = test {
        prepareBlock(time(CardioRole.WARMUP), time(CardioRole.WORK), time(CardioRole.RECOVERY),
            time(CardioRole.WORK), time(CardioRole.RECOVERY))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "A")
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        val slotId = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        vm.toggleDone(slotId, 0, true, Slot.MAIN)
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        assertFalse(requireNotNull(vm.uiState.value.cardio?.run).running)
        vm.startCardio()
        runCurrent()
        tick(10_000)
        tick(5_000)
        assertTrue(requireNotNull(vm.uiState.value.cardio?.run).running)

        vm.completeDay()
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        val sets = repo.history.cardioSetsFor(session.id)
        assertEquals(listOf(CardioSetStatus.FINISHED, CardioSetStatus.FINISHED, CardioSetStatus.SHORTENED,
            CardioSetStatus.SKIPPED, CardioSetStatus.SKIPPED), sets.map { it.status })
        assertEquals(listOf(10, 10, 5, 0, 0), sets.map { it.elapsedSeconds })
        assertNull(vm.uiState.value.cardio?.run)
        assertNull(handle.get<CardioRun>("day_cardio_run"))
        assertFalse(vm.executionActive)
        assertTrue(requireNotNull(vm.finisher.receipt.value).cardioRows.isNotEmpty())
        assertEquals(listOf(session.id), publishedCardio)
        assertTrue(vm.finisher.dismissReceipt())
        assertNull(alarm.deadline)
    }

    @Test
    fun finishingMidIntervalWithAFailedCardioSaveKeepsStrengthOpenUntilRetrySucceeds() = test {
        prepareBlock(time(CardioRole.WARMUP), time(CardioRole.WORK), time(CardioRole.RECOVERY))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "A")
        val runId = requireNotNull(repo.openRunFlow.first()).id
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        val slotId = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        vm.toggleDone(slotId, 0, true, Slot.MAIN)
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.startCardio()
        runCurrent()
        tick(5_000)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")

        vm.completeDay()
        runCurrent()

        val failed = requireNotNull(vm.uiState.value.cardio?.run)
        assertTrue(failed.complete)
        assertTrue(failed.saveFailed)
        assertEquals(failed, handle.get<CardioRun>("day_cardio_run"))
        assertEquals(listOf(SavedCardioResult("FINISHED", 10), SavedCardioResult("SHORTENED", 5),
            SavedCardioResult("SKIPPED", 0)), failed.results)
        assertTrue(vm.uiState.value.runOpen)
        assertEquals(runId, repo.openRunFlow.first()?.id)
        assertTrue(requireNotNull(repo.openRunFlow.first()).tracks.single().sets.first().done)
        assertTrue(repo.history.sessionsFlow.first().isEmpty())
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertTrue(publishedCardio.isEmpty())
        assertNull(vm.finisher.receipt.value)
        assertNull(vm.finisher.attempt.value)
        assertNull(alarm.deadline)

        vm.completeDay()
        runCurrent()
        assertNull(vm.finisher.receipt.value)
        assertTrue(repo.history.sessionsFlow.first().isEmpty())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        vm.retryCardioSave()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(listOf(10, 5, 0), repo.history.cardioSetsFor(session.id).map { it.elapsedSeconds })
        assertEquals(listOf(session.id), publishedCardio)
        assertFalse(vm.executionActive)
        assertTrue(repo.history.sessionsFlow.first().isEmpty())

        vm.completeDay()
        runCurrent()
        assertEquals(1, repo.history.sessionsFlow.first().size)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertTrue(requireNotNull(vm.finisher.receipt.value).cardioRows.isNotEmpty())
        assertTrue(vm.finisher.dismissReceipt())
    }

    @Test
    fun finishingWithAnUnstartedCardioRunLogsNothingAndStillCloses() = test {
        prepareBlock(time(CardioRole.WARMUP), time(CardioRole.WORK), time(CardioRole.RECOVERY),
            time(CardioRole.WORK), time(CardioRole.RECOVERY))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "A")
        val vm = readyVm()
        vm.skipCardioSet(CardioSetAction("A", null, 0))
        runCurrent()
        assertNull(requireNotNull(vm.uiState.value.cardio?.run).startedAt)
        val slotId = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        vm.toggleDone(slotId, 0, true, Slot.MAIN)
        runCurrent()

        vm.completeDay()
        runCurrent()

        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertTrue(publishedCardio.isEmpty())
        assertTrue(requireNotNull(vm.finisher.receipt.value).cardioRows.isEmpty())
        assertNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.executionActive)
        assertTrue(vm.finisher.dismissReceipt())
    }

    @Test
    fun cardioLoggedOnAnotherPlansSameDayDoesNotMarkTheDisplayedDayLogged() = test {
        val prescription = time(CardioRole.WORK, seconds = 10)
        prepareBlock(prescription)
        val loggedPlan = repo.plans.selectedPlanIdFlow.first()!!
        val otherPlan = repo.plans.duplicatePlan(loggedPlan, "Other")
        val loggedSpec = time(CardioRole.WORK, seconds = 30)
        val sessionId = repo.logCardioSession(
            CardioSessionEntity(
                dayId = "A", mode = CardioMode.OUTDOOR_RUN.name, hard = true, label = "Track run",
                startedAt = clock.wall - 30_000, completedAt = clock.wall,
                seconds = 30, stepsCompleted = 1, planId = loggedPlan,
            ),
            listOf(CardioSetResult(loggedSpec, CardioSetStatus.FINISHED, elapsedSeconds = 30)),
        )
        val vm = readyVm()
        assertEquals(sessionId, vm.uiState.value.cardio?.loggedSessionId)
        assertEquals(30, vm.uiState.value.cardio?.loggedSeconds)

        repo.plans.selectPlan(otherPlan)
        runCurrent()

        assertEquals(otherPlan, vm.uiState.value.planId)
        assertEquals("A", vm.uiState.value.viewDayId)
        val cardio = requireNotNull(vm.uiState.value.cardio)
        assertNull(cardio.loggedSessionId)
        assertNull(cardio.loggedSeconds)
        assertTrue(cardio.loggedSets.isEmpty())
        assertEquals(listOf(prescription), cardio.block?.sets)
    }

    @Test fun editingActivityAfterLoggingKeepsTheLoggedActivityForSummary() = test {
        prepareBlock(distance())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(100_000)
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertNull(vm.uiState.value.cardio?.run)

        repo.replaceProgram(Program(repo.plans.programFlow.first().days.map { day ->
            day.copy(cardioBlock = day.cardioBlock?.copy(activity = CardioActivity.ROAD_RUN))
        }))
        runCurrent()

        val cardio = requireNotNull(vm.uiState.value.cardio)
        assertEquals(CardioActivity.ROAD_RUN, cardio.block?.activity)
        assertEquals(CardioActivity.TRACK_RUN, cardio.loggedActivity)
        assertEquals(CardioActivity.ROAD_RUN, repo.plans.programFlow.first().days.first().cardioBlock?.activity)
        assertEquals(session.id, cardio.loggedSessionId)
        assertEquals(400, cardio.loggedSets.single().metres)
        assertEquals(CardioActivity.TRACK_RUN.name, repo.history.cardioSessionsFlow.first().single().activity)
    }

    @Test
    fun legacyCardioWithNullOrZeroPlanIdStaysOutsidePlanScopedLoggedState() = test {
        prepareBlock(time(CardioRole.WORK))
        for (planId in listOf(null, 0L)) {
            db.cardioSessionDao().insert(CardioSessionEntity(
                dayId = "A", mode = CardioMode.OUTDOOR_RUN.name, hard = true, label = "Track run",
                startedAt = clock.wall - 30_000, completedAt = clock.wall,
                seconds = 30, stepsCompleted = 1, planId = planId,
            ))
        }
        val vm = readyVm()

        assertTrue(repo.history.selectedPlanCardioSessionsFlow.first().isEmpty())
        val cardio = requireNotNull(vm.uiState.value.cardio)
        assertNull(cardio.loggedSessionId)
        assertNull(cardio.loggedSeconds)
        assertTrue(cardio.loggedSets.isEmpty())
    }

    private fun DayViewModel.distanceAction(): CardioSetAction {
        val sheet = requireNotNull(uiState.value.openSheet)
        return CardioSetAction(sheet.dayId, sheet.cardioRunId, sheet.setIndex)
    }

    private fun recreateHandle(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private suspend fun prepareAuthoredCardioDay(
        vararg sets: CardioSetSpec,
        activity: CardioActivity = CardioActivity.TRACK_RUN,
    ) {
        val planId = repo.plans.createAuthoredPlan("Running plan", "Running")
        repo.plans.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(activity, sets.toList())
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), cardioBlock = block)
        }))
    }

    private suspend fun prepareLegacyCardioDays(): Long {
        val block = CardioBlocks.fromLegacy(CardioSuggestion("Intervals", "", true),
            fiveK = true, activity = CardioActivity.TRACK_RUN)
        prepareAuthoredCardioDay(*block.sets.toTypedArray())
        return requireNotNull(repo.plans.selectedPlanIdFlow.first())
    }

    private suspend fun seedCardioBest(
        spec: CardioSetSpec,
        seconds: Int,
        metres: Int? = null,
        activity: CardioActivity = CardioActivity.TRACK_RUN,
    ): Long {
        val id = db.cardioSessionDao().insert(CardioSessionEntity(
            dayId = "A", mode = CardioMode.OUTDOOR_RUN.name, hard = true, label = "Run",
            startedAt = clock.wall - seconds * 1000L - 1000, completedAt = clock.wall - 1000,
            seconds = seconds, stepsCompleted = 1, activity = activity.name,
        ))
        val result = CardioSetResult(spec, CardioSetStatus.FINISHED, seconds,
            metres, metres?.let { DistanceSource.MANUAL })
        db.cardioSetResultDao().insertAll(listOf(result.toEntity(id, 0)))
        return id
    }

    private fun parcelHandle(handle: SavedStateHandle): SavedStateHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(CardioRun::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun fasterRecordIsFrozenBeforeLoggingAndSurvivesReceiptParcelAndHistoryEdits() = test {
        val spec = distance()
        prepareAuthoredCardioDay(spec)
        val standingId = seedCardioBest(spec, 102)
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(98_000)
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(400, receipt.cardioRows.single().endMetres)
        assertEquals(98, receipt.cardioRows.single().intervals.single().elapsedSeconds)
        assertFalse(handle.keys().any { it.startsWith("day_cardio_standing_bests:") })
        assertEquals(listOf(CardioRecordMark(PrKind.FASTER, 0, 102, null)), vm.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        assertEquals(2, repo.history.cardioSessionsFlow.first().size)
        repo.history.deleteCardioSession(standingId)
        seedCardioBest(spec, 90)
        vm.retryCardioSave()
        runCurrent()
        assertEquals(receipt, vm.finisher.receipt.value)
        assertEquals(listOf(CardioRecordMark(PrKind.FASTER, 0, 102, null)), vm.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })

        vm.setCardioScreenLive(false)
        vm.viewModelScope.cancel()
        val restoredHandle = parcelHandle(handle)
        val restored = readyVm(restoredHandle)
        assertEquals(receipt, restored.finisher.receipt.value)
        assertEquals(listOf(CardioRecordMark(PrKind.FASTER, 0, 102, null)), restored.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        restored.retryCardioSave()
        runCurrent()
        assertEquals(receipt, restored.finisher.receipt.value)
        assertEquals(listOf(CardioRecordMark(PrKind.FASTER, 0, 102, null)), restored.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        assertEquals(2, repo.history.cardioSessionsFlow.first().size)
        assertTrue(restored.finisher.dismissReceipt())
        runCurrent()
        assertNull(restored.finisher.receipt.value)
        assertFalse(restoredHandle.keys().any {
            it.startsWith("day_cardio_standing_bests:") || it.startsWith("day_cardio_receipt_records:")
        })
    }

    @Test
    fun unequalDurationRunsAreLoggedWithoutRecordsAndDoNotSuppressAnEqualDurationImprovement() = test {
        settings.setDistanceUnit(DistanceUnit.KM)
        val spec = time(CardioRole.WORK, seconds = 1500)
        val activity = CardioActivity.ROAD_RUN
        prepareAuthoredCardioDay(spec, activity = activity)
        seedCardioBest(spec, 1500, 5000, activity)
        val vm = readyVm()

        for (seconds in listOf(1200, 2400)) {
            vm.startCardio()
            runCurrent()
            tick(seconds * 1_000L)
            vm.changeCardioRunDistance("6")
            vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
            runCurrent()

            val receipt = requireNotNull(vm.finisher.receipt.value)
            val result = repo.history.cardioSetsFor(receipt.sessionId).single()
            assertEquals(spec, result.spec)
            assertEquals(CardioSetStatus.FINISHED, result.status)
            assertEquals(seconds, result.elapsedSeconds)
            assertEquals(6000, result.metres)
            assertNull(receipt.cardioRows.single().record)
            assertEquals(5000, repo.history.cardioBestsFor(activity).values.single().metres)
            assertTrue(vm.finisher.dismissReceipt())
            runCurrent()
        }

        vm.startCardio()
        runCurrent()
        tick(1_500_000)
        vm.changeCardioRunDistance("5.5")
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()

        assertEquals(CardioRecordMark(PrKind.FARTHER, 0, null, 5000),
            requireNotNull(vm.finisher.receipt.value).cardioRows.single().record)
        assertEquals(5500, repo.history.cardioBestsFor(activity).values.single().metres)
        assertEquals(4, repo.history.cardioSessionsFlow.first().size)
    }

    @Test
    fun fartherRecordKeepsPreLogBaselineThroughDistanceEntryAndSavedStateRestore() = test {
        settings.setDistanceUnit(DistanceUnit.KM)
        val spec = time(CardioRole.WORK, seconds = 1800, aim = 5000)
        val activity = CardioActivity.ROAD_RUN
        prepareAuthoredCardioDay(spec, activity = activity)
        seedCardioBest(spec, 1800, 4980, activity)
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(1_800_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        assertNull(vm.finisher.receipt.value)
        val pending = vm.uiState.value.cardio!!.distanceRuns.single()
        assertEquals(4980, handle.get<ArrayList<SavedCardioBest>>("day_cardio_standing_bests:${pending.runId}")?.single()?.metres)
        val sessionId = requireNotNull(pending.sessionId)
        assertNull(repo.history.cardioSetsFor(sessionId).single().metres)

        seedCardioBest(spec, 1800, 5400, activity)
        vm.setCardioScreenLive(false)
        vm.viewModelScope.cancel()
        val restoredHandle = parcelHandle(handle)
        val restored = readyVm(restoredHandle)
        restored.openCardioDistance()
        runCurrent()
        restored.changeCardioDistance("5.21")
        restored.saveCardioDistance(restored.distanceAction())
        runCurrent()

        val receipt = requireNotNull(restored.finisher.receipt.value)
        assertEquals(sessionId, receipt.sessionId)
        assertEquals(1800, receipt.cardioRows.single().endSeconds)
        assertEquals(5210, receipt.cardioRows.single().intervals.single().metres)
        assertFalse(restoredHandle.keys().any { it.startsWith("day_cardio_standing_bests:") })
        assertEquals(listOf(CardioRecordMark(PrKind.FARTHER, 0, null, 4980)), restored.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        assertEquals(5210, repo.history.cardioSetsFor(sessionId).single().metres)
        assertEquals(5400, repo.history.cardioBestsFor(activity).values.single().metres)
        restored.retryCardioSave()
        runCurrent()
        assertEquals(receipt, restored.finisher.receipt.value)
        assertEquals(3, repo.history.cardioSessionsFlow.first().size)
    }

    @Test
    fun multiplePendingRunsKeepTheirOwnBaselinesAcrossParcelRestoreAndReceiptDismissal() = test {
        settings.setDistanceUnit(DistanceUnit.KM)
        val spec = time(CardioRole.WORK, seconds = 1800, aim = 5000)
        val activity = CardioActivity.ROAD_RUN
        prepareAuthoredCardioDay(spec, activity = activity)
        seedCardioBest(spec, 1800, 4980, activity)
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(1_800_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val first = vm.uiState.value.cardio!!.distanceRuns.single()
        vm.openCardioDistance()
        vm.closeSheet()
        runCurrent()

        seedCardioBest(spec, 1800, 5400, activity)
        vm.startCardio()
        runCurrent()
        tick(1_800_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val pending = requireNotNull(handle.get<ArrayList<CardioRun>>("day_cardio_distances"))
        assertEquals(2, pending.size)
        val second = pending.single { it.runId != first.runId }
        vm.setCardioScreenLive(false)
        vm.viewModelScope.cancel()

        val restoredHandle = parcelHandle(handle)
        val restored = readyVm(restoredHandle)
        assertEquals(4980, restoredHandle.get<ArrayList<SavedCardioBest>>("day_cardio_standing_bests:${first.runId}")?.single()?.metres)
        assertEquals(5400, restoredHandle.get<ArrayList<SavedCardioBest>>("day_cardio_standing_bests:${second.runId}")?.single()?.metres)
        restored.requestCardioDistance(first.action(0))
        restored.openCardioDistance()
        runCurrent()
        assertEquals(first.action(0), restored.distanceAction())
        restored.changeCardioDistance("5.21")
        restored.saveCardioDistance(restored.distanceAction())
        runCurrent()

        assertEquals(first.sessionId, restored.finisher.receipt.value?.sessionId)
        assertEquals(listOf(CardioRecordMark(PrKind.FARTHER, 0, null, 4980)), restored.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        assertNull(restoredHandle.get<ArrayList<SavedCardioBest>>("day_cardio_standing_bests:${first.runId}"))
        assertTrue(restored.finisher.dismissReceipt())
        runCurrent()
        assertNull(restoredHandle.get<ArrayList<CardioReceiptRecord>>("day_cardio_receipt_records:${first.runId}"))
        assertEquals(5400, restoredHandle.get<ArrayList<SavedCardioBest>>("day_cardio_standing_bests:${second.runId}")?.single()?.metres)

        restored.selectDay(second.dayId)
        runCurrent()
        restored.requestCardioDistance(second.action(0))
        restored.openCardioDistance()
        runCurrent()
        assertEquals(second.action(0), restored.distanceAction())
        restored.changeCardioDistance("5.5")
        restored.saveCardioDistance(restored.distanceAction())
        runCurrent()

        assertEquals(second.sessionId, restored.finisher.receipt.value?.sessionId)
        assertEquals(listOf(CardioRecordMark(PrKind.FARTHER, 0, null, 5400)), restored.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        assertTrue(restored.finisher.dismissReceipt())
        runCurrent()
        assertFalse(restoredHandle.keys().any {
            it.startsWith("day_cardio_standing_bests:") || it.startsWith("day_cardio_receipt_records:")
        })
    }

    @Test
    fun failedSaveRetainsPreLogBaselineForRetryAfterSavedStateRestore() = test {
        val spec = distance()
        prepareAuthoredCardioDay(spec)
        seedCardioBest(spec, 102)
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        vm.startCardio()
        runCurrent()
        tick(98_000)
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val failed = requireNotNull(vm.uiState.value.cardio?.run)
        assertTrue(failed.saveFailed)
        assertEquals(102, handle.get<ArrayList<SavedCardioBest>>("day_cardio_standing_bests:${failed.runId}")?.single()?.elapsedSeconds)
        assertNull(vm.finisher.receipt.value)

        vm.setCardioScreenLive(false)
        vm.viewModelScope.cancel()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        seedCardioBest(spec, 90)
        val restored = readyVm(parcelHandle(handle))
        restored.retryCardioSave()
        restored.retryCardioSave()
        runCurrent()

        assertEquals(listOf(CardioRecordMark(PrKind.FASTER, 0, 102, null)),
            restored.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        assertEquals(3, repo.history.cardioSessionsFlow.first().size)
        assertEquals(1, publishedCardio.size)
    }

    @Test
    fun committedRunWithoutSavedSessionIdOrBaselineDoesNotCompeteWithItselfAfterRestore() = test {
        val spec = distance()
        prepareAuthoredCardioDay(spec)
        seedCardioBest(spec, 102)
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        val started = requireNotNull(first.uiState.value.cardio?.run)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        clock.advance(98_000)
        val completed = started.reachTarget(clock)
        assertTrue(completed.complete)
        assertNull(completed.sessionId)
        handle["day_cardio_run"] = completed
        val summary = completed.summary()
        val committedId = repo.logCardioSession(CardioSessionEntity(
            dayId = completed.dayId, mode = summary.mode.name, hard = summary.hard,
            label = summary.label, startedAt = requireNotNull(completed.startedAt),
            completedAt = requireNotNull(completed.completedAt), seconds = summary.seconds,
            stepsCompleted = summary.stepsCompleted, planId = completed.planId,
            planName = completed.planName, activity = completed.block.activity.name,
        ), completed.resultSets, finishDay = true)
        assertFalse(handle.keys().any { it.startsWith("day_cardio_standing_bests:") })
        assertEquals(98, repo.history.cardioBestsFor(CardioActivity.TRACK_RUN).values.single().elapsedSeconds)

        val restoredHandle = parcelHandle(handle)
        val restored = readyVm(restoredHandle)

        assertEquals(committedId, restored.finisher.receipt.value?.sessionId)
        assertEquals(listOf(CardioRecordMark(PrKind.FASTER, 0, 102, null)),
            restored.finisher.receipt.value?.cardioRows?.mapNotNull { it.record })
        assertEquals(2, repo.history.cardioSessionsFlow.first().size)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertFalse(restoredHandle.keys().any { it.startsWith("day_cardio_standing_bests:") })
        assertTrue(restored.finisher.dismissReceipt())
        runCurrent()
        assertFalse(restoredHandle.keys().any { it.startsWith("day_cardio_receipt_records:") })
    }

    @Test
    fun trackIntervalsPutTheFasterRecordOnTheSecondIntervalOfTheWorkRow() = test {
        val work = distance()
        prepareAuthoredCardioDay(
            time(CardioRole.WARMUP, 10), work, work, time(CardioRole.COOLDOWN, 10),
        )
        seedCardioBest(work, 102)
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.startCardio()
        runCurrent()
        tick(101_000)
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(98_000)
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        val rows = requireNotNull(vm.finisher.receipt.value).cardioRows
        assertEquals(listOf(CardioRole.WARMUP, CardioRole.WORK, CardioRole.COOLDOWN), rows.map { it.role })
        assertEquals(400, rows[1].endMetres)
        assertEquals(listOf(101, 98), rows[1].intervals.map { it.elapsedSeconds })
        assertEquals(CardioRecordMark(PrKind.FASTER, 1, 102, null), rows[1].record)
        assertFalse(handle.keys().any { it.startsWith("day_cardio_standing_bests:") })
    }

    @Test
    fun showCardioWithNoStandingBestsShowsTheReceiptWithRecordsNotChecked() = test {
        prepareAuthoredCardioDay(distance())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(98_000)
        val running = requireNotNull(vm.uiState.value.cardio?.run)
        val run = running.reachTarget(clock)
        vm.reachCardioTarget(running.action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        vm.finisher.showCardio(run.copy(sessionId = session.id), standing = null, session = session)
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertTrue(receipt.cardio)
        assertFalse(receipt.recordsChecked)
        assertTrue(receipt.cardioRows.none { it.record != null })
        assertEquals(98, receipt.cardioRows.single().intervals.single().elapsedSeconds)
    }

    private fun TestScope.startLegacyWorkSet(vm: DayViewModel): CardioRun {
        vm.startCardio()
        runCurrent()
        tick(300_000)
        assertEquals(1, vm.uiState.value.cardio?.run?.position)
        vm.startCardio()
        runCurrent()
        tick(30_000)
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
        assertTrue(alarm.deadline != null)
        return requireNotNull(vm.uiState.value.cardio?.run)
    }

    private suspend fun assertInterruptedLegacyRunLogged(vm: DayViewModel, run: CardioRun) {
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(run.planId, session.planId)
        assertEquals(run.dayId, session.dayId)
        assertEquals(run.startedAt, session.startedAt)
        assertEquals(330, session.seconds)
        val sets = repo.history.cardioSetsFor(session.id)
        assertEquals(run.block.sets, sets.map { it.spec })
        assertEquals(listOf(CardioSetStatus.FINISHED, CardioSetStatus.SHORTENED) +
            List(9) { CardioSetStatus.SKIPPED }, sets.map { it.status })
        assertEquals(listOf(300, 30) + List(9) { 0 }, sets.map { it.elapsedSeconds })
        assertTrue(sets.all { it.metres == null })
        assertEquals(listOf(session.id), publishedCardio)
        assertNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.uiState.value.executionBusy)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
        assertNull(vm.uiState.value.openSheet)
        assertNull(vm.finisher.receipt.value)
        assertNull(alarm.deadline)
        assertNull(alarm.callback)
        assertEquals(1, alarm.buzzes) // Only the warm-up boundary buzzed.
    }

    private fun assertAutoEndedSaveFailed(vm: DayViewModel, run: CardioRun) {
        val card = requireNotNull(vm.uiState.value.cardio)
        val failed = requireNotNull(card.run)
        assertEquals(run.runId, failed.runId)
        assertEquals(run.block, card.block)
        assertEquals(activityLabel(run.block.activity), card.label)
        assertTrue(failed.complete)
        assertTrue(failed.saveFailed) // The set screen exposes the existing failure message and Retry.
        assertFalse(failed.showReceipt)
        assertTrue(vm.uiState.value.executionBusy)
        assertFalse(vm.uiState.value.executionKeepsScreenOn)
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
    fun removingTheNextDayDuringCardioUsesTheCommittedSuggestionAndCurrentTitleOnTheReceipt() = test {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        assertEquals("Day B, Run B", vm.uiState.value.cardio?.run?.nextDayTitle)

        assertTrue(repo.removeDay(plan, "B"))
        repo.renameDay(plan, "C", "Updated run C")
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.run!!.running)
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(plan, session.planId)
        assertEquals("A", session.dayId)
        assertEquals("C", db.planDao().plan(plan)!!.suggestedDayId)
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(session.id, receipt.sessionId)
        assertEquals("Run A", receipt.dayTitle)
        assertEquals("Updated run C", repo.plans.programFlow.first().days.first { it.id == "C" }.title)
    }

    @Test
    fun addDayThenAuthorCardioRunsAndFinishesWithoutExercises() = test {
        val plan = repo.plans.createAuthoredPlan("Running", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        assertEquals("B", repo.addDay(plan, "Removed"))
        assertEquals("C", repo.addDay(plan, "Run"))
        assertTrue(repo.removeDay(plan, "B"))
        val vm = readyVm()
        backgroundScope.launch { vm.dayEditState.collect {} }
        vm.selectDay("C")
        runCurrent()
        vm.editor.setDayEditorOpen(true)
        vm.editor.openCardioBlock()
        runCurrent()
        val draft = vm.dayEditState.value.cardioDraft!!
        vm.editor.changeCardioDraft(draft.copy(sets = listOf(CardioSetDraft(seconds = 10, hasAim = true))))
        vm.editor.saveCardioBlock()
        runCurrent()
        vm.editor.setDayEditorOpen(false)
        assertTrue(vm.uiState.value.exercises.isEmpty())
        assertTrue(vm.uiState.value.cardio!!.finishesDay)
        vm.startCardio()
        runCurrent()
        assertEquals(2, vm.uiState.value.cardio?.run?.dayIndex)
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction()) // Blank distance is allowed.
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(plan, session.planId)
        assertEquals("C", session.dayId)
        assertEquals(CardioSetStatus.FINISHED, repo.history.cardioSetsFor(session.id).single().status)
        assertEquals(session.id, vm.finisher.receipt.value?.sessionId)
        assertEquals(2, vm.finisher.receipt.value?.dayIndex)
        assertEquals("A", repo.plans.suggestedDayFlow.first())
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

        assertNull(vm.finisher.receipt.value)
        assertFalse(vm.finisher.dismissReceipt())
        assertEquals(second.runId, vm.uiState.value.cardio?.run?.runId)
        assertEquals(CardioPhase.EXECUTING, vm.uiState.value.cardio?.phase)
        assertTrue(vm.uiState.value.executionBusy)
        assertTrue(vm.executionActive)
        assertTrue(vm.uiState.value.executionKeepsScreenOn)
        assertEquals(deadline, alarm.deadline)
        assertEquals("A", vm.uiState.value.viewDayId)
        assertEquals(390, repo.history.cardioSetsFor(requireNotNull(first.sessionId)).single().metres)

        tick(10_000)
        vm.changeCardioRunDistance("390")
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()

        assertNull(vm.uiState.value.cardio?.run)
        assertFalse(vm.uiState.value.executionBusy)
        assertFalse(vm.executionActive)
        assertEquals(2, repo.history.cardioSessionsFlow.first().size)
        assertEquals(first.sessionId, vm.finisher.receipt.value?.sessionId)
        assertEquals(first.dayId, vm.finisher.receipt.value?.dayId)
        assertTrue(vm.finisher.receipt.value?.cardio == true)
        assertEquals(1, vm.finisher.receipt.value?.minutes)
        assertTrue(vm.finisher.receipt.value?.groups?.isEmpty() == true)
        assertEquals(390, vm.finisher.receipt.value?.cardioRows?.single()?.intervals?.single()?.metres)
        // Dated by the cardio row, not by when the deferred receipt finally showed.
        val firstCompletedAt = requireNotNull(repo.history.cardioSession(requireNotNull(first.sessionId))).completedAt
        assertEquals(firstCompletedAt, vm.finisher.receipt.value?.completedAt)
        assertNotEquals(clock.wallMillis(), firstCompletedAt)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertTrue(vm.finisher.dismissReceipt())
        runCurrent()
        assertNull(vm.finisher.receipt.value)
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

        assertNull(restored.finisher.receipt.value)
        assertEquals(secondId, restored.uiState.value.cardio?.run?.runId)
        assertTrue(restored.uiState.value.cardio!!.run!!.running)
        assertFalse(restored.finisher.dismissReceipt())
        restored.endCardioBlock()
        runCurrent()
        assertEquals(first.sessionId, restored.finisher.receipt.value?.sessionId)
        assertEquals(2, repo.history.cardioSessionsFlow.first().size)
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
        assertNull(vm.finisher.receipt.value)
        assertTrue(vm.uiState.value.executionBusy)
        assertFalse(vm.uiState.value.cardio!!.run!!.running)

        vm.startCardio()
        runCurrent()
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()

        assertEquals(first.sessionId, vm.finisher.receipt.value?.sessionId)
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
        assertNull(vm.finisher.receipt.value)
        assertTrue(vm.uiState.value.executionBusy)

        vm.endCardioBlock()
        runCurrent()

        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertEquals(first.sessionId, vm.finisher.receipt.value?.sessionId)
    }

    @Test
    fun heldReceiptWaitsForTimedRepCountToBeLogged() = test {
        val (vm, first) = receiptDueDuringTimedRep()
        tick(120_000)
        assertTrue(vm.uiState.value.timedRep!!.event.awaitingCount)
        assertNull(vm.finisher.receipt.value)
        assertFalse(vm.finisher.dismissReceipt())

        vm.changeTimedRepDraft("12")
        vm.logTimedRepCount()
        runCurrent()

        assertNull(vm.uiState.value.timedRep)
        assertEquals(first.sessionId, vm.finisher.receipt.value?.sessionId)
        assertEquals(12, repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single().sets.first().reps)
    }

    @Test
    fun heldReceiptReleasesWhenTimedRepIsStopped() = test {
        val (vm, first) = receiptDueDuringTimedRep()

        vm.stopTimedRep()
        runCurrent()

        assertNull(vm.uiState.value.timedRep)
        assertEquals(first.sessionId, vm.finisher.receipt.value?.sessionId)
    }

    private suspend fun TestScope.receiptDueDuringTimedRep(): Pair<DayViewModel, CardioRun> {
        prepareAuthoredCardioDay(time(CardioRole.WORK))
        val vm = readyVm()
        val first = stopFirstRunAndDismissDistance(vm)
        // An authored day can gain a timed-rep slot while its logged distance is deferred.
        repo.replaceProgram(Program(repo.plans.programFlow.first().days.map { day ->
            if (day.id != "A") day else day.copy(exercises = listOf(ProgramExercise(
                "pushup", targetSets = 2, prescription = Prescription(PrescribedTarget.RepsInTime(120, 40)),
            )))
        }))
        runCurrent()
        val card = vm.uiState.value.exercises.single()
        vm.startTimedRep(card.programExerciseId, card.focusIndex)
        runCurrent()
        finishOldDistance(vm, first)
        assertNull(vm.finisher.receipt.value)
        assertTrue(vm.uiState.value.timedRepRunning)
        assertFalse(vm.finisher.dismissReceipt())
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

        vm.editor.setDayEditorOpen(true)
        vm.editor.openCardioBlock()
        runCurrent()
        val draft = requireNotNull(vm.dayEditState.value.cardioDraft)
        val edited = draft.copy(sets = draft.sets.mapIndexed { index, set ->
            if (index == 3) set.copy(seconds = 60) else set
        }, editingSet = 3)
        vm.editor.changeCardioDraft(edited)
        vm.editor.saveCardioBlock() // Set-page Save commits while keeping the block page open.
        runCurrent()
        assertEquals(edited.block(), repo.plans.programFlow.first().days.first().cardioBlock)
        assertEquals(frozen, vm.uiState.value.cardio?.run)
        assertEquals(deadline, alarm.deadline)
        vm.editor.saveCardioBlock() // Block-page Save must preserve the same execution too.
        runCurrent()
        vm.editor.setDayEditorOpen(false)
        assertEquals(frozen, vm.uiState.value.cardio?.run)
        assertEquals(sets, vm.uiState.value.cardio?.block?.sets)

        tick(119_000)
        repeat(4) {
            vm.startCardio()
            runCurrent()
            tick(120_000)
            tick(120_000)
        }
        val session = repo.history.cardioSessionsFlow.first().single()
        val logged = repo.history.cardioSetsFor(session.id)
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
        assertEquals(123, repo.history.cardioSetsFor(session.id)[1].metres)
        assertTrue(vm.finisher.dismissReceipt())

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
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(startedAt, session.startedAt)
        assertEquals(1_500, session.seconds)
        assertEquals(11, session.stepsCompleted)
        assertEquals("ROAD_RUN", session.activity)
        assertEquals("OUTDOOR_RUN", session.mode)
        assertTrue(session.hard)
        assertEquals(repo.plans.selectedPlanIdFlow.first(), session.planId)
        assertEquals(block.sets, repo.history.cardioSetsFor(session.id).map { it.spec })
        assertTrue(repo.history.cardioSetsFor(session.id).all { it.status == CardioSetStatus.FINISHED })
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
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        vm.reachCardioTarget(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        val set = repo.history.cardioSetsFor(session.id).single()
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
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
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
        val day = repo.plans.programFlow.first().days.single()
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
        val day = repo.plans.programFlow.first().days.single()
        repo.replaceProgram(Program(listOf(day.copy(exercises = listOf(ProgramExercise(
            "pushup", targetSets = 2, prescription = Prescription(PrescribedTarget.RepsInTime(120, 40)),
        ))), day.copy(id = "B", title = "Other run"))))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
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
        assertEquals(390, repo.history.cardioSetsFor(session.id).single().metres)
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
        val session = repo.history.cardioSessionsFlow.first().single()
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
        assertEquals(390, repo.history.cardioSetsFor(session.id)[0].metres)
        assertNull(repo.history.cardioSetsFor(session.id)[1].metres)
        assertEquals(1, vm.uiState.value.openSheet?.setIndex)
        vm.saveCardioDistance(second)
        runCurrent()
        assertEquals(95, repo.history.cardioSetsFor(session.id)[1].metres)
        assertEquals(listOf(session.id), publishedCardio)
    }

    @Test
    fun staleDoneCannotSaveANewerRunsDraftAtTheSameSetIndex() = test {
        prepareBlock(time(CardioRole.WORK, aim = 400))
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val firstSession = repo.history.cardioSessionsFlow.first().single()
        vm.openCardioDistance()
        runCurrent()
        val first = vm.distanceAction()
        vm.closeSheet()
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val secondSession = repo.history.cardioSessionsFlow.first().first { it.id != firstSession.id }
        vm.openCardioDistance()
        vm.changeCardioDistance("395")
        runCurrent()
        val second = vm.distanceAction()
        assertEquals(first.setIndex, second.setIndex)
        assertFalse(first.runId == second.runId)
        vm.saveCardioDistance(first)
        runCurrent()
        assertNull(repo.history.cardioSetsFor(firstSession.id).single().metres)
        assertNull(repo.history.cardioSetsFor(secondSession.id).single().metres)
        assertEquals(second.runId, vm.uiState.value.openSheet?.cardioRunId)
        vm.saveCardioDistance(second)
        runCurrent()
        assertNull(repo.history.cardioSetsFor(firstSession.id).single().metres)
        assertEquals(395, repo.history.cardioSetsFor(secondSession.id).single().metres)
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
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(CardioSetStatus.SHORTENED, repo.history.cardioSetsFor(session.id).single().status)
        assertNull(repo.history.cardioSetsFor(session.id).single().metres)
        vm.openCardioDistance()
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.single().drafts.single().stopped)
        vm.changeCardioDistance("380")
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertEquals(380, repo.history.cardioSetsFor(session.id).single().metres)
        assertEquals(DistanceSource.MANUAL, repo.history.cardioSetsFor(session.id).single().distanceSource)
        assertEquals(23, session.seconds)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
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
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(1, session.seconds)
        assertNull(repo.history.cardioSetsFor(session.id).single().metres)
        assertNull(repo.history.cardioSetsFor(session.id).single().distanceSource)
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
        val session = repo.history.cardioSessionsFlow.first().single()
        assertNull(repo.history.cardioSetsFor(session.id).first().metres)
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
        val session = repo.history.cardioSessionsFlow.first().single()
        assertNull(vm.uiState.value.cardio!!.run)
        assertFalse(vm.uiState.value.executionBusy)
        assertEquals("380", vm.uiState.value.cardio!!.distanceRuns.single().drafts.single().text)
        assertNull(repo.history.cardioSetsFor(session.id).first().metres)
        vm.requestCardioDistance(vm.uiState.value.cardio!!.distanceRuns.first().action(0))
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertEquals(380, repo.history.cardioSetsFor(session.id).first().metres)
        assertNull(vm.uiState.value.cardio?.run)
    }

    @Test
    fun skipBeforeStartAndEndShortenActiveAndSkipUntouchedSets() = test {
        prepareBlock(time(CardioRole.WARMUP), distance(), time(CardioRole.RECOVERY), time(CardioRole.WORK))
        val vm = readyVm()
        vm.skipCardioSet(vm.uiState.value.cardio?.run?.action() ?: CardioSetAction("A", null, 0))
        runCurrent()
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        vm.startCardio()
        runCurrent()
        vm.skipCardioSet(vm.uiState.value.cardio?.run?.action() ?: CardioSetAction("A", null, 0)) // A running set cannot be skipped.
        runCurrent()
        tick(4_000)
        vm.endCardioBlock()
        vm.endCardioBlock()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        val sets = repo.history.cardioSetsFor(session.id)
        assertEquals(listOf(CardioSetStatus.SKIPPED, CardioSetStatus.SHORTENED,
            CardioSetStatus.SKIPPED, CardioSetStatus.SKIPPED), sets.map { it.status })
        assertEquals(listOf(0, 4, 0, 0), sets.map { it.elapsedSeconds })
        assertTrue(sets.all { it.metres == null })
    }

    @Test
    fun entirelyUnstartedBlockDoesNotLogOrAdvance() = test {
        prepareBlock(time(CardioRole.WORK), time(CardioRole.COOLDOWN))
        val vm = readyVm()
        val suggested = repo.plans.suggestedDayFlow.first()
        vm.endCardioBlock()
        runCurrent()
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertEquals(suggested, repo.plans.suggestedDayFlow.first())
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
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
    }

    @Test
    fun expiredFinalRestoreLogsSilentlyWithoutOverrunOrDuplicate() = test {
        prepareBlock(time(CardioRole.WORK, 5), time(CardioRole.COOLDOWN, 5))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        clock.advance(60_000)
        val stale = recreateHandle(handle)
        val restored = readyVm(recreateHandle(stale))
        assertEquals(10, repo.history.cardioSessionsFlow.first().single().seconds)
        assertNull(restored.uiState.value.cardio?.run)
        assertEquals(0, alarm.buzzes)
        restored.viewModelScope.cancel()
        readyVm(recreateHandle(stale))
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
    }

    @Test
    fun missingRunDayOnRestoreEndsAndLogsInsteadOfRetargeting() = test {
        prepareBlock(distance())
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        val run = requireNotNull(first.uiState.value.cardio?.run)
        tick(30_000)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        val day = repo.plans.programFlow.first().days.single()
        repo.replaceProgram(Program(listOf(day.copy(id = "B"))))
        val restored = readyVm(recreateHandle(handle))
        assertNull(restored.uiState.value.cardio?.run)
        assertNull(alarm.deadline)
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(run.planId, session.planId)
        assertEquals("A", session.dayId)
        assertEquals(30, session.seconds)
        assertEquals(CardioSetStatus.SHORTENED, repo.history.cardioSetsFor(session.id).single().status)
        assertNull(restored.uiState.value.openSheet)
        assertNull(restored.finisher.receipt.value)
    }

    @Test
    fun differentPlanEndsAndLogsLegacyRunEvenWhenDayAndBlockMatch() = test {
        val plan = prepareLegacyCardioDays()
        val other = repo.plans.duplicatePlan(plan, "Other plan")
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        val run = startLegacyWorkSet(vm)
        assertTrue(run.finishesDay)
        repo.plans.selectPlan(other)
        runCurrent()
        vm.openCardioDistance()
        vm.retryCardioSave()
        runCurrent()
        assertInterruptedLegacyRunLogged(vm, run)
        assertNull(handle.get<CardioRun>("day_cardio_run"))
        assertEquals(other, vm.uiState.value.planId)
        assertEquals("A", repo.plans.suggestedDayFlow.first())
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
    }

    @Test
    fun removingRunDayInEditorEndsAndLogsLegacyRunWithoutAdvancingNewCursor() = test {
        val plan = prepareLegacyCardioDays()
        val vm = readyVm()
        val run = startLegacyWorkSet(vm)
        assertTrue(repo.removeDay(plan, "A"))
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        assertInterruptedLegacyRunLogged(vm, run)
        assertEquals("B", vm.uiState.value.viewDayId)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
    }

    @Test
    fun removedRunDayShowsFailedSaveOnAnotherDayAndRetryLogsOnce() = test {
        val plan = prepareLegacyCardioDays()
        repo.addExercise("B", ProgramExercise("plank"))
        assertTrue(repo.setCardioBlock(plan, "B", null))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        val run = startLegacyWorkSet(vm)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        assertTrue(repo.removeDay(plan, "A"))
        runCurrent()
        assertEquals("B", vm.uiState.value.viewDayId)
        assertAutoEndedSaveFailed(vm, run)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        // Rebuilding this day's logs with the same DayContext must not retry.
        vm.changeSeconds(vm.uiState.value.exercises.single().programExerciseId, Slot.MAIN, 0, 42)
        runCurrent()
        assertEquals(42, repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "B").map { it.tracks }.first().single().sets.first().seconds)
        assertAutoEndedSaveFailed(vm, run)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        vm.retryCardioSave()
        vm.retryCardioSave()
        runCurrent()
        assertInterruptedLegacyRunLogged(vm, run)
        assertNull(handle.get<CardioRun>("day_cardio_run"))
        assertNull(vm.uiState.value.cardio)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
    }

    @Test
    fun autoEndedRunRetriesOnLaterPlanContextAndLogsOnce() = test {
        val plan = prepareLegacyCardioDays()
        val other = repo.plans.duplicatePlan(plan, "Other plan")
        assertTrue(repo.setCardioBlock(other, "A", CardioBlock(CardioActivity.OUTDOOR_WALK, listOf(distance()))))
        val vm = readyVm()
        val run = startLegacyWorkSet(vm)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        repo.plans.selectPlan(other)
        runCurrent()
        assertEquals(other, vm.uiState.value.planId)
        assertAutoEndedSaveFailed(vm, run)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        repo.plans.selectPlan(plan)
        runCurrent()
        assertInterruptedLegacyRunLogged(vm, run)
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
        assertEquals("A", db.planDao().plan(other)?.suggestedDayId)
        repo.plans.selectPlan(other)
        runCurrent()
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
    }

    @Test
    fun failedAutoEndedRunRestoresOnAnotherDayAndRetryLogsOnce() = test {
        val plan = prepareLegacyCardioDays()
        repo.addExercise("B", ProgramExercise("plank"))
        assertTrue(repo.setCardioBlock(plan, "B", null))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        val run = startLegacyWorkSet(first)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        assertTrue(repo.removeDay(plan, "A"))
        runCurrent()
        assertAutoEndedSaveFailed(first, run)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()

        // The restore's automatic retry also fails; manual Retry must stay reachable.
        val restoredHandle = recreateHandle(handle)
        val restored = readyVm(restoredHandle)
        assertEquals("B", restored.uiState.value.viewDayId)
        assertAutoEndedSaveFailed(restored, run)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        restored.retryCardioSave()
        restored.retryCardioSave()
        runCurrent()
        assertInterruptedLegacyRunLogged(restored, run)
        assertNull(restoredHandle.get<CardioRun>("day_cardio_run"))
        assertNull(restored.uiState.value.cardio)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
    }

    @Test
    fun unstartedRunIsClearedOnPlanSwitchWithoutLoggingOrAdvancing() = test {
        val plan = prepareLegacyCardioDays()
        val other = repo.plans.duplicatePlan(plan, "Other plan")
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.addCardioSet()
        runCurrent()
        assertNull(requireNotNull(handle.get<CardioRun>("day_cardio_run")).startedAt)
        repo.plans.selectPlan(other)
        runCurrent()
        assertNull(handle.get<CardioRun>("day_cardio_run"))
        assertNull(vm.uiState.value.cardio?.run)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertEquals("A", db.planDao().plan(plan)?.suggestedDayId)
        assertEquals("A", repo.plans.suggestedDayFlow.first())
        assertNull(vm.finisher.receipt.value)
    }

    @Test
    fun unstartedRunIsClearedWhenItsDayIsRemovedWithoutLogging() = test {
        val plan = prepareLegacyCardioDays()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.addCardioSet()
        runCurrent()
        assertNull(requireNotNull(handle.get<CardioRun>("day_cardio_run")).startedAt)
        assertTrue(repo.removeDay(plan, "A"))
        runCurrent()
        assertNull(handle.get<CardioRun>("day_cardio_run"))
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertNull(vm.uiState.value.openSheet)
        assertNull(vm.finisher.receipt.value)
    }

    @Test
    fun unstartedRunIsClearedWhenItsBlockIsEditedWithoutLogging() = test {
        val plan = prepareLegacyCardioDays()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.addCardioSet()
        runCurrent()
        assertNull(requireNotNull(handle.get<CardioRun>("day_cardio_run")).startedAt)
        assertTrue(repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN, listOf(distance()))))
        runCurrent()
        assertNull(handle.get<CardioRun>("day_cardio_run"))
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertEquals("A", repo.plans.suggestedDayFlow.first())
    }

    @Test
    fun interruptedRunAutomaticallyRetriesAfterProcessDeathWithoutReceiptOrMovingAnotherPlansCursor() = test {
        val plan = prepareLegacyCardioDays()
        val other = repo.plans.duplicatePlan(plan, "Other plan")
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        val run = startLegacyWorkSet(first)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        repo.plans.selectPlan(other)
        runCurrent()
        val failed = requireNotNull(handle.get<CardioRun>("day_cardio_run"))
        assertTrue(failed.complete)
        assertTrue(failed.saveFailed)
        assertFalse(failed.showReceipt)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertNull(alarm.deadline)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        val restored = readyVm(recreateHandle(handle))
        assertInterruptedLegacyRunLogged(restored, run)
        assertEquals(other, restored.uiState.value.planId)
        assertEquals("A", repo.plans.suggestedDayFlow.first())
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
    }

    @Test
    fun removedSourceBlockLeavesFrozenRunAccessibleUntilDistanceIsSaved() = test {
        val plan = repo.plans.createAuthoredPlan("Running", "Run")
        repo.plans.publishCreatedAuthoredPlan(plan)
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
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(frozen.block.sets, repo.history.cardioSetsFor(session.id).map { it.spec })
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
        assertEquals(390, repo.history.cardioSetsFor(session.id).single().metres)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertNull(vm.uiState.value.cardio)
    }

    @Test
    fun finalDistanceEntryAmendsBeforeCardioOnlyReceiptAndPlanAdvancesOnce() = test {
        val planId = repo.plans.createAuthoredPlan("Running plan", "Running")
        repo.plans.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(time(CardioRole.WORK, aim = 400)))
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Running", "", emptyList(), cardioBlock = block),
            ProgramDay("B", "Next run", "", emptyList(), cardioBlock = block),
            ProgramDay("C", "Third run", "", emptyList(), cardioBlock = block),
        )))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.startCardio()
        runCurrent()
        tick(10_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertNull(vm.finisher.receipt.value)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        vm.openCardioDistance()
        vm.changeCardioDistance("401")
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(planId, session.planId)
        assertEquals("Running plan", session.planName)
        assertEquals(401, repo.history.cardioSetsFor(session.id).single().metres)
        assertEquals(401, vm.finisher.receipt.value?.cardioRows?.single()?.intervals?.single()?.metres)
        vm.retryCardioSave()
        runCurrent()
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
    }

    @Test
    fun finalSheetDraftSurvivesProcessDeathAfterLogAndBlankEntryStillFinishesDay() = test {
        val planId = repo.plans.createAuthoredPlan("Running", "Run")
        repo.plans.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(time(CardioRole.WORK, aim = 400)))
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), cardioBlock = block)
        }))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        first.startCardio()
        runCurrent()
        tick(10_000)
        first.stopCardio(requireNotNull(first.uiState.value.cardio?.run).action())
        runCurrent()
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
        assertNull(repo.history.cardioSetsFor(repo.history.cardioSessionsFlow.first().single().id).single().metres)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertEquals("A", restored.finisher.receipt.value?.dayId)
        assertEquals(0, alarm.buzzes)
    }

    @Test
    fun generatedCardioDayFinishesOutsideStrengthRotation() = test {
        prepare()
        val source = repo.plans.programFlow.first().days.single()
        repo.replaceProgram(Program(listOf(source, source.copy(id = "B"),
            ProgramDay("C", "Running", "", emptyList(), cardioBlock = CardioBlocks.fromLegacy(CardioSuggestion("Easy", "", false), true, CardioActivity.ROAD_RUN), kind = ProgramDayKind.CARDIO))))
        val vm = readyVm()
        vm.selectDay("C")
        runCurrent()
        vm.startCardio()
        runCurrent()
        tick(1_500_000)
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        vm.openCardioDistance()
        runCurrent()
        vm.saveCardioDistance(vm.distanceAction())
        runCurrent()
        assertEquals("A", repo.plans.suggestedDayFlow.first())
        assertEquals("C", vm.finisher.receipt.value?.dayId)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
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
        vm.stopCardio(requireNotNull(vm.uiState.value.cardio?.run).action())
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.run!!.saveFailed)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        vm.retryCardioSave()
        vm.retryCardioSave()
        runCurrent()
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
        assertEquals(10, repo.history.cardioSessionsFlow.first().single().seconds)
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
