package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryRepositoryCardioBestsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var history: HistoryRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("cardio-bests", ".preferences_pb")
        }
        val settings = SettingsStore(dataStore)
        history = HistoryRepository(db = db, settings = settings, plans = PlanRepository(db, settings))
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun result(
        end: Measure = Measure.Distance(400),
        seconds: Int = 102,
        metres: Int? = null,
        role: CardioRole = CardioRole.WORK,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
    ) = CardioSetResult(CardioSetSpec(role, end), status, seconds,
        metres, metres?.let { DistanceSource.MANUAL })

    private suspend fun insert(
        activity: String?,
        completedAt: Long,
        sets: List<CardioSetResult>,
        planId: Long? = null,
        dayId: String? = "A",
    ): Long {
        val id = db.cardioSessionDao().insert(CardioSessionEntity(
            dayId = dayId, mode = CardioMode.OUTDOOR_RUN.name, hard = false, label = "Run",
            startedAt = completedAt - 1000, completedAt = completedAt,
            seconds = sets.sumOf { it.elapsedSeconds }, stepsCompleted = sets.size,
            planId = planId, activity = activity,
        ))
        db.cardioSetResultDao().insertAll(sets.mapIndexed { position, set -> set.toEntity(id, position) })
        return id
    }

    @Test
    fun queryUsesExactActivityAcrossPlansAndReducesOnlyValidFinishedWork() = runTest {
        val activity = CardioActivity.TRACK_RUN
        val fastest = result(seconds = 98, metres = 400).copy(distanceSource = DistanceSource.GPS)
        val farthest = result(Measure.Time(1800), 1800, 5210)
        insert(activity.name, 3000, listOf(result(seconds = 101), farthest), planId = 2)
        insert(activity.name, 2000, listOf(result(seconds = 98, metres = 400)), planId = 1)
        val oldId = insert(activity.name, 1000, listOf(
            fastest,
            result(seconds = 80, role = CardioRole.WARMUP),
            result(seconds = 80, role = CardioRole.RECOVERY),
            result(seconds = 80, role = CardioRole.COOLDOWN),
            result(seconds = 80, status = CardioSetStatus.SHORTENED),
            result(seconds = 0, status = CardioSetStatus.SKIPPED),
            result(Measure.Time(1800), 1800),
            result(Measure.Distance(5000), 1500),
        ))
        val malformed = fastest.toEntity(oldId, 8)
        db.cardioSetResultDao().insertAll(listOf(
            malformed.copy(specJson = "invalid", elapsedSeconds = 10),
            malformed.copy(position = 9, distanceSource = "UNKNOWN", elapsedSeconds = 10),
            malformed.copy(cardioSessionId = 9999, elapsedSeconds = 10),
        ))
        insert(CardioActivity.TREADMILL_RUN.name, 4000, listOf(result(seconds = 90)))
        insert(CardioActivity.ROAD_RUN.name, 4000, listOf(result(seconds = 85)))
        insert(null, 4000, listOf(result(seconds = 80)))
        insert("UNKNOWN", 4000, listOf(result(seconds = 75)))

        val bests = history.cardioBestsFor(activity)

        assertEquals(3, bests.size)
        assertEquals(fastest, bests[CardioRecordKey(activity, Measure.Distance(400))])
        assertEquals(farthest, bests[CardioRecordKey(activity, Measure.Time(1800))])
        assertEquals(1500, bests[CardioRecordKey(activity, Measure.Distance(5000))]?.elapsedSeconds)
        assertEquals(90, history.cardioBestsFor(CardioActivity.TREADMILL_RUN).values.single().elapsedSeconds)
        assertTrue(history.cardioBestsFor(CardioActivity.ROWER).isEmpty())
    }

    @Test
    fun queryReturnsOnlyFinishedRowsInCompletionAndPositionOrder() = runTest {
        val activity = CardioActivity.TRACK_RUN.name
        val newer = insert(activity, 2000, listOf(result(seconds = 100)))
        val older = insert(activity, 1000, listOf(result(seconds = 102), result(seconds = 101),
            result(seconds = 90, status = CardioSetStatus.SHORTENED)))

        val rows = db.cardioSetResultDao().finishedForActivity(activity, CardioSetStatus.FINISHED.name)

        assertEquals(listOf(older, older, newer), rows.map { it.cardioSessionId })
        assertEquals(listOf(0, 1, 0), rows.map { it.position })
        assertEquals(listOf(102, 101, 100), rows.map { it.elapsedSeconds })
    }

    @Test
    fun baselineExcludesAllRowsWithTheCurrentRunIdentityBeforeReducingBests() = runTest {
        val activity = CardioActivity.TRACK_RUN
        val prior = insert(activity.name, 2000, listOf(result(seconds = 102)))
        insert(activity.name, 3000, listOf(result(seconds = 98)), planId = 2)
        insert(activity.name, 3000, listOf(result(seconds = 99)), planId = 3)
        val otherDay = insert(activity.name, 3000, listOf(result(seconds = 101)), dayId = "B")
        val noDay = insert(activity.name, 3000, listOf(result(seconds = 100)), dayId = null)

        val rows = db.cardioSetResultDao().finishedForActivity(
            activity.name, CardioSetStatus.FINISHED.name, excludedDayId = "A", excludedStartedAt = 2000,
        )
        assertEquals(listOf(prior, otherDay, noDay), rows.map { it.cardioSessionId })
        assertEquals(100, history.cardioBestsFor(activity, "A", 2000).values.single().elapsedSeconds)
        assertEquals(98, history.cardioBestsFor(activity).values.single().elapsedSeconds)
        assertEquals(98, history.cardioBestsFor(activity, null, 2000).values.single().elapsedSeconds)
    }
}
