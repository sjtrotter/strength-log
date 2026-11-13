package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.theme.KeepScreenOn
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepScreenOnSettingsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsStore
    private lateinit var journalStore: DataStore<Preferences>
    private lateinit var journal: RestoreJournal
    private lateinit var backup: BackupRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("keep-screen-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        journalStore = PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("keep-screen-journal", ".preferences_pb")
        }
        journal = RestoreJournal(journalStore, settings)
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        backup = BackupRepository(db, settings, repo.plans, repo.history)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `a fresh store reads WHILE_TIMER`() = runTest {
        assertEquals(KeepScreenOn.WHILE_TIMER, settings.keepScreenOnFlow.first())
    }

    @Test fun `legacy true means workouts and false means timer`() = runTest {
        dataStore.edit { it[booleanPreferencesKey("keep_screen_on")] = true }
        assertEquals(KeepScreenOn.DURING_WORKOUTS, settings.keepScreenOnFlow.first())
        dataStore.edit { it[booleanPreferencesKey("keep_screen_on")] = false }
        assertEquals(KeepScreenOn.WHILE_TIMER, settings.keepScreenOnFlow.first())
    }

    @Test fun `a choice replaces and removes the legacy key`() = runTest {
        dataStore.edit { it[booleanPreferencesKey("keep_screen_on")] = true }
        settings.setKeepScreenOn(KeepScreenOn.OFF)
        assertEquals(KeepScreenOn.OFF, settings.keepScreenOnFlow.first())
        assertEquals("OFF", dataStore.data.first()[stringPreferencesKey("keep_screen")])
        assertFalse(dataStore.data.first().contains(booleanPreferencesKey("keep_screen_on")))
    }

    @Test fun `an unknown name falls back to the legacy boolean`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("keep_screen")] = "SOMETHING_NEW"
            it[booleanPreferencesKey("keep_screen_on")] = true
        }
        assertEquals(KeepScreenOn.DURING_WORKOUTS, settings.keepScreenOnFlow.first())
    }

    @Test fun `restore writes OFF`() = runTest {
        settings.restore(GlobalLifter(), WeightUnit.LB, true, restSettings = RestSettings(),
            keepScreenOn = KeepScreenOn.OFF)
        assertEquals(KeepScreenOn.OFF, settings.keepScreenOnFlow.first())
    }

    @Test fun `a journal from an older build replays the legacy boolean`() = runTest {
        journal.stage(backup.exportSnapshot(), "old")
        journalStore.edit {
            it.remove(stringPreferencesKey("keep_screen"))
            it[booleanPreferencesKey("keep_screen_on")] = true
        }
        settings.setKeepScreenOn(KeepScreenOn.OFF)
        assertTrue(journal.reconcile("old", {}, { _, _, id -> id }))
        assertEquals(KeepScreenOn.DURING_WORKOUTS, settings.keepScreenOnFlow.first())
    }
}
