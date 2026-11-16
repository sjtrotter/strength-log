package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.data.insertRuledOldWizardPlan
import cloud.trotter.log.strength.data.runLaunchFixups
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.plan.PlanScreenBuilder

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
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
class OldPlanMainsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var plans: PlanRepository
    private lateinit var storeScope: CoroutineScope
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

    @Test fun `an old wizard main switched to straight keeps its History card and Plan goal`() = runTest {
        for (plan in listOf(OldWizardPlans.DUMBBELL_NOVICE, OldWizardPlans.DUMBBELL_INTERMEDIATE)) {
            val id = db.insertRuledOldWizardPlan(plan, settings, "Dumbbells")
            val squats = db.programDao().exercisesForPlan(id).filter { it.exerciseId == "goblet_squat" }
            assertTrue(squats.isNotEmpty())
            // Before #514 a structure edit cleared the prescription and replanned the accessory rule.
            for (slot in squats) {
                db.programDao().replaceExercise(slot.id, slot.exerciseId, SetStructure.STRAIGHT.name, 3)
                db.programDao().setRule(slot.id, SlotRuleJson.encode(SlotRule.defaults(SlotRole.ACCESSORY,
                    LiftGoal.STRENGTH, SetStructure.STRAIGHT, null, MovementPattern.SQUAT_BILATERAL)))
            }
            val before = db.programDao().exercisesForPlan(id)
            db.runLaunchFixups(settings)
            plans.publishPlannedPlan(id)
            val stored = plans.programFor(id).first()
            val planSettings = plans.planSettingsFor(id).first()
            val mains = JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY,
                planSettings.goalsLb, planSettings.legacyMains)
            assertEquals(JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY, plan.goalsLb,
                plan.mains.toSet()), mains)
            val card = JournalBuilder.trajectories(mains,
                listOf(TopSetRow(1, 1000, "goblet_squat", 60.0)), WeightUnit.LB).single()
            assertEquals("goblet_squat", card.exerciseId)
            assertEquals(listOf(60f), card.points.map { it.value })
            assertEquals(80f, card.goalValue)
            val state = PlanScreenBuilder.buildUiState(assertNotNull(db.planDao().plan(id)), stored,
                ExerciseCatalog.CODE_ONLY, WeightUnit.LB)
            assertEquals("80", state.goals.single { it.exerciseId == "goblet_squat" }.display)
            assertEquals(before, db.programDao().exercisesForPlan(id))
        }
    }

    @Test fun `old plan History mains are unchanged by conversion`() = runTest {
        for (plan in OldWizardPlans.ALL) {
            val id = db.insertRuledOldWizardPlan(plan, settings)
            db.runLaunchFixups(settings)
            val plans = PlanRepository(db, settings)
            plans.publishPlannedPlan(id)
            plans.setPlanGoal(id, plan.mains.first(), 150.0)
            val goals = plan.goalsLb + (plan.mains.first() to 150.0)
            val stored = plans.programFor(id).first()
            val settings = plans.planSettingsFor(id).first()
            assertEquals(JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY, goals,
                plan.mains.toSet()),
                JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY, settings.goalsLb, settings.legacyMains),
                plan.recipeJson)
        }
    }
}
