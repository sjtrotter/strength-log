package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    /** Stands in for the injected app scope (a [SupervisorJob], like the real
     *  one). The wizard's restore path runs on it; nothing here exercises that,
     *  but the ViewModel needs one. */
    private lateinit var appScope: CoroutineScope
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        appScope = CoroutineScope(dispatcher + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("wizard-vm-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        journal = RestoreJournal(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("wizard-vm-journal", ".preferences_pb")
            },
            settings,
        )
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
        programDao: cloud.trotter.log.strength.data.db.dao.ProgramDao,
        sessionDao: cloud.trotter.log.strength.data.db.dao.SessionDao,
        customExerciseDao: cloud.trotter.log.strength.data.db.dao.CustomExerciseDao,
        settings: SettingsStore,
    ) : TrackerRepository(db, programDao, sessionDao, customExerciseDao, settings) {
        val calls = mutableListOf<String>()

        override suspend fun setWizardAnswers(answers: WizardAnswers) {
            calls += "setWizardAnswers"
            super.setWizardAnswers(answers)
        }

        override suspend fun replaceProgram(program: cloud.trotter.log.strength.domain.model.Program) {
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

    /** Stands in for whatever a restore put in Room: one day the generator would
     *  never produce, so "was it regenerated?" is a one-line assert. */
    private fun restoredProgram() = Program(
        listOf(
            ProgramDay(
                id = "R",
                title = "Restored",
                emphasisLine = "from a backup",
                exercises = listOf(ProgramExercise(exerciseId = "bb_back_squat", isMain = true)),
                cardio = null,
            ),
        ),
    )

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        appScope.cancel()
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
        defaultUnit: WeightUnit = WeightUnit.LB,
    ): WizardViewModel =
        WizardViewModel(
            repository,
            handle,
            context,
            BackupService(repository, journal),
            appScope,
            DeviceWeightUnitProvider { defaultUnit },
        ).also { vm ->
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
        assertEquals(WizardStep.ROTATION, vm.uiState.value.step)
        assertFalse(vm.uiState.value.isComplete)

        vm.onBack()
        advanceUntilIdle()
        assertEquals(WizardStep.EQUIPMENT, vm.uiState.value.step)
    }

    @Test
    fun metricLocale_defaultsToKg_onFirstRun() = runVmTest {
        val vm = newViewModel(defaultUnit = WeightUnit.KG)
        advanceUntilIdle()

        assertEquals(WeightUnit.KG, vm.uiState.value.unit)
    }

    @Test
    fun enteringRotation_generatesTheCurrentAnswersPreview() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setDaysPerWeek(3)

        repeat(WizardStep.ROTATION.ordinal) { vm.onNext() }
        advanceUntilIdle()

        val expected = ProgramGenerator.generate(vm.uiState.value.answers).program
        assertEquals(
            expected.days.map { it.exercises.first { exercise -> exercise.isMain }.exerciseId },
            vm.uiState.value.previewProgram!!.days.map { it.exercises.first { exercise -> exercise.isMain }.exerciseId },
        )
    }

    @Test
    fun backThenNext_regeneratesTheRotationPreview() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        repeat(WizardStep.ROTATION.ordinal) { vm.onNext() }
        advanceUntilIdle()

        vm.onBack()
        vm.setDaysPerWeek(3)
        vm.onNext()
        advanceUntilIdle()

        assertEquals(ProgramGenerator.generate(vm.uiState.value.answers).program, vm.uiState.value.previewProgram)
        assertEquals(3, vm.uiState.value.previewProgram!!.days.size)
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
        vm.setUnit(WeightUnit.KG)

        repeat(WizardStep.entries.size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isComplete)
        assertTrue(repo.wizardCompleteFlow.first())
        assertEquals(GoalEmphasis.PHYSIQUE, repo.wizardAnswersFlow.first().config.emphasis)
        assertEquals(WeightUnit.KG, repo.unitFlow.first())
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
    fun finish_stepsAsideWhenARestoreLandedUnderTheFirstRunWizard() = runVmTest {
        // #172: the app opens on the wizard whenever wizardComplete reads false,
        // so an interrupted restore whose settings half is still pending puts the
        // wizard in front of a *restored* program. Generating over it would delete
        // the restored program and keep the history — the worst of both.
        val recording = newRecordingRepo()
        val vm = newViewModel(repository = recording)
        advanceUntilIdle()

        // What the startup reconciliation does: the restored program is already in
        // Room, and the settings replay flips the flag under the open wizard.
        recording.replaceProgram(restoredProgram())
        settings.setWizardComplete(true)
        recording.calls.clear()
        advanceUntilIdle()

        repeat(WizardStep.entries.size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish()
        advanceUntilIdle()

        assertEquals("finish must not write anything over a restored device", emptyList<String>(), recording.calls)
        assertEquals(listOf("R"), repo.programFlow.first().days.map { it.id })
        assertTrue("the wizard still leaves — the device is set up", vm.uiState.value.isComplete)
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

        DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo), SavedStateHandle(), kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), FixedCardioClock(), InertCardioAlarm)
            .also { vms += it }
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

    // --- restore from backup: offered on a first run only ------------------------

    @Test
    fun firstRun_offers_the_restore_from_backup_entry() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.restore.offered)
    }

    @Test
    fun reRun_doesNotOfferRestore_becauseDataBackupOwnsImportThere() = runVmTest {
        repo.setWizardComplete(true)

        val vm = newViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.restore.offered)
    }

    @Test
    fun reRun_ignores_a_restore_request_outright() = runVmTest {
        // Not just hidden in the UI: the ViewModel refuses too, so no stray
        // launcher result can wipe a live device from the wizard route.
        repo.setWizardAnswers(WizardAnswers(daysPerWeek = 3))
        repo.setWizardComplete(true)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.restoreFromBackup(android.net.Uri.parse("content://nowhere/backup.json"))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.restore.inFlight)
        assertEquals(null, vm.uiState.value.restore.error)
        assertEquals(3, repo.wizardAnswersFlow.first().daysPerWeek)
    }

    @Test
    fun theFirstRunLatch_survives_view_model_recreation() = runVmTest {
        val handle = SavedStateHandle()
        newViewModel(handle)
        advanceUntilIdle()

        // The wizard finishing flips wizardComplete; the latch must not follow it,
        // or a process death right after finish() would re-offer a destructive
        // import against data that now exists.
        repo.setWizardComplete(true)
        val revived = newViewModel(handle)
        advanceUntilIdle()

        assertTrue(revived.uiState.value.restore.offered)
    }
}
