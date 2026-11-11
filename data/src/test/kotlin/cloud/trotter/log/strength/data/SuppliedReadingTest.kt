package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.progression.FinishedRule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SuppliedReadingTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private var plan = 0L
    private val history get() = repo.history
    private val day = 86_400_000L
    private val T = 2_000 * day

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("supplied-reading", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings)
        val history = HistoryRepository(db, settings, plans)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, plans = plans, history = history)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun time(seconds: Int, aim: Int? = null) =
        CardioSetSpec(CardioRole.WORK, Measure.Time(seconds), aim?.let { Measure.Distance(it) })
    private fun distance(metres: Int, aim: Int? = null) =
        CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), aim?.let { Measure.Time(it) })
    private fun block(vararg sets: CardioSetSpec, activity: CardioActivity = CardioActivity.ROAD_RUN) =
        CardioBlock(activity, sets.toList())
    private fun result(spec: CardioSetSpec, elapsed: Int, metres: Int? = null,
                       status: CardioSetStatus = CardioSetStatus.FINISHED) = FinishedRule.atEnd(
        CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL }),
    )
    private suspend fun prepare(block: CardioBlock, automatic: Boolean = true) {
        if (automatic) {
            val id = repo.plans.createAuthoredPlan("Mine", "Run")
            repo.plans.publishCreatedAuthoredPlan(id)
        }
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Run", "", emptyList(), cardioBlock = block))))
        plan = repo.plans.selectedPlanIdFlow.first()!!
    }
    private suspend fun log(at: Long, rows: List<CardioSetResult>, activity: CardioActivity = CardioActivity.ROAD_RUN,
                            owner: Long? = plan, dayId: String = "A", increaseApplied: Boolean = false): Long {
        val seconds = rows.sumOf { it.elapsedSeconds }
        return repo.logCardioSession(CardioSessionEntity(
            planId = owner, dayId = dayId, mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = at - seconds * 1000L, completedAt = at, seconds = seconds,
            stepsCompleted = rows.count { it.status == CardioSetStatus.FINISHED }, activity = activity.name,
            increaseApplied = increaseApplied,
        ), rows)
    }
    private suspend fun working() = CardioBlockJson.decode(db.programDao().day(plan, "A")!!.cardioWorkingJson)

    @Test fun `a distance at a time end is written once and earns the aim`() = runTest {
        val spec = time(1500, aim = 5000)
        prepare(block(spec))
        val id = log(T, listOf(result(spec, 1690)))
        assertEquals(null to 1500, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
        assertNull(working())

        repo.amendCardioSetDistance(id, 0, 5600, readingAtEnd = 5050)

        val supplied = history.cardioSetsFor(id).single()
        assertEquals(5600, supplied.metres)
        assertEquals(5050 to 1500, supplied.metresAtEnd to supplied.secondsAtEnd)
        assertEquals(block(time(1575, aim = 5250)), working())
        assertTrue(db.cardioSessionDao().byId(id)!!.increaseApplied)
        repo.amendCardioSetDistance(id, 0, 5600, 4000)
        assertEquals(supplied, history.cardioSetsFor(id).single())
        repo.amendCardioSetDistance(id, 0, 5700, null)
        assertEquals(supplied.copy(metres = 5700), history.cardioSetsFor(id).single())
    }

    @Test fun `a reading that is over the total or not awaited is not written`() = runTest {
        val spec = time(1500, aim = 5000)
        prepare(block(spec))
        val id = log(T, listOf(result(spec, 1690)))
        repo.amendCardioSetDistance(id, 0, 5600, 5601)
        val row = history.cardioSetsFor(id).single()
        assertEquals(5600, row.metres)
        assertEquals(null to 1500, row.metresAtEnd to row.secondsAtEnd)
        assertNull(working())

        val exact = log(T + day, listOf(result(spec, 1500, 5000)))
        repo.amendCardioSetDistance(exact, 0, 5000, 4900)
        assertEquals(5000 to 1500, history.cardioSetsFor(exact).single().let { it.metresAtEnd to it.secondsAtEnd })
    }

    @Test fun `a time at a distance end finishes the set and earns the aim`() = runTest {
        val spec = distance(2414, aim = 960)
        prepare(block(spec))
        val id = log(T, listOf(result(spec, 1000, status = CardioSetStatus.SHORTENED)))
        assertEquals(null to null, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
        assertEquals(0, db.cardioSessionDao().byId(id)!!.stepsCompleted)
        assertNull(working())

        repo.amendCardioSetDistance(id, 0, 2736, 900)

        val supplied = history.cardioSetsFor(id).single()
        assertEquals(CardioSetStatus.FINISHED, supplied.status)
        assertEquals(1000, supplied.elapsedSeconds)
        assertEquals(2736, supplied.metres)
        assertEquals(2414 to 900, supplied.metresAtEnd to supplied.secondsAtEnd)
        assertEquals(1, db.cardioSessionDao().byId(id)!!.stepsCompleted)
        assertEquals(block(distance(2535, aim = 1008)), working())
        assertEquals(900, FinishedRule.reading(history.cardioBestsFor(CardioActivity.ROAD_RUN).values.single()))
        repo.amendCardioSetDistance(id, 0, 2736, 900)
        assertEquals(supplied, history.cardioSetsFor(id).single())
        assertEquals(1, db.cardioSessionDao().byId(id)!!.stepsCompleted)
        assertEquals(block(distance(2535, aim = 1008)), working())
    }

    @Test fun `a time at a distance end needs a total past it`() = runTest {
        val spec = distance(2414, aim = 960)
        prepare(block(spec))
        val id = log(T, listOf(result(spec, 1000, status = CardioSetStatus.SHORTENED)))
        for ((metres, reading) in listOf(2414 to 900, 2736 to 1001)) {
            repo.amendCardioSetDistance(id, 0, metres, reading)
            val row = history.cardioSetsFor(id).single()
            assertEquals(CardioSetStatus.SHORTENED, row.status)
            assertEquals(null to null, row.metresAtEnd to row.secondsAtEnd)
            assertEquals(0, db.cardioSessionDao().byId(id)!!.stepsCompleted)
        }
    }

    @Test fun `an ask plan stores the reading and writes nothing`() = runTest {
        val spec = time(1500, aim = 5000)
        prepare(block(spec), automatic = false)
        val id = log(T, listOf(result(spec, 1690)))
        repo.amendCardioSetDistance(id, 0, 5600, 5050)
        assertEquals(5050 to 1500, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
        assertNull(working())
        assertFalse(db.cardioSessionDao().byId(id)!!.increaseApplied)
    }

    @Test fun `a logged session never starts with increaseApplied`() = runTest {
        val noPlan = log(T, emptyList(), owner = null, increaseApplied = true)
        assertFalse(db.cardioSessionDao().byId(noPlan)!!.increaseApplied)
        prepare(block(time(1200)))
        val id = log(T + day, listOf(result(time(1200), 1200)), increaseApplied = true)
        assertEquals(block(time(1260)), working())
        assertTrue(db.cardioSessionDao().byId(id)!!.increaseApplied)
    }

    @Test fun `a typed time survives correcting 401 metres to 400 then editing duration`() = runTest {
        val spec = distance(400)
        val id = log(T, listOf(result(spec, 100, status = CardioSetStatus.SHORTENED)), owner = null)
        repo.amendCardioSetDistance(id, 0, 401, readingAtEnd = 100)
        assertEquals("TYPED", db.cardioSetResultDao().forSession(id).single().readingSource)

        repo.amendCardioSetDistance(id, 0, 400)
        val corrected = history.cardioSetsFor(id).single()
        assertEquals(400 to 100, corrected.metresAtEnd to corrected.secondsAtEnd)
        assertEquals(ReadingSource.TYPED, corrected.readingSource)

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 110, 400, "Corrected duration")
        val edited = history.cardioSetsFor(id).single()
        assertEquals(110, edited.elapsedSeconds)
        assertEquals(400, edited.metres)
        assertEquals(400 to 100, edited.metresAtEnd to edited.secondsAtEnd)
        assertEquals(ReadingSource.TYPED, edited.readingSource)
        assertEquals(1, history.cardioSession(id)!!.stepsCompleted)
    }

    @Test fun `a typed distance survives a distance amendment and duration edit`() = runTest {
        val id = log(T, listOf(result(time(1500), 1690)), owner = null)
        repo.amendCardioSetDistance(id, 0, 5600, readingAtEnd = 5050)
        repo.amendCardioSetDistance(id, 0, 5700)
        assertEquals(5050 to 1500, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 1700, 5700, "Corrected duration")
        val edited = history.cardioSetsFor(id).single()
        assertEquals(1700, edited.elapsedSeconds)
        assertEquals(5700, edited.metres)
        assertEquals(Measure.Time(1500), edited.spec.end)
        assertEquals(5050 to 1500, edited.metresAtEnd to edited.secondsAtEnd)
        assertEquals(ReadingSource.TYPED, edited.readingSource)
    }

    @Test fun `a derived time recomputes through distance and duration edits`() = runTest {
        val id = log(T, listOf(result(distance(400), 100, 400)), owner = null)
        assertEquals(400 to 100, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
        repo.amendCardioSetDistance(id, 0, 401)
        val pastEnd = history.cardioSetsFor(id).single()
        assertEquals(400 to null, pastEnd.metresAtEnd to pastEnd.secondsAtEnd)
        assertNull(pastEnd.readingSource)

        repo.amendCardioSetDistance(id, 0, 400)
        assertEquals(400 to 100, history.cardioSetsFor(id).single().let { it.metresAtEnd to it.secondsAtEnd })
        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 110, 400, "Corrected duration")
        val edited = history.cardioSetsFor(id).single()
        assertEquals(400 to 110, edited.metresAtEnd to edited.secondsAtEnd)
        assertNull(edited.readingSource)
    }

    @Test fun `logging preserves a typed reading even when it equals the derived reading`() = runTest {
        val stopped = result(distance(400), 100, 401, CardioSetStatus.SHORTENED)
        val supplied = requireNotNull(FinishedRule.withReading(stopped, 100)).copy(metres = 400)
        val id = log(T, listOf(supplied), owner = null)
        assertEquals(supplied, history.cardioSetsFor(id).single())

        history.updateCardioSession(id, CardioActivity.ROAD_RUN, 110, 400, "Corrected duration")
        assertEquals(supplied.copy(elapsedSeconds = 110), history.cardioSetsFor(id).single())
    }

    @Test fun `logging recomputes readings without a source even when readings arrive populated`() = runTest {
        val stale = result(time(900), 900, 3000).copy(metres = 3100)
        val id = log(T, listOf(stale), owner = null)
        val logged = history.cardioSetsFor(id).single()
        assertEquals(3100 to 900, logged.metresAtEnd to logged.secondsAtEnd)
        assertNull(logged.readingSource)
    }
}
