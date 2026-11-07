package cloud.trotter.log.strength.ui.plan

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import android.os.Parcel
import kotlinx.parcelize.parcelableCreator
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.CardioBlockDraft
import cloud.trotter.log.strength.ui.day.CardioTemplate
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PlanViewModelTest {

    @Test fun `offer switches update only the viewed plan`() = runTest(dispatcher) {
        val preferred = repo.plans.createAuthoredPlan("Preferred", "Day")
        repo.plans.publishCreatedAuthoredPlan(preferred)
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.offerStrengthIncreases)
        assertEquals(false, vm.uiState.value.offerCardioIncreases)
        vm.setOfferStrengthIncreases(false)
        vm.setOfferCardioIncreases(true)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.offerStrengthIncreases)
        assertEquals(true, vm.uiState.value.offerCardioIncreases)
        assertEquals(false, db.planDao().plan(viewed)!!.offerStrengthIncreases)
        assertEquals(true, db.planDao().plan(viewed)!!.offerCardioIncreases)
        assertEquals(true, db.planDao().plan(preferred)!!.offerStrengthIncreases)
        assertEquals(false, db.planDao().plan(preferred)!!.offerCardioIncreases)
    }

    @Test fun `save a timed goal from the draft and preserve it across process death`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openGoal()
        advanceUntilIdle()
        assertEquals(PlanGoalDraft(), vm.uiState.value.goalDraft)
        val draft = PlanGoalDraft(activity = "TRAIL_RUN", metres = 5000, underSeconds = 1500, byEpochDay = 21000)
        vm.changeGoalDraft(draft)
        val parcel = Parcel.obtain()
        val restoredDraft = try {
            handle.get<PlanGoalDraft>("plan_goal_draft")!!.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            parcelableCreator<PlanGoalDraft>().createFromParcel(parcel)
        } finally { parcel.recycle() }
        val restoredHandle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString(), "plan_goal_draft" to restoredDraft))
        vm.viewModelScope.cancel()
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals(draft, restored.uiState.value.goalDraft)
        restored.saveGoal()
        advanceUntilIdle()
        assertEquals(draft.goal(), PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
        assertNull(restored.uiState.value.goalDraft)
        assertNull(restoredHandle.get<PlanGoalDraft>("plan_goal_draft"))
        restored.openGoal()
        advanceUntilIdle()
        assertEquals(draft, restored.uiState.value.goalDraft)
        restored.removeGoal()
        advanceUntilIdle()
        assertNull(restored.uiState.value.goal)
    }

    @Test fun `invalid cardio goal drafts cannot save and cancel leaves storage alone`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal()
        for (invalid in listOf(PlanGoalDraft(metres = 0), PlanGoalDraft(underSeconds = 0),
            PlanGoalDraft(activity = "UNKNOWN"), PlanGoalDraft(timed = false, protocol = " "))) {
            vm.changeGoalDraft(invalid)
            vm.saveGoal()
            advanceUntilIdle()
            assertEquals(invalid, vm.uiState.value.goalDraft)
            assertNull(db.planDao().plan(viewed)!!.goalJson)
        }
        vm.dismissGoal()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDraft)
    }

    @Test fun `out of range dates cannot save either cardio goal draft`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val saved = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)
        repo.plans.setGoal(viewed, saved)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal()
        for (day in listOf(LocalDate.of(1999, 12, 31).toEpochDay(), LocalDate.of(2101, 1, 1).toEpochDay(),
            LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay(), Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (timed in listOf(true, false)) {
                val draft = PlanGoalDraft(timed = timed, protocol = "PACER", byEpochDay = day)
                vm.changeGoalDraft(draft)
                vm.saveGoal()
                advanceUntilIdle()
                assertEquals(draft, vm.uiState.value.goalDraft)
                assertEquals(saved, PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
            }
        }
    }

    @Test fun `shuttle entry survives recreation and logging updates best`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openGoal()
        vm.changeGoalDraft(PlanGoalDraft(timed = false, protocol = " PACER ", targetShuttles = 52))
        vm.saveGoal()
        advanceUntilIdle()
        assertEquals(PlanGoal.Shuttle("PACER", 52), vm.uiState.value.goal!!.goal)
        vm.openShuttleEntry()
        vm.saveShuttleEntry()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goal!!.best)
        vm.changeShuttleEntry("44")
        vm.changeShuttleEntry("4444")
        vm.changeShuttleEntry("4x")
        assertEquals("44", handle.get<String>("plan_shuttle_entry"))
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.viewModelScope.cancel()
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals("44", restored.uiState.value.shuttleEntry)
        restored.saveShuttleEntry()
        advanceUntilIdle()
        assertEquals(44, restored.uiState.value.goal!!.best)
        assertEquals(false, restored.uiState.value.goal!!.met)
        assertNull(restored.uiState.value.shuttleEntry)
    }

    @Test fun `timed goal observes cardio attempts from every plan and later history edits`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val other = repo.plans.createAuthoredPlan("Other", "Run")
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)
        repo.plans.setGoal(viewed, goal)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        assertNull(vm.uiState.value.goal!!.best)
        val session = CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Road run",
            startedAt = 1000, completedAt = 985000, seconds = 984, stepsCompleted = 1, planId = other, activity = "ROAD_RUN")
        val id = db.cardioSessionDao().insert(session)
        db.cardioSetResultDao().insertAll(listOf(CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(3219)),
            CardioSetStatus.FINISHED, 984).toEntity(id, 0)))
        advanceUntilIdle()
        assertEquals(PlanGoalUi(goal, 984, false), vm.uiState.value.goal)
        db.cardioSessionDao().update(session.copy(id = id, activity = "TRACK_RUN"))
        advanceUntilIdle()
        assertEquals(PlanGoalUi(goal, null, false), vm.uiState.value.goal)
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var context: Context
    private lateinit var storeScope: CoroutineScope
    private val viewModels = mutableListOf<PlanViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("plan-days-vm", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun viewModel(handle: SavedStateHandle = SavedStateHandle()): PlanViewModel {
        if (!handle.contains(Routes.PLAN_ID_ARG)) handle[Routes.PLAN_ID_ARG] = repo.plans.selectedPlanIdFlow.first()!!.toString()
        return PlanViewModel(repo, handle, context, settings = settings, plans = repo.plans, catalog = repo.catalog).also { vm ->
            viewModels += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }
    }

    @Test
    fun `emphasis edits and unit changes update the viewed generated plan`() = runTest(dispatcher) {
        val answers = WizardAnswers()
        val program = ProgramGenerator.generate(answers)
        val preferred = repo.plans.createGeneratedPlan("Preferred", answers, program)
        repo.plans.publishCreatedPlan(preferred, answers)
        val tapped = repo.plans.createGeneratedPlan("Viewed", answers, program)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to tapped.toString())))
        advanceUntilIdle()
        assertEquals("235", vm.uiState.value.goals.first().display)
        assertEquals(GoalEmphasis.BALANCED, vm.uiState.value.emphasis)
        vm.setEmphasis(GoalEmphasis.STRENGTH)
        advanceUntilIdle()
        assertEquals(GoalEmphasis.STRENGTH, vm.uiState.value.emphasis)
        assertEquals(GoalEmphasis.BALANCED, repo.plans.wizardAnswersFor(preferred).first().config.emphasis)
        assertEquals(preferred, repo.plans.selectedPlanIdFlow.first())
        val pounds = vm.uiState.value.goals
        settings.setUnit(WeightUnit.KG)
        advanceUntilIdle()
        assertEquals(false, pounds == vm.uiState.value.goals)
        assertEquals(tapped, vm.uiState.value.planId)
    }

    @Test
    fun `saving a goal writes it to the plan`() = runTest(dispatcher) {
        val answers = WizardAnswers()
        val program = ProgramGenerator.generate(answers)
        val preferred = repo.plans.createGeneratedPlan("Preferred", answers, program)
        repo.plans.publishCreatedPlan(preferred, answers)
        val viewed = repo.plans.createGeneratedPlan("Viewed", answers, program)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal("bb_back_squat")
        advanceUntilIdle()
        assertEquals(235.0, vm.uiState.value.goalDialog!!.draftLb!!, 0.0)
        assertEquals(false, vm.uiState.value.goalDialog!!.edited)
        vm.setGoalDraft(200.0)
        vm.saveGoal()
        advanceUntilIdle()

        assertEquals(mapOf("bb_back_squat" to 200.0), repo.plans.wizardAnswersFor(viewed).first().config.goalsLb)
        assertEquals(emptyMap<String, Double>(), repo.plans.wizardAnswersFor(preferred).first().config.goalsLb)
        assertNull(vm.uiState.value.goalDialog)
        assertEquals("200", vm.uiState.value.goals.first().display)
        assertEquals("235", vm.uiState.value.goals.first().calculated)
        vm.openGoal("bb_back_squat")
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.goalDialog!!.edited)
        assertEquals("235", vm.uiState.value.goalDialog!!.calculated)
        vm.useCalculatedGoal()
        advanceUntilIdle()
        assertEquals(emptyMap<String, Double>(), repo.plans.wizardAnswersFor(viewed).first().config.goalsLb)
        assertNull(vm.uiState.value.goalDialog)
        assertEquals("235", vm.uiState.value.goals.first().display)
    }

    @Test
    fun `offer switches and goal edits preserve each other on the viewed generated plan`() = runTest(dispatcher) {
        val answers = WizardAnswers()
        val program = ProgramGenerator.generate(answers)
        val preferred = repo.plans.createGeneratedPlan("Preferred", answers, program)
        repo.plans.publishCreatedPlan(preferred, answers)
        val viewed = repo.plans.createGeneratedPlan("Viewed", answers, program)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal("bb_back_squat")
        vm.setGoalDraft(200.0)
        advanceUntilIdle()
        val dialog = vm.uiState.value.goalDialog

        vm.setOfferStrengthIncreases(false)
        vm.setOfferCardioIncreases(true)
        advanceUntilIdle()

        assertEquals(dialog, vm.uiState.value.goalDialog)
        assertEquals(false, vm.uiState.value.offerStrengthIncreases)
        assertEquals(true, vm.uiState.value.offerCardioIncreases)
        vm.saveGoal()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDialog)
        assertEquals("200", vm.uiState.value.goals.first().display)
        assertEquals(mapOf("bb_back_squat" to 200.0), repo.plans.wizardAnswersFor(viewed).first().config.goalsLb)
        assertEquals(false, db.planDao().plan(viewed)!!.offerStrengthIncreases)
        assertEquals(true, db.planDao().plan(viewed)!!.offerCardioIncreases)
        assertEquals(true, db.planDao().plan(preferred)!!.offerStrengthIncreases)
        assertEquals(false, db.planDao().plan(preferred)!!.offerCardioIncreases)
        assertEquals(emptyMap<String, Double>(), repo.plans.wizardAnswersFor(preferred).first().config.goalsLb)
    }

    @Test
    fun `goal dialog and its draft survive recreation and keep the viewed plan`() = runTest(dispatcher) {
        val answers = WizardAnswers()
        val plan = repo.plans.createGeneratedPlan("Goals", answers, ProgramGenerator.generate(answers))
        repo.plans.publishCreatedPlan(plan, answers)
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openGoal("bb_back_squat")
        vm.setGoalDraft(200.0)
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.viewModelScope.cancel()
        val other = repo.plans.createAuthoredPlan("Other", "Day A")
        repo.plans.publishCreatedAuthoredPlan(other)
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals(PlanGoalDialog("bb_back_squat", "Barbell Back Squat", false, 200.0, "235", false),
            restored.uiState.value.goalDialog)
        restored.saveGoal()
        advanceUntilIdle()
        assertEquals(mapOf("bb_back_squat" to 200.0), repo.plans.wizardAnswersFor(plan).first().config.goalsLb)
        assertEquals(emptyMap<String, Double>(), repo.plans.wizardAnswersFor(other).first().config.goalsLb)
        assertNull(restored.uiState.value.goalDialog)
        assertNull(restoredHandle.get<String>("plan_goal_exercise"))
        assertNull(restoredHandle.get<Double>("plan_goal_draft_lb"))
    }

    @Test
    fun `invalid goal drafts cannot save and a missing goal cannot open`() = runTest(dispatcher) {
        val answers = WizardAnswers()
        val plan = repo.plans.createGeneratedPlan("Goals", answers, ProgramGenerator.generate(answers))
        repo.plans.publishCreatedPlan(plan, answers)
        val vm = viewModel()
        advanceUntilIdle()
        vm.openGoal("missing")
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDialog)
        vm.openGoal("bb_back_squat")
        for (invalid in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
            PlanRecipeJson.MAX_GOAL_LB + 0.1, 2500.0, WeightUnit.KG.toLb(907.2))) {
            vm.setGoalDraft(invalid)
            vm.saveGoal()
            advanceUntilIdle()
            assertEquals("bb_back_squat", vm.uiState.value.goalDialog!!.exerciseId)
            assertEquals(emptyMap<String, Double>(), repo.plans.wizardAnswersFor(plan).first().config.goalsLb)
        }
        vm.dismissGoal()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDialog)
        val restored = viewModel(SavedStateHandle(mapOf(
            Routes.PLAN_ID_ARG to plan.toString(), "plan_goal_exercise" to "missing", "plan_goal_draft_lb" to 200.0,
        )))
        advanceUntilIdle()
        assertNull(restored.uiState.value.goalDialog)
    }

    @Test
    fun `saving the goal ceiling accepts canonical pounds in either display unit`() = runTest(dispatcher) {
        val answers = WizardAnswers()
        val plan = repo.plans.createGeneratedPlan("Goals", answers, ProgramGenerator.generate(answers))
        repo.plans.publishCreatedPlan(plan, answers)
        val vm = viewModel()
        for (unit in WeightUnit.entries) {
            settings.setUnit(unit)
            advanceUntilIdle()
            vm.openGoal("bb_back_squat")
            vm.setGoalDraft(PlanRecipeJson.MAX_GOAL_LB)
            vm.saveGoal()
            advanceUntilIdle()
            assertEquals(mapOf("bb_back_squat" to PlanRecipeJson.MAX_GOAL_LB),
                repo.plans.wizardAnswersFor(plan).first().config.goalsLb)
            assertNull(vm.uiState.value.goalDialog)
        }
    }

    @Test
    fun `tapped plan stays visible and all day actions target it while preferred changes`() = runTest(dispatcher) {
        val preferred = repo.plans.createAuthoredPlan("Preferred", "Preferred day")
        repo.plans.publishCreatedAuthoredPlan(preferred)
        val tapped = repo.plans.createAuthoredPlan("Physio", "Rehab")
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to tapped.toString())))
        advanceUntilIdle()
        assertEquals("Physio", vm.uiState.value.name)
        assertEquals(listOf("Rehab"), vm.uiState.value.days.map { it.title })
        vm.addDay()
        advanceUntilIdle()
        assertEquals(2, db.programDao().daysForPlan(tapped).size)
        assertEquals(1, db.programDao().daysForPlan(preferred).size)
        repo.plans.selectPlan(tapped)
        repo.plans.selectPlan(preferred)
        advanceUntilIdle()
        assertEquals(tapped, vm.uiState.value.planId)
    }

    @Test
    fun `added cardio only day exposes its activity and set count immediately`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Running", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val vm = viewModel()
        advanceUntilIdle()
        vm.addDay()
        advanceUntilIdle()
        val block = CardioBlockDraft.new(plan, "B").startFrom(CardioTemplate.INTERVALS).block()
        repo.setCardioBlock(plan, "B", block)
        advanceUntilIdle()
        val row = vm.uiState.value.days.single { it.dayId == "B" }
        assertEquals(0, row.liftCount)
        assertEquals(block.activity, row.cardioActivity)
        assertEquals(11, row.cardioSetCount)
    }

    @Test
    fun `authored days add rename and remove immediately with a seven day cap`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Prep", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val vm = viewModel()
        advanceUntilIdle()
        repeat(8) { vm.addDay() }
        advanceUntilIdle()
        assertEquals(('A'..'G').map { it.toString() }, vm.uiState.value.days.map { it.dayId })
        vm.renameDay("B")
        vm.setDayTitleDraft("  Test practice  ")
        vm.saveDayDialog()
        advanceUntilIdle()
        assertEquals("Test practice", db.programDao().day(plan, "B")!!.title)
        vm.removeDay("B")
        advanceUntilIdle()
        assertEquals("remove", vm.uiState.value.dialog)
        vm.saveDayDialog()
        advanceUntilIdle()
        assertNull(db.programDao().day(plan, "B"))
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun `day dialog survives recreation and writes to its original plan after selection changes`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Physio", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.renameDay("A")
        vm.setDayTitleDraft("Daily rehab")
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.viewModelScope.cancel()
        val other = repo.plans.createAuthoredPlan("Other", "Day A")
        repo.plans.publishCreatedAuthoredPlan(other)
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals("rename", restored.uiState.value.dialog)
        assertEquals("Daily rehab", restored.uiState.value.dayTitleDraft)
        restored.saveDayDialog()
        advanceUntilIdle()
        assertEquals("Daily rehab", db.programDao().day(plan, "A")!!.title)
        assertEquals("Day A", db.programDao().day(other, "A")!!.title)
    }

    @Test
    fun `generated plan adds a seventh strength day alongside standalone cardio`() = runTest(dispatcher) {
        repo.replaceProgram(ProgramGenerator.generate(WizardAnswers(
            daysPerWeek = 6, cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        )))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(8, vm.uiState.value.days.size)
        vm.addDay()
        advanceUntilIdle()
        assertEquals(('A'..'G').map { it.toString() },
            vm.uiState.value.days.filter { it.kind == ProgramDayKind.STRENGTH }.map { it.dayId })
        assertEquals(9, vm.uiState.value.days.size)
        assertEquals(listOf("C1", "C2"), repo.plans.programFor(plan).first().cardioDays.map { it.id })
        vm.addDay()
        advanceUntilIdle()
        assertEquals(9, db.programDao().daysForPlan(plan).size)
    }

    @Test
    fun `generated days add rename and remove immediately`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", emptyList()))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = viewModel()
        advanceUntilIdle()
        vm.addDay()
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.days.map { it.dayId })
        vm.renameDay("A")
        vm.setDayTitleDraft("Renamed")
        vm.saveDayDialog()
        advanceUntilIdle()
        assertEquals("Renamed", db.programDao().day(plan, "A")!!.title)
        vm.removeDay("B")
        advanceUntilIdle()
        assertEquals("remove", vm.uiState.value.dialog)
        vm.saveDayDialog()
        advanceUntilIdle()
        assertNull(vm.uiState.value.dialog)
        assertEquals(listOf("Renamed"), db.programDao().daysForPlan(plan).map { it.title })
    }
    @Test fun `Add rest moves and removes the viewed plans rest entry`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Plan", "Lift")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val vm = viewModel()
        advanceUntilIdle()
        vm.addRest()
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.days.map { it.dayId })
        val rest = vm.uiState.value.days.last()
        assertEquals(ProgramDayKind.REST, rest.kind)
        assertEquals("Rest", rest.title)
        assertEquals(0, rest.liftCount)
        assertEquals(0, rest.setCount)
        assertNull(rest.cardioActivity)
        vm.openDayMenu("B")
        vm.moveDay("B", true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.menuDayId)
        assertEquals(listOf("B", "A"), vm.uiState.value.days.map { it.dayId })
        vm.removeDay("B")
        vm.saveDayDialog()
        advanceUntilIdle()
        assertEquals(listOf("A"), vm.uiState.value.days.map { it.dayId })
    }

}
