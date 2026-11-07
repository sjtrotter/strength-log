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
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.ExperienceLevel
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
import org.junit.Assert.assertTrue
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

    @Test
    fun `generated cardio plans offer increases and start at A on insert and update`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        try {
            val settings = SettingsStore(PausingDataStore())
            val repo = PlanRepository(db, settings)
            repo.runPlanSettingsFixupIfNeeded()
            for (cardioFirst in listOf(false, true)) {
                val answers = WizardAnswers(cardioFirst = if (cardioFirst) CardioFirst() else null)
                for (update in listOf(false, true)) {
                    val emptyId = if (update) db.planDao().insertPlan(PlanEntity(
                        0, "Empty", PlanOrigin.GENERATED.name, 0, 1000,
                        offerCardioIncreases = !cardioFirst,
                    )).also { settings.setSelectedPlanId(it) } else null
                    val id = repo.createGeneratedPlan("Generated", answers, ProgramGenerator.generate(answers))
                    if (emptyId != null) assertEquals(emptyId, id)
                    val plan = db.planDao().plan(id)!!
                    assertEquals(cardioFirst, plan.offerCardioIncreases)
                    assertEquals("A", plan.suggestedDayId)
                    assertEquals(if (cardioFirst) 3 else 4, db.programDao().daysForPlan(id).size)
                    repo.publishCreatedPlan(id, answers)
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `publishing without lifter answers preserves the global lifter`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        try {
            val settings = SettingsStore(PausingDataStore())
            val repo = PlanRepository(db, settings)
            repo.runPlanSettingsFixupIfNeeded()
            val global = GlobalLifter(190.0, 55, ExperienceLevel.ADVANCED)
            settings.setGlobalLifter(global)
            val answers = WizardAnswers(cardioFirst = CardioFirst())
            val id = repo.createGeneratedPlan("Running", answers, ProgramGenerator.generate(answers))
            repo.publishCreatedPlan(id, answers, writeLifter = false)
            assertEquals(global, settings.globalLifterFlow.first())
            assertEquals(id, settings.selectedPlanIdFlow.first())
            assertTrue(db.planDao().plan(id)!!.onToday)
        } finally {
            db.close()
        }
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
            val repo = PlanRepository(db, settings)
            db.planDao().insertPlans(listOf(
                PlanEntity(1, "A", PlanOrigin.GENERATED.name, 0, 1_000),
                PlanEntity(2, "B", PlanOrigin.GENERATED.name, 1, 1_000),
            ))
            settings.setSelectedPlanId(1)
            repo.runPlanSettingsFixupIfNeeded()

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
