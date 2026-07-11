package io.github.sjtrotter.strengthlog.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.model.SupersetPartner
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
        applier = SetEditApplier(repo, markers)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
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
                        ),
                        cardio = null,
                    ),
                ),
            ),
        )
        repo.updateSets("T", trackId("pullup"), Slot.MAIN, listOf(LoggedSet(0.0, 6, SetKind.WORK)))
        repo.updateSets("T", trackId("plank"), Slot.MAIN, listOf(LoggedSet(0.0, 0, SetKind.WORK, seconds = 45)))
        repo.updateSets("T", trackId("weighted_plank"), Slot.MAIN, listOf(LoggedSet(25.0, 0, SetKind.WORK, seconds = 45)))
    }

    private suspend fun trackId(exerciseId: String): Long =
        repo.daySlotsFlow("T").first().first { it.exercise.exerciseId == exerciseId }.programExerciseId

    private suspend fun tTrack(id: Long): List<LoggedSet> =
        repo.logFlow("T").first().first { it.programExerciseId == id && it.slot == Slot.MAIN }.sets

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
}
