package io.github.sjtrotter.strengthlog.ui.setup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.standards.RestCategory
import io.github.sjtrotter.strengthlog.domain.standards.RestPolicy
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring tests for [SetupViewModel]: every setter commits straight through
 * [TrackerRepository] (no draft, unlike the wizard — spec §8.4/A6), the
 * bodyweight setter converts through the *current* display unit before
 * storing (A5, [SetupStateBuilder.bodyweightLb]), and config/cardio writes
 * never touch the program (GOAL-vs-ACTUAL, spec §8.4).
 */
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

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(): SetupViewModel =
        SetupViewModel(repo).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    @Test
    fun setBodyweight_persists_in_canonical_lb_regardless_of_display_unit() = runVmTest {
        repo.setUnit(WeightUnit.KG)
        val vm = newViewModel()
        advanceUntilIdle()

        // 100 kg display -> canonical lb via WeightUnit (A5's one conversion point).
        vm.setBodyweight(100.0)
        advanceUntilIdle()

        val expectedLb = WeightUnit.KG.toLb(100.0).let(Math::round).toInt()
        assertEquals(expectedLb, repo.configFlow.first().bodyweightLb)
    }

    @Test
    fun everySetter_persists_immediately_through_the_repository() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setAge(28)
        vm.setLevel(ExperienceLevel.ADVANCED)
        vm.setEmphasis(GoalEmphasis.PHYSIQUE)
        vm.setCardioMode(CardioMode.TREADMILL)
        vm.setCardioPlacement(CardioPlacement.SEPARATE_DAYS)
        vm.setFiveK(false)
        vm.setUnit(WeightUnit.KG)
        advanceUntilIdle()

        val cfg = repo.configFlow.first()
        assertEquals(28, cfg.age)
        assertEquals(ExperienceLevel.ADVANCED, cfg.level)
        assertEquals(GoalEmphasis.PHYSIQUE, cfg.emphasis)

        val cardio = repo.cardioPrefsFlow.first()
        assertEquals(CardioMode.TREADMILL, cardio.mode)
        assertEquals(CardioPlacement.SEPARATE_DAYS, cardio.placement)
        assertEquals(false, cardio.fiveKGoal)

        assertEquals(WeightUnit.KG, repo.unitFlow.first())
    }

    @Test
    fun configChanges_never_touch_the_program_GOAL_vs_ACTUAL() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val programBefore = repo.programFlow.first()

        vm.setAge(50)
        vm.setEmphasis(GoalEmphasis.STRENGTH)
        advanceUntilIdle()

        assertEquals(programBefore, repo.programFlow.first())
    }

    // --- rest-timer setters (watch W2c) ---------------------------------------

    @Test
    fun setRestTimerEnabled_persists_the_master_toggle_immediately() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setRestTimerEnabled(false)
        advanceUntilIdle()
        assertEquals(false, repo.restSettingsFlow.first().enabled)

        vm.setRestTimerEnabled(true)
        advanceUntilIdle()
        assertEquals(true, repo.restSettingsFlow.first().enabled)
    }

    @Test
    fun setRestOverride_persists_a_per_category_override_immediately() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setRestOverride(RestCategory.TOP, 210)
        advanceUntilIdle()

        assertEquals(210, repo.restSettingsFlow.first().overrides[RestCategory.TOP])
    }

    @Test
    fun setRestOverride_clamps_to_RestPolicy_bounds() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setRestOverride(RestCategory.LIGHT, RestPolicy.MAX_REST_SECONDS + 100)
        advanceUntilIdle()

        assertEquals(RestPolicy.MAX_REST_SECONDS, repo.restSettingsFlow.first().overrides[RestCategory.LIGHT])
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

        val settings = repo.restSettingsFlow.first()
        assertEquals(true, settings.overrides.isEmpty())
        assertEquals(false, settings.enabled)
    }
}
