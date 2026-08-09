package cloud.trotter.log.strength.transfer.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.FlakyDataStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The turn-taking [BackupService] owes the restore journal (#172). A restore and
 * the startup reconciliation drive the same payload, so they must never overlap:
 * a reconcile reading mid-restore would replay a journal the restore is still
 * writing, and two restores at once would interleave two nonces through one
 * marker row.
 *
 * Real repository, real service, real Room — only the settings DataStore is
 * instrumented, so a restore can be held open at a known point while something
 * else tries to run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreCoordinationTest {

    private lateinit var db: StrengthDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var settingsStore: FlakyDataStore
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository
    private lateinit var service: BackupService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsStore = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-coordination-settings", ".preferences_pb")
            },
        )
        settings = SettingsStore(settingsStore)
        journal = RestoreJournal(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-coordination-journal", ".preferences_pb")
            },
            settings,
        )
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        service = BackupService(repo, journal)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    /** A minimal but valid backup naming one day and one catalog exercise. */
    private fun backupJson(dayId: String, bodyweightLb: Int): String = BackupCodec().encode(
        BackupDocument(
            settings = SettingsBackup(
                bodyweightLb = bodyweightLb, age = 33, level = "NOVICE", emphasis = "STRENGTH",
                cardioMode = "NONE", cardioPlacement = "NONE", fiveKGoal = false,
                daysPerWeek = 3, split = "FULL_BODY", anchorScheme = "BIG_4",
                deadliftVariant = "SUMO", equipment = listOf("BARBELL"), weightUnit = "LB",
                wizardComplete = true, suggestedDay = dayId,
            ),
            program = listOf(
                ProgramDayBackup(
                    dayId = dayId,
                    title = "Day $dayId",
                    emphasisLine = "Squat-focused",
                    exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", true, 4, "5/5/5/3", true, null, "")),
                ),
            ),
        ),
    )

    @Test
    fun reconciliationWaitsForAnInFlightRestoreInsteadOfReadingItsJournal() = runBlocking {
        val open = CompletableDeferred<Unit>()
        settingsStore.gateOnUpdate = 1
        settingsStore.gate = open

        val restore = storeScope.async { service.import(backupJson("A", 190)) }
        withTimeout(TIMEOUT_MS) { settingsStore.gateReached.await() }

        val reconcile = storeScope.async { service.reconcilePendingRestore() }
        // Long enough to have finished if nothing were holding it: the journal it
        // would read is mid-write, so it must still be at the door.
        delay(200)
        assertFalse("reconcile must not run beside a restore", reconcile.isCompleted)

        open.complete(Unit)
        withTimeout(TIMEOUT_MS) { restore.await() }
        val replayed = withTimeout(TIMEOUT_MS) { reconcile.await() }

        assertFalse("by its turn the restore had finished, so there was nothing left", replayed)
        assertEquals(listOf("A"), repo.programFlow.first().days.map { it.id })
        assertEquals(190, settings.configFlow.first().bodyweightLb)
        assertNull(db.restoreMarkerDao().nonce())
    }

    @Test
    fun twoRestoresBackToBackLeaveTheSecondOneWholeAndNothingOutstanding() = runBlocking {
        service.import(backupJson("A", 190))
        service.import(backupJson("B", 205))

        assertEquals(listOf("B"), repo.programFlow.first().days.map { it.id })
        assertEquals(205, settings.configFlow.first().bodyweightLb)
        assertEquals("B", settings.suggestedDayFlow.first())
        assertNull("the second restore cleared its own marker", db.restoreMarkerDao().nonce())
        assertFalse(service.reconcilePendingRestore())
    }

    @Test
    fun twoRestoresRacingTakeTurnsRatherThanInterleave() = runBlocking {
        val text = backupJson("A", 190)

        // Same document twice so the outcome is deterministic; what's under test
        // is that neither run sees the other's journal or marker half-written.
        withTimeout(TIMEOUT_MS) {
            listOf(
                storeScope.async { service.import(text) },
                storeScope.async { service.import(text) },
            ).awaitAll()
        }

        assertEquals(listOf("A"), repo.programFlow.first().days.map { it.id })
        assertEquals(190, settings.configFlow.first().bodyweightLb)
        assertTrue(settings.wizardCompleteFlow.first())
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse(service.reconcilePendingRestore())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
