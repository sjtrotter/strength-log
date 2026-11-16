package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.SplitTemplate
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
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
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
        for (level in listOf(ExperienceLevel.NOVICE, ExperienceLevel.INTERMEDIATE)) {
            val answers = WizardAnswers(equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
                config = LifterConfig(level = level, goalsLb = mapOf("goblet_squat" to 80.0)))
            val id = plans.createGeneratedPlan("Dumbbells", answers, ProgramGenerator.generate(answers))
            val squats = db.programDao().exercisesForPlan(id).filter { it.exerciseId == "goblet_squat" }
            assertTrue(squats.isNotEmpty())
            // Before #514 a structure edit cleared the prescription and replanned the accessory rule.
            for (slot in squats) {
                db.programDao().replaceExercise(slot.id, slot.exerciseId, SetStructure.STRAIGHT.name, 3)
                db.programDao().setRule(slot.id, SlotRuleJson.encode(SlotRule.defaults(SlotRole.ACCESSORY,
                    LiftGoal.STRENGTH, SetStructure.STRAIGHT, null, MovementPattern.SQUAT_BILATERAL)))
            }
            val before = db.programDao().exercisesForPlan(id)
            plans.publishCreatedPlan(id, answers)
            val stored = plans.programFor(id).first()
            val planSettings = plans.planSettingsFor(id).first()
            val mains = JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY,
                planSettings.goalsLb, planSettings.legacyMains)
            assertEquals(JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY, answers.config.goalsLb,
                ProgramGenerator.mainLiftIds(answers).toSet()), mains)
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
        val combinations = buildList {
            for (equipment in listOf(Equipment.entries.toSet(), setOf(Equipment.MACHINE),
                setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT))) {
                for (level in listOf(ExperienceLevel.NOVICE, ExperienceLevel.INTERMEDIATE)) {
                    for ((split, days) in listOf(SplitTemplate.FULL_BODY to 3, SplitTemplate.UPPER_LOWER to 4,
                        SplitTemplate.PPL to 6)) {
                        add(WizardAnswers(daysPerWeek = days, split = split, equipment = equipment,
                            config = LifterConfig(level = level)))
                    }
                }
            }
            add(WizardAnswers(daysPerWeek = 3, cardioFirst = CardioFirst(liftDays = 3)))
        }
        for (base in combinations) {
            val answers = base.copy(config = base.config.copy(goalsLb =
                mapOf(ProgramGenerator.mainLiftIds(base).first() to 150.0)))
            val id = plans.createGeneratedPlan("Old", answers, ProgramGenerator.generate(answers))
            plans.publishCreatedPlan(id, answers)
            val stored = plans.programFor(id).first()
            val settings = plans.planSettingsFor(id).first()
            assertEquals(JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY, answers.config.goalsLb,
                ProgramGenerator.mainLiftIds(answers).toSet()),
                JournalBuilder.mainLifts(stored, ExerciseCatalog.CODE_ONLY, settings.goalsLb, settings.legacyMains),
                "${base.equipment} ${base.config.level} ${base.split} ${base.daysPerWeek} ${base.cardioFirst}")
        }
    }
}
