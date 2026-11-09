package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.progression.RepTarget
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanConversionGateTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private val plan = PlanEntity(1, "Plan", "AUTHORED", 0, 1000, onToday = true)

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("conversion-gate", ".preferences_pb")
        })
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        db.planDao().insertPlan(plan)
        db.programDao().upsertDay(ProgramDayEntity(1, "A", 0, "Day A", "", null))
        for (id in listOf(7L, 8L)) {
            db.programDao().insertExercise(ProgramExerciseEntity(
                id, 1, "A", (id - 7).toInt(), "bb_back_squat", "STRAIGHT", 3, null, "",
                prescriptionJson = prescription(5), keepToPlan = true,
            ))
        }
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun prescription(reps: Int) =
        PrescriptionJson.encode(Prescription(PrescribedTarget.LoadReps(155.0, reps)))

    @Test fun `subscribed plan flows emit existing plans during storage failure and a later call converts`() = runTest {
        var failing = true
        var attempts = 0
        val dao = object : ProgramDao by db.programDao() {
            override suspend fun setRule(id: Long, json: String) {
                if (id == 8L) {
                    attempts++
                    if (failing) throw SQLiteFullException("Storage full")
                }
                db.programDao().setRule(id, json)
            }
        }
        val plans = PlanRepository(db, settings, programDao = dao)
        val original = db.programDao().allExercises()

        assertEquals(listOf(plan), plans.todayPlans.first())
        assertEquals(original, db.programDao().allExercises())
        assertEquals(plan, plans.selectedPlanFlow.first())
        assertEquals(original, db.programDao().allExercises())
        assertEquals(2, attempts)

        failing = false
        assertEquals(listOf(plan), plans.todayPlans.first())
        assertEquals(3, attempts)
        db.programDao().allExercises().forEach { assertNotNull(SlotRuleJson.decode(it.ruleJson)) }
    }

    @Test fun `conversion cancellation escapes a subscribed plan flow and rolls back`() = runTest {
        val cancellation = CancellationException("Canceled conversion")
        val dao = object : ProgramDao by db.programDao() {
            override suspend fun setRule(id: Long, json: String) {
                if (id == 8L) throw cancellation
                db.programDao().setRule(id, json)
            }
        }
        val plans = PlanRepository(db, settings, programDao = dao)
        val original = db.programDao().allExercises()
        val thrown = assertFailsWith<CancellationException> { plans.todayPlans.first() }
        assertEquals(cancellation.message, thrown.message)
        assertEquals(original, db.programDao().allExercises())
    }

    @Test fun `a prescription reps edit clears a converted rule and conversion uses the new reps`() = runTest {
        val plans = PlanRepository(db, settings)
        plans.convertPlans()
        assertEquals(RepTarget.Fixed(5), SlotRuleJson.decode(db.programDao().exercise(7)!!.ruleJson)!!.target)

        db.programDao().setPrescription(7, prescription(8), 3)
        assertNull(db.programDao().exercise(7)!!.ruleJson)
        plans.convertPlans()

        assertEquals(RepTarget.Fixed(8), SlotRuleJson.decode(db.programDao().exercise(7)!!.ruleJson)!!.target)
    }
}
