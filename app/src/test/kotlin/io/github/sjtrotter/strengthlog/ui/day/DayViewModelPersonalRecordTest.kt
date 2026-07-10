package io.github.sjtrotter.strengthlog.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.ImportedSession
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.transfer.health.SessionPublisher
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
 * The performance-profile Phase 1 "Best" chip end to end: [DayViewModel]
 * batches [TrackerRepository.personalRecords] for a whole day's exercises
 * into [ExerciseCardState.personalRecordDisplay], suppressing it when it
 * would just repeat the "last time" chip. Kept in its own file (not
 * [DayViewModelLastPerformedTest]) for the same diff-isolation reason that
 * file documents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelPersonalRecordTest {

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
            File.createTempFile("day-vm-personal-record-settings", ".preferences_pb")
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
        DayViewModel(repo, SessionPublisher.NoOp, handle).also { vms += it }

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

    private fun session(completedAt: Long, dayTitle: String = "Test") =
        WorkoutSessionEntity(id = 0, dayId = "A", dayTitle = dayTitle, startedAt = null, completedAt = completedAt, bodyweightLb = 180)

    private fun set(exerciseId: String, name: String, kind: SetKind, weightLb: Double, reps: Int) =
        SessionSetEntity(
            id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
            setIndex = 0, kind = kind.name, weightLb = weightLb, reps = reps, done = true,
        )

    @Test
    fun cardHasNoBestChipBeforeAnySessionIsCompleted() = runVmTest {
        insertSingleDayProgram()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val squatId = slotId("bb_back_squat")
        assertNull(vm.uiState.value.exercises.first { it.programExerciseId == squatId }.personalRecordDisplay)
        collect.cancel()
    }

    @Test
    fun cardShowsTheBestChipWhenTheRecordOutweighsTheLastTimeChip() = runVmTest {
        insertSingleDayProgram()
        // An older, heavier session sets the record; a newer, lighter one is
        // the most recent performance — the two chips must differ.
        repo.importSessionHistory(
            listOf(
                ImportedSession(
                    session(1_000L, "Older — heavy"),
                    listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, 5)),
                ),
                ImportedSession(
                    session(2_000L, "Newer — lighter"),
                    listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 225.0, 5)),
                ),
            ),
            newCustomExercises = emptyList(),
        )
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val squatId = slotId("bb_back_squat")
        val card = vm.uiState.value.exercises.first { it.programExerciseId == squatId }
        assertEquals("225×5", card.lastTimeDisplay)
        assertEquals("245×5", card.personalRecordDisplay)
        collect.cancel()
    }

    @Test
    fun cardHidesTheBestChipWhenItWouldRepeatTheLastTimeChip() = runVmTest {
        insertSingleDayProgram()
        // The only session on record is both the record AND the last
        // performance — showing "245×5" twice would be redundant.
        repo.importSessionHistory(
            listOf(
                ImportedSession(
                    session(1_000L),
                    listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, 5)),
                ),
            ),
            newCustomExercises = emptyList(),
        )
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val squatId = slotId("bb_back_squat")
        val card = vm.uiState.value.exercises.first { it.programExerciseId == squatId }
        assertEquals("245×5", card.lastTimeDisplay)
        assertNull(card.personalRecordDisplay)
        collect.cancel()
    }
}
