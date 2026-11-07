package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupV20Test {
    private val codec = BackupCodec()
    private fun block(seconds: Int) = CardioBlockJson.encode(CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds)))))
    private val day = ProgramDayBackup("A", "Run", "", cardioBlockJson = block(1200),
        cardioWorkingJson = block(1260), cardioKeepToPlan = true, cardioProgressBy = "TIME", cardioIncreasePercent = 7)
    private fun document(day: ProgramDayBackup = this.day) = BackupDocument(
        settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Running", "AUTHORED", 1000, days = listOf(day), onToday = true,
            offerStrengthIncreases = false, offerCardioIncreases = true)),
    )

    @Test fun `v20 export and restore preserve cardio Next time and offer switches verbatim`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
        assertEquals(day, exported.plans.single().days.single())
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        assertEquals(snapshot, restored)
        assertEquals(block(1260), restored.days.single().cardioWorkingJson)
        assertEquals("TIME", restored.days.single().cardioProgressBy)
        assertEquals(7, restored.days.single().cardioIncreasePercent)
        assertTrue(restored.days.single().cardioKeepToPlan)
        assertFalse(restored.plans.single().offerStrengthIncreases)
        assertTrue(restored.plans.single().offerCardioIncreases)
    }

    @Test fun `v19 ignores future cardio Next time and restores offer defaults`() {
        val legacy = document().copy(schemaVersion = 19)
        val snapshot = codec.decode(codec.encode(legacy)).toSnapshot()
        assertNull(snapshot.days.single().cardioWorkingJson)
        assertNull(snapshot.days.single().cardioProgressBy)
        assertNull(snapshot.days.single().cardioIncreasePercent)
        assertFalse(snapshot.days.single().cardioKeepToPlan)
        assertTrue(snapshot.plans.single().offerStrengthIncreases)
        assertFalse(snapshot.plans.single().offerCardioIncreases)
        assertEquals(snapshot, legacy.toSnapshot())
    }

    @Test fun `older schemas ignore malformed future fields`() {
        for (version in 9..19) {
            val legacy = document().copy(schemaVersion = version)
            val encoded = codec.encode(legacy)
            val poisoned = encoded.replace("\"offerStrengthIncreases\":false", "\"offerStrengthIncreases\":{}")
                .replace("\"offerCardioIncreases\":true", "\"offerCardioIncreases\":{}")
                .replace("\"cardioKeepToPlan\":true", "\"cardioKeepToPlan\":{}")
                .replace("\"cardioProgressBy\":\"TIME\"", "\"cardioProgressBy\":{}")
                .replace("\"cardioIncreasePercent\":7", "\"cardioIncreasePercent\":{}")
                .replace(kotlinx.serialization.json.JsonPrimitive(block(1260)).toString(), "{}")
            assertEquals(codec.decode(encoded).toSnapshot(), codec.decode(poisoned).toSnapshot(), "schema v$version")
        }
    }

    @Test fun `invalid progression fields fail as inconsistent`() {
        val badDays = listOf(day.copy(cardioProgressBy = "UNKNOWN"), day.copy(cardioIncreasePercent = 0),
            day.copy(cardioIncreasePercent = 26), day.copy(cardioWorkingJson = "bad block"), day.copy(cardioBlockJson = null))
        badDays.forEach { invalid ->
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(invalid))) }
        }
    }
}
