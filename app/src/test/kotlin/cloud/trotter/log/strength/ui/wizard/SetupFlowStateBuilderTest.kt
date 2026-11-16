package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.PlanReason
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SessionBudget
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SlotKey
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupFlowStateBuilderTest {
    private val builder = SetupFlowStateBuilder
    private val reference = builder.answers(SetupFlowFixtures.reference)
    private fun preview(a: SetupAnswers) = builder.planPreview(Planner.plan(a), a) as PlanPreviewUi.Ready

    @Test fun `the default draft maps to the default setup answers`() { assertEquals(SetupAnswers(), builder.answers(SetupDraft())) }

    @Test fun `step lists follow sub-goal and experience`() {
        val common = listOf(SetupStep.GOAL, SetupStep.SUB_GOAL, SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT)
        assertEquals(common + SetupStep.PLAN, builder.steps(SetupDraft()))
        assertEquals(common + listOf(SetupStep.LIFTS, SetupStep.PLAN), builder.steps(SetupDraft(goal = LiftGoal.STRENGTH)))
        assertEquals(common + listOf(SetupStep.LIFTS, SetupStep.RECENT, SetupStep.PLAN), builder.steps(SetupFlowFixtures.reference))
        assertEquals(common + listOf(SetupStep.RECENT, SetupStep.PLAN), builder.steps(SetupDraft(experience = LiftExperience.UNDER_A_YEAR)))
    }
    @Test fun `a gear set without weighted mains skips Recent numbers`() {
        val gear = EquipmentPreset.BODYWEIGHT.equipment
        assertEquals(Planner.defaultMains(gear).any { ExerciseLibrary.get(it).tracking == TrackingType.WEIGHTED },
            SetupStep.RECENT in builder.steps(SetupDraft(equipment = gear, goal = LiftGoal.STRENGTH, experience = LiftExperience.A_YEAR_OR_MORE)))
    }
    @Test fun `a step dropped from the list falls back to the one before it`() {
        assertEquals(SetupStep.ABOUT, builder.currentStep(SetupDraft(step = SetupStep.LIFTS)))
        assertEquals(SetupStep.LIFTS, builder.currentStep(SetupDraft(step = SetupStep.RECENT, goal = LiftGoal.STRENGTH)))
    }
    @Test fun `first step options are the setup defaults in both units`() {
        WeightUnit.entries.forEach { unit ->
            assertEquals(LoadSteps.defaults(unit), LoadSteps(builder.stepOptionsLb(StepRow.BAR, unit)[0],
                builder.stepOptionsLb(StepRow.DUMBBELL, unit)[0], builder.stepOptionsLb(StepRow.STACK, unit)[0]))
        }
        assertEquals(WeightUnit.KG.toLb(1.25), builder.answers(SetupDraft(unit = WeightUnit.KG, barStep = 1)).steps.barLb)
    }
    @Test fun `recent numbers keep only complete in-range rows for current mains`() {
        val d = SetupDraft(goal = LiftGoal.STRENGTH, experience = LiftExperience.A_YEAR_OR_MORE, recent = mapOf(
            "bb_back_squat" to RecentDraft(275.0, 5), "bb_bench" to RecentDraft(185.0, 11), "ohp" to RecentDraft(0.0, 5),
            "trap_dl" to RecentDraft(2500.0, 5), "goblet_squat" to RecentDraft(50.0, 8)))
        assertEquals(setOf("bb_back_squat"), builder.answers(d).recent.keys)
        assertTrue(builder.answers(d.copy(experience = LiftExperience.NEW)).recent.isEmpty())
    }
    @Test fun `a chosen main replaces its row only`() {
        val defaults = Planner.defaultMains(SetupDraft().equipment)
        val mains = defaults.map { if (it == "bb_bench") "db_bench" else it }
        assertEquals(mains, builder.answers(SetupDraft(mains = mains)).mains)
        assertEquals(4, mains.intersect(defaults.toSet()).size)
    }
    @Test fun `preset follows the equipment set`() {
        EquipmentPreset.entries.forEach { assertEquals(it, builder.preset(it.equipment)) }
        assertNull(builder.preset(EquipmentPreset.FULL_GYM.equipment - Equipment.TRAP_BAR))
    }
    @Test fun `prefill round trips the answers`() {
        val a = SetupAnswers(goal = LiftGoal.MUSCLE, days = 4, minutes = 75, unit = WeightUnit.KG,
            steps = LoadSteps(WeightUnit.KG.toLb(1.25), WeightUnit.KG.toLb(1.0), WeightUnit.KG.toLb(2.5)),
            equipment = EquipmentPreset.DUMBBELLS.equipment, experience = LiftExperience.UNDER_A_YEAR, olderAdult = true,
            mains = Planner.defaultMains(EquipmentPreset.DUMBBELLS.equipment), balanceDays = true)
        assertEquals(a, builder.answers(builder.draftFrom(a)))
        assertEquals(SetupStep.GOAL, builder.draftFrom(a).step)
    }
    @Test fun `prefill resets mains when a stored main is excluded`() {
        val a = SetupAnswers(mains = listOf("bb_back_squat"), excluded = setOf("bb_back_squat"))
        assertNull(builder.draftFrom(a).mains)
    }
    @Test fun `plan rows read the draft`() {
        val draft = Planner.plan(reference)
        val rows = preview(reference).rows
        assertEquals(draft.program.days.map { it.id }, rows.map { it.dayId })
        assertTrue(rows.single { it.title == "Rest" }.rest)
        assertEquals("Squat", rows.first().mainName)
        assertEquals(275.0, rows.first().mainLoadLb)
        draft.program.days.zip(rows).forEach { (day, row) ->
            assertEquals(ceil(SessionBudget.seconds(day, reference.goal) / 60.0).toInt(), row.minutes)
            assertEquals(day.exercises.sumOf { if (it.superset == null) 1 else 2 }, row.lifts)
        }
    }
    @Test fun `case 36 shows the prompt with every main kept`() {
        val a = builder.answers(SetupFlowFixtures.overBudget)
        val draft = Planner.plan(a)
        val ui = preview(a)
        assertTrue(ui.over)
        assertEquals(ceil(draft.reasons.filterIsInstance<PlanReason.OverBudget>().single().seconds / 60.0).toInt(), ui.rows.single().overMinutes)
        assertEquals(Planner.defaultMains(a.equipment), ui.droppable)
        assertTrue(ui.longerAvailable)
        assertEquals(5, draft.program.days.single().exercises.count { it.rule?.role == SlotRole.MAIN })
    }
    @Test fun `the balance offer counts the days it would add`() {
        // Audit 2026-11-13 decision 12.
        assertEquals(2, preview(SetupAnswers(olderAdult = true, days = 1)).balanceOffer)
        assertEquals(1, preview(SetupAnswers(olderAdult = true, days = 2)).balanceOffer)
        val added = preview(SetupAnswers(olderAdult = true, days = 1, balanceDays = true))
        assertEquals(2, added.balanceAdded)
        assertNull(added.balanceOffer)
        val three = preview(SetupAnswers(olderAdult = true))
        assertNull(three.balanceAdded)
        assertNull(three.balanceOffer)
    }
    @Test fun `why lines follow the draft`() {
        assertEquals(listOf(PlanWhy.RepsLeft, PlanWhy.Calibrating, PlanWhy.Minutes), preview(SetupAnswers()).why)
        assertTrue(PlanWhy.LightWeek(6) in preview(reference).why)
        assertTrue(PlanWhy.Calibrating in preview(reference).why)
        val a = SetupAnswers(goal = LiftGoal.STRENGTH, days = 1, minutes = 30, experience = LiftExperience.A_YEAR_OR_MORE)
        Planner.plan(a).reasons.filterIsInstance<PlanReason.ShortSession>().forEach {
            val line = when (it.step) { 0 -> PlanWhy.Floor(it.dayId, 30); in 1..3 -> PlanWhy.Trimmed(it.dayId, 30); else -> PlanWhy.OneSet(it.dayId, 30) }
            assertTrue(line in preview(a).why)
        }
        val older = a.copy(olderAdult = true)
        val over = Planner.plan(older).reasons.filterIsInstance<PlanReason.OverBudget>().single()
        assertTrue(PlanWhy.Over("A", ceil(over.seconds / 60.0).toInt()) in preview(older).why)
        assertTrue(PlanWhy.BalanceOffered(1) in preview(older).why)
        listOf(a, older, reference).forEach { assertEquals(PlanWhy.Minutes, preview(it).why.last()) }
    }
    @Test fun `no equipment gives the empty plan`() {
        val a = SetupAnswers(equipment = emptySet())
        assertEquals(PlanPreviewUi.Empty, builder.planPreview(Planner.plan(a), a))
    }
    @Test fun `the default plan matches what the pilots walk`() {
        val days = Planner.plan(SetupAnswers()).program.days
        assertEquals(listOf("A", "B", "C"), days.map { it.id })
        assertTrue(days.all { it.title == "Full Body" })
        assertEquals(listOf("bb_back_squat", "ohp", "rdl", "db_bench", "cs_row", "cable_lateral", "plank"), days.first().exercises.map { it.exerciseId })
        assertTrue(days.first().exercises.all { it.targetSets == 2 && it.structure == SetStructure.STRAIGHT })
        val r = Planner.plan(reference)
        assertEquals("bb_back_squat", r.program.days.first().exercises.first().exerciseId)
        assertEquals(SetStructure.RAMP_TOP_BACKOFF, r.program.days.first().exercises.first().structure)
        assertEquals(5, r.working.getValue(SlotKey("A", 0)).size)
        assertFalse(r.program.days.isEmpty())
    }
}
