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
import cloud.trotter.log.strength.domain.model.ProgramDay
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
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
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
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
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

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): PlanViewModel =
        PlanViewModel(repo, handle, context).also { vm ->
            viewModels += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    @Test
    fun `added cardio only day exposes its activity and set count immediately`() = runTest(dispatcher) {
        val plan = repo.createAuthoredPlan("Running", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
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
        val plan = repo.createAuthoredPlan("Prep", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
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
        val plan = repo.createAuthoredPlan("Physio", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.renameDay("A")
        vm.setDayTitleDraft("Daily rehab")
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.viewModelScope.cancel()
        val other = repo.createAuthoredPlan("Other", "Day A")
        repo.publishCreatedAuthoredPlan(other)
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
        val plan = repo.selectedPlanIdFlow.first()!!
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(6, vm.uiState.value.days.size)
        vm.addDay()
        advanceUntilIdle()
        assertEquals(('A'..'G').map { it.toString() }, vm.uiState.value.days.map { it.dayId })
        assertEquals(listOf("C1", "C2"), repo.programFor(plan).first().cardioDays.map { it.id })
        vm.addDay()
        advanceUntilIdle()
        assertEquals(9, db.programDao().daysForPlan(plan).size)
    }

    @Test
    fun `generated days add rename and remove immediately`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", emptyList()))))
        val plan = repo.selectedPlanIdFlow.first()!!
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
