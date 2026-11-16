package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.PlanDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.serialization.hasSetupRecipe
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var appScope: CoroutineScope
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()
    private val calls = mutableListOf<String>()
    private var failCompletion = false
    private var failPublication = false
    private var failPreview = false
    private var beforePreviewRead: (() -> Unit)? = null
    private val completeKey = booleanPreferencesKey("wizard_complete")

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        appScope = CoroutineScope(dispatcher + SupervisorJob())
        val delegate = PreferenceDataStoreFactory.create(scope = storeScope) { File.createTempFile("setup-flow", ".preferences_pb") }
        settings = SettingsStore(object : DataStore<Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData { before ->
                if (transform(emptyPreferences())[completeKey] == true) {
                    check(!db.inTransaction())
                    calls += "setWizardComplete"
                    if (failCompletion) { failCompletion = false; throw IOException("completion write failed") }
                }
                transform(before)
            }
        })
        journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("setup-journal", ".preferences_pb")
        }, settings)
        val programDao = object : ProgramDao by db.programDao() {
            override suspend fun upsertDay(day: ProgramDayEntity) {
                if (day.position == 0) calls += "createPlannedPlan"
                db.programDao().upsertDay(day)
            }
        }
        val planDao = object : PlanDao by db.planDao() {
            override suspend fun updateOnToday(id: Long, onToday: Boolean) {
                check(!db.inTransaction())
                check(db.programDao().daysForPlan(id).isNotEmpty())
                calls += "publishPlannedPlan"
                if (failPublication) { failPublication = false; throw IOException("publication failed") }
                db.planDao().updateOnToday(id, onToday)
            }
        }
        val sessionDao = object : SessionDao by db.sessionDao() {
            override suspend fun lastWorkingSet(exerciseId: String): SessionSetEntity? {
                // A suspended fake DAO would hold Room's transaction while collectLatest cancels it.
                val beforeRead = beforePreviewRead
                beforePreviewRead = null
                beforeRead?.invoke()
                if (failPreview) { failPreview = false; throw IOException("preview failed") }
                return db.sessionDao().lastWorkingSet(exerciseId)
            }
        }
        repo = TrackerRepository(db, programDao, sessionDao, db.customExerciseDao(), settings,
            plans = PlanRepository(db, settings, programDao = programDao, sessionDao = sessionDao, planDao = planDao))
        runTest(dispatcher) {
            settings.setPlanSettingsFixupDone()
            settings.setOnTodayFixupDone()
        }
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        appScope.cancel(); storeScope.cancel(); db.close(); Dispatchers.resetMain()
    }
    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }
    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle(), repository: TrackerRepository = repo,
        defaultUnit: WeightUnit = WeightUnit.LB, preferences: SettingsStore = settings) = SetupFlowViewModel(
        repository.plans, handle, context, BackupService(BackupRepository(db, preferences, repository.plans, repository.history), journal),
        appScope, DeviceWeightUnitProvider { defaultUnit }, preferences,
    ).also { vm -> vms += vm; vm.viewModelScope.launch { vm.uiState.collect {} } }
    private fun answers(vm: SetupFlowViewModel) = SetupFlowStateBuilder.answers(vm.uiState.value.draft)
    private fun TestScope.plan(vm: SetupFlowViewModel) {
        repeat(SetupFlowStateBuilder.steps(vm.uiState.value.draft).size - 1) { vm.onNext() }
        advanceUntilIdle()
        assertEquals(SetupStep.PLAN, vm.uiState.value.step)
    }
    private fun TestScope.start(vm: SetupFlowViewModel) { plan(vm); vm.onNext(); advanceUntilIdle() }
    private fun ready(vm: SetupFlowViewModel) = vm.uiState.value.plan as PlanPreviewUi.Ready
    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

    @Test fun `a first run starts on Goal with defaults and offers restore`() = runVmTest {
        WeightUnit.entries.forEach { unit ->
            val vm = newViewModel(defaultUnit = unit); advanceUntilIdle()
            assertEquals(SetupStep.GOAL, vm.uiState.value.step)
            assertEquals(6, vm.uiState.value.stepCount)
            assertTrue(vm.uiState.value.firstRun && vm.uiState.value.restore.offered)
            assertEquals(SetupAnswers(unit = unit), answers(vm))
        }
    }
    @Test fun `a later run starts from the selected plan's setup answers`() = runVmTest {
        val a = SetupAnswers(goal = LiftGoal.MUSCLE, days = 4, equipment = EquipmentPreset.DUMBBELLS.equipment, experience = LiftExperience.UNDER_A_YEAR)
        repo.plans.publishPlannedPlan(repo.plans.createPlannedPlan("Muscle", a)); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        assertEquals(a, answers(vm)); assertFalse(vm.uiState.value.restore.offered)
    }
    private suspend fun TestScope.prefillWithInvalidMain(preset: EquipmentPreset, vararg mains: String) {
        val a = SetupAnswers(goal = LiftGoal.STRENGTH, equipment = preset.equipment,
            experience = LiftExperience.UNDER_A_YEAR)
        val id = repo.plans.createPlannedPlan("Imported", a)
        db.planDao().updateRecipe(id, SetupRecipeJson.encode(a.copy(mains = Planner.defaultMains(a.equipment).drop(mains.size) + mains)))
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        assertEquals(SetupStep.GOAL, vm.uiState.value.step)
        assertNull(vm.uiState.value.draft.mains)
        assertEquals(Planner.defaultMains(a.equipment), SetupFlowStateBuilder.currentMains(vm.uiState.value.draft))
        plan(vm)
        assertTrue(vm.uiState.value.plan is PlanPreviewUi.Ready)
    }
    @Test fun `an imported unknown main opens New plan with defaults and a ready preview`() = runVmTest {
        prefillWithInvalidMain(EquipmentPreset.FULL_GYM, "no_such_lift")
    }
    @Test fun `an imported unavailable main opens New plan with defaults and a ready preview`() = runVmTest {
        prefillWithInvalidMain(EquipmentPreset.DUMBBELLS, "bb_back_squat")
    }
    @Test fun `an imported recipe with two squats opens New plan with defaults and a ready preview`() = runVmTest {
        prefillWithInvalidMain(EquipmentPreset.FULL_GYM, "bb_back_squat", "goblet_squat")
    }
    @Test fun `a later run over an old wizard plan starts from defaults`() = runVmTest {
        val old = WizardAnswers()
        repo.plans.publishCreatedPlan(repo.plans.createGeneratedPlan("Old", old, ProgramGenerator.generate(old)), old)
        settings.setUnit(WeightUnit.KG); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        assertEquals(SetupAnswers(unit = WeightUnit.KG), answers(vm))
    }
    @Test fun `next walks every step and back returns`() = runVmTest {
        for (draft in listOf(SetupDraft(), SetupDraft(goal = LiftGoal.STRENGTH), SetupFlowFixtures.reference)) {
            val vm = newViewModel(); advanceUntilIdle()
            vm.setSubGoal(draft.goal); vm.setExperience(draft.experience); advanceUntilIdle()
            val steps = SetupFlowStateBuilder.steps(draft)
            steps.dropLast(1).forEach { assertEquals(it, vm.uiState.value.step); vm.onNext(); advanceUntilIdle() }
            assertEquals(SetupStep.PLAN, vm.uiState.value.step)
            steps.dropLast(1).reversed().forEach { vm.onBack(); advanceUntilIdle(); assertEquals(it, vm.uiState.value.step) }
            vm.onBack(); advanceUntilIdle(); assertEquals(SetupStep.GOAL, vm.uiState.value.step)
        }
    }
    @Test fun `setters reach the answers`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setSubGoal(LiftGoal.MUSCLE); vm.setDays(4); vm.setMinutes(75); vm.choosePreset(EquipmentPreset.FULL_GYM)
        vm.chooseExactly(); vm.toggleEquipment(Equipment.EZ_BAR); vm.setUnit(WeightUnit.KG)
        vm.setStepOption(StepRow.BAR, 1); vm.setStepOption(StepRow.DUMBBELL, 1); vm.setStepOption(StepRow.STACK, 1)
        vm.setExperience(LiftExperience.A_YEAR_OR_MORE); vm.setOlderAdult(true); vm.setMain(2, "db_bench")
        vm.setRecentWeight("db_bench", 50.0); vm.setRecentReps("db_bench", 8); vm.setRecentEffort("db_bench", Effort.HARD)
        vm.setBalanceDays(true); advanceUntilIdle()
        val a = answers(vm)
        assertEquals(LiftGoal.MUSCLE, a.goal); assertEquals(4, a.days); assertEquals(75, a.minutes)
        assertEquals(EquipmentPreset.FULL_GYM.equipment - Equipment.EZ_BAR, a.equipment)
        assertEquals(WeightUnit.KG, a.unit); assertEquals(WeightUnit.KG.toLb(1.25), a.steps.barLb)
        assertEquals(WeightUnit.KG.toLb(1.0), a.steps.dumbbellLb); assertEquals(WeightUnit.KG.toLb(2.5), a.steps.stackLb)
        assertEquals(LiftExperience.A_YEAR_OR_MORE, a.experience); assertTrue(a.olderAdult && a.balanceDays)
        assertTrue("db_bench" in a.mains.orEmpty()); assertEquals(Effort.HARD, a.recent.getValue("db_bench").effort)
        assertEquals(50.0, a.recent.getValue("db_bench").weightLb); assertEquals(8, a.recent.getValue("db_bench").reps)
        assertTrue(vm.uiState.value.draft.chooseExactly)
    }
    @Test fun `changing the gear resets chosen mains`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setSubGoal(LiftGoal.STRENGTH); vm.setMain(2, "db_bench"); vm.choosePreset(EquipmentPreset.DUMBBELLS); advanceUntilIdle()
        assertNull(vm.uiState.value.draft.mains)
        vm.setMain(2, "db_bench"); vm.toggleEquipment(Equipment.BARBELL); advanceUntilIdle()
        assertNull(vm.uiState.value.draft.mains)
    }
    @Test fun `calls before initialization are ignored`() = runVmTest {
        val vm = newViewModel(); vm.setDays(5); vm.onNext(); advanceUntilIdle()
        assertEquals(SetupDraft(), vm.uiState.value.draft)
    }
    @Test fun `the draft survives process death`() = runVmTest {
        val h = SavedStateHandle(); val vm = newViewModel(h); advanceUntilIdle()
        vm.setSubGoal(LiftGoal.STRENGTH); vm.setDays(4); vm.setMinutes(75); vm.setExperience(LiftExperience.A_YEAR_OR_MORE)
        repeat(6) { vm.onNext() }; vm.setRecentWeight("bb_back_squat", 275.0); vm.setRecentReps("bb_back_squat", 5); advanceUntilIdle()
        val revived = newViewModel(copyHandle(h)); advanceUntilIdle()
        assertEquals(SetupStep.RECENT, revived.uiState.value.step); assertEquals(answers(vm), answers(revived))
        h.keys().forEach { val value = h.get<Any>(it); assertTrue(value is String || value is Boolean || value is Long) }
    }
    @Test fun `an unreadable draft starts over`() = runVmTest {
        val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true, "setup_draft" to """{"v":9}"""))); advanceUntilIdle()
        assertEquals(SetupDraft(), vm.uiState.value.draft)
    }
    @Test fun `entering Your plan previews the current answers`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        var previewRead = false
        beforePreviewRead = {
            previewRead = true
            assertEquals(PlanPreviewUi.Loading, vm.uiState.value.plan); assertFalse(vm.uiState.value.canStart)
        }
        repeat(5) { vm.onNext() }; advanceUntilIdle()
        assertTrue(previewRead)
        assertEquals(repo.plans.previewPlan(answers(vm)).program.days.map { it.id }, ready(vm).rows.map { it.dayId })
    }
    @Test fun `a changed answer previews again and the newest wins`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); plan(vm)
        val states = mutableListOf<SetupFlowUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { states += it } }
        states.clear()
        beforePreviewRead = { vm.longerSessions() }
        vm.longerSessions(); advanceUntilIdle()
        assertEquals(90, answers(vm).minutes)
        assertTrue(states.any { it.plan == PlanPreviewUi.Loading && !it.canStart })
        states.forEach { state ->
            val preview = state.plan as? PlanPreviewUi.Ready
            if (preview != null) {
                assertEquals(90, state.draft.minutes)
                assertFalse(preview.longerAvailable)
            }
        }
        val expected = SetupFlowStateBuilder.planPreview(repo.plans.previewPlan(answers(vm)), answers(vm)) as PlanPreviewUi.Ready
        assertEquals(expected.rows, ready(vm).rows)
    }
    @Test fun `a preview invalidated in the same frame cannot start`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); plan(vm)
        vm.longerSessions(); vm.onNext()
        assertTrue(db.planDao().allPlans().isEmpty())
        advanceUntilIdle(); vm.onNext(); advanceUntilIdle()
        assertEquals(75, db.planDao().allPlans().single().setupAnswers(WeightUnit.LB)!!.minutes)
    }
    @Test fun `returning to the same answers refreshes the preview`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); plan(vm)
        vm.setMinutes(75); vm.setMinutes(60); advanceUntilIdle()
        assertTrue(vm.uiState.value.canStart)
        assertEquals(60, answers(vm).minutes)
    }
    @Test fun `a failed preview offers a retry`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); failPreview = true; plan(vm)
        assertEquals(PlanPreviewUi.Failed, vm.uiState.value.plan); vm.retryPreview(); advanceUntilIdle()
        assertTrue(vm.uiState.value.plan is PlanPreviewUi.Ready)
    }
    @Test fun `the preview comes back after process death on Your plan`() = runVmTest {
        val h = SavedStateHandle(); val vm = newViewModel(h); advanceUntilIdle(); plan(vm)
        vm.viewModelScope.cancel(); val revived = newViewModel(copyHandle(h)); advanceUntilIdle()
        assertTrue(revived.uiState.value.plan is PlanPreviewUi.Ready)
    }
    @Test fun `case 36 waits for the lifter to choose`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setSubGoal(LiftGoal.STRENGTH); vm.setExperience(LiftExperience.A_YEAR_OR_MORE); vm.setOlderAdult(true); vm.setDays(1); vm.setMinutes(30)
        advanceUntilIdle(); plan(vm); assertTrue(ready(vm).over); assertTrue(db.planDao().allPlans().isEmpty())
        assertEquals(4, ready(vm).rows.single().overMinutes)
        assertEquals(Planner.defaultMains(answers(vm).equipment), ready(vm).droppable)
        assertEquals(5, repo.plans.previewPlan(answers(vm)).program.days.single().exercises.count { it.rule?.role == SlotRole.MAIN })
        vm.dropLift("bb_row"); advanceUntilIdle(); assertEquals(4, answers(vm).mains!!.size)
        assertFalse(ready(vm).over)
        assertEquals(4, repo.plans.previewPlan(answers(vm)).program.days.single().exercises.count { it.rule?.role == SlotRole.MAIN })
        val fresh = newViewModel(); advanceUntilIdle()
        fresh.setSubGoal(LiftGoal.STRENGTH); fresh.setExperience(LiftExperience.A_YEAR_OR_MORE); fresh.setOlderAdult(true); fresh.setDays(1); fresh.setMinutes(30)
        advanceUntilIdle(); plan(fresh); fresh.longerSessions(); advanceUntilIdle(); assertEquals(45, answers(fresh).minutes)
        assertFalse(ready(fresh).over)
        fresh.setMinutes(30); advanceUntilIdle()
        assertEquals(30, answers(fresh).minutes)
        assertTrue(fresh.uiState.value.canStart); assertTrue(ready(fresh).over)
        fresh.onNext(); advanceUntilIdle()
        val after = fresh.uiState.value
        assertNull(after.finishError)
        assertTrue(after.isComplete, "not complete: step=${after.step} plan=${after.plan::class.simpleName} canStart=${after.canStart} plans=${db.planDao().allPlans().size}")
        assertEquals(1, db.planDao().allPlans().size)
        assertEquals(5, repo.plans.programFlow.first().days.single().exercises.count { it.rule?.role == SlotRole.MAIN })
    }
    @Test fun `the balance offer adds and removes days`() = runVmTest {
        // Audit 2026-11-13 decision 12: accepting is explicit; declining leaves the lifting day alone.
        val vm = newViewModel(); advanceUntilIdle(); vm.setOlderAdult(true); vm.setDays(1); advanceUntilIdle(); plan(vm)
        assertEquals(2, ready(vm).balanceOffer)
        vm.setBalanceDays(true); advanceUntilIdle(); assertEquals(listOf("Full Body", "Balance", "Balance"), ready(vm).rows.map { it.title })
        assertEquals(2, ready(vm).balanceAdded)
        vm.setBalanceDays(false); advanceUntilIdle(); assertEquals(2, ready(vm).balanceOffer)
        vm.setBalanceDays(true); advanceUntilIdle(); vm.onNext(); advanceUntilIdle()
        assertEquals(3, repo.plans.programFlow.first().days.size)
        assertTrue(db.planDao().allPlans().single().setupAnswers(WeightUnit.LB)!!.balanceDays)
        val fresh = newViewModel(); advanceUntilIdle(); fresh.setBalanceDays(false); advanceUntilIdle(); start(fresh)
        assertEquals(1, repo.plans.programFlow.first().days.size)
        assertFalse(db.planDao().allPlans().last().setupAnswers(WeightUnit.LB)!!.balanceDays)
    }
    @Test fun `start creates the planned plan and lands on Today`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); start(vm)
        val p = db.planDao().allPlans().single()
        assertTrue(vm.uiState.value.isComplete); assertEquals("GENERATED", p.origin); assertTrue(p.hasSetupRecipe)
        assertEquals(SetupAnswers(), p.setupAnswers(WeightUnit.LB)); assertEquals("General fitness, 3 days", p.name)
        assertEquals(p.id, settings.selectedPlanIdFlow.first()); assertTrue(p.onToday)
        assertEquals(WeightUnit.LB, settings.unitFlow.first()); assertTrue(settings.wizardCompleteFlow.first())
    }
    @Test fun `a New lifter on dumbbells gets three full-body days at the floor`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); vm.setMinutes(45); vm.choosePreset(EquipmentPreset.DUMBBELLS); advanceUntilIdle()
        val a = answers(vm); start(vm); val program = repo.plans.programFlow.first()
        assertEquals(Planner.plan(a).program, program)
        assertEquals(3, program.days.size); assertTrue(program.days.all { it.kind == ProgramDayKind.STRENGTH && it.title == "Full Body" })
        val mains = program.days.flatMap { it.exercises }.filter { it.rule?.role == SlotRole.MAIN }
        assertTrue(mains.all {
            val rule = requireNotNull(it.rule)
            it.structure == SetStructure.STRAIGHT && rule.target == RepTarget.Range(8, 15, 8) && rule.state.calibrating
        })
        assertEquals(3, mains.first { it.exerciseId == "db_shoulder" }.targetSets)
        assertTrue(mains.filter { it.exerciseId != "db_shoulder" }.all { it.targetSets == 2 })
        val id = db.planDao().allPlans().single().id
        db.programDao().exercisesForPlan(id).filter { slot -> mains.any { it.exerciseId == slot.exerciseId } && Equipment.DUMBBELL in ExerciseLibrary.get(slot.exerciseId).equipment }.forEach { slot ->
            assertTrue(SetJson.decodeSets(db.programDao().logForSlot(id, slot.dayId, slot.id, Slot.MAIN)!!.setsJson).all { it.weightLb == 10.0 })
        }
    }
    @Test fun `start twice quickly creates one plan`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); plan(vm); vm.onNext(); vm.onNext(); advanceUntilIdle()
        assertEquals(1, db.planDao().allPlans().size); assertEquals(1, calls.count { it == "createPlannedPlan" })
    }
    @Test fun `start writes the plan before marking setup complete`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); start(vm)
        assertEquals(listOf("createPlannedPlan", "publishPlannedPlan", "setWizardComplete"), calls)
    }
    @Test fun `a failed completion retries without another plan`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); failCompletion = true; start(vm)
        assertNotNull(vm.uiState.value.finishError); vm.onNext(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete); assertEquals(1, calls.count { it == "createPlannedPlan" })
    }
    @Test fun `a failed publication retries without another plan`() = runVmTest {
        val h = SavedStateHandle(); val vm = newViewModel(h); advanceUntilIdle(); failPublication = true; start(vm)
        assertNotNull(vm.uiState.value.finishError)
        val id = assertNotNull(h.get<Long>("setup_created_plan_id"))
        assertFalse(assertNotNull(db.planDao().plan(id)).onToday)
        vm.onNext(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete); assertEquals(id, db.planDao().allPlans().single().id)
        assertEquals(id, settings.selectedPlanIdFlow.first())
        assertTrue(assertNotNull(db.planDao().plan(id)).onToday)
        assertEquals(1, repo.plans.planSummariesFlow.first().size)
        assertEquals(1, calls.count { it == "createPlannedPlan" })
    }
    private suspend fun TestScope.retryAfterSettingsChange(change: (SetupFlowViewModel) -> Unit) {
        val h = SavedStateHandle(); val vm = newViewModel(h); advanceUntilIdle(); failPublication = true; start(vm)
        assertNotNull(vm.uiState.value.finishError)
        val old = db.planDao().allPlans().single()
        assertFalse(old.onToday)
        val before = answers(vm)
        change(vm); advanceUntilIdle()
        val current = answers(vm)
        assertTrue(before != current)
        assertEquals(old.recipeJson, SetupRecipeJson.encode(current))
        val preview = repo.plans.previewPlan(current)
        vm.onNext(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete)
        val id = assertNotNull(h.get<Long>("setup_created_plan_id"))
        assertTrue(id != old.id)
        val published = assertNotNull(db.planDao().plan(id))
        assertTrue(published.onToday)
        assertEquals(current, published.setupAnswers(current.unit))
        assertEquals(current.equipment, published.planSettings.equipment)
        assertEquals(current.steps, published.planSettings.loadSteps)
        assertEquals(current.unit, settings.unitFlow.first())
        assertEquals(current.unit.name, h.get<String>("setup_created_plan_unit"))
        assertEquals(preview.program, repo.plans.programFor(id).first())
        assertEquals(id, settings.selectedPlanIdFlow.first())
        assertEquals(old, db.planDao().plan(old.id))
        assertEquals(2, calls.count { it == "createPlannedPlan" })
    }
    @Test fun `changed equipment after failed publication creates and publishes the current plan`() = runVmTest {
        retryAfterSettingsChange { it.choosePreset(EquipmentPreset.DUMBBELLS) }
    }
    @Test fun `a changed load step after failed publication creates and publishes the current plan`() = runVmTest {
        retryAfterSettingsChange { it.setStepOption(StepRow.BAR, 1) }
    }
    @Test fun `a changed unit after failed publication creates and publishes the current plan`() = runVmTest {
        retryAfterSettingsChange { it.setUnit(WeightUnit.KG) }
    }
    @Test fun `a changed balance answer after failed publication creates and publishes the current recipe`() = runVmTest {
        val h = SavedStateHandle(); val vm = newViewModel(h); advanceUntilIdle()
        vm.setOlderAdult(true); vm.setDays(1); advanceUntilIdle()
        failPublication = true; start(vm)
        assertNotNull(vm.uiState.value.finishError)
        val old = db.planDao().allPlans().single()
        assertFalse(old.onToday); assertEquals(2, ready(vm).balanceOffer)
        vm.setBalanceDays(true); advanceUntilIdle(); vm.onNext(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete)
        val id = assertNotNull(h.get<Long>("setup_created_plan_id"))
        assertTrue(id != old.id)
        val published = assertNotNull(db.planDao().plan(id))
        assertTrue(published.onToday); assertTrue(assertNotNull(published.setupAnswers(WeightUnit.LB)).balanceDays)
        assertEquals(SetupRecipeJson.encode(answers(vm)), published.recipeJson)
        assertEquals(id, settings.selectedPlanIdFlow.first())
        assertEquals(old, db.planDao().plan(old.id))
        assertEquals(2, repo.plans.planSummariesFlow.first().size)
        assertEquals(2, calls.count { it == "createPlannedPlan" })
    }
    @Test fun `a changed answer after failed completion preserves the published plan and publishes the current recipe`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); failCompletion = true; start(vm)
        assertNotNull(vm.uiState.value.finishError)
        val old = db.planDao().allPlans().single()
        assertTrue(old.onToday)
        vm.setDays(4); advanceUntilIdle(); vm.onNext(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete)
        val id = assertNotNull(settings.selectedPlanIdFlow.first())
        assertTrue(id != old.id)
        assertEquals(SetupRecipeJson.encode(answers(vm)), assertNotNull(db.planDao().plan(id)).recipeJson)
        assertEquals(old, db.planDao().plan(old.id))
        assertEquals(2, db.planDao().allPlans().size)
    }
    @Test fun `review answers returns to Equipment with the draft preserved`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); vm.setMinutes(45); advanceUntilIdle(); plan(vm)
        val before = answers(vm); vm.reviewAnswers(); advanceUntilIdle()
        assertEquals(SetupStep.EQUIPMENT, vm.uiState.value.step); assertEquals(before, answers(vm))
        vm.onNext(); vm.onNext(); advanceUntilIdle(); assertTrue(vm.uiState.value.plan is PlanPreviewUi.Ready)
    }
    @Test fun `invalid recent numbers are omitted while the draft stays typed`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); vm.setExperience(LiftExperience.A_YEAR_OR_MORE)
        for (weight in listOf(Double.NaN, Double.POSITIVE_INFINITY, -5.0, 0.0)) {
            vm.setRecentWeight("bb_back_squat", weight); advanceUntilIdle()
            assertNull(vm.uiState.value.draft.recent["bb_back_squat"]!!.weightLb)
        }
        vm.setRecentWeight("bb_back_squat", 2500.0); vm.setRecentReps("bb_back_squat", 5); advanceUntilIdle()
        assertEquals(2500.0, vm.uiState.value.draft.recent["bb_back_squat"]!!.weightLb)
        assertTrue(answers(vm).recent.isEmpty())
        vm.setRecentWeight("bb_back_squat", 275.0); vm.setRecentReps("bb_back_squat", 11); advanceUntilIdle()
        assertTrue(answers(vm).recent.isEmpty())
    }
    @Test fun `the remembered plan survives recreation before a retry`() = runVmTest {
        val h = SavedStateHandle(); val vm = newViewModel(h); advanceUntilIdle(); failCompletion = true; start(vm)
        val id = h.get<Long>("setup_created_plan_id"); vm.viewModelScope.cancel()
        val revived = newViewModel(copyHandle(h)); advanceUntilIdle(); revived.onNext(); advanceUntilIdle()
        assertTrue(revived.uiState.value.isComplete); assertEquals(id, db.planDao().allPlans().single().id)
        assertEquals(1, calls.count { it == "createPlannedPlan" })
    }
    private suspend fun TestScope.invalidRemembered(delete: Boolean) {
        val vm = newViewModel(); advanceUntilIdle(); failCompletion = true; start(vm)
        val id = db.planDao().allPlans().single().id
        db.programDao().deleteLogsForPlan(id); db.programDao().deleteExercisesForPlan(id); db.programDao().deleteDaysForPlan(id)
        if (delete) db.planDao().deleteAllPlans()
        vm.onNext(); advanceUntilIdle(); assertTrue(vm.uiState.value.isComplete)
        assertEquals(2, calls.count { it == "createPlannedPlan" }); assertTrue(repo.plans.programFlow.first().days.isNotEmpty())
    }
    @Test fun `a deleted remembered plan is created again`() = runVmTest { invalidRemembered(true) }
    @Test fun `a remembered plan without days is filled`() = runVmTest { invalidRemembered(false) }
    @Test fun `start on an existing install adds a second plan`() = runVmTest {
        val id = repo.plans.createPlannedPlan("Original", SetupAnswers())
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val p = db.planDao().plan(id); val days = db.programDao().daysForPlan(id)
        val slots = db.programDao().exercisesForPlan(id); val logs = db.programDao().logsForPlan(id)
        val vm = newViewModel(); advanceUntilIdle(); start(vm)
        assertEquals(2, db.planDao().allPlans().size); assertEquals(p, db.planDao().plan(id))
        assertEquals(days, db.programDao().daysForPlan(id)); assertEquals(slots, db.programDao().exercisesForPlan(id)); assertEquals(logs, db.programDao().logsForPlan(id))
    }
    @Test fun `start steps aside when a restore landed under a first run`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); plan(vm)
        val id = repo.plans.createPlannedPlan("Restored", SetupAnswers(days = 1)); repo.plans.publishPlannedPlan(id)
        settings.setWizardComplete(true); calls.clear(); vm.onNext(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete); assertTrue(calls.isEmpty()); assertEquals(id, db.planDao().allPlans().single().id)
    }
    @Test fun `after start the workout opens the squat at the bar calibrating`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle(); start(vm)
        val id = db.planDao().allPlans().single().id
        DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo.history, settings = settings),
            SavedStateHandle(), FixedCardioClock(), InertCardioAlarm, PhoneRestController(settings, NoOpRestRuntime),
            settings = settings, plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history).also { vms += it }
        advanceUntilIdle()
        val track = repo.dayTracksFlow(id, "A").first().tracks.first { it.slot == Slot.MAIN }
        assertEquals("bb_back_squat", track.exerciseId)
        assertEquals(2, track.sets.size)
        assertTrue(track.sets.all { it.weightLb == 45.0 && it.reps == 8 })
        assertTrue(repo.plans.daySlotsFlow("A").first().first().exercise.rule!!.state.calibrating)
    }
    @Test fun `a later run does not offer restore`() = runVmTest {
        settings.setWizardComplete(true); val vm = newViewModel(); advanceUntilIdle(); assertFalse(vm.uiState.value.restore.offered)
    }
    @Test fun `a later run ignores a restore request`() = runVmTest {
        settings.setWizardComplete(true); val vm = newViewModel(); advanceUntilIdle()
        vm.restoreFromBackup(Uri.parse("content://nowhere/backup.json")); advanceUntilIdle()
        assertFalse(vm.uiState.value.restore.inFlight); assertNull(vm.uiState.value.restore.error)
    }
    @Test fun `the first-run latch survives recreation`() = runVmTest {
        val h = SavedStateHandle(); newViewModel(h); advanceUntilIdle(); settings.setWizardComplete(true)
        val vm = newViewModel(copyHandle(h)); advanceUntilIdle(); assertTrue(vm.uiState.value.restore.offered)
    }
}
