package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioRuleJson
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
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.progression.CardioRule
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioFinishTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private var plan = 0L
    private val day = 86_400_000L
    private val t = 2_000 * day

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-finish", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings)
        val history = HistoryRepository(db, settings, plans)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, plans = plans, history = history)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun time(seconds: Int, aim: Int? = null) =
        CardioSetSpec(CardioRole.WORK, Measure.Time(seconds), aim?.let { Measure.Distance(it) })
    private fun distance(metres: Int, role: CardioRole = CardioRole.WORK) = CardioSetSpec(role, Measure.Distance(metres))
    private fun block(vararg sets: CardioSetSpec, activity: CardioActivity = CardioActivity.ROAD_RUN) =
        CardioBlock(activity, sets.toList())
    private fun result(spec: CardioSetSpec, elapsed: Int, metres: Int? = null) = FinishedRule.atEnd(
        CardioSetResult(spec, CardioSetStatus.FINISHED, elapsed, metres, metres?.let { DistanceSource.MANUAL }),
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
                            owner: Long? = plan, dayId: String = "A"): Long {
        val seconds = rows.sumOf { it.elapsedSeconds }
        return repo.logCardioSession(CardioSessionEntity(
            planId = owner, dayId = dayId, mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = at - seconds * 1000L, completedAt = at, seconds = seconds,
            stepsCompleted = rows.size, activity = activity.name,
        ), rows)
    }
    private suspend fun working() = CardioBlockJson.decode(db.programDao().day(plan, "A")!!.cardioWorkingJson)

    @Test fun `an automatic plan applies the increase at the commit and a tap restores the opening`() = runTest {
        val opening = block(time(1200))
        prepare(opening)
        assertFalse(db.planDao().plan(plan)!!.offerCardioIncreases)
        log(t, listOf(result(time(1200), 1200)))
        assertEquals(block(time(1260)), working())
        repo.setCardioWorking(plan, "A", opening)
        assertNull(working())
    }

    @Test fun `an ask plan writes nothing`() = runTest {
        prepare(block(time(1200)), automatic = false)
        repo.plans.setOfferCardioIncreases(plan, true)
        log(t, listOf(result(time(1200), 1200)))
        assertNull(working())
    }

    @Test fun `only the newest session that ran the stored block counts`() = runTest {
        prepare(block(time(1200)))
        log(t, listOf(result(time(1200), 1200), result(time(120).copy(role = CardioRole.RECOVERY), 120),
            result(time(1200), 1200)))
        assertNull(working())
        log(t + day, listOf(result(time(1200), 1200)), CardioActivity.TRAIL_RUN)
        assertNull(working())
        repo.setCardioKeepToPlan(plan, "A", true)
        log(t + 2 * day, listOf(result(time(1200), 1200)))
        assertNull(working())
        repo.setCardioKeepToPlan(plan, "A", false)
        val entity = db.programDao().day(plan, "A")!!
        db.programDao().upsertDay(entity.copy(cardioRuleJson = CardioRuleJson.encode(CardioRule(3))))
        log(t + 3 * day, listOf(result(time(1200), 1200)))
        assertNull(working())
        db.programDao().upsertDay(entity)
        log(t + 4 * day, listOf(result(time(1200), 1100)))
        assertNull(working())
        log(t - day, listOf(result(time(1200), 1200)))
        assertNull(working())
    }

    @Test fun `case 17 an 8050 m run in the window caps 10000 m to 8800 m`() = runTest {
        prepare(block(distance(8000)))
        repo.setCardioProgression(plan, "A", ProgressMeasure.DISTANCE, 25)
        log(t - 10 * day, listOf(result(distance(8050), 2900, 8050)), CardioActivity.TRAIL_RUN, owner = null, dayId = "B")
        log(t - 5 * day, listOf(result(distance(12000), 1800, 12000)), CardioActivity.OUTDOOR_RIDE, owner = null, dayId = "B")
        log(t, listOf(result(distance(8000), 2880, 8000)))
        assertEquals(block(distance(8800)), working())
    }

    @Test fun `a 9000 m run 29 days back caps at 9900 m`() = runTest {
        prepare(block(distance(8000)))
        repo.setCardioProgression(plan, "A", ProgressMeasure.DISTANCE, 25)
        log(t - 29 * day, listOf(result(distance(9000), 3300, 9000)), owner = null)
        log(t, listOf(result(distance(8000), 2880, 8000)))
        assertEquals(block(distance(9900)), working())
    }

    @Test fun `a run 30 days back is outside the window`() = runTest {
        prepare(block(distance(8000)))
        repo.setCardioProgression(plan, "A", ProgressMeasure.DISTANCE, 25)
        log(t - 30 * day, listOf(result(distance(9000), 3300, 9000)), owner = null)
        log(t, listOf(result(distance(8000), 2880, 8000)))
        assertEquals(block(distance(8800)), working())
    }

    @Test fun `a capped interval increase that drops rounds holds`() = runTest {
        val work = distance(1000)
        val recovery = distance(200, CardioRole.RECOVERY)
        val opening = block(work, recovery, work, recovery, work, activity = CardioActivity.TRACK_RUN)
        prepare(opening)
        repo.setCardioProgression(plan, "A", ProgressMeasure.DISTANCE, 25)
        val rows = opening.sets.map { result(it, if (it.role == CardioRole.WORK) 240 else 90, (it.end as Measure.Distance).metres) }
        log(t, rows, CardioActivity.TRACK_RUN)
        assertNull(working())
        repo.setCardioProgression(plan, "A", ProgressMeasure.DISTANCE, 5)
        log(t + day, rows, CardioActivity.TRACK_RUN)
        assertEquals(block(distance(1050), recovery, distance(1050), recovery, distance(1050),
            activity = CardioActivity.TRACK_RUN), working())
    }

    @Test fun `a distance entered later earns the increase once`() = runTest {
        val opening = block(time(900, aim = 3000))
        prepare(opening)
        val id = log(t, listOf(result(opening.sets.single(), 900)))
        assertNull(working())
        repo.amendCardioSetDistance(id, 0, 3100)
        assertEquals(block(time(945, aim = 3150)), working())
        repo.setCardioWorking(plan, "A", opening)
        repo.amendCardioSetDistance(id, 0, 3200)
        assertNull(working())
        repo.amendCardioSetDistance(id, 0, null)
        assertNull(working())
    }
    @Test fun `an ask plan writes nothing after a distance amendment`() = runTest {
        val opening = block(time(900, aim = 3000))
        prepare(opening, automatic = false)
        val id = log(t, listOf(result(opening.sets.single(), 900)))
        repo.amendCardioSetDistance(id, 0, 3100)
        assertNull(working())
    }

    @Test fun `lowering a distance never takes an increase back`() = runTest {
        val opening = block(time(900, aim = 3000))
        prepare(opening)
        val id = log(t, listOf(result(opening.sets.single(), 900, 3100)))
        val offered = block(time(945, aim = 3150))
        assertEquals(offered, working())
        repo.amendCardioSetDistance(id, 0, 2900)
        assertEquals(offered, working())
        repo.amendCardioSetDistance(id, 0, null)
        assertEquals(offered, working())
    }

}
