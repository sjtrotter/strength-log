package cloud.trotter.log.strength.playtest

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.transfer.backup.BackupCodec
import cloud.trotter.log.strength.transfer.backup.BackupService
import java.io.File
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
class BackupExportTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun backupExportWaitsForSetupCompletion() {
        withBackup { backup, settings, repo, scope ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer = scope.launch {
                backup.withRestoreLock {
                    repo.replaceProgram(Program(listOf(ProgramDay("A", "Workout", "", listOf(
                        ProgramExercise("bb_back_squat", targetSets = 1),
                    )))))
                    entered.complete(Unit)
                    release.await()
                    settings.setWizardComplete(true)
                }
            }
            try {
                waitForStorage(10_000) { entered.await() }
                val artifacts = RunArtifacts(temporary.newFolder("artifacts"), 0)
                val deadline = StepDeadline(10_000, operation = "Backup export")
                deadline.run(compose) {
                    artifacts.writeBackup(backup, deadline) {
                        scope.launch {
                            delay(100)
                            release.complete(Unit)
                        }
                    }
                }
                val document = BackupCodec().decode(File(artifacts.directory, "backup.json").readText())
                assertTrue(document.settings.wizardComplete)
                assertEquals("Workout", document.plans.single().days.single().title)
                waitForStorage(10_000) { writer.join() }
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test fun backupExportSetupWaitTimesOutWithoutWritingArtifact() {
        withBackup { backup, _, _, scope ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            scope.launch {
                backup.withRestoreLock {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                waitForStorage(10_000) { entered.await() }
                assertExportTimeout(backup) {}
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test fun backupExportPendingWriteWaitSharesTheDeadline() {
        withBackup { backup, _, _, _ ->
            val pending = CompletableDeferred<Unit>()
            assertExportTimeout(backup) { pending.await() }
        }
    }

    private fun assertExportTimeout(backup: BackupService, awaitPendingWrites: suspend () -> Unit) {
        val artifacts = RunArtifacts(temporary.newFolder("artifacts"), 0)
        val deadline = StepDeadline(1_000, operation = "Backup export")
        val error = assertFailsWith<PlaytestError> {
            deadline.run(compose) { artifacts.writeBackup(backup, deadline, awaitPendingWrites) }
        }
        assertEquals("timeout", error.kind)
        assertTrue(requireNotNull(error.message).contains("Backup export"))
        assertFalse(File(artifacts.directory, "backup.json").exists())
        assertFalse(File(artifacts.directory, "backup.json.tmp").exists())
    }

    private fun withBackup(block: (BackupService, SettingsStore, TrackerRepository, CoroutineScope) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).build()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File(temporary.root, "settings.preferences_pb")
            })
            val cardioRunPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = scope) {
                File(temporary.root, "cardio_runs.preferences_pb")
            })
            val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
                File(temporary.root, "journal.preferences_pb")
            }, settings, cardioRunPrefs = cardioRunPrefs)
            val repo = TrackerRepository(database, database.programDao(), database.sessionDao(),
                database.customExerciseDao(), settings)
            val backup = BackupService(BackupRepository(database, settings, repo.plans, repo.history, cardioRunPrefs = cardioRunPrefs), journal)
            compose.mainClock.autoAdvance = false
            compose.setContent { Text("Export") }
            block(backup, settings, repo, scope)
        } finally {
            scope.cancel()
            database.close()
        }
    }
}
