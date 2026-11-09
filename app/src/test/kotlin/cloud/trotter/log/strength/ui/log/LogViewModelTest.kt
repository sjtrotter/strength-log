package cloud.trotter.log.strength.ui.log

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.ImportedSession
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.TrackEdit
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.transfer.health.HealthConnectReader
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.day.CardioEntryDraft
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LogViewModel] wiring (journal sections per docs/briefs/journal.md): the
 * reverse-chronological list shape, the lazy expand-fetches-once-then-caches
 * behavior, and that the journal sections come off real history — including
 * collapsing to nothing on a fresh install. The pure grouping/format/derivation
 * logic itself is [LogScreenBuilderTest]'s and [JournalBuilderTest]'s job — this
 * is the Robolectric + in-memory Room seam, same pattern as
 * [cloud.trotter.log.strength.ui.day.DayViewModelWiringTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<LogViewModel>()

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
            File.createTempFile("log-vm-settings", ".preferences_pb")
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
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    // No Health Connect provider under Robolectric — the no-provider reader makes
    // every HC read a degrade-safe empty, isolating these tests to own-history
    // (and keeping androidx.health entirely out of :app's test classpath).
    private val healthReader = HealthConnectReader()

    /**
     * A reader that answers the two questions the Log section's status turns on,
     * both mutable so a test can revoke a grant between refreshes. It inherits
     * the no-provider reads, so the external-session list and bodyweight stay
     * empty — this is about reporting publishing status, not about faking a
     * provider.
     */
    private class FakeHealthReader(
        var available: Boolean = true,
        var publishing: Boolean = true,
    ) : HealthConnectReader("fake") {
        override fun isAvailable(): Boolean = available
        override suspend fun publishesWorkouts(): Boolean = publishing
    }

    /** Records what a backfill hands over, and can refuse it (partial failure). */
    private class RecordingPublisher(
        private val succeeds: Boolean = true,
        private val cardioSucceeds: Boolean = true,
    ) : SessionPublisher {
        val backfilled = mutableListOf<List<Long>>()
        val cardioBackfilled = mutableListOf<List<Long>>()
        val published = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        val cardioPublished = mutableListOf<Long>()
        val cardioReplaced = mutableListOf<Long>()

        /** Session completion doesn't happen on the Log screen. */
        override suspend fun publish(sessionId: Long) { published += sessionId }
        override suspend fun delete(sessionId: Long) { deleted += sessionId }
        override suspend fun publishCardio(cardioSessionId: Long) { cardioPublished += cardioSessionId }
        override suspend fun replaceCardio(cardioSessionId: Long) { cardioReplaced += cardioSessionId }

        override suspend fun publishAll(sessionIds: List<Long>): Boolean {
            backfilled += sessionIds
            return succeeds
        }

        override suspend fun publishAllCardio(cardioSessionIds: List<Long>): Boolean {
            cardioBackfilled += cardioSessionIds
            return cardioSucceeds
        }
    }

    /**
     * A device clock the test moves by hand. [wallClock] is what the device
     * would say if asked right now; it only reaches the screen when the source
     * wakes — on its own at midnight or on a time/zone broadcast ([tick]), or
     * because the screen resumed ([refresh]). Keeping those two apart is the
     * whole point: it is what lets a test park a screen across midnight and
     * watch it go stale.
     */
    private class FakeCivilTimeSource(initial: CivilTime) : CivilTimeSource {
        private val emissions = MutableStateFlow(initial)

        // Mirror the production contract: a fresh collection reads the wall
        // clock NOW (not a replayed past value), and same-(date, zone) signals
        // stay quiet.
        override val civilTime: Flow<CivilTime> = kotlinx.coroutines.flow.flow {
            beforeRead?.also { beforeRead = null }?.invoke()
            emissions.value = wallClock
            emitAll(emissions)
        }.distinctUntilChanged { a, b -> a.date == b.date && a.zone == b.zone }

        var wallClock: CivilTime = initial
            private set
        var beforeRead: (suspend () -> Unit)? = null

        fun set(instant: Instant, zone: ZoneId = wallClock.zone) {
            wallClock = CivilTime.now(instant, zone)
        }

        /** Local midnight, or an ACTION_TIME/DATE/TIMEZONE_CHANGED broadcast. */
        fun tick() {
            emissions.value = wallClock
        }

        override fun refresh() {
            emissions.value = wallClock
        }
    }

    // Fixed so the journal's "which week / which month is now" is deterministic.
    private val civilTime = FakeCivilTimeSource(
        CivilTime.now(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC),
    )

    private val shareCardService by lazy { ShareCardService(ApplicationProvider.getApplicationContext(), repo.history, settings = settings) }

    private fun newViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        reader: HealthConnectReader = healthReader,
        publisher: SessionPublisher = SessionPublisher.NoOp,
    ): LogViewModel =
        LogViewModel(repo.history, reader, publisher, shareCardService, handle, civilTime, settings = settings, plans = repo.plans, catalog = repo.catalog, tracker = repo).also { vms += it }

    private fun session(dayId: String, dayTitle: String, completedAt: Long) =
        WorkoutSessionEntity(id = 0, dayId = dayId, dayTitle = dayTitle, startedAt = null, completedAt = completedAt, bodyweightLb = 180.0)

    private fun set(exerciseId: String, name: String) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 100.0, reps = 8, done = true,
    )

    private suspend fun seedTwoSessions() {
        repo.history.importSessionHistory(
            listOf(
                ImportedSession(session("A", "Lower", 1_000L), listOf(set("bb_back_squat", "Barbell Back Squat"))),
                ImportedSession(session("B", "Upper", 2_000L), listOf(set("bb_bench", "Barbell Bench Press"), set("bb_row", "Barbell Row"))),
            ),
            newCustomExercises = emptyList(),
        )
    }

    private suspend fun logFirstSet(runId: String, planId: Long, slotId: Long, slot: String = Slot.MAIN) {
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, planId, "A", listOf(
            TrackEdit(slotId, slot) { rows -> rows.mapIndexed { index, row -> row.copy(done = index == 0) } },
        )))
    }

    @Test
    fun rowsCarryPlanIdsAndCopiedNamesForStrengthCardioAndUnnamedHistory() = runVmTest {
        db.sessionDao().insertSessions(listOf(
            session("A", "Lower", 1_000).copy(planId = 1, planName = "Test prep"),
            session("B", "Upper", 2_000),
        ))
        db.cardioSessionDao().insert(CardioSessionEntity(
            dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Zone 2",
            startedAt = 3_000, completedAt = 63_000, seconds = 60, stepsCompleted = 1,
            planId = 2, planName = "Run plan",
        ))
        val vm = newViewModel()
        vm.viewModelScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("Run plan", null, "Test prep"), vm.uiState.value.sessions.map { it.planName })
        assertEquals(listOf(2L, null, 1L), vm.uiState.value.sessions.map { it.planId })
    }

    /** The empty journal's action depends on this, and offering to start a
     *  session there with no program would promise a workout that lands on
     *  NO PROGRAM YET. */
    @Test
    fun reportsWhetherThereIsAProgramToStartASessionFrom() = runVmTest {
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasProgram)

        insertProgram()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasProgram)
        assertEquals("A", vm.uiState.value.nextDayId)
        collect.cancel()
    }

    @Test
    fun emptyHistoryUsesTheSuggestedDayAndTheSharedFallback() = runVmTest {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Lower", "", listOf(ProgramExercise("bb_back_squat"))),
            ProgramDay("B", "Upper", "", listOf(ProgramExercise("bb_bench"))),
        )))
        val planId = repo.plans.selectedPlanIdFlow.first()!!
        db.planDao().updateSuggestedDay(planId, "B")
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals("B", vm.uiState.value.nextDayId)
        db.planDao().updateSuggestedDay(planId, "missing")
        advanceUntilIdle()
        assertEquals("A", vm.uiState.value.nextDayId)
        collect.cancel()
    }

    @Test
    fun listsSessionsNewestFirstWithNoGroupsUntilExpanded() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val sessions = vm.uiState.value.sessions
        assertEquals(listOf("Upper", "Lower"), sessions.map { it.dayTitle })
        assertEquals(2, sessions[0].setCount)
        assertEquals(1, sessions[1].setCount)
        assertTrue(sessions.none { it.expanded })
        assertTrue(sessions.all { it.exerciseGroups == null })
        collect.cancel()
    }

    @Test
    fun expandedLiftNotesKeepTheFinishTimeNoteAfterPlanSelectionAndNoteEdits() = runVmTest {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Lower", "", listOf(ProgramExercise("bb_back_squat",
                superset = SupersetPartner("bb_row"), note = "Seat 4"))),
            ProgramDay("B", "Other day", "", listOf(ProgramExercise("bb_back_squat", note = "Other day note"))),
        )))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        repo.startRun(plan, "A")
        val runId = repo.openRunFlow.first()!!.id
        logFirstSet(runId, plan, slot)
        logFirstSet(runId, plan, slot, Slot.SS)
        repo.setExerciseNote(slot, "Seat 5")
        val id = repo.finishRun(runId)!!
        repo.history.setSessionNote(id, "Felt steady")
        val other = repo.plans.duplicatePlan(plan, "Other plan")
        repo.setExerciseNote(repo.plans.slotsFor(other, "A").first().single().programExerciseId, "Other plan note")
        repo.plans.selectPlan(other)
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()
        val expected = listOf(
            SessionLiftNote("Barbell Back Squat", "Seat 5"),
            SessionLiftNote("Barbell Row", "Seat 5"),
        )
        assertEquals(expected, vm.uiState.value.sessions.single().liftNotes)
        assertEquals("Felt steady", vm.uiState.value.sessions.single().note)

        repo.setExerciseNote(slot, "Seat 6")
        advanceUntilIdle()
        assertEquals(expected, vm.uiState.value.sessions.single().liftNotes)
        repo.setExerciseNote(slot, "")
        advanceUntilIdle()
        assertEquals(expected, vm.uiState.value.sessions.single().liftNotes)
        assertEquals("Felt steady", repo.history.session(id)?.note)
        vm.toggleExpanded(id)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessions.single().liftNotes.isEmpty())
        collect.cancel()
    }

    @Test
    fun expandedLiftNotesDeduplicateNameAndNotePairsInSnapshotOrderIncludingUnloggedTracks() = runVmTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Lower", "", listOf(
            ProgramExercise("seated_curl", note = "Seat 4"),
            ProgramExercise("seated_curl", note = "Seat 5"),
            ProgramExercise("seated_curl", note = "Seat 4"),
            ProgramExercise("seated_curl", note = "  "),
            ProgramExercise("bb_bench", note = "Seat 4"),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slots = repo.plans.slotsFor(plan, "A").first()
        repo.startRun(plan, "A")
        slots.forEach { repo.setExerciseNote(it.programExerciseId, it.exercise.note) }
        val runId = repo.openRunFlow.first()!!.id
        logFirstSet(runId, plan, slots.first().programExerciseId)
        val id = repo.finishRun(runId)!!
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()

        val expected = listOf(
            SessionLiftNote("Seated Leg Curl", "Seat 4"),
            SessionLiftNote("Seated Leg Curl", "Seat 5"),
            SessionLiftNote("Barbell Bench Press", "Seat 4"),
        )
        assertEquals(expected, vm.uiState.value.sessions.single().liftNotes)
        slots.forEach { repo.setExerciseNote(it.programExerciseId, "Changed") }
        vm.toggleEdit(id)
        advanceUntilIdle()
        assertEquals(expected, vm.uiState.value.sessions.single().liftNotes)
        collect.cancel()
    }

    @Test
    fun reorderingDuplicateLegCurlSlotsAfterFinishKeepsSnapshotNoteOrder() = runVmTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Lower", "", listOf(
            ProgramExercise("seated_curl", note = "Seat 4"),
            ProgramExercise("seated_curl", note = "Seat 5"),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slots = repo.plans.slotsFor(plan, "A").first()
        repo.startRun(plan, "A")
        slots.forEach { repo.setExerciseNote(it.programExerciseId, it.exercise.note) }
        val runId = repo.openRunFlow.first()!!.id
        logFirstSet(runId, plan, slots.first().programExerciseId)
        val id = repo.finishRun(runId)!!
        repo.moveExercise(slots.first().programExerciseId, up = false)
        assertEquals(slots.reversed().map { it.programExerciseId },
            repo.plans.slotsFor(plan, "A").first().map { it.programExerciseId })

        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()

        assertEquals(listOf(
            SessionLiftNote("Seated Leg Curl", "Seat 4"),
            SessionLiftNote("Seated Leg Curl", "Seat 5"),
        ), vm.uiState.value.sessions.single().liftNotes)
        collect.cancel()
    }

    @Test
    fun reorderAndWorkoutOnlySwapKeepBothCurlNotesAtTheSameArchivedPosition() = runVmTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Lower", "", listOf(
            ProgramExercise("seated_curl", note = "Seat 4"),
            ProgramExercise("db_bench", note = "Bench 2"),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slots = repo.plans.slotsFor(plan, "A").first()
        repo.startRun(plan, "A")
        slots.forEach { repo.setExerciseNote(it.programExerciseId, it.exercise.note) }
        val runId = repo.openRunFlow.first()!!.id
        logFirstSet(runId, plan, slots.first().programExerciseId)
        repo.moveExercise(slots.last().programExerciseId, up = true)
        assertTrue(repo.swapInRun(runId, slots.last().programExerciseId, Slot.MAIN, "seated_curl"))
        logFirstSet(runId, plan, slots.last().programExerciseId)
        val id = repo.finishRun(runId)!!
        val snapshot = SessionPlanJson.decode(repo.history.session(id)!!.plannedJson!!)
        assertEquals(listOf(0, 0), snapshot.filter { !it.retired }.map { it.position })
        assertEquals(listOf("seated_curl", "seated_curl"), snapshot.filter { !it.retired }.map { it.exerciseId })
        assertEquals(listOf(0, 0), repo.history.sessionSets(id).filter { it.done }.map { it.trackPosition })

        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()

        assertEquals(listOf(
            SessionLiftNote("Seated Leg Curl", "Seat 4"),
            SessionLiftNote("Seated Leg Curl", "Bench 2"),
        ), vm.uiState.value.sessions.single().liftNotes)
        collect.cancel()
    }

    @Test
    fun workoutOnlyMainAndSupersetSwapsShowPerformedNamesAndExcludeRetiredTracks() = runVmTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Upper", "", listOf(
            ProgramExercise("db_bench", superset = SupersetPartner("cable_row"), note = "Bench 2"),
            ProgramExercise("bb_bench", note = "Rack 3"),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(plan, "A").first().first()
        repo.startRun(plan, "A")
        repo.plans.slotsFor(plan, "A").first().forEach { repo.setExerciseNote(it.programExerciseId, it.exercise.note) }
        val runId = repo.openRunFlow.first()!!.id
        assertTrue(repo.swapInRun(runId, slot.programExerciseId, Slot.MAIN, "bb_bench"))
        assertTrue(repo.swapInRun(runId, slot.programExerciseId, Slot.SS, "bb_row"))
        logFirstSet(runId, plan, slot.programExerciseId)
        logFirstSet(runId, plan, slot.programExerciseId, Slot.SS)
        val id = repo.finishRun(runId)!!
        assertEquals(slot, repo.plans.slotsFor(plan, "A").first().first())
        val snapshot = SessionPlanJson.decode(repo.history.session(id)!!.plannedJson!!)
        val swapped = snapshot.filter { it.position == slot.position && !it.retired }
        assertEquals(setOf("bb_bench", "bb_row"), swapped.map { it.exerciseId }.toSet())
        assertTrue(swapped.all { it.note == "Bench 2" })
        val retired = snapshot.filter { it.retired }
        assertEquals(setOf("db_bench", "cable_row"), retired.map { it.exerciseId }.toSet())
        assertTrue(retired.all { it.note == "Bench 2" })
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()

        val expected = listOf(
            SessionLiftNote("Barbell Bench Press", "Bench 2"),
            SessionLiftNote("Barbell Row", "Bench 2"),
            SessionLiftNote("Barbell Bench Press", "Rack 3"),
        )
        assertEquals(expected, vm.uiState.value.sessions.single().liftNotes)
        repo.setExerciseNote(slot.programExerciseId, "Bench 3")
        advanceUntilIdle()
        assertEquals(expected, vm.uiState.value.sessions.single().liftNotes)
        collect.cancel()
    }

    @Test
    fun legacyHistoryWithoutASnapshotShowsNoLiftNotes() = runVmTest {
        insertProgram()
        val slot = repo.plans.daySlotsFlow("A").first().single().programExerciseId
        repo.setExerciseNote(slot, "Belt on top")
        seedTwoSessions()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val lowerId = vm.uiState.value.sessions.first { it.dayLetter == "A" }.sessionId
        vm.toggleExpanded(lowerId)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessions.first { it.sessionId == lowerId }.liftNotes.isEmpty())
        val upperId = vm.uiState.value.sessions.first { it.dayLetter == "B" }.sessionId
        vm.toggleExpanded(upperId)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessions.first { it.sessionId == upperId }.liftNotes.isEmpty())
        collect.cancel()
    }

    @Test
    fun oldSnapshotWithoutTheNoteFieldShowsNoCurrentLiftNotes() = runVmTest {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(plan, "A").first().single()
        repo.setExerciseNote(slot.programExerciseId, "Belt on top")
        val plannedJson = """[{"position":0,"exerciseId":"bb_back_squat","slot":"main","sets":[]}]"""
        assertNull(SessionPlanJson.decode(plannedJson).single().note)
        val id = db.sessionDao().insertSession(session("A", "Lower", 1_000).copy(
            planId = plan, plannedJson = plannedJson,
        ))
        db.sessionDao().insertSets(listOf(
            set("bb_back_squat", "Barbell Back Squat").copy(sessionId = id, trackPosition = 0),
        ))
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessions.single().liftNotes.isEmpty())
        collect.cancel()
    }

    @Test
    fun blankSnapshotNoteShowsNoLiftNotes() = runVmTest {
        val id = db.sessionDao().insertSession(session("A", "Lower", 1_000).copy(
            plannedJson = """[{"position":0,"exerciseId":"bb_back_squat","slot":"main","sets":[],"note":"   "}]""",
        ))
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessions.single().liftNotes.isEmpty())
        collect.cancel()
    }

    @Test
    fun malformedSnapshotShowsNoLiftNotesWithoutThrowing() = runVmTest {
        val id = db.sessionDao().insertSession(session("A", "Lower", 1_000).copy(
            plannedJson = "{}", note = "Felt steady",
        ))
        db.sessionDao().insertSets(listOf(set("bb_back_squat", "Barbell Back Squat").copy(sessionId = id)))
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        vm.toggleExpanded(id)
        advanceUntilIdle()

        val expanded = vm.uiState.value.sessions.single()
        assertTrue(expanded.expanded)
        assertTrue(expanded.liftNotes.isEmpty())
        assertEquals("Barbell Back Squat", expanded.exerciseGroups!!.single().exerciseName)
        assertEquals("Felt steady", expanded.note)
        vm.toggleEdit(id)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessions.single().liftNotes.isEmpty())
        collect.cancel()
    }

    @Test
    fun expandingASessionFetchesAndGroupsItsSets() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val upperId = vm.uiState.value.sessions.first { it.dayTitle == "Upper" }.sessionId

        vm.toggleExpanded(upperId)
        advanceUntilIdle()

        val expanded = vm.uiState.value.sessions.first { it.sessionId == upperId }
        assertTrue(expanded.expanded)
        assertEquals(listOf("Barbell Bench Press", "Barbell Row"), expanded.exerciseGroups?.map { it.exerciseName })
        collect.cancel()
    }

    @Test
    fun togglingAnExpandedSessionAgainCollapsesIt() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val sessionId = vm.uiState.value.sessions.first().sessionId

        vm.toggleExpanded(sessionId)
        advanceUntilIdle()
        vm.toggleExpanded(sessionId)
        advanceUntilIdle()

        val item = vm.uiState.value.sessions.first { it.sessionId == sessionId }
        assertFalse(item.expanded)
        assertNull(item.exerciseGroups)
        collect.cancel()
    }

    @Test
    fun expandingASecondSessionCollapsesTheFirst() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val (first, second) = vm.uiState.value.sessions.map { it.sessionId }

        vm.toggleExpanded(first)
        advanceUntilIdle()
        vm.toggleExpanded(second)
        advanceUntilIdle()

        val sessions = vm.uiState.value.sessions.associateBy { it.sessionId }
        assertFalse(sessions.getValue(first).expanded)
        assertTrue(sessions.getValue(second).expanded)
        collect.cancel()
    }

    @Test
    fun restoringWithAnExpandedSessionIdFetchesItsSets() = runVmTest {
        seedTwoSessions()
        // Simulate process death: a fresh VM is constructed with a
        // SavedStateHandle that already carries an expanded session id (as it
        // would survive), but expandedSets — an in-memory cache — starts empty.
        val probe = newViewModel()
        val probeCollect = launch { probe.uiState.collect {} }
        advanceUntilIdle()
        val upperId = probe.uiState.value.sessions.first { it.dayTitle == "Upper" }.sessionId
        probeCollect.cancel()

        val restoredHandle = SavedStateHandle(mapOf("log_expanded_session" to upperId))
        val vm = newViewModel(restoredHandle)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val expanded = vm.uiState.value.sessions.first { it.sessionId == upperId }
        assertTrue(expanded.expanded)
        assertEquals(listOf("Barbell Bench Press", "Barbell Row"), expanded.exerciseGroups?.map { it.exerciseName })
        collect.cancel()
    }

    @Test
    fun deletedSessionOffersUndoForFiveSecondsAndUndoRestoresIt() = runVmTest {
        seedTwoSessions()
        val publisher = RecordingPublisher()
        val vm = newViewModel(publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val deletedId = vm.uiState.value.sessions.first().sessionId

        vm.deleteSession(deletedId)
        runCurrent()
        advanceTimeBy(4_999)
        assertTrue(vm.uiState.value.sessions.any { it.sessionId == deletedId && it.undoPending })
        assertEquals(listOf(deletedId), publisher.deleted)

        vm.undoDeleteSession()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessions.any { it.sessionId == deletedId && !it.undoPending })
        assertEquals(listOf(deletedId), publisher.published)
        collect.cancel()
    }

    @Test
    fun deletedSessionUndoOfferExpiresAfterFiveSeconds() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel(publisher = RecordingPublisher())
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val deletedId = vm.uiState.value.sessions.first().sessionId

        vm.deleteSession(deletedId)
        runCurrent()
        advanceTimeBy(5_001)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.sessions.any { it.sessionId == deletedId })
        collect.cancel()
    }

    private suspend fun seedCardio(
        results: List<CardioSetResult> = listOf(ManualCardio.result(2413, 7100)),
    ): Long {
        val seconds = if (results.isEmpty()) 2413 else results.sumOf { it.elapsedSeconds }
        val summary = ManualCardio.summary(CardioActivity.TRAIL_RUN, seconds)
        val start = 1_783_512_000_000L
        val id = db.cardioSessionDao().insert(CardioSessionEntity(
            dayId = "A", mode = summary.mode.name, hard = false, label = summary.label,
            startedAt = start, completedAt = start + seconds * 1_000L,
            seconds = seconds, stepsCompleted = results.size, activity = CardioActivity.TRAIL_RUN.name, note = "Easy",
        ))
        db.cardioSetResultDao().insertAll(results.mapIndexed { position, result -> result.toEntity(id, position) })
        return id
    }

    @Test
    fun `edit opens a prefilled draft and save writes it`() = runVmTest {
        val id = seedCardio()
        val publisher = RecordingPublisher()
        val vm = newViewModel(publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.editCardio(id)
        advanceUntilIdle()

        val draft = vm.uiState.value.cardioEdit!!
        assertEquals(CardioEntryDraft(CardioActivity.TRAIL_RUN, 1_783_512_000_000L, 2413, "7100", "Easy"), draft)
        vm.changeCardioEdit(draft.copy(startedAt = draft.startedAt + 60_000, seconds = 2412, note = "Hilly"))
        vm.saveCardioEdit()
        advanceUntilIdle()

        assertEquals(draft.startedAt, repo.history.cardioSession(id)!!.startedAt)
        assertEquals(2412, repo.history.cardioSession(id)!!.seconds)
        assertEquals("40:12", vm.uiState.value.sessions.single().cardioDuration)
        assertEquals("Hilly", vm.uiState.value.sessions.single().note)
        assertNull(vm.uiState.value.cardioEdit)
        assertEquals(listOf(id), publisher.cardioReplaced)
        assertTrue(publisher.cardioPublished.isEmpty())
        collect.cancel()
    }

    @Test
    fun `a cardio save freezes its captured editor until the database commit`() = runVmTest {
        val id = seedCardio()
        val otherId = seedCardio()
        val publisher = RecordingPublisher()
        val vm = newViewModel(publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.editCardio(id)
        advanceUntilIdle()
        val draft = vm.uiState.value.cardioEdit!!.copy(seconds = 2412, note = "Saved")
        vm.changeCardioEdit(draft)
        val resume = CompletableDeferred<Unit>()
        civilTime.beforeRead = { resume.await() }

        vm.saveCardioEdit()
        vm.changeCardioEdit(draft.copy(note = "Changed"))
        vm.dismissCardioEdit()
        vm.editCardio(otherId)
        vm.saveCardioEdit()
        runCurrent()

        assertTrue(vm.uiState.value.cardioEditSaving)
        assertEquals(draft, vm.uiState.value.cardioEdit)
        assertEquals("Easy", repo.history.cardioSession(id)!!.note)
        assertTrue(publisher.cardioReplaced.isEmpty())
        resume.complete(Unit)
        runCurrent()

        assertFalse(vm.uiState.value.cardioEditSaving)
        assertNull(vm.uiState.value.cardioEdit)
        assertEquals("Saved", repo.history.cardioSession(id)!!.note)
        assertEquals(2412, repo.history.cardioSession(id)!!.seconds)
        assertEquals("Easy", repo.history.cardioSession(otherId)!!.note)
        assertEquals(listOf(id), publisher.cardioReplaced)
        collect.cancel()
    }

    @Test
    fun `a slow cardio publication cannot clear a newer editor or its saved draft`() = runVmTest {
        val id = seedCardio()
        val otherId = seedCardio()
        val resume = CompletableDeferred<Unit>()
        val publications = mutableListOf<Long>()
        val publisher = object : SessionPublisher by SessionPublisher.NoOp {
            override suspend fun publishCardio(cardioSessionId: Long) {
                publications += cardioSessionId
                resume.await()
            }
            override suspend fun replaceCardio(cardioSessionId: Long) = publishCardio(cardioSessionId)
        }
        val handle = SavedStateHandle()
        val vm = newViewModel(handle, publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.editCardio(id)
        advanceUntilIdle()
        vm.changeCardioEdit(vm.uiState.value.cardioEdit!!.copy(seconds = 2412, note = "Saved"))
        vm.saveCardioEdit()
        runCurrent()

        assertEquals(listOf(id), publications)
        assertEquals("Saved", repo.history.cardioSession(id)!!.note)
        assertNull(vm.uiState.value.cardioEdit)
        assertFalse(vm.uiState.value.cardioEditSaving)
        vm.dismissCardioEdit()
        vm.editCardio(otherId)
        runCurrent()
        val newerDraft = vm.uiState.value.cardioEdit!!.copy(note = "New draft")
        vm.changeCardioEdit(newerDraft)
        runCurrent()
        resume.complete(Unit)
        runCurrent()

        assertEquals(newerDraft, vm.uiState.value.cardioEdit)
        assertEquals(otherId, handle.get<Long>("log_cardio_edit_id"))
        assertEquals(newerDraft, handle.get<CardioEntryDraft>("log_cardio_edit_draft"))
        assertEquals("Easy", repo.history.cardioSession(otherId)!!.note)
        collect.cancel()
    }

    @Test
    fun `an interval edit validates elapsed time including gaps before saving`() = runVmTest {
        val id = seedCardio(List(3) { ManualCardio.result(600, 1000) })
        val session = repo.history.cardioSession(id)!!
        val now = civilTime.wallClock.instant.toEpochMilli()
        val start = now - 1_800_000
        db.cardioSessionDao().update(session.copy(startedAt = start, completedAt = start + 3_600_000))
        val before = repo.history.cardioSession(id)
        val rows = db.cardioSetResultDao().forSession(id)
        val publisher = RecordingPublisher()
        val vm = newViewModel(publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.editCardio(id)
        advanceUntilIdle()
        val draft = vm.uiState.value.cardioEdit!!.copy(note = "Edited")
        assertTrue(draft.valid(now))
        assertEquals(start + 3_600_000, vm.uiState.value.cardioEditCompletedAt)
        vm.changeCardioEdit(draft)
        vm.saveCardioEdit()
        advanceUntilIdle()

        assertEquals(before, repo.history.cardioSession(id))
        assertEquals(rows, db.cardioSetResultDao().forSession(id))
        assertEquals(draft, vm.uiState.value.cardioEdit)
        assertFalse(vm.uiState.value.cardioEditSaving)
        assertTrue(publisher.cardioPublished.isEmpty())
        assertTrue(publisher.cardioReplaced.isEmpty())

        civilTime.set(civilTime.wallClock.instant.plusSeconds(1800))
        vm.saveCardioEdit()
        advanceUntilIdle()

        val saved = repo.history.cardioSession(id)!!
        assertEquals(start, saved.startedAt)
        assertEquals(start + 3_600_000, saved.completedAt)
        assertEquals(1800, saved.seconds)
        assertEquals(rows, db.cardioSetResultDao().forSession(id))
        assertNull(vm.uiState.value.cardioEdit)
        assertEquals(listOf(id), publisher.cardioReplaced)
        collect.cancel()
    }

    @Test
    fun `an interval session opens with measures locked`() = runVmTest {
        val id = seedCardio(List(3) { ManualCardio.result(600, 1000) })
        val rows = db.cardioSetResultDao().forSession(id)
        val before = repo.history.cardioSession(id)!!
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.editCardio(id)
        advanceUntilIdle()

        val draft = vm.uiState.value.cardioEdit!!
        assertFalse(draft.measuresEditable)
        assertEquals("", draft.metresText)
        vm.changeCardioEdit(draft.copy(activity = CardioActivity.ROWER, startedAt = draft.startedAt + 60_000, note = "Moved"))
        vm.saveCardioEdit()
        advanceUntilIdle()

        val after = repo.history.cardioSession(id)!!
        assertEquals(rows, db.cardioSetResultDao().forSession(id))
        assertEquals(before.seconds, after.seconds)
        assertEquals(before.startedAt, after.startedAt)
        assertEquals(before.completedAt, after.completedAt)
        assertEquals(CardioActivity.ROWER.name, after.activity)
        assertEquals("Moved", after.note)
        collect.cancel()
    }

    @Test
    fun `the cardio draft survives recreation`() = runVmTest {
        val id = seedCardio()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.editCardio(id)
        advanceUntilIdle()
        val draft = vm.uiState.value.cardioEdit!!.copy(seconds = 2412, metresText = "7000", note = "Hilly")
        vm.changeCardioEdit(draft)
        advanceUntilIdle()
        collect.cancel()
        vm.viewModelScope.cancel()

        val restored = newViewModel(handle)
        val restoredCollect = launch { restored.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(draft, restored.uiState.value.cardioEdit)
        restored.saveCardioEdit()
        advanceUntilIdle()
        assertEquals(2412, repo.history.cardioSession(id)!!.seconds)
        assertNull(restored.uiState.value.cardioEdit)
        restoredCollect.cancel()
    }

    @Test
    fun `a restored cardio draft uses the stored start`() = runVmTest {
        val id = seedCardio()
        val session = repo.history.cardioSession(id)!!
        val draft = CardioEntryDraft(CardioActivity.ROWER, session.startedAt + 60_000, 2412, "7000", "Edited")
        val handle = SavedStateHandle(mapOf("log_cardio_edit_id" to id, "log_cardio_edit_draft" to draft))
        val vm = newViewModel(handle)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(draft.copy(startedAt = session.startedAt), vm.uiState.value.cardioEdit)
        vm.saveCardioEdit()
        advanceUntilIdle()

        val saved = repo.history.cardioSession(id)!!
        assertEquals(session.startedAt, saved.startedAt)
        assertEquals(session.startedAt + 2_412_000, saved.completedAt)
        assertEquals("Edited", saved.note)
        assertNull(vm.uiState.value.cardioEdit)
        collect.cancel()
    }

    @Test
    fun `a future cardio edit stays open and does not publish`() = runVmTest {
        val id = seedCardio()
        val publisher = RecordingPublisher()
        val vm = newViewModel(publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.editCardio(id)
        advanceUntilIdle()
        val before = repo.history.cardioSession(id)
        civilTime.set(Instant.ofEpochMilli(before!!.startedAt))
        val draft = vm.uiState.value.cardioEdit!!
        vm.changeCardioEdit(draft)
        vm.saveCardioEdit()
        advanceUntilIdle()

        assertEquals(before, repo.history.cardioSession(id))
        assertEquals(draft, vm.uiState.value.cardioEdit)
        assertTrue(publisher.cardioPublished.isEmpty())
        collect.cancel()
    }

    @Test
    fun `a legacy summary opens with measures locked and an unknown activity falls back`() = runVmTest {
        val id = seedCardio(emptyList())
        val session = repo.history.cardioSession(id)!!
        db.cardioSessionDao().update(session.copy(activity = "UNKNOWN"))
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.editCardio(id)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.cardioEdit!!.measuresEditable)
        assertEquals(CardioActivity.ROAD_RUN, vm.uiState.value.cardioEdit!!.activity)
        vm.dismissCardioEdit()
        advanceUntilIdle()
        assertNull(vm.uiState.value.cardioEdit)
        collect.cancel()
    }

    // --- journal sections (docs/briefs/journal.md) ---------------------------

    /** Day A with the squat as its ramped main, so the trajectory section has a
     *  lift to draw and the calendar has a day letter to carry. */
    private suspend fun insertProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Lower",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)),
                    ),
                ),
            ),
        )
    }

    private fun topSet(weightLb: Double) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = "bb_back_squat", exerciseName = "Barbell Back Squat",
        slot = Slot.MAIN, setIndex = 0, kind = SetKind.TOP.name, weightLb = weightLb, reps = 5, done = true,
    )

    /** Two squat sessions inside the fixed clock's twelve-week window. */
    private suspend fun seedSquatHistory() {
        val july1 = 1_782_907_200_000L // 2026-07-01T12:00:00Z
        val july8 = 1_783_512_000_000L // 2026-07-08T12:00:00Z
        repo.history.importSessionHistory(
            listOf(
                ImportedSession(session("A", "Lower", july1), listOf(topSet(225.0))),
                ImportedSession(session("A", "Lower", july8), listOf(topSet(235.0))),
            ),
            newCustomExercises = emptyList(),
        )
    }

    @Test
    fun equipmentSubstitutedNoviceMainsReachHistoryFromTheStoredRecipe() = runVmTest {
        val answers = WizardAnswers(
            equipment = setOf(Equipment.MACHINE),
            config = LifterConfig(level = ExperienceLevel.NOVICE),
        )
        val program = ProgramGenerator.generate(answers)
        val planId = repo.plans.createGeneratedPlan("Machines", answers, program)
        repo.plans.publishCreatedPlan(planId, answers)
        val entry = ExerciseLibrary.get("hack_squat")
        repo.history.importSessionHistory(
            listOf(ImportedSession(
                session("A", "Lower", 1_783_512_000_000L),
                listOf(set(entry.id, entry.name)),
            )),
            newCustomExercises = emptyList(),
        )
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val card = vm.uiState.value.journal.trajectories.single()
        assertEquals("hack_squat", card.exerciseId)
        assertEquals(listOf(100f), card.points.map { it.value })
        assertEquals(GoalCalculator.goalFor(entry, answers.config).toFloat(), card.goalValue)
        collect.cancel()
    }

    @Test
    fun journalDerivesTrajectoryVolumeAndCalendarFromHistory() = runVmTest {
        insertProgram()
        seedSquatHistory()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val journal = vm.uiState.value.journal
        val trajectory = journal.trajectories.single()
        assertEquals("Barbell Back Squat", trajectory.exerciseName)
        assertEquals(listOf(225f, 235f), trajectory.points.map { it.value })
        assertTrue("235 is the pinned squat GOAL", trajectory.goalMet)

        val volume = journal.volume
        assertNotNull(volume)
        assertEquals(2, volume!!.weeks.count { it.trained })

        val calendar = journal.calendar
        assertNotNull(calendar)
        assertEquals("July 2026", calendar!!.title)
        assertEquals("A", calendar.days.single { it.dayOfMonth == 8 }.dayLetter)
        collect.cancel()
    }

    @Test
    fun journalIsEmptyOnAFreshInstall() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val journal = vm.uiState.value.journal
        assertTrue(journal.trajectories.isEmpty())
        assertNull(journal.volume)
        assertNull(journal.calendar)
        collect.cancel()
    }

    @Test
    fun pagingTheCalendarWalksBackwardAndNeverPastTheCurrentMonth() = runVmTest {
        insertProgram()
        seedSquatHistory()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.pageCalendar(-1)
        advanceUntilIdle()
        assertEquals("June 2026", vm.uiState.value.journal.calendar?.title)

        vm.pageCalendar(1)
        vm.pageCalendar(1)
        advanceUntilIdle()
        assertEquals("July 2026", vm.uiState.value.journal.calendar?.title)
        collect.cancel()
    }

    @Test
    fun theCalendarMonthSurvivesProcessDeath() = runVmTest {
        insertProgram()
        seedSquatHistory()
        val restored = newViewModel(SavedStateHandle(mapOf("log_calendar_month_offset" to -1)))
        val collect = launch { restored.uiState.collect {} }
        advanceUntilIdle()

        assertEquals("June 2026", restored.uiState.value.journal.calendar?.title)
        collect.cancel()
    }

    // --- the civil day -------------------------------------------------------
    //
    // Nothing in Room or DataStore changes at midnight, so the journal must
    // observe the civil-time source to update its "today" marker, month and
    // twelve-week window. A timezone change must recompute every boundary in the
    // new zone.

    @Test
    fun midnightMovesTheTodayMarkerAndTheCalendarMonth() = runVmTest {
        insertProgram()
        seedSquatHistory()
        civilTime.set(Instant.parse("2026-07-31T23:59:00Z"))
        civilTime.tick()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals("July 2026", vm.uiState.value.journal.calendar?.title)
        assertEquals(31, vm.uiState.value.journal.calendar?.days?.single { it.isToday }?.dayOfMonth)

        civilTime.set(Instant.parse("2026-08-01T00:00:00Z"))
        civilTime.tick()
        advanceUntilIdle()

        val calendar = vm.uiState.value.journal.calendar
        assertEquals("August 2026", calendar?.title)
        assertEquals(1, calendar?.days?.single { it.isToday }?.dayOfMonth)
        collect.cancel()
    }

    /** The same instant, two zones, two different civil days — the zone is
     *  re-read per emission rather than frozen when the graph was built. */
    @Test
    fun aTimezoneChangeRecomputesTheDayInTheNewZone() = runVmTest {
        insertProgram()
        seedSquatHistory()
        // 14:00 on July 31st in New York; 06:00 on August 1st in Auckland.
        val instant = Instant.parse("2026-07-31T18:00:00Z")
        civilTime.set(instant, ZoneId.of("America/New_York"))
        civilTime.tick()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals("July 2026", vm.uiState.value.journal.calendar?.title)

        civilTime.set(instant, ZoneId.of("Pacific/Auckland"))
        civilTime.tick()
        advanceUntilIdle()

        val calendar = vm.uiState.value.journal.calendar
        assertEquals("August 2026", calendar?.title)
        assertEquals(1, calendar?.days?.single { it.isToday }?.dayOfMonth)
        collect.cancel()
    }

    /**
     * The gap `WhileSubscribed(STOP_TIMEOUT_MS)` leaves open: away and back
     * inside the timeout, collection never restarted, so nothing upstream
     * re-emitted while the day turned. The resume read is what closes it.
     */
    @Test
    fun aQuickResumeRereadsTheCivilDay() = runVmTest {
        insertProgram()
        seedSquatHistory()
        civilTime.set(Instant.parse("2026-07-31T23:59:00Z"))
        civilTime.tick()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        civilTime.set(Instant.parse("2026-08-01T00:00:30Z"))
        advanceUntilIdle()
        assertEquals("July 2026", vm.uiState.value.journal.calendar?.title)

        vm.onResumed()
        advanceUntilIdle()

        assertEquals("August 2026", vm.uiState.value.journal.calendar?.title)
        collect.cancel()
    }

    // --- share (docs/briefs/session-share.md) --------------------------------
    //
    // The happy path — render, write, build the ACTION_SEND intent — is
    // [cloud.trotter.log.strength.ui.log.share.ShareCardServiceTest]'s
    // job under a plain `runBlocking`: [ShareCardService.buildShareIntent]
    // hops onto Dispatchers.IO internally (§3's "off the main thread"), which
    // is invisible to this class's virtual-time `TestDispatcher` and its
    // `advanceUntilIdle`, so it can't be asserted deterministically here. This
    // pins the one thing that *is* this class's concern: a miss leaves
    // [LogViewModel.pendingShare] untouched rather than crashing or firing a
    // stale intent.

    @Test
    fun shareSessionOfAnUnknownSessionLeavesPendingShareNull() = runVmTest {
        val vm = newViewModel()
        vm.shareSession(999L)
        advanceUntilIdle()
        assertNull(vm.pendingShare.value)
    }

    // --- Health Connect publishing status ------------------------------------

    @Test
    fun aGrantedWorkoutWritePermissionReadsAsPublishing() = runVmTest {
        val vm = newViewModel(reader = FakeHealthReader(available = true, publishing = true))
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.health.available)
        assertTrue(vm.uiState.value.health.publishing)
        collect.cancel()
    }

    /** The grant is re-read, never cached, so a permission revoked in the Health
     *  Connect app stops claiming to publish. */
    @Test
    fun aRevokedPermissionReadsAsNotPublishingOnTheNextRefresh() = runVmTest {
        val reader = FakeHealthReader(available = true, publishing = true)
        val vm = newViewModel(reader = reader)
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.health.publishing)

        reader.publishing = false
        vm.refreshHealth()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.health.available)
        assertFalse(vm.uiState.value.health.publishing)
        collect.cancel()
    }

    /** A read-only grant must leave the Connect card visible because it cannot
     *  publish workouts. */
    @Test
    fun aReadOnlyGrantIsNotPublishing() = runVmTest {
        val vm = newViewModel(reader = FakeHealthReader(available = true, publishing = false))
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.health.publishing)
        collect.cancel()
    }

    @Test
    fun noProviderIsNeitherAvailableNorPublishing() = runVmTest {
        val vm = newViewModel(reader = FakeHealthReader(available = false, publishing = true))
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.health.available)
        assertFalse(vm.uiState.value.health.publishing)
        collect.cancel()
    }

    // --- backfill offer ------------------------------------------------------

    @Test
    fun theBackfillOfferCountsTheHistoryTheScreenIsShowing() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel(reader = FakeHealthReader())
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()

        assertEquals(cloud.trotter.log.strength.ui.text.UiText.LogBackfill(running = false, count = 2), vm.uiState.value.health.backfill?.label)
        collect.cancel()
    }

    @Test
    fun thereIsNoBackfillOfferWithoutHistoryOrWithoutPublishing() = runVmTest {
        val empty = newViewModel(reader = FakeHealthReader())
        val emptyCollect = launch { empty.uiState.collect {} }
        empty.refreshHealth()
        advanceUntilIdle()
        assertNull("no history to publish", empty.uiState.value.health.backfill)
        emptyCollect.cancel()

        seedTwoSessions()
        val denied = newViewModel(reader = FakeHealthReader(publishing = false))
        val deniedCollect = launch { denied.uiState.collect {} }
        denied.refreshHealth()
        advanceUntilIdle()
        assertNull("a backfill would write nothing", denied.uiState.value.health.backfill)
        deniedCollect.cancel()
    }

    @Test
    fun backfillPublishesEverySessionAndThenStopsOffering() = runVmTest {
        seedTwoSessions()
        val publisher = RecordingPublisher()
        val vm = newViewModel(reader = FakeHealthReader(), publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()
        val sessionIds = vm.uiState.value.sessions.map { it.sessionId }

        vm.publishPastWorkouts()
        advanceUntilIdle()

        assertEquals(1, publisher.backfilled.size)
        assertEquals(sessionIds.sorted(), publisher.backfilled.single().sorted())
        assertNull("the one-shot offer is spent", vm.uiState.value.health.backfill)
        collect.cancel()
    }

    /** The gate is a conjunction: strength publishing cleanly while cardio
     *  fails (or vice versa) must leave the one-shot un-spent. */
    @Test
    fun aCardioOnlyFailureKeepsTheBackfillOffered() = runVmTest {
        seedTwoSessions()
        val publisher = RecordingPublisher(succeeds = true, cardioSucceeds = false)
        val vm = newViewModel(reader = FakeHealthReader(), publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()

        vm.publishPastWorkouts()
        advanceUntilIdle()

        assertNotNull("cardio failed, so the offer must survive", vm.uiState.value.health.backfill)
        collect.cancel()
    }

    /** A backfill that didn't publish everything must not claim it did, so the
     *  flag stays unset and the offer is still there next visit. */
    @Test
    fun aFailedBackfillKeepsOffering() = runVmTest {
        seedTwoSessions()
        val publisher = RecordingPublisher(succeeds = false)
        val vm = newViewModel(reader = FakeHealthReader(), publisher = publisher)
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()

        vm.publishPastWorkouts()
        advanceUntilIdle()

        assertEquals(cloud.trotter.log.strength.ui.text.UiText.LogBackfill(running = false, count = 2), vm.uiState.value.health.backfill?.label)
        assertFalse(settings.healthBackfillDoneFlow.first())
        collect.cancel()
    }

    /** The one-shot flag is persisted, not remembered: a fresh ViewModel over the
     *  same store makes no second offer. */
    @Test
    fun aCompletedBackfillStaysDoneAcrossViewModels() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel(reader = FakeHealthReader(), publisher = RecordingPublisher())
        val collect = launch { vm.uiState.collect {} }
        vm.refreshHealth()
        advanceUntilIdle()
        vm.publishPastWorkouts()
        advanceUntilIdle()
        collect.cancel()

        val restarted = newViewModel(reader = FakeHealthReader())
        val restartedCollect = launch { restarted.uiState.collect {} }
        restarted.refreshHealth()
        advanceUntilIdle()

        assertNull(restarted.uiState.value.health.backfill)
        restartedCollect.cancel()
    }
}
