package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupV28Test {
    private val codec = BackupCodec()
    private val answers = SetupAnswers(equipment = EquipmentPreset.DUMBBELLS.equipment)
    private fun document() = BackupDocument(
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1, bodyweightLbPrecise = 200.0),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, onToday = true,
            recipeJson = SetupRecipeJson.encode(answers),
            settingsJson = PlanSettingsJson.encode(PlanSettings(LiftGoal.GENERAL, LoadSteps.defaults(answers.unit),
                equipment = answers.equipment, goalsLb = mapOf("db_bench" to 100.0))),
            autoApply = true, suggestedDay = "A",
            days = listOf(ProgramDayBackup("A", "Full Body", "", nextRunId = "next", exercises = listOf(
                ProgramExerciseBackup(7, "goblet_squat", "STRAIGHT", 2, note = "", keepToPlan = false),
            ))))),
    )

    @Test fun `a v28 document carries recipe v2 and plan facts`() {
        val source = document()
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source, decoded)
        assertEquals(source.plans.single().recipeJson, decoded.plans.single().recipeJson)
        assertEquals(source.plans.single().settingsJson, decoded.plans.single().settingsJson)
        assertEquals(CURRENT_SCHEMA_VERSION, decoded.toSnapshot().toDocument().schemaVersion)
    }

    @Test fun `older documents cannot carry v28 payloads`() {
        for (version in listOf(9, 26, 27)) {
            val source = document().copy(schemaVersion = version)
            val recipe = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(source)) }
            assertTrue(recipe.message.orEmpty().contains("schema v28"))
            val settingsOnly = source.copy(plans = source.plans.map { it.copy(
                recipeJson = PlanRecipeJson.encode(WizardAnswers()),
                settingsJson = PlanSettingsJson.encode(PlanSettings(equipment = answers.equipment)),
            ) })
            val settings = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(settingsOnly)) }
            assertTrue(settings.message.orEmpty().contains("schema v28"))
        }
    }

    @Test fun `v28 rejects bad v2 recipes and unknown gear`() {
        val source = document()
        val badRecipe = source.copy(plans = source.plans.map {
            it.copy(recipeJson = SetupRecipeJson.encode(answers).replace("GENERAL", "POWER"))
        })
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(badRecipe)) }
        for (plan in listOf(
            source.plans.single().copy(settingsJson = """{"equipment":["HOVERBOARD"]}"""),
            source.plans.single().copy(settingsJson = """{"goalsLb":{"goblet_squat":0}}"""),
        )) {
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(source.copy(plans = listOf(plan)))) }
        }
    }

    @Test fun `a v0_11 document still restores`() {
        val source = document().copy(schemaVersion = 27, plans = document().plans.map { it.copy(
            recipeJson = PlanRecipeJson.encode(WizardAnswers()),
            settingsJson = PlanSettingsJson.encode(PlanSettings(LiftGoal.STRENGTH, LoadSteps.defaults(answers.unit))),
        ) })
        assertEquals(source, codec.decode(codec.encode(source)))
        assertEquals(source.plans.single().recipeJson, codec.decode(codec.encode(source)).toSnapshot().plans.single().recipeJson)
    }
}
