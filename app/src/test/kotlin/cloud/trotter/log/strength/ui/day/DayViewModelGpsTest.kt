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
import cloud.trotter.log.strength.domain.cardio.Fix
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.run.FakeForeground
import cloud.trotter.log.strength.run.FakeLocationSource
import cloud.trotter.log.strength.run.GpsNotice
import cloud.trotter.log.strength.run.LocationAccess
import cloud.trotter.log.strength.run.LocationAnswer
import cloud.trotter.log.strength.run.RunTracker
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import kotlin.math.PI
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
class DayViewModelGpsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val clock = FakeCardioClock()
    private val alarm = FakeCardioAlarm()
    private val vms = mutableListOf<DayViewModel>()
    private val source = FakeLocationSource()
    private val foreground = FakeForeground()
    private val trackers = mutableListOf<RunTracker>()
    private fun tracker() = RunTracker(source, foreground, clock, dispatcher).also { trackers += it }

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
        trackers.forEach { it.endAll() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = DayViewModel(
        repo, SessionPublisher.NoOp, share, handle, clock, alarm, PhoneRestController(settings, NoOpRestRuntime),
        settings = settings,
        plans = repo.plans,
        catalog = repo.catalog,
        historyRepository = repo.history,
        gps = tracker(),
    ).also { vms += it }

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

    private suspend fun prepareBlock(vararg sets: CardioSetSpec, activity: CardioActivity = CardioActivity.ROAD_RUN) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Running", "",
            listOf(ProgramExercise("bb_back_squat")),
            cardioBlock = CardioBlock(activity, sets.toList())))))
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

    private fun time(seconds: Int, role: CardioRole = CardioRole.WORK) = CardioSetSpec(role, Measure.Time(seconds))
    private fun DayViewModel.run() = requireNotNull(uiState.value.cardio?.run)
    private fun stamps(handle: SavedStateHandle) = handle.get<ArrayList<CardioGpsSet>>("day_cardio_gps_sets").orEmpty()
    private suspend fun rows(): List<CardioSetResult> = repo.history.cardioSetsFor(repo.history.cardioSessionsFlow.first().single().id)
    private suspend fun TestScope.fix(t: Long) {
        source.fixes.emit(Fix(clock.elapsed, 3.0 * t / (6_371_000.0 * PI / 180.0), 0.0, 5.0))
        runCurrent()
    }
    private suspend fun TestScope.fixes(first: Long, last: Long, keep: (Long) -> Boolean = { true }) {
        for (t in first..last) { tick(1000); if (keep(t)) fix(t) }
    }
    private suspend fun TestScope.begin(vm: DayViewModel, firstFix: Boolean = true) {
        vm.startCardio()
        runCurrent()
        if (firstFix) fix(0)
    }

    @Test fun `an open run logs its GPS total and reading without drafts`() = test {
        prepareBlock(time(1800))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        fixes(1, 1900)
        vm.stopCardio(vm.run().action())
        runCurrent()
        val row = rows().single()
        assertEquals(5700, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertEquals(5400 to 1800, row.metresAtEnd to row.secondsAtEnd)
        assertEquals(ReadingSource.GPS, row.readingSource)
        assertTrue(vm.uiState.value.cardio?.distanceRuns.orEmpty().all { it.drafts.isEmpty() })
        assertNull(handle.get<OpenSheet>("day_open_sheet"))
        assertEquals(1, foreground.stops)
    }

    @Test fun `indoor cardio never starts the tracker and still asks for distance`() = test {
        prepareBlock(time(1800), activity = CardioActivity.TREADMILL_RUN)
        val vm = readyVm()
        vm.startCardio()
        runCurrent()
        tick(200_000)
        vm.stopCardio(vm.run().action())
        runCurrent()
        assertEquals(0, foreground.starts)
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.single().drafts.isNotEmpty())
    }

    @Test fun `a disabled GPS setting never starts tracking`() = test {
        prepareBlock(time(1800))
        settings.setGpsDistance(false)
        val vm = readyVm()
        begin(vm, false)
        assertEquals(0, foreground.starts)
    }

    @Test fun `denial clears the ask turns GPS off and retains typed entry`() = test {
        source.access = LocationAccess.DENIED
        prepareBlock(time(1800))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm, false)
        assertEquals(vm.run().runId, handle.get<String>("day_gps_ask"))
        assertTrue(vm.uiState.value.cardio!!.gpsAsk)
        vm.answerLocation(LocationAnswer.DENIED)
        runCurrent()
        assertFalse(settings.gpsDistanceFlow.first())
        assertEquals(GpsNotice.OFF, vm.gpsNotice.value)
        assertNull(handle.get<String>("day_gps_ask"))
        tick(200_000)
        vm.stopCardio(vm.run().action())
        runCurrent()
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.single().drafts.isNotEmpty())
    }

    @Test fun `approximate permission turns GPS off with the precise notice`() = test {
        source.access = LocationAccess.APPROXIMATE
        prepareBlock(time(1800))
        val vm = readyVm()
        begin(vm, false)
        vm.answerLocation(LocationAnswer.APPROXIMATE)
        runCurrent()
        assertFalse(settings.gpsDistanceFlow.first())
        assertEquals(GpsNotice.NEEDS_PRECISE, vm.gpsNotice.value)
    }

    @Test fun `timed work and recovery keep stamps through end block logging`() = test {
        prepareBlock(time(300), time(120, CardioRole.RECOVERY), time(300))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        val id = vm.run().runId
        fixes(1, 430)
        assertEquals(listOf(CardioGpsSet(id, 0, 900, 900, 300, true, false),
            CardioGpsSet(id, 1, 360, 360, 120, true, false)), stamps(handle))
        vm.endCardioBlock()
        runCurrent()
        assertEquals(listOf(DistanceSource.GPS, DistanceSource.GPS, null), rows().map { it.distanceSource })
        assertEquals(CardioSetStatus.SKIPPED, rows()[2].status)
        assertTrue(stamps(handle).none { it.runId == id })
    }

    @Test fun `a causal boundary waits at zero and reads the next fix without an alarm loop`() = test {
        prepareBlock(time(300), time(300))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        val id = vm.run().runId
        fixes(1, 299)
        tick(1500)
        assertTrue(vm.run().results.isEmpty())
        assertEquals(0, vm.uiState.value.cardio!!.stepSecondsLeft)
        alarm.fire()
        runCurrent()
        assertNull(alarm.callback)
        tick(500)
        fix(301)
        tick(1)
        assertEquals(CardioGpsSet(id, 0, 895, 895, 300, true, false), stamps(handle).single())
        assertEquals(300, vm.run().results.single().seconds)
    }

    @Test fun `an expired fix wait preserves an unknown exact reading`() = test {
        prepareBlock(time(300), time(300))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        val id = vm.run().runId
        fixes(1, 299)
        tick(11_000)
        assertEquals(CardioGpsSet(id, 0, 888, null, null, true, false), stamps(handle).single())
        vm.endCardioBlock()
        runCurrent()
        val row = rows()[0]
        assertEquals(888, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertEquals(300, row.secondsAtEnd)
        assertNull(row.metresAtEnd)
    }

    @Test fun `turning GPS off mid set stops immediately and leaves no stamp`() = test {
        prepareBlock(time(1800))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        fixes(1, 120)
        settings.setGpsDistance(false)
        runCurrent()
        assertEquals(1, foreground.stops)
        tick(80_000)
        vm.stopCardio(vm.run().action())
        runCurrent()
        assertTrue(stamps(handle).isEmpty())
        assertTrue(vm.uiState.value.cardio!!.distanceRuns.single().drafts.isNotEmpty())
    }

    @Test fun `an idle hidden run stops and the next Start begins again`() = test {
        prepareBlock(time(60), time(60))
        val vm = readyVm()
        begin(vm)
        fixes(1, 61)
        assertEquals(1, vm.run().results.size)
        vm.setCardioScreenLive(false)
        runCurrent()
        assertEquals(1, foreground.stops)
        vm.setCardioScreenLive(true)
        vm.startCardio()
        runCurrent()
        assertEquals(2, foreground.starts)
    }

    @Test fun `warm up follows the selected cardio page only`() = test {
        prepareBlock(time(1800))
        val vm = readyVm()
        assertEquals(0, source.fixes.subscriptionCount.value)
        vm.selectWorkoutLift("cardio")
        runCurrent()
        assertEquals(1, source.fixes.subscriptionCount.value)
        assertEquals(0, foreground.starts)
    }

    @Test fun `a missing planned reading prefills GPS and editing explicitly marks typed`() = test {
        prepareBlock(time(1800))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        val id = vm.run().runId
        fixes(1, 1900) { it !in 1796L..1810L }
        vm.stopCardio(vm.run().action())
        runCurrent()
        assertEquals(OpenSheet.Kind.CARDIO_DISTANCE, handle.get<OpenSheet>("day_open_sheet")?.kind)
        assertEquals("5697", vm.uiState.value.cardio!!.distanceRuns.single().drafts.single().text)
        assertEquals(CardioGpsSet(id, 0, 5697, null, null, true, false), vm.uiState.value.cardio!!.gpsSets.single())
        vm.changeCardioDistance("3.11")
        assertTrue(stamps(handle).single().typed)
    }

    @Test fun `a restored running set starts a fresh partial track`() = test {
        prepareBlock(time(1800))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        begin(first)
        fixes(1, 60)
        first.setCardioScreenLive(false)
        first.viewModelScope.cancel()
        trackers.forEach { it.endAll() }
        foreground.starts = 0
        val restored = readyVm(parcelHandle(handle))
        assertEquals(1, foreground.starts)
        fixes(61, 1900)
        restored.stopCardio(restored.run().action())
        runCurrent()
        val cardio = restored.uiState.value.cardio!!
        val stamp = cardio.gpsSets.single()
        assertFalse(stamp.complete)
        assertEquals(stamp.metres.toString(), cardio.distanceRuns.single().drafts.single().text)
    }

    @Test fun `a live run projects its set distance and steady pace`() = test {
        prepareBlock(time(1800))
        val vm = readyVm()
        begin(vm)
        fixes(1, 120)
        tick(1)
        val live = requireNotNull(vm.uiState.value.cardio!!.gps)
        assertEquals(GpsShown.LIVE, live.shown)
        assertEquals(360, live.metres)
        assertEquals(3.0, requireNotNull(live.metresPerSecond), 0.01)
    }

    @Test fun `the existing tick recovers after location returns mid run`() = test {
        prepareBlock(time(1800))
        val vm = readyVm()
        begin(vm)

        source.access = LocationAccess.LOCATION_OFF
        tick(1000)
        assertEquals(GpsShown.LOCATION_OFF, vm.uiState.value.cardio?.gps?.shown)

        source.access = LocationAccess.PRECISE
        tick(1000)
        fix(2)
        tick(1)
        assertEquals(GpsShown.LIVE, vm.uiState.value.cardio?.gps?.shown)
        assertEquals(1, foreground.starts)
        assertEquals(0, foreground.stops)
    }

    @Test fun `a restored run waits for its disabled GPS setting before tracking`() = test {
        prepareBlock(time(1800))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        begin(first)
        first.viewModelScope.cancel()
        trackers.forEach { it.endAll() }
        foreground.starts = 0
        settings.setGpsDistance(false)

        val restored = vm(parcelHandle(handle))
        restored.setCardioScreenLive(true)
        assertEquals(0, foreground.starts)
        backgroundScope.launch { restored.uiState.collect {} }
        runCurrent()
        assertEquals(0, foreground.starts)
        assertEquals(0, source.fixes.subscriptionCount.value)
    }

    @Test fun `a restored run begins when its enabled GPS setting loads`() = test {
        prepareBlock(time(1800))
        val handle = SavedStateHandle()
        val first = readyVm(handle)
        begin(first)
        first.viewModelScope.cancel()
        trackers.forEach { it.endAll() }
        foreground.starts = 0

        val restored = vm(parcelHandle(handle))
        restored.setCardioScreenLive(true)
        assertEquals(0, foreground.starts)
        backgroundScope.launch { restored.uiState.collect {} }
        runCurrent()
        assertEquals(1, foreground.starts)
        assertEquals(1, source.fixes.subscriptionCount.value)
    }

    @Test fun `a reached tap inside the GPS margin remains manual and unstamped`() = test {
        prepareBlock(CardioSetSpec(CardioRole.WORK, Measure.Distance(1000)))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        fixes(1, 340)
        vm.reachCardioTarget(vm.run().action())
        runCurrent()
        val row = rows().single()
        assertEquals(1000, row.metres)
        assertEquals(DistanceSource.MANUAL, row.distanceSource)
        assertEquals(1000 to 340, row.metresAtEnd to row.secondsAtEnd)
        assertNull(row.readingSource)
        assertTrue(stamps(handle).isEmpty())
    }

    @Test fun `end block keeps a partial stopped total as a draft and skips the rest`() = test {
        prepareBlock(time(600), time(300))
        val vm = readyVm()
        begin(vm, false)
        fixes(1, 200) { it >= 20 }
        vm.endCardioBlock()
        runCurrent()
        val rows = rows()
        assertEquals(CardioSetStatus.SHORTENED, rows[0].status)
        assertNull(rows[0].metres)
        assertEquals("540", vm.uiState.value.cardio!!.distanceRuns.single().drafts.single().text)
        assertEquals(CardioSetStatus.SKIPPED, rows[1].status)
    }

    @Test fun `a causal gap crossing the boundary is partial and prefills its total`() = test {
        prepareBlock(time(300), time(300))
        val handle = SavedStateHandle()
        val vm = readyVm(handle)
        begin(vm)
        val id = vm.run().runId
        fixes(1, 230)
        tick(70_500)
        assertTrue(vm.run().results.isEmpty())
        tick(500)
        fix(301)
        tick(1)
        assertEquals(CardioGpsSet(id, 0, 899, null, null, false, false), stamps(handle).single())
        assertEquals("899", vm.run().drafts.single().text)
    }
}
