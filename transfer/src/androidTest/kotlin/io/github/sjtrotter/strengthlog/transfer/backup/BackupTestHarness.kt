package io.github.sjtrotter.strengthlog.transfer.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before

/**
 * A real on-disk Room DB + DataStore + [TrackerRepository] + [BackupService],
 * mirroring `:data`'s persistence tests. The backup contract has to run against
 * the actual data layer (transactions, DataStore edits), so these tests are
 * instrumented; CI runs them via `:transfer:connectedDebugAndroidTest`.
 */
abstract class BackupTestHarness {

    private val dbName = "backup_test.db"
    private val prefsName = "backup_test_prefs"

    protected lateinit var db: StrengthDatabase
    private lateinit var dataStoreScope: CoroutineScope
    protected lateinit var repository: TrackerRepository
    protected lateinit var service: BackupService

    @Before
    fun setUp() {
        cleanUpFiles()
        db = Room.databaseBuilder(context(), StrengthDatabase::class.java, dbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context().preferencesDataStoreFile(prefsName) },
        )
        repository = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
        service = BackupService(repository)
    }

    @After
    fun tearDown() {
        runBlocking {
            db.close()
            dataStoreScope.coroutineContext.job.cancelAndJoin()
        }
        cleanUpFiles()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun cleanUpFiles() {
        val context = context()
        context.deleteDatabase(dbName)
        context.preferencesDataStoreFile(prefsName).delete()
    }
}
