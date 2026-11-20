package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioRunPrefsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: CardioRunPrefs
    private lateinit var backup: BackupRepository
    private lateinit var journal: RestoreJournal

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-prefs-settings", ".preferences_pb")
        })
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-prefs", ".preferences_pb")
        }
        prefs = CardioRunPrefs(dataStore)
        journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-prefs-journal", ".preferences_pb")
        }, settings, prefs)
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        backup = BackupRepository(db, settings, repo.plans, repo.history, cardioRunPrefs = prefs)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `a saved cardio run reads back by plan and day`() = runTest {
        prefs.set(1L, "A", "first")
        prefs.set(1L, "B", "second")
        prefs.set(2L, "A", "third")
        assertEquals("first", prefs.get(1L, "A"))
        assertEquals("second", prefs.get(1L, "B"))
        assertEquals("third", prefs.get(2L, "A"))
        prefs.set(1L, "A", null)
        assertNull(prefs.get(1L, "A"))
        assertEquals("second", prefs.get(1L, "B"))
        assertEquals("third", prefs.get(2L, "A"))
    }

    @Test fun `restore drops saved cardio runs`() = runTest {
        val snapshot = backup.exportSnapshot()
        prefs.set(1L, "A", "before restore")
        backup.importSnapshot(snapshot, journal)
        assertNull(prefs.get(1L, "A"))
        assertTrue(dataStore.data.first().asMap().keys.none { it.name.startsWith("cardio_run:") })

        prefs.set(1L, "B", "before recovery")
        journal.stage(snapshot, "nonce")
        assertTrue(journal.reconcile("nonce", {}, { _, _, id -> id }))
        assertNull(prefs.get(1L, "B"))
        assertTrue(dataStore.data.first().asMap().keys.none { it.name.startsWith("cardio_run:") })
    }
}
