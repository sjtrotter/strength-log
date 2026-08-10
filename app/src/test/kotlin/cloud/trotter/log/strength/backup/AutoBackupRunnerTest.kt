package cloud.trotter.log.strength.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
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

    @Test fun `partial exporter failure leaves prior snapshot intact`() = runTest {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        val prior = "{\"schemaVersion\":4,\"run\":1}".toByteArray()
        val documents = FakeDocuments(mutableMapOf(AUTO_BACKUP_FILE_NAME to prior))
        val runner = AutoBackupRunner(settings, {
            it.write("{\"schemaVersion\":".toByteArray())
            throw IOException("export interrupted")
        }, documents)

        assertEquals(AutoBackupRunResult.Retry, runner.run())
        assertArrayEquals(prior, documents.files.getValue(AUTO_BACKUP_FILE_NAME))
        assertArrayEquals(
            "{\"schemaVersion\":".toByteArray(),
            documents.files.getValue(AUTO_BACKUP_TEMP_FILE_NAME),
        )
    }

    @Test fun `complete orphaned temp is promoted on next run`() = runTest {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        val orphan = "{\"schemaVersion\":4,\"orphan\":true}".toByteArray()
        val documents = FakeDocuments(mutableMapOf(AUTO_BACKUP_TEMP_FILE_NAME to orphan))
        val runner = AutoBackupRunner(settings, { throw IOException("new export failed") }, documents)

        assertEquals(AutoBackupRunResult.Retry, runner.run())
        assertArrayEquals(orphan, documents.files.getValue(AUTO_BACKUP_FILE_NAME))
    }

    @Test fun `transient IO records failure and retries`() = runTest {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        val runner = runnerThrowing(IOException("provider busy"))

        assertEquals(AutoBackupRunResult.Retry, runner.run())
        assertTrue(settings.autoBackupSettingsFlow.first().lastAttemptFailed)
    }

    @Test fun `absent persisted permission disables`() = runTest {
        assertUnavailableDisables(AutoBackupPermissionAbsentException())
    }

    @Test fun `definitively missing tree disables`() = runTest {
        assertUnavailableDisables(AutoBackupTreeMissingException())
    }

    @Test fun `gone provider disables`() = runTest {
        assertUnavailableDisables(AutoBackupProviderGoneException())
    }

    @Test fun `security failure disables`() = runTest {
        assertUnavailableDisables(SecurityException("revoked"))
    }

    @Test fun `cancellation is rethrown without changing state`() = runTest {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        val runner = runnerThrowing(CancellationException("stopped"))

        try {
            runner.run()
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }
        val state = settings.autoBackupSettingsFlow.first()
        assertTrue(state.enabled)
        assertFalse(state.lastAttemptFailed)
    }

    private fun runnerThrowing(failure: Exception) = AutoBackupRunner(
        settings, {}, AutoBackupDocumentStore { _, _, _ -> throw failure },
    )

    private suspend fun assertUnavailableDisables(failure: Exception) {
        settings.enableAutoBackup("content://tree/folder", "Folder")
        assertEquals(AutoBackupRunResult.Disabled, runnerThrowing(failure).run())
        val state = settings.autoBackupSettingsFlow.first()
        assertFalse(state.enabled)
        assertTrue(state.permissionLost)
        assertTrue(state.lastAttemptFailed)
    }

    /** Commits every write immediately, even when the exporter later throws. */
    private class FakeDocuments(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : AutoBackupDocumentStore {
        var writeCount = 0

        override suspend fun replace(treeUri: String, fileName: String, write: suspend (OutputStream) -> Unit) {
            val tempName = "$fileName.tmp"
            files[tempName]?.let { orphan ->
                if (orphan.isCompleteJsonDocument()) files[fileName] = orphan
                files.remove(tempName)
            }
            files[tempName] = byteArrayOf()
            val out = object : OutputStream() {
                override fun write(value: Int) {
                    files[tempName] = files.getValue(tempName) + value.toByte()
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    files[tempName] = files.getValue(tempName) + bytes.copyOfRange(offset, offset + length)
                }
            }
            write(out)
            check(files.getValue(tempName).isCompleteJsonDocument())
            files[fileName] = files.getValue(tempName)
            files.remove(tempName)
            writeCount++
        }
    }
}
