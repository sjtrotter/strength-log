package cloud.trotter.log.strength.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring tests for [StartDestinationViewModel]: a completed wizard starts on
 * [Routes.TODAY], not [Routes.DAY], plus the `.take(1)` latch documented on
 * the ViewModel itself — the start destination is resolved exactly once per
 * process so a first-run `finish()` (wizardComplete false -> true) can't
 * rebuild the NavHost's start destination out from under an explicit
 * navigation already in flight. Uses Robolectric and a temp-file DataStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartDestinationTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var settings: SettingsStore
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<StartDestinationViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("start-destination-vm-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(): StartDestinationViewModel =
        StartDestinationViewModel(settings).also { vms += it }

    @Test
    fun freshInstall_resolvesToTheWizard() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(Routes.WIZARD, vm.startDestination.value)
    }

    @Test
    fun completedWizard_resolvesToTodayNotDay() = runVmTest {
        // A completed wizard starts on TODAY, not DAY.
        settings.setWizardComplete(true)

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(Routes.TODAY, vm.startDestination.value)
    }

    @Test
    fun onceResolved_theStartDestinationLatchesAndIgnoresLaterWizardCompletion() = runVmTest {
        // StartDestinationViewModel's KDoc: without the `.take(1)` latch, a
        // first-run finish() flipping wizardComplete false->true would rebuild
        // the NavHost with a new start destination (resetting the back stack)
        // at the same instant WizardRoute explicitly navigates away — the
        // double-navigation its doc warns against. So a later flip must be a
        // no-op for a ViewModel that already resolved.
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(Routes.WIZARD, vm.startDestination.value)

        settings.setWizardComplete(true)
        advanceUntilIdle()

        assertEquals(Routes.WIZARD, vm.startDestination.value)
    }
}
