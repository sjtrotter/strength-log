package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.V1Recipe
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import kotlin.test.assertFailsWith
import org.junit.Test

class PlanRecipeJsonTest {
    @Test
    fun `edited goals round-trip and old recipes decode with none`() {
        val answers = WizardAnswers().let { it.copy(config = it.config.copy(goalsLb = mapOf("bb_bench" to 205.0))) }
        val encoded = PlanRecipeJson.encode(answers)
        PlanRecipeJson.validate(encoded)
        assertEquals(answers.config.goalsLb, PlanRecipeJson.read(encoded)!!.goalsLb)
        val legacy = PlanRecipeJson.encode(WizardAnswers())
        assertFalse(legacy.contains("goalsLb"))
        assertEquals(emptyMap<String, Double>(), PlanRecipeJson.read(legacy)!!.goalsLb)

        for (invalid in listOf("-1", "0", "2000.1", "NaN", "1e999")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                PlanRecipeJson.validate(encoded.replace("205.0", invalid))
            }
        }
        PlanRecipeJson.validate(encoded.replace("205.0", "2000.0"))
    }


    @Test
    fun `unknown cardio mode defaults and unknown equipment is dropped`() {
        val json = """
            {"daysPerWeek":1,"split":"NEW_SPLIT","anchorScheme":"NEW_ANCHORS",
             "deadliftVariant":"NEW_VARIANT","emphasis":"NEW_EMPHASIS",
             "cardioMode":"NEW_MODE","cardioPlacement":"NEW_PLACEMENT","fiveKGoal":true,
             "equipment":["BARBELL","FUTURE_EQUIPMENT"],"futureField":123}
        """.trimIndent()

        assertEquals(
            V1Recipe(equipment = setOf(Equipment.BARBELL)),
            PlanRecipeJson.read(json),
        )
        assertEquals(
            V1Recipe(equipment = emptySet()),
            PlanRecipeJson.read(json.replace("1,", "7,").replace("\"BARBELL\",", "")),
        )
    }


    private fun recipe(cardio: String): String = PlanRecipeJson.encode(WizardAnswers()).dropLast(1) + ",\"cardioFirst\":$cardio}"

    @Test
    fun `validate rejects invalid cardio first answers`() {
        val valid = """{"activity":"ROAD_RUN","seconds":1800,"sessions":3,"liftDays":0}"""
        val invalid = listOf(
            valid.replace("\"seconds\":1800", "\"seconds\":1800,\"metres\":5000"),
            valid.replace("\"seconds\":1800,", ""),
            valid.replace("1800", "0"),
            valid.replace("1800", "86401"),
            valid.replace("\"seconds\":1800", "\"metres\":200001"),
            valid.replace("ROAD_RUN", "UNKNOWN"),
            valid.replace("\"sessions\":3", "\"sessions\":7"),
            valid.replace("\"liftDays\":0", "\"liftDays\":1"),
        )
        invalid.forEach { cardio -> assertFailsWith<IllegalArgumentException> { PlanRecipeJson.validate(recipe(cardio)) } }
    }


    @Test fun `rest days validate`() {
        val encoded = PlanRecipeJson.encode(WizardAnswers(cardioFirst = CardioFirst(restDays = 2)))
        PlanRecipeJson.validate(encoded)
        for (invalid in listOf(-1, 3)) {
            assertFailsWith<IllegalArgumentException> {
                PlanRecipeJson.validate(encoded.replace("\"restDays\":2", "\"restDays\":$invalid"))
            }
        }
    }

    @Test fun `read keeps gear goals and cardio answers`() {
        val answers = WizardAnswers(equipment = setOf(Equipment.DUMBBELL),
            cardio = CardioPrefs(mode = CardioMode.LOW_IMPACT, fiveKGoal = false),
            config = LifterConfig(goalsLb = mapOf("db_bench" to 90.0)))
        assertEquals(V1Recipe(setOf(Equipment.DUMBBELL), mapOf("db_bench" to 90.0), CardioMode.LOW_IMPACT, false),
            PlanRecipeJson.read(PlanRecipeJson.encode(answers)))
    }

    @Test fun `read drops unknown gear and bad goals`() {
        val json = PlanRecipeJson.encode(WizardAnswers(equipment = setOf(Equipment.DUMBBELL)))
            .replace("[\"DUMBBELL\"]", "[\"DUMBBELL\",\"HOVERBOARD\"]").dropLast(1) +
            ",\"goalsLb\":{\"a\":0,\"b\":2001,\"\":5,\"c\":100}}"
        assertEquals(V1Recipe(setOf(Equipment.DUMBBELL), mapOf("c" to 100.0)), PlanRecipeJson.read(json))
    }

    @Test fun `read refuses v2 and malformed text`() {
        assertNull(PlanRecipeJson.read(SetupRecipeJson.encode(SetupAnswers())))
        assertNull(PlanRecipeJson.read("{}"))
        assertNull(PlanRecipeJson.read("not json"))
    }
}
