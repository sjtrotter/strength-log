package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.DayRoute
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WorkoutSetRouteTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: StrengthDatabase
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
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), SettingsStore(preferences))
        runBlocking {
            repo.replaceProgram(Program(listOf(ProgramDay("A", "Strength", "", listOf(ProgramExercise("bb_back_squat"))))))
        }
    }

    @After fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        storeScope.cancel()
        db.close()
    }

    @Test fun flagOnShowsTheSetScreenWithoutThePagerIndicator() {
        show(WorkoutFlags(true))
        rule.onNode(hasText("Log", substring = true)).assertExists()
        rule.onAllNodesWithContentDescription("Barbell Back Squat, not started").assertCountEquals(0)
        rule.onNodeWithText("All sets").assertExists()
    }

    @Test fun flagOffKeepsThePager() {
        show(WorkoutFlags())
        rule.onAllNodesWithContentDescription("Barbell Back Squat, not started").assertCountEquals(1)
        rule.onNodeWithTag("workout-action-filled").assertDoesNotExist()
    }

    private fun show(flags: WorkoutFlags) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.runOnUiThread {
            viewModel = DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo), SavedStateHandle(),
                TestClock, SilentAlarm, flags = flags)
        }
        rule.setContent { AppTheme {
            DayRoute(onCreateExercise = {}, onSetUpProgram = {}, onOpenRestTimer = {}, onFinishSession = {}, viewModel = viewModel)
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
