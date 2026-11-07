package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupV20Test {
    private val codec = BackupCodec()
    private val goals = mapOf("bb_back_squat" to 200.0)
    private val recipe = PlanRecipeJson.encode(WizardAnswers(config = LifterConfig(goalsLb = goals)))

    private fun document(recipeJson: String = recipe) = BackupDocument(
        settings = SettingsBackup(235, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, recipeJson = recipeJson)),
    )

    @Test
    fun `v20 export and restore preserve edited goals`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(20, exported.schemaVersion)
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        val restoredRecipe = restored.plans.single().recipeJson!!
        assertEquals(recipe, restoredRecipe)
        assertEquals(goals, PlanRecipeJson.decode(restoredRecipe, GlobalLifter()).config.goalsLb)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `a v19 document restores with no edited goals and re-exports as v20`() {
        val legacyRecipe = PlanRecipeJson.encode(WizardAnswers())
        assertFalse(legacyRecipe.contains("goalsLb"))
        for (version in listOf(18, 19)) {
            val legacy = document(legacyRecipe).copy(schemaVersion = version)
            val snapshot = codec.decode(codec.encode(legacy)).toSnapshot()
            val restoredRecipe = snapshot.plans.single().recipeJson!!
            assertEquals(legacyRecipe, restoredRecipe)
            assertTrue(PlanRecipeJson.decode(restoredRecipe, GlobalLifter()).config.goalsLb.isEmpty())
            val exported = snapshot.toDocument()
            assertEquals(20, exported.schemaVersion)
            assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
        }
    }

    @Test
    fun `a malformed goal is rejected before restore`() {
        for (invalid in listOf("-1", "0", "2000.1", "NaN", "1e999")) {
            val malformed = document(recipe.replace("200.0", invalid))
            assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(malformed)) }
        }
    }
}
