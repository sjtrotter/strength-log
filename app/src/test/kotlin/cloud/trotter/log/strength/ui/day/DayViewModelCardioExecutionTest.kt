package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        repo, SessionPublisher.NoOp, share, handle, MutableStateFlow(repo.currentDate()), clock, alarm,
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

    private suspend fun TestScope.runningVm(fiveK: Boolean = true): DayViewModel {
        prepare(fiveK)
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.startCardio()
        runCurrent()
        return vm
    }

    @Test
    fun derivesStepAndElapsedFromAnchors() = test {
        val vm = runningVm()
        clock.advance(423_000)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(423, vm.uiState.value.cardio?.elapsedSeconds)
        assertEquals("EASY", vm.uiState.value.cardio?.currentStepLabel)
        assertEquals(117, vm.uiState.value.cardio?.stepSecondsLeft)
    }

    @Test
    fun rebootHandsElapsedToTheWallClock() = test {
        val vm = runningVm()
        clock.wall += 610_000
        clock.elapsed = 2_000
        clock.boot += 1
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(610, vm.uiState.value.cardio?.elapsedSeconds)
        assertEquals("HARD", vm.uiState.value.cardio?.currentStepLabel)
    }

    @Test
    fun rebootWithLongerUptimeStillHandsElapsedToTheWallClock() = test {
        val vm = runningVm()
        clock.wall += 610_000
        clock.elapsed += 300_000_000
        clock.boot += 1
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(610, vm.uiState.value.cardio?.elapsedSeconds)
    }

    @Test
    fun wallClockJumpCannotHijackALiveMonotonicClock() = test {
        val vm = runningVm()
        clock.advance(120_000)
        clock.wall += 3_600_000
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(120, vm.uiState.value.cardio?.elapsedSeconds)
    }

    @Test
    fun stopAtExactlySixtySecondsLogs() = test {
        val vm = runningVm()
        clock.advance(60_000)
        vm.stopCardio()
        runCurrent()
        assertEquals(1, repo.cardioSessionsFlow.first().size)
        assertEquals(60, repo.cardioSessionsFlow.first().single().seconds)
    }

    @Test
    fun doubleTapStartReservesOneBlock() = test {
        val vm = runningVm()
        val startedWall = clock.wall
        clock.advance(30_000)
        vm.startCardio()
        runCurrent()
        clock.advance(90_000)
        vm.stopCardio()
        runCurrent()
        assertEquals(startedWall, repo.cardioSessionsFlow.first().single().startedAt)
    }

    @Test
    fun restorePastPlanEndIsOverrunAndArmsNoAlarm() = test {
        val vm = runningVm()
        vm.setCardioScreenLive(true)
        clock.advance(1_700_000)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(CardioPhase.OVERRUN, vm.uiState.value.cardio?.phase)
        assertEquals(1_700, vm.uiState.value.cardio?.elapsedSeconds)
        assertNull(alarm.identity)
    }

    @Test
    fun stopBeforeSixtySecondsDiscards() = test {
        val vm = runningVm()
        clock.advance(59_999)
        vm.stopCardio()
        runCurrent()
        assertEquals(emptyList<Any>(), repo.cardioSessionsFlow.first())
    }

    @Test
    fun stopWritesExactlyOneSessionWithFullyElapsedPrefix() = test {
        val vm = runningVm()
        clock.advance(665_000)
        vm.stopCardio()
        vm.stopCardio()
        runCurrent()
        val sessions = repo.cardioSessionsFlow.first()
        assertEquals(1, sessions.size)
        assertEquals(665, sessions.single().seconds)
        assertEquals(4, sessions.single().stepsCompleted)
        assertEquals("A", sessions.single().dayId)
    }

    @Test
    fun loggedLineComesBackFromHistoryWithoutSavedState() = test {
        val vm = runningVm(fiveK = false)
        clock.advance(72_000)
        vm.stopCardio()
        runCurrent()
        val restored = vm(SavedStateHandle())
        backgroundScope.launch { restored.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CardioPhase.SUGGESTION, restored.uiState.value.cardio?.phase)
        assertEquals(72, restored.uiState.value.cardio?.loggedSeconds)
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
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
            this.deadline = deadlineElapsedMillis
            this.identity = identity
            callback = onBoundary
        }
        override fun cancel() { deadline = null; identity = null; callback = null }
    }
}
