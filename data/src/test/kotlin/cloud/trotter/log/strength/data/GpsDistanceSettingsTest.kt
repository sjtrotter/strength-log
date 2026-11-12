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

class GpsDistanceSettingsTest {
    private lateinit var settings: SettingsStore
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("gps-distance-settings", ".preferences_pb")
        })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun gpsDistanceDefaultsOn() = runTest {
        assertTrue(settings.gpsDistanceFlow.first())
    }

    @Test fun decliningGpsDistancePersistsOff() = runTest {
        settings.setGpsDistance(false)
        assertFalse(settings.gpsDistanceFlow.first())
    }
}
