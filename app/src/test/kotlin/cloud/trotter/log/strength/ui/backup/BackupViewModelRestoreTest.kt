package cloud.trotter.log.strength.ui.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
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
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Data/Backup screen's half of #172: what the user is told when a restore's
 * settings write fails after its data has already landed, and that the screen
 * stays busy (and therefore shut) for the whole operation.
 *
 * Real repository, real [BackupService], real dispatchers — only the settings
 * DataStore is instrumented, because that is the write that can fail after the
 * point of no return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupViewModelRestoreTest {

    /** The settings store, with a way to make one write fail or park. */
    private class FlakyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = delegate.data

        @Volatile var failNextUpdate = false
        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            gate?.let { open ->
                gate = null
                open.await()
            }
            if (failNextUpdate) {
                failNextUpdate = false
                throw IOException("settings store is down")
            }
            return delegate.updateData(transform)
        }
    }

    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var storeScope: CoroutineScope

    /** Stands in for the injected app scope. A [SupervisorJob] like the real one
     *  (AppScopeModule): a failed restore must not take the scope down with it. */
    private lateinit var appScope: CoroutineScope
    private lateinit var flaky: FlakyDataStore
    private lateinit var viewModel: BackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        flaky = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("backup-vm-settings", ".preferences_pb")
            },
        )
        val settings = SettingsStore(flaky)
        val repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        val journal = RestoreJournal(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("backup-vm-journal", ".preferences_pb")
            },
            settings,
        )
        viewModel = BackupViewModel(
            context,
            BackupService(repo, journal),
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

    @Test
    fun aSettingsFailureAfterTheDataLandsIsNotReportedAsAFileProblem() = runBlocking {
        stageConfirmDialog()
        flaky.failNextUpdate = true

        viewModel.confirmRestore()

        val message = withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.message != null }.message!! }
        assertTrue(message.isError)
        assertEquals(TransferErrorMessages.RESTORE_INCOMPLETE, message.text)
    }

    @Test
    fun theScreenStaysBusyForTheWholeRestore() = runBlocking {
        stageConfirmDialog()
        val open = CompletableDeferred<Unit>()
        flaky.gate = open

        viewModel.confirmRestore()
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.isBusy } }

        // isBusy is what the screen gates its back chevron and BackHandler on
        // (BackupBusyGateTest), and what makes a second tap a no-op here.
        viewModel.exportBackup(Uri.parse("content://test.backups/never-opened"))
        assertTrue(viewModel.uiState.value.isBusy)

        open.complete(Unit)
        val message = withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.message != null }.message!! }
        assertEquals("Backup restored.", message.text)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
