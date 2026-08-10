package cloud.trotter.log.strength.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoBackupRunnerTest {
    private lateinit var settings: SettingsStore
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("auto-backup-runner", ".preferences_pb")
        })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `writes current bytes and replaces the rolling document`() = runTest {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        val documents = FakeDocuments()
        var bytes = "{\"schemaVersion\":4,\"run\":1}".toByteArray()
        val runner = AutoBackupRunner(settings, { it.write(bytes) }, documents) { 10L }

        assertEquals(AutoBackupRunResult.Success, runner.run())
        bytes = "{\"schemaVersion\":4,\"run\":2}".toByteArray()
        assertEquals(AutoBackupRunResult.Success, runner.run())

        assertEquals(setOf(AUTO_BACKUP_FILE_NAME), documents.files.keys)
        assertArrayEquals(bytes, documents.files.getValue(AUTO_BACKUP_FILE_NAME))
        assertEquals(2, documents.writeCount)
    }

    @Test fun `ordinary write failure records failure and retries`() = runTest {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        val runner = AutoBackupRunner(settings, {}, AutoBackupDocumentStore { _, _, _ -> error("provider down") })

        assertEquals(AutoBackupRunResult.Retry, runner.run())
        assertTrue(settings.autoBackupSettingsFlow.first().lastAttemptFailed)
    }

    @Test fun `revoked permission disables without retrying`() = runTest {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        val runner = AutoBackupRunner(settings, {}, AutoBackupDocumentStore { _, _, _ -> throw SecurityException("revoked") })

        assertEquals(AutoBackupRunResult.Disabled, runner.run())
        val state = settings.autoBackupSettingsFlow.first()
        assertFalse(state.enabled)
        assertTrue(state.permissionLost)
    }

    private class FakeDocuments : AutoBackupDocumentStore {
        val files = mutableMapOf<String, ByteArray>()
        var writeCount = 0
        override suspend fun overwrite(treeUri: String, fileName: String, write: suspend (OutputStream) -> Unit) {
            val out = ByteArrayOutputStream()
            write(out)
            files[fileName] = out.toByteArray()
            writeCount++
        }
    }
}
