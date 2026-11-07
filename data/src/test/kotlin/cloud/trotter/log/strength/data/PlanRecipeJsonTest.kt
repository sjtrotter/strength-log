package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.Measure
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

    @Test
    fun `cardio first answers round trip`() {
        for (measure in listOf(Measure.Time(1800), Measure.Distance(4828))) {
            val answers = WizardAnswers(daysPerWeek = 2, cardioFirst = CardioFirst(
                CardioActivity.TRAIL_RUN, measure, sessions = 4, liftDays = 2,
            ))
            val encoded = PlanRecipeJson.encode(answers)
            PlanRecipeJson.validate(encoded)
            assertEquals(answers, PlanRecipeJson.decode(encoded, GlobalLifter()))
        }
    }

    @Test
    fun `cardio first answers and edited goals round trip together`() {
        val defaults = WizardAnswers()
        val answers = defaults.copy(
            daysPerWeek = 2,
            config = defaults.config.copy(goalsLb = mapOf("bb_back_squat" to 200.0)),
            cardioFirst = CardioFirst(CardioActivity.TRAIL_RUN, Measure.Distance(4828), liftDays = 2),
        )
        val encoded = PlanRecipeJson.encode(answers)
        PlanRecipeJson.validate(encoded)
        assertEquals(answers, PlanRecipeJson.decode(encoded, GlobalLifter()))
    }

    @Test
    fun `a recipe without cardio first decodes as lifting first`() {
        val encoded = PlanRecipeJson.encode(WizardAnswers())
        assertFalse(encoded.contains("cardioFirst"))
        assertNull(PlanRecipeJson.decode(encoded, GlobalLifter()).cardioFirst)
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

    @Test
    fun `decode defaults invalid cardio answers and aligns lifting day count`() {
        val invalid = """{"activity":"UNKNOWN","seconds":1800,"metres":5000,"sessions":7,"liftDays":1}"""
        assertEquals(CardioFirst(), PlanRecipeJson.decode(recipe(invalid), GlobalLifter()).cardioFirst)
        val withLifting = """{"activity":"ROAD_RUN","seconds":1800,"sessions":3,"liftDays":2}"""
        val answers = PlanRecipeJson.decode(recipe(withLifting), GlobalLifter())
        assertEquals(2, answers.daysPerWeek)
        assertEquals(2, answers.cardioFirst?.liftDays)
    }
}
