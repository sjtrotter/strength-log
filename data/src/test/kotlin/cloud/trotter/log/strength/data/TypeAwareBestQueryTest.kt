package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Best"/"last time" queries must be type-correct: a REPS exercise's rows all
 * tie at weight 0 so the pick is its highest rep set, a TIMED exercise's rows tie
 * at weight and reps so the pick is its longest hold, and a WEIGHTED lift is still
 * chosen by heaviest weight. This drives the real Room ORDER BY, not just the pure
 * reduction, since the ordering is where the type-awareness lives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TypeAwareBestQueryTest {

    private lateinit var db: StrengthDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun set(exerciseId: String, weightLb: Double, reps: Int, seconds: Int) =
        SessionSetEntity(0, 1, exerciseId, exerciseId, "main", 0, "WORK", weightLb, reps, true, seconds)

    @Test
    fun `best picks max reps for REPS, max seconds for TIMED, heaviest for WEIGHTED`() = runTest {
        db.sessionDao().insertSession(WorkoutSessionEntity(1, "A", "Day A", null, 1_000, 235))
        db.sessionDao().insertSets(
            listOf(
                set("pushup", 0.0, 12, 0),
                set("pushup", 0.0, 20, 0), // most reps → the REPS best
                set("plank", 0.0, 0, 30),
                set("plank", 0.0, 0, 75), // longest hold → the TIMED best
                set("bb_back_squat", 235.0, 5, 0), // heaviest → the WEIGHTED best
                set("bb_back_squat", 175.0, 8, 0),
            ),
        )

        val prs = db.sessionDao()
            .personalRecordRows(listOf("pushup", "plank", "bb_back_squat"), excludedRunId = null)
            .toPersonalRecordsByExercise()

        assertEquals(20, prs.getValue("pushup").reps)
        assertEquals(0, prs.getValue("pushup").seconds)
        assertEquals(75, prs.getValue("plank").seconds)
        assertEquals(0, prs.getValue("plank").reps)
        assertEquals(235.0, prs.getValue("bb_back_squat").weightLb, 0.0)

        val last = db.sessionDao()
            .lastPerformedRows(listOf("pushup", "plank", "bb_back_squat"))
            .toLastPerformedByExercise()

        assertEquals(20, last.getValue("pushup").reps)
        assertEquals(75, last.getValue("plank").seconds)
        assertEquals(235.0, last.getValue("bb_back_squat").weightLb, 0.0)
    }

    @Test
    fun `personalRecordRows excludes only the requested run and retains null run ids`() = runTest {
        val dao = db.sessionDao()
        dao.insertSession(WorkoutSessionEntity(1, "A", "Day A", null, 1_000, 235, runId = "r1"))
        dao.insertSession(WorkoutSessionEntity(2, "A", "Day A", null, 2_000, 235, runId = "r2"))
        dao.insertSets(listOf(
            set("bb_back_squat", 235.0, 5, 0),
            set("bb_back_squat", 245.0, 5, 0).copy(sessionId = 2),
        ))
        val ids = listOf("bb_back_squat")

        assertEquals(listOf(235.0), dao.personalRecordRows(ids, excludedRunId = "r2").map { it.weightLb })
        assertEquals(listOf(235.0, 245.0), dao.personalRecordRows(ids, excludedRunId = null).map { it.weightLb })

        dao.insertSession(WorkoutSessionEntity(3, "A", "Day A", null, 3_000, 235, runId = null))
        dao.insertSets(listOf(set("bb_back_squat", 225.0, 5, 0).copy(sessionId = 3)))

        assertEquals(listOf(235.0, 225.0), dao.personalRecordRows(ids, excludedRunId = "r2").map { it.weightLb })
        assertEquals(listOf(245.0, 225.0), dao.personalRecordRows(ids, excludedRunId = "r1").map { it.weightLb })
        assertEquals(listOf(235.0, 245.0, 225.0), dao.personalRecordRows(ids, excludedRunId = null).map { it.weightLb })
    }
}
