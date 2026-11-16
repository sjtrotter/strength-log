package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.RecentNumber
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlannedResetDayTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val answers = SetupAnswers(goal = LiftGoal.STRENGTH, days = 4, experience = LiftExperience.A_YEAR_OR_MORE,
        recent = mapOf("bb_back_squat" to RecentNumber(275.0, 5, Effort.GOOD)))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("planned-reset", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() { db.close(); storeScope.cancel() }

    @Test fun `reset rebuilds a v2 day from the planner and seeds it lazily`() = runTest {
        val id = repo.plans.createPlannedPlan("Strength, 4 days", answers)
        repo.plans.publishPlannedPlan(id)
        val slots = db.programDao().exercisesForDay(id, "A")
        assertTrue(repo.swapExerciseById("A", slots.first().id, "goblet_squat"))
        assertTrue(repo.removeExercise(slots.last().id))
        val other = db.programDao().exercisesForDay(id, "B")
        val log = ExerciseLogEntity(id, "B", other.first().id, Slot.MAIN,
            SetJson.encodeSets(listOf(LoggedSet(185.0, 8, SetKind.WORK))), 1000)
        db.programDao().upsertLog(log)
        val otherLogs = db.programDao().logsForDay(id, "B")
        val originalDay = assertNotNull(db.programDao().day(id, "A"))

        repo.resetDayToTemplate("A")

        val day = repo.plans.programFor(id).first().days.single { it.id == "A" }
        assertEquals(assertNotNull(Planner.day(answers, "A")).day, day)
        assertTrue(db.programDao().logsForDay(id, "A").isEmpty())
        assertEquals(other, db.programDao().exercisesForDay(id, "B"))
        assertEquals(otherLogs, db.programDao().logsForDay(id, "B"))
        val stored = assertNotNull(db.programDao().day(id, "A"))
        assertEquals(originalDay.nextRunId, stored.nextRunId)
        assertEquals(originalDay.closedAt, stored.closedAt)
        assertEquals(originalDay.position, stored.position)
        val rebuilt = repo.plans.slotsFor(id, "A").first()
        val seeds = repo.plans.seeds(id, rebuilt, emptyMap(), WeightUnit.LB, repo.catalog.snapshot())
        assertTrue(rebuilt.all { (it.programExerciseId to Slot.MAIN) in seeds })
        assertTrue(rebuilt.filter { it.exercise.superset != null }.all { (it.programExerciseId to Slot.SS) in seeds })
        assertTrue(db.programDao().logsForDay(id, "A").isEmpty())
    }

    @Test fun `an unreadable v2 recipe resets nothing`() = runTest {
        val id = repo.plans.createPlannedPlan("Strength", answers)
        repo.plans.publishPlannedPlan(id)
        db.planDao().updateRecipe(id,
            """{"v":2,"branch":"LIFT","goal":"POWER","days":4,"minutes":60,"experience":"NEW"}""")
        val days = db.programDao().daysForPlan(id)
        val slots = db.programDao().allExercises()
        val logs = db.programDao().allLogs()
        repo.resetDayToTemplate("A")
        assertEquals(days, db.programDao().daysForPlan(id))
        assertEquals(slots, db.programDao().allExercises())
        assertEquals(logs, db.programDao().allLogs())
    }

    @Test fun `a rest or unknown day resets nothing`() = runTest {
        val id = repo.plans.createPlannedPlan("Strength", answers)
        repo.plans.publishPlannedPlan(id)
        val days = db.programDao().daysForPlan(id)
        val slots = db.programDao().allExercises()
        val logs = db.programDao().allLogs()
        repo.resetDayToTemplate("D")
        repo.resetDayToTemplate("Z")
        assertEquals(days, db.programDao().daysForPlan(id))
        assertEquals(slots, db.programDao().allExercises())
        assertEquals(logs, db.programDao().allLogs())
    }
}
