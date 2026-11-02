package cloud.trotter.log.strength.ui.setup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.RestRuntime
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.theme.ThemePreference
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupViewModelWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<SetupViewModel>()

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
            File.createTempFile("setup-vm-settings", ".preferences_pb")
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

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        repo.replaceProgram(Program(emptyList()))
        assertNull(db.planDao().allPlans().single().recipeJson)
        block()
    }

    private fun newViewModel(): SetupViewModel =
        SetupViewModel(settings).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    @Test
    fun keepScreenOnReadsAndWritesTheExistingPreference() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.keepScreenOn)
        vm.setKeepScreenOn(true)
        advanceUntilIdle()
        assertEquals(true, settings.keepScreenOnFlow.first())
        assertEquals(true, vm.uiState.value.keepScreenOn)
        settings.setKeepScreenOn(false)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.keepScreenOn)
    }

    @Test
    fun appPreferencesPersistImmediatelyThroughTheRepository() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setUnit(WeightUnit.KG)
        vm.setThemePreference(ThemePreference.LIGHT)
        advanceUntilIdle()
        assertEquals(WeightUnit.KG, settings.unitFlow.first())
        assertEquals(ThemePreference.LIGHT, settings.themePreferenceFlow.first())
    }

    // --- rest-timer setters (watch W2c) ---------------------------------------

    @Test
    fun disablingThePhoneTimerCancelsItsRuntime() = runVmTest {
        var cancellations = 0
        val runtime = object : RestRuntime by NoOpRestRuntime {
            override fun cancel() { cancellations++ }
        }
        val vm = SetupViewModel(settings, runtime).also { vms += it }
        vm.setPhoneRestTimerEnabled(false)
        advanceUntilIdle()
        assertEquals(false, settings.phoneRestTimerEnabledFlow.first())
        assertEquals(1, cancellations)
        vm.setPhoneRestTimerEnabled(true)
        advanceUntilIdle()
        assertEquals(true, settings.phoneRestTimerEnabledFlow.first())
        assertEquals(1, cancellations)
    }

    @Test
    fun setRestTimerEnabled_persists_the_master_toggle_immediately() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setRestTimerEnabled(false)
        advanceUntilIdle()
        assertEquals(false, settings.restSettingsFlow.first().enabled)

        vm.setRestTimerEnabled(true)
        advanceUntilIdle()
        assertEquals(true, settings.restSettingsFlow.first().enabled)
    }

    @Test
    fun setRestOverride_persists_a_per_category_override_immediately() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setRestOverride(RestCategory.TOP, 210)
        advanceUntilIdle()

        assertEquals(210, settings.restSettingsFlow.first().overrides[RestCategory.TOP])
    }

    @Test
    fun setRestOverride_clamps_to_RestPolicy_bounds() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setRestOverride(RestCategory.LIGHT, RestPolicy.MAX_REST_SECONDS + 100)
        advanceUntilIdle()

        assertEquals(RestPolicy.MAX_REST_SECONDS, settings.restSettingsFlow.first().overrides[RestCategory.LIGHT])
    }

    @Test
    fun clearRestOverrides_removes_every_override_but_leaves_the_master_toggle() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setRestTimerEnabled(false)
        vm.setRestOverride(RestCategory.RAMP, 15)
        vm.setRestOverride(RestCategory.TOP, 200)
        advanceUntilIdle()

        vm.clearRestOverrides()
        advanceUntilIdle()

        val restSettings = settings.restSettingsFlow.first()
        assertEquals(true, restSettings.overrides.isEmpty())
        assertEquals(false, restSettings.enabled)
    }
}
