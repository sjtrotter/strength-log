package cloud.trotter.log.strength.sync

import android.content.Context
import android.os.Parcel
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.cardio.CardioExecution
import cloud.trotter.log.strength.domain.cardio.Clocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.CardioRun
import cloud.trotter.log.strength.ui.day.CardioTemplate
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.day.SavedCardioResult
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
class TodaySnapshotCardioDraftTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var syncStore: WearSyncStore
    private lateinit var share: ShareCardService
    private val drafts = CardioDraftSource()
    private val vms = mutableListOf<DayViewModel>()
    private val now = 1_800_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    private val road = CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-draft-settings", ".preferences_pb")
        })
        syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-draft-sync", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock = clock)
        share = ShareCardService(context, repo.history, settings = settings)
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun prepare(): Long {
        val plan = repo.plans.createAuthoredPlan("Running", "Run")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.replaceProgram(Program(listOf("A", "B").map {
            ProgramDay(it, "Run $it", "", emptyList(), cardioBlock = road)
        }))
        return plan
    }

    private suspend fun TestScope.readyVm(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val vm = DayViewModel(repo, SessionPublisher.NoOp, share, handle, FixedCardioClock(now), InertCardioAlarm,
            PhoneRestController(settings, NoOpRestRuntime), clock = clock, settings = settings, plans = repo.plans,
            catalog = repo.catalog, historyRepository = repo.history, cardioDraft = drafts)
        vms += vm
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun source() = TodaySnapshotSource(repo, flowOf(LocalDate.now(clock)), syncStore, syncStore,
        settings, repo.plans, repo.history, clock, drafts)

    private suspend fun Channel<WatchSnapshot>.awaitBlock(block: CardioBlock): WatchSnapshot {
        while (true) {
            val snapshot = receive()
            if (snapshot.cardioBlock?.toDomain() == block) return snapshot
        }
    }

    @Test fun phoneSwapRepublishesTheActivityAndSetsThatTheWatchStarts() = runTest(dispatcher) {
        val plan = prepare()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        val snapshots = Channel<WatchSnapshot>(Channel.UNLIMITED)
        backgroundScope.launch { source().snapshots.collect { if (it != null) snapshots.send(it) } }
        snapshots.awaitBlock(road)

        vm.swapCardio(CardioActivity.ELLIPTICAL, CardioTemplate.EASY)
        runCurrent()

        val expected = CardioBlock(CardioActivity.ELLIPTICAL,
            listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        val snapshot = snapshots.awaitBlock(expected)
        assertEquals("Elliptical", snapshot.cardio?.label)
        assertEquals(false, snapshot.cardio?.hard)
        assertNull(snapshot.cardio?.loggedStartedAt)
        assertNull(snapshot.runStartedAt)
        assertNull(handle.get<CardioRun>("day_cardio_run")?.startedAt)
        val received = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(snapshot))
        val wrist = CardioExecution(requireNotNull(received.cardioBlock).toDomain()).start(Clocks(now, 1000, 1))
        assertEquals(expected, wrist.block)
        assertTrue(wrist.running)
        val finished = wrist.reconcile(Clocks(now + 1_500_000, 1_501_000, 1))
        assertTrue(finished.complete)
        assertEquals("Elliptical", finished.summary().label)
        assertEquals(expected.sets.single(), finished.results.single().spec)
        assertEquals(CardioSetStatus.FINISHED, finished.results.single().status)
        val day = db.programDao().day(plan, "A")!!
        assertEquals(road, CardioBlockJson.decode(day.cardioBlockJson))
        assertNull(day.cardioWorkingJson)
        assertTrue(repo.history.cardioSessionsFlow.first().isEmpty())
    }

    @Test fun anActivitySwapPublishesSetsAddedBeforeStart() = runTest(dispatcher) {
        prepare()
        val vm = readyVm()
        vm.addCardioSet()
        runCurrent()
        vm.swapCardio(CardioActivity.ELLIPTICAL, null)
        runCurrent()

        val expected = CardioBlock(CardioActivity.ELLIPTICAL, listOf(road.sets.single(), road.sets.single(),
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120))))
        val snapshot = source().snapshots.first { it != null }!!
        assertEquals(expected, snapshot.cardioBlock?.toDomain())
        assertNull(snapshot.cardio?.loggedStartedAt)
        assertNull(vm.uiState.value.cardio?.run?.startedAt)
    }

    @Test fun restoredPhoneDraftPublishesTheSwap() = runTest(dispatcher) {
        prepare()
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        vm.swapCardio(CardioActivity.ELLIPTICAL, CardioTemplate.INTERVALS)
        runCurrent()
        val expected = requireNotNull(handle.get<CardioRun>("day_cardio_run")).block
        vm.viewModelScope.cancel()
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(CardioRun::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }
        readyVm(restored)
        assertEquals(expected, source().snapshots.first { it != null }?.cardioBlock?.toDomain())
    }

    @Test fun aPlanEditInvalidatesThePublishedDraftWithoutAnActiveViewModel() = runTest(dispatcher) {
        val plan = prepare()
        val vm = readyVm()
        vm.swapCardio(CardioActivity.ELLIPTICAL, null)
        runCurrent()
        vm.viewModelScope.cancel()
        val edited = road.copy(sets = listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        repo.setCardioWorking(plan, "A", edited)

        assertEquals(edited, source().snapshots.first { it != null }?.cardioBlock?.toDomain())
    }

    @Test fun aDraftForAnotherDayOrPlanDoesNotReplaceTodaysBlock() = runTest(dispatcher) {
        val plan = prepare()
        val json = CardioBlockJson.encode(road)
        val swapped = CardioBlockJson.encode(road.copy(activity = CardioActivity.ELLIPTICAL))
        val handle = SavedStateHandle(mapOf("day_cardio_run" to CardioRun(plan, "Running", "B", 1,
            json, swapped, true, null)))
        drafts.bind(handle.getStateFlow<CardioRun?>("day_cardio_run", null))
        assertEquals(road, source().snapshots.first { it != null }?.cardioBlock?.toDomain())

        handle["day_cardio_run"] = handle.get<CardioRun>("day_cardio_run")!!.copy(dayId = "A", planId = plan + 1)
        assertEquals(road, source().snapshots.first { it != null }?.cardioBlock?.toDomain())
    }

    @Test fun startedAndSkippedAndLoggedDraftsDoNotReplaceTodaysBlock() = runTest(dispatcher) {
        val plan = prepare()
        val json = CardioBlockJson.encode(road)
        val draft = CardioRun(plan, "Running", "A", 0, json,
            CardioBlockJson.encode(road.copy(activity = CardioActivity.ELLIPTICAL)), true, null)
        val handle = SavedStateHandle(mapOf("day_cardio_run" to draft.copy(startedAt = now)))
        drafts.bind(handle.getStateFlow<CardioRun?>("day_cardio_run", null))
        assertEquals(road, source().snapshots.first { it != null }?.cardioBlock?.toDomain())

        handle["day_cardio_run"] = draft.copy(results = listOf(SavedCardioResult("SKIPPED", 0)))
        assertEquals(road, source().snapshots.first { it != null }?.cardioBlock?.toDomain())

        handle["day_cardio_run"] = draft
        db.cardioSessionDao().insert(CardioSessionEntity(planId = plan, dayId = "A", mode = "LOW_IMPACT",
            hard = false, label = "Elliptical", startedAt = now - 1_200_000, completedAt = now,
            seconds = 1200, stepsCompleted = 1, activity = "ELLIPTICAL"))
        val snapshot = source().snapshots.first { it != null }!!
        assertEquals(road, snapshot.cardioBlock?.toDomain())
        assertEquals(now - 1_200_000, snapshot.cardio?.loggedStartedAt)
    }
}
