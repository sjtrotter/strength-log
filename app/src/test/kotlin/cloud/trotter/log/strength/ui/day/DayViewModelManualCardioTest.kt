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
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.JournalBuilder
import cloud.trotter.log.strength.ui.log.LogScreenBuilder
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class DayViewModelManualCardioTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.systemDefault()
    private val now = java.time.LocalDate.of(2026, 11, 6).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<DayViewModel>()
    private val published = mutableListOf<Long>()
    private var failReceiptRead = false
    private var beforeReceiptRead: (suspend () -> Unit)? = null
    private var beforeProgramRead: (suspend () -> Unit)? = null
    private val publisher = object : SessionPublisher by SessionPublisher.NoOp {
        override suspend fun publishCardio(cardioSessionId: Long) { published += cardioSessionId }
    }
    private val block = CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(2700))))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("manual-cardio-vm", ".preferences_pb")
        })
        repo = TrackerRepository(db, object : ProgramDao by db.programDao() {
            override fun observeDays(planId: Long) = db.programDao().observeDays(planId).onStart {
                beforeProgramRead?.also { beforeProgramRead = null }?.invoke()
            }
        }, object : SessionDao by db.sessionDao() {
            override fun observeSessions() = db.sessionDao().observeSessions().onStart {
                beforeReceiptRead?.also { beforeReceiptRead = null }?.invoke()
                check(!failReceiptRead)
            }
        }, db.customExerciseDao(), settings)
        share = ShareCardService(context, repo.history, settings = settings)
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun prepare(mixed: Boolean = false): Long {
        val plan = repo.plans.createAuthoredPlan("Running plan", "Running")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Running", "", if (mixed) listOf(ProgramExercise("bb_back_squat")) else emptyList(), cardioBlock = block),
            ProgramDay("B", "Lifting", "", listOf(ProgramExercise("bb_bench"))),
        )))
        return plan
    }

    private fun TestScope.readyVm(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val vm = DayViewModel(repo, publisher, share, handle, FixedCardioClock(now), InertCardioAlarm,
            PhoneRestController(settings, NoOpRestRuntime), clock = Clock.fixed(Instant.ofEpochMilli(now), zone),
            settings = settings, plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history)
        vms += vm
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun enter(vm: DayViewModel, startedAt: Long = now - 2_472_000) {
        vm.openPastCardio()
        vm.changePastCardio(requireNotNull(vm.pastCardio.value).copy(
            startedAt = startedAt, seconds = 2412, metresText = "7100", note = "Easy"))
    }

    @Test fun `an after-the-fact run on today's cardio day finishes the day`() = runTest(dispatcher) {
        val plan = prepare()
        val vm = readyVm()
        enter(vm)
        vm.savePastCardio()
        vm.savePastCardio()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(2412, session.seconds)
        assertEquals(2_412_000L, session.completedAt - session.startedAt)
        assertEquals("Easy", session.note)
        assertEquals("ROAD_RUN", session.activity)
        assertEquals(plan, session.planId)
        assertEquals("Running plan", session.planName)
        val result = repo.history.cardioSetsFor(session.id).single()
        assertEquals(CardioRole.WORK, result.spec.role)
        assertEquals(Measure.Time(2412), result.spec.end)
        assertEquals(CardioSetStatus.FINISHED, result.status)
        assertEquals(7100, result.metres)
        assertEquals(7100, result.metresAtEnd)
        assertEquals(2412, result.secondsAtEnd)
        assertFalse(vm.pastCardioSaveFailed.value)
        assertEquals(DistanceSource.MANUAL, result.distanceSource)
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertTrue(receipt.cardio)
        assertEquals("Easy", receipt.note)
        assertEquals(40, receipt.minutes)
        assertEquals(7100, receipt.cardioRows.single().intervals.single().metres)
        assertEquals("A", vm.uiState.value.viewDayId)
        assertEquals(session.id, vm.uiState.value.cardio?.loggedSessionId)
        assertEquals(listOf(session.id), published)
        assertNull(vm.pastCardio.value)
        val item = LogScreenBuilder.cardioItem(session, listOf(result))
        assertEquals("40:12", item.cardioDuration)
        assertEquals(7100, item.cardioSets.single().metres)
    }

    @Test fun `one on a past date appears on that date in History`() = runTest(dispatcher) {
        prepare()
        val vm = readyVm()
        val startedAt = now - 2 * 86_400_000L - 2_472_000
        enter(vm, startedAt)
        vm.savePastCardio()
        runCurrent()
        val sessions = repo.history.cardioSessionsFlow.first()
        val session = sessions.single()
        assertEquals(startedAt + 2_412_000, session.completedAt)
        val date = Instant.ofEpochMilli(session.completedAt).atZone(zone).toLocalDate()
        assertEquals(date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)),
            LogScreenBuilder.cardioItem(session).dateDisplay)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val calendar = requireNotNull(JournalBuilder.calendar(emptyList(), 0, today, zone, sessions))
        assertEquals("A", calendar.days.single { it.dayOfMonth == date.dayOfMonth }.dayLetter)
        assertNull(vm.uiState.value.cardio?.loggedSessionId)
    }

    @Test fun `a past cardio entry offers the next increase before a live run`() = runTest(dispatcher) {
        val plan = prepare(mixed = true)
        repo.plans.setOfferCardioIncreases(plan, true)
        val vm = readyVm()
        enter(vm, now - 2 * 86_400_000L - 2_700_000)
        vm.changePastCardio(requireNotNull(vm.pastCardio.value).copy(seconds = 2700))
        vm.savePastCardio()
        runCurrent()

        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals("Easy", session.note)
        assertNull(vm.uiState.value.cardio?.loggedSessionId)
        assertEquals(CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(2835)))),
            vm.uiState.value.cardio?.block)
        val offer = vm.uiState.value.cardio?.nextTime as CardioNextTime.Offer
        assertEquals(block, offer.opening)
        assertEquals(Measure.Time(2835), offer.offered.sets.single().end)
        vm.applyCardioNextTime(offer.offered)
        runCurrent()
        assertTrue((vm.uiState.value.cardio?.nextTime as CardioNextTime.Offer).applied)
        vm.startCardio()
        runCurrent()
        assertNull(vm.uiState.value.cardio?.nextTime)
        assertEquals(offer.offered, vm.uiState.value.cardio?.run?.block)
        assertEquals(session, repo.history.cardioSession(session.id))
    }

    @Test fun `a cardio finisher logged after the fact does not finish a lift day`() = runTest(dispatcher) {
        val plan = prepare(mixed = true)
        repo.startRun(plan, "A")
        val vm = readyVm()
        enter(vm)
        vm.savePastCardio()
        runCurrent()
        assertEquals("A", db.planDao().plan(plan)?.suggestedDayId)
        assertNull(vm.finisher.receipt.value)
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(session, repo.history.cardioSessionsFlow.first().loggedToday(plan, "A", now))
        assertTrue(vm.uiState.value.canFinish)
        vm.completeDay()
        runCurrent()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertFalse(receipt.cardio)
        assertEquals(CardioActivity.ROAD_RUN, receipt.activity)
        assertEquals(7100, receipt.cardioRows.single().intervals.single().metres)
    }

    @Test fun `a cross plan start time collision retains the draft`() = runTest(dispatcher) {
        val firstPlan = prepare()
        val firstVm = readyVm()
        val startedAt = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
            .atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        enter(firstVm, startedAt)
        firstVm.savePastCardio()
        runCurrent()
        val first = repo.history.cardioSessionsFlow.first().single()
        val firstSets = repo.history.cardioSetsFor(first.id)
        firstVm.viewModelScope.cancel()

        val secondPlan = prepare()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        enter(vm, startedAt)
        val draft = requireNotNull(vm.pastCardio.value)
        repeat(2) {
            vm.savePastCardio()
            runCurrent()

            assertEquals(draft, vm.pastCardio.value)
            assertEquals(draft, handle.get<CardioEntryDraft>("day_past_cardio_draft"))
            assertEquals(DaySelection(secondPlan, "A"), handle.get<DaySelection>("day_past_cardio_day"))
            assertTrue(vm.pastCardioSaveFailed.value)
            assertTrue(vm.pastCardioCollision.value)
            assertFalse(vm.pastCardioSaving.value)
            assertNull(handle.get<Long>("day_past_cardio_receipt_id"))
            assertNull(vm.finisher.receipt.value)
            assertEquals("B", db.planDao().plan(firstPlan)?.suggestedDayId)
            assertEquals("A", db.planDao().plan(secondPlan)?.suggestedDayId)
            assertEquals(listOf(first), repo.history.cardioSessionsFlow.first())
            assertEquals(firstSets, repo.history.cardioSetsFor(first.id))
            assertEquals(listOf(first.id), published)
        }

        vm.changePastCardio(draft.copy(startedAt = startedAt + 60_000))
        assertFalse(vm.pastCardioCollision.value)
        vm.savePastCardio()
        runCurrent()
        val second = repo.history.cardioSessionsFlow.first().single { it.id != first.id }
        assertEquals(secondPlan, second.planId)
        assertEquals(second.id, vm.finisher.receipt.value?.sessionId)
        assertEquals("B", db.planDao().plan(secondPlan)?.suggestedDayId)
        assertNull(vm.pastCardio.value)
        assertFalse(vm.pastCardioSaveFailed.value)
        assertEquals(listOf(first.id, second.id), published)
    }

    @Test fun `a content collision retains the draft while matching retries succeed`() = runTest(dispatcher) {
        val plan = prepare()
        val vm = readyVm()
        enter(vm, now - 86_400_000L - 2_472_000)
        val draft = requireNotNull(vm.pastCardio.value)
        vm.savePastCardio()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        val results = repo.history.cardioSetsFor(session.id)
        assertTrue(vm.finisher.dismissReceipt())
        runCurrent()
        vm.selectDay("A")
        runCurrent()
        vm.openPastCardio()

        for (changed in listOf(
            draft.copy(seconds = draft.seconds + 60),
            draft.copy(metresText = "9000"),
            draft.copy(note = "Changed"),
            draft.copy(activity = CardioActivity.OUTDOOR_RIDE),
        )) {
            vm.changePastCardio(changed)
            vm.savePastCardio()
            runCurrent()
            assertEquals(changed, vm.pastCardio.value)
            assertTrue(vm.pastCardioSaveFailed.value)
            assertTrue(vm.pastCardioCollision.value)
            assertNull(vm.finisher.receipt.value)
            assertEquals(listOf(session), repo.history.cardioSessionsFlow.first())
            assertEquals(results, repo.history.cardioSetsFor(session.id))
            assertEquals(listOf(session.id), published)
        }

        vm.changePastCardio(draft.copy(note = " Easy "))
        vm.savePastCardio()
        runCurrent()
        assertNull(vm.pastCardio.value)
        assertFalse(vm.pastCardioSaveFailed.value)
        assertFalse(vm.pastCardioCollision.value)
        assertEquals(session.id, vm.finisher.receipt.value?.sessionId)
        assertEquals(listOf(session), repo.history.cardioSessionsFlow.first())
        assertEquals(results, repo.history.cardioSetsFor(session.id))
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
    }

    @Test fun `an invalid draft does not save`() = runTest(dispatcher) {
        val plan = prepare()
        val vm = readyVm()
        enter(vm, now)
        vm.savePastCardio()
        runCurrent()
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        assertNotNull(vm.pastCardio.value)
        assertEquals("A", db.planDao().plan(plan)?.suggestedDayId)
    }

    @Test fun `saving freezes the draft before the first read`() = runTest(dispatcher) {
        prepare()
        val vm = readyVm()
        enter(vm)
        val draft = requireNotNull(vm.pastCardio.value)
        val resume = CompletableDeferred<Unit>()
        beforeProgramRead = { resume.await() }
        vm.savePastCardio()
        vm.changePastCardio(draft.copy(startedAt = draft.startedAt - 60_000, metresText = "9000", note = "Changed"))
        vm.dismissPastCardio()
        vm.openPastCardio()
        vm.savePastCardio()
        runCurrent()
        assertEquals(draft, vm.pastCardio.value)
        assertTrue(vm.pastCardioSaving.value)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        resume.complete(Unit)
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertEquals(draft.startedAt, session.startedAt)
        assertEquals(draft.note, session.note)
        assertEquals(7100, repo.history.cardioSetsFor(session.id).single().metres)
        assertFalse(vm.pastCardioSaving.value)
        assertNull(vm.pastCardio.value)
    }

    @Test fun `a receipt failure clears the committed draft and retry reads the saved session`() = runTest(dispatcher) {
        val plan = prepare()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        enter(vm)
        val draft = requireNotNull(vm.pastCardio.value)
        val readingReceipt = CompletableDeferred<Unit>()
        val resumeReceipt = CompletableDeferred<Unit>()
        beforeReceiptRead = { readingReceipt.complete(Unit); resumeReceipt.await() }
        failReceiptRead = true
        vm.savePastCardio()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertTrue(readingReceipt.isCompleted)
        assertNull(vm.pastCardio.value)
        assertNull(handle.get<DaySelection>("day_past_cardio_day"))
        assertFalse(vm.pastCardioSaveFailed.value)
        assertNull(vm.finisher.receipt.value)
        assertEquals(session.id, handle.get<Long>("day_past_cardio_receipt_id"))
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
        resumeReceipt.complete(Unit)
        runCurrent()
        assertFalse(vm.pastCardioSaveFailed.value)
        vm.changePastCardio(draft.copy(metresText = "9000", note = "Changed"))
        vm.savePastCardio()
        vm.changePastCardio(draft.copy(startedAt = draft.startedAt - 60_000))
        vm.savePastCardio()
        runCurrent()
        assertEquals(session, repo.history.cardioSessionsFlow.first().single())
        failReceiptRead = false
        vm.setCardioScreenLive(true)
        vm.retryCardioSave()
        vm.retryCardioSave()
        runCurrent()
        assertEquals(session.id, vm.finisher.receipt.value?.sessionId)
        assertEquals("Easy", vm.finisher.receipt.value?.note)
        assertEquals(7100, vm.finisher.receipt.value?.cardioRows?.single()?.intervals?.single()?.metres)
        assertNull(handle.get<Long>("day_past_cardio_receipt_id"))
        assertEquals(listOf(session.id), published)
        assertEquals(listOf(session), repo.history.cardioSessionsFlow.first())
    }

    @Test fun `a second past session shows its receipt after the first receipt read fails`() = runTest(dispatcher) {
        val plan = prepare()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.setCardioScreenLive(true)
        runCurrent()
        val startedAt = now - 86_400_000L - 2_472_000
        enter(vm, startedAt)
        failReceiptRead = true
        vm.savePastCardio()
        runCurrent()
        val first = repo.history.cardioSessionsFlow.first().single()
        assertNull(vm.finisher.receipt.value)
        assertNull(vm.pastCardio.value)
        assertFalse(vm.pastCardioSaveFailed.value)
        assertEquals(first.id, handle.get<Long>("day_past_cardio_receipt_id"))
        assertEquals("A", vm.uiState.value.viewDayId)
        assertNull(vm.uiState.value.cardio?.loggedSessionId)

        failReceiptRead = false
        enter(vm, startedAt - 3_600_000L)
        vm.changePastCardio(requireNotNull(vm.pastCardio.value).copy(metresText = "9000", note = "Earlier run"))
        vm.savePastCardio()
        runCurrent()

        val sessions = repo.history.cardioSessionsFlow.first()
        assertEquals(2, sessions.size)
        assertEquals(first, sessions.single { it.id == first.id })
        val second = sessions.single { it.id != first.id }
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertTrue(receipt.cardio)
        assertEquals(second.id, receipt.sessionId)
        assertEquals("Earlier run", receipt.note)
        assertEquals(9000, receipt.cardioRows.single().intervals.single().metres)
        assertNull(handle.get<Long>("day_past_cardio_receipt_id"))
        assertNull(vm.pastCardio.value)
        assertFalse(vm.pastCardioSaveFailed.value)
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
        assertEquals(listOf(first.id, second.id), published)
    }

    @Test fun `a committed manual session recovers its receipt after restoration`() = runTest(dispatcher) {
        prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        enter(first)
        failReceiptRead = true
        first.savePastCardio()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        assertNull(first.pastCardio.value)
        assertNull(first.finisher.receipt.value)
        first.viewModelScope.cancel()
        failReceiptRead = false
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = readyVm(restoredHandle)
        assertNull(restored.pastCardio.value)
        assertEquals(session.id, restored.finisher.receipt.value?.sessionId)
        assertEquals("Easy", restored.finisher.receipt.value?.note)
        assertNull(restoredHandle.get<Long>("day_past_cardio_receipt_id"))
        assertEquals(listOf(session), repo.history.cardioSessionsFlow.first())
        assertEquals(listOf(session.id), published)
    }

    @Test fun `completed cardio uses the entered activity on a mixed day`() = runTest(dispatcher) {
        val plan = prepare(mixed = true)
        settings.setDistanceUnit(DistanceUnit.KM)
        repo.startRun(plan, "A")
        val vm = readyVm()
        enter(vm)
        vm.changePastCardio(requireNotNull(vm.pastCardio.value).copy(activity = CardioActivity.OUTDOOR_RIDE))
        vm.savePastCardio()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        val cardio = requireNotNull(vm.uiState.value.cardio)
        assertEquals("OUTDOOR_RIDE", session.activity)
        assertEquals(session.id, cardio.loggedSessionId)
        assertEquals(DistanceUnit.KM, cardio.distanceUnit)
        assertEquals(CardioActivity.OUTDOOR_RIDE, cardio.loggedActivity)
        assertEquals(CardioActivity.ROAD_RUN, cardio.block?.activity)
        assertEquals("Outdoor ride", cardio.label)
        assertEquals("Outdoor ride", vm.uiState.value.pages.last().title)
        assertEquals(repo.history.cardioSetsFor(session.id).map { it.spec }, cardio.block?.sets)
        assertEquals(CardioActivity.ROAD_RUN, repo.plans.programFor(plan).first().days.first().cardioBlock?.activity)
        assertNull(vm.finisher.receipt.value)
        assertEquals("A", db.planDao().plan(plan)?.suggestedDayId)
        vm.completeDay()
        runCurrent()
        assertEquals(CardioActivity.OUTDOOR_RIDE, vm.finisher.receipt.value?.activity)
    }

    @Test fun `a failed save keeps the draft and retry commits once with a trimmed note`() = runTest(dispatcher) {
        prepare()
        val vm = readyVm()
        enter(vm)
        vm.changePastCardio(requireNotNull(vm.pastCardio.value).copy(note = " Easy "))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cardio BEFORE INSERT ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")
        vm.savePastCardio()
        runCurrent()
        assertTrue(vm.pastCardioSaveFailed.value)
        assertNotNull(vm.pastCardio.value)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cardio")
        vm.savePastCardio()
        runCurrent()
        assertEquals("Easy", repo.history.cardioSessionsFlow.first().single().note)
        assertEquals("Easy", vm.finisher.receipt.value?.note)
        assertFalse(vm.pastCardioSaveFailed.value)
    }

    @Test fun `the repository trims and caps a cardio note before inserting`() = runTest(dispatcher) {
        prepare()
        val vm = readyVm()
        enter(vm)
        vm.savePastCardio()
        runCurrent()
        val session = repo.history.cardioSessionsFlow.first().single()
        val id = repo.logCardioSession(session.copy(
            id = 0, startedAt = session.startedAt - 86_400_000, completedAt = session.completedAt - 86_400_000,
            note = "  " + "x".repeat(NOTE_MAX_LENGTH + 10) + "  ",
        ), repo.history.cardioSetsFor(session.id))
        assertEquals("x".repeat(NOTE_MAX_LENGTH), repo.history.cardioSession(id)?.note)
    }

    @Test fun `the draft and its originating day survive a saved state parcel`() = runTest(dispatcher) {
        val plan = prepare()
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        enter(first)
        first.changePastCardio(requireNotNull(first.pastCardio.value).copy(note = "x".repeat(NOTE_MAX_LENGTH + 1)))
        val draft = requireNotNull(first.pastCardio.value)
        assertEquals(NOTE_MAX_LENGTH, draft.note.length)
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeParcelable(draft, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            requireNotNull(parcel.readParcelable<CardioEntryDraft>(CardioEntryDraft::class.java.classLoader))
        } finally { parcel.recycle() }
        first.viewModelScope.cancel()
        val vm = readyVm(SavedStateHandle(mapOf(
            "day_past_cardio_draft" to restored,
            "day_past_cardio_day" to DaySelection(plan, "A"),
        )))
        assertEquals(draft, vm.pastCardio.value)
        vm.selectDay("B")
        runCurrent()
        vm.savePastCardio()
        runCurrent()
        assertEquals("A", repo.history.cardioSessionsFlow.first().single().dayId)
    }

    @Test fun `an out of order manual finish leaves the suggestion unchanged`() = runTest(dispatcher) {
        val plan = prepare()
        db.planDao().updateSuggestedDay(plan, "B")
        val vm = readyVm()
        vm.selectDay("A")
        runCurrent()
        enter(vm)
        vm.savePastCardio()
        runCurrent()
        assertEquals("B", db.planDao().plan(plan)?.suggestedDayId)
        assertEquals(1, repo.history.cardioSessionsFlow.first().size)
    }

    @Test fun `entry duration sums timed ends and defaults when a set ends by distance`() = runTest(dispatcher) {
        val plan = prepare()
        val vm = readyVm()
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
            CardioSetSpec(CardioRole.WORK, Measure.Time(120), Measure.Distance(400)),
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
        )))
        runCurrent()
        vm.openPastCardio()
        assertEquals(540, vm.pastCardio.value?.seconds)
        vm.dismissPastCardio()
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105)),
        )))
        runCurrent()
        vm.openPastCardio()
        assertEquals(1800, vm.pastCardio.value?.seconds)
        assertEquals(CardioActivity.TRACK_RUN, vm.pastCardio.value?.activity)
    }

    @Test fun `opening defaults to the block time and is refused during execution`() = runTest(dispatcher) {
        prepare()
        val vm = readyVm()
        vm.openPastCardio()
        val draft = requireNotNull(vm.pastCardio.value)
        assertEquals(2700, draft.seconds)
        assertEquals(0L, draft.startedAt % 60_000L)
        assertEquals(CardioActivity.ROAD_RUN, draft.activity)
        vm.dismissPastCardio()
        vm.startCardio()
        runCurrent()
        vm.openPastCardio()
        assertNull(vm.pastCardio.value)
    }
}
