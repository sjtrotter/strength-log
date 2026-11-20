package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.units.DistanceUnit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioRunStoreTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: FailingDataStore
    private lateinit var prefs: CardioRunPrefs
    private lateinit var store: PrefsCardioRunStore

    @Before fun setUp() {
        storeScope = CoroutineScope(dispatcher + Job())
        dataStore = FailingDataStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-run-store", ".preferences_pb")
        })
        prefs = CardioRunPrefs(dataStore)
        store = PrefsCardioRunStore(prefs, storeScope)
    }

    @After fun tearDown() { storeScope.cancel() }

    @Test fun `load sees a save that is not written yet`() = runTest(dispatcher) {
        val copy = stored()
        store.save(copy)
        assertEquals(copy, store.load(1L, "A"))
    }

    @Test fun `the last write for a day wins`() = runTest(dispatcher) {
        val first = stored()
        val second = first.copy(savedAt = 2L, run = first.run.copy(saveFailed = true))
        store.save(first)
        store.save(second)
        assertEquals(second, store.load(1L, "A"))
        store.save(first)
        store.delete(1L, "A")
        assertNull(store.load(1L, "A"))
    }

    @Test fun `days keep separate copies`() = runTest(dispatcher) {
        val first = stored()
        val second = first.copy(run = first.run.copy(dayId = "B", dayIndex = 1, runId = "second"))
        store.save(first)
        store.save(second)
        assertEquals(first, store.load(1L, "A"))
        assertEquals(second, store.load(1L, "B"))
    }

    @Test fun `a copy keeps every field`() = runTest(dispatcher) {
        val copy = stored().let { stored ->
            val run = stored.run.copy(
                results = listOf(SavedCardioResult("FINISHED", 98, 400)),
                startedAt = 1_000L, wallStart = 2_000L, elapsedStart = 3_000L, bootCount = 4,
                drafts = listOf(CardioDistanceDraft(0, true, "400", true, true, true, true)),
                completedAt = 99_000L, sessionId = 5L, saveFailed = true, showReceipt = false,
            )
            stored.copy(run = run,
                gpsSets = listOf(CardioGpsSet(run.runId, 0, 410, 400, 98, true, true)),
                sprintTimes = listOf(CardioSprintTime(run.runId, 0, 9800, "HAND", "STANDING")),
                readingInputs = listOf(CardioReadingInput(run.runId, 0, "0.4", DistanceUnit.KM)),
                runDistance = CardioRunDistance(run.runId, 1, "1200"))
        }
        store.save(copy)
        assertEquals(copy, store.load(1L, "A"))
    }

    @Test fun `an unreadable copy loads as nothing`() = runTest(dispatcher) {
        prefs.set(1L, "A", "{")
        assertNull(store.load(1L, "A"))
    }

    @Test fun `a failed delete hides the saved copy`() = runTest(dispatcher) {
        store.save(stored())
        assertEquals(stored(), store.load(1L, "A"))
        dataStore.failWrites = true
        store.delete(1L, "A")
        runCurrent()
        assertNull(store.load(1L, "A"))
        assertEquals(1, dataStore.data.first().asMap().size)
    }

    @Test fun `a failed save loads the pending value`() = runTest(dispatcher) {
        val first = stored()
        store.save(first)
        assertEquals(first, store.load(1L, "A"))
        val latest = first.copy(savedAt = 2L, run = first.run.copy(saveFailed = true))
        dataStore.failWrites = true
        store.save(latest)
        runCurrent()
        assertEquals(latest, store.load(1L, "A"))
        dataStore.failWrites = false
        assertEquals(latest, store.load(1L, "A"))
    }

    @Test fun `a backup restore drops a retained failed save`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("cardio-store-settings", ".preferences_pb")
            })
            val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
            val backup = BackupRepository(db, settings, repo.plans, repo.history, cardioRunPrefs = prefs)
            val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("cardio-store-journal", ".preferences_pb")
            }, settings, prefs)
            val snapshot = backup.exportSnapshot()
            dataStore.failWrites = true
            store.save(stored())
            runCurrent()
            dataStore.failWrites = false

            backup.importSnapshot(snapshot, journal)
            assertNull(store.load(1L, "A"))
            runCurrent()
            assertNull(store.load(1L, "A"))
        } finally {
            db.close()
        }
    }

    private class FailingDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> by delegate {
        var failWrites = false

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("cardio write failed")
            return delegate.updateData(transform)
        }
    }

    private fun stored() = StoredCardioRun(1L, CardioRun(
        planId = 1L, planName = "Running", dayId = "A", dayIndex = 0,
        sourceBlockJson = "source", blockJson = "frozen", finishesDay = true,
        nextDayTitle = "Day B, Run", runId = "first",
    ))
}
