package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
class HistoryRepositoryCardioEditTest {
    private lateinit var db: StrengthDatabase
    private lateinit var history: HistoryRepository
    private lateinit var storeScope: CoroutineScope
    private val start = 1_783_512_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-edit", ".preferences_pb")
        }
        val settings = SettingsStore(dataStore)
        history = HistoryRepository(db, settings, PlanRepository(db, settings))
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun insert(
        sets: List<CardioSetResult> = listOf(ManualCardio.result(2413, 7100)),
        planId: Long? = null,
    ): Long {
        val seconds = if (sets.isEmpty()) 2413 else sets.sumOf { it.elapsedSeconds }
        val summary = ManualCardio.summary(CardioActivity.ROAD_RUN, seconds)
        val id = db.cardioSessionDao().insert(CardioSessionEntity(
            dayId = "A", mode = summary.mode.name, hard = false, label = summary.label,
            startedAt = start, completedAt = start + seconds * 1_000L + 500,
            seconds = seconds, stepsCompleted = sets.size, activity = CardioActivity.ROAD_RUN.name,
            note = "Easy", planId = planId, planName = planId?.let { "Run plan" },
        ))
        db.cardioSetResultDao().insertAll(sets.mapIndexed { position, set -> set.toEntity(id, position) })
        return id
    }

    @Test
    fun `editing a one-set session keeps its start and rewrites duration distance and note`() = runTest {
        val id = insert()

        history.updateCardioSession(id, CardioActivity.TRAIL_RUN, 2412, 7000, "Hilly")

        val session = history.cardioSession(id)!!
        assertEquals(CardioActivity.TRAIL_RUN.name, session.activity)
        assertEquals("Trail run", session.label)
        assertEquals(ManualCardio.summary(CardioActivity.TRAIL_RUN, 2412).mode.name, session.mode)
        assertEquals(start, session.startedAt)
        assertEquals("A", session.dayId)
        assertEquals(2412, session.seconds)
        assertEquals(start + 2_412_000, session.completedAt)
        assertEquals("Hilly", session.note)
        val result = db.cardioSetResultDao().forSession(id).single()
        assertEquals(2412, result.elapsedSeconds)
        assertEquals(Measure.Time(2412), CardioBlockJson.decodeSet(result.specJson).end)
        assertEquals(7000, result.metres)
        assertEquals(DistanceSource.MANUAL.name, result.distanceSource)
    }

    @Test
    fun `editing an interval session keeps its sets`() = runTest {
        val sets = listOf(
            CardioSetResult(CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)), CardioSetStatus.FINISHED, 300),
            ManualCardio.result(900, 2000),
            CardioSetResult(CardioSetSpec(CardioRole.COOLDOWN, Measure.Time(300)), CardioSetStatus.SHORTENED, 180),
        )
        val id = insert(sets)
        val before = history.cardioSession(id)!!
        val rows = db.cardioSetResultDao().forSession(id)

        history.updateCardioSession(id, CardioActivity.ROWER, null, null, "Moved")

        val after = history.cardioSession(id)!!
        assertEquals(rows, db.cardioSetResultDao().forSession(id))
        assertEquals(before.completedAt - before.startedAt, after.completedAt - after.startedAt)
        assertEquals(before.seconds, after.seconds)
        assertEquals(before.startedAt, after.startedAt)
        assertEquals("Moved", after.note)
        assertEquals(CardioActivity.ROWER.name, after.activity)
    }

    @Test
    fun `delete and restore bring back the same rows`() = runTest {
        val planId = db.planDao().insertPlan(PlanEntity(
            id = 0, name = "Run plan", origin = PlanOrigin.AUTHORED.name, position = 0,
            createdAt = start, suggestedDayId = "B",
        ))
        val id = insert(planId = planId)
        val session = history.cardioSession(id)!!
        val sets = db.cardioSetResultDao().forSession(id)

        val deleted = history.deleteCardioSession(id)!!

        assertEquals(session, deleted.session)
        assertEquals(sets, deleted.sets)
        assertNull(history.cardioSession(id))
        assertTrue(db.cardioSetResultDao().forSession(id).isEmpty())
        assertEquals("B", db.planDao().plan(planId)!!.suggestedDayId)
        history.restoreCardioSession(deleted)
        assertEquals(session, history.cardioSession(id))
        assertEquals(sets, db.cardioSetResultDao().forSession(id))
        assertEquals("B", db.planDao().plan(planId)!!.suggestedDayId)
    }

    @Test
    fun `a distance-ended set keeps its spec and can clear its distance`() = runTest {
        val result = CardioSetResult(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(5000), Measure.Time(1500), hard = true),
            CardioSetStatus.FINISHED, 1490, 5000, DistanceSource.GPS,
        )
        val id = insert(listOf(result))
        val before = db.cardioSetResultDao().forSession(id).single()

        history.updateCardioSession(id, CardioActivity.TRACK_RUN, 1489, null, "  Track  ")

        val after = db.cardioSetResultDao().forSession(id).single()
        assertEquals(before.copy(elapsedSeconds = 1489, metres = null, distanceSource = null,
            metresAtEnd = 5000, secondsAtEnd = 1489), after)
        assertEquals("Track", history.cardioSession(id)!!.note)
    }

    @Test
    fun `a legacy summary keeps its duration and trims its note without moving rotation`() = runTest {
        val planId = db.planDao().insertPlan(PlanEntity(
            id = 0, name = "Run plan", origin = PlanOrigin.AUTHORED.name, position = 0,
            createdAt = start, suggestedDayId = "B",
        ))
        val id = insert(emptyList(), planId)
        val before = history.cardioSession(id)!!

        history.updateCardioSession(id, CardioActivity.ROWER, 100, 100, "  ${"x".repeat(130)}  ")

        val after = history.cardioSession(id)!!
        assertEquals(before.seconds, after.seconds)
        assertEquals(before.startedAt, after.startedAt)
        assertEquals(before.completedAt, after.completedAt)
        assertEquals("x".repeat(NOTE_MAX_LENGTH), after.note)
        assertTrue(db.cardioSetResultDao().forSession(id).isEmpty())
        assertEquals("B", db.planDao().plan(planId)!!.suggestedDayId)
    }

    @Test
    fun `invalid measures leave both rows unchanged`() = runTest {
        val id = insert()
        val session = history.cardioSession(id)
        val sets = db.cardioSetResultDao().forSession(id)
        for ((seconds, metres) in listOf(0 to 7000, 86_401 to 7000, 2412 to 0, 2412 to 200_001)) {
            val failure = runCatching {
                history.updateCardioSession(id, CardioActivity.TRAIL_RUN, seconds, metres, "Changed")
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(session, history.cardioSession(id))
            assertEquals(sets, db.cardioSetResultDao().forSession(id))
        }
    }
}
