package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.convertTestPlans
import cloud.trotter.log.strength.data.storeTestRows
import cloud.trotter.log.strength.data.writeTestRows
import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.LoggedSlot
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
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.acknowledgesCardio
import cloud.trotter.log.strength.widget.TodayWidgetContent
import cloud.trotter.log.strength.widget.todayWidgetContent
import java.io.File
import java.time.Clock
import java.time.LocalDate
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
    private val clock = Clock.systemDefaultZone()
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
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `pausing all plans publishes an empty snapshot with live acknowledgments`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val slot = repo.plans.daySlotsFlow("A").first().single().programExerciseId
        val rows = listOf(
            LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
            LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
            LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        )
        db.storeTestRows("A", slot, Slot.MAIN, rows)
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val before = source.snapshots.publishableSnapshots().first()
        val expected = WatchSnapshotBuilder.build(
            repo.plans.programFor(plan).first(), "A", repo.plans.slotsFor(plan, "A").first(), listOf(LoggedSlot(slot, Slot.MAIN, rows)),
            repo.plans.wizardAnswersFor(plan).first().config, repo.catalog.catalogFlow.first(), settings.unitFlow.first(),
            revision = 0L, planId = plan, planName = before.planName, runId = before.runId,
            restSettings = settings.restSettingsFlow.first(), equipment = repo.plans.wizardAnswersFor(plan).first().equipment,
            processedEditStamps = emptyList(),
        )
        assertEquals(expected, before)
        val widgetBefore = todayWidgetContent(before)
        repo.plans.pause(plan)
        syncStore.record(101)
        syncStore.record(100)
        syncStore.recordProcessedEdit(200)
        val empty = source.snapshots.publishableSnapshots().first()
        assertEquals("", empty.day.dayId)
        assertTrue(empty.day.exercises.isEmpty())
        assertEquals(101L, empty.cardioAckStamp)
        assertEquals(listOf(101L, 100L), empty.cardioAckedStamps)
        assertEquals(listOf(200L), empty.processedEditStamps)
        assertEquals(cloud.trotter.log.strength.widget.WidgetStatLine.NothingOnToday, todayWidgetContent(empty).statLine)
        syncStore.recordProcessedEdit(201)
        assertEquals(listOf(200L, 201L), source.snapshots.publishableSnapshots().first().processedEditStamps)
        repo.plans.useAlongside(plan)
        assertEquals(widgetBefore, todayWidgetContent(source.snapshots.publishableSnapshots().first()))
    }

    @Test
    fun `nothing on Today stays null while a restore marker is present`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Empty", "", emptyList()))))
        repo.plans.pause(repo.plans.selectedPlanIdFlow.first()!!)
        db.restoreMarkerDao().put(cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity(nonce = "pending"))
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        assertEquals(null, source.snapshots.first())
    }

    @Test
    fun `snapshot collected first waits for stored prose conversion`() = runTest(dispatcher) {
        db.planDao().insertPlan(PlanEntity(
            1, "Running", "GENERATED", 0, 1_000, suggestedDayId = "C1",
        ))
        db.programDao().upsertDay(ProgramDayEntity(
            1, "C1", 0, "Running", "", CardioDto.encode(
                CardioSuggestion("Intervals", "", true),
            ), kind = "CARDIO", nextRunId = "preview-C1",
        ))
        settings.setSelectedPlanId(1)
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)

        val snapshot = source.snapshots.publishableSnapshots().first()

        assertEquals(11, snapshot.cardioBlock?.sets?.size)
        assertEquals(null, snapshot.cardio?.mode)
        assertEquals("", snapshot.cardio?.detail)
        assertEquals(null, db.programDao().day(1, "C1")!!.cardioJson)
    }

    @Test
    fun `a throwing set transaction publishes no stamp and a committed retry publishes its stamp`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val preview = source.snapshots.publishableSnapshots().first()
        val edit = SetEditDelta(
            dayId = "A", programExerciseId = preview.day.exercises.single().programExerciseId,
            slot = Slot.MAIN, setIndex = 0, done = true, editedAtMillis = 100,
            planId = preview.planId, runId = preview.runId, exerciseId = "bb_back_squat",
        )
        val applier = SetEditApplier(repo, syncStore, syncStore, plans = repo.plans, catalog = repo.catalog)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_watch_track BEFORE INSERT ON run_track " +
                "BEGIN SELECT RAISE(ABORT, 'watch track write failed'); END",
        )
        val failure = runCatching { applier.apply(edit) }.exceptionOrNull()
        assertTrue(checkNotNull(failure).message.orEmpty().contains("watch track write failed"))
        assertEquals(null, repo.openRunFlow.first())
        assertEquals(emptyList<Long>(), source.snapshots.publishableSnapshots().first().processedEditStamps)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_watch_track")
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
        val committed = source.snapshots.publishableSnapshots().first()
        assertEquals(listOf(100L), committed.processedEditStamps)
        assertTrue(committed.day.exercises.single().sets.first().done)
        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(edit.copy(editedAtMillis = 99)))
        assertEquals(listOf(100L, 99L), source.snapshots.publishableSnapshots().first().processedEditStamps)
    }

    @Test
    fun `a throwing swap transaction publishes no stamp and a committed retry publishes its stamp`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val preview = source.snapshots.publishableSnapshots().first()
        val exercise = preview.day.exercises.single()
        val alternate = exercise.alternates.first()
        val swap = ExerciseSwapDelta(
            dayId = "A", programExerciseId = exercise.programExerciseId,
            exerciseId = alternate.exerciseId, exerciseName = alternate.name, editedAtMillis = 100,
            planId = preview.planId, runId = preview.runId,
        )
        val applier = SetEditApplier(repo, syncStore, syncStore, plans = repo.plans, catalog = repo.catalog)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_watch_swap BEFORE INSERT ON run_track " +
                "BEGIN SELECT RAISE(ABORT, 'watch swap write failed'); END",
        )
        val failure = runCatching { applier.apply(swap) }.exceptionOrNull()
        assertTrue(checkNotNull(failure).message.orEmpty().contains("watch swap write failed"))
        val failed = source.snapshots.publishableSnapshots().first()
        assertEquals(emptyList<Long>(), failed.processedEditStamps)
        assertEquals("bb_back_squat", failed.day.exercises.single().exerciseId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_watch_swap")
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(swap))
        val committed = source.snapshots.publishableSnapshots().first()
        assertEquals(listOf(100L), committed.processedEditStamps)
        assertEquals(alternate.exerciseId, committed.day.exercises.single().exerciseId)
        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(swap.copy(
            exerciseId = exercise.exerciseId, exerciseName = exercise.name, editedAtMillis = 99,
        )))
        assertEquals(listOf(100L, 99L), source.snapshots.publishableSnapshots().first().processedEditStamps)
    }

    @Test
    fun `rejected and invalid edits and swaps publish their processed stamps`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val preview = source.snapshots.publishableSnapshots().first()
        val exercise = preview.day.exercises.single()
        val alternate = exercise.alternates.first()
        val applier = SetEditApplier(repo, syncStore, syncStore, plans = repo.plans, catalog = repo.catalog)
        val edit = SetEditDelta(
            dayId = "A", programExerciseId = exercise.programExerciseId,
            slot = Slot.MAIN, setIndex = 0, done = true, editedAtMillis = 100,
            planId = preview.planId, runId = "unknown", exerciseId = exercise.exerciseId,
        )
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(edit))
        assertEquals(listOf(100L), source.snapshots.publishableSnapshots().first().processedEditStamps)
        val swap = ExerciseSwapDelta(
            dayId = "A", programExerciseId = exercise.programExerciseId,
            exerciseId = alternate.exerciseId, exerciseName = alternate.name, editedAtMillis = 101,
            planId = preview.planId, runId = "unknown",
        )
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(swap))
        assertEquals(listOf(100L, 101L), source.snapshots.publishableSnapshots().first().processedEditStamps)
        assertEquals(SetEditApplier.Outcome.INVALID, applier.apply(edit.copy(slot = "invalid", editedAtMillis = 102)))
        assertEquals(SetEditApplier.Outcome.INVALID, applier.apply(swap.copy(exerciseId = "invalid", editedAtMillis = 103)))
        val refused = source.snapshots.publishableSnapshots().first()
        assertEquals(listOf(100L, 101L, 102L, 103L), refused.processedEditStamps)
        assertEquals(null, refused.runStartedAt)
        assertEquals(exercise.exerciseId, refused.day.exercises.single().exerciseId)
    }

    @Test
    fun `processed edit stamps republish in processing order and stay bounded to 32`() = runTest(dispatcher) {
        val planId = repo.plans.createAuthoredPlan("Rehab", "Daily rehab")
        repo.plans.publishCreatedAuthoredPlan(planId)
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val snapshots = Channel<WatchSnapshot>(Channel.UNLIMITED)
        backgroundScope.launch {
            source.snapshots.publishableSnapshots().collect { snapshots.send(it) }
        }
        suspend fun awaitStamps(stamps: List<Long>): WatchSnapshot {
            while (true) {
                val snapshot = snapshots.receive()
                if (snapshot.processedEditStamps == stamps) return snapshot
            }
        }
        awaitStamps(emptyList())
        for (stamp in 1L..40L) syncStore.recordProcessedEdit(stamp)
        val bounded = awaitStamps((9L..40L).toList())
        assertEquals(bounded, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(bounded)))
        syncStore.recordProcessedEdit(20L)
        syncStore.recordProcessedEdit(5L)
        val next = awaitStamps((10L..40L).toList() + 5L)
        assertEquals((10L..40L).toList() + 5L, next.processedEditStamps)
    }

    @Test
    fun `an upgraded phone publishes its max as the only processed stamp`() = runTest(dispatcher) {
        val planId = repo.plans.createAuthoredPlan("Running", "Track")
        repo.plans.publishCreatedAuthoredPlan(planId)
        syncStore.markApplied(CardioDeltaApplier.MARKER_KEY, 101L)
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val snapshot = source.snapshots.publishableSnapshots().first()
        assertEquals(101L, snapshot.cardioAckStamp)
        // A lost 100 must stay queued on a new watch, which only the list rule does.
        assertEquals(listOf(101L), snapshot.cardioAckedStamps)
        assertFalse(snapshot.acknowledgesCardio(100L))
    }

    @Test
    fun `recorded cardio stamps republish even when a late stamp leaves the max unchanged`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
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
        val planId = repo.plans.createAuthoredPlan("Rehab", "Daily rehab")
        repo.plans.publishCreatedAuthoredPlan(planId)
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
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
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
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
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val firstPlan = repo.plans.selectedPlanIdFlow.first()!!
        val secondPlan = repo.plans.duplicatePlan(firstPlan, "Test prep")
        repo.plans.selectPlan(secondPlan)
        repo.replaceProgram(Program(listOf(ProgramDay(
            "B", "Bench", "", listOf(ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        repo.plans.selectPlan(firstPlan)
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val widgets = Channel<TodayWidgetContent>(Channel.UNLIMITED)
        backgroundScope.launch { source.snapshots.collect { widgets.send(todayWidgetContent(it)) } }
        suspend fun awaitDay(line: String): TodayWidgetContent {
            while (true) {
                val content = widgets.receive()
                if (content.dayLine == line) return content
            }
        }

        assertEquals("Day A, Squat", awaitDay("Day A, Squat").dayLine)
        repo.plans.selectPlan(secondPlan)
        assertEquals("Day B, Bench", awaitDay("Day B, Bench").dayLine)
        repo.plans.selectPlan(firstPlan)
        assertEquals("Day A, Squat", awaitDay("Day A, Squat").dayLine)
    }

    @Test
    fun `same day ids do not share cardio completion across plans`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
            cardioBlock = CardioBlocks.fromLegacy(CardioSuggestion("Zone 2", "20 minutes", hard = false), true, CardioActivity.ROAD_RUN),
        ))))
        val firstPlan = repo.plans.selectedPlanIdFlow.first()!!
        val secondPlan = repo.plans.duplicatePlan(firstPlan, "Test prep")
        val noon = LocalDate.now(clock).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val firstSession = CardioSessionEntity(
            dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Zone 2",
            startedAt = noon - 60_000, completedAt = noon, seconds = 60, stepsCompleted = 1, planId = firstPlan,
        )
        db.cardioSessionDao().insertAll(listOf(
            firstSession,
            firstSession.copy(planId = secondPlan, completedAt = noon + 60_000, seconds = 120),
            firstSession.copy(planId = null, completedAt = noon + 120_000, seconds = 180),
        ))
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)

        assertEquals(60, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
        repo.plans.selectPlan(secondPlan)
        assertEquals(120, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
        repo.plans.selectPlan(firstPlan)
        assertEquals(60, source.snapshots.first { it != null }?.cardio?.loggedSeconds)
    }

    @Test
    fun `live snapshots carry the selected plan and name across same-day plan switches`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val planP = repo.plans.selectedPlanIdFlow.first()!!
        repo.plans.renamePlan(planP, "Strength")
        val planQ = repo.plans.duplicatePlan(planP, "Running")
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
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
        assertEquals(repo.plans.slotsFor(planP, "A").first().single().programExerciseId, first.day.exercises.single().programExerciseId)
        repo.plans.selectPlan(planQ)
        val second = awaitPlan(planQ)
        assertEquals("Running", second.planName)
        assertEquals("A", second.day.dayId)
        assertEquals(repo.plans.slotsFor(planQ, "A").first().single().programExerciseId, second.day.exercises.single().programExerciseId)
        repo.plans.selectPlan(planP)
        assertEquals("Strength", awaitPlan(planP).planName)
    }

    /** [F] */
    @Test fun `an open run supplies the watch and widget even when another plan is selected`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.plans.renamePlan(plan, "Strength")
        val other = repo.plans.duplicatePlan(plan, "Running")
        val slot = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val preview = repo.dayTracksFlow(plan, "A").first()
        repo.writeTestRows(preview.runId!!, "A", slot, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP, true)))
        repo.plans.selectPlan(other)
        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
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
            Program(listOf(ProgramDay("A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF))))),
        )
        db.planDao().updateSuggestedDay(repo.plans.selectedPlanIdFlow.first()!!, "A")
        val slotId = repo.plans.daySlotsFlow("A").first().single().programExerciseId
        repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slotId) ?: -1, "A").first().runId.orEmpty(), "A", slotId, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP)))

        val source = TodaySnapshotSource(repo, kotlinx.coroutines.flow.MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)

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

    /** [F] */
    @Test
    fun `a preview publishes the next run and an open run publishes its start`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Squat", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)),
        ))))
        val planId = repo.plans.selectedPlanIdFlow.first()!!
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val preview = source.snapshots.publishableSnapshots().first()
        assertEquals(runId, preview.runId)
        assertEquals(null, preview.runStartedAt)
        assertEquals(null, preview.retiredRoundsDone)
        val slot = repo.plans.slotsFor(planId, "A").first().single().programExerciseId
        repo.writeTestRows(runId, "A", slot, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP, true)))
        val open = repo.openRunFlow.first()!!
        val started = source.snapshots.publishableSnapshots().first()
        assertEquals(open.id, started.runId)
        assertEquals(open.startedAt, started.runStartedAt)
    }

    /** [F] */
    @Test
    fun `a mid-run bench swap publishes two retired rounds for watch and widget progress`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Bench", "", listOf(ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 3)),
        ))))
        val planId = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(planId, "A").first().single().programExerciseId
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        repo.writeTestRows(runId, "A", slot, Slot.MAIN, List(3) { index -> LoggedSet(135.0, 5, SetKind.WORK, index < 2) })
        val alternate = repo.catalog.catalogFlow.first().substitutionsFor("bb_bench").first().id
        repo.swapExerciseById("A", slot, alternate)
        repo.ensureSeeded(planId, "A")
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore, settings = settings, plans = repo.plans, historyRepository = repo.history)
        val snapshot = source.snapshots.publishableSnapshots().first()
        assertEquals(2, snapshot.retiredRoundsDone)
        assertEquals(2, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(snapshot)).retiredRoundsDone)
        val widget = todayWidgetContent(snapshot)
        assertEquals(2, widget.setsDone)
        assertEquals(snapshot.day.exercises.single().sets.size + 2, widget.totalSets)
    }
    @Test fun `rest follows the slot role and the plan goal`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_back_squat", targetSets = 3), ProgramExercise("ez_curl", targetSets = 3),
        )))))
        db.convertTestPlans(settings)
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        db.planDao().updateSuggestedDay(plan, "A")
        val source = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore,
            settings = settings, plans = repo.plans, historyRepository = repo.history)
        suspend fun snapshot() = source.snapshots.first { it != null }!!
        fun check(snapshot: WatchSnapshot, squat: Int) {
            assertTrue(snapshot.day.exercises[0].sets.all { it.restAfterSeconds == squat })
            assertTrue(snapshot.day.exercises[1].sets.all { it.restAfterSeconds == 90 })
        }
        val first = snapshot()
        val slots = db.programDao().exercisesForDay(plan, "A")
        val squat = requireNotNull(cloud.trotter.log.strength.data.serialization.SlotRuleJson.decode(slots[0].ruleJson))
        val curl = requireNotNull(cloud.trotter.log.strength.data.serialization.SlotRuleJson.decode(slots[1].ruleJson))
        assertEquals(cloud.trotter.log.strength.domain.progression.SlotRole.MAIN, squat.role)
        assertEquals(cloud.trotter.log.strength.domain.progression.SlotRole.ACCESSORY, curl.role)
        check(first, 180)
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(settingsJson =
            cloud.trotter.log.strength.data.serialization.PlanSettingsJson.encode(
                cloud.trotter.log.strength.data.serialization.PlanSettings(cloud.trotter.log.strength.domain.progression.LiftGoal.MUSCLE))))
        check(snapshot(), 120)
        settings.setRestOverride(RestCategory.TOP, 100)
        check(snapshot(), 100)
        db.programDao().setRule(slots[0].id, cloud.trotter.log.strength.data.serialization.SlotRuleJson.encode(squat.copy(restSeconds = 150)))
        check(snapshot(), 150)
    }

}
