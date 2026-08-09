package cloud.trotter.log.strength.ui.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.FlakyDataStore
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.backup.BackupCodec
import cloud.trotter.log.strength.transfer.backup.BackupDocument
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.backup.ProgramDayBackup
import cloud.trotter.log.strength.transfer.backup.ProgramExerciseBackup
import cloud.trotter.log.strength.transfer.backup.SettingsBackup
import cloud.trotter.log.strength.transfer.csv.CsvHistoryService
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Data/Backup screen's half of #172: what the user is told when a restore
 * stops part-way, and which operations shut the screen's exits.
 *
 * Real repository, real [BackupService], real dispatchers — both DataStores are
 * instrumented, because the two writes that can fail after the point of no
 * return live in different ones (the settings restore, and the journal clear
 * that follows it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupViewModelRestoreTest {

    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var storeScope: CoroutineScope

    /** Stands in for the injected app scope. A [SupervisorJob] like the real one
     *  (AppScopeModule): a failed restore must not take the scope down with it. */
    private lateinit var appScope: CoroutineScope
    private lateinit var settingsStore: FlakyDataStore
    private lateinit var journalStore: FlakyDataStore
    private lateinit var settings: SettingsStore
    private lateinit var viewModel: BackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsStore = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("backup-vm-settings", ".preferences_pb")
            },
        )
        journalStore = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("backup-vm-journal", ".preferences_pb")
            },
        )
        settings = SettingsStore(settingsStore)
        val repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        viewModel = BackupViewModel(
            context,
            BackupService(repo, RestoreJournal(journalStore, settings)),
            CsvHistoryService(repo),
            appScope,
        )
    }

    @After
    fun tearDown() {
        // Joined, not just cancelled: on real dispatchers a bare cancel() returns
        // before the import has let go of Room or DataStore's file.
        runBlocking {
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            appScope.coroutineContext.job.cancelAndJoin()
            storeScope.coroutineContext.job.cancelAndJoin()
        }
        db.close()
        Dispatchers.resetMain()
    }

    /** A minimal but valid backup: one day, one real catalog exercise. */
    private fun backupJson(): String = BackupCodec().encode(
        BackupDocument(
            settings = SettingsBackup(
                bodyweightLb = 190, age = 33, level = "NOVICE", emphasis = "STRENGTH",
                cardioMode = "NONE", cardioPlacement = "NONE", fiveKGoal = false,
                daysPerWeek = 3, split = "FULL_BODY", anchorScheme = "BIG_4",
                deadliftVariant = "SUMO", equipment = listOf("BARBELL"), weightUnit = "KG",
                wizardComplete = true,
            ),
            program = listOf(
                ProgramDayBackup(
                    dayId = "A",
                    title = "Day A",
                    emphasisLine = "Squat-focused",
                    exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", true, 4, "5/5/5/3", true, null, "")),
                ),
            ),
        ),
    )

    private fun pickable(text: String): Uri {
        val uri = Uri.parse("content://test.backups/${text.hashCode()}")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
        return uri
    }

    private suspend fun stageConfirmDialog() {
        viewModel.beginImportBackup(pickable(backupJson()))
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.pendingRestoreConfirm } }
    }

    private suspend fun awaitMessage(): StatusMessage =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.message != null }.message!! }

    // --- honest reporting ------------------------------------------------------

    @Test
    fun aSettingsFailureAfterTheDataLandsIsNotReportedAsAFileProblem() = runBlocking {
        stageConfirmDialog()
        settingsStore.failOnUpdate = 1

        viewModel.confirmRestore()

        val message = awaitMessage()
        assertTrue(message.isError)
        assertEquals(
            TransferErrorMessages.of(RestoreInterruption.SettingsPending(RuntimeException())),
            message.text,
        )
    }

    @Test
    fun aFailureStagingTheJournalSaysNothingChanged() = runBlocking {
        stageConfirmDialog()
        journalStore.failOnUpdate = 1

        viewModel.confirmRestore()

        val message = awaitMessage()
        assertTrue(message.isError)
        assertEquals(
            TransferErrorMessages.of(RestoreInterruption.NotStarted(RuntimeException())),
            message.text,
        )
        assertTrue("nothing was destroyed", db.programDao().allDays().isEmpty())
    }

    @Test
    fun aFailureClearingTheJournalReadsAsSuccess() = runBlocking {
        stageConfirmDialog()
        // Journal writes in one restore: 1 payload, 2 nonce, 3 the post-success
        // clear. Everything the user owns has landed by then.
        journalStore.failOnUpdate = 3

        viewModel.confirmRestore()

        val message = awaitMessage()
        assertFalse("the restore worked; this is a footnote, not a failure", message.isError)
        assertEquals(
            TransferErrorMessages.of(RestoreInterruption.CleanupPending(RuntimeException())),
            message.text,
        )
        assertEquals(190, settings.configFlow.first().bodyweightLb)
    }

    // --- what the exits are gated on -------------------------------------------

    @Test
    fun aRestoreHoldsTheExitsShutForItsWholeDuration() = runBlocking {
        stageConfirmDialog()
        val open = CompletableDeferred<Unit>()
        settingsStore.gateOnUpdate = 1
        settingsStore.gate = open

        viewModel.confirmRestore()
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.restoreInFlight } }

        // restoreInFlight is what the screen gates back on (BackupBusyGateTest),
        // and isBusy is what makes a second tap a no-op here.
        assertTrue(viewModel.uiState.value.isBusy)
        viewModel.exportBackup(Uri.parse("content://test.backups/never-opened"))
        assertTrue(viewModel.uiState.value.restoreInFlight)

        open.complete(Unit)
        val message = awaitMessage()
        assertEquals("Backup restored.", message.text)
        assertFalse("and the screen lets go again", viewModel.uiState.value.restoreInFlight)
    }

    @Test
    fun readingABackupFileIsBusyButDoesNotHoldTheExitsShut() = runBlocking {
        // Held open mid-read, which is the same "busy" the old gate keyed on —
        // but reading a file can't leave two stores disagreeing, so the exits
        // must stay open through it.
        val open = CompletableDeferred<Unit>()
        val uri = Uri.parse("content://test.backups/slow")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            BlockingInputStream(backupJson().toByteArray(Charsets.UTF_8), open)
        }

        viewModel.beginImportBackup(uri)

        assertTrue(viewModel.uiState.value.isBusy)
        assertFalse(
            "only a confirmed restore may trap the user on this screen",
            viewModel.uiState.value.restoreInFlight,
        )

        open.complete(Unit)
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.pendingRestoreConfirm } }
        assertFalse(viewModel.uiState.value.restoreInFlight)
    }

    /** Serves [bytes] only once [open] completes, so a test can hold a SAF read
     *  in flight and inspect the state the screen is rendering meanwhile. */
    private class BlockingInputStream(
        bytes: ByteArray,
        private val open: CompletableDeferred<Unit>,
    ) : java.io.InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int {
            runBlocking { open.await() }
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            runBlocking { open.await() }
            return delegate.read(b, off, len)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
