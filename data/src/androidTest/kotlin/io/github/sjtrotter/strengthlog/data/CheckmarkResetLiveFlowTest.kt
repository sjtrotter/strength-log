package io.github.sjtrotter.strengthlog.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.sjtrotter.strengthlog.data.checkmark.CheckmarkReset
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

/**
 * Pins how [TrackerRepository.logFlow] applies the daily checkmark reset: at
 * *emission* time, computing "today" fresh on each re-query. A collector that
 * crossed local midnight sees stale checks cleared on the very next emission —
 * whatever triggered it — because the reset is evaluated against the new date,
 * not the date the flow was first collected on.
 *
 * This is also where the accepted A6 limitation lives (PLAN.md): Room's
 * InvalidationTracker only re-runs the query on a write to an observed table,
 * never on a wall-clock tick, so a midnight crossing with *no* accompanying DB
 * write does not itself produce a re-emission — a silent, untouched app keeps
 * showing yesterday's snapshot until something writes or re-collects (the day
 * screen re-subscribes on resume, so in practice the stale window is an app
 * left running, uninteracted with, across local midnight).
 */
@RunWith(AndroidJUnit4::class)
class CheckmarkResetLiveFlowTest {

    private val dbName = "live_flow_reset_test.db"
    private val prefsName = "live_flow_reset_test_prefs"
    private val dayId = "A"
    private val ny = ZoneId.of("America/New_York")

    /** A [Clock] whose instant can be advanced mid-test, standing in for time
     *  passing with the app alive and no relaunch. */
    private class MutableClock(private val zone: ZoneId, var instant: Instant) : Clock() {
        override fun getZone() = zone
        override fun withZone(zone: ZoneId) = MutableClock(zone, instant)
        override fun instant() = instant
    }

    private lateinit var db: StrengthDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var clock: MutableClock
    private lateinit var repository: TrackerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        context.preferencesDataStoreFile(prefsName).delete()

        db = Room.databaseBuilder(context, StrengthDatabase::class.java, dbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        clock = MutableClock(ny, Instant.parse("2026-07-06T20:00:00Z")) // 2026-07-06, 4pm EDT
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context.preferencesDataStoreFile(prefsName) },
        )
        repository = TrackerRepository(
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
        // Join the cancellation so the next test's DataStore over this file
        // can't race "multiple DataStores active".
        runBlocking { dataStoreScope.coroutineContext.job.cancelAndJoin() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        context.preferencesDataStoreFile(prefsName).delete()
    }

    @Test
    fun a_live_collector_sees_stale_checks_cleared_on_the_first_emission_after_midnight() = runTest {
        val day = ProgramDay(
            id = dayId,
            title = "Day A",
            emphasisLine = "Squat-focused",
            exercises = listOf(
                ProgramExercise(exerciseId = "bb_back_squat", isMain = true),
                ProgramExercise(exerciseId = "bb_row"),
            ),
            cardio = null,
        )
        repository.replaceProgram(Program(listOf(day)))
        val squatId = db.programDao().exerciseAt(dayId, 0)!!.id
        val rowId = db.programDao().exerciseAt(dayId, 1)!!.id

        assertEquals("2026-07-06", CheckmarkReset.today(clock))
        repository.updateSets(dayId, squatId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))

        repository.logFlow(dayId).test {
            val beforeMidnight = awaitItem().single()
            assertTrue("checked before midnight", beforeMidnight.sets.single().done)

            // Cross local midnight with the collector still alive. The clock bump
            // alone triggers nothing (the accepted A6 limitation: no table write,
            // no re-query) — so the next observable emission is caused by a write.
            clock.instant = Instant.parse("2026-07-07T05:00:00Z") // 2026-07-07, 1am EDT
            assertEquals("2026-07-07", CheckmarkReset.today(clock))
            repository.updateSets(dayId, rowId, Slot.MAIN, listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true)))

            // That emission is computed against the NEW today: the squat's check
            // (stamped yesterday) reads cleared even though its row was untouched,
            // while the row's fresh check (stamped today) stands.
            val afterMidnight = awaitItem().associateBy { it.programExerciseId }
            val squatLog = afterMidnight.getValue(squatId)
            assertFalse("yesterday's check cleared at emission", squatLog.sets.single().done)
            assertEquals("2026-07-06", squatLog.checkDate) // stored stamp untouched; reset is read-side
            assertTrue("today's check stands", afterMidnight.getValue(rowId).sets.single().done)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
