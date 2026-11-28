package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.domain.planner.LiftTest
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RecentNumber
import cloud.trotter.log.strength.domain.standards.TestPick
import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.data.insertRuledOldWizardPlan
import cloud.trotter.log.strength.data.runLaunchFixups
import java.time.ZoneId
import cloud.trotter.log.strength.data.SpacingReason
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.planner.SprintFloor
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
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.serialization.hasSetupRecipe
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.model.CardioActivity
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
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.TestGoalJson
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.standards.TestRef

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupFlowViewModelTest {
    private lateinit var cardioRunPrefs: CardioRunPrefs
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
        cardioRunPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("SetupFlowViewModelTest-cardio", ".preferences_pb")
        })
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
        }, settings, cardioRunPrefs = cardioRunPrefs)
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
        repository.plans, handle, context,
        BackupService(BackupRepository(db, preferences, repository.plans, repository.history,
            cardioRunPrefs = cardioRunPrefs), journal),
        appScope, DeviceWeightUnitProvider { defaultUnit }, preferences,
    ).also { vm -> vms += vm; vm.viewModelScope.launch { vm.uiState.collect {} } }
    private fun answers(vm: SetupFlowViewModel) = SetupFlowStateBuilder.answers(vm.uiState.value.draft)
    private fun TestScope.plan(vm: SetupFlowViewModel) {
        repeat(SetupFlowStateBuilder.steps(vm.uiState.value.draft).size - 1) { vm.onNext() }
        advanceUntilIdle()
        assertEquals(SetupStep.PLAN, vm.uiState.value.step)
    }
    private fun TestScope.start(vm: SetupFlowViewModel) { plan(vm); vm.onNext(); advanceUntilIdle() }
    private fun TestScope.backToGoal(vm: SetupFlowViewModel) {
        repeat(SetupFlowStateBuilder.steps(vm.uiState.value.draft).size) {
            if (vm.uiState.value.step != SetupStep.GOAL) { vm.onBack(); advanceUntilIdle() }
        }
        assertEquals(SetupStep.GOAL, vm.uiState.value.step)
    }
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
        val old = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings)
        db.runLaunchFixups(settings)
        repo.plans.publishPlannedPlan(old)
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
    @Test fun `the Together sequence keeps a run`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.BOTH); advanceUntilIdle()
        while (vm.uiState.value.step != SetupStep.TOGETHER) { vm.onNext(); advanceUntilIdle() }
        vm.setTogether(BothAnswers.Together.SEPARATE_DAYS)
        while (vm.uiState.value.step != SetupStep.WEEK) { vm.onBack(); advanceUntilIdle() }
        vm.setDays(1); advanceUntilIdle()
        while (vm.uiState.value.step != SetupStep.PLAN) { vm.onNext(); advanceUntilIdle() }
        assertFalse(SetupStep.TOGETHER in SetupFlowStateBuilder.steps(vm.uiState.value.draft))
        assertTrue(ready(vm).rows.any { it.cardio != null })
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, vm.uiState.value.draft.together)
        vm.onNext(); advanceUntilIdle()
        val stored = assertNotNull(db.planDao().allPlans().single().setupAnswers(WeightUnit.LB))
        assertEquals(BothAnswers.Together.SAME_VISIT, stored.both.together)
    }

    @Test fun `an invalid event time cannot advance`() = runVmTest {
        val draft = SetupFlowFixtures.run.copy(step = SetupStep.EVENT, runGoal = RunGoal.MARATHON)
        val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true,
            "setup_draft" to SetupDraftJson.encode(draft))))
        advanceUntilIdle()
        for (seconds in listOf(INVALID_GOAL_TIME, 0, 210)) {
            vm.setGoalSeconds(seconds); vm.onNext(); advanceUntilIdle()
            assertEquals(SetupStep.EVENT, vm.uiState.value.step)
            assertNull(answers(vm).run.goalSeconds)
        }
        vm.setGoalSeconds(12600); vm.onNext(); advanceUntilIdle()
        assertEquals(SetupStep.WHERE, vm.uiState.value.step)
        assertEquals(12600, answers(vm).run.goalSeconds)
        vm.onBack(); vm.setGoalSeconds(null); vm.onNext(); advanceUntilIdle()
        assertEquals(SetupStep.WHERE, vm.uiState.value.step)
        assertNull(answers(vm).run.goalSeconds)
    }

    @Test fun `changing an event invalidates a time below the new floor`() = runVmTest {
        val draft = SetupFlowFixtures.run.copy(step = SetupStep.EVENT, runGoal = RunGoal.MIDDLE_DISTANCE,
            runEvent = RunEvent.M800, goalSeconds = 101)
        val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true,
            "setup_draft" to SetupDraftJson.encode(draft))))
        advanceUntilIdle()
        vm.setRunEvent(RunEvent.M1500); vm.onNext(); advanceUntilIdle()
        assertEquals(INVALID_GOAL_TIME, vm.uiState.value.draft.goalSeconds)
        assertEquals(SetupStep.EVENT, vm.uiState.value.step)
        vm.setRunEvent(RunEvent.M800); advanceUntilIdle()
        assertEquals(INVALID_GOAL_TIME, vm.uiState.value.draft.goalSeconds)
        vm.setGoalSeconds(101); vm.setRunGoal(RunGoal.MARATHON); advanceUntilIdle()
        assertEquals(INVALID_GOAL_TIME, vm.uiState.value.draft.goalSeconds)

    }

    @Test fun `goal times clear only across the fitness test boundary`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.RUN); vm.setRunGoal(RunGoal.MARATHON); vm.setGoalSeconds(12600)
        vm.setRunGoal(RunGoal.HALF_MARATHON); advanceUntilIdle()
        assertEquals(12600, vm.uiState.value.draft.goalSeconds)
        vm.setRunGoal(RunGoal.FITNESS_TEST); advanceUntilIdle()
        assertNull(vm.uiState.value.draft.goalSeconds)
        vm.setGoalSeconds(960); vm.setRunGoal(RunGoal.FITNESS_TEST); advanceUntilIdle()
        assertEquals(960, vm.uiState.value.draft.goalSeconds)
        vm.setRunGoal(RunGoal.HALF_MARATHON); advanceUntilIdle()
        assertNull(vm.uiState.value.draft.goalSeconds)
    }

    @Test fun `process death keeps every run answer`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle); advanceUntilIdle()
        vm.setBranch(PlanBranch.BOTH); vm.setCardioFirst(true); vm.setRunGoal(RunGoal.ULTRA)
        vm.setRunExperience(RunExperience.SOME); vm.setRunningInjury(true); vm.setRunEvent(RunEvent.MI50)
        vm.setEventDate(21000); vm.setGoalSeconds(30000); vm.setTestScore(50)
        vm.setRecent(21097, 6600); vm.setLongest(3600); vm.setLongestByDistance(true); vm.setLongest(30000)
        vm.setActivity(CardioActivity.TRAIL_RUN); vm.setMarathonBest(14400)
        vm.setTogether(BothAnswers.Together.SEPARATE_DAYS); vm.setStrengthBlocks(1); advanceUntilIdle()
        val original = vm.uiState.value.draft
        for (step in listOf(SetupStep.ORDER, SetupStep.RUN_GOAL, SetupStep.ABOUT, SetupStep.EVENT,
            SetupStep.WHERE, SetupStep.TOGETHER, SetupStep.PLAN)) {
            val copied = copyHandle(handle)
            copied["setup_draft"] = SetupDraftJson.encode(original.copy(step = step))
            val restored = newViewModel(copied); advanceUntilIdle()
            assertEquals(original.copy(step = step), restored.uiState.value.draft)
            assertEquals(answers(vm), answers(restored))
        }
    }

    @Test fun `Start writes a run recipe`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.RUN); vm.setRunGoal(RunGoal.MARATHON); advanceUntilIdle()
        val expected = answers(vm)
        start(vm)
        val stored = db.planDao().allPlans().single()
        assertEquals(expected, stored.setupAnswers(WeightUnit.LB))
        assertEquals(RunPhase.LADDER, SetupRecipeJson.phase(assertNotNull(stored.recipeJson)))
    }

    @Test fun `a run publish retry reuses only matching answers`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.RUN); advanceUntilIdle()
        failPublication = true; start(vm)
        val first = db.planDao().allPlans().single().id
        failPublication = true; vm.onNext(); advanceUntilIdle()
        assertEquals(listOf(first), db.planDao().allPlans().map { it.id })
        vm.setRunningInjury(true); advanceUntilIdle()
        vm.onNext(); advanceUntilIdle()
        assertEquals(2, db.planDao().allPlans().size)
        assertTrue(vm.uiState.value.isComplete)
        assertTrue(assertNotNull(db.planDao().allPlans().last().setupAnswers(WeightUnit.LB)).run.runningInjury)
    }

    @Test fun `plan names fit forty characters`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        for (branch in listOf(PlanBranch.RUN, PlanBranch.BOTH)) for (goal in RunGoal.entries)
            for (lift in LiftGoal.entries) for (days in 1..6) {
                val a = SetupFlowStateBuilder.answers(SetupDraft(branch = branch, runGoal = goal, goal = lift, days = days))
                assertTrue(vm.planName(a).length <= 40, vm.planName(a))
            }
        assertEquals("Lifting and Just cardio fitness, 6 days", vm.planName(SetupFlowStateBuilder.answers(
            SetupDraft(branch = PlanBranch.BOTH, days = 6))))
    }
    @Test fun `Start persists Strength defaults and main set rest for a pure sprint plan`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.SPRINT); advanceUntilIdle()
        val input = answers(vm)
        val expected = Planner.plan(input)
        start(vm)
        val stored = db.planDao().allPlans().single()
        assertEquals(LiftGoal.STRENGTH, stored.planSettings.defaultsFor)
        val saved = assertNotNull(stored.setupAnswers(WeightUnit.LB))
        assertFalse(saved.olderAdult)
        assertEquals(LiftGoal.GENERAL, SetupFlowStateBuilder.draftFrom(saved).goal)
        val plannedMain = expected.program.days.first().exercises.first { it.rule?.role == SlotRole.MAIN }
        val persistedMain = repo.plans.programFor(stored.id).first().days.first().exercises
            .first { it.rule?.role == SlotRole.MAIN }
        assertEquals(plannedMain.rule, persistedMain.rule)
        val overrides = settings.restSettingsFlow.first().overrides
        assertTrue(overrides.isEmpty())
        val entry = ExerciseLibrary.get(persistedMain.exerciseId)
        val expectedRest = RestPolicy.effectiveRestSeconds(SetKind.WORK, entry.tracking, overrides,
            plannedMain.rule, input.slotGoal)
        assertEquals(180, expectedRest)
        assertEquals(expectedRest, RestPolicy.effectiveRestSeconds(SetKind.WORK, entry.tracking, overrides,
            persistedMain.rule, stored.planSettings.defaultsFor))
    }

    @Test fun `Start persists Strength defaults and strength block rest for a Run plan`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.RUN); vm.setStrengthBlocks(2); advanceUntilIdle()
        val input = answers(vm)
        assertEquals(LiftGoal.GENERAL, input.goal)
        val expected = Planner.plan(input)
        start(vm)
        val stored = db.planDao().allPlans().single()
        assertEquals(LiftGoal.STRENGTH, stored.planSettings.defaultsFor)
        assertEquals(2, assertNotNull(stored.setupAnswers(WeightUnit.LB)).run.strengthBlocks)
        val plannedSecondary = expected.program.days.first().exercises.first { it.rule?.role == SlotRole.SECONDARY }
        val persistedSecondary = repo.plans.programFor(stored.id).first().days.first().exercises
            .first { it.rule?.role == SlotRole.SECONDARY }
        assertEquals(plannedSecondary.rule, persistedSecondary.rule)
        val overrides = settings.restSettingsFlow.first().overrides
        assertTrue(overrides.isEmpty())
        val entry = ExerciseLibrary.get(persistedSecondary.exerciseId)
        val expectedRest = RestPolicy.effectiveRestSeconds(SetKind.WORK, entry.tracking, overrides,
            plannedSecondary.rule, input.slotGoal)
        assertEquals(120, expectedRest)
        assertEquals(expectedRest, RestPolicy.effectiveRestSeconds(SetKind.WORK, entry.tracking, overrides,
            persistedSecondary.rule, stored.planSettings.defaultsFor))
    }

    @Test fun `Start preserves the chosen lift goal for Lift and Both plans`() = runVmTest {
        for (draft in listOf(SetupDraft(), SetupFlowFixtures.both, SetupFlowFixtures.bothSprint)) {
            for (goal in LiftGoal.entries) {
                val chosen = draft.copy(goal = goal)
                val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true,
                    "setup_draft" to SetupDraftJson.encode(chosen))))
                advanceUntilIdle()
                start(vm)
                val stored = db.planDao().allPlans().last()
                assertEquals(goal, stored.planSettings.defaultsFor)
                assertEquals(goal, assertNotNull(stored.setupAnswers(WeightUnit.LB)).goal)
            }
        }
    }

    @Test fun `Start writes a sprint plan whose careful answer reaches Start`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.SPRINT); vm.setFortyOrOlder(true); advanceUntilIdle()
        start(vm)
        assertTrue(vm.uiState.value.isComplete)
        val plan = db.planDao().allPlans().single()
        val saved = assertNotNull(plan.setupAnswers(WeightUnit.LB))
        assertTrue(saved.sprint.fortyOrOlder)
        assertTrue(saved.careful)
        val program = repo.plans.programFlow.first()
        val start = assertNotNull(DayScreenBuilder.sprintStart(program.days.first(), plan, emptyList(), emptyMap(),
            1_000L, program, WeightUnit.LB, ZoneId.of("UTC")))
        assertEquals(3, start.spacingDays)
        assertEquals(SpacingReason.FORTY_OR_OLDER, start.reason)
    }

    @Test fun `a dropped main cannot break the Both sprint floor`() = runVmTest {
        val draft = SetupFlowFixtures.bothSprint.copy(step = SetupStep.PLAN, goal = LiftGoal.STRENGTH,
            mains = listOf("bb_back_squat", "bb_bench", "ohp", "bb_row"), minutes = 30, days = 2,
            fortyOrOlder = true, experience = LiftExperience.A_YEAR_OR_MORE)
        val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true, "setup_draft" to SetupDraftJson.encode(draft))))
        advanceUntilIdle()
        assertFalse("bb_back_squat" in ready(vm).droppable)
        vm.dropLift("bb_back_squat"); advanceUntilIdle()
        assertNull(Planner.sprintLowerMainMissing(answers(vm)))
        assertTrue("bb_back_squat" in SetupFlowStateBuilder.currentMains(vm.uiState.value.draft))
    }

    @Test fun `a missing lower main shows its floor before preview and reviews lifts`() = runVmTest {
        val draft = SetupFlowFixtures.sprintFloor.copy(step = SetupStep.PLAN)
        val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true, "setup_draft" to SetupDraftJson.encode(draft))))
        advanceUntilIdle()
        assertEquals(PlanPreviewUi.NeedsLowerMain(SprintFloor.SQUAT_OR_HINGE), vm.uiState.value.plan)
        assertFalse(vm.uiState.value.canStart)
        vm.reviewAnswers(); advanceUntilIdle()
        assertEquals(SetupStep.LIFTS, vm.uiState.value.step)
        assertFalse(SetupFlowStateBuilder.canAdvance(vm.uiState.value.draft))
    }

    @Test fun `sprint longer sessions goes to the full dose`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.SPRINT); vm.setDays(2); vm.setSprintedRecently(true); advanceUntilIdle()
        plan(vm)
        assertEquals(75, assertNotNull(ready(vm).sprint).fullDoseMinutes)
        vm.longerSessions(); advanceUntilIdle()
        assertEquals(75, answers(vm).minutes)
        vm.setMinutes(60); vm.setSprintEvent(SprintEvent.M200); advanceUntilIdle()
        assertEquals(90, assertNotNull(ready(vm).sprint).fullDoseMinutes)
        vm.longerSessions(); advanceUntilIdle()
        assertEquals(90, answers(vm).minutes)
    }

    @Test fun `sprint plan names fit forty characters`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        for (branch in listOf(PlanBranch.SPRINT, PlanBranch.BOTH)) for (event in SprintEvent.entries) for (days in 1..6) {
            val draft = SetupDraft(branch = branch, sprintCardio = branch == PlanBranch.BOTH, sprintEvent = event, days = days)
            assertTrue(vm.planName(SetupFlowStateBuilder.answers(draft)).length <= 40)
        }
        assertEquals("Lifting and 400 m sprint, 6 days", vm.planName(SetupFlowStateBuilder.answers(
            SetupFlowFixtures.bothSprint.copy(sprintEvent = SprintEvent.M400, days = 6))))
        assertEquals("40 yd sprint, 1 day", vm.planName(SetupFlowStateBuilder.answers(
            SetupFlowFixtures.sprint.copy(sprintEvent = SprintEvent.YD40, days = 1))))
    }

    @Test fun `a fresh Both flow choosing sprint at Run goal never reaches Test`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.BOTH); advanceUntilIdle()
        for (step in listOf(SetupStep.GOAL, SetupStep.ORDER, SetupStep.SUB_GOAL)) {
            assertEquals(step, vm.uiState.value.step)
            vm.onNext(); advanceUntilIdle()
        }
        assertEquals(SetupStep.RUN_GOAL, vm.uiState.value.step)
        vm.setSprintEvent(SprintEvent.M100); vm.onNext(); advanceUntilIdle()
        assertFalse(SetupStep.TEST in SetupFlowStateBuilder.steps(vm.uiState.value.draft))
        for (step in listOf(SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT)) {
            assertEquals(step, vm.uiState.value.step)
            vm.onNext(); advanceUntilIdle()
        }
        assertEquals(SetupStep.PLAN, vm.uiState.value.step)
    }

    @Test fun `a fresh Both flow choosing a non sprint run goal skips Your test`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.BOTH); advanceUntilIdle()
        for (step in listOf(SetupStep.GOAL, SetupStep.ORDER, SetupStep.SUB_GOAL)) {
            assertEquals(step, vm.uiState.value.step)
            vm.onNext(); advanceUntilIdle()
        }
        assertEquals(SetupStep.RUN_GOAL, vm.uiState.value.step)
        vm.setRunGoal(RunGoal.FIRST_5K); vm.onNext(); advanceUntilIdle()
        assertEquals(SetupStep.WEEK, vm.uiState.value.step)
    }

    @Test fun `saved Both drafts on Test and Run goal resume by step name`() = runVmTest {
        for (step in listOf(SetupStep.TEST, SetupStep.RUN_GOAL)) {
            val handle = SavedStateHandle(mapOf("setup_initialized" to true, "setup_first_run" to true,
                "setup_draft" to """{"v":1,"branch":"BOTH","step":"${step.name}","tests":[{"eventId":"aft_hrp","required":40,"lastResult":24}]}"""))
            val vm = newViewModel(handle); advanceUntilIdle()
            assertEquals(step, vm.uiState.value.step)
            assertEquals(listOf(TestDraft("aft_hrp", 40, 24)), vm.uiState.value.draft.tests)
            vm.onNext(); advanceUntilIdle()
            assertEquals(if (step == SetupStep.RUN_GOAL) SetupStep.TEST else SetupStep.WEEK, vm.uiState.value.step)
        }
    }

    @Test fun `a Lift plan with a test goal is created with its goal and prep slots`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        vm.chooseFitnessTest(); vm.onNext(); advanceUntilIdle()
        assertEquals(SetupStep.TEST, vm.uiState.value.step)
        vm.pickTest(TestPick.Library(
            ExerciseLibrary.get("hand_release_pushup")))
        vm.setTestEdition("hand_release_pushup", "aft_hrp")
        vm.onNext(); advanceUntilIdle()
        assertEquals(SetupStep.TEST, vm.uiState.value.step)
        vm.setTestRequired("aft_hrp", 40)
        vm.setTestLast("aft_hrp", 24)
        vm.setTestDate(21000)
        advanceUntilIdle()
        val revived = newViewModel(copyHandle(handle))
        advanceUntilIdle()
        assertEquals(listOf(TestDraft("aft_hrp", 40, 24, 120)), revived.uiState.value.draft.tests)
        plan(revived)
        revived.onNext(); advanceUntilIdle()
        val created = db.planDao().allPlans().single()
        val hrp = TestRef("aft_hrp", 1, 120)
        assertEquals(TestGoal(listOf(TestEventGoal(hrp, 40)), 21000), TestGoalJson.decode(created.testGoalJson))
        val slot = db.programDao().exercisesForDay(created.id, "A")
            .single { PrescriptionJson.decode(it.prescriptionJson)?.test == hrp }
        assertEquals("hand_release_pushup", slot.exerciseId)
    }

    @Test fun `Review answers and save preserve a Both Muscle two mile plan at four days`() = runVmTest {
        val original = SetupAnswers(branch = PlanBranch.BOTH, goal = LiftGoal.MUSCLE, days = 4,
            experience = LiftExperience.A_YEAR_OR_MORE, mains = Planner.defaultMains(EquipmentPreset.FULL_GYM.equipment),
            recent = mapOf("bb_bench" to RecentNumber(185.0, 5, Effort.GOOD)),
            run = RunAnswers(goal = RunGoal.FITNESS_TEST,
                experience = RunExperience.SOME, goalSeconds = 960, longest = Measure.Time(1500)),
            both = BothAnswers(cardioFirst = false))
        val id = repo.plans.createPlannedPlan("Both test", original)
        val before = repo.plans.programFor(id).first()
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        val expected = original.copy(both = original.both.copy(together = BothAnswers.Together.SEPARATE_DAYS))
        assertEquals(expected, answers(vm))
        for (step in listOf(SetupStep.SUB_GOAL, SetupStep.ORDER, SetupStep.LIFTS, SetupStep.RECENT)) {
            assertTrue(step in SetupFlowStateBuilder.steps(vm.uiState.value.draft))
        }
        plan(vm); vm.reviewAnswers(); advanceUntilIdle()
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, vm.uiState.value.draft.together)
        assertEquals(expected, answers(vm))
        plan(vm); vm.onNext(); advanceUntilIdle()
        val saved = db.planDao().allPlans().last()
        assertEquals(expected, saved.setupAnswers(WeightUnit.LB))
        assertEquals(before, repo.plans.programFor(saved.id).first())
    }

    @Test fun `Review answers adding Push-Up to a legacy Run test uses Strength through save`() = runVmTest {
        val original = SetupAnswers(branch = PlanBranch.RUN,
            run = RunAnswers(goal = RunGoal.FITNESS_TEST, goalSeconds = 960))
        val id = repo.plans.createPlannedPlan("Run test", original)
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        assertEquals(original, answers(vm))
        plan(vm); vm.reviewAnswers(); advanceUntilIdle()
        assertEquals(SetupStep.TEST, vm.uiState.value.step)
        assertFalse(vm.uiState.value.draft.cardioFirst)
        val pick = TestPick.Library(ExerciseLibrary.get("pushup"))
        vm.pickTest(pick); vm.setTestRequired("custom_pushup_window", 30); advanceUntilIdle()
        assertEquals(PlanBranch.BOTH, answers(vm).branch)
        assertEquals(LiftGoal.STRENGTH, answers(vm).goal)
        assertTrue(vm.uiState.value.draft.cardioFirst)
        assertFalse(SetupStep.LIFTS in SetupFlowStateBuilder.steps(vm.uiState.value.draft))
        val fresh = SetupDraft().chooseFitnessTest().pick(TestPick.Run(FitnessTest.TWO_MILE)).pick(pick)
            .copy(goalSeconds = 960,
                tests = listOf(TestDraft("custom_pushup_window", 30, windowSeconds = 60)))
        assertFalse(SetupStep.LIFTS in SetupFlowStateBuilder.steps(fresh))
        val expected = SetupFlowStateBuilder.answers(fresh)
        assertEquals(expected, answers(vm))
        val expectedProgram = Planner.plan(expected).program
        assertTrue(expectedProgram.days.any { it.exercises.isNotEmpty() })
        assertEquals(expectedProgram.days.map { it.exercises }, Planner.plan(answers(vm)).program.days.map { it.exercises })
        plan(vm); vm.onNext(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isComplete)
        val saved = db.planDao().allPlans().last()
        assertEquals(expected, saved.setupAnswers(WeightUnit.LB))
        assertEquals(expectedProgram, repo.plans.programFor(saved.id).first())
        assertEquals(original, assertNotNull(db.planDao().plan(id)).setupAnswers(WeightUnit.LB))
    }

    @Test fun `Review answers and save restore a lift only pick N and the live Required weight`() = runVmTest {
        val lift = LiftTest("incline_db", 5)
        val original = SetupAnswers(goal = LiftGoal.STRENGTH, lifts = listOf(lift),
            mains = Planner.defaultMains(EquipmentPreset.FULL_GYM.equipment).map { if (it == "bb_bench") lift.exerciseId else it })
        val id = repo.plans.createPlannedPlan("Lift test", original, mapOf(lift.exerciseId to 100.0))
        repo.plans.setPlanGoal(id, lift.exerciseId, 110.0)
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val handle = SavedStateHandle()
        val vm = newViewModel(handle); advanceUntilIdle()
        assertEquals(listOf(LiftTestDraft(lift.exerciseId, 5, 110.0)), vm.uiState.value.draft.testLifts)
        assertEquals(PlanBranch.LIFT, answers(vm).branch)
        plan(vm); vm.reviewAnswers(); advanceUntilIdle()
        assertEquals(SetupStep.TEST, vm.uiState.value.step)
        val revived = newViewModel(copyHandle(handle)); advanceUntilIdle()
        assertEquals(listOf(LiftTestDraft(lift.exerciseId, 5, 110.0)), revived.uiState.value.draft.testLifts)
        plan(revived); revived.onNext(); advanceUntilIdle()
        val saved = db.planDao().allPlans().last()
        assertEquals(listOf(lift), assertNotNull(saved.setupAnswers(WeightUnit.LB)).lifts)
        assertEquals(110.0, saved.planSettings.goalsLb[lift.exerciseId])
        assertEquals("Fitness test, 3 days", saved.name)
    }

    @Test fun `Review answers and save restore the legacy Army hand release event`() = runVmTest {
        val original = SetupAnswers(tests = listOf(TestAnswer(
            TestEventGoal(TestRef("aft_hrp", 1, 120), 40), 31)), testDateEpochDay = 21000)
        val id = repo.plans.createPlannedPlan("Army", original)
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        assertEquals(true, vm.uiState.value.draft.fitnessTest)
        assertEquals(listOf(TestDraft("aft_hrp", 40, 31)), vm.uiState.value.draft.tests)
        assertEquals(120, answers(vm).tests.single().goal.ref.windowSeconds)
        plan(vm); vm.reviewAnswers(); advanceUntilIdle()
        assertEquals(original, answers(vm))
        plan(vm); vm.onNext(); advanceUntilIdle()
        assertEquals(original, db.planDao().allPlans().last().setupAnswers(WeightUnit.LB))
    }

    @Test fun `the forward test flow derives Together until the user chooses`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.chooseFitnessTest()
        vm.pickTest(TestPick.Library(
            ExerciseLibrary.get("bb_bench")))
        vm.pickTest(TestPick.Run(FitnessTest.TWO_MILE))
        vm.setLiftGoal("bb_bench", 225.0); vm.setGoalSeconds(960)
        vm.setDays(4); advanceUntilIdle()
        assertEquals(BothAnswers.Together.SAME_VISIT, answers(vm).both.together)
        assertNull(vm.uiState.value.draft.together)
        vm.setDays(5); advanceUntilIdle()
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, answers(vm).both.together)
        vm.setTogether(BothAnswers.Together.SAME_VISIT); vm.setDays(6); advanceUntilIdle()
        assertEquals(BothAnswers.Together.SAME_VISIT, answers(vm).both.together)
        vm.setBranch(PlanBranch.RUN); advanceUntilIdle()
        assertEquals(false, vm.uiState.value.draft.fitnessTest)
        assertTrue(vm.uiState.value.draft.testLifts.isEmpty())
        assertFalse(vm.uiState.value.draft.testRun)
        assertEquals(RunGoal.CARDIO_FITNESS, vm.uiState.value.draft.runGoal)
    }

    @Test fun `switching from Run with zero strength blocks to Both keeps lifting`() = runVmTest {
        val vm = newViewModel(); advanceUntilIdle()
        vm.setBranch(PlanBranch.RUN); advanceUntilIdle()
        plan(vm)
        vm.setStrengthBlocks(0); advanceUntilIdle()
        assertEquals(0, answers(vm).run.strengthBlocks)
        backToGoal(vm)
        vm.setBranch(PlanBranch.BOTH); vm.setCardioFirst(true)
        vm.setTogether(BothAnswers.Together.SAME_VISIT); advanceUntilIdle()
        plan(vm)
        assertEquals(2, answers(vm).run.strengthBlocks)
        assertTrue(ready(vm).rows.any { it.lifts > 0 })
        vm.onNext(); advanceUntilIdle()
        val saved = db.planDao().allPlans().single()
        assertTrue(repo.plans.programFor(saved.id).first().days.any { it.exercises.isNotEmpty() })
    }

    @Test fun `reselecting Fitness test after Review answers keeps every Army answer`() = runVmTest {
        val original = SetupAnswers(branch = PlanBranch.BOTH, goal = LiftGoal.MUSCLE,
            run = RunAnswers(goal = RunGoal.FITNESS_TEST, goalSeconds = 960, dateEpochDay = 21000),
            tests = listOf(TestAnswer(TestEventGoal(TestRef("aft_hrp", 1, 120), 40), 31),
                TestAnswer(TestEventGoal(TestRef("aft_plank", 1), 220), 110)),
            testDateEpochDay = 21000, both = BothAnswers(cardioFirst = false),
            lifts = listOf(LiftTest("trap_dl", 3)))
        val id = repo.plans.createPlannedPlan("Army", original, mapOf("trap_dl" to 340.0))
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        plan(vm); vm.reviewAnswers(); advanceUntilIdle()
        backToGoal(vm)
        val before = vm.uiState.value.draft
        vm.chooseFitnessTest(); advanceUntilIdle()
        assertEquals(before, vm.uiState.value.draft)
        repeat(3) { vm.onNext() }; advanceUntilIdle()
        assertEquals(before.copy(step = SetupStep.TEST), vm.uiState.value.draft)
    }

    @Test fun `reselecting Both keeps a race and push ups`() = runVmTest {
        val draft = SetupFlowFixtures.both.copy(runGoal = RunGoal.FIRST_5K, eventDate = 21000,
            tests = listOf(TestDraft("aft_hrp", 40, 31)), testDate = 21000)
        val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true,
            "setup_draft" to SetupDraftJson.encode(draft)))); advanceUntilIdle()
        val before = vm.uiState.value.draft
        vm.setBranch(PlanBranch.BOTH); advanceUntilIdle()
        assertEquals(before, vm.uiState.value.draft)
        assertEquals(1, answers(vm).tests.size)
        assertEquals(RunGoal.FIRST_5K, answers(vm).run.goal)
    }

    @Test fun `legacy Sub goal and Order stay visible after changing their answers`() = runVmTest {
        val lift = SetupAnswers(goal = LiftGoal.MUSCLE,
            tests = listOf(TestAnswer(TestEventGoal(TestRef("aft_hrp", 1, 120), 40))))
        for (original in listOf(lift, lift.copy(branch = PlanBranch.BOTH,
            run = RunAnswers(goal = RunGoal.FITNESS_TEST, goalSeconds = 960), both = BothAnswers(cardioFirst = false)))) {
            val draft = SetupFlowStateBuilder.draftFrom(original).copy(step = SetupStep.PLAN)
            val vm = newViewModel(SavedStateHandle(mapOf("setup_initialized" to true,
                "setup_draft" to SetupDraftJson.encode(draft)))); advanceUntilIdle()
            vm.reviewAnswers(); advanceUntilIdle()
            vm.onBack(); advanceUntilIdle()
            assertEquals(SetupStep.SUB_GOAL, vm.uiState.value.step)
            vm.setSubGoal(LiftGoal.GENERAL); advanceUntilIdle()
            assertEquals(SetupStep.SUB_GOAL, vm.uiState.value.step)
            vm.setSubGoal(LiftGoal.MUSCLE); advanceUntilIdle()
            assertEquals(LiftGoal.MUSCLE, answers(vm).goal)
            if (original.branch == PlanBranch.BOTH) {
                vm.onBack(); advanceUntilIdle()
                assertEquals(SetupStep.ORDER, vm.uiState.value.step)
                vm.setCardioFirst(true); advanceUntilIdle()
                assertEquals(SetupStep.ORDER, vm.uiState.value.step)
                vm.setCardioFirst(false); advanceUntilIdle()
                assertFalse(answers(vm).both.cardioFirst)
            }
        }
    }

    @Test fun `Review answers keeps lifts and recent on legacy Muscle Same visit tests`() = runVmTest {
        val original = SetupAnswers(branch = PlanBranch.BOTH, goal = LiftGoal.MUSCLE,
            experience = LiftExperience.A_YEAR_OR_MORE, mains = Planner.defaultMains(EquipmentPreset.FULL_GYM.equipment),
            recent = mapOf("bb_bench" to RecentNumber(185.0, 5, Effort.GOOD)),
            run = RunAnswers(goal = RunGoal.FITNESS_TEST, goalSeconds = 960),
            both = BothAnswers(cardioFirst = true, together = BothAnswers.Together.SAME_VISIT))
        val id = repo.plans.createPlannedPlan("Both test", original)
        val before = repo.plans.programFor(id).first()
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        plan(vm); vm.reviewAnswers(); advanceUntilIdle()
        assertTrue(SetupStep.LIFTS in SetupFlowStateBuilder.steps(vm.uiState.value.draft))
        assertTrue(SetupStep.RECENT in SetupFlowStateBuilder.steps(vm.uiState.value.draft))
        assertEquals(original, answers(vm))
        plan(vm); vm.onNext(); advanceUntilIdle()
        val saved = db.planDao().allPlans().last()
        assertEquals(original, saved.setupAnswers(WeightUnit.LB))
        assertEquals(before, repo.plans.programFor(saved.id).first())
    }

    @Test fun `Review answers keeps an excluded test lift without copying other goals`() = runVmTest {
        val original = SetupAnswers(branch = PlanBranch.BOTH, goal = LiftGoal.MUSCLE, days = 4,
            excluded = setOf("trap_dl"), lifts = listOf(LiftTest("trap_dl", 3)),
            run = RunAnswers(goal = RunGoal.FITNESS_TEST, goalSeconds = 960))
        val id = repo.plans.createPlannedPlan("Both test", original, mapOf("trap_dl" to 340.0, "bb_bench" to 225.0))
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        plan(vm); vm.reviewAnswers(); advanceUntilIdle()
        assertEquals(setOf("trap_dl"), answers(vm).excluded)
        plan(vm)
        assertEquals(cloud.trotter.log.strength.R.string.test_training_equipment,
            ready(vm).testTraining.single().reason)
        vm.onNext(); advanceUntilIdle()
        val saved = db.planDao().allPlans().last()
        assertEquals(setOf("trap_dl"), assertNotNull(saved.setupAnswers(WeightUnit.LB)).excluded)
        assertEquals(mapOf("trap_dl" to 340.0), saved.planSettings.goalsLb)
    }

    @Test fun `removing a test lift drops its Required weight from the saved plan`() = runVmTest {
        val original = SetupAnswers(goal = LiftGoal.STRENGTH, lifts = listOf(LiftTest("bb_bench", 5)),
            tests = listOf(TestAnswer(TestEventGoal(TestRef("aft_hrp", 1, 120), 40))))
        val id = repo.plans.createPlannedPlan("Test", original, mapOf("bb_bench" to 225.0))
        repo.plans.publishPlannedPlan(id); settings.setWizardComplete(true)
        val vm = newViewModel(); advanceUntilIdle()
        plan(vm); vm.reviewAnswers(); vm.removeTestPick("bb_bench"); advanceUntilIdle()
        plan(vm); vm.onNext(); advanceUntilIdle()
        val saved = db.planDao().allPlans().last()
        assertTrue(assertNotNull(saved.setupAnswers(WeightUnit.LB)).lifts.isEmpty())
        assertTrue(saved.planSettings.goalsLb.isEmpty())
    }

}
