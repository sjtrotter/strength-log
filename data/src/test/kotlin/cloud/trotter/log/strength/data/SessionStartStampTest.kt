package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
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
        assertNull(repo.sessionStartedAtFlow(1).first())
    }

    @Test
    fun firstStampWins_aSecondCallIsANoOp() = runTest {
        repo.stampSessionStartIfUnset(1)
        val first = repo.sessionStartedAtFlow(1).first()
        assertEquals(clock.instant.toEpochMilli(), first)

        clock.instant = clock.instant.plusSeconds(60)
        repo.stampSessionStartIfUnset(1)

        assertEquals("a second stamp call must not move an existing stamp", first, repo.sessionStartedAtFlow(1).first())
    }

    @Test
    fun clearChecksClearsTheStamp() = runTest {
        seedProgram()
        repo.stampSessionStartIfUnset(1)
        assertEquals(clock.instant.toEpochMilli(), repo.sessionStartedAtFlow(1).first())

        repo.clearChecks("A")

        assertNull(repo.sessionStartedAtFlow(1).first())
    }

    @Test
    fun advanceDayConsumesTheStampIntoTheSessionAndClearsIt() = runTest {
        seedProgram()
        repo.stampSessionStartIfUnset(1)
        val stampedAt = clock.instant.toEpochMilli()

        clock.instant = clock.instant.plusSeconds(1_800) // 30 minutes later
        val sessionId = repo.advanceDay("A")

        val session = repo.session(sessionId)!!
        assertEquals(stampedAt, session.startedAt)
        assertEquals(clock.instant.toEpochMilli(), session.completedAt)
        assertNull("the stamp must be consumed, not left for the next session", repo.sessionStartedAtFlow(1).first())
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
        repo.stampSessionStartIfUnset(1)
        assertEquals(clock.instant.toEpochMilli(), repo.sessionStartedAtFlow(1).first())

        // Roll the device clock into the next calendar day (UTC zone): the
        // yesterday-dated stamp must no longer surface.
        clock.instant = clock.instant.plusSeconds(24 * 3_600)
        assertNull("a stamp dated to a prior day must read as unset", repo.sessionStartedAtFlow(1).first())
        assertNull(repo.sessionStartedAtFlow(1, flowOf(repo.currentDate())).first())
    }

    @Test
    fun theFirstTickOfANewDayOverwritesAStaleStampWithTodaysStart() = runTest {
        repo.stampSessionStartIfUnset(1)
        val dayOne = clock.instant.toEpochMilli()

        clock.instant = clock.instant.plusSeconds(24 * 3_600) // next calendar day
        repo.stampSessionStartIfUnset(1) // first tick of the new day

        // Fresh stamp for today, not the inherited day-one start.
        assertEquals(clock.instant.toEpochMilli(), repo.sessionStartedAtFlow(1).first())
        assertNotEquals(dayOne, repo.sessionStartedAtFlow(1).first())
    }

    @Test
    fun advanceDayOnALaterDayDoesNotInheritAnAbandonedStamp() = runTest {
        seedProgram()
        repo.stampSessionStartIfUnset(1) // day one: ticked but never advanced

        clock.instant = clock.instant.plusSeconds(24 * 3_600) // finish on a later day
        val sessionId = repo.advanceDay("A")

        assertNull(
            "a stale cross-day stamp must not become the completed session's startedAt",
            repo.session(sessionId)!!.startedAt,
        )
    }

    @Test
    fun tickingAnotherPlanReplacesTheStartAndFinishingItUsesItsOwnStart() = runTest {
        seedProgram()
        val planP = repo.selectedPlanIdFlow.first()!!
        val planQ = repo.duplicatePlan(planP, "Q")
        clock.instant = Instant.parse("2026-07-06T10:00:00Z")
        repo.stampSessionStartIfUnset(planP)

        repo.selectPlan(planQ)
        clock.instant = Instant.parse("2026-07-06T10:15:00Z")
        repo.stampSessionStartIfUnset(planQ)
        val qStart = clock.instant.toEpochMilli()
        assertEquals(qStart, repo.sessionStartedAtFlow(planQ).first())
        assertEquals(qStart, repo.sessionStartedAtFlow(planQ, flowOf(repo.currentDate())).first())
        assertNull(repo.sessionStartedAtFlow(planP).first())
        assertNull(repo.sessionStartedAtFlow(planP, flowOf(repo.currentDate())).first())

        clock.instant = Instant.parse("2026-07-06T10:20:00Z")
        val qSession = repo.advanceDay("A", repo.currentDate())
        assertEquals(qStart, repo.session(qSession)!!.startedAt)
        assertEquals(clock.instant.toEpochMilli(), repo.session(qSession)!!.completedAt)
        assertNull(repo.sessionStartedAtFlow(planQ).first())
        assertNull(dataStore.data.first()[longPreferencesKey("session_started_plan_id")])

        repo.selectPlan(planP)
        clock.instant = Instant.parse("2026-07-06T10:30:00Z")
        val pSession = repo.advanceDay("A")
        assertNull("Q replaced the one slot; P needs another tick to regain a start", repo.session(pSession)!!.startedAt)
    }

    @Test
    fun anotherPlansClearChecksLeavesTheStamp() = runTest {
        seedProgram()
        val planP = repo.selectedPlanIdFlow.first()!!
        val planQ = repo.duplicatePlan(planP, "Q")
        repo.stampSessionStartIfUnset(planP)
        val pStart = clock.instant.toEpochMilli()

        repo.selectPlan(planQ)
        repo.clearChecks("A")

        assertEquals(pStart, repo.sessionStartedAtFlow(planP).first())
        assertNull(repo.sessionStartedAtFlow(planQ).first())
    }

    @Test
    fun anotherPlansAdvanceLeavesTheStampForItsOwner() = runTest {
        seedProgram()
        val planP = repo.selectedPlanIdFlow.first()!!
        val planQ = repo.duplicatePlan(planP, "Q")
        repo.stampSessionStartIfUnset(planP)
        val pStart = clock.instant.toEpochMilli()

        repo.selectPlan(planQ)
        clock.instant = clock.instant.plusSeconds(60)
        val qSession = repo.advanceDay("A")
        assertNull(repo.session(qSession)!!.startedAt)
        assertEquals(pStart, repo.sessionStartedAtFlow(planP).first())

        repo.selectPlan(planP)
        val pSession = repo.advanceDay("A")
        assertEquals(pStart, repo.session(pSession)!!.startedAt)
    }

    @Test
    fun legacyStampWithoutAPlanServesOnlyTheSelectedPlan() = runTest {
        seedProgram()
        val planP = repo.selectedPlanIdFlow.first()!!
        val planQ = repo.duplicatePlan(planP, "Q")
        val legacyStart = clock.instant.toEpochMilli()
        dataStore.edit {
            it[longPreferencesKey("session_started_at")] = legacyStart
            it[stringPreferencesKey("session_started_date")] = repo.currentDate().toString()
        }

        assertEquals(legacyStart, repo.sessionStartedAtFlow(planP).first())
        assertEquals(legacyStart, repo.sessionStartedAtFlow(planP, flowOf(repo.currentDate())).first())
        assertNull(repo.sessionStartedAtFlow(planQ).first())
        assertNull(repo.sessionStartedAtFlow(planQ, flowOf(repo.currentDate())).first())
        clock.instant = clock.instant.plusSeconds(60)

        val sessionId = repo.advanceDay("A")
        assertEquals(legacyStart, repo.session(sessionId)!!.startedAt)
        assertNull(repo.sessionStartedAtFlow(planP).first())
    }

    @Test
    fun firstTickBindsALegacyStampWithoutMovingItsStart() = runTest {
        seedProgram()
        val planP = repo.selectedPlanIdFlow.first()!!
        val planQ = repo.duplicatePlan(planP, "Q")
        val legacyStart = clock.instant.toEpochMilli()
        dataStore.edit {
            it[longPreferencesKey("session_started_at")] = legacyStart
            it[stringPreferencesKey("session_started_date")] = repo.currentDate().toString()
            // Selection can still be implicit, via the repository's first-plan fallback.
            it.remove(longPreferencesKey("selected_plan_id"))
        }
        assertEquals(planP, repo.selectedPlanIdFlow.first())
        assertEquals(legacyStart, repo.sessionStartedAtFlow(planP).first())
        clock.instant = clock.instant.plusSeconds(60)

        repo.stampSessionStartIfUnset(planP)

        assertEquals(legacyStart, repo.sessionStartedAtFlow(planP).first())
        assertEquals(planP, dataStore.data.first()[longPreferencesKey("session_started_plan_id")])
        repo.selectPlan(planQ)
        assertNull(repo.sessionStartedAtFlow(planQ).first())
        repo.stampSessionStartIfUnset(planQ)
        assertEquals(clock.instant.toEpochMilli(), repo.sessionStartedAtFlow(planQ).first())
        assertNull(repo.sessionStartedAtFlow(planP).first())
    }
}
