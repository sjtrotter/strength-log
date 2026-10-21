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
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlansViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val viewModels = mutableListOf<PlansViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("plans-vm", ".preferences_pb")
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

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): PlansViewModel =
        PlansViewModel(repo, handle, context).also { vm ->
            viewModels += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    private suspend fun seed() {
        repo.replaceProgram(Program(listOf(ProgramDay(
            id = "A", title = "Day A", emphasisLine = "", exercises = emptyList(), cardio = null,
        ))))
    }

    @Test
    fun `start empty restores name draft then creates selects and returns once`() = runTest(dispatcher) {
        seed()
        val global = settings.globalLifterFlow.first()
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.showNewPlan()
        advanceUntilIdle()
        assertEquals("choice", first.uiState.value.newPlanPage)
        first.startEmpty()
        first.setNewPlanName("  Physio  ")
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        first.viewModelScope.cancel()
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals("name", restored.uiState.value.newPlanPage)
        assertEquals("  Physio  ", restored.uiState.value.newPlanName)
        restored.createEmpty()
        restored.createEmpty()
        advanceUntilIdle()
        val created = db.planDao().allPlans().last()
        assertEquals(2, db.planDao().allPlans().size)
        assertEquals("Physio", created.name)
        assertEquals("AUTHORED", created.origin)
        assertEquals(created.id, settings.selectedPlanIdFlow.first())
        assertEquals(global, settings.globalLifterFlow.first())
        assertTrue(repo.programFor(created.id).first().days.single().exercises.isEmpty())
        assertEquals("Day A", repo.programFor(created.id).first().days.single().title)
        assertNull(restored.uiState.value.newPlanPage)
        assertEquals(Unit, restored.selected.first())
    }

    @Test
    fun `start empty refuses invalid names and dismiss discards the draft`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showNewPlan()
        vm.startEmpty()
        for (name in listOf("  ", "x".repeat(41))) {
            vm.setNewPlanName(name)
            vm.createEmpty()
            advanceUntilIdle()
            assertTrue(db.planDao().allPlans().isEmpty())
        }
        vm.dismissNewPlan()
        advanceUntilIdle()
        assertNull(vm.uiState.value.newPlanPage)
        assertEquals("", vm.uiState.value.newPlanName)
    }

    @Test
    fun `rename refuses blank and overlong drafts and saves trimmed name`() = runTest(dispatcher) {
        seed()
        val vm = viewModel()
        vm.rename(1)
        advanceUntilIdle()
        assertEquals("My plan", vm.uiState.value.renameDraft)
        vm.setRenameDraft("   ")
        vm.saveRename()
        advanceUntilIdle()
        assertEquals("My plan", db.planDao().plan(1)!!.name)
        assertEquals(1L, vm.uiState.value.renamePlanId)
        vm.setRenameDraft("x".repeat(41))
        vm.saveRename()
        advanceUntilIdle()
        assertEquals("My plan", db.planDao().plan(1)!!.name)
        vm.setRenameDraft("  Training  ")
        vm.saveRename()
        advanceUntilIdle()
        assertEquals("Training", db.planDao().plan(1)!!.name)
        assertNull(vm.uiState.value.renamePlanId)
    }

    @Test
    fun `duplicate archive restore and select go through repository`() = runTest(dispatcher) {
        seed()
        val vm = viewModel()
        vm.duplicate(1)
        advanceUntilIdle()
        val copy = db.planDao().allPlans().last()
        assertEquals("My plan copy", copy.name)
        assertEquals(listOf("Day A"), db.programDao().daysForPlan(copy.id).map { it.title })
        assertEquals(1L, settings.selectedPlanIdFlow.first())
        vm.archive(1)
        advanceUntilIdle()
        assertFalse(db.planDao().plan(1)!!.archived)
        vm.archive(copy.id)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.plans.first { it.id == copy.id }.archived)
        vm.select(copy.id)
        advanceUntilIdle()
        assertEquals(1L, settings.selectedPlanIdFlow.first())
        vm.restore(copy.id)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.plans.first { it.id == copy.id }.archived)
        vm.select(copy.id)
        advanceUntilIdle()
        assertEquals(copy.id, settings.selectedPlanIdFlow.first())
        assertEquals(Unit, vm.selected.first())
        assertTrue(vm.uiState.value.plans.first { it.id == copy.id }.selected)
    }

    @Test
    fun `rename dialog and draft survive SavedStateHandle recreation`() = runTest(dispatcher) {
        seed()
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.rename(1)
        advanceUntilIdle()
        first.setRenameDraft("Draft after process death")
        advanceUntilIdle()
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        first.viewModelScope.cancel()

        val restored = viewModel(restoredHandle)
        advanceUntilIdle()

        assertEquals(1L, restored.uiState.value.renamePlanId)
        assertEquals("Draft after process death", restored.uiState.value.renameDraft)
        restored.saveRename()
        advanceUntilIdle()
        assertEquals("Draft after process death", db.planDao().plan(1)!!.name)
        restored.rename(1)
        advanceUntilIdle()
        restored.setRenameDraft("Discarded")
        restored.dismissRename()
        advanceUntilIdle()
        assertNull(restored.uiState.value.renamePlanId)
        assertEquals("Draft after process death", db.planDao().plan(1)!!.name)
    }
}
