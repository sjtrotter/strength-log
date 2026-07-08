package io.github.sjtrotter.strengthlog.ui.wizard

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
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
 * Wiring tests for [WizardViewModel]: the seams [WizardStateBuilderTest] can't
 * reach — the [androidx.lifecycle.SavedStateHandle] round-trip for every field
 * type (including the equipment [List]), the days/split auto-correction on
 * [WizardViewModel.setDaysPerWeek], re-run pre-fill from stored answers, and
 * [finish][WizardViewModel] actually replacing the program (D3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WizardViewModelWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<WizardViewModel>()

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
            File.createTempFile("wizard-vm-settings", ".preferences_pb")
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

    /** [uiState] is a `WhileSubscribed` [kotlinx.coroutines.flow.StateFlow] — an
     *  active collector is required for `.value` to track updates at all
     *  (mirrors the collector [DayViewModelWiringTest] launches for the same
     *  reason), so every ViewModel here gets one on its own scope. */
    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): WizardViewModel =
        WizardViewModel(repo, handle).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    // --- first-run defaults ----------------------------------------------------

    @Test
    fun firstRun_starts_on_emphasis_with_spec_default_answers() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(WizardStep.EMPHASIS, state.step)
        assertEquals(WizardAnswers(), state.answers)
    }

    // --- navigation --------------------------------------------------------------

    @Test
    fun onNext_advances_through_every_step_and_onBack_returns() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        repeat(WizardStep.entries.size - 1) { vm.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.EQUIPMENT, vm.uiState.value.step)
        assertFalse(vm.uiState.value.isComplete)

        vm.onBack()
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, vm.uiState.value.step)
    }

    // --- field setters + SavedStateHandle round trip ----------------------------

    @Test
    fun everyFieldSetter_is_reflected_in_the_answers_draft() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setEmphasis(GoalEmphasis.STRENGTH)
        vm.setDaysPerWeek(6)
        vm.setSplit(SplitTemplate.UPPER_LOWER)
        vm.setAnchorScheme(AnchorScheme.BIG_4)
        vm.setDeadliftVariant(DeadliftVariant.SUMO)
        vm.setBodyweight(200)
        vm.setAge(30)
        vm.toggleEquipment(Equipment.KETTLEBELL) // present by default -> removed
        advanceUntilIdle()

        val answers = vm.uiState.value.answers
        assertEquals(GoalEmphasis.STRENGTH, answers.config.emphasis)
        assertEquals(6, answers.daysPerWeek)
        assertEquals(SplitTemplate.UPPER_LOWER, answers.split)
        assertEquals(AnchorScheme.BIG_4, answers.anchorScheme)
        assertEquals(DeadliftVariant.SUMO, answers.deadliftVariant)
        assertEquals(200, answers.config.bodyweightLb)
        assertEquals(30, answers.config.age)
        assertFalse(Equipment.KETTLEBELL in answers.equipment)
    }

    @Test
    fun changingDaysPerWeek_autoCorrectsASplitTheNewCountNoLongerOffers() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setDaysPerWeek(5)
        vm.setSplit(SplitTemplate.PPLUL) // only valid at 5 days
        advanceUntilIdle()
        assertEquals(SplitTemplate.PPLUL, vm.uiState.value.answers.split)

        vm.setDaysPerWeek(3) // PPLUL isn't offered at 3 -> falls back to that count's default
        advanceUntilIdle()
        assertEquals(SplitTemplate.FULL_BODY, vm.uiState.value.answers.split)
    }

    @Test
    fun collapseOverride_style_draft_survives_view_model_recreation() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        vm.setBodyweight(210)
        vm.toggleEquipment(Equipment.MACHINE)
        vm.onNext()
        advanceUntilIdle()

        // Same SavedStateHandle, fresh ViewModel — the process-death analog.
        val revived = newViewModel(handle)
        advanceUntilIdle()
        val state = revived.uiState.value
        assertEquals(210, state.answers.config.bodyweightLb)
        assertFalse(Equipment.MACHINE in state.answers.equipment)
        assertEquals(WizardStep.DAYS_PER_WEEK, state.step)
    }

    // --- finish: the only program creator (D3) ----------------------------------

    @Test
    fun finish_persists_answers_and_replaces_the_program() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setEmphasis(GoalEmphasis.PHYSIQUE)

        repeat(WizardStep.entries.size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isComplete)
        assertTrue(repo.wizardCompleteFlow.first())
        assertEquals(GoalEmphasis.PHYSIQUE, repo.wizardAnswersFlow.first().config.emphasis)
        assertEquals(4, repo.programFlow.first().days.size) // spec default: 4-day full-body
    }

    @Test
    fun reRun_prefills_the_draft_from_previously_stored_answers() = runVmTest {
        val previous = WizardAnswers(daysPerWeek = 3, split = SplitTemplate.FULL_BODY, config = WizardAnswers().config.copy(bodyweightLb = 190))
        repo.setWizardAnswers(previous)
        repo.setWizardComplete(true)

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.answers.daysPerWeek)
        assertEquals(190, vm.uiState.value.answers.config.bodyweightLb)
    }
}
