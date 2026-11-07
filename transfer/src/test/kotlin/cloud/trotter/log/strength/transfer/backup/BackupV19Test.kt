package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.units.DistanceUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupV19Test {
    private val codec = BackupCodec()

    private fun document(distanceUnit: String? = "KM") = BackupDocument(
        schemaVersion = 19,
        settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1, distanceUnit = distanceUnit),
        plans = listOf(PlanBackup(1, "Running", "AUTHORED", 1000)),
    )

    @Test fun `v19 export and restore preserve the chosen distance unit`() {
        val decoded = codec.decode(codec.encode(document()))
        assertEquals(19, decoded.schemaVersion)
        val snapshot = decoded.toSnapshot()
        assertEquals(DistanceUnit.KM, snapshot.distanceUnit)
        val exported = snapshot.toDocument()
        assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
        assertEquals("KM", exported.settings.distanceUnit)
        val encoded = codec.encode(exported)
        assertTrue(encoded.contains("\"distanceUnit\":\"KM\""))
        assertEquals(snapshot, codec.decode(encoded).toSnapshot())
    }

    @Test fun `v18 ignores a future distance choice`() {
        val legacy = document().copy(schemaVersion = 18)
        assertNull(codec.decode(codec.encode(legacy)).toSnapshot().distanceUnit)
    }

    @Test fun `older versions ignore even malformed future distance fields`() {
        for (version in 1..18) {
            val source = document()
            val legacy = source.copy(schemaVersion = version, settings = source.settings.copy(
                emphasis = "BALANCED", cardioMode = "NONE", cardioPlacement = "NONE", fiveKGoal = false,
                daysPerWeek = 3, split = "FULL_BODY", anchorScheme = "SBD", deadliftVariant = "CONVENTIONAL",
                equipment = emptyList(),
            ))
            val encoded = codec.encode(legacy)
            val poisoned = encoded.replace("\"distanceUnit\":\"KM\"", "\"distanceUnit\":{}")
            assertNull(codec.decode(poisoned).toSnapshot().distanceUnit, "schema v$version")
        }
    }

    @Test fun `v19 rejects display only and unknown distance units`() {
        for (name in listOf("M", "unknown")) {
            val error = assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(name))) }
            assertEquals("Inconsistent backup: unknown distance unit '$name'", error.message)
        }
    }

    @Test fun `v19 without a distance choice restores absence`() {
        val encoded = codec.encode(document(null)).replace(",\"distanceUnit\":null", "")
        val snapshot = codec.decode(encoded).toSnapshot()
        assertNull(snapshot.distanceUnit)
        assertNull(codec.decode(codec.encode(snapshot.toDocument())).toSnapshot().distanceUnit)
    }
}
