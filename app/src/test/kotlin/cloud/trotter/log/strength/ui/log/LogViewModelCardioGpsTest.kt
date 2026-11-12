package cloud.trotter.log.strength.ui.log

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.transfer.health.HealthConnectReader
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.CardioEntryDraft
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class LogViewModelCardioGpsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<LogViewModel>()
    private val now = 1_800_000_000_000L

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("log-gps-vm", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun vm(handle: SavedStateHandle): LogViewModel {
        val civil = object : CivilTimeSource {
            override val civilTime = flowOf(CivilTime.now(Instant.ofEpochMilli(now), ZoneOffset.UTC))
            override fun refresh() = Unit
        }
        return LogViewModel(repo.history, HealthConnectReader(), SessionPublisher.NoOp,
            ShareCardService(ApplicationProvider.getApplicationContext(), repo.history, settings = settings),
            handle, civil, settings, repo.plans, repo.catalog, repo).also { vms += it }
    }

    private suspend fun seed(exact: Boolean = false): Long {
        val seconds = if (exact) 300 else 1900
        val row = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(if (exact) 300 else 1800)),
            CardioSetStatus.FINISHED, seconds, if (exact) 888 else 5700, DistanceSource.GPS,
            if (exact) null else 5400, if (exact) 300 else 1800, if (exact) null else ReadingSource.GPS)
        return repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = now - 2_000_000, completedAt = now - 2_000_000 + seconds * 1000L,
            seconds = seconds, stepsCompleted = 1, activity = CardioActivity.ROAD_RUN.name), listOf(row))
    }

    @Test fun `History can replace a GPS reading while keeping its GPS total`() = runTest(dispatcher) {
        settings.setDistanceUnit(DistanceUnit.MI)
        val id = seed()
        val vm = vm(SavedStateHandle())
        vm.editCardio(id)
        runCurrent()
        assertEquals(5400, vm.cardioEditGps.value?.reading)
        vm.changeCardioEditReading("3.30")
        runCurrent()
        vm.saveCardioEdit()
        runCurrent()
        val row = repo.history.cardioSetsFor(id).single()
        assertEquals(5311, row.metresAtEnd)
        assertEquals(ReadingSource.TYPED, row.readingSource)
        assertEquals(5700, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
    }

    @Test fun `a seconds only exact GPS edit keeps its reading unknown`() = runTest(dispatcher) {
        val id = seed(exact = true)
        val handle = SavedStateHandle()
        val vm = vm(handle)
        vm.editCardio(id)
        runCurrent()
        vm.changeCardioEdit(requireNotNull(handle.get<CardioEntryDraft>("log_cardio_edit_draft")).copy(seconds = 301))
        vm.saveCardioEdit()
        runCurrent()
        val row = repo.history.cardioSetsFor(id).single()
        assertEquals(Measure.Time(301), row.spec.end)
        assertEquals(888, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertNull(row.metresAtEnd)
    }
}
