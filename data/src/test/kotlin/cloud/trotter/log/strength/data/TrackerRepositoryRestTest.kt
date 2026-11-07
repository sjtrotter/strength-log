package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryRestTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("rest-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock)
    }

    @After fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun program() = Program(listOf(
        ProgramDay("A", "Run", "", emptyList(), cardioBlock = CardioBlock(CardioActivity.ROAD_RUN,
            listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))),
        ProgramDay("B", "Lift", "", listOf(ProgramExercise("bb_back_squat"))),
        ProgramDay("C", "Rest", "", emptyList(), kind = ProgramDayKind.REST),
    ))

    private suspend fun plan(): Long {
        repo.replaceProgram(program())
        val id = repo.plans.selectedPlanIdFlow.first()!!
        db.planDao().updateSuggestedDay(id, "C")
        return id
    }

    @Test fun `rest taken advances once and writes no workout`() = runTest {
        val plan = plan()
        val beforeDays = db.programDao().allDays()
        assertTrue(repo.takeRest(plan, "C"))
        assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
        assertFalse(repo.takeRest(plan, "C"))
        assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
        assertFalse(repo.takeRest(plan, "A"))
        assertEquals(beforeDays, db.programDao().allDays())
        assertTrue(db.sessionDao().allSessions().isEmpty())
        assertTrue(db.cardioSessionDao().all().isEmpty())
        assertTrue(db.runDao().allRuns().isEmpty())
        assertTrue(db.programDao().allLogs().isEmpty())
    }

    @Test fun `finishing the training day after a rest moves past it`() = runTest {
        val plan = plan()
        repo.logCardioSession(cloud.trotter.log.strength.data.db.entity.CardioSessionEntity(
            dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = 1000, completedAt = 121000, seconds = 120, stepsCompleted = 1, planId = plan,
        ), finishDay = true)
        assertEquals("B", db.planDao().plan(plan)!!.suggestedDayId)
    }

    @Test fun `finishing another day leaves rest up next`() = runTest {
        val plan = plan()
        val slot = db.programDao().exercisesForDay(plan, "B").single().id
        val runId = db.programDao().day(plan, "B")!!.nextRunId
        repo.applyToRun(runId, plan, "B", listOf(TrackEdit(slot, Slot.MAIN) { rows ->
            rows.mapIndexed { index, row -> row.copy(done = index == 0) }
        }))
        repo.finishRun(runId)
        assertEquals("C", db.planDao().plan(plan)!!.suggestedDayId)
    }

    @Test fun `adding moving and removing rest keeps letters and a training day`() = runTest {
        val plan = plan()
        assertEquals("D", repo.addRestDay(plan, "Rest"))
        assertEquals(ProgramDayKind.REST.name, db.programDao().day(plan, "D")!!.kind)
        val before = db.programDao().daysForPlan(plan)
        repo.moveDay(plan, "D", up = true)
        assertEquals(listOf("A", "B", "D", "C"), db.programDao().daysForPlan(plan).map { it.dayId })
        assertEquals(before[2].position, db.programDao().day(plan, "D")!!.position)
        assertTrue(repo.removeDay(plan, "A"))
        assertFalse(repo.removeDay(plan, "B"))
        assertTrue(repo.removeDay(plan, "D"))
        assertEquals(listOf("B", "C"), db.programDao().daysForPlan(plan).map { it.dayId })
    }

    @Test fun `rest does not use the seven training day cap or reserve training letters`() = runTest {
        val plan = repo.plans.createAuthoredPlan("Plan", "Lift")
        assertEquals("B", repo.addRestDay(plan, "Rest"))
        for (letter in 'C'..'H') assertEquals(letter.toString(), repo.addDay(plan, "Lift"))
        assertEquals(null, repo.addDay(plan, "Lift"))
    }

    @Test fun `initial and removed suggestions resolve to training days`() = runTest {
        val restFirst = Program(listOf(program().days[2], program().days[0], program().days[1]))
        val id = repo.plans.createGeneratedPlan("Plan", WizardAnswers(), restFirst)
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
        repo.plans.publishCreatedPlan(id, WizardAnswers())
        repo.replaceProgram(restFirst)
        assertEquals("A", db.planDao().plan(id)!!.suggestedDayId)
        assertTrue(repo.removeDay(id, "A"))
        assertEquals("B", db.planDao().plan(id)!!.suggestedDayId)
        db.planDao().updateSuggestedDay(id, "C")
        assertTrue(repo.removeDay(id, "C"))
        assertEquals("B", db.planDao().plan(id)!!.suggestedDayId)
    }
    @Test fun `moving a day at either end is a no-op and moving down keeps identity`() = runTest {
        val plan = plan()
        val before = db.programDao().daysForPlan(plan)
        repo.moveDay(plan, "A", up = true)
        repo.moveDay(plan, "C", up = false)
        repo.moveDay(plan, "missing", up = true)
        assertEquals(before, db.programDao().daysForPlan(plan))
        repo.moveDay(plan, "C", up = true)
        repo.moveDay(plan, "C", up = false)
        assertEquals(before, db.programDao().daysForPlan(plan))
    }

}
