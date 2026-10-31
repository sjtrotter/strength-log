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
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.acknowledgesCardio
import cloud.trotter.log.strength.widget.TodayWidgetContent
import cloud.trotter.log.strength.widget.todayWidgetContent
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared today-state source folds [SettingsStore.restSettingsFlow] into its
 * content combine, so a Setup rest edit changes the projected snapshot — the byte
 * change that, via the publisher's distinctUntilChanged + nextStamp
 * (WearSyncStoreTest), spends a fresh revision. Real repo + Room + DataStore on
 * the test dispatcher.
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
    private lateinit var syncStore: WearSyncStore

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
        syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("pub-wear-sync", ".preferences_pb")
        })
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
    fun `an upgraded phone publishes its max as the only processed stamp`() = runTest(dispatcher) {
        val planId = repo.createAuthoredPlan("Running", "Track")
        repo.publishCreatedAuthoredPlan(planId)
        syncStore.markApplied(CardioDeltaApplier.MARKER_KEY, 101L)
        val source = TodaySnapshotSource(repo, MutableStateFlow(repo.currentDate()), syncStore)
        val snapshot = source.snapshots.publishableSnapshots().first()
        assertEquals(101L, snapshot.cardioAckStamp)
        // A lost 100 must stay queued on a new watch, which only the list rule does.
        assertEquals(listOf(101L), snapshot.cardioAckedStamps)
        assertFalse(snapshot.acknowledgesCardio(100L))
    }

    @Test
    fun `recorded cardio stamps republish even when a late stamp leaves the max unchanged`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null,
        ))))
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)
        val snapshots = Channel<WatchSnapshot>(Channel.UNLIMITED)
        backgroundScope.launch {
            source.snapshots.publishableSnapshots().collect { snapshots.send(it) }
        }
        suspend fun awaitStamps(stamps: List<Long>): WatchSnapshot {
            while (true) {
                val snapshot = snapshots.receive()
                if (snapshot.cardioAckedStamps == stamps) return snapshot
            }
        }

        assertEquals(0L, awaitStamps(emptyList()).cardioAckStamp)
        syncStore.record(101L)
        assertEquals(101L, awaitStamps(listOf(101L)).cardioAckStamp)
        syncStore.record(100L)
        val late = awaitStamps(listOf(101L, 100L))
        assertEquals(101L, late.cardioAckStamp)
        assertEquals(listOf(101L, 100L), SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(late)).cardioAckedStamps)
    }

    @Test
    fun `an empty authored day publishes its plan and day identity without exercises`() = runTest(dispatcher) {
        val planId = repo.createAuthoredPlan("Rehab", "Daily rehab")
        repo.publishCreatedAuthoredPlan(planId)
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)
        val snapshot = source.snapshots.publishableSnapshots().first()
        val published = mutableListOf<WatchSnapshot>()
        publishSnapshotWithinSizeLimit(
            snapshot,
            spendStamp = { SnapshotStamp(epoch = 1L, revision = 1L) },
            publishBytes = { published += SyncCodec.decodeSnapshot(it) },
        )

        assertEquals(1, published.size)
        val day = published.single()
        assertEquals(planId, day.planId)
        assertEquals("Rehab", day.planName)
        assertEquals("A", day.suggestedDayId)
        assertEquals("A", day.day.dayId)
        assertEquals("Daily rehab", day.day.title)
        assertEquals(0, day.day.accentIndex)
        assertTrue(day.day.exercises.isEmpty())
    }

    @Test
    fun `no program projects null and publishes nothing`() = runTest(dispatcher) {
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)
        val projection = source.snapshots.first()
        assertEquals(null, projection)
        var stamps = 0
        val published = mutableListOf<ByteArray>()
        flowOf(projection).publishableSnapshots().collect {
            publishSnapshotWithinSizeLimit(
                it,
                spendStamp = { stamps++; SnapshotStamp(epoch = 1L, revision = 1L) },
                publishBytes = { bytes -> published += bytes },
            )
        }
        assertEquals(0, stamps)
        assertEquals(emptyList<ByteArray>(), published)
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
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)
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
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)

        assertEquals(60, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
        repo.selectPlan(secondPlan)
        assertEquals(120, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
        repo.selectPlan(firstPlan)
        assertEquals(60, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
    }

    @Test
    fun `live snapshots carry the selected plan and name across same-day plan switches`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null,
        ))))
        val planP = repo.selectedPlanIdFlow.first()!!
        repo.renamePlan(planP, "Strength")
        val planQ = repo.duplicatePlan(planP, "Running")
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)
        val snapshots = Channel<WatchSnapshot>(Channel.UNLIMITED)
        backgroundScope.launch { source.snapshots.collect { if (it != null) snapshots.send(it) } }
        suspend fun awaitPlan(planId: Long): WatchSnapshot {
            while (true) {
                val snapshot = snapshots.receive()
                if (snapshot.planId == planId) return snapshot
            }
        }

        val first = awaitPlan(planP)
        assertEquals("Strength", first.planName)
        assertEquals("A", first.day.dayId)
        assertEquals(repo.slotsFor(planP, "A").first().single().programExerciseId, first.day.exercises.single().programExerciseId)
        repo.selectPlan(planQ)
        val second = awaitPlan(planQ)
        assertEquals("Running", second.planName)
        assertEquals("A", second.day.dayId)
        assertEquals(repo.slotsFor(planQ, "A").first().single().programExerciseId, second.day.exercises.single().programExerciseId)
        repo.selectPlan(planP)
        assertEquals("Strength", awaitPlan(planP).planName)
    }

    /** [F] */
    @Test fun `an open run supplies the watch and widget even when another plan is selected`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null,
        ))))
        val plan = repo.selectedPlanIdFlow.first()!!
        repo.renamePlan(plan, "Strength")
        val other = repo.duplicatePlan(plan, "Running")
        val slot = repo.slotsFor(plan, "A").first().single().programExerciseId
        val preview = repo.dayTracksFlow(plan, "A").first()
        repo.updateSets(preview.runId!!, "A", slot, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP, true)))
        repo.selectPlan(other)
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)
        val snapshot = source.snapshots.first { it != null }!!
        assertEquals(plan, snapshot.planId)
        assertEquals("Strength", snapshot.planName)
        assertEquals("A", snapshot.day.dayId)
        assertEquals(slot, snapshot.day.exercises.single().programExerciseId)
        assertTrue(snapshot.day.exercises.single().sets.single().done)
        assertEquals(1, todayWidgetContent(snapshot).setsDone)
    }

    @Test
    fun `a rest-settings edit changes the projected snapshot content`() = runTest(dispatcher) {
        repo.replaceProgram(
            Program(listOf(ProgramDay("A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null))),
        )
        db.planDao().updateSuggestedDay(repo.selectedPlanIdFlow.first()!!, "A")
        val slotId = repo.daySlotsFlow("A").first().single().programExerciseId
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slotId) ?: -1, "A").first().runId.orEmpty(), "A", slotId, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP)))

        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), syncStore)

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
