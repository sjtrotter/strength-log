package io.github.sjtrotter.strengthlog.ui.customexercise

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.Routes
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CustomExerciseViewModel] wiring (brief #13): the form's one validation rule
 * (blank/whitespace name is never savable), the [Routes.CUSTOM_EXERCISE_PATTERN_ARG]
 * pre-fill from the #11 picker context, and that [CustomExerciseViewModel.save]
 * goes through the existing `:data` API with no bypass — a saved exercise must
 * turn up in [TrackerRepository.catalogFlow] with exactly what the form held.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CustomExerciseViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<CustomExerciseViewModel>()

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
            File.createTempFile("custom-exercise-vm-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
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

    /** [uiState] is `WhileSubscribed` — needs an active collector to track updates
     *  at all (mirrors [io.github.sjtrotter.strengthlog.ui.wizard.WizardViewModelWiringTest]). */
    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): CustomExerciseViewModel =
        CustomExerciseViewModel(repo, handle).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    // --- validation: blank/whitespace name is never savable ---------------------

    @Test
    fun blankName_cannotBeSaved_andNeverReachesTheRepository() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canSave)
        vm.save()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.saved)
        assertEquals(ExerciseLibrary.entries.size, repo.catalogFlow.first().entries.size)
    }

    @Test
    fun whitespaceOnlyName_isRejectedTheSameAsBlank() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setName("   ")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.saved)
    }

    // --- happy path: save goes through the existing :data API -------------------

    @Test
    fun validName_isTrimmedAndSavedThroughTheRepository_andBecomesVisibleInTheCatalog() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setName("  Cable Hack Squat  ")
        vm.setPattern(MovementPattern.SQUAT_BILATERAL)
        vm.toggleEquipment(Equipment.CABLE)
        vm.setPerHand(true)
        vm.setWeightDisplay(65.0)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.canSave)
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saved)
        val catalog = repo.catalogFlow.first()
        val saved = catalog.entries.first { it.name == "Cable Hack Squat" }
        assertTrue(saved.id.startsWith("custom_"))
        assertEquals(MovementPattern.SQUAT_BILATERAL, saved.pattern)
        assertTrue(saved.perHand)
        assertEquals(listOf(Equipment.CABLE), saved.equipment)
    }

    // --- nav arg: pattern pre-fill from the #11 picker context -------------------

    @Test
    fun preselectedPatternArg_prefillsTheDraft() = runVmTest {
        val handle = SavedStateHandle(mapOf(Routes.CUSTOM_EXERCISE_PATTERN_ARG to MovementPattern.H_PULL.name))
        val vm = newViewModel(handle)
        advanceUntilIdle()

        assertEquals(MovementPattern.H_PULL, vm.uiState.value.pattern)
    }

    @Test
    fun noPatternArg_defaultsToTheFirstPattern() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(MovementPattern.entries.first(), vm.uiState.value.pattern)
    }

    // --- unit-aware weight -------------------------------------------------------

    @Test
    fun weightDisplay_convertsThroughTheCurrentUnit_forStorageAndDisplay() = runVmTest {
        settings.setUnit(WeightUnit.KG)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setWeightDisplay(50.0) // 50 kg entered on screen
        advanceUntilIdle()

        assertEquals(WeightUnit.KG, vm.uiState.value.unit)
        assertEquals(50.0, vm.uiState.value.weightDisplay, 0.001)

        vm.setName("Kg Test")
        vm.save()
        advanceUntilIdle()

        val saved = repo.catalogFlow.first().entries.first { it.name == "Kg Test" }
        val storedLb = (saved.goal as io.github.sjtrotter.strengthlog.domain.library.GoalSource.Flat).weightLb
        assertEquals(WeightUnit.KG.toLb(50.0), storedLb, 0.001)
    }
}
