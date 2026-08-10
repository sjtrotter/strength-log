package cloud.trotter.log.strength.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.data.prefs.AUTO_BACKUP_DAILY_HOURS
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoBackupSettingsTest {
    private lateinit var settings: SettingsStore
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("auto-backup-settings", ".preferences_pb")
        })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `automatic backup settings round trip and clear`() = runTest {
        assertFalse(settings.autoBackupSettingsFlow.first().enabled)
        settings.enableAutoBackup("content://tree/folder", "My backups")
        settings.recordAutoBackupSuccess(1234L)

        val saved = settings.autoBackupSettingsFlow.first()
        assertTrue(saved.enabled)
        assertEquals("content://tree/folder", saved.treeUri)
        assertEquals("My backups", saved.folderName)
        assertEquals(AUTO_BACKUP_DAILY_HOURS, saved.cadenceHours)
        assertEquals(1234L, saved.lastSuccessAtMillis)

        settings.disableAutoBackup()
        val cleared = settings.autoBackupSettingsFlow.first()
        assertFalse(cleared.enabled)
        assertNull(cleared.treeUri)
        assertNull(cleared.folderName)
    }
}
