package cloud.trotter.log.strength.playtest

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.DayRoute
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
class InteractionSettleTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun immediateAllSetsTapUsesTheDestinationAfterDelayedStorageCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).build()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
        val release = CompletableDeferred<Unit>()
        try {
            val preferences = PreferenceDataStoreFactory.create(scope = scope) {
                temporary.newFile("settings.preferences_pb")
            }
            val entered = CompletableDeferred<Unit>()
            val nextWrite = AtomicReference<CompletableDeferred<Unit>?>(null)
            val settings = SettingsStore(object : DataStore<Preferences> {
                override val data = preferences.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    nextWrite.getAndSet(null)?.let { gate ->
                        entered.complete(Unit)
                        gate.await()
                    }
                    return preferences.updateData(transform)
                }
            })
            val time = PlaytestTime()
            val repo = TrackerRepository(database, database.programDao(), database.sessionDao(),
                database.customExerciseDao(), settings, time.clock)
            waitForStorage(10_000) {
                repo.replaceProgram(Program(listOf(ProgramDay("A", "Workout", "", listOf(
                    ProgramExercise("bb_back_squat", targetSets = 1),
                    ProgramExercise("db_bench", targetSets = 1),
                )))))
                settings.setPhoneRestTimerEnabled(false)
            }
            compose.mainClock.autoAdvance = false
            lateinit var vm: DayViewModel
            compose.runOnUiThread {
                vm = DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo.history, settings = settings),
                    SavedStateHandle(), time.cardioClock, InertCardioAlarm, PhoneRestController(settings, NoOpRestRuntime),
                    clock = time.clock, settings = settings, plans = repo.plans, catalog = repo.catalog,
                    historyRepository = repo.history)
            }
            compose.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    AppTheme {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = "workout") {
                            composable("workout") { entry ->
                                val model = remember(entry) {
                                    entry.viewModelStore.put("workout", vm)
                                    vm
                                }
                                DayRoute({}, {}, {}, viewModel = model)
                            }
                        }
                    }
                }
            }
            fun await(text: String) {
                val deadline = StepDeadline(10_000)
                deadline.run(compose) {
                    Selectors(compose, compose.density.density).await(
                        Selector(Json.parseToJsonElement("""{"text":"$text"}""").jsonObject), deadline)
                }
            }
            await("All sets")
            compose.runOnIdle { vm.startWorkout() }
            compose.waitUntil(5_000) { compose.runOnIdle { vm.uiState.value.runOpen } }
            compose.runOnUiThread { vm.toggleDone(vm.uiState.value.exercises.first().programExerciseId, 0, true, Slot.MAIN) }
            await("Next: DB Bench Press")
            val before = time.clock.instant()
            nextWrite.set(release)
            compose.onNodeWithText("Next: DB Bench Press").performClick()
            val deadline = StepDeadline(10_000)
            var frames = 0
            deadline.run(compose) {
                compose.settleSemantics(deadline, awaitPendingWork = {
                    assertTrue(frames >= 3)
                    assertFalse(release.isCompleted)
                    val locks = store.workoutMutationLocks()
                    deadline.io {
                        entered.await()
                        coroutineScope {
                            val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitWorkoutMutations(locks) }
                            assertFalse(waiting.isCompleted)
                            release.complete(Unit)
                            waiting.await()
                        }
                    }
                }) { frames++ }
            }
            compose.onNodeWithText("All sets").performClick()
            val sheetDeadline = StepDeadline(10_000)
            sheetDeadline.run(compose) {
                compose.settleSemantics(sheetDeadline, awaitPendingWork = {
                    sheetDeadline.io { awaitWorkoutMutations(store.workoutMutationLocks()) }
                }) {}
            }
            compose.onNode(hasText("DB Bench Press") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNode(hasText("Barbell Back Squat") and hasAnyAncestor(isDialog())).assertDoesNotExist()
            assertEquals(before, time.clock.instant())
            assertFalse(compose.mainClock.autoAdvance)
        } finally {
            release.complete(Unit)
            compose.runOnUiThread { store.clear() }
            scope.cancel()
            database.close()
        }
    }
}
