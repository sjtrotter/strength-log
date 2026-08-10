package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.LastPerformed
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/**
 * The gap a day switch used to leave open (#127). `flatMapLatest` cancels the
 * day the lifter left, but the cancellation publishes nothing — so until the
 * new day's history read came back from Room, `uiState` kept handing out the
 * *old* day's rows, every one of them still tickable. A tick landing there
 * writes to the day the lifter walked away from.
 *
 * Pinned by holding the history read open: with the read gated, the screen has
 * to be in its loading state with no rows at all, and the old day's must be
 * gone. Keep-screen-on is pinned in the same way and for the opposite reason —
 * it rides outside the day pipeline now, so flipping it while a read is in
 * flight must leave the day exactly where it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelDaySwitchTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: GatedRepository
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
            File.createTempFile("day-vm-day-switch-settings", ".preferences_pb")
        }
        repo = GatedRepository(
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

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(): DayViewModel =
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, SavedStateHandle(), kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), FixedCardioClock(), InertCardioAlarm).also { vms += it }

    private suspend fun insertTwoDayProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay("A", "Lower", "", listOf(ProgramExercise("bb_back_squat", isMain = true, targetSets = 6)), null),
                    ProgramDay("B", "Upper", "", listOf(ProgramExercise("bb_bench", isMain = true, targetSets = 6)), null),
                ),
            ),
        )
    }

    @Test
    fun switchingDaysDropsTheOldDaysRowsBeforeTheNewDaysArrive() = runVmTest {
        insertTwoDayProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals("A", vm.uiState.value.viewDayId)
        assertTrue(vm.uiState.value.exercises.isNotEmpty())

        // Hold day B's history read open, then switch.
        val gate = CompletableDeferred<Unit>()
        repo.historyGate = gate
        vm.selectDay("B")
        advanceUntilIdle()

        val waiting = vm.uiState.value
        assertTrue("the screen must say it is resolving", waiting.loading)
        assertFalse(waiting.hasProgram)
        assertNull("day A must not still be on screen", waiting.viewDayId)
        assertTrue("no tickable rows may survive the switch", waiting.exercises.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        val settled = vm.uiState.value
        assertEquals("B", settled.viewDayId)
        assertFalse(settled.loading)
        assertTrue(settled.exercises.isNotEmpty())
        collect.cancel()
    }

    @Test
    fun keepScreenOnDoesNotRestartTheDay() = runVmTest {
        insertTwoDayProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        // Gated: if the switch flowed through the day pipeline, the branch would
        // restart and stall on this read instead of staying on day A.
        repo.historyGate = CompletableDeferred()
        vm.setKeepScreenOn(true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.keepScreenOn)
        assertFalse(state.loading)
        assertEquals("A", state.viewDayId)
        assertTrue(state.exercises.isNotEmpty())
        collect.cancel()
    }

    /** The real repository with one read the test can hold open. */
    private class GatedRepository(
        db: StrengthDatabase,
        programDao: ProgramDao,
        sessionDao: SessionDao,
        customExerciseDao: CustomExerciseDao,
        settings: SettingsStore,
    ) : TrackerRepository(db, programDao, sessionDao, customExerciseDao, settings) {
        @Volatile
        var historyGate: CompletableDeferred<Unit>? = null

        override suspend fun lastPerformed(exerciseIds: List<String>): Map<String, LastPerformed> {
            historyGate?.await()
            return super.lastPerformed(exerciseIds)
        }
    }
}
