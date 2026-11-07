package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanGoalTest {
    private lateinit var db: StrengthDatabase
    private lateinit var plans: PlanRepository
    private lateinit var scope: CoroutineScope
    private val timed = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960, 21000)
    private val shuttle = PlanGoal.Shuttle("PACER", 52)
    private val now = 123456L

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("plan-goal", ".preferences_pb")
        })
        plans = PlanRepository(db, settings, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun seed() {
        db.planDao().insertPlans(listOf(PlanEntity(1, "One", "AUTHORED", 0, 1), PlanEntity(2, "Two", "AUTHORED", 1, 1)))
    }

    private suspend fun goal() = PlanGoalJson.decode(db.planDao().plan(1)!!.goalJson)

    @Test fun `setGoal stores and clears only the addressed plan`() = runTest {
        seed()
        val other = db.planDao().plan(2)
        plans.setGoal(1, timed)
        assertEquals(timed, goal())
        assertEquals(other, db.planDao().plan(2))
        plans.setGoal(1, null)
        assertNull(db.planDao().plan(1)!!.goalJson)
        plans.setGoal(99, timed)
        assertEquals(2, db.planDao().allPlans().size)
    }

    @Test fun `logShuttleAttempt appends with the clock time including zero`() = runTest {
        seed()
        plans.setGoal(1, shuttle)
        plans.logShuttleAttempt(1, 42)
        plans.logShuttleAttempt(1, 0)
        assertEquals(shuttle.copy(attempts = listOf(ShuttleAttempt(now, 42), ShuttleAttempt(now, 0))), goal())
        assertFailsWith<IllegalArgumentException> { plans.logShuttleAttempt(1, 1000) }
        assertEquals(2, (goal() as PlanGoal.Shuttle).attempts.size)
    }

    @Test fun `same protocol keeps stored attempts and a new protocol drops them`() = runTest {
        seed()
        val supplied = listOf(ShuttleAttempt(5, 999))
        plans.setGoal(1, shuttle.copy(attempts = supplied))
        assertEquals(shuttle, goal())
        plans.logShuttleAttempt(1, 42)
        val edited = shuttle.copy(protocol = " pacer ", targetShuttles = 60, byEpochDay = 21000, attempts = supplied)
        plans.setGoal(1, edited)
        assertEquals(edited.copy(protocol = "pacer", attempts = listOf(ShuttleAttempt(now, 42))), goal())
        plans.setGoal(1, edited.copy(protocol = "HAMR"))
        assertEquals(edited.copy(protocol = "HAMR", attempts = emptyList()), goal())
        plans.setGoal(1, timed)
        plans.setGoal(1, shuttle.copy(attempts = supplied))
        assertEquals(shuttle, goal())
    }

    @Test fun `logShuttleAttempt on a timed missing or absent goal changes nothing`() = runTest {
        seed()
        plans.logShuttleAttempt(1, 44)
        assertNull(goal())
        plans.setGoal(1, timed)
        val row = db.planDao().plan(1)
        plans.logShuttleAttempt(1, 44)
        plans.logShuttleAttempt(99, 44)
        assertEquals(row, db.planDao().plan(1))
    }

    @Test fun `duplicatePlan copies the goal and its attempts`() = runTest {
        seed()
        plans.setGoal(1, shuttle)
        plans.logShuttleAttempt(1, 42)
        val source = db.planDao().plan(1)!!
        val copied = plans.duplicatePlan(1, "Copy")
        assertEquals(source.goalJson, db.planDao().plan(copied)!!.goalJson)
        plans.logShuttleAttempt(copied, 55)
        assertEquals(source, db.planDao().plan(1))
    }
}
