package cloud.trotter.log.strength.ui.customexercise

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.Routes
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CustomExerciseViewModel] wiring: a non-blank name and explicit pattern are
 * required, navigation hints cannot silently choose a pattern, and
 * [CustomExerciseViewModel.save] goes through the existing
 * `:data` API with no bypass — a saved exercise must turn up in
 * [CatalogRepository.catalogFlow] with exactly what the form held.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CustomExerciseViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: CatalogRepository
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
        repo = CatalogRepository(db.customExerciseDao())
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
     *  at all (mirrors [cloud.trotter.log.strength.ui.wizard.WizardViewModelWiringTest]). */
    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): CustomExerciseViewModel =
        CustomExerciseViewModel(repo, handle, settings = settings).also { vm ->
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

    @Test
    fun namedExerciseRequiresAPatternAndRestoresTheExplicitChoice() = runVmTest {
        val handle = SavedStateHandle(mapOf("custom_exercise_pattern" to MovementPattern.HINGE.name))
        val vm = newViewModel(handle)
        vm.setName("Custom row")
        advanceUntilIdle()
        assertNull(vm.uiState.value.pattern)
        assertFalse(vm.uiState.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.saved)
        vm.setPattern(MovementPattern.H_PULL)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canSave)
        val restored = newViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals(MovementPattern.H_PULL, restored.uiState.value.pattern)
        restored.save()
        advanceUntilIdle()
        assertEquals(MovementPattern.H_PULL, repo.catalogFlow.first().entries.first { it.name == "Custom row" }.pattern)
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

    @Test
    fun savingImmediatelyAfterTheWeightCommitKeepsTheTypedWeight() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setName("Committed on Save")
        vm.setPattern(MovementPattern.H_PULL)
        vm.setWeightDisplay(80.0)
        vm.save()
        advanceUntilIdle()
        val exercise = repo.catalogFlow.first().entries.first { it.name == "Committed on Save" }
        assertEquals(GoalSource.Flat(80.0), exercise.goal)
    }

    // --- tracking-type mapping (WEIGHTED/REPS/TIMED choice) ---------------------

    @Test
    fun defaultTracking_isWeighted_andSavesAFlatGoalAsBefore() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(TrackingType.WEIGHTED, vm.uiState.value.tracking)
        vm.setName("Cable Hack Squat")
        vm.setPattern(MovementPattern.H_PULL)
        vm.setWeightDisplay(80.0)
        advanceUntilIdle() // setWeightDisplay converts via unitFlow.first() asynchronously
        vm.save()
        advanceUntilIdle()

        val saved = repo.catalogFlow.first().entries.first { it.name == "Cable Hack Squat" }
        assertEquals(GoalSource.Flat(80.0), saved.goal)
    }

    @Test
    fun repsTracking_savesTheTargetRepCount_ignoringTheWeightField() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setName("Bodyweight Lunge")
        vm.setPattern(MovementPattern.H_PULL)
        vm.setTracking(TrackingType.REPS)
        vm.setTargetReps(15)
        vm.setWeightDisplay(999.0) // must be ignored once tracking is REPS
        advanceUntilIdle()

        assertEquals(TrackingType.REPS, vm.uiState.value.tracking)
        assertEquals(15, vm.uiState.value.targetReps)
        vm.save()
        advanceUntilIdle()

        val saved = repo.catalogFlow.first().entries.first { it.name == "Bodyweight Lunge" }
        assertEquals(GoalSource.Reps(15), saved.goal)
    }

    @Test
    fun timedTracking_savesTargetSecondsAndOptionalAddedLoad() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setName("Custom Timed Hold")
        vm.setPattern(MovementPattern.H_PULL)
        vm.setTracking(TrackingType.TIMED)
        vm.setTargetSeconds(60)
        vm.setAddedWeightDisplay(10.0)
        advanceUntilIdle()

        assertEquals(TrackingType.TIMED, vm.uiState.value.tracking)
        assertEquals(60, vm.uiState.value.targetSeconds)
        assertEquals(10.0, vm.uiState.value.addedWeightDisplay, 0.001)
        vm.save()
        advanceUntilIdle()

        val saved = repo.catalogFlow.first().entries.first { it.name == "Custom Timed Hold" }
        assertEquals(GoalSource.Time(60, 10.0), saved.goal)
    }

    @Test
    fun timedTracking_withNoAddedLoad_savesAnUnloadedHold() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setName("Custom Unloaded Hold")
        vm.setPattern(MovementPattern.H_PULL)
        vm.setTracking(TrackingType.TIMED)
        vm.setTargetSeconds(45)
        vm.save()
        advanceUntilIdle()

        val saved = repo.catalogFlow.first().entries.first { it.name == "Custom Unloaded Hold" }
        assertEquals(GoalSource.Time(45, 0.0), saved.goal)
    }

    // --- nav arg: explicit choice still required -----------------------

    @Test
    fun patternNavHintStillRequiresAnExplicitChoice() = runVmTest {
        val handle = SavedStateHandle(mapOf(Routes.CUSTOM_EXERCISE_PATTERN_ARG to MovementPattern.H_PULL.name))
        val vm = newViewModel(handle)
        advanceUntilIdle()

        assertNull(vm.uiState.value.pattern)
    }

    @Test
    fun noPatternArg_startsUnselected() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertNull(vm.uiState.value.pattern)
    }

    // --- unit-aware weight -------------------------------------------------------

    @Test
    fun untouchedWeightUsesTheUsersEmptyBarForDisplayAndSave() = runVmTest {
        for ((unit, expected) in listOf(WeightUnit.KG to 20.0, WeightUnit.LB to 45.0)) {
            settings.setUnit(unit)
            val vm = newViewModel()
            advanceUntilIdle()

            assertEquals(unit, vm.uiState.value.unit)
            assertEquals(expected, vm.uiState.value.weightDisplay, 0.0)
            assertEquals(expected, CustomExerciseUiState(unit = unit).weightDisplay, 0.0)
            vm.setName("Empty bar ${unit.name}")
            vm.setPattern(MovementPattern.H_PULL)
            vm.save()
            advanceUntilIdle()

            val saved = repo.catalogFlow.first().entries.first { it.name == "Empty bar ${unit.name}" }
            assertEquals(GoalSource.Flat(unit.toLb(expected)), saved.goal)
        }
    }

    @Test
    fun untouchedKgWeightSavesTheEmptyBarBeforeUiStateIsCollected() = runVmTest {
        settings.setUnit(WeightUnit.KG)
        val vm = CustomExerciseViewModel(repo, SavedStateHandle(), settings).also { vms += it }
        vm.setName("Immediate kg bar")
        vm.setPattern(MovementPattern.H_PULL)
        vm.save()
        advanceUntilIdle()

        val saved = repo.catalogFlow.first().entries.first { it.name == "Immediate kg bar" }
        assertEquals(GoalSource.Flat(WeightUnit.KG.toLb(20.0)), saved.goal)
    }

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
        vm.setPattern(MovementPattern.H_PULL)
        vm.save()
        advanceUntilIdle()

        val saved = repo.catalogFlow.first().entries.first { it.name == "Kg Test" }
        val storedLb = (saved.goal as cloud.trotter.log.strength.domain.library.GoalSource.Flat).weightLb
        assertEquals(WeightUnit.KG.toLb(50.0), storedLb, 0.001)
    }
}
