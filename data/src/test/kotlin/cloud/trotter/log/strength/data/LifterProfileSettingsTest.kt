package cloud.trotter.log.strength.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.theme.KeepScreenOn
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Test

class LifterProfileSettingsTest {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val store = PreferenceDataStoreFactory.create(scope = scope) {
        File.createTempFile("lifter-profile-settings", ".preferences_pb")
    }
    private val settings = SettingsStore(store)

    @After fun tearDown() = scope.cancel()

    @Test fun `an empty store reads an empty profile`() = runTest {
        assertEquals(LifterProfile(null, null), settings.lifterProfileFlow.first())
    }

    @Test fun `bodyweight alone stays alone`() = runTest {
        settings.setLifterProfile(LifterProfile(bodyweightLb = 180.5))
        assertEquals(LifterProfile(180.5, null), settings.lifterProfileFlow.first())
        assertNull(store.data.first()[intPreferencesKey("age")])
    }

    @Test fun `a whole pound bodyweight from an old build still reads`() = runTest {
        store.edit { it[intPreferencesKey("bodyweight")] = 200 }
        assertEquals(LifterProfile(200.0, null), settings.lifterProfileFlow.first())
    }

    @Test fun `a profile write retires the level key`() = runTest {
        store.edit {
            it[stringPreferencesKey("level")] = "NOVICE"
            it[intPreferencesKey("age")] = 40
        }
        settings.updateLifterProfile { it.copy(age = 41) }
        val prefs = store.data.first()
        assertNull(prefs[stringPreferencesKey("level")])
        assertEquals(41, prefs[intPreferencesKey("age")])
    }

    @Test fun `clearing bodyweight removes both bodyweight keys`() = runTest {
        settings.setLifterProfile(LifterProfile(180.0, 40))
        store.edit { it[intPreferencesKey("bodyweight")] = 180 }
        settings.setLifterProfile(LifterProfile(null, 40))
        val prefs = store.data.first()
        assertNull(prefs[intPreferencesKey("bodyweight")])
        assertNull(prefs[longPreferencesKey("bodyweight_tenths_lb")])
        assertEquals(LifterProfile(null, 40), settings.lifterProfileFlow.first())
    }

    @Test fun `restore writes only the profile it is given`() = runTest {
        settings.setLifterProfile(LifterProfile(180.0, 40))
        settings.restore(profile = LifterProfile(age = 70), unit = WeightUnit.LB, wizardComplete = true,
            restSettings = RestSettings(), keepScreenOn = KeepScreenOn.WHILE_TIMER)
        assertEquals(LifterProfile(null, 70), settings.lifterProfileFlow.first())
    }
}
