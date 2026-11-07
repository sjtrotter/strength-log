package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupV21Test {
    private val codec = BackupCodec()
    private val goals = mapOf("bb_back_squat" to 200.0)
    private val recipe = PlanRecipeJson.encode(WizardAnswers(config = LifterConfig(goalsLb = goals)))

    private fun document(recipeJson: String = recipe) = BackupDocument(
        schemaVersion = 21,
        settings = SettingsBackup(235, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, recipeJson = recipeJson)),
    )

    @Test
    fun `v21 export and restore preserve edited goals`() {
        val decoded = codec.decode(codec.encode(document()))
        assertEquals(21, decoded.schemaVersion)
        val snapshot = decoded.toSnapshot()
        val exported = snapshot.toDocument()
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        val restoredRecipe = restored.plans.single().recipeJson!!
        assertEquals(recipe, restoredRecipe)
        assertEquals(goals, PlanRecipeJson.decode(restoredRecipe, GlobalLifter()).config.goalsLb)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `v21 preserves edited goals together with offer switches and cardio Next time`() {
        fun block(seconds: Int) = CardioBlockJson.encode(CardioBlock(CardioActivity.ROAD_RUN,
            listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds)))))
        val day = ProgramDayBackup("A", "Run", "", cardioBlockJson = block(1200),
            cardioWorkingJson = block(1260), cardioKeepToPlan = true,
            cardioProgressBy = "TIME", cardioIncreasePercent = 7)
        val base = document()
        val source = base.copy(plans = listOf(base.plans.single().copy(
            days = listOf(day), offerStrengthIncreases = false, offerCardioIncreases = true,
        )))
        val decoded = codec.decode(codec.encode(source))
        assertEquals(21, decoded.schemaVersion)
        val snapshot = decoded.toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(recipe, exported.plans.single().recipeJson)
        assertEquals(day, exported.plans.single().days.single())
        assertFalse(exported.plans.single().offerStrengthIncreases)
        assertTrue(exported.plans.single().offerCardioIncreases)
        assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
    }

    @Test
    fun `v18 through v20 restore with no edited goals`() {
        val legacyRecipe = PlanRecipeJson.encode(WizardAnswers())
        assertFalse(legacyRecipe.contains("goalsLb"))
        for (version in 18..20) {
            val legacy = document(legacyRecipe).copy(schemaVersion = version)
            val decoded = codec.decode(codec.encode(legacy))
            assertEquals(version, decoded.schemaVersion)
            val snapshot = decoded.toSnapshot()
            val restoredRecipe = snapshot.plans.single().recipeJson!!
            assertEquals(legacyRecipe, restoredRecipe)
            assertTrue(PlanRecipeJson.decode(restoredRecipe, GlobalLifter()).config.goalsLb.isEmpty())
            val exported = snapshot.toDocument()
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
