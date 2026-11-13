package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.theme.KeepScreenOn
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
class EffortScaleSettingsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var journalStore: DataStore<Preferences>
    private lateinit var journal: RestoreJournal
    private lateinit var backup: BackupRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("effort-settings", ".preferences_pb")
        })
        journalStore = PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("effort-journal", ".preferences_pb")
        }
        journal = RestoreJournal(journalStore, settings)
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        backup = BackupRepository(db, settings, repo.plans, repo.history)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun restore(scale: EffortScale) {
        settings.restore(GlobalLifter(), WeightUnit.LB, true, restSettings = RestSettings(),
            keepScreenOn = KeepScreenOn.WHILE_TIMER, effortScale = scale)
    }

    @Test fun `a fresh store reads WORDS`() = runTest {
        assertEquals(EffortScale.WORDS, settings.effortScaleFlow.first())
    }
    @Test fun `restore reads REPS_LEFT`() = runTest {
        restore(EffortScale.REPS_LEFT)
        assertEquals(EffortScale.REPS_LEFT, settings.effortScaleFlow.first())
    }
    @Test fun `a journal replays the staged effort scale with its nonce`() = runTest {
        val snapshot = backup.exportSnapshot().copy(effortScale = EffortScale.REPS_LEFT)
        journal.stage(snapshot, "nonce")
        assertTrue(journal.reconcile("nonce", {}, { _, _, id -> id }))
        assertEquals(EffortScale.REPS_LEFT, settings.effortScaleFlow.first())
    }
    @Test fun `a journal from an older build replays WORDS when the key is absent`() = runTest {
        journal.stage(backup.exportSnapshot(), "old")
        journalStore.edit { it.remove(stringPreferencesKey("effort_scale")) }
        restore(EffortScale.REPS_LEFT)
        assertTrue(journal.reconcile("old", {}, { _, _, id -> id }))
        assertEquals(EffortScale.WORDS, settings.effortScaleFlow.first())
    }
    @Test fun `exportSnapshot carries the effort scale`() = runTest {
        restore(EffortScale.REPS_LEFT)
        assertEquals(EffortScale.REPS_LEFT, backup.exportSnapshot().effortScale)
    }
}
