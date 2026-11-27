package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.standards.TestFormat
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupDraftJsonTest {
    @Test fun `a draft round trips`() {
        val draft = SetupDraft(step = SetupStep.RECENT, goal = LiftGoal.MUSCLE, days = 4, minutes = 75,
            equipment = EquipmentPreset.DUMBBELLS.equipment, chooseExactly = true, unit = WeightUnit.KG,
            barStep = 2, dumbbellStep = 1, stackStep = 1, experience = LiftExperience.A_YEAR_OR_MORE,
            olderAdult = true, mains = listOf("db_bench"), recent = mapOf("db_bench" to RecentDraft(50.0, 8, Effort.HARD)), balanceDays = true)
        assertEquals(draft.copy(fitnessTest = false), SetupDraftJson.decode(SetupDraftJson.encode(draft)))
    }
    @Test fun `unknown keys are ignored and missing keys default`() {
        assertEquals(SetupDraft(days = 4, fitnessTest = false), SetupDraftJson.decode("""{"v":1,"days":4,"future":true}"""))
    }
    @Test fun `even the default draft writes its version`() {
        assertTrue(SetupDraftJson.encode(SetupDraft()).contains("\"v\":1"))
    }
    @Test fun `unreadable drafts decode to null`() {
        listOf(null, "", "not json", """{"v":2}""", """{"v":1,"days":7}""", """{"v":1,"minutes":50}""",
            """{"v":1,"goal":"POWER"}""", """{"v":1,"barStep":3}""").forEach { assertNull(SetupDraftJson.decode(it)) }
    }
    @Test fun `a draft with every run and both field round trips`() {
        val draft = SetupDraft(branch = PlanBranch.BOTH, runGoal = RunGoal.ULTRA,
            runExperience = RunExperience.RACING, runEvent = RunEvent.MI50, eventDate = 21000,
            goalSeconds = 30000, test = FitnessTest.HAMR, testScore = 70, recentMetres = 21097,
            recentSeconds = 6600, longestByDistance = true, longestSeconds = 3600, longestMetres = 30000,
            activity = CardioActivity.TRAIL_RUN, marathonBestSeconds = 14400, runningInjury = true,
            strengthBlocks = 1, cardioFirst = true, together = BothAnswers.Together.SEPARATE_DAYS)
        assertEquals(draft.copy(fitnessTest = false), SetupDraftJson.decode(SetupDraftJson.encode(draft)))
    }
    @Test fun `an old draft takes every run default`() {
        assertEquals(SetupDraft(fitnessTest = false), SetupDraftJson.decode("""{"v":1,"step":"GOAL","goal":"GENERAL","days":3}"""))
    }
    @Test fun `three strength blocks is unreadable`() {
        assertNull(SetupDraftJson.decode("""{"v":1,"strengthBlocks":3}"""))
    }
    @Test fun `an invalid A time survives draft restoration`() {
        val draft = SetupFlowFixtures.run.copy(step = SetupStep.EVENT, runGoal = RunGoal.MARATHON,
            goalSeconds = INVALID_GOAL_TIME)
        val restored = requireNotNull(SetupDraftJson.decode(SetupDraftJson.encode(draft)))
        assertEquals(draft.copy(fitnessTest = false), restored)
        assertEquals(false, SetupFlowStateBuilder.canAdvance(restored))
        assertNull(SetupFlowStateBuilder.answers(restored).run.goalSeconds)
    }
    @Test fun `sprint draft fields survive version one restoration`() {
        val draft = SetupFlowFixtures.bothSprint.copy(sprintEvent = SprintEvent.YD40, sprintedRecently = true,
            hamstringStrain = true, fortyOrOlder = true)
        assertEquals(draft.copy(fitnessTest = false), SetupDraftJson.decode(SetupDraftJson.encode(draft)))
        assertEquals(1, draft.v)
        assertEquals(SetupDraft(fitnessTest = false), SetupDraftJson.decode("""{"v":1}"""))
        assertNull(SetupDraftJson.decode("""{"v":1,"sprintEvent":"M50"}"""))
    }

    @Test fun `tests survive the draft and duplicates are refused`() {
        val draft = SetupDraft(step = SetupStep.TEST, tests = listOf(TestDraft("aft_hrp", 40, 24),
            TestDraft("custom_situp_window", 45, 31, 60)), testDate = 21000)
        assertEquals(draft.copy(fitnessTest = false), SetupDraftJson.decode(SetupDraftJson.encode(draft)))
        assertNull(SetupDraftJson.decode(SetupDraftJson.encode(draft.copy(tests = listOf(TestDraft("aft_hrp"), TestDraft("aft_hrp"))))))
    }


    @Test fun `a legacy Run test draft keeps its run and maps Event to Your test`() {
        val restored = requireNotNull(SetupDraftJson.decode("""{"v":1,"branch":"RUN","runGoal":"FITNESS_TEST",
            "step":"EVENT","test":"ONE_AND_HALF_MILE","goalSeconds":780,"eventDate":21000,"strengthBlocks":1}"""))
        assertEquals(true, restored.fitnessTest)
        assertTrue(restored.testRun)
        assertEquals(SetupStep.TEST, restored.step)
        assertEquals(21000L, restored.testDate)
        val answers = SetupFlowStateBuilder.answers(restored)
        assertEquals(PlanBranch.RUN, answers.branch)
        assertEquals(FitnessTest.ONE_AND_HALF_MILE, answers.run.test)
        assertEquals(780, answers.run.goalSeconds)
        assertEquals(1, answers.run.strengthBlocks)
        assertEquals(restored, SetupDraftJson.decode(SetupDraftJson.encode(restored)))
    }

    @Test fun `legacy Both null Together becomes its stored schedule`() {
        val restored = requireNotNull(SetupDraftJson.decode("""{"v":1,"branch":"BOTH","days":4,
            "goal":"MUSCLE","runGoal":"FITNESS_TEST","goalSeconds":960}"""))
        assertTrue(restored.testRun)
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, restored.together)
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, SetupFlowStateBuilder.answers(restored).both.together)
    }

    @Test fun `a legacy Both race without tests keeps Together derived on day changes`() {
        val restored = requireNotNull(SetupDraftJson.decode("""{"v":1,"branch":"BOTH","days":3,
            "runGoal":"FIRST_5K","cardioFirst":true}"""))
        assertNull(restored.together)
        assertEquals(BothAnswers.Together.SAME_VISIT, SetupFlowStateBuilder.answers(restored).together())
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, SetupFlowStateBuilder.answers(restored.copy(days = 4)).together())
    }

    @Test fun `legacy Both race with tests keeps its resolved Together on day changes`() {
        val restored = requireNotNull(SetupDraftJson.decode("""{"v":1,"branch":"BOTH","days":3,
            "runGoal":"FIRST_5K","cardioFirst":true,"tests":[{"eventId":"aft_hrp","required":40}]}"""))
        assertEquals(BothAnswers.Together.SAME_VISIT, restored.together)
        assertEquals(BothAnswers.Together.SAME_VISIT, SetupFlowStateBuilder.answers(restored.copy(days = 4)).together())
    }

    @Test fun `a new draft with its run removed does not synthesize it on restoration`() {
        val draft = SetupDraft().chooseFitnessTest().copy(testRun = false, tests = listOf(TestDraft("aft_hrp", 40)))
        val restored = requireNotNull(SetupDraftJson.decode(SetupDraftJson.encode(draft)))
        assertEquals(draft, restored)
        assertEquals(PlanBranch.LIFT, SetupFlowStateBuilder.answers(restored).branch)
    }

    @Test fun `a legacy hand release pick retains its edition and window`() {
        val restored = requireNotNull(SetupDraftJson.decode("""{"v":1,"branch":"LIFT","step":"TEST",
            "tests":[{"eventId":"aft_hrp","required":40,"lastResult":31}]}"""))
        assertEquals(true, restored.fitnessTest)
        val event = TestEvents.get(restored.tests.single().eventId)
        assertEquals("hand_release_pushup", event.exerciseId)
        assertEquals(120, SetupFlowStateBuilder.answers(restored).tests.single().goal.ref.windowSeconds)
        assertTrue(event in TestEvents.editions(event.exerciseId,
            TestFormat.REPS_IN_WINDOW))
    }

}
