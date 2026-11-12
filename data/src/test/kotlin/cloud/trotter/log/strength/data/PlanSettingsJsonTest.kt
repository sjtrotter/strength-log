package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanSettingsJsonTest {
    @Test fun `defaults and unknown keys use the default settings`() {
        assertEquals(PlanSettings(), PlanSettingsJson.decode("{}"))
        assertEquals(PlanSettings(), PlanSettingsJson.decode("""{"equipment":["BARBELL"]}"""))
        assertNull(PlanSettingsJson.decode(null))
        assertNull(PlanSettingsJson.decode("junk"))
    }

    @Test fun `released settings without runCap default on and false round trips`() {
        assertEquals(true, PlanSettingsJson.decode("""{"defaultsFor":"STRENGTH"}""")!!.runCap)
        val settings = PlanSettings(runCap = false)
        assertEquals(settings, PlanSettingsJson.decode(PlanSettingsJson.encode(settings)))
    }

    @Test fun `Muscle with steps round trips`() {
        val settings = PlanSettings(LiftGoal.MUSCLE, LoadSteps(2.5, 5.0, 10.0))
        assertEquals(settings, PlanSettingsJson.decode(PlanSettingsJson.encode(settings)))
    }

    @Test fun `names and step bounds reject invalid values and accept edges`() {
        val invalid = listOf("""{"defaultsFor":"POWER"}""", """{"defaultsFor":""}""") +
            listOf("0", "-0.1", "100.1", "1e999").flatMap { value ->
                listOf("barLb", "dumbbellLb", "stackLb").map { field ->
                    """{"loadSteps":{"barLb":1.0,"dumbbellLb":1.0,"stackLb":1.0}}"""
                        .replace("\"$field\":1.0", "\"$field\":$value")
                }
            }
        for (json in invalid) {
            assertNull(PlanSettingsJson.decode(json))
            assertFailsWith<IllegalArgumentException> { PlanSettingsJson.validate(json) }
        }
        for (step in listOf(Double.MIN_VALUE, 100.0)) {
            val settings = PlanSettings(loadSteps = LoadSteps(step, step, step))
            assertEquals(settings, PlanSettingsJson.decode(PlanSettingsJson.encode(settings)))
        }
        assertFailsWith<IllegalArgumentException> {
            PlanSettingsJson.encode(PlanSettings(loadSteps = LoadSteps(Double.NaN, 1.0, 1.0)))
        }
    }
}
