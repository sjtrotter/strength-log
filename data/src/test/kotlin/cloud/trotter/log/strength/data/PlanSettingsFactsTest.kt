package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PlanSettingsFactsTest {
    @Test fun `equipment and goals round trip`() {
        val settings = PlanSettings(LiftGoal.MUSCLE, LoadSteps(2.5, 5.0, 10.0),
            equipment = EquipmentPreset.DUMBBELLS.equipment, goalsLb = mapOf("bb_bench" to 225.0))
        assertEquals(settings, PlanSettingsJson.decode(PlanSettingsJson.encode(settings)))
        val old = assertNotNull(PlanSettingsJson.decode("""{"defaultsFor":"STRENGTH"}"""))
        assertNull(old.equipment)
        assertTrue(old.goalsLb.isEmpty())
    }

    @Test fun `reads drop what validation rejects`() {
        val json = """{"equipment":["DUMBBELL","HOVERBOARD"],"goalsLb":{"bb_bench":0}}"""
        val settings = assertNotNull(PlanSettingsJson.decode(json))
        assertEquals(setOf(Equipment.DUMBBELL), settings.equipment)
        assertTrue(settings.goalsLb.isEmpty())
        assertFailsWith<IllegalArgumentException> { PlanSettingsJson.validate(json) }
        for (goals in listOf("\"\":200", "\"bb_bench\":-1", "\"bb_bench\":2000.1")) {
            val payload = "{\"goalsLb\":{$goals}}"
            assertTrue(assertNotNull(PlanSettingsJson.decode(payload)).goalsLb.isEmpty())
            assertFailsWith<IllegalArgumentException> { PlanSettingsJson.validate(payload) }
        }
    }

    @Test fun `hasPlanFacts`() {
        assertTrue(PlanSettingsJson.hasPlanFacts("""{"equipment":[]}"""))
        assertTrue(PlanSettingsJson.hasPlanFacts("""{"goalsLb":{"bb_bench":225}}"""))
        assertFalse(PlanSettingsJson.hasPlanFacts("""{"defaultsFor":"STRENGTH"}"""))
        assertFalse(PlanSettingsJson.hasPlanFacts("not json"))
    }
}
