package io.github.sjtrotter.strengthlog.ui.wizard

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.transfer.health.SessionPublisher
import io.github.sjtrotter.strengthlog.ui.day.DayViewModel
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
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()

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
        settings = SettingsStore(dataStore)
        repo = newRepo()
    }

    private fun newRepo(): TrackerRepository = TrackerRepository(
        db = db,
        programDao = db.programDao(),
        sessionDao = db.sessionDao(),
        customExerciseDao = db.customExerciseDao(),
        settings = settings,
    )

    /** Records the order of the cross-store writes [WizardViewModel.finish] makes,
     *  delegating each to the real repository so the underlying stores still get
     *  the real data (the VM's later reads see a genuine program + flags). */
    private class RecordingRepository(
        db: StrengthDatabase,
        programDao: io.github.sjtrotter.strengthlog.data.db.dao.ProgramDao,
        sessionDao: io.github.sjtrotter.strengthlog.data.db.dao.SessionDao,
        customExerciseDao: io.github.sjtrotter.strengthlog.data.db.dao.CustomExerciseDao,
        settings: SettingsStore,
    ) : TrackerRepository(db, programDao, sessionDao, customExerciseDao, settings) {
        val calls = mutableListOf<String>()

        override suspend fun setWizardAnswers(answers: WizardAnswers) {
            calls += "setWizardAnswers"
            super.setWizardAnswers(answers)
        }

        override suspend fun replaceProgram(program: io.github.sjtrotter.strengthlog.domain.model.Program) {
            calls += "replaceProgram"
            super.replaceProgram(program)
        }

        override suspend fun setWizardComplete(complete: Boolean) {
            calls += "setWizardComplete"
            super.setWizardComplete(complete)
        }
    }

    private fun newRecordingRepo(): RecordingRepository = RecordingRepository(
        db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings,
    )

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
    private fun newViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        repository: TrackerRepository = repo,
    ): WizardViewModel =
        WizardViewModel(repository, handle).also { vm ->
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
    fun finish_writesTheProgramBeforeMarkingTheWizardComplete() = runVmTest {
        // Crash-safety (D1/D3): wizardComplete is the routing flag, so it must be
        // set only after the program exists. A completion flag ahead of the
        // program would strand a killed app on an empty day screen with no
        // in-app recovery.
        val recording = newRecordingRepo()
        val vm = newViewModel(repository = recording)
        advanceUntilIdle()

        repeat(WizardStep.entries.size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish()
        advanceUntilIdle()

        assertEquals(listOf("setWizardAnswers", "replaceProgram", "setWizardComplete"), recording.calls)
        assertTrue(
            "replaceProgram must run before setWizardComplete",
            recording.calls.indexOf("replaceProgram") < recording.calls.indexOf("setWizardComplete"),
        )
    }

    @Test
    fun finishThenDayScreenSeedsThePinnedSquatSequence() = runVmTest {
        // Closes the compositional gap between finish_persists_answers_and_
        // replaces_the_program (real generator, but only a day count assert)
        // and DayViewModelWiringTest (pinned §11 seed numbers, but against a
        // hand-built program fixture): drive the REAL generator through
        // finish(), then let a real DayViewModel seed day A of that program
        // and pin the persisted squat log end to end.
        val vm = newViewModel()
        advanceUntilIdle()
        repeat(WizardStep.entries.size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish(): ProgramGenerator -> replaceProgram
        advanceUntilIdle()

        DayViewModel(repo, SessionPublisher.NoOp, SavedStateHandle()).also { vms += it }
        advanceUntilIdle() // constructing the VM triggers the day-A seed pass

        val squatSlotId = repo.daySlotsFlow("A").first()
            .first { it.exercise.exerciseId == "bb_back_squat" }
            .programExerciseId
        val squat = repo.logFlow("A").first()
            .first { it.programExerciseId == squatSlotId && it.slot == Slot.MAIN }
            .sets
        assertEquals(
            listOf(
                Triple(130.0, 5, SetKind.RAMP),
                Triple(165.0, 5, SetKind.RAMP),
                Triple(190.0, 5, SetKind.RAMP),
                Triple(210.0, 3, SetKind.RAMP),
                Triple(235.0, 5, SetKind.TOP),
                Triple(175.0, 8, SetKind.BACKOFF),
            ),
            squat.map { Triple(it.weightLb, it.reps, it.kind) },
        )
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
