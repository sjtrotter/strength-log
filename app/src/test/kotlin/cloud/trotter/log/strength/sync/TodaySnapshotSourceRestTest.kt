package cloud.trotter.log.strength.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.widget.TodayWidgetContent
import cloud.trotter.log.strength.widget.todayWidgetContent
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared today-state source folds [SettingsStore.restSettingsFlow] into its
 * content combine, so a Setup rest edit changes the projected snapshot — the byte
 * change that, via the publisher's distinctUntilChanged + nextStamp
 * (WearSyncStoreTest), spends a fresh revision. R8 risk #3 flags forgetting this
 * combine as the real bug; this test is that checklist item. Real repo + Room +
 * DataStore on the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodaySnapshotSourceRestTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("pub-rest-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
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
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `switching plans changes the live widget and watch day`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null,
        ))))
        val firstPlan = repo.selectedPlanIdFlow.first()!!
        val secondPlan = repo.duplicatePlan(firstPlan, "Test prep")
        repo.selectPlan(secondPlan)
        repo.replaceProgram(Program(listOf(ProgramDay(
            "B", "Bench", "", listOf(ProgramExercise("bb_bench", isMain = true)), cardio = null,
        ))))
        repo.selectPlan(firstPlan)
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), InertMarkers)
        val widgets = Channel<TodayWidgetContent>(Channel.UNLIMITED)
        backgroundScope.launch { source.snapshots.collect { widgets.send(todayWidgetContent(it)) } }
        suspend fun awaitDay(line: String): TodayWidgetContent {
            while (true) {
                val content = widgets.receive()
                if (content.dayLine == line) return content
            }
        }

        assertEquals("DAY A · SQUAT", awaitDay("DAY A · SQUAT").dayLine)
        repo.selectPlan(secondPlan)
        assertEquals("DAY B · BENCH", awaitDay("DAY B · BENCH").dayLine)
        repo.selectPlan(firstPlan)
        assertEquals("DAY A · SQUAT", awaitDay("DAY A · SQUAT").dayLine)
    }

    @Test
    fun `same day ids do not share cardio completion across plans`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)),
            cardio = CardioSuggestion("Zone 2", "20 minutes", hard = false),
        ))))
        val firstPlan = repo.selectedPlanIdFlow.first()!!
        val secondPlan = repo.duplicatePlan(firstPlan, "Test prep")
        val noon = repo.currentDate().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val firstSession = CardioSessionEntity(
            dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Zone 2",
            startedAt = noon - 60_000, completedAt = noon, seconds = 60, stepsCompleted = 1, planId = firstPlan,
        )
        db.cardioSessionDao().insertAll(listOf(
            firstSession,
            firstSession.copy(planId = secondPlan, completedAt = noon + 60_000, seconds = 120),
            firstSession.copy(planId = null, completedAt = noon + 120_000, seconds = 180),
        ))
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), InertMarkers)

        assertEquals(60, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
        repo.selectPlan(secondPlan)
        assertEquals(120, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
        repo.selectPlan(firstPlan)
        assertEquals(60, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
    }

    @Test
    fun `a rest-settings edit changes the projected snapshot content`() = runTest(dispatcher) {
        repo.replaceProgram(
            Program(listOf(ProgramDay("A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null))),
        )
        db.planDao().updateSuggestedDay(repo.selectedPlanIdFlow.first()!!, "A")
        val slotId = repo.daySlotsFlow("A").first().single().programExerciseId
        repo.updateSets("A", slotId, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP)))

        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), InertMarkers)

        // Default (master on, no overrides): TOP rests its 180s default.
        val before = source.snapshots.first { it != null }!!
        assertEquals(180, before.day.exercises.single().sets.single().restAfterSeconds)

        // Editing the TOP override republishes with the new number.
        settings.setRestOverride(RestCategory.TOP, 210)
        val afterOverride = source.snapshots.first { it != null }!!
        assertEquals(210, afterOverride.day.exercises.single().sets.single().restAfterSeconds)

        // Master toggle off zeroes it — a distinct content again.
        settings.setRestTimerEnabled(false)
        val afterOff = source.snapshots.first { it != null }!!
        assertEquals(0, afterOff.day.exercises.single().sets.single().restAfterSeconds)
    }
}

private object InertMarkers : AppliedEditMarkers {
    override suspend fun lastApplied(rowKey: String) = 0L
    override suspend fun markApplied(rowKey: String, editedAtMillis: Long) = Unit
    override fun lastAppliedFlow(rowKey: String) = kotlinx.coroutines.flow.flowOf(0L)
}
