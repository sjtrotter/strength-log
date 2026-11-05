package cloud.trotter.log.strength.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
import org.junit.Assert.assertNull
import org.junit.Test

class BodyweightSettingsTest {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val store = PreferenceDataStoreFactory.create(scope = scope) {
        File.createTempFile("bodyweight-settings", ".preferences_pb")
    }
    private val settings = SettingsStore(store)

    @After fun tearDown() = scope.cancel()

    @Test fun legacyWholePoundsReadWithoutAFinerKey() = runTest {
        store.edit { it[intPreferencesKey("bodyweight")] = 185 }
        assertEquals(185.0, settings.globalLifterFlow.first().bodyweightLb, 0.0)
    }

    @Test fun finerValueWinsAndAnEditRemovesTheLegacyKey() = runTest {
        store.edit {
            it[intPreferencesKey("bodyweight")] = 185
            it[longPreferencesKey("bodyweight_tenths_lb")] = 2105L
        }
        assertEquals(210.5, settings.globalLifterFlow.first().bodyweightLb, 0.0)
        settings.setGlobalLifter(GlobalLifter(bodyweightLb = 211.6))
        assertEquals(211.6, settings.globalLifterFlow.first().bodyweightLb, 0.0)
        assertNull(store.data.first()[intPreferencesKey("bodyweight")])
    }
}
