package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.planner.SprintEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupV32Test {
    private val codec = BackupCodec()
    private val recipes = listOf(PlanBranch.SPRINT, PlanBranch.BOTH).map { branch ->
        SetupRecipeJson.encode(SetupAnswers(branch = branch, sprint = SprintAnswers(SprintEvent.YD40, true, true, true),
            both = if (branch == PlanBranch.BOTH) BothAnswers(sprint = true) else BothAnswers()))
    }
    private fun document(recipe: String, version: Int = CURRENT_SCHEMA_VERSION) = BackupDocument(schemaVersion = version,
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Sprint", "GENERATED", 1000, onToday = true, recipeJson = recipe,
            suggestedDay = "A", days = listOf(ProgramDayBackup("A", "Sprint", "", exercises = emptyList())))))

    @Test fun `v32 round trips sprint plans`() {
        assertEquals(32, CURRENT_SCHEMA_VERSION)
        for (recipe in recipes) {
            val source = document(recipe)
            val decoded = codec.decode(codec.encode(source))
            assertEquals(source, decoded)
            assertEquals(recipe, decoded.toSnapshot().toDocument().plans.single().recipeJson)
        }
    }

    @Test fun `older documents with sprint recipes are inconsistent`() {
        for (recipe in recipes) for (version in listOf(1, 26, 28, 30, 31)) {
            val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(recipe, version))) }
            assertTrue(error.message.orEmpty().contains("requires schema v32"))
        }
    }
}
