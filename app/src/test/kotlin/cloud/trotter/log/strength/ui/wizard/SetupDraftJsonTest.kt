package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.model.CardioActivity
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
        assertEquals(draft, SetupDraftJson.decode(SetupDraftJson.encode(draft)))
    }
    @Test fun `unknown keys are ignored and missing keys default`() {
        assertEquals(SetupDraft(days = 4), SetupDraftJson.decode("""{"v":1,"days":4,"future":true}"""))
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
        assertEquals(draft, SetupDraftJson.decode(SetupDraftJson.encode(draft)))
    }
    @Test fun `an old draft takes every run default`() {
        assertEquals(SetupDraft(), SetupDraftJson.decode("""{"v":1,"step":"GOAL","goal":"GENERAL","days":3}"""))
    }
    @Test fun `three strength blocks is unreadable`() {
        assertNull(SetupDraftJson.decode("""{"v":1,"strengthBlocks":3}"""))
    }
    @Test fun `an invalid A time survives draft restoration`() {
        val draft = SetupFlowFixtures.run.copy(step = SetupStep.EVENT, runGoal = RunGoal.MARATHON,
            goalSeconds = INVALID_GOAL_TIME)
        val restored = requireNotNull(SetupDraftJson.decode(SetupDraftJson.encode(draft)))
        assertEquals(draft, restored)
        assertEquals(false, SetupFlowStateBuilder.canAdvance(restored))
        assertNull(SetupFlowStateBuilder.answers(restored).run.goalSeconds)
    }
}
