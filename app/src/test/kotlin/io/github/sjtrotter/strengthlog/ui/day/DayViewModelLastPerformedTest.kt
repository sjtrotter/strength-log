package io.github.sjtrotter.strengthlog.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The A1 bonus "last time" chip end to end: [DayViewModel] batches
 * [TrackerRepository.lastPerformed] for a whole day's exercises into
 * [ExerciseCardState.lastTimeDisplay]. Kept in its own file (not
 * [DayViewModelWiringTest]) so this PR's diff stays isolated from #11's
 * concurrent day-edit-sheet work on the same shared test file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelLastPerformedTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
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
            File.createTempFile("day-vm-last-performed-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): DayViewModel =
        DayViewModel(repo, handle).also { vms += it }

    private suspend fun insertSingleDayProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Test",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", isMain = true, targetSets = 6)),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(exerciseId: String): Long =
        repo.daySlotsFlow("A").first().first { it.exercise.exerciseId == exerciseId }.programExerciseId

    @Test
    fun cardHasNoLastTimeChipBeforeAnySessionIsCompleted() = runVmTest {
        insertSingleDayProgram()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val squatId = slotId("bb_back_squat")
        assertNull(vm.uiState.value.exercises.first { it.programExerciseId == squatId }.lastTimeDisplay)
        collect.cancel()
    }

    @Test
    fun cardShowsTheTopSetFromTheMostRecentlyCompletedSession() = runVmTest {
        insertSingleDayProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        // Pinned squat seed: RAMP 130/165/190/210, TOP 235x5, BACKOFF 175 (index 4
        // is TOP) — only the TOP set gets ticked before completing the day.
        vm.toggleDone(squatId, index = 4, checked = true, isSuperset = false)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        collect.cancel()

        // A fresh ViewModel (the real-world case: re-opening the app) picks up
        // the just-completed session on its first emission — this doesn't rely
        // on distinctUntilChanged letting a same-day re-select force a re-fetch,
        // which it wouldn't here (a one-day program resolves to "A" either way).
        val vm2 = newViewModel()
        val collect2 = launch { vm2.uiState.collect {} }
        advanceUntilIdle()

        val card = vm2.uiState.value.exercises.first { it.programExerciseId == squatId }
        assertEquals("235×5", card.lastTimeDisplay)
        collect2.cancel()
    }
}
