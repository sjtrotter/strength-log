package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The session-start stamp lifecycle (session-start capture): the DataStore
 * slot is set once by [TrackerRepository.stampSessionStartIfUnset], cleared by
 * [TrackerRepository.clearChecks], and consumed into
 * `workout_session.startedAt` by [TrackerRepository.advanceDay] — see that
 * method's crash-ordering doc for why the read happens before the Room
 * transaction and the clear happens after. Same Robolectric + in-memory-Room +
 * real-DataStore shape as [TrackerRepositoryPairedWriteTest], with a
 * [MutableClock] so "the tick" and "the advance" land at distinct, assertable
 * instants.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionStartStampTest {

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
            File.createTempFile("session-stamp-settings", ".preferences_pb")
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

    private suspend fun seedProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Test",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", isMain = true, targetSets = 1)),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    @Test
    fun noStampUntilTheFirstStampCall() = runTest {
        assertNull(repo.sessionStartedAtFlow.first())
    }

    @Test
    fun firstStampWins_aSecondCallIsANoOp() = runTest {
        repo.stampSessionStartIfUnset()
        val first = repo.sessionStartedAtFlow.first()
        assertEquals(clock.instant.toEpochMilli(), first)

        clock.instant = clock.instant.plusSeconds(60)
        repo.stampSessionStartIfUnset()

        assertEquals("a second stamp call must not move an existing stamp", first, repo.sessionStartedAtFlow.first())
    }

    @Test
    fun clearChecksClearsTheStamp() = runTest {
        seedProgram()
        repo.stampSessionStartIfUnset()
        assertEquals(clock.instant.toEpochMilli(), repo.sessionStartedAtFlow.first())

        repo.clearChecks("A")

        assertNull(repo.sessionStartedAtFlow.first())
    }

    @Test
    fun advanceDayConsumesTheStampIntoTheSessionAndClearsIt() = runTest {
        seedProgram()
        repo.stampSessionStartIfUnset()
        val stampedAt = clock.instant.toEpochMilli()

        clock.instant = clock.instant.plusSeconds(1_800) // 30 minutes later
        val sessionId = repo.advanceDay("A")

        val session = repo.session(sessionId)!!
        assertEquals(stampedAt, session.startedAt)
        assertEquals(clock.instant.toEpochMilli(), session.completedAt)
        assertNull("the stamp must be consumed, not left for the next session", repo.sessionStartedAtFlow.first())
    }

    @Test
    fun advanceDayWithNoStampWritesANullStartedAt() = runTest {
        seedProgram()
        val sessionId = repo.advanceDay("A")
        assertNull(repo.session(sessionId)!!.startedAt)
    }

    // --- cross-day staleness (the abandoned-session poison path) --------------

    @Test
    fun aStampFromAPreviousCalendarDayReadsAsAbsent() = runTest {
        repo.stampSessionStartIfUnset()
        assertEquals(clock.instant.toEpochMilli(), repo.sessionStartedAtFlow.first())

        // Roll the device clock into the next calendar day (UTC zone): the
        // yesterday-dated stamp must no longer surface.
        clock.instant = clock.instant.plusSeconds(24 * 3_600)
        assertNull("a stamp dated to a prior day must read as unset", repo.sessionStartedAtFlow.first())
    }

    @Test
    fun theFirstTickOfANewDayOverwritesAStaleStampWithTodaysStart() = runTest {
        repo.stampSessionStartIfUnset()
        val dayOne = clock.instant.toEpochMilli()

        clock.instant = clock.instant.plusSeconds(24 * 3_600) // next calendar day
        repo.stampSessionStartIfUnset() // first tick of the new day

        // Fresh stamp for today, not the inherited day-one start.
        assertEquals(clock.instant.toEpochMilli(), repo.sessionStartedAtFlow.first())
        assertNotEquals(dayOne, repo.sessionStartedAtFlow.first())
    }

    @Test
    fun advanceDayOnALaterDayDoesNotInheritAnAbandonedStamp() = runTest {
        seedProgram()
        repo.stampSessionStartIfUnset() // day one: ticked but never advanced

        clock.instant = clock.instant.plusSeconds(24 * 3_600) // finish on a later day
        val sessionId = repo.advanceDay("A")

        assertNull(
            "a stale cross-day stamp must not become the completed session's startedAt",
            repo.session(sessionId)!!.startedAt,
        )
    }
}
