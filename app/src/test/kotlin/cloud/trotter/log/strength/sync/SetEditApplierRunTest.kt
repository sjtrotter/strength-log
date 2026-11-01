package cloud.trotter.log.strength.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.data.RunSeed
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetEditApplierRunTest {
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var applier: SetEditApplier
    private lateinit var syncStore: WearSyncStore

    private class RecordingMarkers(private val beforeRead: suspend () -> Unit = {}) : AppliedEditMarkers {
        private val stamps = mutableMapOf<String, Long>()
        override suspend fun lastApplied(rowKey: String): Long {
            beforeRead()
            return stamps[rowKey] ?: 0L
        }
        override suspend fun markApplied(rowKey: String, editedAtMillis: Long) { stamps[rowKey] = editedAtMillis }
        override fun lastAppliedFlow(rowKey: String) = flowOf(stamps[rowKey] ?: 0L)
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("applier-run-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db, programDao = db.programDao(), sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(), settings = SettingsStore(dataStore),
        )
        syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("applier-edit-acks", ".preferences_pb")
        })
        applier = SetEditApplier(repo, RecordingMarkers(), syncStore)
    }

    @After
    fun tearDown() {
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private suspend fun authored(): Long {
        val planId = repo.createAuthoredPlan("Repeatable", "Day A")
        repo.publishCreatedAuthoredPlan(planId)
        repo.addExercise("A", ProgramExercise(
            "bb_back_squat", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(100.0, 5)),
        ))
        repo.addExercise("A", ProgramExercise(
            "ez_curl", targetSets = 2, prescription = Prescription(PrescribedTarget.LoadReps(50.0, 10)),
            superset = SupersetPartner("rope_pushdown"),
        ))
        for (slot in repo.slotsFor(planId, "A").first()) {
            val squat = slot.exercise.exerciseId == "bb_back_squat"
            repo.setPrescription(slot.programExerciseId,
                Prescription(PrescribedTarget.LoadReps(if (squat) 100.0 else 50.0, if (squat) 5 else 10)),
                sets = if (squat) 3 else 2)
            repo.setKeepToPlan(slot.programExerciseId, true)
        }
        return planId
    }

    private suspend fun squatId(planId: Long): Long = repo.slotsFor(planId, "A").first()
        .first { it.exercise.exerciseId == "bb_back_squat" }.programExerciseId

    private suspend fun seeds(planId: Long): List<RunSeed> = DayScreenBuilder.seedPlan(
        repo.slotsFor(planId, "A").first(), emptyMap(),
        repo.wizardAnswersFor(planId).first().config, repo.catalogFlow.first(),
    ).map { RunSeed(it.programExerciseId, it.slot, it.sets) }

    private suspend fun phoneStart(planId: Long): String {
        val seeds = seeds(planId)
        for (seed in seeds) repo.seedIfEmpty("A", seed.programExerciseId, seed.slot, seed.sets)
        val runId = repo.dayTracksFlow(planId, "A").first().runId!!
        val id = squatId(planId)
        val main = seeds.first { it.programExerciseId == id && it.slot == Slot.MAIN }
        repo.updateSets(runId, "A", main.programExerciseId, Slot.MAIN,
            main.sets.mapIndexed { index, set -> if (index == 0) set.copy(done = true) else set })
        assertEquals(runId, repo.openRunFlow.first()!!.id)
        return runId
    }

    private suspend fun delta(planId: Long, runId: String, index: Int = 0, stamp: Long = 100): SetEditDelta = SetEditDelta(
        dayId = "A", programExerciseId = squatId(planId), slot = Slot.MAIN, setIndex = index,
        done = true, editedAtMillis = stamp, planId = planId, runId = runId, exerciseId = "bb_back_squat",
    )

    /** [F] */
    @Test
    fun `a done delta for a finished run is rejected without another session or run`() = runTest {
        val planId = authored()
        val runId = phoneStart(planId)
        val edit = delta(planId, runId)
        assertNotNull(repo.finishRun(runId))
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(edit))
        assertEquals(1, repo.sessionsFlow.first().size)
        assertNull(repo.openRunFlow.first())
    }

    /** [F] */
    @Test
    fun `a repeatable day's next run starts while its previous run is rejected`() = runTest {
        val planId = authored()
        val first = phoneStart(planId)
        val oldEdit = delta(planId, first, index = 1)
        repo.finishRun(first)
        val next = db.programDao().day(planId, "A")!!.nextRunId
        assertNotEquals(first, next)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(delta(planId, next)))
        assertEquals(next, repo.openRunFlow.first()!!.id)
        val before = repo.openRunFlow.first()
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(oldEdit))
        assertEquals(before, repo.openRunFlow.first())
    }

    /** [F] */
    @Test
    fun `a preview done delta seeds every slot and starts the next run`() = runTest {
        val planId = authored()
        assertTrue(repo.dayTracksFlow(planId, "A").first().tracks.isEmpty())
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val curl = repo.slotsFor(planId, "A").first().first { it.exercise.exerciseId == "ez_curl" }.programExerciseId
        val edit = delta(planId, runId).copy(programExerciseId = curl, exerciseId = "ez_curl")
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
        val open = repo.openRunFlow.first()!!
        assertEquals(runId, open.id)
        assertEquals(seeds(planId).map { it.programExerciseId to it.slot }.toSet(),
            open.tracks.map { it.programExerciseId to it.slot }.toSet())
        assertTrue(open.tracks.all { it.sets.isNotEmpty() })
        assertTrue(open.tracks.filter { it.programExerciseId == curl }.all { it.sets.first().done })
    }

    @Test
    fun `queued run ticks keep confirmed timing and unticks clear both tracks`() = runTest {
        for (doneSlot in listOf(Slot.MAIN, Slot.SS)) {
            val planId = authored()
            val runId = phoneStart(planId)
            val id = repo.slotsFor(planId, "A").first()
                .first { it.exercise.exerciseId == "ez_curl" }.programExerciseId
            val initial = repo.dayTracksFlow(planId, "A").first().tracks
                .first { it.programExerciseId == id && it.slot == doneSlot }.sets
            repo.updateSets(runId, "A", id, doneSlot, initial.mapIndexed { index, set ->
                if (index == 0) set.copy(done = true, startedAtMillis = 10L, completedAtMillis = 70L) else set
            })
            val edit = delta(planId, runId).copy(programExerciseId = id, exerciseId = "ez_curl",
                startedAtMillis = 100L, completedAtMillis = 160L)
            assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
            val pair = repo.dayTracksFlow(planId, "A").first().tracks.filter { it.programExerciseId == id }
            assertTrue(pair.all { it.sets.first().done })
            for (track in pair) {
                val row = track.sets.first()
                assertEquals(if (track.slot == doneSlot) 10L else 100L, row.startedAtMillis)
                assertEquals(if (track.slot == doneSlot) 70L else 160L, row.completedAtMillis)
                assertEquals(if (track.slot == Slot.MAIN) 100L else null, row.watchEditedAt)
            }
            assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit.copy(done = false, editedAtMillis = 200)))
            val cleared = repo.dayTracksFlow(planId, "A").first().tracks.filter { it.programExerciseId == id }
            assertTrue(cleared.all { !it.sets.first().done })
            assertTrue(cleared.all { it.sets.first().startedAtMillis == null && it.sets.first().completedAtMillis == null })
            repo.discardRun(runId)
        }
    }

    /** [F] */
    @Test
    fun `a preview weight edit changes working values without starting a run`() = runTest {
        val planId = authored()
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val edit = delta(planId, runId).copy(done = null, weightLb = 125.0)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
        assertEquals(125.0, repo.dayTracksFlow(planId, "A").first().tracks.single().sets.first().weightLb, 0.0)
        assertNull(repo.openRunFlow.first())
    }

    /** [F] */
    @Test
    fun `exercise identity routes a late tick to the retired squat track`() = runTest {
        val planId = authored()
        val runId = phoneStart(planId)
        val edit = delta(planId, runId, index = 2)
        repo.swapExerciseById("A", edit.programExerciseId, "hack_squat")
        repo.seedRunTracksIfMissing(runId, seeds(planId))
        val before = repo.openRunFlow.first()!!.tracks.first { it.exerciseId == "hack_squat" }
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
        val tracks = repo.openRunFlow.first()!!.tracks
        val retired = tracks.first { it.exerciseId == "bb_back_squat" }
        assertTrue(retired.retired)
        assertTrue(retired.sets[2].done)
        assertEquals(100L, retired.sets[2].watchEditedAt)
        assertEquals(before, tracks.first { it.exerciseId == "hack_squat" })
    }

    /** [F] */
    @Test
    fun `a preview tick naming an exercise replaced by a swap is rejected`() = runTest {
        val planId = authored()
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val edit = delta(planId, runId)
        repo.swapExerciseById("A", edit.programExerciseId, "hack_squat")
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(edit))
        assertNull(repo.openRunFlow.first())
        assertTrue(repo.dayTracksFlow(planId, "A").first().tracks.isEmpty())
    }

    /** [F] */
    @Test
    fun `a swap for a closed run is rejected before changing its slot`() = runTest {
        val planId = authored()
        val runId = phoneStart(planId)
        val id = squatId(planId)
        repo.finishRun(runId)
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = id, exerciseId = "hack_squat", exerciseName = "Hack Squat",
            editedAtMillis = 100, planId = planId, runId = runId,
        )))
        assertEquals("bb_back_squat", repo.slotsFor(planId, "A").first().first { it.programExerciseId == id }.exercise.exerciseId)
        assertFalse(repo.dayTracksFlow(planId, "A").first().runOpen)
    }

    /** [F] */
    @Test
    fun `Finish during swap validation rejects the old run without changing the slot`() = runTest {
        val planId = authored()
        val runId = phoneStart(planId)
        val id = squatId(planId)
        applier = SetEditApplier(repo, RecordingMarkers { repo.finishRun(runId) }, syncStore)
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = id, exerciseId = "hack_squat", exerciseName = "Hack Squat",
            editedAtMillis = 100, planId = planId, runId = runId,
        )))
        assertEquals("bb_back_squat", repo.slotsFor(planId, "A").first()
            .first { it.programExerciseId == id }.exercise.exerciseId)
        assertTrue(repo.dayTracksFlow(planId, "A").first().tracks.isEmpty())
        assertNull(repo.openRunFlow.first())
        assertEquals(1, repo.sessionsFlow.first().size)
    }

    /** [F] */
    @Test
    fun `out of order preview edits retain the newer value and stamp`() = runTest {
        val planId = authored()
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val newer = delta(planId, runId, stamp = 200).copy(done = null, weightLb = 125.0)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(newer))
        val before = repo.dayTracksFlow(planId, "A").first()
        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(newer.copy(editedAtMillis = 100, weightLb = 110.0)))
        assertEquals(before, repo.dayTracksFlow(planId, "A").first())
        assertEquals(125.0, before.tracks.single().sets.first().weightLb, 0.0)
        assertEquals(200L, before.tracks.single().sets.first().watchEditedAt)
        assertNull(repo.openRunFlow.first())
    }

    /** [F] */
    @Test
    fun `starting a run retains preview stamps so preview replays stay stale`() = runTest {
        val planId = authored()
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val preview = delta(planId, runId, stamp = 200).copy(done = null, weightLb = 125.0)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(preview))
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(delta(planId, runId, index = 1, stamp = 300)))
        val before = repo.openRunFlow.first()!!
        assertEquals(200L, before.tracks.first { it.programExerciseId == preview.programExerciseId }
            .sets.first().watchEditedAt)
        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(preview))
        assertEquals(before, repo.openRunFlow.first())
    }

    /** [F] */
    @Test
    fun `Discard clears preview stamps before the next run's working edits`() = runTest {
        val planId = authored()
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val preview = delta(planId, runId, stamp = 200).copy(done = null, weightLb = 125.0)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(preview))
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(delta(planId, runId, index = 1, stamp = 300)))
        assertTrue(repo.discardRun(runId))
        val next = db.programDao().day(planId, "A")!!.nextRunId
        assertNotEquals(runId, next)
        assertNull(repo.dayTracksFlow(planId, "A").first().tracks.single().sets.first().watchEditedAt)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(preview.copy(runId = next, editedAtMillis = 100, weightLb = 110.0)))
        assertEquals(110.0, repo.dayTracksFlow(planId, "A").first().tracks.single().sets.first().weightLb, 0.0)
        assertNull(repo.openRunFlow.first())
    }

    /** [F] */
    @Test
    fun `a replayed run delta is stale`() = runTest {
        val planId = authored()
        val runId = db.programDao().day(planId, "A")!!.nextRunId
        val edit = delta(planId, runId)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
        val before = repo.openRunFlow.first()
        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(edit))
        assertEquals(before, repo.openRunFlow.first())
    }

    /** [F] */
    @Test
    fun `an unknown run cannot edit working values or start a run`() = runTest {
        val planId = authored()
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(delta(planId, "unknown")))
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(delta(planId, "unknown").copy(done = null, weightLb = 125.0)))
        assertNull(repo.openRunFlow.first())
        assertTrue(repo.dayTracksFlow(planId, "A").first().tracks.isEmpty())
    }

    @Test fun `run identified swap opens the next run without changing the plan and swapping back restores its rows`() = runTest {
        val plan = authored()
        val id = squatId(plan)
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        val beforeSlot = db.programDao().exercise(id)
        val swap = ExerciseSwapDelta(dayId = "A", programExerciseId = id, exerciseId = "hack_squat",
            exerciseName = "Hack Squat", editedAtMillis = 100, planId = plan, runId = runId)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(swap))
        assertEquals(beforeSlot, db.programDao().exercise(id))
        val open = repo.openRunFlow.first()!!
        assertEquals(runId, open.id)
        assertTrue(open.tracks.all { track -> track.sets.none { it.done } })
        val original = open.tracks.single { it.exerciseId == "bb_back_squat" }
        assertTrue(original.retired)
        val active = open.tracks.single { it.programExerciseId == id && !it.retired }
        assertEquals("hack_squat", active.exerciseId)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(swap))
        assertEquals(open, repo.openRunFlow.first())
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(swap.copy(
            exerciseId = "bb_back_squat", exerciseName = "Barbell Back Squat", editedAtMillis = 200,
        )))
        assertEquals(original.copy(retired = false), repo.openRunFlow.first()!!.tracks.single {
            it.programExerciseId == id && !it.retired
        })
        assertEquals(beforeSlot, db.programDao().exercise(id))
    }

    @Test fun `run identified swap refuses another open workout without changing anything`() = runTest {
        val plan = authored()
        val other = repo.duplicatePlan(plan, "Other")
        val openId = phoneStart(other)
        val beforeRun = repo.openRunFlow.first()
        val beforeSlot = db.programDao().exercise(squatId(plan))
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = squatId(plan), exerciseId = "hack_squat", exerciseName = "Hack Squat",
            editedAtMillis = 100, planId = plan, runId = db.programDao().day(plan, "A")!!.nextRunId,
        )))
        assertEquals(openId, repo.openRunFlow.first()!!.id)
        assertEquals(beforeRun, repo.openRunFlow.first())
        assertEquals(beforeSlot, db.programDao().exercise(squatId(plan)))
    }

    @Test fun `value guards follow the performed substitute instead of the plans rep window`() = runTest {
        val plan = authored()
        val id = squatId(plan)
        repo.setPrescription(id, Prescription(PrescribedTarget.RepsInTime(90, 30)))
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = id, exerciseId = "hack_squat", exerciseName = "Hack Squat",
            editedAtMillis = 100, planId = plan, runId = runId,
        )))
        val edit = delta(plan, runId, stamp = 200).copy(exerciseId = "hack_squat", done = null, weightLb = 77.0, reps = 9, seconds = 15)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
        val row = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && !it.retired }.sets.first()
        assertEquals(77.0, row.weightLb, 0.0)
        assertEquals(9, row.reps)
        assertEquals(0, row.seconds)
    }

    @Test fun `closing the next run during swap validation cannot start its successor`() = runTest {
        val plan = authored()
        val runId = db.programDao().day(plan, "A")!!.nextRunId
        val id = squatId(plan)
        applier = SetEditApplier(repo, RecordingMarkers {
            phoneStart(plan)
            repo.finishRun(runId)
        }, syncStore)
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = id, exerciseId = "hack_squat", exerciseName = "Hack Squat",
            editedAtMillis = 100, planId = plan, runId = runId,
        )))
        assertNull(repo.openRunFlow.first())
        assertNotEquals(runId, db.programDao().day(plan, "A")!!.nextRunId)
        assertEquals("bb_back_squat", db.programDao().exercise(id)!!.exerciseId)
    }
    @Test fun `a duplicate next run swap is applied without opening a run or changing working values`() = runTest {
        val plan = authored()
        val id = squatId(plan)
        val seed = seeds(plan).first { it.programExerciseId == id }
        repo.seedIfEmpty("A", id, Slot.MAIN, seed.sets)
        val before = db.programDao().logsForDay(plan, "A")
        val next = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = id, exerciseId = "bb_back_squat", exerciseName = "Squat",
            editedAtMillis = 100, planId = plan, runId = next,
        )))
        assertNull(repo.openRunFlow.first())
        assertEquals(before, db.programDao().logsForDay(plan, "A"))
        assertEquals(next, db.programDao().day(plan, "A")!!.nextRunId)
    }

    @Test fun `a stale duplicate checks the performed identity after a phone swap during validation`() = runTest {
        val plan = authored()
        val id = squatId(plan)
        val runId = phoneStart(plan)
        val seed = DayScreenBuilder.swapSeed(repo.slotsFor(plan, "A").first().first(), Slot.MAIN,
            "hack_squat", emptyMap(), repo.wizardAnswersFor(plan).first().config, repo.catalogFlow.first())!!
        var afterPhoneSwap: OpenRun? = null
        val markers = RecordingMarkers {
            assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", RunSeed(id, Slot.MAIN, seed.sets)))
            afterPhoneSwap = repo.openRunFlow.first()
        }
        markers.markApplied("A|$id|swap", 200)
        applier = SetEditApplier(repo, markers, syncStore)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = id, exerciseId = "hack_squat", exerciseName = "Hack Squat",
            editedAtMillis = 100, planId = plan, runId = runId,
        )))
        assertEquals(afterPhoneSwap, repo.openRunFlow.first())
    }

    @Test fun `an old watch swap back reopens the plan exercise without clearing working values`() = runTest {
        val plan = authored()
        val id = squatId(plan)
        val runId = phoneStart(plan)
        val original = repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id }
        val logs = db.programDao().logsForDay(plan, "A")
        val swap = ExerciseSwapDelta(dayId = "A", programExerciseId = id, exerciseId = "hack_squat",
            exerciseName = "Hack Squat", editedAtMillis = 100, planId = plan, runId = runId)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(swap))
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(swap.copy(
            exerciseId = "bb_back_squat", exerciseName = "Squat", editedAtMillis = 200, runId = null,
        )))
        assertEquals(original, repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && !it.retired })
        assertEquals(logs, db.programDao().logsForDay(plan, "A"))
        assertEquals("bb_back_squat", db.programDao().exercise(id)!!.exerciseId)
    }

    @Test fun `watch Start seeds a missing SS to its MAIN remembered length`() = runTest {
        val plan = authored()
        val curl = repo.slotsFor(plan, "A").first().first { it.exercise.exerciseId == "ez_curl" }
        val remembered = List(5) { LoggedSet(60.0, 8, SetKind.WORK) }
        repo.seedIfEmpty("A", curl.programExerciseId, Slot.MAIN, remembered)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = squatId(plan), exerciseId = "hack_squat", exerciseName = "Hack Squat",
            editedAtMillis = 100, planId = plan, runId = db.programDao().day(plan, "A")!!.nextRunId,
        )))
        val tracks = repo.openRunFlow.first()!!.tracks.filter { it.programExerciseId == curl.programExerciseId }
        assertEquals(remembered, tracks.single { it.slot == Slot.MAIN }.planned)
        assertEquals(5, tracks.single { it.slot == Slot.SS }.sets.size)
    }

    @Test fun `a stale duplicate cannot reopen its target after a phone swap changes the performed identity`() = runTest {
        val plan = authored()
        val id = squatId(plan)
        val runId = phoneStart(plan)
        val seed = DayScreenBuilder.swapSeed(repo.slotsFor(plan, "A").first().first(), Slot.MAIN,
            "hack_squat", emptyMap(), repo.wizardAnswersFor(plan).first().config, repo.catalogFlow.first())!!
        var afterPhoneSwap: OpenRun? = null
        val markers = RecordingMarkers {
            assertTrue(repo.swapInRun(runId, id, Slot.MAIN, "hack_squat", RunSeed(id, Slot.MAIN, seed.sets)))
            afterPhoneSwap = repo.openRunFlow.first()
        }
        markers.markApplied("A|$id|swap", 200)
        applier = SetEditApplier(repo, markers, syncStore)
        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(ExerciseSwapDelta(
            dayId = "A", programExerciseId = id, exerciseId = "bb_back_squat", exerciseName = "Squat",
            editedAtMillis = 100, planId = plan, runId = runId,
        )))
        assertEquals(afterPhoneSwap, repo.openRunFlow.first())
        assertEquals("hack_squat", repo.openRunFlow.first()!!.tracks.single { it.programExerciseId == id && !it.retired }.exerciseId)
    }
    @Test fun `SS delta for a dropped partner is rejected and acknowledged without changing tracks`() = runTest {
        val plan = authored()
        val runId = phoneStart(plan)
        val id = repo.slotsFor(plan, "A").first().first { it.exercise.superset != null }.programExerciseId
        repo.dropSupersetHalf(plan, "A", id, Slot.MAIN, "ez_curl", "rope_pushdown")
        val before = db.runDao().tracks(runId)
        val edit = SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.SS, setIndex = 0,
            done = true, editedAtMillis = 900, planId = plan, runId = runId, exerciseId = "rope_pushdown")
        assertEquals(SetEditApplier.Outcome.REJECTED, applier.apply(edit))
        assertEquals(before, db.runDao().tracks(runId))
        assertTrue(900L in syncStore.processedEditsFlow.first())
    }

}
