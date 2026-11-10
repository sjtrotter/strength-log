package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.HistoryRepository
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.storeTestRows
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
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
 * [HistoryRepository.lastPerformed] for a whole day's exercises into
 * [ExerciseCardState.lastTimeDisplay].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelLastPerformedTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
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
            File.createTempFile("day-vm-last-performed-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        shareCardService = ShareCardService(context, repo.history, settings = settings)
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
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, handle, FixedCardioClock(), InertCardioAlarm, PhoneRestController(settings, NoOpRestRuntime), settings = settings, plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history).also { vms += it }

    private suspend fun insertSingleDayProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Test",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)),
                    ),
                ),
            ),
        )
        db.storeTestRows("A", slotId("bb_back_squat"), Slot.MAIN, listOf(
            LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
            LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
            LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        ))
    }

    private suspend fun slotId(exerciseId: String): Long =
        repo.plans.daySlotsFlow("A").first().first { it.exercise.exerciseId == exerciseId }.programExerciseId

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
    fun `the lift sheet's last time lists every set`() = runVmTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Bench", "", listOf(
            ProgramExercise("bb_bench", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(225.0, 5))),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val benchId = slotId("bb_bench")
        val started = repo.startRun(plan, "A") as RunStartResult.Started
        db.storeTestRows("A", benchId, Slot.MAIN,
            listOf(5, 5, 4).mapIndexed { index, reps ->
                LoggedSet(225.0, reps, SetKind.WORK, done = true, plannedIndex = index)
            }, started.runId)
        repo.finishRun(started.runId)

        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val card = vm.uiState.value.exercises.single()
        assertEquals("225 lb × 5, 5, 4", card.lastTimeDisplay)
        assertNull(card.topSetComparison)
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
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(squatId, index = 4, checked = true, slot = Slot.MAIN)
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
        assertEquals("235 lb × 5", card.lastTimeDisplay)
        collect2.cancel()
    }

    @Test
    fun cardComparesTheSeededTopSetWithTheMostRecentlyCompletedSession() = runVmTest {
        insertSingleDayProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        assertEquals(
            "FIRST LOG",
            vm.uiState.value.exercises.first { it.programExerciseId == squatId }.topSetComparison,
        )

        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(squatId, index = 4, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        collect.cancel()

        val vm2 = newViewModel()
        val collect2 = launch { vm2.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(
            "MATCHED",
            vm2.uiState.value.exercises.first { it.programExerciseId == squatId }.topSetComparison,
        )
        collect2.cancel()
    }
}
