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

/**
 * Documents the accepted A6 limitation (PLAN.md, spec §11.2): [TrackerRepository.logFlow]
 * applies the checkmark reset at *emission* time, computing "today" fresh each
 * time the underlying Room query re-runs. Room's [androidx.room.InvalidationTracker]
 * only re-emits on a write to an observed table, never on a wall-clock tick, so a
 * midnight crossing with no DB write in between does not itself trigger a
 * re-emission — a collector that has been alive since before midnight keeps
 * seeing yesterday's (still-checked) snapshot until something re-collects the
 * flow. This is a deliberate, accepted trade-off, not a bug: the UI re-collects
 * `logFlow` whenever the day screen resumes, so the stale window in practice is
 * "the app kept running, uninteracted with, across local midnight."
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
        dataStoreScope.cancel()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        context.preferencesDataStoreFile(prefsName).delete()
    }

    @Test
    fun a_midnight_crossing_with_no_write_does_not_re_emit_until_re_collection() = runTest {
        val exercise = ProgramExercise(exerciseId = "bb_back_squat", isMain = true)
        val day = ProgramDay(
            id = dayId,
            title = "Day A",
            emphasisLine = "Squat-focused",
            exercises = listOf(exercise),
            cardio = null,
        )
        repository.replaceProgram(Program(listOf(day)))
        val rowId = db.programDao().exerciseAt(dayId, 0)!!.id

        assertEquals("2026-07-06", CheckmarkReset.today(clock))
        repository.updateSets(dayId, rowId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))

        repository.logFlow(dayId).test {
            val beforeMidnight = awaitItem().single()
            assertTrue("checked before midnight", beforeMidnight.sets.single().done)

            // Cross local midnight — no DB write accompanies it.
            clock.instant = Instant.parse("2026-07-07T05:00:00Z") // 2026-07-07, 1am EDT
            assertEquals("2026-07-07", CheckmarkReset.today(clock))

            // The live collector does not re-emit on its own: no table changed.
            expectNoEvents()
        }

        // A fresh collection re-runs the query and reapplies the reset at the new
        // "today" — this is how the UI actually observes the rollover in practice
        // (day-screen resume re-subscribes to logFlow).
        val afterReCollection = repository.logFlow(dayId).first().single()
        assertFalse("cleared once something re-collects the flow", afterReCollection.sets.single().done)
    }
}
