package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.PlanDao
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.LegacyCardio
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.V1Recipe
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.LiftGoal
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OldPlanConversionTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var plans: PlanRepository
    private lateinit var storeScope: CoroutineScope
    private val novice = WizardAnswers(equipment = setOf(Equipment.MACHINE),
        config = LifterConfig(level = ExperienceLevel.NOVICE, goalsLb = mapOf("hack_squat" to 150.0)))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("old-plan", ".preferences_pb")
        })
        plans = PlanRepository(db, settings)
    }

    @After fun tearDown() { db.close(); storeScope.cancel() }

    private suspend fun create(answers: WizardAnswers = novice) =
        plans.createGeneratedPlan("Old", answers, ProgramGenerator.generate(answers))

    private fun expected(answers: WizardAnswers) = PlanSettings(equipment = answers.equipment,
        goalsLb = answers.config.goalsLb, legacyMains = ProgramGenerator.mainLiftIds(answers).toSet())

    @Test fun `an old wizard plan moves its gear goals and mains into settings`() = runTest {
        val id = create()
        plans.publishCreatedPlan(id, novice)
        val plan = assertNotNull(db.planDao().plan(id))
        assertNull(plan.recipeJson)
        assertEquals(expected(novice), plan.planSettings)
    }

    @Test fun `a ramped old plan records its ramped mains`() = runTest {
        val answers = WizardAnswers()
        val id = create(answers)
        plans.publishCreatedPlan(id, answers)
        val plan = assertNotNull(db.planDao().plan(id))
        assertNull(plan.recipeJson)
        assertEquals(expected(answers), plan.planSettings)
    }

    @Test fun `rules rows and history are untouched`() = runTest {
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        val id = create()
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Day B", null, 1000, 235.0, planId = id))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "hack_squat", "Hack Squat", Slot.MAIN, 0,
            "WORK", 100.0, 8, true)))
        val days = db.programDao().allDays()
        val exercises = db.programDao().allExercises()
        val logs = db.programDao().allLogs()
        val sessions = db.sessionDao().allSessions()
        val sets = db.sessionDao().allSessionSets()
        val before = assertNotNull(db.planDao().plan(id))
        // Publishing also selects the plan and puts it on Today; capture the conversion itself first.
        plans.ensurePlanSettingsMigrated()
        val converted = assertNotNull(db.planDao().plan(id))
        assertEquals(before.copy(recipeJson = converted.recipeJson, settingsJson = converted.settingsJson), converted)
        plans.publishCreatedPlan(id, novice)
        assertEquals(days, db.programDao().allDays())
        assertEquals(exercises, db.programDao().allExercises())
        assertEquals(logs, db.programDao().allLogs())
        assertEquals(sessions, db.sessionDao().allSessions())
        assertEquals(sets, db.sessionDao().allSessionSets())
    }

    @Test fun `setup and built plans are untouched`() = runTest {
        plans.createPlannedPlan("Strength, 4 days", SetupAnswers(goal = LiftGoal.STRENGTH, days = 4))
        plans.createAuthoredPlan("Mine", "Day A")
        plans.ensurePlanSettingsMigrated()
        val before = db.planDao().allPlans()
        val id = create()
        plans.publishCreatedPlan(id, novice)
        assertEquals(before, db.planDao().allPlans().filter { it.id != id })
    }

    @Test fun `conversion runs once`() = runTest {
        val id = create()
        plans.publishCreatedPlan(id, novice)
        val before = db.planDao().allPlans()
        plans.invalidatePlanFixups()
        plans.ensurePlanSettingsMigrated()
        assertEquals(before, db.planDao().allPlans())
    }

    @Test fun `a failed conversion leaves every old plan as it was`() = runTest {
        create()
        create()
        settings.setPlanSettingsFixupDone()
        settings.setOnTodayFixupDone()
        val before = db.planDao().allPlans()
        var writes = 0
        val crashing = PlanRepository(db, settings, planDao = object : PlanDao by db.planDao() {
            override suspend fun updatePlan(plan: PlanEntity) {
                if (++writes == 2) throw SQLiteException("killed")
                db.planDao().updatePlan(plan)
            }
        })
        assertFailsWith<SQLiteException> { crashing.ensurePlanSettingsMigrated() }
        assertEquals(before, db.planDao().allPlans())
        PlanRepository(db, settings).ensurePlanSettingsMigrated()
        for (plan in db.planDao().allPlans()) {
            assertNull(plan.recipeJson)
            assertEquals(expected(novice), plan.planSettings)
        }
    }

    @Test fun `a goal typed before conversion survives`() = runTest {
        val id = create()
        val goals = mapOf("hack_squat" to 160.0, "leg_press" to 300.0)
        db.planDao().updateSettings(id, PlanSettingsJson.encode(PlanSettings(goalsLb = goals)))
        plans.publishCreatedPlan(id, novice)
        assertEquals(goals, assertNotNull(db.planDao().plan(id)).planSettings.goalsLb)
    }

    @Test fun `an unreadable old recipe is dropped and settings stay`() = runTest {
        val id = create()
        db.planDao().updateRecipe(id, """{"daysPerWeek":"x"}""")
        db.planDao().updateSettings(id, PlanSettingsJson.encode(PlanSettings(goalsLb = mapOf("hack_squat" to 120.0))))
        plans.publishCreatedPlan(id, novice)
        val plan = assertNotNull(db.planDao().plan(id))
        assertNull(plan.recipeJson)
        assertNull(plan.planSettings.equipment)
        assertEquals(mapOf("hack_squat" to 120.0), plan.planSettings.goalsLb)
        assertEquals(expected(novice).legacyMains, plan.planSettings.legacyMains)
    }

    @Test fun `a v1 recipe that only a test wrote reads as no setup recipe`() = runTest {
        val id = create()
        plans.setPlanGoal(id, "hack_squat", 175.0)
        val plan = assertNotNull(db.planDao().plan(id))
        assertNull(plan.recipeJson)
        assertEquals(175.0, plan.planSettings.goalsLb["hack_squat"])
    }

    @Test fun `legacy cardio on an old plan takes its own cardio answers`() = runTest {
        val answers = WizardAnswers(cardio = CardioPrefs(mode = CardioMode.TREADMILL, fiveKGoal = false))
        val id = create(answers)
        val day = db.programDao().daysForPlan(id).first()
        val json = """{"label":"Intervals","detail":"Run hard, recover easy.","hard":true}"""
        db.programDao().upsertDay(day.copy(cardioJson = json, cardioBlockJson = null))
        plans.publishCreatedPlan(id, answers)
        val converted = assertNotNull(db.programDao().day(id, day.dayId))
        assertNull(converted.cardioJson)
        assertEquals(CardioBlockJson.encode(assertNotNull(LegacyCardio.toBlock(json,
            V1Recipe(cardioMode = CardioMode.TREADMILL, fiveKGoal = false)))), converted.cardioBlockJson)
    }
}
