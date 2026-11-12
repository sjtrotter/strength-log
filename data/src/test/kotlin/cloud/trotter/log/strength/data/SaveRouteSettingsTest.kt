package cloud.trotter.log.strength.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SaveRouteSettingsTest {
    private lateinit var settings: SettingsStore
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("save-route-settings", ".preferences_pb")
        })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun saveRouteDefaultsOff() = runTest {
        assertFalse(settings.saveRouteFlow.first())
    }

    @Test fun savingRoutesPersistsOn() = runTest {
        settings.setSaveRoute(true)
        assertTrue(settings.saveRouteFlow.first())
    }
}
