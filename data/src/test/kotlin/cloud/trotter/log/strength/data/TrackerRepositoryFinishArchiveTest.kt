package cloud.trotter.log.strength.data

import kotlinx.coroutines.flow.map
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryFinishArchiveTest {

    private class MutableClock(private val zone: ZoneId, var instant: Instant) : Clock() {
        override fun getZone() = zone
        override fun withZone(zone: ZoneId) = MutableClock(zone, instant)
        override fun instant() = instant
    }

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var clock: MutableClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("finish-archive-settings", ".preferences_pb")
        }
        clock = MutableClock(ZoneId.of("UTC"), Instant.parse("2026-07-06T12:00:00Z"))
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun seedTracks(): List<Long> {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Test",
                        emphasisLine = "",
                        exercises = listOf(
                            ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 2),
                            ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 2),
                        ),
                    ),
                ),
            ),
        )
        val ids = repo.plans.daySlotsFlow("A").first().map { it.programExerciseId }
        for (id in ids) {
            db.storeTestRows(
                "A", id, Slot.MAIN,
                listOf(LoggedSet(100.0, 8, SetKind.WORK), LoggedSet(90.0, 10, SetKind.BACKOFF)),
            )
        }
        return ids
    }

    private suspend fun tickSets(slotId: Long, indices: Set<Int>) {
        val sets = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single { it.programExerciseId == slotId }.sets
        repo.writeTestRows(repo.dayTracksFlow(repo.plans.planIdOfSlot(slotId) ?: -1, "A").first().runId.orEmpty(),
            "A", slotId, Slot.MAIN,
            sets.mapIndexed { index, set ->
                if (index in indices) {
                    set.copy(
                        done = true,
                        startedAtMillis = clock.millis() - 30_000L,
                        completedAtMillis = clock.millis(),
                    )
                } else {
                    set
                }
            },
        )
    }

    @Test
    fun finishSnapshotsTheSlotsNoteAtFinishAndStoresNullForBlankNotes() = runTest {
        val ids = seedTracks()
        repo.setExerciseNote(ids.first(), "Seat 4")
        repo.setExerciseNote(ids.last(), "  ")
        ids.forEach { tickSets(it, setOf(0)) }
        repo.setExerciseNote(ids.first(), "Seat 5")

        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!
        val plannedJson = repo.history.session(sessionId)!!.plannedJson!!
        val snapshot = SessionPlanJson.decode(plannedJson)
        assertEquals(listOf("Seat 5", null), snapshot.map { it.note })
        val archived = repo.history.sessionSets(sessionId)
        snapshot.forEach { track ->
            assertTrue(archived.any {
                it.trackPosition == track.position && it.slot == track.slot && it.exerciseId == track.exerciseId
            })
        }

        repo.setExerciseNote(ids.first(), "Seat 6")
        repo.setExerciseNote(ids.last(), "Bench 2")
        assertEquals(plannedJson, repo.history.session(sessionId)!!.plannedJson)
    }

    @Test
    fun finishedShortDayCountsFiveDoneSetsAndKeepsSixUndoneRows() = runTest {
        val ids = seedTracks()
        db.storeTestRows("A", ids.first(), Slot.MAIN, List(5) { LoggedSet(100.0, 8, SetKind.WORK) })
        db.storeTestRows("A", ids.last(), Slot.MAIN, List(6) { LoggedSet(90.0, 10, SetKind.WORK) })
        tickSets(ids.first(), (0 until 5).toSet())

        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val summary = repo.history.sessionSummariesFlow.first().single()
        assertEquals(sessionId, summary.session.id)
        assertEquals(5, summary.setCount)
        val archived = repo.history.sessionSets(sessionId)
        assertEquals(11, archived.size)
        assertEquals(5, archived.count { it.done })
        assertEquals(6, archived.count { !it.done })
        assertTrue(archived.filter { it.exerciseId == "bb_back_squat" }.all { it.done })
        assertTrue(archived.filter { it.exerciseId == "bb_bench" }.none { it.done })
    }

    @Test
    fun finishClearsEarlierRunFlagsAndTimingForAnUntouchedTrack() = runTest {
        val ids = seedTracks()
        for (id in ids) tickSets(id, setOf(0, 1))
        val firstSessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!
        val firstArchive = repo.history.sessionSets(firstSessionId)
        assertEquals(4, firstArchive.size)
        firstArchive.forEach {
            assertTrue(it.done)
            assertNotNull(it.startedAtMillis)
            assertNotNull(it.completedAtMillis)
        }

        clock.instant = clock.instant.plusSeconds(3_600)
        tickSets(ids.first(), setOf(0))
        val secondSessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val untouched = repo.history.sessionSets(secondSessionId).filter { it.exerciseId == "bb_bench" }
        assertEquals(2, untouched.size)
        untouched.forEach {
            assertFalse(it.done)
            assertNull(it.startedAtMillis)
            assertNull(it.completedAtMillis)
        }
    }

    @Test
    fun clearThenTickThenFinishArchivesOnlyTheNewTick() = runTest {
        val ids = seedTracks()
        for (id in ids) tickSets(id, setOf(0, 1))
        repo.discardRun(repo.openRunFlow.first()!!.id)

        clock.instant = clock.instant.plusSeconds(3_600)
        tickSets(ids.first(), setOf(0))
        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val archived = repo.history.sessionSets(sessionId)
        val untouched = archived.filter { it.exerciseId == "bb_bench" }
        assertEquals(2, untouched.size)
        untouched.forEach {
            assertFalse(it.done)
            assertNull(it.startedAtMillis)
            assertNull(it.completedAtMillis)
        }
        val touched = archived.filter { it.exerciseId == "bb_back_squat" }
        assertEquals(2, touched.size)
        assertEquals(1, touched.count { it.done })
    }

    @Test
    fun finishAcrossMidnightKeepsTheTickAndItsTiming() = runTest {
        val ids = seedTracks()
        clock.instant = Instant.parse("2026-07-06T23:40:00Z")
        assertEquals("2026-07-06", LocalDate.now(clock).toString())
        tickSets(ids.first(), setOf(0))
        val ticked = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().single { it.programExerciseId == ids.first() }.sets.first()

        clock.instant = Instant.parse("2026-07-07T00:20:00Z")
        assertEquals("2026-07-07", LocalDate.now(clock).toString())
        val sessionId = repo.finishRun(repo.openRunFlow.first()!!.id)!!

        val archived = repo.history.sessionSets(sessionId).single { it.exerciseId == "bb_back_squat" && it.setIndex == 0 }
        assertTrue(archived.done)
        assertNotNull(ticked.startedAtMillis)
        assertNotNull(ticked.completedAtMillis)
        assertEquals(ticked.startedAtMillis, archived.startedAtMillis)
        assertEquals(ticked.completedAtMillis, archived.completedAtMillis)
    }
}
