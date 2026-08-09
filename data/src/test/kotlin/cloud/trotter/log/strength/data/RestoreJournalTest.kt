package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.prefs.RestoreIncompleteException
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * #172: a restore writes Room and then DataStore with no transaction across the
 * two, so what's pinned here is the *recovery*, not an atomicity the code can't
 * have. Failures are injected at the settings DataStore — the seam that actually
 * broke — and the assertions are about where the device ends up: never restored
 * training data wearing the old device's config, and never a wizard flag left
 * behind to reopen setup over a restored program.
 *
 * Robolectric with a real in-memory Room DB and real DataStore files, because
 * the transaction boundary is the thing under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreJournalTest {

    /**
     * The settings store, with a way to make one write fail or park. Standing in
     * for the two real interruptions: an I/O failure, and a process that dies
     * after the Room transaction commits.
     */
    private class FlakyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = delegate.data

        @Volatile var failNextUpdate = false
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val gateReached = CompletableDeferred<Unit>()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            gate?.let { open ->
                gate = null
                gateReached.complete(Unit)
                open.await()
            }
            if (failNextUpdate) {
                failNextUpdate = false
                throw IOException("settings store is down")
            }
            return delegate.updateData(transform)
        }
    }

    private lateinit var db: StrengthDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var flaky: FlakyDataStore
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
        flaky = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-journal-settings", ".preferences_pb")
            },
        )
        settings = SettingsStore(flaky)
        journal = RestoreJournal(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-journal", ".preferences_pb")
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

    @Test
    fun happyPath_leavesNoJournalBehind() = runTest {
        repo.importSnapshot(snapshot(), journal)

        assertSettingsRestored()
        assertEquals(listOf("R"), repo.programFlow.first().days.map { it.id })
        assertFalse("nothing is outstanding", journal.reconcile())
    }

    @Test
    fun settingsFailureAfterTheRoomCommit_isReportedHonestly_andReconcileConverges() = runTest {
        flaky.failNextUpdate = true

        val thrown = runCatching { repo.importSnapshot(snapshot(), journal) }.exceptionOrNull()

        assertTrue(
            "a post-commit settings failure is not a problem with the picked file",
            thrown is RestoreIncompleteException,
        )
        // The split state, exactly: restored program, old config.
        assertEquals(listOf("R"), repo.programFlow.first().days.map { it.id })
        assertEquals(LifterConfig().bodyweightLb, settings.configFlow.first().bodyweightLb)
        assertFalse(settings.wizardCompleteFlow.first())

        assertTrue("the journal survived the failure", journal.reconcile())
        assertSettingsRestored()
        assertFalse("and is spent", journal.reconcile())
    }

    // runBlocking, not runTest: this one waits on work handed to real dispatchers,
    // and runTest's virtual clock would fire the timeout the instant it idled.
    @Test
    fun cancellingMidRestore_cannotSplitTheTwoStores() = runBlocking {
        // The back press from the issue: the screen goes away while the settings
        // write is in flight. The write is uncancellable precisely so this can't
        // leave the restored program wearing the old config.
        val open = CompletableDeferred<Unit>()
        flaky.gate = open
        val job = storeScope.launch { repo.importSnapshot(snapshot(), journal) }
        withTimeout(TIMEOUT_MS) { flaky.gateReached.await() }

        job.cancel()
        open.complete(Unit)
        withTimeout(TIMEOUT_MS) { job.join() }

        assertEquals(listOf("R"), repo.programFlow.first().days.map { it.id })
        assertSettingsRestored()
        assertFalse(journal.reconcile())
    }

    @Test
    fun aStagedRestoreWhoseRoomHalfNeverCommitted_isDiscarded_notReplayed() = runTest {
        // Death before the destructive transaction: replaying here would pair the
        // *backup's* settings with this device's untouched data — the same bug
        // mirrored — so an unarmed journal is dropped.
        journal.stage(snapshot())

        assertFalse(journal.reconcile())
        assertEquals(LifterConfig().bodyweightLb, settings.configFlow.first().bodyweightLb)
        assertFalse(settings.wizardCompleteFlow.first())
        assertNull(settings.suggestedDayFlow.first())
        assertTrue(repo.programFlow.first().days.isEmpty())
    }

    @Test
    fun replayingTheSamePayloadTwiceLandsTheSameSettings() = runTest {
        journal.stage(snapshot())
        journal.arm()
        assertTrue(journal.reconcile())
        assertSettingsRestored()

        // The one window the design leaves open: the settings edit lands but the
        // journal never gets cleared, so the next launch replays it. Writing the
        // same values twice has to be a no-op, and it is.
        journal.stage(snapshot())
        journal.arm()
        assertTrue(journal.reconcile())
        assertSettingsRestored()
        assertFalse(journal.reconcile())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
