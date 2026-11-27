package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.RecentRace
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.model.ProgramDayKind
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
import java.time.DayOfWeek
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.planner.SprintFloor
import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestFormat
import cloud.trotter.log.strength.domain.standards.TestPick
import cloud.trotter.log.strength.domain.planner.LiftTest
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.serialization.PlanSettings

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
    @Test fun `run and both steps follow the answers`() {
        val run = listOf(SetupStep.GOAL, SetupStep.RUN_GOAL, SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT)
        assertEquals(run + listOf(SetupStep.WHERE, SetupStep.PLAN), builder.steps(SetupDraft(branch = PlanBranch.RUN)))
        RunGoal.entries.filter { it != RunGoal.CARDIO_FITNESS }.forEach { goal ->
            assertEquals(run + listOf(SetupStep.EVENT, SetupStep.WHERE, SetupStep.PLAN),
                builder.steps(SetupDraft(branch = PlanBranch.RUN, runGoal = goal)))
        }
        // #646: Both resolves the run goal before offering Test, so sprint skips it.
        val both = listOf(SetupStep.GOAL, SetupStep.ORDER, SetupStep.SUB_GOAL, SetupStep.RUN_GOAL,
            SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT)
        assertEquals(both + listOf(SetupStep.WHERE, SetupStep.TOGETHER, SetupStep.PLAN),
            builder.steps(SetupDraft(branch = PlanBranch.BOTH)))
        assertEquals(both + listOf(SetupStep.LIFTS, SetupStep.RECENT, SetupStep.EVENT, SetupStep.WHERE,
            SetupStep.TOGETHER, SetupStep.PLAN), builder.steps(SetupFlowFixtures.reference.copy(
                branch = PlanBranch.BOTH, runGoal = RunGoal.HALF_MARATHON)))
        assertEquals(both + listOf(SetupStep.RECENT, SetupStep.WHERE, SetupStep.TOGETHER, SetupStep.PLAN),
            builder.steps(SetupDraft(branch = PlanBranch.BOTH, experience = LiftExperience.UNDER_A_YEAR)))
    }

    @Test fun `Together is offered only where it is a choice`() {
        for (days in 1..6) for (first in listOf(false, true)) {
            assertEquals(days >= 3 || days == 2 && first,
                SetupStep.TOGETHER in builder.steps(SetupDraft(branch = PlanBranch.BOTH, days = days, cardioFirst = first)))
        }
        assertFalse(builder.offersTogether(SetupDraft(branch = PlanBranch.RUN)))
    }

    @Test fun `a hidden Together answer maps to Same visit`() {
        val draft = SetupDraft(branch = PlanBranch.BOTH, together = BothAnswers.Together.SEPARATE_DAYS)
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, builder.answers(draft).together())
        val one = draft.copy(days = 1)
        val answers = builder.answers(one)
        assertEquals(BothAnswers.Together.SAME_VISIT, answers.both.together)
        assertTrue(Planner.plan(answers).sessions.isNotEmpty())
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, one.together)
    }

    @Test fun `run answers drop what RunAnswers rejects`() {
        val half = SetupDraft(branch = PlanBranch.RUN, runGoal = RunGoal.HALF_MARATHON)
        val invalid = half.copy(runEvent = RunEvent.K5, marathonBestSeconds = 14400,
            activity = CardioActivity.STATIONARY_BIKE, recentMetres = 5000, goalSeconds = -1, longestSeconds = 0)
        assertEquals(RunAnswers(goal = RunGoal.HALF_MARATHON), builder.answers(invalid).run)
        assertNull(builder.answers(half.copy(recentSeconds = 1500)).run.recent)
        assertNull(builder.answers(half.copy(recentMetres = 200001, recentSeconds = 1500)).run.recent)
        assertNull(builder.answers(half.copy(eventDate = Long.MAX_VALUE)).run.dateEpochDay)
        val test = half.copy(runGoal = RunGoal.FITNESS_TEST, eventDate = 21000)
        assertNull(builder.answers(test.copy(test = FitnessTest.COOPER)).run.dateEpochDay)
        assertNull(builder.answers(test.copy(test = FitnessTest.PACER)).run.dateEpochDay)
        val pacer = builder.answers(test.copy(test = FitnessTest.PACER, testScore = 1000)).run
        assertNull(pacer.testScore)
        assertNull(pacer.dateEpochDay)
        assertEquals(21000L, builder.answers(test.copy(test = FitnessTest.HAMR, testScore = 50)).run.dateEpochDay)
        assertEquals(SetupAnswers(), builder.answers(invalid.copy(branch = PlanBranch.LIFT)))
    }

    @Test fun `event and fitness targets must meet their current floor in both branches`() {
        for (branch in listOf(PlanBranch.RUN, PlanBranch.BOTH)) {
            val middle = SetupDraft(branch = branch, step = SetupStep.EVENT, runGoal = RunGoal.MIDDLE_DISTANCE,
                runEvent = RunEvent.M800, goalSeconds = 101)
            assertTrue(builder.canAdvance(middle))
            assertEquals(101, builder.answers(middle).run.goalSeconds)
            val longer = middle.copy(runEvent = RunEvent.M1500)
            assertFalse(builder.canAdvance(longer))
            assertNull(builder.answers(longer).run.goalSeconds)
            assertTrue(builder.canAdvance(longer.copy(goalSeconds = 150)))
            assertEquals(150, builder.answers(longer.copy(goalSeconds = 150)).run.goalSeconds)
            assertTrue(builder.canAdvance(longer.copy(goalSeconds = null)))
            val test = middle.copy(runGoal = RunGoal.FITNESS_TEST, test = FitnessTest.ONE_AND_HALF_MILE, goalSeconds = 241)
            assertTrue(builder.canAdvance(test))
            assertEquals(241, builder.answers(test).run.goalSeconds)
            val twoMile = test.copy(test = FitnessTest.TWO_MILE)
            assertFalse(builder.canAdvance(twoMile))
            assertNull(builder.answers(twoMile).run.goalSeconds)
            assertTrue(builder.canAdvance(twoMile.copy(goalSeconds = 321)))
            assertEquals(321, builder.answers(twoMile.copy(goalSeconds = 321)).run.goalSeconds)
            assertTrue(builder.canAdvance(twoMile.copy(goalSeconds = null)))
            for (untimed in listOf(FitnessTest.COOPER, FitnessTest.PACER, FitnessTest.HAMR)) {
                assertTrue(builder.canAdvance(twoMile.copy(test = untimed, goalSeconds = INVALID_GOAL_TIME)))
            }
        }
    }

    @Test fun `prefill round trips run and both answers`() {
        val half = RunAnswers(goal = RunGoal.HALF_MARATHON, experience = RunExperience.SOME,
            event = RunEvent.HALF, dateEpochDay = 21000, goalSeconds = 7000, recent = RecentRace(5000, 1500),
            longest = Measure.Time(3600), runningInjury = true, strengthBlocks = 1)
        listOf(
            SetupAnswers(branch = PlanBranch.RUN, run = half),
            SetupAnswers(branch = PlanBranch.RUN, run = half.copy(goal = RunGoal.ULTRA, event = RunEvent.K50,
                activity = CardioActivity.TRAIL_RUN, marathonBestSeconds = 14400, longest = Measure.Distance(30000))),
            SetupAnswers(branch = PlanBranch.RUN, run = RunAnswers(goal = RunGoal.FITNESS_TEST,
                goalSeconds = 960, dateEpochDay = 21000)),
            SetupAnswers(branch = PlanBranch.BOTH, days = 4, run = half.copy(strengthBlocks = 2),
                both = BothAnswers(true, BothAnswers.Together.SEPARATE_DAYS)),
        ).forEach { assertEquals(it, builder.answers(builder.draftFrom(it))) }
    }

    @Test fun `the default run and both plans match what the pilots walk`() {
        val plan = Planner.plan(builder.answers(SetupDraft(branch = PlanBranch.RUN)))
        assertEquals(RunPhase.LADDER, plan.phase)
        assertEquals(listOf("A", "B", "C", "D"), plan.program.days.map { it.id })
        plan.program.days.take(3).forEach { assertEquals(CardioRule(1), it.cardioRule) }
        assertEquals(ProgramDayKind.REST, plan.program.days.last().kind)
        // #646: Both resolves the run goal before offering Test, so sprint skips it.
        assertEquals(listOf(SetupStep.GOAL, SetupStep.ORDER, SetupStep.SUB_GOAL, SetupStep.RUN_GOAL,
            SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT, SetupStep.WHERE, SetupStep.TOGETHER, SetupStep.PLAN),
            builder.steps(SetupDraft(branch = PlanBranch.BOTH)))
    }

    @Test fun `the run plan preview shows its runs and blocks`() {
        val a = builder.answers(SetupDraft(branch = PlanBranch.RUN, runGoal = RunGoal.HALF_MARATHON,
            runExperience = RunExperience.SOME, days = 4, longestSeconds = 3600))
        val plan = Planner.plan(a)
        val ui = preview(a)
        assertEquals(4, plan.sessions.size)
        assertEquals(3, plan.sessions.values.count { it != RunSession.LONG })
        assertEquals(2, plan.program.days.count { it.exercises.isNotEmpty() })
        plan.program.days.filter { it.exercises.isNotEmpty() }.forEach {
            assertTrue(plan.sessions[it.id] in listOf(RunSession.EASY, RunSession.THRESHOLD))
        }
        assertTrue(plan.program.days.single { plan.sessions[it.id] == RunSession.LONG }.cardioBlock!!.sets.filter { it.role == cloud.trotter.log.strength.domain.model.CardioRole.WORK }
            .sumOf { (it.end as? Measure.Time)?.seconds ?: 0 } <= 3960)
        plan.program.days.zip(ui.rows).forEach { (day, row) -> assertEquals(day.cardioBlock, row.cardio) }
        assertTrue(preview(a.copy(run = a.run.copy(strengthBlocks = 0))).rows.all { it.lifts == 0 })
    }

    @Test fun `a New marathoner with no runs starts on the ladder`() {
        assertEquals(RunPhase.LADDER, Planner.plan(builder.answers(SetupDraft(branch = PlanBranch.RUN,
            runGoal = RunGoal.MARATHON))).phase)
    }

    @Test fun `no run gives the no run line and no date line`() {
        val a = builder.answers(SetupDraft(branch = PlanBranch.BOTH, minutes = 30,
            runGoal = RunGoal.HALF_MARATHON, eventDate = 21000))
        val noRun = builder.planPreview(Planner.plan(a, todayEpochDay = 20999), a) as PlanPreviewUi.Ready
        assertTrue(PlanWhy.NoRun(30) in noRun.why)
        assertFalse(PlanWhy.DateTooClose in noRun.why)
        assertTrue(noRun.longerAvailable)
        val longer = a.copy(minutes = 60)
        val plan = Planner.plan(longer, todayEpochDay = 20999)
        assertTrue(plan.sessions.isNotEmpty())
        assertEquals(PlanReason.DateTooClose in plan.reasons, PlanWhy.DateTooClose in
            (builder.planPreview(plan, longer) as PlanPreviewUi.Ready).why)
    }
    private val sprintDraft = SetupDraft(branch = PlanBranch.SPRINT)
    private val bothSprint = SetupDraft(branch = PlanBranch.BOTH, sprintCardio = true)

    @Test fun `sprint steps follow the branch`() {
        val sprint = listOf(SetupStep.GOAL, SetupStep.RUN_GOAL, SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT)
        assertEquals(sprint + SetupStep.PLAN, builder.steps(sprintDraft))
        assertEquals(sprint + listOf(SetupStep.RECENT, SetupStep.PLAN),
            builder.steps(sprintDraft.copy(experience = LiftExperience.UNDER_A_YEAR, goal = LiftGoal.STRENGTH)))
        val both = listOf(SetupStep.GOAL, SetupStep.SUB_GOAL, SetupStep.RUN_GOAL, SetupStep.WEEK, SetupStep.EQUIPMENT, SetupStep.ABOUT)
        assertEquals(both + SetupStep.PLAN, builder.steps(bothSprint))
        assertEquals(both + listOf(SetupStep.LIFTS, SetupStep.PLAN), builder.steps(bothSprint.copy(goal = LiftGoal.STRENGTH)))
        for (draft in listOf(sprintDraft, bothSprint)) {
            assertTrue(builder.steps(draft).intersect(setOf(SetupStep.ORDER, SetupStep.EVENT, SetupStep.WHERE, SetupStep.TOGETHER)).isEmpty())
        }
    }

    @Test fun `sprint answers map and normalize`() {
        val sprint = sprintDraft.copy(sprintEvent = SprintEvent.YD40, sprintedRecently = true,
            hamstringStrain = true, fortyOrOlder = true, goal = LiftGoal.MUSCLE)
        val mapped = builder.answers(sprint)
        assertEquals(SprintAnswers(SprintEvent.YD40, true, true, true), mapped.sprint)
        assertEquals(LiftGoal.GENERAL, mapped.goal)
        assertEquals(RunAnswers(), mapped.run)
        val both = builder.answers(sprint.copy(branch = PlanBranch.BOTH, sprintCardio = true, cardioFirst = true,
            together = BothAnswers.Together.SEPARATE_DAYS))
        assertEquals(BothAnswers(sprint = true), both.both)
        assertEquals(LiftGoal.MUSCLE, both.goal)
        assertEquals(mapped.sprint, both.sprint)
        assertEquals(RunAnswers(), both.run)
        for (draft in listOf(sprintDraft.copy(fortyOrOlder = true), sprintDraft.copy(olderAdult = true),
            sprintDraft.copy(hamstringStrain = true))) assertTrue(builder.answers(draft).careful)
    }

    @Test fun `prefill round trips sprint answers`() {
        for (draft in listOf(sprintDraft, bothSprint)) {
            val answers = builder.answers(draft.copy(sprintEvent = SprintEvent.M200, sprintedRecently = true,
                fortyOrOlder = true, hamstringStrain = true))
            assertEquals(answers, builder.answers(builder.draftFrom(answers)))
        }
    }

    @Test fun `the floor blocks Your lifts and Your plan`() {
        val draft = bothSprint.copy(step = SetupStep.LIFTS, goal = LiftGoal.STRENGTH,
            mains = listOf("bb_bench", "ohp", "bb_row"))
        val answers = builder.answers(draft)
        assertEquals(SprintFloor.SQUAT_OR_HINGE, Planner.sprintLowerMainMissing(answers))
        assertFalse(builder.canAdvance(draft))
        assertEquals(PlanPreviewUi.NeedsLowerMain(SprintFloor.SQUAT_OR_HINGE),
            builder.planPreview(Planner.plan(builder.answers(bothSprint)), answers))
        val careful = draft.copy(fortyOrOlder = true, mains = listOf("trap_dl", "bb_bench"))
        assertEquals(SprintFloor.SQUAT, Planner.sprintLowerMainMissing(builder.answers(careful)))
        assertFalse(builder.canAdvance(careful))
        assertNull(Planner.sprintLowerMainMissing(builder.answers(careful.copy(mains = listOf("bb_back_squat")))))
    }

    @Test fun `sprint fitting names what it cut`() {
        fun names(vararg ids: String) = ids.map { ExerciseLibrary.get(it).name }
        val one = builder.answers(sprintDraft.copy(days = 1, minutes = 30))
        assertTrue(PlanReason.ShortSession("A", 3) in Planner.plan(one).reasons)
        assertEquals(PlanWhy.Dropped("A", names("rdl", "bss"), true, 30),
            preview(one).why.filterIsInstance<PlanWhy.Dropped>().single())
        val three = builder.answers(sprintDraft.copy(minutes = 30, sprintedRecently = true))
        val plan = Planner.plan(three)
        assertTrue(PlanReason.ShortSession("D", 7) in plan.reasons)
        assertEquals(PlanWhy.Dropped("D", names("pogo_hop", "a_skip"), true, 30),
            preview(three).why.filterIsInstance<PlanWhy.Dropped>().single { it.dayId == "D" })
        val ui = assertNotNull(preview(three).sprint)
        assertTrue(ui.nordic)
        assertFalse(ui.jumps)
        assertEquals(PlanWhy.Minutes, preview(three).why.last())
        assertEquals(1, preview(three).why.count { it == PlanWhy.SplitDay })
    }

    @Test fun `sprint fitting falls back when the reference has the same cuts`() {
        for (minutes in listOf(75, 90)) for (step in listOf(3, 7)) {
            val answers = builder.answers(sprintDraft.copy(minutes = minutes))
            val fitted = Planner.plan(answers).copy(reasons = listOf(PlanReason.ShortSession("A", step)))
            val ui = builder.planPreview(fitted, answers) as PlanPreviewUi.Ready
            assertTrue(assertNotNull(ui.sprint).shortened)
            assertTrue(ui.why.filterIsInstance<PlanWhy.Dropped>().isEmpty())
            assertTrue((if (step == 3) PlanWhy.Trimmed("A", minutes) else PlanWhy.OneSet("A", minutes)) in ui.why)
        }
    }

    @Test fun `sprint fitting at seventy five minutes names full exercise names`() {
        val answers = builder.answers(sprintDraft.copy(days = 2, minutes = 75,
            sprintEvent = SprintEvent.M200, sprintedRecently = true, experience = LiftExperience.A_YEAR_OR_MORE))
        val cuts = preview(answers).why.filterIsInstance<PlanWhy.Dropped>()
        assertTrue(cuts.isNotEmpty())
        assertTrue(cuts.all { it.names.isNotEmpty() || it.extraSets })
        assertTrue(cuts.any { ExerciseLibrary.get("bss").name in it.names })
        assertTrue(cuts.all { "Bulgarians" !in it.names })
    }

    @Test fun `the short two day sprint reason requires two requested days`() {
        val two = builder.answers(sprintDraft.copy(days = 2, minutes = 30))
        assertTrue(PlanWhy.ShortTwoDaySprint in preview(two).why)
        val three = builder.answers(sprintDraft.copy(minutes = 30, mains = listOf("bb_back_squat")))
        assertEquals(2, Planner.plan(three).program.days.count { it.kind != ProgramDayKind.REST })
        assertFalse(PlanWhy.ShortTwoDaySprint in preview(three).why)
    }

    @Test fun `the full plan offer reads fullDoseMinutes`() {
        val two = builder.answers(sprintDraft.copy(days = 2, sprintedRecently = true))
        assertEquals(75, assertNotNull(preview(two).sprint).fullDoseMinutes)
        assertEquals(90, assertNotNull(preview(two.copy(sprint = SprintAnswers(SprintEvent.M200, true))).sprint).fullDoseMinutes)
        assertTrue(assertNotNull(preview(two).sprint).shortened)
    }

    @Test fun `the suggested week follows the rotation`() {
        fun week(draft: SetupDraft) = assertNotNull(preview(builder.answers(draft)).sprint).week
        val two = listOf(DayOfWeek.MONDAY to SprintWeekKind.SPRINT, DayOfWeek.THURSDAY to SprintWeekKind.SPRINT)
        val three = listOf(two[0], DayOfWeek.TUESDAY to SprintWeekKind.UPPER, two[1])
        assertEquals(two, week(sprintDraft.copy(days = 2)))
        assertEquals(three, week(sprintDraft))
        assertEquals(three + listOf(DayOfWeek.FRIDAY to SprintWeekKind.UPPER, DayOfWeek.SATURDAY to SprintWeekKind.TEMPO,
            DayOfWeek.SUNDAY to SprintWeekKind.UPPER), week(sprintDraft.copy(days = 6)))
        val noUpper = two
        for (draft in listOf(sprintDraft, bothSprint)) {
            assertEquals(noUpper, week(draft.copy(mains = listOf("bb_back_squat"))))
            assertEquals(noUpper + (DayOfWeek.SATURDAY to SprintWeekKind.TEMPO),
                week(draft.copy(days = 6, mains = listOf("bb_back_squat"))))
        }
    }

    @Test fun `each sprint weekday template matches its rotation size`() {
        for (days in 1..6) for (draft in listOf(sprintDraft, bothSprint)) {
            for (mains in listOf(null, listOf("bb_back_squat"))) {
                val answers = builder.answers(draft.copy(days = days, mains = mains))
                assertEquals(Planner.plan(answers).program.rotationDays.size, builder.sprintWeekdays(days).size)
            }
        }
    }

    @Test fun `every sprint training day gets its own weekday`() {
        for (days in 1..6) for (draft in listOf(sprintDraft, bothSprint)) {
            for (mains in listOf(null, listOf("bb_back_squat"))) {
                val answers = builder.answers(draft.copy(days = days, mains = mains))
                val rotation = Planner.plan(answers).program.rotationDays
                val weekdays = builder.sprintWeekdays(days)
                val training = rotation.mapIndexedNotNull { index, day ->
                    if (day.kind == ProgramDayKind.REST) null
                    else assertNotNull(weekdays.getOrNull(index), "$days days ${draft.branch} $mains ${day.id}")
                }
                assertEquals(training.size, training.distinct().size)
            }
        }
    }

    @Test fun `the high day line follows the slots`() {
        val full = assertNotNull(preview(builder.answers(sprintDraft)).sprint)
        assertEquals(R.string.setup_flow_high_day_full, sprintHighDayLabel(full.jumps, full.nordic))
        val noNordic = assertNotNull(preview(builder.answers(sprintDraft.copy(days = 1))).sprint)
        assertEquals(R.string.setup_flow_high_day_no_nordic, sprintHighDayLabel(noNordic.jumps, noNordic.nordic))
        val noJumps = assertNotNull(preview(builder.answers(sprintDraft.copy(minutes = 30, sprintedRecently = true))).sprint)
        assertEquals(R.string.setup_flow_high_day_no_jumps, sprintHighDayLabel(noJumps.jumps, noJumps.nordic))
        val neither = assertNotNull(preview(builder.answers(sprintDraft.copy(days = 1, minutes = 30, sprintedRecently = true))).sprint)
        assertEquals(R.string.setup_flow_high_day_lifts, sprintHighDayLabel(neither.jumps, neither.nordic))
    }

    @Test fun `the default sprint plans match what the pilots walk`() {
        val plan = Planner.plan(builder.answers(sprintDraft))
        assertEquals(listOf("A", "B", "C", "D", "E"), plan.program.days.map { it.id })
        assertEquals(mapOf("A" to RunSession.ACCELERATION, "D" to RunSession.MAX_VELOCITY), plan.sessions)
        assertEquals(listOf(SetupStep.GOAL, SetupStep.SUB_GOAL, SetupStep.RUN_GOAL, SetupStep.WEEK,
            SetupStep.EQUIPMENT, SetupStep.ABOUT, SetupStep.PLAN), builder.steps(bothSprint))
    }

    @Test fun `a New tester too close for the ladder and event gets the ladder date line`() {
        val answers = builder.answers(SetupFlowFixtures.test.copy(eventDate = SetupFlowFixtures.TODAY + 56))
        val draft = Planner.plan(answers, todayEpochDay = SetupFlowFixtures.TODAY)
        val why = (builder.planPreview(draft, answers) as PlanPreviewUi.Ready).why
        assertTrue(PlanWhy.LadderDateTooClose(true) in why)
        assertTrue(PlanWhy.Ladder(8) in why)
        assertFalse(PlanWhy.DateTooClose in why)
    }

    @Test fun `a New middle distance runner gets the race line`() {
        val answers = builder.answers(SetupFlowFixtures.run.copy(runGoal = RunGoal.MIDDLE_DISTANCE,
            runEvent = RunEvent.M1500, eventDate = SetupFlowFixtures.TODAY + 56))
        val draft = Planner.plan(answers, todayEpochDay = SetupFlowFixtures.TODAY)
        val why = (builder.planPreview(draft, answers) as PlanPreviewUi.Ready).why
        assertTrue(PlanWhy.LadderDateTooClose(false) in why)
        assertTrue(PlanWhy.Ladder(8) in why)
        assertFalse(PlanWhy.DateTooClose in why)
    }

    @Test fun `a phased ladder plan keeps the generic date line`() {
        val answers = builder.answers(SetupFlowFixtures.half.copy(longestSeconds = 1000,
            eventDate = SetupFlowFixtures.TODAY + 84))
        val draft = Planner.plan(answers, todayEpochDay = SetupFlowFixtures.TODAY)
        val why = (builder.planPreview(draft, answers) as PlanPreviewUi.Ready).why
        assertTrue(PlanWhy.DateTooClose in why)
        assertTrue(PlanWhy.Ladder(5) in why)
    }

    @Test fun `the ladder row follows the start phase`() {
        for (fixture in listOf(SetupFlowFixtures.run, SetupFlowFixtures.half, SetupFlowFixtures.test, SetupFlowFixtures.both)) {
            val answers = builder.answers(fixture)
            val draft = Planner.plan(answers, todayEpochDay = SetupFlowFixtures.TODAY)
            val why = (builder.planPreview(draft, answers) as PlanPreviewUi.Ready).why
            assertEquals(draft.phase == RunPhase.LADDER, why.any { it is PlanWhy.Ladder })
        }
    }

    @Test fun `only the first screen entry and existing events offer Your test`() {
        assertEquals(listOf(SetupStep.GOAL, SetupStep.TEST, SetupStep.WEEK, SetupStep.EQUIPMENT,
            SetupStep.ABOUT, SetupStep.PLAN), builder.steps(SetupDraft().chooseFitnessTest()))
        for (draft in listOf(SetupFlowFixtures.reference, SetupFlowFixtures.both, SetupFlowFixtures.run,
            SetupFlowFixtures.sprint, SetupFlowFixtures.bothSprint)) assertFalse(SetupStep.TEST in builder.steps(draft))
        val mixed = SetupFlowFixtures.both.copy(runGoal = RunGoal.FIRST_5K, tests = listOf(TestDraft("aft_hrp", 40)))
        assertTrue(SetupStep.TEST in builder.steps(mixed))
        assertEquals(PlanBranch.BOTH, builder.answers(mixed).branch)
    }

    @Test fun `a chosen event blocks Next until its required number is valid`() {
        val draft = SetupFlowFixtures.reference.copy(step = SetupStep.TEST, tests = listOf(TestDraft("aft_hrp")))
        assertFalse(builder.canAdvance(draft))
        assertTrue(builder.canAdvance(draft.copy(tests = listOf(TestDraft("aft_hrp", 40)))))
        for (test in listOf(TestDraft("aft_hrp", 0), TestDraft("aft_hrp", 3601),
            TestDraft("aft_hrp", 40, 3601), TestDraft("unknown", 40),
            TestDraft("custom_pushup_window", 40), TestDraft("custom_pushup_window", 40, windowSeconds = 9),
            TestDraft("custom_pushup_window", 40, windowSeconds = 3601))) {
            assertFalse(builder.canAdvance(draft.copy(tests = listOf(test))))
        }
        assertTrue(builder.canAdvance(draft.copy(tests = listOf(TestDraft("custom_pushup_window", 40, windowSeconds = 60)))))
        assertTrue(builder.canAdvance(draft.copy(tests = listOf(TestDraft("aft_hrp", 40, 0)))))
        assertFalse(builder.canAdvance(SetupDraft().chooseFitnessTest().copy(step = SetupStep.TEST)))
    }

    @Test fun `answers carry the tests and draftFrom restores them`() {
        val hrp = TestRef("aft_hrp", 1, 120)
        val draft = SetupDraft(tests = listOf(TestDraft("aft_hrp", 40, 24)), testDate = 21000)
        assertEquals(listOf(TestAnswer(TestEventGoal(hrp, 40), 24)), builder.answers(draft).tests)
        assertEquals(21000L, builder.answers(draft).testDateEpochDay)
        for (test in listOf(TestDraft("aft_hrp", 40, 24), TestDraft("custom_pushup_window", 40, 24, 60),
            TestDraft("aft_plank", 220, 95))) {
            val input = draft.copy(tests = listOf(test))
            val restored = builder.draftFrom(builder.answers(input))
            assertEquals(input.tests, restored.tests)
            assertEquals(input.testDate, restored.testDate)
        }
        for (branch in listOf(SetupFlowFixtures.run, SetupFlowFixtures.sprint, SetupFlowFixtures.bothSprint)) {
            val answers = builder.answers(branch.copy(tests = draft.tests, testDate = 21000))
            assertTrue(answers.tests.isEmpty())
            assertNull(answers.testDateEpochDay)
        }
        assertNull(builder.answers(draft.copy(testDate = Long.MAX_VALUE)).testDateEpochDay)
    }

    @Test fun `the Why sheet lists the prep block`() {
        val answers = builder.answers(SetupDraft(tests = listOf(TestDraft("aft_hrp", 40))))
        assertTrue(PlanWhy.TestPrep(1) in preview(answers).why)
    }

    @Test fun `case 59 switching to max unbroken clears the window`() {
        val draft = SetupDraft().chooseFitnessTest().pick(TestPick.Library(ExerciseLibrary.get("pullup")))
            .copy(step = SetupStep.TEST, tests = listOf(TestDraft("pullup:REPS_IN_WINDOW", 20, 8, 60)))
            .testFormat("pullup", TestFormat.REPS_CONTINUOUS)
        assertNull(draft.tests.single().windowSeconds)
        val answers = builder.answers(draft)
        assertEquals(TestRef("pullup:REPS_CONTINUOUS", 1), answers.tests.single().goal.ref)
        assertTrue(builder.canAdvance(draft))
        val missing = answers.copy(equipment = EquipmentPreset.DUMBBELLS.equipment)
        val training = preview(missing).testTraining.single()
        assertEquals(R.string.test_training_equipment, training.reason)
        assertEquals(listOf(Equipment.PULLUP_BAR), training.equipment)
        assertTrue(training.slots.isEmpty())
        val army = SetupDraft(tests = listOf(TestDraft("aft_hrp", 40, 31)))
        assertEquals(army, army.testFormat("hand_release_pushup", TestFormat.REPS_IN_WINDOW))
    }

    @Test fun `case 60 a required or last hold above the cap does not advance`() {
        val draft = SetupDraft().chooseFitnessTest().copy(step = SetupStep.TEST, olderAdult = true,
            tests = listOf(TestDraft("wall_sit:MAX_HOLD", 90, 110)))
        assertTrue(builder.canAdvance(draft))
        assertFalse(builder.canAdvance(draft.copy(tests = listOf(TestDraft("wall_sit:MAX_HOLD", 130, 110)))))
        assertFalse(builder.canAdvance(draft.copy(tests = listOf(TestDraft("wall_sit:MAX_HOLD", 90, 130)))))
        assertTrue(builder.answers(draft.copy(tests = listOf(TestDraft("wall_sit:MAX_HOLD", 130, 110)))).tests.isEmpty())
        assertEquals(120, TestEvents.requiredLimit(TestEvents.get("wall_sit:MAX_HOLD")))
    }

    @Test fun `case 61 a run plus bench test derives Same visit at four days and Separate days at five, and keeps an explicit choice`() {
        val draft = SetupDraft().chooseFitnessTest().pick(TestPick.Library(ExerciseLibrary.get("bb_bench")))
            .pick(TestPick.Run(FitnessTest.TWO_MILE)).copy(runExperience = RunExperience.SOME, longestSeconds = 1500,
                goalSeconds = 960, testLifts = listOf(LiftTestDraft("bb_bench", 5, 225.0)))
        assertEquals(3, draft.days)
        assertNull(draft.together)
        val four = builder.answers(draft.copy(days = 4))
        assertEquals(BothAnswers.Together.SAME_VISIT, four.both.together)
        assertEquals(R.string.test_training_same_visit, preview(four).testTraining.single().reason)
        val five = builder.answers(draft.copy(days = 5))
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, five.both.together)
        assertTrue(preview(five).testTraining.single().slots.isNotEmpty())
        assertEquals(BothAnswers.Together.SAME_VISIT, builder.answers(draft.copy(days = 6,
            together = BothAnswers.Together.SAME_VISIT)).both.together)
    }

    @Test fun `case 55 a hinge pick at four days derives Same visit`() {
        val draft = SetupDraft().chooseFitnessTest().pick(TestPick.Run(FitnessTest.TWO_MILE))
            .pick(TestPick.Library(ExerciseLibrary.get("trap_dl"))).copy(days = 4)
        assertNull(draft.together)
        assertEquals(BothAnswers.Together.SAME_VISIT, builder.answers(draft).both.together)
    }

    @Test fun `case 55 a dumbbell gym leaves the deadlift untrained and keeps its requirement`() {
        val draft = SetupDraft().chooseFitnessTest().pick(TestPick.Library(ExerciseLibrary.get("trap_dl")))
            .copy(equipment = EquipmentPreset.DUMBBELLS.equipment, testLifts = listOf(LiftTestDraft("trap_dl", 3, 340.0)))
        val answers = builder.answers(draft)
        assertEquals(listOf(LiftTest("trap_dl", 3)), answers.lifts)
        assertEquals(340.0, draft.testLifts.single().goalLb)
        assertEquals(R.string.test_training_equipment, preview(answers).testTraining.single().reason)
        assertFalse("trap_dl" in builder.currentMains(draft))
    }

    @Test fun `case 61 a nondefault test lift binds its row and survives Review answers`() {
        val lift = LiftTest("incline_db", 5)
        val draft = SetupDraft().chooseFitnessTest().pick(TestPick.Library(ExerciseLibrary.get(lift.exerciseId)))
            .copy(testLifts = listOf(LiftTestDraft(lift.exerciseId, 5, 100.0)), experience = LiftExperience.UNDER_A_YEAR)
        val answers = builder.answers(draft)
        assertEquals(listOf("incline_db"), builder.mainChoices(draft)[2])
        assertEquals("incline_db", SetupFlowFixtures.state(draft).lifts.single { it.row == 2 }.selectedId)
        assertFalse("bb_bench" in assertNotNull(answers.mains))
        val restored = builder.draftFrom(answers, SetupRecipeJson.encode(answers), PlanSettings(goalsLb = mapOf("incline_db" to 110.0)))
        assertEquals(listOf(LiftTestDraft("incline_db", 5, 110.0)), restored.testLifts)
        assertEquals(answers, builder.answers(restored))
        val slots = Planner.plan(answers).program.days.flatMap { it.exercises }.filter { it.exerciseId == "incline_db" }
        assertTrue(slots.any { it.rule?.role == SlotRole.MAIN && it.structure == SetStructure.STRAIGHT })
        assertTrue(SetupStep.LIFTS in builder.steps(draft))
        assertTrue(SetupStep.RECENT in builder.steps(draft))
    }

    @Test fun `a legacy Both Muscle test keeps its screens and resolved schedule`() {
        val answers = SetupAnswers(branch = PlanBranch.BOTH, goal = LiftGoal.MUSCLE, days = 4,
            experience = LiftExperience.A_YEAR_OR_MORE,
            run = RunAnswers(goal = RunGoal.FITNESS_TEST, goalSeconds = 960), both = BothAnswers(cardioFirst = false))
        val draft = builder.draftFrom(answers, SetupRecipeJson.encode(answers), PlanSettings())
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, draft.together)
        for (step in listOf(SetupStep.SUB_GOAL, SetupStep.ORDER, SetupStep.LIFTS, SetupStep.RECENT)) {
            assertTrue(step in builder.steps(draft))
        }
        val mapped = builder.answers(draft)
        assertEquals(answers.copy(both = answers.both.copy(together = answers.together())), mapped)
        assertEquals(Planner.plan(answers).program, Planner.plan(mapped).program)
    }

    @Test fun `a mixed race and test plan keeps its run when a test lift is added and restored`() {
        val original = SetupAnswers(branch = PlanBranch.BOTH, days = 4,
            run = RunAnswers(goal = RunGoal.FIVE_TO_TEN_K, goalSeconds = 3300),
            tests = listOf(TestAnswer(TestEventGoal(TestRef("custom_pushup_window", 1, 60), 30))))
        val draft = builder.draftFrom(original).pick(TestPick.Library(ExerciseLibrary.get("incline_db")))
            .copy(testLifts = listOf(LiftTestDraft("incline_db", 5, 100.0)))
        val answers = builder.answers(draft)
        val restored = builder.draftFrom(answers, SetupRecipeJson.encode(answers), PlanSettings(goalsLb = mapOf("incline_db" to 100.0)))
        assertEquals(false, restored.fitnessTest)
        assertEquals(original.run, builder.answers(restored).run)
        assertEquals(PlanBranch.BOTH, builder.answers(restored).branch)
        assertEquals(listOf(LiftTestDraft("incline_db", 5, 100.0)), restored.testLifts)
        assertTrue(SetupStep.EVENT in builder.steps(restored))
    }

    @Test fun `a trimmed prep day shows one Why line for its day`() {
        val answers = SetupAnswers(tests = listOf(TestAnswer(TestEventGoal(TestRef("custom_pushup_window", 1, 60), 30))))
        val draft = Planner.plan(answers).copy(reasons = listOf(PlanReason.ShortSession("A", 0), PlanReason.ShortSession("A", 2)))
        val why = (builder.planPreview(draft, answers) as PlanPreviewUi.Ready).why
        assertFalse(PlanWhy.Floor("A", 60) in why)
        assertEquals(1, why.count { it == PlanWhy.Trimmed("A", 60) })
    }

}
