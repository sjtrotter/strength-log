package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.seeding.CarryForward
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Run identity, atomic writes, closing, and persistence are independent of live-log reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryRunTest {
    private lateinit var db: StrengthDatabase
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val ids = ArrayDeque((1..100).map { "id-$it" })
    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseName = "run-persistence-test.db"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("run-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("run-journal", ".preferences_pb")
        }, settings)
        repo = repository()
    }

    @After fun tearDown() {
        db.close()
        storeScope.cancel()
        context.deleteDatabase(databaseName)
    }

    /** [F] */
    @Test fun `day tracks keep a tick across midnight`() = runTest {
        val plan = authored()
        var instant = Instant.parse("2026-10-19T23:50:00Z")
        val changingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant() = instant
        }
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, changingClock)
        val id = start(plan)
        instant = Instant.parse("2026-10-20T00:10:00Z")
        val state = repo.dayTracksFlow(plan, "A").first()
        assertTrue(state.runOpen)
        assertEquals(id, state.runId)
        assertTrue(state.tracks.single().sets.first().done)
    }

    /** [F] */
    @Test fun `old-watch edits require an open run and a stamp newer than closedAt`() = runTest {
        val plan = authored()
        val edits = listOf(tick(slot(plan)))
        assertEquals(RunWriteResult.Rejected, repo.applyToOpenRun(plan, "A", 10_000, edits))
        assertNull(repo.openRunFlow.first())
        start(plan)
        val day = day(plan)
        db.programDao().upsertDay(day.copy(closedAt = 9_000))
        for (stamp in listOf(8_999L, 9_000L)) {
            assertEquals(RunWriteResult.Rejected, repo.applyToOpenRun(plan, "A", stamp, edits))
        }
        assertTrue(repo.applyToOpenRun(plan, "A", 9_001, edits) is RunWriteResult.InRun)
    }

    /** [F] */
    @Test fun `legacy session start moves run earlier and consumes all keys`() = runTest {
        val plan = authored()
        start(plan)
        dataStore.edit {
            it[longPreferencesKey("session_started_at")] = 1_000L
            it[stringPreferencesKey("session_started_date")] = "2026-10-19"
            it[longPreferencesKey("session_started_plan_id")] = plan
        }
        repo.adoptLegacySessionStartIfNeeded()
        assertEquals(1_000L, repo.openRunFlow.first()!!.startedAt)
        val prefs = dataStore.data.first()
        assertNull(prefs[longPreferencesKey("session_started_at")])
        assertNull(prefs[stringPreferencesKey("session_started_date")])
        assertNull(prefs[longPreferencesKey("session_started_plan_id")])
        assertNull(settings.takeLegacySessionStart())
    }

    /** [F] */
    @Test fun `stale-date legacy session start is consumed without changing the run`() = runTest {
        val plan = authored()
        start(plan)
        val before = repo.openRunFlow.first()!!.startedAt
        dataStore.edit {
            it[longPreferencesKey("session_started_at")] = 1_000L
            it[stringPreferencesKey("session_started_date")] = "2026-10-18"
            it[longPreferencesKey("session_started_plan_id")] = plan
        }
        repo.adoptLegacySessionStartIfNeeded()
        assertEquals(before, repo.openRunFlow.first()!!.startedAt)
        assertNull(settings.takeLegacySessionStart())
        val prefs = dataStore.data.first()
        assertNull(prefs[longPreferencesKey("session_started_at")])
        assertNull(prefs[stringPreferencesKey("session_started_date")])
        assertNull(prefs[longPreferencesKey("session_started_plan_id")])
    }

    /** [F] */
    @Test fun `legacy start date is compared in the repository clock zone`() = runTest {
        val plan = authored()
        val zonedClock = Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), java.time.ZoneId.of("America/Chicago"))
        val repository = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, zonedClock)
        val id = day(plan).nextRunId
        repository.applyToRun(id, plan, "A", listOf(tick(slot(plan))), listOf(RunSeed(slot(plan), Slot.MAIN, work())))
        val earlier = zonedClock.millis() - 1_000
        dataStore.edit {
            it[longPreferencesKey("session_started_at")] = earlier
            it[stringPreferencesKey("session_started_date")] = "2026-10-19"
            it[longPreferencesKey("session_started_plan_id")] = plan
        }
        repository.adoptLegacySessionStartIfNeeded()
        assertEquals(earlier, repository.openRunFlow.first()!!.startedAt)
        assertNull(settings.takeLegacySessionStart())
    }

    /** [F] */
    @Test fun `legacy restore splits on the repository clock and attaches minted identity`() = runTest {
        val plan = authored()
        val snapshot = repo.exportSnapshot()
        val legacy = cloud.trotter.log.strength.data.run.LegacyLog(
            plan, "A", slot(plan), Slot.MAIN, work().map { it.copy(done = true) }, "2026-10-19", 6_000,
        )
        repo.importSnapshot(snapshot.copy(days = snapshot.days.map { it.copy(nextRunId = "") },
            legacyLogs = listOf(legacy)), journal)
        val state = repo.dayTracksFlow(plan, "A").first()
        assertTrue(state.runOpen)
        assertEquals(db.programDao().day(plan, "A")!!.nextRunId, state.runId)
        assertTrue(state.tracks.single().sets.all { it.done })
        assertTrue(db.programDao().allLogs().flatMap { SetJson.decodeSets(it.setsJson) }.none { it.done })

        repo.importSnapshot(snapshot.copy(legacyLogs = listOf(legacy.copy(checkDate = "2026-10-18"))), journal)
        assertNull(repo.openRunFlow.first())
        assertTrue(repo.sessionsFlow.first().isEmpty())
        assertTrue(repo.dayTracksFlow(plan, "A").first().tracks.single().sets.none { it.done })
    }

    /** [F] */
    @Test fun `legacy restore leaves an existing run attached to its own day`() = runTest {
        val plan = authored()
        val runId = start(plan)
        val snapshot = repo.exportSnapshot()
        val legacy = listOf("A", "B").map { day ->
            cloud.trotter.log.strength.data.run.LegacyLog(plan, day, slot(plan, day), Slot.MAIN,
                work().map { it.copy(done = true) }, "2026-10-19", 6_000)
        }
        repo.importSnapshot(snapshot.copy(legacyLogs = legacy), journal)
        assertEquals(snapshot.runs, db.runDao().allRuns())
        assertEquals(snapshot.runTracks, db.runDao().allTracks())
        assertEquals(runId, day(plan).nextRunId)
        assertNull(day(plan).closedAt)
        assertEquals(listOf("B"), db.sessionDao().allSessions().map { it.dayId })
        assertTrue(db.programDao().allLogs().flatMap { SetJson.decodeSets(it.setsJson) }.none { it.done })
    }

    private fun repository() = TrackerRepository(
        db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock, { ids.removeFirst() },
    )

    private fun squat() = SetSeeder.seed(ProgramExercise("bb_back_squat", isMain = true), 235.0, LifterConfig())
    private fun work() = List(3) { LoggedSet(50.0, 10, SetKind.WORK) }
    private fun tick(id: Long, index: Int = 0) = TrackEdit(id, Slot.MAIN) { rows ->
        rows.mapIndexed { i, row -> if (i == index) row.copy(done = true) else row }
    }
    private fun weight(id: Long, value: Double) = TrackEdit(id, Slot.MAIN) { SetEditor.editWeight(it, 0, value) }
    private suspend fun day(planId: Long, dayId: String = "A") = checkNotNull(db.programDao().day(planId, dayId))
    private suspend fun slot(planId: Long, dayId: String = "A") = db.programDao().exercisesForDay(planId, dayId).first().id
    private suspend fun authored(): Long {
        val plan = repo.createAuthoredPlan("Authored", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("bb_back_squat", prescription = Prescription(PrescribedTarget.LoadReps(50.0, 10)), keepToPlan = true))
        repo.addDay(plan, "Day B")
        repo.addExercise("B", ProgramExercise("bb_bench", prescription = Prescription(PrescribedTarget.LoadReps(50.0, 10)), keepToPlan = true))
        return plan
    }
    private suspend fun start(plan: Long, dayId: String = "A"): String {
        val id = day(plan, dayId).nextRunId
        val slot = slot(plan, dayId)
        assertEquals(RunWriteResult.InRun(id, true), repo.applyToRun(id, plan, dayId, listOf(tick(slot)), listOf(RunSeed(slot, Slot.MAIN, work()))))
        return id
    }

    /** [F] */
    @Test fun `workout swap rejects a mismatched run without writing`() = runTest {
        val plan = authored()
        val id = slot(plan)
        for (open in listOf(false, true)) {
            if (open) start(plan)
            val beforeSlot = db.programDao().exercise(id)
            val beforeLogs = db.programDao().logsForDay(plan, "A")
            val beforeRun = repo.openRunFlow.first()
            assertFalse(repo.swapInRun(day(plan, "B").nextRunId, id, Slot.MAIN, "hack_squat", RunSeed(id, Slot.MAIN, work())))
            assertEquals(beforeSlot, db.programDao().exercise(id))
            assertEquals(beforeLogs, db.programDao().logsForDay(plan, "A"))
            assertEquals(beforeRun, repo.openRunFlow.first())
        }
    }

    /** [F] */
    @Test fun `workout swap requires an open run and rejects a finished run`() = runTest {
        val plan = authored()
        val id = slot(plan)
        val preview = day(plan).nextRunId
        val seed = RunSeed(id, Slot.MAIN, work())
        assertFalse(repo.swapInRun(preview, id, Slot.MAIN, "hack_squat", seed))
        val runId = start(plan)
        assertEquals(preview, runId)
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", seed))
        assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "bb_back_squat", null))
        repo.finishRun(runId)
        assertFalse(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", seed))
        assertEquals("bb_back_squat", db.programDao().exercise(id)!!.exerciseId)
    }

    private fun generated() = Program(listOf(
        ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_back_squat", isMain = true, superset = SupersetPartner("ez_curl")),
            ProgramExercise("bb_bench", prescription = Prescription(PrescribedTarget.LoadReps(50.0, 10)), keepToPlan = true),
        )),
        ProgramDay("B", "Day B", "", listOf(ProgramExercise("bb_bench"))),
    ))

    /** [F] */
    @Test fun `preview weight edit writes working values without starting`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        assertEquals(RunWriteResult.WorkingValues, repo.applyToRun(day(plan).nextRunId, plan, "A", listOf(weight(slot, 60.0)), listOf(RunSeed(slot, Slot.MAIN, work()))))
        assertEquals(60.0, SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson).first().weightLb, 0.0)
        assertNull(db.runDao().openRun())
    }

    /** [F] */
    @Test fun `first done tick captures every seeded slot and second tick resumes`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.plansFlow.first().single().id
        val slots = db.programDao().exercisesForDay(plan, "A")
        val seeds = listOf(RunSeed(slots[0].id, Slot.MAIN, squat()), RunSeed(slots[0].id, Slot.SS, work()), RunSeed(slots[1].id, Slot.MAIN, work()))
        val id = day(plan).nextRunId
        assertEquals(RunWriteResult.InRun(id, true), repo.applyToRun(id, plan, "A", listOf(tick(slots[0].id)), seeds))
        val run = repo.openRunFlow.first()!!
        assertEquals(id, run.id)
        assertEquals(1_792_411_200_000L, run.startedAt)
        assertEquals(3, run.tracks.size)
        run.tracks.forEach { track ->
            assertTrue(track.planned.all { !it.done && it.plannedIndex == null })
            assertEquals(track.sets.indices.toList(), track.sets.map { it.plannedIndex })
        }
        assertTrue(run.tracks.first().sets.first().done)
        assertEquals(RunWriteResult.InRun(id, false), repo.applyToRun(id, plan, "A", listOf(tick(slots[0].id, 1))))
        assertTrue(repo.openRunFlow.first()!!.tracks.first().sets[1].done)
    }

    /** [F] */
    @Test fun `superset pair ticks both tracks in one newly started run`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.plansFlow.first().single().id
        val slot = slot(plan)
        val beforeDay = day(plan)
        val beforePlan = db.planDao().plan(plan)
        val seeds = listOf(RunSeed(slot, Slot.MAIN, work()), RunSeed(slot, Slot.SS, work()))
        val edits = listOf(tick(slot), TrackEdit(slot, Slot.SS) { rows ->
            rows.mapIndexed { i, row -> if (i == 0) row.copy(done = true) else row }
        })

        assertEquals(RunWriteResult.InRun(beforeDay.nextRunId, true), repo.applyToRun(beforeDay.nextRunId, plan, "A", edits, seeds))

        assertEquals(listOf(beforeDay.nextRunId), db.runDao().allRuns().map { it.id })
        val tracks = db.runDao().allTracks()
        assertEquals(setOf(Slot.MAIN, Slot.SS), tracks.map { it.slot }.toSet())
        assertEquals(2, tracks.size)
        val expected = work().mapIndexed { i, row -> row.copy(done = i == 0, plannedIndex = i) }
        tracks.forEach { track ->
            assertEquals(beforeDay.nextRunId, track.runId)
            assertEquals(slot, track.programExerciseId)
            assertEquals(if (track.slot == Slot.MAIN) "bb_back_squat" else "ez_curl", track.exerciseId)
            assertEquals(work(), SetJson.decodeSets(track.plannedJson))
            assertEquals(expected, SetJson.decodeSets(track.setsJson))
        }
        assertTrue(db.programDao().allLogs().isEmpty())
        assertEquals(beforeDay, day(plan))
        assertEquals(beforePlan, db.planDao().plan(plan))
    }

    /** [F] */
    @Test fun `unticking an open run persists the row and keeps the run open`() = runTest {
        val plan = authored()
        val id = start(plan)
        val beforeRun = db.runDao().openRun()
        val beforeDay = day(plan)
        val beforePlan = db.planDao().plan(plan)
        val beforeTrack = db.runDao().tracks(id).single()
        assertTrue(SetJson.decodeSets(beforeTrack.setsJson).first().done)
        val edit = TrackEdit(slot(plan), Slot.MAIN) { rows ->
            rows.mapIndexed { i, row -> if (i == 0) row.copy(done = false) else row }
        }

        assertEquals(RunWriteResult.InRun(id, false), repo.applyToRun(id, plan, "A", listOf(edit)))

        val expected = SetJson.decodeSets(beforeTrack.setsJson).mapIndexed { i, row ->
            if (i == 0) row.copy(done = false) else row
        }
        assertEquals(listOf(beforeTrack.copy(setsJson = SetJson.encodeSets(expected))), db.runDao().allTracks())
        assertEquals(listOf(beforeRun), db.runDao().allRuns())
        assertTrue(db.sessionDao().allSessions().isEmpty())
        assertEquals(beforeDay, day(plan))
        assertEquals(beforePlan, db.planDao().plan(plan))
    }

    /** [F] */
    @Test fun `unticking a preview writes working values without starting a run`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot) ?: -1, "A").first().runId.orEmpty(), "A", slot, Slot.MAIN, work())
        val beforeDay = day(plan)
        val beforePlan = db.planDao().plan(plan)
        val edit = TrackEdit(slot, Slot.MAIN) { rows -> rows.map { it.copy(done = false) } }

        assertEquals(RunWriteResult.WorkingValues, repo.applyToRun(beforeDay.nextRunId, plan, "A", listOf(edit)))

        val log = db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!
        assertEquals(work(), SetJson.decodeSets(log.setsJson))
        assertTrue(db.runDao().allRuns().isEmpty())
        assertTrue(db.runDao().allTracks().isEmpty())
        assertTrue(db.sessionDao().allSessions().isEmpty())
        assertEquals(beforeDay, day(plan))
        assertEquals(beforePlan, db.planDao().plan(plan))
    }

    /** [F] */
    @Test fun `another day rejects ticks but permits preview edits`() = runTest {
        val plan = authored()
        start(plan)
        val slot = slot(plan, "B")
        val id = day(plan, "B").nextRunId
        val seeds = listOf(RunSeed(slot, Slot.MAIN, work()))
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(id, plan, "B", listOf(tick(slot)), seeds))
        assertEquals(RunWriteResult.WorkingValues, repo.applyToRun(id, plan, "B", listOf(weight(slot, 60.0)), seeds))
    }

    /** [F] */
    @Test fun `unknown blank mismatched day and empty edits reject`() = runTest {
        val plan = authored()
        val id = start(plan)
        val edit = listOf(tick(slot(plan)))
        for (invalid in listOf("nope", "")) assertEquals(RunWriteResult.Rejected, repo.applyToRun(invalid, plan, "A", edit))
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(id, plan, "B", edit))
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(id, plan, "A", emptyList()))
    }

    /** [F] */
    @Test fun `finish archives prescriptions and row identity and carries forward by next time choice`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.plansFlow.first().single().id
        val slots = db.programDao().exercisesForDay(plan, "A")
        val main = slots[0].id
        val authored = slots[1].id
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(main) ?: -1, "A").first().runId.orEmpty(), "A", main, Slot.MAIN, squat())
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(authored) ?: -1, "A").first().runId.orEmpty(), "A", authored, Slot.MAIN, work())
        val id = day(plan).nextRunId
        repo.applyToRun(id, plan, "A", listOf(tick(main)))
        repo.applyToRun(id, plan, "A", listOf(TrackEdit(main, Slot.MAIN) { SetEditor.editWeight(it, 4, 245.0) }))
        val before = repo.openRunFlow.first()!!
        val sessionId = repo.finishRun(id)!!
        val session = db.sessionDao().sessionById(sessionId)!!
        assertEquals(id, session.runId)
        assertEquals(before.startedAt, session.startedAt)
        val planned = SessionPlanJson.decode(session.plannedJson!!)
        assertEquals(before.tracks.map { it.planned }, planned.map { track -> track.sets.map { it.toDomain() } })
        assertEquals(before.tracks.map { it.position }, planned.map { it.position })
        val archived = repo.sessionSets(sessionId)
        before.tracks.forEach { track ->
            val rows = archived.filter { it.exerciseId == track.exerciseId && it.slot == track.slot }
            assertEquals(track.sets.map { it.plannedIndex }, rows.map { it.plannedIndex })
            assertTrue(rows.all { it.trackPosition == track.position })
        }
        assertNull(db.runDao().openRun())
        assertTrue(db.runDao().allTracks().isEmpty())
        assertNotEquals(id, day(plan).nextRunId)
        assertTrue(day(plan).nextRunId.startsWith("id-"))
        assertEquals(clock.millis(), day(plan).closedAt)
        assertEquals("B", db.planDao().plan(plan)!!.suggestedDayId)
        val mainTrack = before.tracks.first { it.programExerciseId == main }
        assertEquals(CarryForward.next(mainTrack.planned, mainTrack.sets), SetJson.decodeSets(db.programDao().logForSlot(plan, "A", main, Slot.MAIN)!!.setsJson))
        assertNull(db.programDao().logForSlot(plan, "A", authored, Slot.MAIN))
    }

    /** [F] */
    @Test fun `finish on an archived plan archives and advances its attached day`() = runTest {
        finishArchivedRun(attached = true)
    }

    /** [F] */
    @Test fun `finish on an archived plan archives without advancing a detached day`() = runTest {
        finishArchivedRun(attached = false)
    }

    private suspend fun finishArchivedRun(attached: Boolean) {
        val plan = authored()
        val slot = slot(plan)
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot) ?: -1, "A").first().runId.orEmpty(), "A", slot, Slot.MAIN, work())
        val id = start(plan)
        val beforeRun = repo.openRunFlow.first()!!
        val other = repo.createAuthoredPlan("Other", "Other day")
        repo.publishCreatedAuthoredPlan(other)
        assertTrue(repo.archivePlan(plan))
        assertEquals(beforeRun, repo.openRunFlow.first())
        if (!attached) db.programDao().upsertDay(day(plan).copy(nextRunId = "detached-$id"))
        val beforeDay = day(plan)
        val beforePlan = db.planDao().plan(plan)!!
        val beforeOther = db.planDao().plan(other)
        val beforeLogs = db.programDao().logsForPlan(plan)

        val sessionId = repo.finishRun(id)!!

        val session = db.sessionDao().allSessions().single()
        assertEquals(sessionId, session.id)
        assertEquals(id, session.runId)
        assertEquals(plan, session.planId)
        assertEquals(beforeRun.startedAt, session.startedAt)
        assertEquals(clock.millis(), session.completedAt)
        val track = beforeRun.tracks.single()
        assertEquals(track.planned, SessionPlanJson.decode(session.plannedJson!!).single().sets.map { it.toDomain() })
        val archived = repo.sessionSets(sessionId)
        assertEquals(track.sets.indices.toList(), archived.map { it.setIndex })
        assertEquals(track.sets.map { it.weightLb }, archived.map { it.weightLb })
        assertEquals(track.sets.map { it.reps }, archived.map { it.reps })
        assertEquals(track.sets.map { it.done }, archived.map { it.done })
        assertEquals(track.sets.map { it.plannedIndex }, archived.map { it.plannedIndex })
        assertTrue(archived.all { it.trackPosition == track.position && it.exerciseId == track.exerciseId && it.slot == track.slot })
        assertTrue(db.runDao().allRuns().isEmpty())
        assertTrue(db.runDao().allTracks().isEmpty())
        assertEquals(beforeOther, db.planDao().plan(other))
        assertEquals(other, settings.selectedPlanIdFlow.first())
        if (attached) {
            assertEquals(beforePlan.copy(suggestedDayId = "B"), db.planDao().plan(plan))
            assertNotEquals(id, day(plan).nextRunId)
            assertEquals(clock.millis(), day(plan).closedAt)
            assertTrue(db.programDao().logsForPlan(plan).isEmpty())
        } else {
            assertEquals(beforePlan, db.planDao().plan(plan))
            assertEquals(beforeDay, day(plan))
            assertEquals(beforeLogs, db.programDao().logsForPlan(plan))
        }
    }

    /** [F] */
    @Test fun `finish replay returns one session and advances rotation once`() = runTest {
        val plan = authored()
        val id = start(plan)
        val session = repo.finishRun(id)
        val closed = day(plan)
        assertEquals(session, repo.finishRun(id))
        assertEquals(1, db.sessionDao().allSessions().size)
        assertEquals(closed, day(plan))
        assertEquals("B", db.planDao().plan(plan)!!.suggestedDayId)
        assertNull(repo.finishRun("nope"))
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(id, plan, "A", listOf(tick(slot(plan)))))
    }

    /** [F] */
    @Test fun `discard preserves working values preview edits and plan changes`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.updateSets(repo.dayTracksFlow(repo.planIdOfSlot(slot) ?: -1, "A").first().runId.orEmpty(), "A", slot, Slot.MAIN, work())
        val id = start(plan)
        repo.applyToRun(id, plan, "A", listOf(weight(slot, 80.0)))
        val other = slot(plan, "B")
        repo.applyToRun(day(plan, "B").nextRunId, plan, "B", listOf(weight(other, 60.0)), listOf(RunSeed(other, Slot.MAIN, work())))
        repo.renameDay(plan, "A", "Renamed")
        val logs = db.programDao().allLogs()
        assertTrue(repo.discardRun(id))
        assertFalse(repo.discardRun(id))
        assertTrue(db.sessionDao().allSessions().isEmpty())
        assertEquals(logs, db.programDao().allLogs())
        assertEquals("Renamed", day(plan).title)
        assertNotEquals(id, day(plan).nextRunId)
        assertEquals(clock.millis(), day(plan).closedAt)
        assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(id, plan, "A", listOf(tick(slot))))
    }

    /** [F] */
    @Test fun `removing a day archives only performed runs without advancing rotation`() = runTest {
        for (done in listOf(true, false)) {
            val plan = authored()
            val id = start(plan, "B")
            if (!done) clearRun(id)
            assertTrue(repo.removeDay(plan, "B"))
            assertEquals(if (done) 1 else 0, db.sessionDao().allSessions().count { it.runId == id })
            assertNull(db.runDao().openRun())
            assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
        }
    }

    /** [F] */
    @Test fun `replacing a program archives only performed runs without advancing rotation`() = runTest {
        for (done in listOf(true, false)) {
            val plan = authored()
            val id = start(plan)
            if (!done) clearRun(id)
            repo.replaceProgram(Program(emptyList()))
            assertEquals(if (done) 1 else 0, db.sessionDao().allSessions().count { it.runId == id })
            assertNull(db.runDao().openRun())
            assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
        }
    }

    private suspend fun clearRun(runId: String) {
        db.runDao().tracks(runId).forEach { db.runDao().updateTrack(it.copy(setsJson = SetJson.encodeSets(work()))) }
    }

    /** [F] */
    @Test fun `wizard new plan save preserves another plans open run`() = runTest {
        val source = authored()
        start(source)
        val before = repo.openRunFlow.first()
        val created = repo.createGeneratedPlan("New generated", WizardAnswers(), generated())
        repo.publishCreatedPlan(created, WizardAnswers())
        assertEquals(before, repo.openRunFlow.first())
        assertNotEquals(source, created)
    }

    /** [F] */
    @Test fun `reusing a day letter mints a new identity and rejects the old preview`() = runTest {
        val plan = authored()
        val old = day(plan, "B").nextRunId
        assertTrue(repo.removeDay(plan, "B"))
        assertEquals("B", repo.addDay(plan, "New B"))
        repo.addExercise("B", ProgramExercise("bb_bench"))
        assertNotEquals(old, day(plan, "B").nextRunId)
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(old, plan, "B", listOf(tick(slot(plan, "B")))))
    }

    /** [F] */
    @Test fun `duplicate days mint fresh distinct identities and clear closed time`() = runTest {
        val plan = authored()
        db.programDao().closeDay(plan, "A", day(plan).nextRunId, 1000)
        val source = db.programDao().daysForPlan(plan)
        val copy = repo.duplicatePlan(plan, "Copy")
        val copied = db.programDao().daysForPlan(copy)
        assertTrue(copied.all { day -> day.nextRunId.isNotBlank() && source.none { it.nextRunId == day.nextRunId } })
        assertEquals(4, (source + copied).map { it.nextRunId }.toSet().size)
        assertTrue(copied.all { it.closedAt == null })
    }

    /** [F] */
    @Test fun `resetting a generated day keeps identity and closed time`() = runTest {
        val answers = WizardAnswers()
        val plan = repo.createGeneratedPlan("Generated", answers, generated())
        repo.publishCreatedPlan(plan, answers)
        db.programDao().closeDay(plan, "A", day(plan).nextRunId, 1000)
        val before = day(plan)
        repo.setKeepToPlan(slot(plan), true)
        repo.resetDayToTemplate("A")
        assertTrue(db.programDao().exercisesForDay(plan, "A").none { it.keepToPlan })
        assertEquals(before.nextRunId, day(plan).nextRunId)
        assertEquals(before.closedAt, day(plan).closedAt)
    }

    /** [F] */
    @Test fun `retired tracks archive only done rows and retain the complete prescription`() = runTest {
        val plan = authored()
        val id = start(plan)
        val track = db.runDao().tracks(id).single()
        db.runDao().updateTrack(track.copy(retired = true))
        val session = repo.finishRun(id)!!
        assertEquals(listOf(0), repo.sessionSets(session).map { it.setIndex })
        val planned = SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single()
        assertTrue(planned.retired)
        assertEquals(3, planned.sets.size)
    }

    /** [F] */
    @Test fun `run survives closing and reopening a file database`() = runTest {
        db.close()
        db = Room.databaseBuilder(context, StrengthDatabase::class.java, databaseName).allowMainThreadQueries().build()
        repo = repository()
        val plan = authored()
        start(plan)
        val before = repo.openRunFlow.first()
        db.close()
        db = Room.databaseBuilder(context, StrengthDatabase::class.java, databaseName).allowMainThreadQueries().build()
        repo = repository()
        assertEquals(before, repo.openRunFlow.first())
    }

    /** [F] */
    @Test fun `snapshot restores runs tracks and mints only blank day identities`() = runTest {
        val plan = authored()
        start(plan)
        val before = repo.exportSnapshot()
        repo.importSnapshot(before, journal)
        val restored = repo.exportSnapshot()
        assertEquals(before.runs, restored.runs)
        assertEquals(before.runTracks, restored.runTracks)
        assertEquals(before.days, restored.days)
        val legacyDay = before.days.last().copy(nextRunId = "", closedAt = 1000)
        repo.importSnapshot(before.copy(days = before.days.dropLast(1) + legacyDay), journal)
        assertTrue(day(plan, "B").nextRunId.isNotBlank())
        assertNull(day(plan, "B").closedAt)
        assertEquals(before.days.first(), day(plan))
    }

    /** [F] */
    @Test fun `declined or rejected batch leaves working values and run creation unwritten`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        val id = day(plan).nextRunId
        val seeds = listOf(RunSeed(slot, Slot.MAIN, work()))
        val decline = TrackEdit(slot, Slot.MAIN) { null }
        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(id, plan, "A", listOf(tick(slot), decline), seeds))
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(id, plan, "A", listOf(tick(slot), tick(-1)), seeds))
        assertTrue(db.programDao().allLogs().isEmpty())
        assertTrue(db.runDao().allRuns().isEmpty())
        assertTrue(db.runDao().allTracks().isEmpty())
        start(plan)
        val before = db.runDao().allTracks()
        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(id, plan, "A", listOf(weight(slot, 100.0), decline)))
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(id, plan, "A", listOf(weight(slot, 100.0), tick(-1))))
        assertEquals(before, db.runDao().allTracks())
    }

    /** [F] */
    @Test fun `missing tracks seed once and explicit exercise edits resolve retired tracks`() = runTest {
        val plan = authored()
        val id = start(plan)
        repo.addExercise("A", ProgramExercise("ez_curl"))
        val added = db.programDao().exercisesForDay(plan, "A").last().id
        val seeds = listOf(RunSeed(added, Slot.MAIN, work()))
        repo.seedRunTracksIfMissing("nope", seeds)
        assertEquals(1, db.runDao().tracks(id).size)
        repo.seedRunTracksIfMissing(id, seeds)
        repo.seedRunTracksIfMissing(id, seeds)
        assertEquals(2, db.runDao().tracks(id).size)
        val old = db.runDao().tracks(id).first()
        db.runDao().updateTrack(old.copy(retired = true))
        val edit = TrackEdit(old.programExerciseId!!, old.slot, old.exerciseId) { SetEditor.editReps(it, 0, 12) }
        assertEquals(RunWriteResult.InRun(id, false), repo.applyToRun(id, plan, "A", listOf(edit)))
        assertEquals(12, SetJson.decodeSets(db.runDao().tracks(id).first().setsJson).first().reps)
    }

    /** [F] */
    @Test fun `seeded edit tracks commit only after every transform accepts`() = runTest {
        val plan = authored()
        val id = start(plan)
        repo.addExercise("A", ProgramExercise("ez_curl"))
        val added = db.programDao().exercisesForDay(plan, "A").last().id
        val seeds = listOf(RunSeed(added, Slot.MAIN, work()))
        val before = db.runDao().allTracks()
        val decline = TrackEdit(slot(plan), Slot.MAIN) { null }
        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(id, plan, "A", listOf(tick(added), decline), seeds))
        assertEquals(before, db.runDao().allTracks())
        assertEquals(RunWriteResult.InRun(id, false), repo.applyToRun(id, plan, "A", listOf(tick(added)), seeds))
        val inserted = repo.openRunFlow.first()!!.tracks.last()
        assertEquals(added, inserted.programExerciseId)
        assertEquals(work(), inserted.planned)
        assertEquals(listOf(0, 1, 2), inserted.sets.map { it.plannedIndex })
        assertTrue(inserted.sets.first().done)
    }

    /** [F] */
    @Test fun `changed or removed slots do not receive carried forward rows`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.plansFlow.first().single().id
        val slots = db.programDao().exercisesForDay(plan, "A")
        val id = day(plan).nextRunId
        val seeds = slots.map { RunSeed(it.id, Slot.MAIN, work()) }
        repo.applyToRun(id, plan, "A", listOf(tick(slots.first().id)), seeds)
        repo.swapExerciseById("A", slots.first().id, "bb_bench")
        db.programDao().deleteExercise(slots.last().id)
        repo.finishRun(id)
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
    }

    @Test fun `kept slot without a prescription clears working values and next read seeds goal math`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.selectedPlanIdFlow.first()!!
        val slot = slot(plan)
        repo.setKeepToPlan(slot, true)
        assertNull(db.programDao().exercise(slot)!!.prescriptionJson)
        val seed = squat()
        repo.seedIfEmpty("A", slot, Slot.MAIN, seed)
        val runId = day(plan).nextRunId
        repo.applyToRun(runId, plan, "A", listOf(tick(slot), weight(slot, 300.0)))

        repo.finishRun(runId)

        assertNull(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN))
        assertTrue(repo.seedIfEmpty("A", slot, Slot.MAIN, seed))
        assertEquals(seed, repo.dayTracksFlow(plan, "A").first().tracks.single().sets)
    }

    @Test fun `remembered prescribed slot carries forward the run track`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setKeepToPlan(slot, false)
        repo.seedIfEmpty("A", slot, Slot.MAIN, work())
        val runId = start(plan)
        repo.applyToRun(runId, plan, "A", listOf(weight(slot, 75.0)))
        val track = repo.openRunFlow.first()!!.tracks.single()

        repo.finishRun(runId)

        assertEquals(CarryForward.next(track.planned, track.sets),
            SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson))
    }

    @Test fun `remembered prescription carries forward until a mid run shape change clears both tracks`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.selectedPlanIdFlow.first()!!
        val storedPlan = db.planDao().plan(plan)!!
        db.planDao().updatePlan(storedPlan.copy(origin = "AUTHORED", recipeJson = null))
        val slot = slot(plan)
        val reps = Prescription(PrescribedTarget.Reps(12))
        repo.setPrescription(slot, reps, 3)
        val seed = SetSeeder.seedPrescribed(3, reps)
        repo.seedIfEmpty("A", slot, Slot.MAIN, seed)
        repo.seedIfEmpty("A", slot, Slot.SS, work())
        var runId = day(plan).nextRunId
        repo.applyToRun(runId, plan, "A", listOf(tick(slot)))
        val firstTrack = repo.openRunFlow.first()!!.tracks.first { it.slot == Slot.MAIN }
        repo.finishRun(runId)
        assertEquals(CarryForward.next(firstTrack.planned, firstTrack.sets),
            SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson))

        runId = day(plan).nextRunId
        repo.applyToRun(runId, plan, "A", listOf(tick(slot)))
        val performed = repo.openRunFlow.first()!!.tracks
        repo.setPrescription(slot, Prescription(PrescribedTarget.Hold(45)), 3)
        assertEquals(performed, repo.openRunFlow.first()!!.tracks)

        val session = repo.finishRun(runId)!!

        assertNull(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN))
        assertNull(db.programDao().logForSlot(plan, "A", slot, Slot.SS))
        performed.forEach { track ->
            val archived = repo.sessionSets(session).filter { it.slot == track.slot }
            assertEquals(track.sets.map { it.reps }, archived.map { it.reps })
            assertEquals(track.sets.map { it.done }, archived.map { it.done })
            assertEquals(track.sets.map { it.seconds }, archived.map { it.seconds })
        }
    }

    @Test fun `switching to plan mid run then discarding leaves goal math as the next seed`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.selectedPlanIdFlow.first()!!
        val slot = slot(plan)
        repo.seedIfEmpty("A", slot, Slot.MAIN, squat())
        repo.seedIfEmpty("A", slot, Slot.SS, work())
        val runId = day(plan).nextRunId
        repo.applyToRun(runId, plan, "A", listOf(tick(slot)))
        val captured = db.runDao().tracks(runId)

        repo.setKeepToPlan(slot, true)

        assertEquals(captured, db.runDao().tracks(runId))
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
        assertTrue(repo.discardRun(runId))
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
        assertTrue(repo.seedIfEmpty("A", slot, Slot.MAIN, squat()))
        assertEquals(squat(), repo.dayTracksFlow(plan, "A").first().tracks.single().sets)
    }

    @Test fun `reset to plan clears both tracks and refuses kept slots and the open day`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.selectedPlanIdFlow.first()!!
        val slot = slot(plan)
        suspend fun seedPair() {
            repo.seedIfEmpty("A", slot, Slot.MAIN, squat())
            repo.seedIfEmpty("A", slot, Slot.SS, work())
        }
        seedPair()
        assertEquals(2, db.programDao().logsForDay(plan, "A").size)
        assertTrue(repo.resetSlotToPlan(slot))
        assertTrue(db.programDao().logsForDay(plan, "A").isEmpty())
        seedPair()
        val runId = day(plan).nextRunId
        repo.applyToRun(runId, plan, "A", listOf(tick(slot)))
        val logs = db.programDao().allLogs()
        val tracks = db.runDao().tracks(runId)
        assertFalse(repo.resetSlotToPlan(slot))
        assertEquals(logs, db.programDao().allLogs())
        assertEquals(tracks, db.runDao().tracks(runId))
        val other = slot(plan, "B")
        repo.seedIfEmpty("B", other, Slot.MAIN, work())
        assertTrue(repo.resetSlotToPlan(other))
        repo.discardRun(runId)
        repo.setKeepToPlan(slot, true)
        seedPair()
        val keptLogs = db.programDao().allLogs()
        assertFalse(repo.resetSlotToPlan(slot))
        assertEquals(keptLogs, db.programDao().allLogs())
    }

    @Test fun `empty planned main track never triggers the prescription shape check`() = runTest {
        val plan = authored()
        val slot = slot(plan)
        repo.setKeepToPlan(slot, false)
        val runId = start(plan)
        val main = db.runDao().tracks(runId).single()
        db.runDao().updateTrack(main.copy(plannedJson = "[]"))
        repo.finishRun(runId)
        assertEquals(CarryForward.next(emptyList(), SetJson.decodeSets(main.setsJson)),
            SetJson.decodeSets(db.programDao().logForSlot(plan, "A", slot, Slot.MAIN)!!.setsJson))
    }

    @Test fun `swapping by id keeps the slots next time choice`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.selectedPlanIdFlow.first()!!
        val slot = slot(plan)
        repo.setKeepToPlan(slot, true)
        repo.swapExerciseById("A", slot, "hack_squat")
        assertTrue(db.programDao().exercise(slot)!!.keepToPlan)
        assertTrue(repo.swapExerciseById("A", slot, "bb_back_squat"))
        assertTrue(repo.daySlotsFlow("A").first().first().exercise.keepToPlan)
        repo.setKeepToPlan(slot, false)
        repo.swapExerciseById("A", slot, "hack_squat")
        assertFalse(repo.daySlotsFlow("A").first().first().exercise.keepToPlan)
    }

    @Test fun `explicit Start captures every slot using working values before seeds and Discard closes it`() = runTest {
        repo.replaceProgram(generated())
        val plan = repo.selectedPlanIdFlow.first()!!
        val slots = db.programDao().exercisesForDay(plan, "A")
        val main = slots.first().id
        val remembered = squat().map { it.copy(weightLb = 123.0) }
        repo.seedIfEmpty("A", main, Slot.MAIN, remembered)
        val seeds = listOf(RunSeed(main, Slot.MAIN, squat()), RunSeed(main, Slot.SS, work()),
            RunSeed(slots[1].id, Slot.MAIN, work()))
        val id = day(plan).nextRunId
        assertEquals(RunStartResult.Started(id), repo.startRun(plan, "A", seeds))
        val open = repo.openRunFlow.first()!!
        assertEquals(id, open.id)
        assertEquals(3, open.tracks.size)
        assertTrue(open.tracks.all { track -> track.sets.none { it.done } })
        assertEquals(remembered, open.tracks.first().planned)
        assertEquals(work(), open.tracks.first { it.slot == Slot.SS }.planned)
        assertEquals(RunStartResult.AlreadyOpen(id), repo.startRun(plan, "A", emptyList()))
        assertEquals(open, repo.openRunFlow.first())
        assertTrue(repo.discardRun(id))
        assertNull(repo.openRunFlow.first())
        assertNotEquals(id, day(plan).nextRunId)
    }

    @Test fun `explicit Start refuses another open run and returns its identity`() = runTest {
        val plan = authored()
        val id = start(plan)
        val before = repo.openRunFlow.first()
        assertEquals(RunStartResult.OtherRunOpen(id, plan, "A"), repo.startRun(plan, "B", emptyList()))
        assertEquals(before, repo.openRunFlow.first())
    }

    @Test fun `explicit Start refuses a day with no strength slots`() = runTest {
        val plan = repo.createAuthoredPlan("Cardio", "Running")
        repo.setCardioBlock(plan, "A", cloud.trotter.log.strength.domain.generator.CardioBlocks.fromLegacy(
            cloud.trotter.log.strength.domain.model.CardioSuggestion("Easy", "", false),
            false, cloud.trotter.log.strength.domain.model.CardioActivity.ROAD_RUN,
        ))
        assertEquals(RunStartResult.NoStrengthSlots, repo.startRun(plan, "A", emptyList()))
        assertNull(repo.openRunFlow.first())
    }

    @Test fun `same exercise plan swap without an open run preserves working values`() = runTest {
        val plan = authored()
        val id = slot(plan)
        repo.seedIfEmpty("A", id, Slot.MAIN, work())
        val before = db.programDao().logsForDay(plan, "A")
        assertTrue(repo.swapExerciseById("A", id, "bb_back_squat"))
        assertEquals(before, db.programDao().logsForDay(plan, "A"))
        assertNull(repo.openRunFlow.first())
    }

    @Test fun `explicit Start refuses a removed day without opening a run`() = runTest {
        val plan = authored()
        assertTrue(repo.removeDay(plan, "B"))
        assertEquals(RunStartResult.NoStrengthSlots, repo.startRun(plan, "B", emptyList()))
        assertNull(repo.openRunFlow.first())
    }
}
