package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.test.assertFailsWith

class PlanRecipeJsonTest {
    @Test
    fun `edited goals round-trip and old recipes decode with none`() {
        val answers = WizardAnswers().let { it.copy(config = it.config.copy(goalsLb = mapOf("bb_bench" to 205.0))) }
        val encoded = PlanRecipeJson.encode(answers)
        PlanRecipeJson.validate(encoded)
        assertEquals(answers, PlanRecipeJson.decode(encoded, GlobalLifter()))
        val legacy = PlanRecipeJson.encode(WizardAnswers())
        assertFalse(legacy.contains("goalsLb"))
        assertEquals(emptyMap<String, Double>(), PlanRecipeJson.decode(legacy, GlobalLifter()).config.goalsLb)

        for (invalid in listOf("-1", "0", "2000.1", "NaN", "1e999")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                PlanRecipeJson.validate(encoded.replace("205.0", invalid))
            }
        }
        PlanRecipeJson.validate(encoded.replace("205.0", "2000.0"))
    }

    @Test
    fun `recipe omits global lifter and decodes with the current globals`() {
        val answers = WizardAnswers()
        val encoded = PlanRecipeJson.encode(answers)
        val global = GlobalLifter(210.0, 55, ExperienceLevel.ADVANCED)

        assertFalse(encoded.contains("bodyweight"))
        assertFalse(encoded.contains("age"))
        assertFalse(encoded.contains("level"))
        assertEquals(answers.copy(config = global.toConfig()), PlanRecipeJson.decode(encoded, global))
    }

    @Test
    fun `unknown enums and invalid days use defaults and unknown equipment is dropped`() {
        val json = """
            {"daysPerWeek":1,"split":"NEW_SPLIT","anchorScheme":"NEW_ANCHORS",
             "deadliftVariant":"NEW_VARIANT","emphasis":"NEW_EMPHASIS",
             "cardioMode":"NEW_MODE","cardioPlacement":"NEW_PLACEMENT","fiveKGoal":true,
             "equipment":["BARBELL","FUTURE_EQUIPMENT"],"futureField":123}
        """.trimIndent()

        assertEquals(
            WizardAnswers(equipment = setOf(Equipment.BARBELL)),
            PlanRecipeJson.decode(json, GlobalLifter()),
        )
        assertEquals(
            WizardAnswers(equipment = emptySet()),
            PlanRecipeJson.decode(json.replace("1,", "7,").replace("\"BARBELL\",", ""), GlobalLifter()),
        )
    }
}
