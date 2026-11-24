package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.TestEventJson
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.domain.standards.TestStop
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
class TestResultDaoTest {
    private lateinit var db: StrengthDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun result(at: Long, event: String = "aft_hrp", window: Int? = 120) = TestResultEntity(
        completedAt = at, planId = 1, dayId = "A", runId = "run", programExerciseId = 7, setIndex = 0,
        exerciseId = TestEvents.get(event).exerciseId, eventId = event, eventVersion = 1, windowSeconds = window,
        count = if (event == "aft_plank") null else 27, seconds = if (event == "aft_plank") 192 else null,
        stop = if (event == "aft_plank") "STOPPED" else "TIME_UP", rulesJson = TestEventJson.encode(TestEvents.get(event)),
    )

    @Test fun `newest completion is per identity and a null window matches only null`() = runTest {
        val dao = db.testResultDao()
        dao.insertAll(listOf(result(30), result(10), result(50, window = 60), result(40, "aft_plank", null)))
        assertEquals(30L, dao.newestCompletedAt("aft_hrp", 1, 120))
        assertEquals(50L, dao.newestCompletedAt("aft_hrp", 1, 60))
        assertEquals(40L, dao.newestCompletedAt("aft_plank", 1, null))
        assertNull(dao.newestCompletedAt("aft_hrp", 1, null))
        assertNull(dao.newestCompletedAt("aft_hrp", 2, 120))
        val sameTime = dao.insert(result(30))
        assertEquals(listOf(10L, 30L, 30L, 40L, 50L), dao.all().map { it.completedAt })
        assertEquals(sameTime, dao.all()[2].id)
        assertEquals(dao.all(), dao.observeAll().first())
    }

    @Test fun `deleteForSet removes one benchmark and keeps the rest`() = runTest {
        val dao = db.testResultDao()
        dao.insert(result(10))
        val others = listOf(result(20).copy(setIndex = 1), result(30).copy(programExerciseId = 8),
            result(40).copy(runId = "other"), result(50).copy(runId = null))
        val kept = others.map { it.copy(id = dao.insert(it)) }
        dao.deleteForSet("run", 7, 0)
        assertEquals(kept, dao.all())
        dao.deleteAll()
        assertEquals(emptyList<TestResultEntity>(), dao.all())
    }

    @Test fun `updateForSet changes only the matching result values`() = runTest {
        val dao = db.testResultDao()
        val original = result(10)
        val saved = original.copy(id = dao.insert(original))
        val others = listOf(result(20).copy(setIndex = 1), result(30).copy(programExerciseId = 8),
            result(40).copy(runId = "other"), result(50).copy(runId = null))
        val kept = others.map { it.copy(id = dao.insert(it)) }
        dao.updateForSet("run", 7, 0, count = 37, seconds = null)
        assertEquals(listOf(saved.copy(count = 37)) + kept, dao.all())
        dao.updateForSet("missing", 7, 0, count = 47, seconds = null)
        assertEquals(listOf(saved.copy(count = 37)) + kept, dao.all())
        val hold = result(60, "aft_plank", null).copy(setIndex = 2)
        val savedHold = hold.copy(id = dao.insert(hold))
        dao.updateForSet("run", 7, 2, count = null, seconds = 202)
        assertEquals(listOf(saved.copy(count = 37)) + kept + savedHold.copy(seconds = 202), dao.all())
    }

    @Test fun `a result maps its identity and refuses unreadable values`() {
        val row = result(10)
        val mapped = assertNotNull(row.toDomain())
        assertEquals(TestRef("aft_hrp", 1, 120), mapped.ref)
        assertEquals(10L, mapped.completedAt)
        assertEquals(27, mapped.count)
        assertNull(mapped.seconds)
        assertEquals(TestStop.TIME_UP, mapped.stop)
        assertNull(row.copy(stop = "FELL").toDomain())
        assertNull(row.copy(count = null).toDomain())
        assertNull(row.copy(seconds = 192).toDomain())
        assertNull(row.copy(eventVersion = 0).toDomain())
    }
}
