package cloud.trotter.log.strength.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import cloud.trotter.log.strength.data.checkmark.CheckmarkReset
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Pins the live civil-day seam on [TrackerRepository.logFlow]. The date is an
 * upstream alongside Room, so midnight and zone changes reproject stored rows
 * without requiring a database write.
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
    fun a_parked_collector_clears_checks_on_a_day_tick_without_a_database_write() = runTest {
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
        assertEquals("2026-07-06", CheckmarkReset.today(clock))
        repository.updateSets(dayId, squatId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))

        val today = MutableStateFlow(java.time.LocalDate.of(2026, 7, 6))
        repository.logFlow(dayId, today).test {
            val beforeMidnight = awaitItem().single()
            assertTrue("checked before midnight", beforeMidnight.sets.single().done)

            today.value = java.time.LocalDate.of(2026, 7, 7)

            // The squat row itself was untouched; only civil time emitted.
            val afterMidnight = awaitItem().associateBy { it.programExerciseId }
            val squatLog = afterMidnight.getValue(squatId)
            assertFalse("yesterday's check cleared at emission", squatLog.sets.single().done)
            assertEquals("2026-07-06", squatLog.checkDate) // stored stamp untouched; reset is read-side
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_zone_change_reprojects_against_the_new_zones_date() = runTest {
        val day = ProgramDay(dayId, "Day A", "", listOf(ProgramExercise("bb_back_squat")), cardio = null)
        repository.replaceProgram(Program(listOf(day)))
        val slotId = db.programDao().exerciseAt(dayId, 0)!!.id
        repository.updateSets(dayId, slotId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))

        val instant = Instant.parse("2026-07-07T03:00:00Z")
        val today = MutableStateFlow(instant.atZone(ny).toLocalDate())
        repository.logFlow(dayId, today).test {
            assertTrue(awaitItem().single().sets.single().done)
            today.value = instant.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()
            assertFalse(awaitItem().single().sets.single().done)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
