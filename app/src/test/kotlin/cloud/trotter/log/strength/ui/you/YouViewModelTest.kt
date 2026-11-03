package cloud.trotter.log.strength.ui.you

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.WeightUnit
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class YouViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val writtenBodyweights = mutableListOf<Int?>()
    private val writtenAges = mutableListOf<Int?>()
    private val vms = mutableListOf<YouViewModel>()

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
            File.createTempFile("you-vm-settings", ".preferences_pb")
        }
        settings = SettingsStore(object : DataStore<Preferences> {
            override val data = dataStore.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                dataStore.updateData(transform).also { prefs ->
                    writtenBodyweights += prefs[intPreferencesKey("bodyweight")]
                    writtenAges += prefs[intPreferencesKey("age")]
                }
        })
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        repo.replaceProgram(Program(emptyList()))
        assertNull(db.planDao().allPlans().single().recipeJson)
        writtenBodyweights.clear()
        writtenAges.clear()
        block()
    }

    private fun newViewModel(): YouViewModel =
        YouViewModel(repo.plans, settings = settings).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    @Test
    fun setBodyweight_persists_in_canonical_lb_regardless_of_display_unit() = runVmTest {
        settings.setUnit(WeightUnit.KG)
        val vm = newViewModel()
        advanceUntilIdle()

        // 100 kg display -> canonical lb via WeightUnit (A5's one conversion point).
        vm.setBodyweight(100.0)
        advanceUntilIdle()

        val expectedLb = WeightUnit.KG.toLb(100.0).let(Math::round).toInt()
        assertEquals(expectedLb, repo.plans.configFlow.first().bodyweightLb)
    }

    @Test
    fun ageIsClampedAndLevelCommitsImmediately() = runVmTest {
        val vm = newViewModel()
        vm.setAge(0)
        vm.setLevel(ExperienceLevel.ADVANCED)
        advanceUntilIdle()
        assertEquals(1, repo.plans.configFlow.first().age)
        assertEquals(ExperienceLevel.ADVANCED, repo.plans.configFlow.first().level)
        assertEquals(1, vm.uiState.value.config.age)
    }

    @Test
    fun rapidBodyweightAndAgeWritesPreserveEachOther() = runVmTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setBodyweight(240.0)
        vm.setBodyweight(245.0)
        vm.setAge(41)
        advanceUntilIdle()
        assertEquals(245, repo.plans.configFlow.first().bodyweightLb)
        assertEquals(41, repo.plans.configFlow.first().age)
        assertEquals(listOf(240, 245, 245), writtenBodyweights)
        assertEquals(listOf(40, 40, 41), writtenAges)
    }

    @Test
    fun configChangesKeepTheSelectedRecipeAndProgram() = runVmTest {
        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first())
        repo.plans.setPlanEmphasis(repo.plans.selectedPlanFlow.first()!!.id, GoalEmphasis.STRENGTH)
        val vm = newViewModel()
        val programBefore = repo.plans.programFlow.first()
        vm.setAge(50)
        advanceUntilIdle()
        assertEquals(GoalEmphasis.STRENGTH, repo.plans.configFlow.first().emphasis)
        assertEquals(programBefore, repo.plans.programFlow.first())
    }

    @Test
    fun levelChangesKeepTheSelectedPlanEmphasis() = runVmTest {
        repo.plans.setWizardAnswers(repo.plans.wizardAnswersFlow.first())
        val selectedPlanId = repo.plans.selectedPlanFlow.first()!!.id
        repo.plans.setPlanEmphasis(selectedPlanId, GoalEmphasis.STRENGTH)
        val vm = newViewModel()

        vm.setLevel(ExperienceLevel.ADVANCED)
        advanceUntilIdle()

        assertEquals(ExperienceLevel.ADVANCED, repo.plans.configFlow.first().level)
        assertEquals(GoalEmphasis.STRENGTH, repo.plans.wizardAnswersFor(selectedPlanId).first().config.emphasis)
    }
}
