package cloud.trotter.log.strength.ui.day

import android.content.Context
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.DayRoute
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DayRouteNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var viewModel: DayViewModel
    private lateinit var navController: NavHostController
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var view: View
    private val alarm = RecordingAlarm()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("day-route", ".preferences_pb")
        }
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), SettingsStore(dataStore))
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        db.close()
        storeScope.cancel()
    }

    @Test
    fun systemBackPopsDuringCardio() = assertBackPops(timedReps = false, systemBack = true)

    @Test
    fun topBarBackPopsDuringCardio() = assertBackPops(timedReps = false, systemBack = false)

    @Test
    fun systemBackPopsDuringTimedReps() = assertBackPops(timedReps = true, systemBack = true)

    @Test
    fun topBarBackPopsDuringTimedReps() = assertBackPops(timedReps = true, systemBack = false)

    private fun assertBackPops(timedReps: Boolean, systemBack: Boolean) {
        runBlocking {
            repo.replaceProgram(Program(listOf(ProgramDay(
                "A", "Workout", "",
                exercises = if (timedReps) listOf(ProgramExercise(
                    "pushup", targetSets = 2, prescription = Prescription(PrescribedTarget.RepsInTime(120, 40)),
                )) else emptyList(),
                cardio = null,
                cardioBlock = if (timedReps) null else CardioBlock(CardioActivity.TRACK_RUN, listOf(
                    CardioSetSpec(CardioRole.WORK, Measure.Time(120)),
                )),
            ))))
        }
        rule.runOnUiThread {
            val context = ApplicationProvider.getApplicationContext<Context>()
            viewModel = DayViewModel(
                repo, SessionPublisher.NoOp, ShareCardService(context, repo), SavedStateHandle(),
                MutableStateFlow(repo.currentDate()), FixedClock, alarm,
            )
        }
        rule.setContent {
            AppTheme {
                navController = rememberNavController()
                backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                NavHost(navController, startDestination = Routes.TODAY) {
                    composable(Routes.TODAY) { Text("Today") }
                    composable(Routes.DAY) {
                        view = LocalView.current
                        DayRoute(
                            onCreateExercise = {}, onSetUpProgram = {}, onOpenRestTimer = {},
                            onFinishSession = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
        rule.runOnIdle { navController.navigate(Routes.DAY) }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                val state = viewModel.uiState.value
                !state.loading && if (timedReps) state.exercises.firstOrNull()?.rows?.isNotEmpty() == true
                else state.cardio?.block != null
            }
        }
        rule.runOnIdle {
            if (timedReps) {
                val card = viewModel.uiState.value.exercises.single()
                viewModel.startTimedRep(card.programExerciseId, card.focusIndex)
            } else viewModel.startCardio()
        }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                val state = viewModel.uiState.value
                if (timedReps) state.timedRepRunning else state.cardio?.run?.running == true
            }
        }
        rule.runOnIdle {
            assertTrue(viewModel.executionActive)
            assertNull(viewModel.sessionReceipt.value)
            assertNull(viewModel.uiState.value.openSheet)
            assertTrue(view.keepScreenOn)
            assertNotNull(alarm.deadline)
        }

        if (systemBack) rule.runOnUiThread { backDispatcher.onBackPressed() }
        else rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals(Routes.TODAY, navController.currentDestination?.route)
            assertFalse(view.keepScreenOn)
            assertNull(alarm.deadline)
        }
    }

    private object FixedClock : CardioClock {
        override fun wallMillis() = 1_800_000_000_000L
        override fun elapsedRealtimeMillis() = 9_000_000L
        override fun bootCount() = 7
    }

    private class RecordingAlarm : CardioAlarm {
        var deadline: Long? = null
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
            deadline = deadlineElapsedMillis
        }
        override fun cancel() { deadline = null }
        override fun buzz() = Unit
    }
}
