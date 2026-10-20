package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.prefs.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryPlanSelectionTest {
    @Test
    fun `archive holds selection stable from the settings read through commit`() = runTest {
        raceSelectionAndArchive(archiveFirst = true)
    }

    @Test
    fun `select holds archive until the settings write commits`() = runTest {
        raceSelectionAndArchive(archiveFirst = false)
    }

    private suspend fun TestScope.raceSelectionAndArchive(archiveFirst: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        try {
            val store = PausingDataStore()
            val settings = SettingsStore(store)
            val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
            db.planDao().insertPlans(listOf(
                PlanEntity(1, "A", PlanOrigin.GENERATED.name, 0, 1_000),
                PlanEntity(2, "B", PlanOrigin.GENERATED.name, 1, 1_000),
            ))
            settings.setSelectedPlanId(1)

            store.pauseRead = archiveFirst
            store.pauseWrite = !archiveFirst
            val first = async {
                if (archiveFirst) repo.archivePlan(2) else {
                    repo.selectPlan(2)
                    false
                }
            }
            store.reached.await()
            val second = launch {
                if (archiveFirst) repo.selectPlan(2) else repo.archivePlan(2)
            }
            runCurrent()
            try {
                assertFalse("the competing operation must wait for the selection lock", second.isCompleted)
            } finally {
                store.release.complete(Unit)
            }
            assertEquals(archiveFirst, first.await())
            second.join()

            assertEquals(archiveFirst, db.planDao().plan(2)!!.archived)
            assertEquals(if (archiveFirst) 1L else 2L, settings.selectedPlanIdFlow.first())
        } finally {
            db.close()
        }
    }

    /** Parks after a read captured its snapshot, or before a write publishes it. */
    private class PausingDataStore : DataStore<Preferences> {
        private val preferences = MutableStateFlow(emptyPreferences())
        private val writeLock = Mutex()
        var pauseRead = false
        var pauseWrite = false
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override val data = preferences.map { snapshot ->
            if (pauseRead) {
                pauseRead = false
                reached.complete(Unit)
                release.await()
            }
            snapshot
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = writeLock.withLock {
            if (pauseWrite) {
                pauseWrite = false
                reached.complete(Unit)
                release.await()
            }
            transform(preferences.value).also { preferences.value = it }
        }
    }
}
