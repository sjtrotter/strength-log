package cloud.trotter.log.strength.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.widget.todayWidgetContent
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodaySnapshotSourceRestDayTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var syncStore: WearSyncStore
    private val today = MutableStateFlow(LocalDate.of(2026, 11, 7))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("rest-day-settings", ".preferences_pb")
        })
        syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("rest-day-sync", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun source() = TodaySnapshotSource(repo, today, syncStore, syncStore,
        settings = settings, plans = repo.plans, historyRepository = repo.history)

    private suspend fun restPlan(): Long {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Run", "", emptyList(), cardioBlock = CardioBlock(CardioActivity.ROAD_RUN,
                listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))),
            ProgramDay("B", "Lift", "", listOf(ProgramExercise("bb_back_squat"))),
            ProgramDay("C", "Rest", "", emptyList(), kind = ProgramDayKind.REST),
        )))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        db.planDao().updateSuggestedDay(plan, "C")
        return plan
    }

    @Test fun `rest suggestion publishes the training day on watch and widget`() = runTest(dispatcher) {
        val plan = restPlan()
        val snapshot = source().snapshots.first { it != null }!!
        assertEquals("A", snapshot.day.dayId)
        assertEquals("A", snapshot.suggestedDayId)
        assertEquals("Run", snapshot.day.title)
        assertEquals("Day A, Run", todayWidgetContent(snapshot).dayLine)
        assertEquals("C", db.planDao().plan(plan)!!.suggestedDayId)
        assertTrue(db.runDao().allRuns().isEmpty())
    }

    @Test fun `resolved training id matches logged cardio`() = runTest(dispatcher) {
        val plan = restPlan()
        val noon = today.value.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.cardioSessionDao().insert(CardioSessionEntity(
            dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = noon - 120000, completedAt = noon, seconds = 120, stepsCompleted = 1, planId = plan,
        ))
        assertEquals(120, source().snapshots.first { it != null }!!.cardio?.loggedSeconds)
    }

    @Test fun `moving rest changes the next training day without changing the suggestion`() = runTest(dispatcher) {
        val plan = restPlan()
        val snapshots = Channel<WatchSnapshot>(Channel.UNLIMITED)
        backgroundScope.launch { source().snapshots.collect { if (it != null) snapshots.send(it) } }
        suspend fun awaitDay(dayId: String): WatchSnapshot {
            while (true) {
                val snapshot = snapshots.receive()
                if (snapshot.day.dayId == dayId) return snapshot
            }
        }
        assertEquals("A", awaitDay("A").day.dayId)
        repo.moveDay(plan, "C", up = true)
        assertEquals("B", awaitDay("B").day.dayId)
        assertEquals("C", db.planDao().plan(plan)!!.suggestedDayId)
    }
}
