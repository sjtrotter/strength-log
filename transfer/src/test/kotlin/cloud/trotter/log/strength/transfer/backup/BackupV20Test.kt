package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.units.DistanceUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupV20Test {
    private val codec = BackupCodec()
    private fun document(note: String = "Easy") = BackupDocument(
        settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            distanceUnit = "KM"),
        cardioSessions = listOf(CardioSessionBackup(
            id = 7, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Road run",
            startedAt = 1000, completedAt = 2413000, seconds = 2412, stepsCompleted = 1,
            activity = "ROAD_RUN", note = note,
        )),
    )

    @Test fun `v20 export and restore preserve the cardio note and distance choice`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(20, exported.schemaVersion)
        assertEquals("Easy", exported.cardioSessions.single().note)
        assertEquals("KM", exported.settings.distanceUnit)
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        assertEquals("Easy", restored.cardioSessions.single().note)
        assertEquals(DistanceUnit.KM, restored.distanceUnit)
        assertEquals(snapshot, restored)
    }

    @Test fun `v18 and v19 without cardio notes restore through v20 export`() {
        for (version in 18..19) {
            val encoded = codec.encode(document("").copy(schemaVersion = version)).replace(",\"note\":\"\"", "")
            val decoded = codec.decode(encoded)
            assertEquals(version, decoded.schemaVersion)
            val snapshot = decoded.toSnapshot()
            assertEquals("", snapshot.cardioSessions.single().note)
            assertEquals(if (version == 19) DistanceUnit.KM else null, snapshot.distanceUnit)
            val exported = snapshot.toDocument()
            assertEquals(20, exported.schemaVersion)
            assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
        }
    }

    @Test fun `older versions ignore future cardio notes even with the wrong type`() {
        for (version in 9..19) {
            val encoded = codec.encode(document().copy(schemaVersion = version))
            val poisoned = encoded.replace("\"note\":\"Easy\"", "\"note\":{}")
            val expected = codec.decode(encoded).toSnapshot()
            assertEquals(expected, codec.decode(poisoned).toSnapshot(), "schema v$version")
            assertEquals(expected, document().copy(schemaVersion = version).toSnapshot(), "schema v$version")
            assertEquals("", expected.cardioSessions.single().note)
        }
    }

    @Test fun `v20 rejects malformed cardio notes`() {
        val encoded = codec.encode(document()).replace("\"note\":\"Easy\"", "\"note\":{}")
        assertFailsWith<BackupError.Malformed> { codec.decode(encoded) }
    }
}
