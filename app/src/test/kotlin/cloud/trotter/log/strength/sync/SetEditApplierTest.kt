package cloud.trotter.log.strength.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
 * The phone-side end of the wire protocol (#20), through a real in-memory
 * repository. The load-bearing rules: a TOP-weight delta cascades *on the phone*
 * (the watch never computes derived sets), a done delta ticks both superset tracks
 * atomically, replayed/stale deltas are dropped, and malformed/foreign deltas
 * never touch the log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetEditApplierTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var markers: RecordingMarkers
    private lateinit var applier: SetEditApplier

    private class RecordingMarkers : AppliedEditMarkers {
        val map = mutableMapOf<String, Long>()
        override suspend fun lastApplied(rowKey: String): Long = map[rowKey] ?: 0L
        override suspend fun markApplied(rowKey: String, editedAtMillis: Long) { map[rowKey] = editedAtMillis }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("applier-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
        markers = RecordingMarkers()
        applier = SetEditApplier(repo, markers, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()))
    }

    @After
    fun tearDown() {
        // Joined, not just cancelled: DataStore releases the file from the scope's
        // completion handler, which a bare cancel() does not wait for.
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    /** Day A: a ramped squat (with a TOP row) and an arms superset. */
    private suspend fun seedProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Test",
                        emphasisLine = "",
                        exercises = listOf(
                            ProgramExercise("bb_back_squat", isMain = true, targetSets = 6),
                            ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown")),
                        ),
                        cardio = null,
                    ),
                ),
            ),
        )
        // Seed logs the way the day VM would (this test targets the applier, not seeding).
        repo.updateSets(
            "A", squatId(), Slot.MAIN,
            listOf(
                LoggedSet(130.0, 5, SetKind.RAMP),
                LoggedSet(165.0, 5, SetKind.RAMP),
                LoggedSet(190.0, 5, SetKind.RAMP),
                LoggedSet(210.0, 3, SetKind.RAMP),
                LoggedSet(235.0, 5, SetKind.TOP),
                LoggedSet(175.0, 8, SetKind.BACKOFF),
            ),
        )
        repo.updateSetsPaired(
            "A", curlId(),
            mainSets = listOf(LoggedSet(60.0, 12, SetKind.WORK), LoggedSet(60.0, 11, SetKind.WORK)),
            ssSets = listOf(LoggedSet(50.0, 15, SetKind.WORK), LoggedSet(50.0, 14, SetKind.WORK)),
        )
    }

    private suspend fun slotId(exerciseId: String): Long =
        repo.daySlotsFlow("A").first().first { it.exercise.exerciseId == exerciseId }.programExerciseId

    private suspend fun squatId() = slotId("bb_back_squat")
    private suspend fun curlId() = slotId("ez_curl")

    private suspend fun track(id: Long, slot: String): List<LoggedSet>? =
        repo.logFlow("A").first().firstOrNull { it.programExerciseId == id && it.slot == slot }?.sets

    @Test
    fun `a TOP-weight delta cascades ramps and back-off on the phone`() = runTest {
        seedProgram()
        val id = squatId()

        val outcome = applier.apply(
            SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 4, weightLb = 245.0, editedAtMillis = 1L),
        )

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        // Pinned §11 cascade: TOP 245 → 135/170/195/220 + B/O 185.
        assertEquals(listOf(135.0, 170.0, 195.0, 220.0, 245.0, 185.0), track(id, Slot.MAIN)!!.map { it.weightLb })
    }

    @Test
    fun `a done delta on the main row ticks both superset tracks atomically`() = runTest {
        seedProgram()
        val id = curlId()

        val outcome = applier.apply(
            SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1, done = true, editedAtMillis = 1L),
        )

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        assertTrue(track(id, Slot.MAIN)!![1].done)
        assertTrue(track(id, Slot.SS)!![1].done)
        assertFalse(track(id, Slot.MAIN)!![0].done)
    }

    @Test
    fun `a stale replayed delta is dropped and changes nothing`() = runTest {
        seedProgram()
        val id = squatId()

        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 6, editedAtMillis = 100L))
        val afterFirst = track(id, Slot.MAIN)!!

        // Same timestamp (a replay) → not newer than last-applied → dropped.
        val replay = applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 99, editedAtMillis = 100L))

        assertEquals(SetEditApplier.Outcome.STALE, replay)
        assertEquals(afterFirst, track(id, Slot.MAIN)!!) // reps still 6, not 99
        assertEquals(6, track(id, Slot.MAIN)!![0].reps)
    }

    @Test
    fun `a newer delta on the same slot applies`() = runTest {
        seedProgram()
        val id = squatId()
        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 6, editedAtMillis = 100L))

        val outcome = applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 7, editedAtMillis = 101L))

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        assertEquals(7, track(id, Slot.MAIN)!![0].reps)
    }

    @Test
    fun `an older row-0 edit still applies after a newer row-1 edit on the same track`() = runTest {
        seedProgram()
        val id = squatId()
        // Row 1 lands first with the newer stamp (e.g. row 0's first send failed)...
        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1, reps = 8, editedAtMillis = 200L))

        // ...then row 0's delayed re-send arrives with an older stamp. Markers are
        // per-ROW, so row 1's newer marker must not STALE-starve it.
        val outcome = applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 7, editedAtMillis = 100L))

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        assertEquals(7, track(id, Slot.MAIN)!![0].reps)
        assertEquals(8, track(id, Slot.MAIN)!![1].reps)
    }

    @Test
    fun `malformed deltas are rejected without touching data`() = runTest {
        seedProgram()
        val id = squatId()
        val before = track(id, Slot.MAIN)!!

        // Unknown programExerciseId.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = 9999L, slot = Slot.MAIN, setIndex = 0, reps = 1, editedAtMillis = 1L)),
        )
        // Out-of-range set index.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 99, reps = 1, editedAtMillis = 1L)),
        )
        // Unknown day.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "Z", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 1, editedAtMillis = 1L)),
        )
        // Nonsense slot.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = "bogus", setIndex = 0, reps = 1, editedAtMillis = 1L)),
        )
        // A ss delta on an exercise with no partner.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.SS, setIndex = 0, reps = 1, editedAtMillis = 1L)),
        )

        assertEquals(before, track(id, Slot.MAIN)!!)
    }

    @Test
    fun `hostile numeric values are rejected without touching data`() = runTest {
        seedProgram()
        val id = squatId()
        val before = track(id, Slot.MAIN)!!

        // Negative weight.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, weightLb = -50.0, editedAtMillis = 1L)),
        )
        // Non-finite weight.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, weightLb = Double.NaN, editedAtMillis = 1L)),
        )
        // Negative reps.
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = -1, editedAtMillis = 1L)),
        )

        assertEquals(before, track(id, Slot.MAIN)!!)
    }

    @Test
    fun `a valid ss-track delta applies to the partner rows only`() = runTest {
        seedProgram()
        val id = curlId()

        // Weight + reps on the partner's first round.
        assertEquals(
            SetEditApplier.Outcome.APPLIED,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.SS, setIndex = 0, weightLb = 55.0, reps = 12, editedAtMillis = 1L)),
        )
        // Done on the partner's second round (does not pair back to main).
        assertEquals(
            SetEditApplier.Outcome.APPLIED,
            applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.SS, setIndex = 1, done = true, editedAtMillis = 2L)),
        )

        val ss = track(id, Slot.SS)!!
        assertEquals(55.0, ss[0].weightLb, 0.0)
        assertEquals(12, ss[0].reps)
        assertTrue(ss[1].done)
        // The main track is untouched by partner-row edits.
        val main = track(id, Slot.MAIN)!!
        assertEquals(listOf(60.0, 60.0), main.map { it.weightLb })
        assertFalse(main[1].done)
    }

    // --- session-start stamp (session-start capture: watch-first workouts) -----

    @Test
    fun `a done=true delta on the main row stamps the session start if unset`() = runTest {
        seedProgram()
        val id = squatId()
        assertEquals(null, repo.sessionStartedAtFlow.first())

        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, done = true, editedAtMillis = 1L))

        assertTrue(repo.sessionStartedAtFlow.first() != null)
    }

    @Test
    fun `a done=true delta on the ss row stamps the session start if unset`() = runTest {
        seedProgram()
        val id = curlId()
        assertEquals(null, repo.sessionStartedAtFlow.first())

        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.SS, setIndex = 0, done = true, editedAtMillis = 1L))

        assertTrue(repo.sessionStartedAtFlow.first() != null)
    }

    @Test
    fun `a weight or reps only delta does not stamp the session start`() = runTest {
        seedProgram()
        val id = squatId()

        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, weightLb = 140.0, editedAtMillis = 1L))
        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1, reps = 4, editedAtMillis = 2L))

        assertEquals(null, repo.sessionStartedAtFlow.first())
    }

    @Test
    fun `a done=false delta does not stamp the session start`() = runTest {
        seedProgram()
        val id = squatId()

        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, done = false, editedAtMillis = 1L))

        assertEquals(null, repo.sessionStartedAtFlow.first())
    }

    // --- per-type delta guard (design risk #2: a stale/old watch can't write dead fields) ---

    /** Day T: a REPS pull-up, a plain TIMED plank, and a loaded TIMED weighted plank. */
    private suspend fun seedTrackingDay() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "T",
                        title = "Tracking",
                        emphasisLine = "",
                        exercises = listOf(
                            ProgramExercise("pullup"),
                            ProgramExercise("plank"),
                            ProgramExercise("weighted_plank"),
                            // Mixed-type superset: a WEIGHTED main + a REPS partner (bench_dip).
                            ProgramExercise("ez_curl", superset = SupersetPartner("bench_dip")),
                        ),
                        cardio = null,
                    ),
                ),
            ),
        )
        repo.updateSets("T", trackId("pullup"), Slot.MAIN, listOf(LoggedSet(0.0, 6, SetKind.WORK)))
        repo.updateSets("T", trackId("plank"), Slot.MAIN, listOf(LoggedSet(0.0, 0, SetKind.WORK, seconds = 45)))
        repo.updateSets("T", trackId("weighted_plank"), Slot.MAIN, listOf(LoggedSet(25.0, 0, SetKind.WORK, seconds = 45)))
        repo.updateSetsPaired(
            "T", trackId("ez_curl"),
            mainSets = listOf(LoggedSet(60.0, 12, SetKind.WORK)),
            ssSets = listOf(LoggedSet(0.0, 12, SetKind.WORK)), // bench_dip: REPS, zero weight
        )
    }

    private suspend fun trackId(exerciseId: String): Long =
        repo.daySlotsFlow("T").first().first { it.exercise.exerciseId == exerciseId }.programExerciseId

    private suspend fun tTrack(id: Long): List<LoggedSet> =
        repo.logFlow("T").first().first { it.programExerciseId == id && it.slot == Slot.MAIN }.sets

    private suspend fun tSsTrack(id: Long): List<LoggedSet> =
        repo.logFlow("T").first().first { it.programExerciseId == id && it.slot == Slot.SS }.sets

    @Test
    fun `a weight edit on a TIMED track is ignored`() = runTest {
        seedTrackingDay()
        val id = trackId("plank")
        val before = tTrack(id)

        val outcome = applier.apply(
            SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, weightLb = 99.0, editedAtMillis = 1L),
        )

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome) // deduped/accepted, but the dead field is stripped
        assertEquals(before, tTrack(id)) // weight still 0, seconds still 45
    }

    @Test
    fun `a reps edit on a TIMED track is ignored`() = runTest {
        seedTrackingDay()
        val id = trackId("plank")
        val before = tTrack(id)

        applier.apply(SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 99, editedAtMillis = 1L))

        assertEquals(before, tTrack(id)) // reps stays 0
    }

    @Test
    fun `a seconds edit on a WEIGHTED track is ignored`() = runTest {
        seedProgram()
        val id = squatId()
        val before = track(id, Slot.MAIN)!!

        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, seconds = 99, editedAtMillis = 1L))

        assertEquals(before, track(id, Slot.MAIN)!!) // seconds stays 0 on a weighted lift
        assertEquals(0, track(id, Slot.MAIN)!![0].seconds)
    }

    @Test
    fun `a seconds edit on a TIMED track applies`() = runTest {
        seedTrackingDay()
        val id = trackId("weighted_plank")

        val outcome = applier.apply(
            SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, seconds = 60, editedAtMillis = 1L),
        )

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        assertEquals(60, tTrack(id)[0].seconds)
        assertEquals(25.0, tTrack(id)[0].weightLb, 0.0) // added load untouched
    }

    @Test
    fun `a reps edit on a REPS track applies`() = runTest {
        seedTrackingDay()
        val id = trackId("pullup")

        val outcome = applier.apply(
            SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 8, editedAtMillis = 1L),
        )

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        assertEquals(8, tTrack(id)[0].reps)
    }

    @Test
    fun `a weight edit on a REPS track is ignored`() = runTest {
        seedTrackingDay()
        val id = trackId("pullup")
        val before = tTrack(id)

        applier.apply(SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, weightLb = 45.0, editedAtMillis = 1L))

        assertEquals(before, tTrack(id)) // a bodyweight movement never gains a weight
        assertEquals(0.0, tTrack(id)[0].weightLb, 0.0)
    }

    @Test
    fun `an SS delta is guarded by the PARTNER's own type, not the main's`() = runTest {
        seedTrackingDay()
        val id = trackId("ez_curl") // WEIGHTED main, bench_dip (REPS) partner
        val before = tSsTrack(id)

        // A weight delta on the REPS partner row must be stripped by the partner's
        // REPS type — not accepted because the *main* is WEIGHTED. A dead weight on a
        // bodyweight row would pollute CSV and the ties-at-zero PR ordering.
        val weightOutcome = applier.apply(
            SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.SS, setIndex = 0, weightLb = 45.0, editedAtMillis = 1L),
        )
        assertEquals(SetEditApplier.Outcome.APPLIED, weightOutcome)
        assertEquals(before, tSsTrack(id)) // partner weight stays 0
        assertEquals(0.0, tSsTrack(id)[0].weightLb, 0.0)

        // A reps delta on the same REPS partner row applies (its live field).
        val repsOutcome = applier.apply(
            SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.SS, setIndex = 0, reps = 8, editedAtMillis = 2L),
        )
        assertEquals(SetEditApplier.Outcome.APPLIED, repsOutcome)
        assertEquals(8, tSsTrack(id)[0].reps)
        assertEquals(0.0, tSsTrack(id)[0].weightLb, 0.0) // still no weight
    }

    @Test
    fun `a done tick on a TIMED track still applies alongside a stripped weight field`() = runTest {
        seedTrackingDay()
        val id = trackId("plank")

        // A stale watch sends done together with a bogus plank weight — the tick must
        // still land, only the weight is dropped.
        applier.apply(
            SetEditDelta(dayId = "T", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, weightLb = 99.0, done = true, editedAtMillis = 1L),
        )

        assertTrue(tTrack(id)[0].done)
        assertEquals(0.0, tTrack(id)[0].weightLb, 0.0)
        assertEquals(45, tTrack(id)[0].seconds)
    }

    // --- per-set timing (#85: the watch sends facts, the phone stores them) -----

    @Test
    fun `a tick delta persists the wrist-observed start and complete stamps`() = runTest {
        seedProgram()
        val id = squatId()

        val outcome = applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 4,
                done = true, editedAtMillis = 1L,
                startedAtMillis = 1_700_000_000_000L,
                completedAtMillis = 1_700_000_045_000L,
            ),
        )

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        val row = track(id, Slot.MAIN)!![4]
        assertTrue(row.done)
        assertEquals(1_700_000_000_000L, row.startedAtMillis)
        assertEquals(1_700_000_045_000L, row.completedAtMillis)
        // Only the addressed row is stamped.
        assertEquals(null, track(id, Slot.MAIN)!![3].startedAtMillis)
    }

    @Test
    fun `a paired tick stamps the partner round with the same timing`() = runTest {
        seedProgram()
        val id = curlId()

        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1,
                done = true, editedAtMillis = 1L,
                startedAtMillis = 500L, completedAtMillis = 560L,
            ),
        )

        // One round, one pair of facts — written in the same paired transaction.
        assertEquals(500L, track(id, Slot.MAIN)!![1].startedAtMillis)
        assertEquals(560L, track(id, Slot.MAIN)!![1].completedAtMillis)
        assertEquals(500L, track(id, Slot.SS)!![1].startedAtMillis)
        assertEquals(560L, track(id, Slot.SS)!![1].completedAtMillis)
        assertEquals(null, track(id, Slot.SS)!![0].completedAtMillis)
    }

    @Test
    fun `a partner-row tick stamps only the partner row`() = runTest {
        seedProgram()
        val id = curlId()

        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.SS, setIndex = 0,
                done = true, editedAtMillis = 1L,
                startedAtMillis = 700L, completedAtMillis = 780L,
            ),
        )

        assertEquals(700L, track(id, Slot.SS)!![0].startedAtMillis)
        assertEquals(780L, track(id, Slot.SS)!![0].completedAtMillis)
        assertEquals(null, track(id, Slot.MAIN)!![0].startedAtMillis)
    }

    @Test
    fun `stamps survive the per-type guard on a TIMED track`() = runTest {
        seedTrackingDay()
        val id = trackId("plank")

        applier.apply(
            SetEditDelta(
                dayId = "T", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = true, editedAtMillis = 1L,
                startedAtMillis = 10L, completedAtMillis = 55L,
            ),
        )

        assertEquals(10L, tTrack(id)[0].startedAtMillis)
        assertEquals(55L, tTrack(id)[0].completedAtMillis)
    }

    @Test
    fun `a later delta without stamps leaves the stored timing alone`() = runTest {
        seedProgram()
        val id = squatId()
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = true, editedAtMillis = 1L, startedAtMillis = 10L, completedAtMillis = 70L,
            ),
        )

        // Null means "unchanged", exactly like every other delta field.
        applier.apply(SetEditDelta(dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0, reps = 6, editedAtMillis = 2L))

        assertEquals(10L, track(id, Slot.MAIN)!![0].startedAtMillis)
        assertEquals(70L, track(id, Slot.MAIN)!![0].completedAtMillis)
        assertEquals(6, track(id, Slot.MAIN)!![0].reps)
    }

    @Test
    fun `an untick clears the timing the tick wrote`() = runTest {
        seedProgram()
        val id = squatId()
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = true, editedAtMillis = 1L, startedAtMillis = 10L, completedAtMillis = 70L,
            ),
        )

        // The watch's long-press undo (#88): done=false, and no stamps of its own.
        val outcome = applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = false, editedAtMillis = 2L,
            ),
        )

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        val row = track(id, Slot.MAIN)!![0]
        assertFalse(row.done)
        assertEquals(null, row.startedAtMillis)
        assertEquals(null, row.completedAtMillis)
        // Weights and reps are untouched — an untick retracts the tick, not the set.
        assertEquals(130.0, row.weightLb, 0.0)
        assertEquals(5, row.reps)
    }

    @Test
    fun `an untick clears the partner round's timing too - one round, one pair of facts`() = runTest {
        seedProgram()
        val id = curlId()
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1,
                done = true, editedAtMillis = 1L, startedAtMillis = 500L, completedAtMillis = 560L,
            ),
        )

        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1,
                done = false, editedAtMillis = 2L,
            ),
        )

        assertFalse(track(id, Slot.MAIN)!![1].done)
        assertEquals(null, track(id, Slot.MAIN)!![1].startedAtMillis)
        assertFalse(track(id, Slot.SS)!![1].done)
        assertEquals(null, track(id, Slot.SS)!![1].startedAtMillis)
        assertEquals(null, track(id, Slot.SS)!![1].completedAtMillis)
    }

    @Test
    fun `a re-tick after an untick stamps the set afresh`() = runTest {
        seedProgram()
        val id = squatId()
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = true, editedAtMillis = 1L, startedAtMillis = 10L, completedAtMillis = 70L,
            ),
        )
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = false, editedAtMillis = 2L,
            ),
        )
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = true, editedAtMillis = 3L, startedAtMillis = 800L, completedAtMillis = 860L,
            ),
        )

        val row = track(id, Slot.MAIN)!![0]
        assertTrue(row.done)
        assertEquals(800L, row.startedAtMillis)
        assertEquals(860L, row.completedAtMillis)
    }

    @Test
    fun `the dedupe stamp still rules - a replayed tick with new timing is dropped`() = runTest {
        seedProgram()
        val id = squatId()
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = true, editedAtMillis = 100L, startedAtMillis = 10L, completedAtMillis = 70L,
            ),
        )

        // editedAtMillis keeps its own job: same stamp = replay, whatever the facts say.
        val replay = applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                done = true, editedAtMillis = 100L, startedAtMillis = 999L, completedAtMillis = 9_999L,
            ),
        )

        assertEquals(SetEditApplier.Outcome.STALE, replay)
        assertEquals(10L, track(id, Slot.MAIN)!![0].startedAtMillis)
        assertEquals(70L, track(id, Slot.MAIN)!![0].completedAtMillis)
    }

    @Test
    fun `negative timing is rejected without touching data`() = runTest {
        seedProgram()
        val id = squatId()
        val before = track(id, Slot.MAIN)!!

        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(
                SetEditDelta(
                    dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                    done = true, editedAtMillis = 1L, startedAtMillis = -1L,
                ),
            ),
        )
        assertEquals(
            SetEditApplier.Outcome.INVALID,
            applier.apply(
                SetEditDelta(
                    dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                    done = true, editedAtMillis = 1L, completedAtMillis = -5L,
                ),
            ),
        )

        assertEquals(before, track(id, Slot.MAIN)!!)
    }

    @Test
    fun `an archived session carries the per-set timing into history`() = runTest {
        seedProgram()
        val id = squatId()
        applier.apply(
            SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 4,
                done = true, editedAtMillis = 1L, startedAtMillis = 1_000L, completedAtMillis = 1_042L,
            ),
        )

        val sessionId = repo.advanceDay("A")
        val archived = repo.sessionSets(sessionId)
            .first { it.slot == Slot.MAIN && it.setIndex == 4 && it.exerciseId == "bb_back_squat" }

        assertEquals(1_000L, archived.startedAtMillis)
        assertEquals(1_042L, archived.completedAtMillis)
    }

    /** The pinned §8.3 contract: the slot keeps its id, the exercise changes, and the
     *  old log does not survive into it. Reseeding is part of the same application
     *  here (a watch swap can land with no phone screen open to do it lazily), so the
     *  track that comes out is the *new* exercise's fresh seed — nothing logged, and
     *  not the squat ramp that was there. */
    @Test
    fun valid_swap_changes_the_exercise_keeps_the_slot_id_and_reseeds_its_log() = runTest {
        seedProgram()
        val id = squatId()
        val before = track(id, Slot.MAIN)!!
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()

        val outcome = applier.apply(ExerciseSwapDelta(1, "A", id, target.id, target.name, 1L))

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        val slot = repo.daySlotsFlow("A").first().first { it.programExerciseId == id }
        assertEquals(target.id, slot.exercise.exerciseId)
        assertEquals(id, slot.programExerciseId)
        val after = track(id, Slot.MAIN)!!
        assertTrue(after.isNotEmpty())
        assertTrue(after.none { it.done })
        assertTrue(after != before)
    }

    @Test
    fun unprescribed_swap_is_invalid_and_changes_nothing() = runTest {
        seedProgram()
        val id = squatId()
        val before = track(id, Slot.MAIN)

        val outcome = applier.apply(ExerciseSwapDelta(1, "A", id, "custom_nonexistent", "Invented", 1L))

        assertEquals(SetEditApplier.Outcome.INVALID, outcome)
        val slot = repo.daySlotsFlow("A").first().first { it.programExerciseId == id }
        assertEquals("bb_back_squat", slot.exercise.exerciseId)
        assertEquals(before, track(id, Slot.MAIN))
    }

    @Test
    fun swap_for_a_slot_not_on_the_day_is_invalid() = runTest {
        seedProgram()

        val outcome = applier.apply(ExerciseSwapDelta(1, "A", 9999L, "hack_squat", "Hack Squat", 1L))

        assertEquals(SetEditApplier.Outcome.INVALID, outcome)
    }

    /**
     * The interleaving that used to lose logged work. Two things seed now — the day
     * ViewModel lazily, and this applier eagerly after a watch swap — and the
     * ViewModel's plan is decided from a read it may act on much later. Here it reads
     * an empty track, the swap seeds it, the lifter logs a set against the *new*
     * exercise, and only then does the stale plan try to write. It must no-op:
     * `seedIfEmpty` re-checks inside its own transaction, so whoever seeds first wins
     * and a tick landed since can never be overwritten by a decision taken before it
     * existed.
     */
    @Test
    fun a_stale_seed_plan_cannot_overwrite_work_logged_since_it_was_decided() = runTest {
        seedProgram()
        val id = squatId()
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        // The state the day screen's seeder would have read: this slot has no track.
        repo.swapExerciseById("A", id, "bb_back_squat")
        val stalePlan = listOf(LoggedSet(999.0, 1, SetKind.WORK))

        // The applier seeds the swapped slot...
        applier.apply(ExerciseSwapDelta(1, "A", id, target.id, target.name, 100L))
        // ...the lifter logs against what it seeded...
        val logged = track(id, Slot.MAIN)!!.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s }
        repo.updateSets("A", id, Slot.MAIN, logged)
        // ...and only now does the stale plan reach the database.
        val wrote = repo.seedIfEmpty("A", id, Slot.MAIN, stalePlan)

        assertFalse(wrote)
        assertEquals(logged, track(id, Slot.MAIN))
        assertTrue(track(id, Slot.MAIN)!![0].done)
    }

    /** A swap that lost the race — an older stamp than the one already applied to
     *  this slot — is dropped before it can be evaluated for anything else. (A
     *  *replay of the same* swap doesn't reach here: the slot already holds the
     *  exercise it asks for, which the guard above answers APPLIED.) */
    @Test
    fun a_swap_older_than_the_one_already_applied_is_stale() = runTest {
        seedProgram()
        val id = squatId()
        val candidates = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat")
        val applied = candidates[0]
        val late = candidates[1]
        assertEquals(
            SetEditApplier.Outcome.APPLIED,
            applier.apply(ExerciseSwapDelta(1, "A", id, applied.id, applied.name, 100L)),
        )

        val outcome = applier.apply(ExerciseSwapDelta(1, "A", id, late.id, late.name, 50L))

        assertEquals(SetEditApplier.Outcome.STALE, outcome)
        val slot = repo.daySlotsFlow("A").first().first { it.programExerciseId == id }
        assertEquals(applied.id, slot.exercise.exerciseId)
    }

    @Test
    fun swap_to_the_current_exercise_is_applied_without_clearing_new_logs() = runTest {
        seedProgram()
        val id = squatId()
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        assertEquals(
            SetEditApplier.Outcome.APPLIED,
            applier.apply(ExerciseSwapDelta(1, "A", id, target.id, target.name, 100L)),
        )
        val newLog = listOf(LoggedSet(75.0, 8, SetKind.WORK))
        repo.updateSets("A", id, Slot.MAIN, newLog)

        val outcome = applier.apply(ExerciseSwapDelta(1, "A", id, target.id, target.name, 101L))

        assertEquals(SetEditApplier.Outcome.APPLIED, outcome)
        assertEquals(newLog, track(id, Slot.MAIN))
    }
}
