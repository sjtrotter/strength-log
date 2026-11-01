package cloud.trotter.log.strength.ui.preview

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.day.WorkoutStart
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.today.TodayViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
open class OverviewFixture {
    protected val dispatcher = StandardTestDispatcher()
    protected lateinit var db: StrengthDatabase
    protected lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var context: Context
    private val vms = mutableListOf<ViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("overview-vm", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    protected suspend fun insertProgram() {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Squat", "Strength", listOf(ProgramExercise("bb_back_squat", isMain = true))),
            ProgramDay("B", "Arms", "", listOf(ProgramExercise("ez_curl", targetSets = 2, superset = SupersetPartner("rope_pushdown")))),
        )))
    }

    protected fun today(handle: SavedStateHandle = SavedStateHandle()): TodayViewModel {
        val zone = ZoneId.of("America/Chicago")
        val instant = Instant.parse("2026-10-31T18:00:00Z")
        val vm = TodayViewModel(repo, object : CivilTimeSource {
            override val civilTime = flowOf(CivilTime(instant, zone, instant.atZone(zone).toLocalDate()))
            override fun refresh() = Unit
        }, handle)
        vms += vm
        vm.viewModelScope.launch { vm.uiState.collect {} }
        observeStart(vm, vm.workoutStart)
        return vm
    }

    protected fun preview(planId: Long, dayId: String): PreviewViewModel {
        val vm = PreviewViewModel(repo, SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to planId.toString(), Routes.DAY_ID_ARG to dayId)))
        vms += vm
        vm.viewModelScope.launch { vm.uiState.collect {} }
        observeStart(vm, vm.workoutStart)
        return vm
    }

    protected fun day(handle: SavedStateHandle = SavedStateHandle()): DayViewModel {
        val vm = DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo), handle, FixedCardioClock(), InertCardioAlarm)
        vms += vm
        vm.viewModelScope.launch { vm.uiState.collect {} }
        vm.viewModelScope.launch { vm.dayEditState.collect {} }
        return vm
    }

    private fun observeStart(vm: ViewModel, start: WorkoutStart) {
        vm.viewModelScope.launch { start.conflict.collect {} }
    }
}
