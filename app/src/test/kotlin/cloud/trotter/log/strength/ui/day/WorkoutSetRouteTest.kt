package cloud.trotter.log.strength.ui.day

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.DayRoute
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WorkoutSetRouteTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var viewModel: DayViewModel

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val preferences = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("set-screen-route", ".preferences_pb")
        }
        settings = SettingsStore(preferences)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        runBlocking {
            repo.replaceProgram(Program(listOf(ProgramDay("A", "Strength", "", listOf(ProgramExercise("bb_back_squat"))))))
        }
    }

    @After fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        storeScope.cancel()
        db.close()
    }

    @Test fun routeShowsTheSetScreen() {
        show()
        rule.onNode(hasText("Log", substring = true) and hasTestTag("workout-action-filled")).assertExists()
        rule.onAllNodesWithContentDescription("Barbell Back Squat, not started").assertCountEquals(0)
        rule.onNodeWithText("All sets").assertExists()
    }

    @Test fun sprintTimerReadsTheProvidedElapsedClock() {
        runBlocking {
            val id = repo.plans.createAuthoredPlan("Sprint", "Sprint")
            repo.plans.publishCreatedAuthoredPlan(id)
            repo.replaceProgram(Planner.plan(SetupAnswers(branch = PlanBranch.SPRINT, days = 2, minutes = 60,
                sprint = SprintAnswers(event = SprintEvent.M60, sprintedRecently = true))).program)
            repo.plans.setGoal(id, PlanGoal.Sprint(60))
        }
        var elapsed = 9_000_000L
        val clock = object : CardioClock {
            override fun wallMillis() = 1_800_000_000_000L + elapsed
            override fun elapsedRealtimeMillis() = elapsed
            override fun bootCount() = 7
        }
        show(clock)
        fun awaitRun(position: Int, running: Boolean) {
            rule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                rule.runOnIdle {
                    val run = viewModel.uiState.value.cardio?.run
                    run != null && run.position == position && run.running == running
                }
            }
        }
        rule.runOnIdle {
            viewModel.selectWorkoutLift("cardio")
            viewModel.startCardio()
        }
        awaitRun(0, true)
        rule.runOnIdle { elapsed += 600_000L }
        awaitRun(1, false)
        rule.runOnIdle { viewModel.startCardio() }
        awaitRun(1, true)
        rule.runOnIdle {
            assertEquals(elapsed, viewModel.elapsedClock())
            viewModel.armSprintTimer(requireNotNull(viewModel.uiState.value.cardio?.run).action())
        }
        rule.onNodeWithText("Beep in 5").assertExists()
        rule.runOnIdle { elapsed += 5_000L }
        rule.mainClock.advanceTimeBy(250L)
        rule.onNodeWithText("0.00").assertExists()
        rule.runOnIdle { elapsed += 4_880L }
        rule.mainClock.advanceTimeBy(32L)
        rule.onNodeWithText("4.88").assertExists()
    }

    private fun show(cardioClock: CardioClock = TestClock) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.runOnUiThread {
            viewModel = DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo.history, settings = settings), SavedStateHandle(),
                cardioClock, SilentAlarm, PhoneRestController(settings, NoOpRestRuntime), settings = settings,
                plans = repo.plans,
                catalog = repo.catalog,
                historyRepository = repo.history,
            )
        }
        rule.setContent { AppTheme {
            DayRoute(onCreateExercise = {}, onSetUpProgram = {}, onFinishSession = {}, viewModel = viewModel)
        } }
        rule.waitUntil(5_000) {
            rule.runOnIdle { viewModel.uiState.value.exercises.firstOrNull()?.rows?.isNotEmpty() == true }
        }
    }

    private object SilentAlarm : CardioAlarm {
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = Unit
        override fun cancel() = Unit
    }

    private object TestClock : CardioClock {
        override fun wallMillis() = 1_800_000_000_000L
        override fun elapsedRealtimeMillis() = 9_000_000L
        override fun bootCount() = 7
    }
}
