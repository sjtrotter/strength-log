package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

/**
 * #172: a restore writes Room and then DataStore with no transaction across the
 * two, so what's pinned here is the *recovery*, not an atomicity the code can't
 * have. Both stores are instrumented, so a test can name the exact write that
 * dies, and the assertions are about where the device ends up: never restored
 * training data wearing the old device's config, never the backup's settings
 * over data that was never replaced, and never a wizard flag left stale enough
 * to reopen setup over a restored program.
 *
 * The truth table the recovery implements, staged payload × committed marker:
 *
 *  | journal        | marker    | means                                  | does        |
 *  |----------------|-----------|----------------------------------------|-------------|
 *  | none           | none      | nothing pending                        | nothing     |
 *  | payload+nonce  | none      | the transaction never committed        | discards    |
 *  | payload+nonce  | other     | payload outlived a finished restore    | discards    |
 *  | payload+nonce  | same      | data landed, settings didn't           | replays     |
 *  | none           | any       | settings landed, cleanup didn't finish | drops marker|
 *
 * Robolectric with a real in-memory Room DB and real DataStore files, because
 * the transaction boundary is the thing under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreJournalTest {

    private lateinit var db: StrengthDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var settingsStore: FlakyDataStore
    private lateinit var journalStore: FlakyDataStore
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsStore = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-journal-settings", ".preferences_pb")
            },
        )
        journalStore = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-journal", ".preferences_pb")
            },
        )
        settings = SettingsStore(settingsStore)
        journal = RestoreJournal(journalStore, settings)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    /** A backup from another device: a program Room can hold, and settings that
     *  differ from this device's in every value a GOAL is derived from. */
    private fun snapshot() = FullSnapshot(
        answers = WizardAnswers(config = LifterConfig(bodyweightLb = 190, age = 28)),
        unit = WeightUnit.KG,
        wizardComplete = true,
        suggestedDay = "R",
        restSettings = RestSettings(enabled = false, overrides = mapOf(RestCategory.TOP to 240)),
        keepScreenOn = true,
        customExercises = emptyList(),
        days = listOf(ProgramDayEntity("R", 0, "Restored", "from a backup", null)),
        exercises = listOf(
            ProgramExerciseEntity(1, "R", 0, "bb_back_squat", true, 4, "5/5/5/3", true, null, ""),
        ),
        logs = emptyList(),
        sessions = emptyList(),
        sessionSets = emptyList(),
    )

    private suspend fun assertSettingsRestored() {
        assertEquals(190, settings.configFlow.first().bodyweightLb)
        assertEquals(28, settings.configFlow.first().age)
        assertEquals(WeightUnit.KG, settings.unitFlow.first())
        assertTrue("the wizard flag is what keeps setup from reopening", settings.wizardCompleteFlow.first())
        assertEquals("R", settings.suggestedDayFlow.first())
        assertEquals(
            RestSettings(enabled = false, overrides = mapOf(RestCategory.TOP to 240)),
            settings.restSettingsFlow.first(),
        )
        assertTrue(settings.keepScreenOnFlow.first())
    }

    private suspend fun assertSettingsUntouched() {
        assertEquals(LifterConfig().bodyweightLb, settings.configFlow.first().bodyweightLb)
        assertFalse(settings.wizardCompleteFlow.first())
        assertNull(settings.suggestedDayFlow.first())
    }

    private suspend fun restoredProgramIsInRoom() =
        repo.programFlow.first().days.map { it.id } == listOf("R")

    // --- the whole path, uninterrupted ----------------------------------------

    @Test
    fun happyPath_leavesNoJournalAndNoMarker() = runTest {
        repo.importSnapshot(snapshot(), journal)

        assertSettingsRestored()
        assertTrue(restoredProgramIsInRoom())
        assertNull("the marker is bookkeeping, not residue", db.restoreMarkerDao().nonce())
        assertFalse("nothing is outstanding", repo.reconcilePendingRestore(journal))
    }

    // --- interrupted after the transaction committed ---------------------------

    @Test
    fun settingsFailureAfterTheCommit_isReportedByPhase_andReconcileConverges() = runTest {
        settingsStore.failOnUpdate = 1

        val thrown = runCatching { repo.importSnapshot(snapshot(), journal) }.exceptionOrNull()

        assertTrue(
            "a post-commit settings failure is not a problem with the picked file",
            thrown is RestoreInterruption.SettingsPending,
        )
        // The split state, exactly: restored program, old config.
        assertTrue(restoredProgramIsInRoom())
        assertSettingsUntouched()
        assertNotNull("the marker is what proves the data half landed", db.restoreMarkerDao().nonce())

        assertTrue("the journal survived the failure", repo.reconcilePendingRestore(journal))
        assertSettingsRestored()
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse("and is spent", repo.reconcilePendingRestore(journal))
    }

    @Test
    fun deathAfterTheTransaction_replaysFromTheStagedPayloadAndMatchingMarker() = runTest {
        // Process death in the gap the old design could not recover from: the
        // transaction committed, nothing after it ran. The marker was written
        // inside that transaction, so it is there — which is the whole point.
        val nonce = "nonce-from-the-committed-transaction"
        journal.stage(snapshot(), nonce)
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = nonce))

        assertTrue(repo.reconcilePendingRestore(journal))

        assertSettingsRestored()
        assertNull(db.restoreMarkerDao().nonce())
    }

    // --- interrupted before the transaction committed --------------------------

    @Test
    fun aStagedPayloadWithNoMarker_isDiscardedNotReplayed() = runTest {
        // Death before (or during) the destructive transaction: replaying here
        // would pair the *backup's* settings with this device's untouched data —
        // the same bug mirrored.
        journal.stage(snapshot(), "nonce-that-never-committed")

        assertFalse(repo.reconcilePendingRestore(journal))
        assertSettingsUntouched()
        assertTrue(repo.programFlow.first().days.isEmpty())
    }

    @Test
    fun aStagedPayloadWithSomeoneElsesMarker_isDiscarded() = runTest {
        journal.stage(snapshot(), "nonce-A")
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "nonce-B"))

        assertFalse(repo.reconcilePendingRestore(journal))
        assertSettingsUntouched()
        assertNull("the stale marker goes too", db.restoreMarkerDao().nonce())
    }

    @Test
    fun aMarkerWithNoStagedPayload_isJustDropped() = runTest {
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "orphan"))

        assertFalse(repo.reconcilePendingRestore(journal))
        assertSettingsUntouched()
        assertNull(db.restoreMarkerDao().nonce())
    }

    @Test
    fun nothingPending_isANoOp() = runTest {
        assertFalse(repo.reconcilePendingRestore(journal))
        assertSettingsUntouched()
    }

    // --- interrupted before anything was destroyed ------------------------------

    @Test
    fun aFailureStagingTheJournal_leavesTheDeviceExactlyAsItWas() = runTest {
        journalStore.failOnUpdate = 1

        val thrown = runCatching { repo.importSnapshot(snapshot(), journal) }.exceptionOrNull()

        assertTrue(
            "nothing was destroyed, and the copy has to say so",
            thrown is RestoreInterruption.NotStarted,
        )
        assertTrue("the transaction never ran", repo.programFlow.first().days.isEmpty())
        assertNull(db.restoreMarkerDao().nonce())
        assertSettingsUntouched()
        assertFalse("no half-written journal is left to replay", repo.reconcilePendingRestore(journal))
    }

    // --- interrupted after everything the user owns had landed ------------------

    @Test
    fun aFailureClearingTheJournal_saysTheRestoreLanded_andTidiesItselfNextLaunch() = runTest {
        // Journal writes in one restore: 1 payload, 2 nonce, 3 the post-success
        // clear. Failing 3 is a restore that fully worked with its paperwork left
        // on the desk.
        journalStore.failOnUpdate = 3

        val thrown = runCatching { repo.importSnapshot(snapshot(), journal) }.exceptionOrNull()

        assertTrue(thrown is RestoreInterruption.CleanupPending)
        assertTrue(restoredProgramIsInRoom())
        assertSettingsRestored()

        // The leftover pair still matches, so the next launch rewrites the same
        // values (a no-op the user can't see) and clears them.
        assertTrue(repo.reconcilePendingRestore(journal))
        assertSettingsRestored()
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse(repo.reconcilePendingRestore(journal))
    }

    // --- cancellation ------------------------------------------------------------

    // runBlocking, not runTest: this one waits on work handed to real dispatchers,
    // and runTest's virtual clock would fire the timeout the instant it idled.
    @Test
    fun cancellingMidRestore_cannotSplitTheTwoStores() = runBlocking {
        // The back press from the issue: the screen goes away while the settings
        // write is in flight. The write is uncancellable precisely so this can't
        // leave the restored program wearing the old config.
        val open = CompletableDeferred<Unit>()
        settingsStore.gateOnUpdate = 1
        settingsStore.gate = open
        val job = storeScope.launch { repo.importSnapshot(snapshot(), journal) }
        withTimeout(TIMEOUT_MS) { settingsStore.gateReached.await() }

        job.cancel()
        open.complete(Unit)
        withTimeout(TIMEOUT_MS) { job.join() }

        assertTrue(restoredProgramIsInRoom())
        assertSettingsRestored()
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse(repo.reconcilePendingRestore(journal))
    }

    // --- back-to-back restores ---------------------------------------------------

    @Test
    fun aSecondRestoreReplacesTheFirstsBookkeeping() = runTest {
        repo.importSnapshot(snapshot(), journal)
        val second = snapshot().copy(
            answers = WizardAnswers(config = LifterConfig(bodyweightLb = 205, age = 31)),
            days = listOf(ProgramDayEntity("S", 0, "Second", "from a later backup", null)),
            exercises = listOf(
                ProgramExerciseEntity(2, "S", 0, "bb_back_squat", true, 3, "5/5/5", false, null, ""),
            ),
            suggestedDay = "S",
        )

        repo.importSnapshot(second, journal)

        assertEquals(listOf("S"), repo.programFlow.first().days.map { it.id })
        assertEquals(205, settings.configFlow.first().bodyweightLb)
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse(repo.reconcilePendingRestore(journal))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
