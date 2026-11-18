package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.CardioRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupV31Test {
    private val codec = BackupCodec()
    private val run = SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.RUN), RunPhase.LADDER)
    private val both = SetupRecipeJson.encode(SetupAnswers(branch = PlanBranch.BOTH), RunPhase.LADDER)
    private val ladder = CardioRuleJson.encode(CardioRule(1, credits = 1, ladderCounted = listOf(21000),
        ladderRaised = 20990))
    private val untimed = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 5000, null, 21030))

    private fun document(recipe: String? = null, rule: String? = null, goal: String? = null, version: Int = 31) =
        BackupDocument(schemaVersion = version,
            settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
            plans = listOf(PlanBackup(1, "Run", "GENERATED", 1000, onToday = true, recipeJson = recipe, goalJson = goal,
                suggestedDay = "A", days = listOf(ProgramDayBackup("A", "Easy", "", nextRunId = "next",
                    cardioRuleJson = rule, exercises = emptyList())))))

    @Test fun `v31 round trips run and both plans`() {
        for (recipe in listOf(run, both)) {
            val source = document(recipe, ladder, untimed)
            val decoded = codec.decode(codec.encode(source))
            assertEquals(31, decoded.schemaVersion)
            assertEquals(source.plans, decoded.plans)
            val exported = decoded.toSnapshot().toDocument()
            assertEquals(source.plans.single().recipeJson, exported.plans.single().recipeJson)
            assertEquals(ladder, exported.plans.single().days.single().cardioRuleJson)
            assertEquals(untimed, exported.plans.single().goalJson)
            assertEquals(exported, codec.decode(codec.encode(exported)))
        }
    }

    @Test fun `older documents with v31 shapes are inconsistent`() {
        val shapes = listOf(document(recipe = run), document(recipe = both), document(rule = ladder),
            document(rule = CardioRuleJson.encode(CardioRule(1, ladderCounted = listOf(21000)))),
            document(rule = CardioRuleJson.encode(CardioRule(1, ladderRaised = 21000))),
            document(goal = untimed))
        for (shape in shapes) for (version in listOf(1, 26, 28, 29, 30)) {
            val error = assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(shape.copy(schemaVersion = version)))
            }
            assertTrue(error.message.orEmpty().contains("requires schema v31"))
        }
        val old = document(version = 30)
        assertEquals(old, codec.decode(codec.encode(old)))
    }
}
