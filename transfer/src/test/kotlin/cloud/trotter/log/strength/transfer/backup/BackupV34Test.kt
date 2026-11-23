package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.progression.LoadSource
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupV34Test {
    private val codec = BackupCodec()
    private val plain = SlotRuleJson.encode(SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, scheme = Scheme.STRENGTH))
    private val linked = SlotRuleJson.encode(requireNotNull(SlotRuleJson.decode(plain)).copy(loadSource = LoadSource("A", 0.9)))
    private fun document(rule: String = linked, version: Int = CURRENT_SCHEMA_VERSION) = BackupDocument(schemaVersion = version,
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Mine", "AUTHORED", 1000, onToday = true, suggestedDay = "A",
            days = listOf(ProgramDayBackup("A", "Day A", "", exercises = listOf(
                ProgramExerciseBackup(1, "bb_bench", structure = "STRAIGHT", targetSets = 3, note = "", ruleJson = rule)))))))

    @Test fun `v34 round trips a load source`() {
        val source = document()
        val decoded = codec.decode(codec.encode(source))
        assertEquals(CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(source, decoded)
        assertEquals(linked, decoded.toSnapshot().toDocument().plans.single().days.single().exercises.single().ruleJson)
    }

    @Test fun `older documents with a load source are inconsistent`() {
        for (version in listOf(27, 31, 32, 33)) {
            val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(version = version))) }
            assertTrue(error.message.orEmpty().contains("requires schema v34"))
        }
    }

    @Test fun `v33 and earlier rules without a load source keep own results`() {
        for (version in listOf(27, 31, 32, 33)) {
            val old = document(plain, version)
            val decoded = codec.decode(codec.encode(old))
            assertEquals(old, decoded)
            val restored = decoded.toSnapshot().toDocument()
            assertEquals(CURRENT_SCHEMA_VERSION, restored.schemaVersion)
            val rule = assertNotNull(SlotRuleJson.decode(restored.plans.single().days.single().exercises.single().ruleJson))
            assertNull(rule.loadSource)
        }
    }

    @Test fun `an older rule with a pending increase decodes and loses it`() {
        val pending = plain.dropLast(1) + ",\"state\":{\"pendingIncrease\":true}}"
        val decoded = codec.decode(codec.encode(document(pending, 32)))
        val rule = assertNotNull(SlotRuleJson.decode(decoded.plans.single().days.single().exercises.single().ruleJson))
        assertTrue("pendingIncrease" !in SlotRuleJson.encode(rule))
    }
}
