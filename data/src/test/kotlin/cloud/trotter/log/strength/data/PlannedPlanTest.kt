package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.data.serialization.hasSetupRecipe
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.PlanDraft
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.RecentNumber
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SlotKey
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
class PlannedPlanTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var plans: PlanRepository
    private lateinit var storeScope: CoroutineScope
    private val answers = SetupAnswers(goal = LiftGoal.STRENGTH, days = 4, experience = LiftExperience.A_YEAR_OR_MORE,
        recent = mapOf("bb_back_squat" to RecentNumber(275.0, 5, Effort.GOOD)))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("planned-plan", ".preferences_pb")
        })
        plans = PlanRepository(db, settings)
    }

    @After fun tearDown() { db.close(); storeScope.cancel() }

    private suspend fun assertDraft(id: Long, draft: PlanDraft) {
        assertEquals(draft.program, plans.programFor(id).first())
        for (day in draft.program.days) {
            val slots = db.programDao().exercisesForDay(id, day.id)
            for ((position, slot) in slots.withIndex()) {
                val expected = day.exercises[position]
                assertEquals(expected.rule, SlotRuleJson.decode(slot.ruleJson))
                assertEquals(expected.superset?.rule, SlotRuleJson.decode(slot.partnerRuleJson))
                val rows = draft.working[SlotKey(day.id, position)]
                if (!rows.isNullOrEmpty()) {
                    val log = assertNotNull(db.programDao().logForSlot(id, day.id, slot.id, Slot.MAIN))
                    assertEquals(rows, SetJson.decodeSets(log.setsJson))
                }
            }
        }
    }

    @Test fun `a run plan records its phase`() = runTest {
        val input = SetupAnswers(branch = PlanBranch.RUN, days = 4,
            run = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.NEW))
        val id = plans.createPlannedPlan("Run", input)
        val recipe = assertNotNull(db.planDao().plan(id)?.recipeJson)
        assertEquals(RunPhase.LADDER, SetupRecipeJson.phase(recipe))
    }

    @Test fun `a planned plan writes its program rules rows recipe and settings`() = runTest {
        val id = plans.createPlannedPlan("Strength, 4 days", answers)
        val plan = assertNotNull(db.planDao().plan(id))
        assertEquals(PlanOrigin.GENERATED.name, plan.origin)
        assertTrue(plan.autoApply)
        assertEquals(6, plan.deloadEveryCycles)
        assertTrue(plan.hasSetupRecipe)
        assertEquals(answers, plan.setupAnswers(WeightUnit.LB))
        assertEquals(PlanSettings(LiftGoal.STRENGTH, answers.steps, equipment = answers.equipment), plan.planSettings)
        assertEquals("A", plan.suggestedDayId)
        assertDraft(id, Planner.plan(answers))
    }

    @Test fun `history loads seed the plan`() = runTest {
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Day B", null, 1000, 235.0))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "bb_bench", "Bench", Slot.MAIN, 0,
            "WORK", 185.0, 8, true, rir = 2)))
        val a = answers.copy(recent = emptyMap())
        val id = plans.createPlannedPlan("Strength", a)
        val draft = Planner.plan(a, KnownLoads(mapOf("bb_bench" to LoggedSet(185.0, 8, SetKind.WORK, rir = 2))))
        assertDraft(id, draft)
        val bench = draft.program.days.flatMap { day -> day.exercises.mapIndexedNotNull { index, slot ->
            SlotKey(day.id, index).takeIf { slot.exerciseId == "bb_bench" && slot.rule?.role == SlotRole.MAIN }
        } }.single()
        assertFalse(draft.working[bench] == Planner.plan(a, KnownLoads.NONE).working[bench])
    }

    @Test fun `a crash mid-write leaves no plan`() = runTest {
        var calls = 0
        val crashing = PlanRepository(db, settings, mintRunId = { if (++calls == 2) error("killed") else "run-$calls" })
        assertFailsWith<IllegalStateException> { crashing.createPlannedPlan("Strength", answers) }
        assertTrue(db.planDao().allPlans().isEmpty())
        assertTrue(db.programDao().allExercises().isEmpty())
        assertTrue(db.programDao().allLogs().isEmpty())
        assertTrue(db.programDao().allDays().isEmpty())
    }

    @Test fun `an empty selected plan is filled, not duplicated`() = runTest {
        val selected = db.planDao().insertPlan(PlanEntity(0, "Legacy", PlanOrigin.GENERATED.name, 0, 1000))
        settings.setSelectedPlanId(selected)
        assertEquals(selected, plans.createPlannedPlan("Strength", answers))
        assertEquals(1, db.planDao().allPlans().size)
        assertDraft(selected, Planner.plan(answers))
    }

    @Test fun `publishing selects the plan and puts it on Today`() = runTest {
        val profile = settings.lifterProfileFlow.first()
        val id = plans.createPlannedPlan("Strength", answers)
        plans.publishPlannedPlan(id)
        assertEquals(id, settings.selectedPlanIdFlow.first())
        assertTrue(assertNotNull(db.planDao().plan(id)).onToday)
        val archived = plans.createPlannedPlan("Archived", answers)
        db.planDao().updateArchived(archived, true)
        plans.publishPlannedPlan(archived)
        assertEquals(id, settings.selectedPlanIdFlow.first())
        assertFalse(assertNotNull(db.planDao().plan(archived)).onToday)
        assertEquals(profile, settings.lifterProfileFlow.first())
    }

    @Test fun `preview matches what Start writes`() = runTest {
        val preview = plans.previewPlan(answers)
        assertTrue(db.planDao().allPlans().isEmpty())
        assertTrue(db.programDao().allLogs().isEmpty())
        val id = plans.createPlannedPlan("Strength", answers)
        assertDraft(id, preview)
    }

    @Test fun `a New lifter on dumbbells gets three full-body days at the floor, calibrating`() = runTest {
        val a = SetupAnswers(goal = LiftGoal.GENERAL, days = 3, minutes = 45,
            equipment = EquipmentPreset.DUMBBELLS.equipment, experience = LiftExperience.NEW)
        val preview = plans.previewPlan(a)
        val id = plans.createPlannedPlan("General", a)
        assertDraft(id, preview)
        assertEquals(a, assertNotNull(db.planDao().plan(id)).setupAnswers(WeightUnit.LB))
        val program = plans.programFor(id).first()
        assertEquals(List(3) { ProgramDayKind.STRENGTH }, program.days.map { it.kind })
        assertEquals(List(3) { "Full Body" }, program.days.map { it.title })
        val mains = program.days.flatMap { day -> db.programDao().exercisesForDay(id, day.id) }
            .filter { SlotRuleJson.decode(it.ruleJson)?.role == SlotRole.MAIN }
        // Revision 27's General floor is 4: bench credits 1 front-delt set, so shoulder press needs 3.
        val mainSets = mapOf("goblet_squat" to 2, "db_shoulder" to 3, "db_rdl" to 2, "db_row" to 2, "db_bench" to 2)
        assertEquals(mainSets.keys, mains.map { it.exerciseId }.toSet())
        var checkedCalibration = false
        for (slot in mains) {
            val rule = assertNotNull(SlotRuleJson.decode(slot.ruleJson))
            val entry = ExerciseLibrary.get(slot.exerciseId)
            val log = assertNotNull(db.programDao().logForSlot(id, slot.dayId, slot.id, Slot.MAIN))
            val rows = SetJson.decodeSets(log.setsJson)
            assertEquals(SetStructure.STRAIGHT.name, slot.structure)
            assertEquals(mainSets.getValue(slot.exerciseId), rows.size)
            assertEquals(RepTarget.Range(8, 15, 8), rule.target)
            assertTrue(rule.state.calibrating)
            if (entry.tracking == TrackingType.WEIGHTED && Equipment.DUMBBELL in entry.equipment) {
                assertTrue(rows.all { it.weightLb == 10.0 && it.reps == 8 })
                if (!checkedCalibration) {
                    val next = Progression.nextSet(rule, listOf(rows[0].copy(weightLb = 20.0, done = true, rir = Effort.GOOD.rir)),
                        rows[1], Progression.Context(WeightUnit.LB, entry.equipment.toSet(), LoadSteps.defaults(WeightUnit.LB), 0))
                    assertEquals(20.0, next.weightLb)
                    checkedCalibration = true
                }
            }
        }
        assertTrue(checkedCalibration)
    }

    @Test fun `a planned plan keeps lift goals in its settings`() = runTest {
        val id = plans.createPlannedPlan("Strength", answers)
        val recipe = assertNotNull(db.planDao().plan(id)).recipeJson
        plans.setPlanGoal(id, "bb_back_squat", 300.0)
        val plan = assertNotNull(db.planDao().plan(id))
        assertEquals(mapOf("bb_back_squat" to 300.0), plan.planSettings.goalsLb)
        assertEquals(recipe, plan.recipeJson)
        assertEquals(plan.planSettings.goalsLb, plans.planSettingsFor(id).first().goalsLb)
        plans.setPlanGoal(id, "bb_back_squat", null)
        assertTrue(assertNotNull(db.planDao().plan(id)).planSettings.goalsLb.isEmpty())
        assertEquals(recipe, assertNotNull(db.planDao().plan(id)).recipeJson)
    }


    @Test fun `a planned plan's gear comes from settings`() = runTest {
        val a = answers.copy(equipment = EquipmentPreset.DUMBBELLS.equipment, recent = emptyMap())
        val id = plans.createPlannedPlan("Dumbbells", a)
        assertEquals(a.equipment, plans.planSettingsFor(id).first().equipmentOrAll)
    }
}
