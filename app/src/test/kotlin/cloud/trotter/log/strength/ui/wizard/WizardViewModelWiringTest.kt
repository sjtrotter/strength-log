package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.data.writeTestRows
import kotlinx.coroutines.flow.map
import android.content.Context
import android.os.Parcel
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.FlakyDataStore
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.dao.PlanDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.DistanceUnit
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
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.text.UiText
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * [finish][WizardViewModel] actually creating a plan (D3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WizardViewModelWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var dataStore: DataStore<Preferences>
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
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
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

    private var createGate: CompletableDeferred<Unit>? = null
    private var creationRecordedBeforeGate = false

    private val calls = mutableListOf<String>()
    private val WIZARD_COMPLETE = booleanPreferencesKey("wizard_complete")
    private var failNextCompletion = false

    /** Records each write that sets `wizard_complete` as "setWizardComplete" (the
     *  failing attempt too), and fails the next one on request. */
    private inner class CompletionRecordingStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        override val data = flow {
            val gate = createGate
            if (gate != null) {
                createGate = null
                // Armed after initialization: finish's restore reconciliation reads
                // preferences before any transaction that could create the plan.
                check(!db.inTransaction())
                calls += "createGeneratedPlan"
                creationRecordedBeforeGate = true
                gate.await()
            }
            emitAll(delegate.data)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData { before ->
                if (transform(emptyPreferences())[WIZARD_COMPLETE] == true) {
                    check(!db.inTransaction())
                    val selected = before[longPreferencesKey("selected_plan_id")]
                    check(selected != null && db.programDao().daysForPlan(selected).isNotEmpty())
                    calls += "setWizardComplete"
                    if (failNextCompletion) {
                        failNextCompletion = false
                        throw IOException("completion write failed")
                    }
                }
                transform(before)
            }
    }

    private fun newRecordingRepo(store: DataStore<Preferences> = dataStore): TrackerRepository {
        settings = SettingsStore(CompletionRecordingStore(store))
        val programDao = object : ProgramDao by db.programDao() {
            override suspend fun deleteDaysForPlan(planId: Long) {
                calls += "replaceProgram"
                db.programDao().deleteDaysForPlan(planId)
            }

            override suspend fun upsertDay(day: ProgramDayEntity) {
                if (day.position == 0) {
                    if (!creationRecordedBeforeGate) calls += "createGeneratedPlan"
                    creationRecordedBeforeGate = false
                }
                db.programDao().upsertDay(day)
            }
        }
        val planDao = object : PlanDao by db.planDao() {
            override suspend fun updateRecipe(id: Long, recipeJson: String) {
                calls += "setWizardAnswers"
                db.planDao().updateRecipe(id, recipeJson)
            }

            override suspend fun updateOnToday(id: Long, onToday: Boolean) {
                check(!db.inTransaction())
                check(db.programDao().daysForPlan(id).isNotEmpty())
                calls += "publishCreatedPlan"
                db.planDao().updateOnToday(id, onToday)
            }
        }
        return TrackerRepository(
            db, programDao, db.sessionDao(), db.customExerciseDao(), settings,
            plans = PlanRepository(
                db, settings, programDao = programDao, planDao = planDao,
            ),
            catalog = CatalogRepository(db.customExerciseDao()),
        )
    }

    private fun failCompletionWrite() {
        failNextCompletion = true
    }

    /** Stands in for whatever a restore put in Room: one day the generator would
     *  never produce, so "was it regenerated?" is a one-line assert. */
    private fun restoredProgram() = Program(
        listOf(
            ProgramDay(
                id = "R",
                title = "Restored",
                emphasisLine = "from a backup",
                exercises = listOf(ProgramExercise(exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
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
        preferences: SettingsStore = settings,
    ): WizardViewModel =
        WizardViewModel(
            repository.plans,
            handle,
            context,
            BackupService(BackupRepository(db, settings, repository.plans, repository.history), journal),
            appScope,
            DeviceWeightUnitProvider { defaultUnit },
            settings = preferences,
        ).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    // --- first-run defaults ----------------------------------------------------

    @Test
    fun firstRun_starts_on_focus_with_spec_default_answers() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(WizardStep.FOCUS, state.step)
        assertEquals(WizardAnswers(), state.answers)
    }

    @Test
    fun stepOneTapBeforeSavedAnswersArriveIsKept() = runVmTest {
        repo.plans.setWizardAnswers(WizardAnswers(config = WizardAnswers().config.copy(emphasis = GoalEmphasis.PHYSIQUE)))
        settings.setWizardComplete(true)
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val gatedSettings = SettingsStore(object : DataStore<Preferences> {
            override val data = flow {
                readStarted.complete(Unit)
                releaseRead.await()
                emitAll(dataStore.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                dataStore.updateData(transform)
        })
        val gatedRepo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), gatedSettings)
        val vm = newViewModel(repository = gatedRepo, preferences = gatedSettings)
        readStarted.await()
        vm.setEmphasis(GoalEmphasis.STRENGTH)
        releaseRead.complete(Unit)
        advanceUntilIdle()
        assertEquals(GoalEmphasis.STRENGTH, vm.uiState.value.answers.config.emphasis)
    }

    @Test
    fun unitTapBeforeSavedAnswersArriveIsKept() = runVmTest {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val gatedSettings = SettingsStore(object : DataStore<Preferences> {
            override val data = flow {
                readStarted.complete(Unit)
                releaseRead.await()
                emitAll(dataStore.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                dataStore.updateData(transform)
        })
        val gatedRepo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), gatedSettings)
        val vm = newViewModel(repository = gatedRepo, preferences = gatedSettings)
        readStarted.await()
        vm.setUnit(WeightUnit.KG)
        releaseRead.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(WeightUnit.KG, state.unit)
        assertEquals(WeightUnit.KG, state.answers.config.weightUnit)
        assertEquals(null, state.bodyweightLb)
        assertEquals(null, state.age)
        assertEquals(WizardAnswers().config.bodyweightLb, state.answers.config.bodyweightLb, 0.0)
        assertEquals(WizardAnswers().config.age, state.answers.config.age)
    }

    @Test
    fun firstRunAboutYouStartsEmptyAndDraftSurvivesRecreation() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.bodyweightLb)
        assertEquals(null, vm.uiState.value.age)
        vm.setBodyweight(210.5)
        vm.setAge(33)
        advanceUntilIdle()
        val recreated = newViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals(210.5, recreated.uiState.value.bodyweightLb ?: Double.NaN, 0.0)
        assertEquals(33, recreated.uiState.value.age)
    }

    // --- navigation --------------------------------------------------------------

    @Test
    fun legacyStepBundles_resumeTheSameQuestionAndMigrateOnlyOnce() = runVmTest {
        val legacySteps = listOf(
            WizardStep.EMPHASIS, WizardStep.DAYS_PER_WEEK, WizardStep.SPLIT,
            WizardStep.ANCHORS, WizardStep.CARDIO, WizardStep.ABOUT_YOU,
            WizardStep.EQUIPMENT, WizardStep.LIFTS, WizardStep.ROTATION,
        )
        for ((index, step) in legacySteps.withIndex()) {
            val handle = parcelRoundTrip(SavedStateHandle(mapOf(
                "wizard_initialized" to true,
                "wizard_first_run" to true,
                "wizard_step" to index,
                "wizard_bodyweight" to 205,
                "wizard_age" to 32,
            )))
            val vm = newViewModel(handle)
            advanceUntilIdle()
            assertEquals(step, vm.uiState.value.step)
            assertEquals(205.0, vm.uiState.value.bodyweightLb ?: Double.NaN, 0.0)
            assertEquals(32, vm.uiState.value.age)
            if (step == WizardStep.ROTATION) {
                assertTrue(vm.uiState.value.previewProgram!!.days.isNotEmpty())
                assertTrue(vm.uiState.value.canContinue)
            }

            vm.onBack()
            advanceUntilIdle()
            val previous = if (index == 0) WizardStep.FOCUS else legacySteps[index - 1]
            assertEquals(previous, vm.uiState.value.step)
            assertEquals(index, handle.get<Int>("wizard_step"))
            vm.viewModelScope.cancel()

            val revived = newViewModel(parcelRoundTrip(handle))
            advanceUntilIdle()
            assertEquals(previous, revived.uiState.value.step)
            revived.onNext()
            advanceUntilIdle()
            assertEquals(step, revived.uiState.value.step)
            revived.viewModelScope.cancel()
        }
    }

    private fun parcelRoundTrip(handle: SavedStateHandle): SavedStateHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(WizardLiftDraft::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun queuedNextFromCardio_stopsAtAboutYouUntilBothAnswersAreEntered() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.CARDIO)) { vm.onNext() }
        advanceUntilIdle()

        vm.onNext()
        vm.onNext()
        assertEquals(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ABOUT_YOU), handle.get<Int>("wizard_step_index_v2"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canContinue)

        vm.setAge(33)
        vm.onNext()
        assertEquals(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ABOUT_YOU), handle.get<Int>("wizard_step_index_v2"))
        vm.setAge(null)
        vm.setBodyweight(210.5)
        vm.onNext()
        assertEquals(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ABOUT_YOU), handle.get<Int>("wizard_step_index_v2"))
        vm.setAge(33)
        vm.onNext()
        assertEquals(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.EQUIPMENT), handle.get<Int>("wizard_step_index_v2"))
    }

    @Test
    fun finish_refusesMissingOrInvalidAboutYouAnswersInRestoredDrafts() = runVmTest {
        val invalidAnswers = listOf(
            null to null,
            210.5 to null,
            null to 33,
            0.0 to 33,
            Double.NaN to 33,
            Double.POSITIVE_INFINITY to 33,
            210.5 to 0,
        )
        for ((bodyweight, age) in invalidAnswers) {
            val handle = SavedStateHandle(mapOf(
                "wizard_initialized" to true,
                "wizard_step_index_v2" to WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION),
                "wizard_bodyweight_lb" to bodyweight,
                "wizard_age" to age,
            ))
            val vm = newViewModel(handle)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.emptyRotation)

            vm.onNext()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isComplete)
            assertFalse(vm.uiState.value.finishInFlight)
            assertFalse(settings.wizardCompleteFlow.first())
            assertTrue(db.planDao().allPlans().isEmpty())
            assertEquals(null, handle.get<Long>("wizard_created_plan_id"))
        }
    }

    @Test
    fun onNext_advances_through_every_step_and_onBack_returns() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)

        repeat(WizardStateBuilder.steps(WizardAnswers()).size - 1) { vm.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.ROTATION, vm.uiState.value.step)
        assertFalse(vm.uiState.value.isComplete)

        vm.onBack()
        advanceUntilIdle()
        assertEquals(WizardStep.LIFTS, vm.uiState.value.step)
    }

    @Test
    fun `recent lifts seed the preview`() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.LIFTS)) { vm.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.LIFTS, vm.uiState.value.step)

        vm.setLiftWeight("bb_back_squat", 225.0)
        vm.onNext()
        advanceUntilIdle()

        assertEquals(WizardStep.ROTATION, vm.uiState.value.step)
        val squat = vm.uiState.value.previewProgram!!.days.first().exercises.first()
        assertEquals(Prescription(PrescribedTarget.LoadReps(225.0, 5)), squat.prescription)
    }

    @Test
    fun `blank recent lifts leave the preview as generated`() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.LIFTS)) { vm.onNext() }
        vm.onNext()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(WizardStep.ROTATION, state.step)
        assertEquals(ProgramGenerator.generate(state.answers.copy(lifts = emptyMap())), state.previewProgram)
    }

    @Test
    fun `recent lifts survive process death`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        vm.setLiftWeight("bb_back_squat", 225.0)
        vm.setLiftReps("bb_back_squat", 8)
        vm.viewModelScope.cancel()

        val revived = newViewModel(handle)
        advanceUntilIdle()

        val squat = revived.uiState.value.liftRows.single { it.exerciseId == "bb_back_squat" }
        assertEquals(225.0, squat.weightLb ?: Double.NaN, 0.0)
        assertEquals(8, squat.reps)
        assertEquals(PrescribedTarget.LoadReps(225.0, 8), ProgramGenerator.generate(revived.uiState.value.answers)
            .days.first().exercises.first().prescription?.target)
    }

    @Test
    fun `invalid recent lift weights stay blank`() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        for (weight in listOf(null, 0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            vm.setLiftWeight("bb_back_squat", 225.0)
            vm.setLiftWeight("bb_back_squat", weight)
            advanceUntilIdle()

            assertEquals(null, vm.uiState.value.liftRows.first().weightLb)
            assertTrue(vm.uiState.value.answers.lifts.isEmpty())
        }
    }

    @Test
    fun metricLocale_defaultsToKg_onFirstRun() = runVmTest {
        val vm = newViewModel(defaultUnit = WeightUnit.KG)
        advanceUntilIdle()

        assertEquals(WeightUnit.KG, vm.uiState.value.unit)
        assertEquals(WeightUnit.KG, vm.uiState.value.answers.config.weightUnit)
    }

    @Test
    fun selectedUnit_isCarriedInAnswersAfterRecreation() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        vm.setUnit(WeightUnit.KG)
        advanceUntilIdle()
        assertEquals(WeightUnit.KG, vm.uiState.value.answers.config.weightUnit)

        val revived = newViewModel(handle)
        advanceUntilIdle()
        assertEquals(WeightUnit.KG, revived.uiState.value.answers.config.weightUnit)
        revived.setUnit(WeightUnit.LB)
        advanceUntilIdle()
        assertEquals(WeightUnit.LB, revived.uiState.value.answers.config.weightUnit)
    }

    @Test
    fun enteringRotation_generatesTheCurrentAnswersPreview() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        vm.setDaysPerWeek(3)

        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION)) { vm.onNext() }
        advanceUntilIdle()

        val expected = ProgramGenerator.generate(vm.uiState.value.answers)
        assertEquals(
            expected.days.map { it.exercises.first { exercise -> exercise.structure.hasTopSet }.exerciseId },
            vm.uiState.value.previewProgram!!.days.map { it.exercises.first { exercise -> exercise.structure.hasTopSet }.exerciseId },
        )
    }

    @Test
    fun backThenNext_regeneratesTheRotationPreview() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION)) { vm.onNext() }
        advanceUntilIdle()

        vm.onBack()
        vm.setDaysPerWeek(3)
        vm.onNext()
        advanceUntilIdle()

        assertEquals(ProgramGenerator.generate(vm.uiState.value.answers), vm.uiState.value.previewProgram)
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
        vm.setBodyweight(200.0)
        vm.setAge(30)
        vm.toggleEquipment(Equipment.KETTLEBELL) // present by default -> removed
        advanceUntilIdle()

        val answers = vm.uiState.value.answers
        assertEquals(GoalEmphasis.STRENGTH, answers.config.emphasis)
        assertEquals(6, answers.daysPerWeek)
        assertEquals(SplitTemplate.UPPER_LOWER, answers.split)
        assertEquals(AnchorScheme.BIG_4, answers.anchorScheme)
        assertEquals(DeadliftVariant.SUMO, answers.deadliftVariant)
        assertEquals(200.0, answers.config.bodyweightLb, 0.0)
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
        vm.setBodyweight(210.0)
        vm.toggleEquipment(Equipment.MACHINE)
        repeat(2) { vm.onNext() }
        advanceUntilIdle()

        // Same SavedStateHandle, fresh ViewModel — the process-death analog.
        val revived = newViewModel(handle)
        advanceUntilIdle()
        val state = revived.uiState.value
        assertEquals(210.0, state.answers.config.bodyweightLb, 0.0)
        assertFalse(Equipment.MACHINE in state.answers.equipment)
        assertEquals(WizardStep.DAYS_PER_WEEK, state.step)
    }

    // --- finish: the only program creator (D3) ----------------------------------

    @Test
    fun finish_persists_answers_and_creates_a_plan() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        vm.setEmphasis(GoalEmphasis.PHYSIQUE)
        vm.setUnit(WeightUnit.KG)

        repeat(WizardStateBuilder.steps(WizardAnswers()).size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isComplete)
        assertTrue(settings.wizardCompleteFlow.first())
        assertEquals(GoalEmphasis.PHYSIQUE, repo.plans.wizardAnswersFlow.first().config.emphasis)
        assertEquals(WeightUnit.KG, settings.unitFlow.first())
        assertEquals(4, repo.plans.programFlow.first().days.size) // spec default: 4-day full-body
    }

    @Test
    fun reviewingRotationReturnsToTheFirstStepWithTheDraftPreserved() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        vm.setDaysPerWeek(3)
        vm.setEmphasis(GoalEmphasis.PHYSIQUE)
        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION)) { vm.onNext() }
        advanceUntilIdle()
        val answers = vm.uiState.value.answers
        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION)) { vm.onBack() }
        advanceUntilIdle()
        assertEquals(WizardStep.FOCUS, vm.uiState.value.step)
        assertEquals(answers, vm.uiState.value.answers)
    }

    @Test
    fun finish_onExistingInstall_createsAndSelectsSecondPlanWithoutChangingOriginal() = runVmTest {
        repo.replaceProgram(restoredProgram())
        settings.setGlobalLifter(GlobalLifter.of(WizardAnswers().config))
        settings.setWizardComplete(true)
        val slot = db.programDao().exercisesForPlan(1).single().id
        repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slot) ?: -1, "R").first().runId.orEmpty(), "R", slot, Slot.MAIN, listOf(
            cloud.trotter.log.strength.domain.model.LoggedSet(225.0, 5, SetKind.WORK, done = true),
        ))
        val original = db.planDao().plan(1)
        val days = db.programDao().daysForPlan(1)
        val exercises = db.programDao().exercisesForPlan(1)
        val logs = db.programDao().logsForPlan(1)
        val vm = newViewModel()
        advanceUntilIdle()

        repeat(WizardStateBuilder.steps(WizardAnswers()).size) { vm.onNext() }
        advanceUntilIdle()

        val plans = db.planDao().allPlans()
        assertEquals(2, plans.size)
        assertEquals("4-day full body", plans.last().name)
        assertEquals(plans.last().id, settings.selectedPlanIdFlow.first())
        assertTrue(vm.uiState.value.isComplete)
        assertEquals(original, db.planDao().plan(1))
        assertEquals(days, db.programDao().daysForPlan(1))
        assertEquals(exercises, db.programDao().exercisesForPlan(1))
        assertEquals(logs, db.programDao().logsForPlan(1))
    }

    @Test
    fun finish_twiceQuicklyOnExistingInstall_createsExactlyOnePlan() = runVmTest {
        repo.replaceProgram(restoredProgram())
        settings.setGlobalLifter(GlobalLifter.of(WizardAnswers().config))
        settings.setWizardComplete(true)
        val recording = newRecordingRepo()
        val gate = CompletableDeferred<Unit>()
        val vm = newViewModel(repository = recording)
        advanceUntilIdle()
        repeat(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION)) { vm.onNext() }
        createGate = gate

        vm.onNext()
        vm.onNext()
        runCurrent()
        assertTrue(vm.uiState.value.finishInFlight)
        assertFalse(vm.uiState.value.isComplete)
        assertEquals(listOf("createGeneratedPlan"), calls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, db.planDao().allPlans().size)
        assertEquals(listOf("createGeneratedPlan", "publishCreatedPlan", "setWizardComplete"), calls)
        assertTrue(settings.wizardCompleteFlow.first())
        assertTrue(vm.uiState.value.isComplete)
        assertFalse(vm.uiState.value.finishInFlight)
    }

    @Test
    fun finish_retriesGlobalLifterPublicationWithoutCreatingAnotherPlan() = runVmTest {
        retryFailedPublication(failedUpdate = 1)
    }

    @Test
    fun finish_retriesSelectionPublicationWithoutCreatingAnotherPlan() = runVmTest {
        retryFailedPublication(failedUpdate = 2)
    }

    private suspend fun TestScope.retryFailedPublication(failedUpdate: Int) {
        repo.replaceProgram(restoredProgram())
        settings.setGlobalLifter(GlobalLifter.of(WizardAnswers().config))
        settings.setWizardComplete(true)
        val originalPlans = db.planDao().allPlans()
        // Wrap the already initialized store so updates 1 and 2 are precisely
        // publication's global-lifter and selection writes, respectively.
        val failingStore = FlakyDataStore(dataStore)
        val recording = newRecordingRepo(failingStore)
        val failingSettings = settings
        val handle = SavedStateHandle()
        val vm = newViewModel(handle, recording, preferences = failingSettings)
        advanceUntilIdle()
        vm.setBodyweight(210.0)
        vm.setUnit(WeightUnit.KG)
        failingStore.failOnUpdate = failedUpdate

        repeat(WizardStateBuilder.steps(WizardAnswers()).size) { vm.onNext() }
        advanceUntilIdle()

        val plans = db.planDao().allPlans()
        assertEquals(originalPlans.size + 1, plans.size)
        assertEquals(originalPlans, plans.dropLast(1))
        assertEquals(plans.last().id, handle.get<Long>("wizard_created_plan_id"))
        assertFalse(vm.uiState.value.isComplete)
        assertFalse(vm.uiState.value.finishInFlight)
        assertEquals(UiText.FileAccessFailure("store is down (update $failedUpdate)"), vm.uiState.value.finishError)
        assertEquals(originalPlans.single().id, settings.selectedPlanIdFlow.first())
        assertEquals(listOf("createGeneratedPlan", "publishCreatedPlan"), calls)

        vm.onNext()
        advanceUntilIdle()

        assertEquals(plans, db.planDao().allPlans())
        assertEquals(
            listOf("createGeneratedPlan", "publishCreatedPlan", "publishCreatedPlan", "setWizardComplete"),
            calls,
        )
        assertEquals(plans.last().id, settings.selectedPlanIdFlow.first())
        assertEquals(210.0, settings.globalLifterFlow.first().bodyweightLb, 0.0)
        assertEquals(WeightUnit.KG, settings.unitFlow.first())
        assertTrue(settings.wizardCompleteFlow.first())
        assertTrue(vm.uiState.value.isComplete)
        assertFalse(vm.uiState.value.finishInFlight)
        assertEquals(null, vm.uiState.value.finishError)
    }

    @Test
    fun finish_recreatesADeletedRememberedPlan() = runVmTest {
        retryWithInvalidRememberedPlan(deletePlan = true)
    }

    @Test
    fun finish_refillsARememberedPlanWithoutDays() = runVmTest {
        retryWithInvalidRememberedPlan(deletePlan = false)
    }

    private suspend fun TestScope.retryWithInvalidRememberedPlan(deletePlan: Boolean) {
        val recording = newRecordingRepo()
        val vm = newViewModel(repository = recording)
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        failCompletionWrite()
        repeat(WizardStateBuilder.steps(WizardAnswers()).size) { vm.onNext() }
        advanceUntilIdle()

        val remembered = db.planDao().allPlans().single().id
        assertFalse(vm.uiState.value.isComplete)
        assertTrue(db.programDao().daysForPlan(remembered).isNotEmpty())
        // Clear dependent rows just as a database replacement would.
        db.programDao().deleteLogsForPlan(remembered)
        db.programDao().deleteExercisesForPlan(remembered)
        db.programDao().deleteDaysForPlan(remembered)
        if (deletePlan) db.planDao().deleteAllPlans()
        assertFalse(recording.plans.planHasDays(remembered))

        vm.onNext()
        advanceUntilIdle()

        val created = db.planDao().allPlans().single().id
        assertEquals(2, calls.count { it == "createGeneratedPlan" })
        assertTrue(db.programDao().daysForPlan(created).isNotEmpty())
        assertEquals(created, settings.selectedPlanIdFlow.first())
        assertTrue(repo.plans.programFlow.first().days.isNotEmpty())
        assertTrue(settings.wizardCompleteFlow.first())
        assertTrue(vm.uiState.value.isComplete)
        assertEquals(null, vm.uiState.value.finishError)
    }

    @Test
    fun finish_retriesCompletionWithoutCreatingAnotherPlan() = runVmTest {
        repo.replaceProgram(restoredProgram())
        settings.setGlobalLifter(GlobalLifter.of(WizardAnswers().config))
        settings.setWizardComplete(true)
        val recording = newRecordingRepo()
        val vm = newViewModel(repository = recording)
        advanceUntilIdle()
        failCompletionWrite()
        vm.setUnit(WeightUnit.KG)
        repeat(WizardStateBuilder.steps(WizardAnswers()).size) { vm.onNext() }
        advanceUntilIdle()

        val plans = db.planDao().allPlans()
        assertEquals(2, plans.size)
        assertFalse(vm.uiState.value.isComplete)
        assertFalse(vm.uiState.value.finishInFlight)
        assertEquals(UiText.FileAccessFailure("completion write failed"), vm.uiState.value.finishError)

        vm.onNext()
        advanceUntilIdle()

        assertEquals(plans, db.planDao().allPlans())
        assertEquals(listOf("createGeneratedPlan", "publishCreatedPlan", "setWizardComplete", "publishCreatedPlan", "setWizardComplete"), calls)
        assertEquals(plans.last().id, settings.selectedPlanIdFlow.first())
        assertEquals(WeightUnit.KG, settings.unitFlow.first())
        assertTrue(settings.wizardCompleteFlow.first())
        assertTrue(vm.uiState.value.isComplete)
        assertFalse(vm.uiState.value.finishInFlight)
        assertEquals(null, vm.uiState.value.finishError)
    }

    @Test
    fun finish_savedPlanSurvivesViewModelRecreationBeforeRetry() = runVmTest {
        repo.replaceProgram(restoredProgram())
        settings.setGlobalLifter(GlobalLifter.of(WizardAnswers().config))
        settings.setWizardComplete(true)
        val recording = newRecordingRepo()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle, recording)
        advanceUntilIdle()
        failCompletionWrite()
        repeat(WizardStateBuilder.steps(WizardAnswers()).size) { vm.onNext() }
        advanceUntilIdle()

        val plans = db.planDao().allPlans()
        assertEquals(2, plans.size)
        assertFalse(vm.uiState.value.isComplete)
        vm.viewModelScope.cancel()
        val revived = newViewModel(handle, recording)
        advanceUntilIdle()

        revived.onNext()
        advanceUntilIdle()

        assertEquals(plans, db.planDao().allPlans())
        assertEquals(listOf("createGeneratedPlan", "publishCreatedPlan", "setWizardComplete", "publishCreatedPlan", "setWizardComplete"), calls)
        assertTrue(settings.wizardCompleteFlow.first())
        assertTrue(revived.uiState.value.isComplete)
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
        vm.setBodyweight(235.0)
        vm.setAge(40)

        repeat(WizardStateBuilder.steps(WizardAnswers()).size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish()
        advanceUntilIdle()

        assertEquals(listOf("createGeneratedPlan", "publishCreatedPlan", "setWizardComplete"), calls)
        assertTrue(settings.wizardCompleteFlow.first())
    }

    @Test
    fun finish_persists_standalone_cardio_days() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)
        vm.setCardioMode(cloud.trotter.log.strength.domain.model.CardioMode.OUTDOOR_RUN)
        vm.setCardioPlacement(cloud.trotter.log.strength.domain.model.CardioPlacement.SEPARATE_DAYS)
        repeat(WizardStateBuilder.steps(WizardAnswers()).size - 1) { vm.onNext() }
        vm.onNext()
        advanceUntilIdle()

        val program = repo.plans.programFlow.first()
        assertEquals(2, program.cardioDays.size)
        assertTrue(program.cardioDays.all { it.exercises.size == 1 && it.cardioBlock != null })
    }

    @Test
    fun finish_stepsAsideWhenARestoreLandedUnderTheFirstRunWizard() = runVmTest {
        // The app opens on the wizard whenever wizardComplete reads false, so an
        // interrupted restore whose settings half is still pending puts the wizard
        // in front of a *restored* program. Generating over it would delete the
        // restored program and keep the history — the worst of both.
        val recording = newRecordingRepo()
        val vm = newViewModel(repository = recording)
        advanceUntilIdle()
        vm.setBodyweight(235.0)
        vm.setAge(40)

        // What the startup reconciliation does: the restored program is already in
        // Room, and the settings replay flips the flag under the open wizard.
        recording.replaceProgram(restoredProgram())
        settings.setWizardComplete(true)
        calls.clear()
        advanceUntilIdle()

        repeat(WizardStateBuilder.steps(WizardAnswers()).size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish()
        advanceUntilIdle()

        assertEquals("finish must not write anything over a restored device", emptyList<String>(), calls)
        assertEquals(listOf("R"), repo.plans.programFlow.first().days.map { it.id })
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
        vm.setBodyweight(235.0)
        vm.setAge(40)
        repeat(WizardStateBuilder.steps(WizardAnswers()).size - 1) { vm.onNext() }
        vm.onNext() // last step -> finish(): ProgramGenerator -> replaceProgram
        advanceUntilIdle()

        DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo.history, settings = settings), SavedStateHandle(), FixedCardioClock(), InertCardioAlarm, PhoneRestController(settings, NoOpRestRuntime), settings = settings, plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history)
            .also { vms += it }
        advanceUntilIdle() // constructing the VM triggers the day-A seed pass

        val squatSlotId = repo.plans.daySlotsFlow("A").first()
            .first { it.exercise.exerciseId == "bb_back_squat" }
            .programExerciseId
        val squat = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first()
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
        val previous = WizardAnswers(daysPerWeek = 3, split = SplitTemplate.FULL_BODY, config = WizardAnswers().config.copy(bodyweightLb = 190.0))
        repo.plans.setWizardAnswers(previous)
        settings.setWizardComplete(true)

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.answers.daysPerWeek)
        assertEquals(190.0, vm.uiState.value.answers.config.bodyweightLb, 0.0)
        assertEquals(190.0, vm.uiState.value.bodyweightLb ?: Double.NaN, 0.0)
        assertEquals(previous.config.age, vm.uiState.value.age)
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
        settings.setWizardComplete(true)

        val vm = newViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.restore.offered)
    }

    @Test
    fun reRun_ignores_a_restore_request_outright() = runVmTest {
        // Not just hidden in the UI: the ViewModel refuses too, so no stray
        // launcher result can wipe a live device from the wizard route.
        repo.plans.setWizardAnswers(WizardAnswers(daysPerWeek = 3))
        settings.setWizardComplete(true)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.restoreFromBackup(android.net.Uri.parse("content://nowhere/backup.json"))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.restore.inFlight)
        assertEquals(null, vm.uiState.value.restore.error)
        assertEquals(3, repo.plans.wizardAnswersFlow.first().daysPerWeek)
    }

    @Test
    fun theFirstRunLatch_survives_view_model_recreation() = runVmTest {
        val handle = SavedStateHandle()
        newViewModel(handle)
        advanceUntilIdle()

        // The wizard finishing flips wizardComplete; the latch must not follow it,
        // or a process death right after finish() would re-offer a destructive
        // import against data that now exists.
        settings.setWizardComplete(true)
        val revived = newViewModel(handle)
        advanceUntilIdle()

        assertTrue(revived.uiState.value.restore.offered)
    }

    @Test
    fun `cardio first with no lifting finishes without About you`() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val global = settings.globalLifterFlow.first()
        assertFalse(settings.hasLifterAnswersFlow.first())
        vm.setFocus(true)
        vm.setCardioFamily(CardioFamily.RUN)
        vm.setCardioActivity(CardioActivity.ROAD_RUN)
        vm.setCardioStartByTime(true)
        vm.setCardioStartSeconds(1800)
        vm.setCardioSessions(3)
        vm.setRestDays(0)
        vm.setLiftDays(0)
        vm.setDistanceUnit(DistanceUnit.KM)
        val visited = mutableListOf<WizardStep>()
        repeat(6) {
            advanceUntilIdle()
            visited += vm.uiState.value.step
            vm.onNext()
        }
        advanceUntilIdle()
        assertEquals(listOf(WizardStep.FOCUS, WizardStep.CARDIO_ACTIVITY, WizardStep.CARDIO_START,
            WizardStep.CARDIO_SESSIONS, WizardStep.ALSO_LIFT, WizardStep.ROTATION), visited)
        assertTrue(vm.uiState.value.isComplete)
        val plan = db.planDao().allPlans().single()
        assertEquals("Running", plan.name)
        assertTrue(plan.offerCardioIncreases)
        assertEquals("A", plan.suggestedDayId)
        assertEquals(listOf("Easy", "Intervals", "Long"), repo.plans.programFlow.first().days.map { it.title })
        assertEquals(global, settings.globalLifterFlow.first())
        assertFalse(settings.hasLifterAnswersFlow.first())
        assertEquals(DistanceUnit.KM, settings.distanceUnitFlow.first())
        assertTrue(settings.wizardCompleteFlow.first())
    }

    @Test
    fun cardioOnlySetup_thenNewPlanWithLiftingRequiresLifterAnswers() = runVmTest {
        val first = newViewModel()
        advanceUntilIdle()
        first.setFocus(true)
        first.setRestDays(0)
        repeat(WizardStateBuilder.steps(WizardAnswers(cardioFirst = CardioFirst())).size) { first.onNext() }
        advanceUntilIdle()
        assertTrue(first.uiState.value.isComplete)
        assertTrue(settings.wizardCompleteFlow.first())
        assertFalse(settings.hasLifterAnswersFlow.first())
        val global = settings.globalLifterFlow.first()
        val original = db.planDao().allPlans().single()
        first.viewModelScope.cancel()

        val second = newViewModel()
        advanceUntilIdle()
        assertEquals(CardioFirst(), second.uiState.value.answers.cardioFirst)
        second.setLiftDays(2)
        val steps = WizardStateBuilder.steps(WizardAnswers(daysPerWeek = 2, cardioFirst = CardioFirst(liftDays = 2)))
        repeat(steps.indexOf(WizardStep.ABOUT_YOU)) { second.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, second.uiState.value.step)
        assertEquals(null, second.uiState.value.bodyweightLb)
        assertEquals(null, second.uiState.value.age)
        assertFalse(second.uiState.value.canContinue)

        repeat(steps.size) { second.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, second.uiState.value.step)
        assertEquals(listOf(original), db.planDao().allPlans())
        assertEquals(global, settings.globalLifterFlow.first())
        assertFalse(settings.hasLifterAnswersFlow.first())

        second.setBodyweight(185.0)
        second.onNext()
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, second.uiState.value.step)
        second.setAge(35)
        repeat(steps.size) { second.onNext() }
        advanceUntilIdle()
        assertTrue(second.uiState.value.isComplete)
        assertEquals(2, db.planDao().allPlans().size)
        assertEquals(original, db.planDao().plan(original.id))
        assertTrue(settings.hasLifterAnswersFlow.first())
        assertEquals(185.0, settings.globalLifterFlow.first().bodyweightLb, 0.0)
        assertEquals(35, settings.globalLifterFlow.first().age)
    }

    @Test
    fun cardioOnlyBackupRestore_thenNewPlanWithLiftingRequiresLifterAnswers() = runVmTest {
        val first = newViewModel()
        advanceUntilIdle()
        first.setFocus(true)
        first.setRestDays(0)
        repeat(WizardStateBuilder.steps(WizardAnswers(cardioFirst = CardioFirst())).size) { first.onNext() }
        advanceUntilIdle()
        assertTrue(first.uiState.value.isComplete)
        first.viewModelScope.cancel()

        val backupRepository = BackupRepository(db, settings, repo.plans, repo.history)
        val service = BackupService(backupRepository, journal)
        val text = service.export()
        assertFalse(backupRepository.exportSnapshot().hasLifterAnswers)
        settings.setGlobalLifter(GlobalLifter(bodyweightLb = 185.0, age = 35))
        assertTrue(settings.hasLifterAnswersFlow.first())
        service.import(text)
        assertFalse(settings.hasLifterAnswersFlow.first())
        assertFalse(backupRepository.exportSnapshot().hasLifterAnswers)
        assertTrue(settings.wizardCompleteFlow.first())
        val original = db.planDao().allPlans().single()

        val second = newViewModel()
        advanceUntilIdle()
        assertEquals(CardioFirst(), second.uiState.value.answers.cardioFirst)
        second.setLiftDays(2)
        val steps = WizardStateBuilder.steps(WizardAnswers(daysPerWeek = 2, cardioFirst = CardioFirst(liftDays = 2)))
        repeat(steps.indexOf(WizardStep.ABOUT_YOU)) { second.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, second.uiState.value.step)
        assertEquals(null, second.uiState.value.bodyweightLb)
        assertEquals(null, second.uiState.value.age)
        assertFalse(second.uiState.value.canContinue)
        repeat(steps.size) { second.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, second.uiState.value.step)
        assertEquals(listOf(original), db.planDao().allPlans())
    }

    @Test
    fun `cardio first with two lift days asks the lifting questions`() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setFocus(true)
        vm.setRestDays(0)
        repeat(4) { vm.onNext() }
        vm.setLiftDays(2)
        vm.onNext()
        advanceUntilIdle()
        assertEquals(WizardStep.EMPHASIS, vm.uiState.value.step)
        assertEquals(2, vm.uiState.value.answers.daysPerWeek)
        repeat(3) { vm.onNext() }
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, vm.uiState.value.step)
        vm.onNext()
        advanceUntilIdle()
        assertEquals(WizardStep.ABOUT_YOU, vm.uiState.value.step)
        vm.setBodyweight(185.0)
        vm.setAge(35)
        vm.onNext()
        vm.onNext()
        advanceUntilIdle()
        assertEquals(WizardStep.LIFTS, vm.uiState.value.step)
        vm.setLiftWeight("bb_back_squat", 135.0)
        vm.setLiftReps("bb_back_squat", 8)
        vm.onNext()
        advanceUntilIdle()
        assertEquals(WizardStep.ROTATION, vm.uiState.value.step)
        val days = vm.uiState.value.previewProgram!!.days
        assertEquals(listOf("Easy", "Full Body", "Intervals", "Full Body", "Long"), days.map { it.title })
        assertEquals(PrescribedTarget.LoadReps(135.0, 8), days[1].exercises.first().prescription?.target)
        vm.onNext()
        advanceUntilIdle()
        assertEquals("Running + lifting", db.planDao().allPlans().single().name)
        assertEquals(185.0, settings.globalLifterFlow.first().bodyweightLb, 0.0)
    }

    @Test
    fun `lifting first is today's flow after the first step`() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onNext()
        advanceUntilIdle()
        assertEquals(WizardStep.EMPHASIS, vm.uiState.value.step)
        vm.setBodyweight(235.0)
        vm.setAge(40)
        repeat(9) { vm.onNext() }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete)
        val program = repo.plans.programFlow.first()
        assertTrue(program.days.flatMap { it.exercises }.all { it.rule != null })
        assertEquals(ProgramGenerator.generate(WizardAnswers()), program.copy(days = program.days.map { day ->
            day.copy(exercises = day.exercises.map { it.copy(rule = null) })
        }))
        assertFalse(db.planDao().allPlans().single().offerCardioIncreases)
    }

    @Test
    fun `process death on cardio start keeps minutes and the other cardio answers`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        vm.setFocus(true)
        vm.onNext()
        vm.setCardioFamily(CardioFamily.RIDE)
        vm.setCardioActivity(CardioActivity.STATIONARY_BIKE)
        vm.onNext()
        vm.setCardioStartSeconds(2700)
        vm.setCardioStartMetres(4828)
        vm.setCardioSessions(6)
        vm.setRestDays(0)
        vm.setLiftDays(3)
        vm.setDistanceUnit(DistanceUnit.KM)
        val revived = newViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals(WizardStep.CARDIO_START, revived.uiState.value.step)
        assertEquals(CardioFirst(CardioActivity.STATIONARY_BIKE, Measure.Time(2700), 6, 3), revived.uiState.value.answers.cardioFirst)
        assertEquals(DistanceUnit.KM, revived.uiState.value.distanceUnit)
        revived.setCardioStartByTime(false)
        advanceUntilIdle()
        assertEquals(Measure.Distance(4828), revived.uiState.value.answers.cardioFirst?.comfortable)
    }

    @Test
    fun `new plan starts from the selected cardio recipe and saved distance unit`() = runVmTest {
        val answers = WizardAnswers(cardioFirst = CardioFirst(CardioActivity.TREADMILL_WALK, Measure.Distance(3200), 2))
        val id = repo.plans.createGeneratedPlan("Walking", answers, ProgramGenerator.generate(answers))
        repo.plans.publishCreatedPlan(id, answers, writeLifter = false)
        settings.setWizardComplete(true)
        settings.setDistanceUnit(DistanceUnit.KM)
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(answers, vm.uiState.value.answers)
        assertEquals(DistanceUnit.KM, vm.uiState.value.distanceUnit)
        assertFalse(vm.uiState.value.cardioStartByTime)
        assertEquals(WizardStep.FOCUS, vm.uiState.value.step)
    }

    @Test
    fun `first run distance unit follows the device weight unit`() = runVmTest {
        val vm = newViewModel(defaultUnit = WeightUnit.KG)
        advanceUntilIdle()
        assertEquals(DistanceUnit.KM, vm.uiState.value.distanceUnit)
    }
    @Test fun `cardio rest defaults to one and survives process death`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        vm.setFocus(true)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.answers.cardioFirst?.restDays)
        vm.setRestDays(2)
        val revived = newViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals(2, revived.uiState.value.answers.cardioFirst?.restDays)
        repeat(5) { revived.onNext() }
        advanceUntilIdle()
        assertEquals(listOf("Easy", "Intervals", "Rest", "Long", "Rest"),
            revived.uiState.value.previewProgram!!.days.map { it.title })
    }

}
