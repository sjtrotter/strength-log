package cloud.trotter.log.strength.ui.plan

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
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
}
