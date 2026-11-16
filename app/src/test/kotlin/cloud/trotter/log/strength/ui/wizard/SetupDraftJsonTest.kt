package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupDraftJsonTest {
    @Test fun `a draft round trips`() {
        val draft = SetupDraft(step = SetupStep.RECENT, goal = LiftGoal.MUSCLE, days = 4, minutes = 75,
            equipment = EquipmentPreset.DUMBBELLS.equipment, chooseExactly = true, unit = WeightUnit.KG,
            barStep = 2, dumbbellStep = 1, stackStep = 1, experience = LiftExperience.A_YEAR_OR_MORE,
            olderAdult = true, mains = listOf("db_bench"), recent = mapOf("db_bench" to RecentDraft(50.0, 8, Effort.HARD)), balanceDays = true)
        assertEquals(draft, SetupDraftJson.decode(SetupDraftJson.encode(draft)))
    }
    @Test fun `unknown keys are ignored and missing keys default`() {
        assertEquals(SetupDraft(days = 4), SetupDraftJson.decode("""{"v":1,"days":4,"future":true}"""))
    }
    @Test fun `even the default draft writes its version`() {
        assertTrue(SetupDraftJson.encode(SetupDraft()).contains("\"v\":1"))
    }
    @Test fun `unreadable drafts decode to null`() {
        listOf(null, "", "not json", """{"v":2}""", """{"v":1,"days":7}""", """{"v":1,"minutes":50}""",
            """{"v":1,"goal":"POWER"}""", """{"v":1,"barStep":3}""").forEach { assertNull(SetupDraftJson.decode(it)) }
    }
}
