package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.domain.model.Equipment
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
import kotlin.test.assertNotNull
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
        for (json in listOf(OldWizardPlans.DEFAULT.recipeJson, "{}", """{"v":"2"}""", "not json", "[]", """{"v":2.0}""")) {
            assertFalse(SetupRecipeJson.isV2(json), json)
        }
    }

    @Test fun `setup and plan readers agree on absent empty and saved equipment`() {
        val json = SetupRecipeJson.encode(answers)
        for (equipment in listOf(null, emptySet(), EquipmentPreset.DUMBBELLS.equipment)) {
            val planSettings = PlanSettings(equipment = equipment)
            val expected = equipment ?: Equipment.entries.toSet()
            assertEquals(expected, assertNotNull(SetupRecipeJson.decode(json, planSettings, WeightUnit.KG)).equipment)
            for (recipe in listOf(null, json)) {
                val plan = PlanEntity(1, "Plan", PlanOrigin.GENERATED.name, 0, 0,
                    recipeJson = recipe, settingsJson = PlanSettingsJson.encode(planSettings))
                assertEquals(expected, plan.planSettings.equipmentOrAll)
            }
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
        PlanRecipeJson.validate(OldWizardPlans.DEFAULT.recipeJson)
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
    @Test fun `field readers are tolerant`() {
        assertEquals(true, SetupRecipeJson.olderAdult(SetupRecipeJson.encode(SetupAnswers(olderAdult = true))))
        assertEquals(false, SetupRecipeJson.olderAdult(SetupRecipeJson.encode(SetupAnswers())))
        val v1 = OldWizardPlans.DEFAULT.recipeJson
        assertNull(SetupRecipeJson.olderAdult(v1))
        assertEquals(LiftExperience.NEW, SetupRecipeJson.experience(SetupRecipeJson.encode(SetupAnswers())))
        assertNull(SetupRecipeJson.experience("""{"v":2,"experience":"GURU"}"""))
        assertNull(SetupRecipeJson.experience(v1))
        assertEquals(true, SetupRecipeJson.olderAdult("""{"v":2,"olderAdult":true,"experience":{}}"""))
        assertEquals(LiftExperience.NEW, SetupRecipeJson.experience("""{"v":2,"olderAdult":{},"experience":"NEW"}"""))
        assertEquals(false, SetupRecipeJson.olderAdult("""{"v":2,"olderAdult":{}}"""))
        for (json in listOf("not json", "[]", """{"v":"2"}""")) {
            assertNull(SetupRecipeJson.olderAdult(json))
            assertNull(SetupRecipeJson.experience(json))
        }
    }
}
