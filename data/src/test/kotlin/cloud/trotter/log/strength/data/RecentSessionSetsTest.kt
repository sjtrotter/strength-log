package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.SetKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecentSessionSetsTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: HistoryRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("recent-session-settings", ".preferences_pb")
        }
        val settings = SettingsStore(dataStore)
        repo = HistoryRepository(
            db = db,
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
            plans = PlanRepository(db, settings),
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun session(at: Long, exercise: String = "side_plank", seconds: Int): Long {
        val id = db.sessionDao().insertSession(WorkoutSessionEntity(
            id = 0, dayId = "A", dayTitle = "Sides", startedAt = null, completedAt = at, bodyweightLb = 180.0))
        db.sessionDao().insertSets(listOf(true, false, true).mapIndexed { index, done ->
            SessionSetEntity(id = 0, sessionId = id, exerciseId = exercise, exerciseName = exercise,
                slot = Slot.MAIN, setIndex = index, kind = SetKind.WORK.name, weightLb = 0.0, reps = 0,
                done = done, seconds = seconds + index)
        })
        return id
    }

    @Test fun `recent sessions stop at the receipt and keep done sets in newest order`() = runTest {
        session(1000, seconds = 10)
        session(2000, seconds = 20)
        val third = session(3000, seconds = 30)
        session(4000, seconds = 40)
        session(2500, exercise = "plank", seconds = 99)
        session(3000, seconds = 50)
        val recent = repo.recentSessionSets("side_plank", third, 3000, 3)
        assertEquals(listOf(listOf(30, 32), listOf(20, 22), listOf(10, 12)), recent.map { rows -> rows.map { it.seconds } })
        assertEquals(listOf(listOf(30, 32)), repo.recentSessionSets("side_plank", third, 3000, 1)
            .map { rows -> rows.map { it.seconds } })
        assertEquals(emptyList<List<cloud.trotter.log.strength.domain.model.LoggedSet>>(),
            repo.recentSessionSets("unknown", third, 3000, 3))
    }
}
