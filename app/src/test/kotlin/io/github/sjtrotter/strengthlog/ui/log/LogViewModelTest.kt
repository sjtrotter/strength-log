package io.github.sjtrotter.strengthlog.ui.log

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
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.transfer.health.HealthConnectClientProvider
import io.github.sjtrotter.strengthlog.transfer.health.HealthConnectReader
import java.io.File
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
 * [LogViewModel] wiring (issue #14): the reverse-chronological list shape, and
 * the lazy expand-fetches-once-then-caches behavior. The pure grouping/format
 * logic itself is [LogScreenBuilderTest]'s job — this is the Robolectric +
 * in-memory Room seam, same pattern as [io.github.sjtrotter.strengthlog.ui.day
 * .DayViewModelWiringTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<LogViewModel>()

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
            File.createTempFile("log-vm-settings", ".preferences_pb")
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

    // No Health Connect provider under Robolectric — a null-provider reader makes
    // every HC read a degrade-safe empty, isolating these tests to own-history.
    private val healthReader = HealthConnectReader(HealthConnectClientProvider { null }, ownPackageName = "test")

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): LogViewModel =
        LogViewModel(repo, healthReader, handle).also { vms += it }

    private fun session(dayId: String, dayTitle: String, completedAt: Long) =
        WorkoutSessionEntity(id = 0, dayId = dayId, dayTitle = dayTitle, startedAt = null, completedAt = completedAt, bodyweightLb = 180)

    private fun set(exerciseId: String, name: String) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 100.0, reps = 8, done = true,
    )

    private suspend fun seedTwoSessions() {
        repo.importSessionHistory(
            listOf(
                ImportedSession(session("A", "Lower", 1_000L), listOf(set("bb_back_squat", "Barbell Back Squat"))),
                ImportedSession(session("B", "Upper", 2_000L), listOf(set("bb_bench", "Barbell Bench Press"), set("bb_row", "Barbell Row"))),
            ),
            newCustomExercises = emptyList(),
        )
    }

    @Test
    fun listsSessionsNewestFirstWithNoGroupsUntilExpanded() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val sessions = vm.uiState.value.sessions
        assertEquals(listOf("Upper", "Lower"), sessions.map { it.dayTitle })
        assertEquals(2, sessions[0].setCount)
        assertEquals(1, sessions[1].setCount)
        assertTrue(sessions.none { it.expanded })
        assertTrue(sessions.all { it.exerciseGroups == null })
        collect.cancel()
    }

    @Test
    fun expandingASessionFetchesAndGroupsItsSets() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val upperId = vm.uiState.value.sessions.first { it.dayTitle == "Upper" }.sessionId

        vm.toggleExpanded(upperId)
        advanceUntilIdle()

        val expanded = vm.uiState.value.sessions.first { it.sessionId == upperId }
        assertTrue(expanded.expanded)
        assertEquals(listOf("Barbell Bench Press", "Barbell Row"), expanded.exerciseGroups?.map { it.exerciseName })
        collect.cancel()
    }

    @Test
    fun togglingAnExpandedSessionAgainCollapsesIt() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val sessionId = vm.uiState.value.sessions.first().sessionId

        vm.toggleExpanded(sessionId)
        advanceUntilIdle()
        vm.toggleExpanded(sessionId)
        advanceUntilIdle()

        val item = vm.uiState.value.sessions.first { it.sessionId == sessionId }
        assertFalse(item.expanded)
        assertNull(item.exerciseGroups)
        collect.cancel()
    }

    @Test
    fun expandingASecondSessionCollapsesTheFirst() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val (first, second) = vm.uiState.value.sessions.map { it.sessionId }

        vm.toggleExpanded(first)
        advanceUntilIdle()
        vm.toggleExpanded(second)
        advanceUntilIdle()

        val sessions = vm.uiState.value.sessions.associateBy { it.sessionId }
        assertFalse(sessions.getValue(first).expanded)
        assertTrue(sessions.getValue(second).expanded)
        collect.cancel()
    }
}
