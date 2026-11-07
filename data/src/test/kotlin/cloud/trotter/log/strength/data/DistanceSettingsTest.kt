package cloud.trotter.log.strength.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightUnit
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
import org.junit.Before
import org.junit.Test

class DistanceSettingsTest {
    private lateinit var settings: SettingsStore
    private lateinit var store: DataStore<Preferences>
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        store = PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("distance-settings", ".preferences_pb")
        }
        settings = SettingsStore(store)
    }

    @After fun tearDown() = scope.cancel()

    @Test fun absentDistanceFollowsWeightUntilChosen() = runTest {
        assertNull(settings.storedDistanceUnitFlow.first())
        assertEquals(DistanceUnit.MI, settings.distanceUnitFlow.first())
        settings.setUnit(WeightUnit.KG)
        assertEquals(DistanceUnit.KM, settings.distanceUnitFlow.first())
        assertNull(settings.storedDistanceUnitFlow.first())
        settings.setUnit(WeightUnit.LB)
        settings.setDistanceUnit(DistanceUnit.KM)
        assertEquals(DistanceUnit.KM, settings.storedDistanceUnitFlow.first())
        assertEquals(DistanceUnit.KM, settings.distanceUnitFlow.first())
        settings.setUnit(WeightUnit.KG)
        settings.setDistanceUnit(DistanceUnit.MI)
        assertEquals(DistanceUnit.MI, settings.distanceUnitFlow.first())
    }

    @Test fun displayOnlyAndUnknownStoredUnitsFollowWeight() = runTest {
        settings.setUnit(WeightUnit.KG)
        for (name in listOf("M", "unknown")) {
            store.edit { it[stringPreferencesKey("distance_unit")] = name }
            assertNull(settings.storedDistanceUnitFlow.first())
            assertEquals(DistanceUnit.KM, settings.distanceUnitFlow.first())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun displayOnlyMetresCannotBeChosen() = runTest {
        settings.setDistanceUnit(DistanceUnit.M)
    }
}
