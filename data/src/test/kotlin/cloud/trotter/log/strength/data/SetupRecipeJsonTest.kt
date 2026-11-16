package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.RecentNumber
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SetupRecipeJsonTest {
    private val answers = SetupAnswers(goal = LiftGoal.MUSCLE, days = 4, minutes = 75,
        experience = LiftExperience.UNDER_A_YEAR, olderAdult = true, unit = WeightUnit.KG,
        steps = LoadSteps.defaults(WeightUnit.KG), equipment = EquipmentPreset.DUMBBELLS.equipment,
        mains = Planner.defaultMains(EquipmentPreset.DUMBBELLS.equipment),
        recent = mapOf(Planner.defaultMains(EquipmentPreset.DUMBBELLS.equipment)[2] to RecentNumber(80.0, 8, Effort.GOOD)))
    private val settings = PlanSettings(equipment = answers.equipment, loadSteps = answers.steps)

    @Test fun `answers round trip without gear`() {
        val json = SetupRecipeJson.encode(answers)
        assertEquals(answers, SetupRecipeJson.decode(json, settings, WeightUnit.KG))
        val keys = Json.parseToJsonElement(json).jsonObject.keys
        assertTrue(json.contains("\"v\":2"))
        assertTrue(keys.intersect(setOf("equipment", "steps", "unit")).isEmpty())
    }

    @Test fun `isV2`() {
        assertTrue(SetupRecipeJson.isV2(SetupRecipeJson.encode(answers)))
        for (json in listOf(PlanRecipeJson.encode(WizardAnswers()), "{}", """{"v":"2"}""", "not json", "[]", """{"v":2.0}""")) {
            assertFalse(SetupRecipeJson.isV2(json), json)
        }
    }

    @Test fun `encode refuses other branches`() {
        for (branch in PlanBranch.entries.filter { it != PlanBranch.LIFT }) {
            assertFailsWith<IllegalArgumentException> { SetupRecipeJson.encode(SetupAnswers(branch = branch)) }
        }
    }

    @Test fun `decode is tolerant and validate is strict`() {
        val json = SetupRecipeJson.encode(answers)
        val invalid = listOf(
            json.replace("\"v\":2", "\"v\":3"), json.replace("MUSCLE", "POWER"),
            json.replace("\"days\":4", "\"days\":7"), json.replace("\"minutes\":75", "\"minutes\":50"),
            json.replace("\"reps\":8", "\"reps\":11"), json.replace("80.0", "0"),
            json.replace("GOOD", "GRINDER"), json.replace("LIFT", "RUN"), json.replace("UNDER_A_YEAR", "EXPERT"),
        )
        for (payload in invalid) {
            assertNull(SetupRecipeJson.decode(payload, settings, WeightUnit.KG), payload)
            assertFailsWith<IllegalArgumentException>(payload) { SetupRecipeJson.validate(payload) }
        }
    }

    @Test fun `a v1 recipe still validates through PlanRecipeJson`() {
        PlanRecipeJson.validate(PlanRecipeJson.encode(WizardAnswers()))
        PlanRecipeJson.validate(SetupRecipeJson.encode(answers))
        assertFailsWith<IllegalArgumentException> {
            PlanRecipeJson.validate(SetupRecipeJson.encode(answers).replace("MUSCLE", "POWER"))
        }
    }

    @Test fun `accepted balance days round trip`() {
        // audit 2026-11-13 decision 12: acceptance survives reset and backup.
        val accepted = answers.copy(balanceDays = true)
        assertEquals(accepted, SetupRecipeJson.decode(SetupRecipeJson.encode(accepted), settings, WeightUnit.KG))
    }
}
