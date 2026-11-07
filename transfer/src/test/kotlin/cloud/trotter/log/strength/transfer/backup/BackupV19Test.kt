package cloud.trotter.log.strength.transfer.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class BackupV19Test {
    private val codec = BackupCodec()
    private fun document(note: String = "Easy") = BackupDocument(
        settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
        cardioSessions = listOf(CardioSessionBackup(
            id = 7, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Road run",
            startedAt = 1000, completedAt = 2413000, seconds = 2412, stepsCompleted = 1,
            activity = "ROAD_RUN", note = note,
        )),
    )

    @Test fun `v19 export and restore preserve the cardio note`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(19, exported.schemaVersion)
        assertEquals("Easy", exported.cardioSessions.single().note)
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        assertEquals("Easy", restored.cardioSessions.single().note)
        assertEquals(snapshot, restored)
    }

    @Test fun `v18 without a cardio note restores an empty note`() {
        val encoded = codec.encode(document("").copy(schemaVersion = 18)).replace(",\"note\":\"\"", "")
        val snapshot = codec.decode(encoded).toSnapshot()
        assertEquals("", snapshot.cardioSessions.single().note)
        assertEquals(19, snapshot.toDocument().schemaVersion)
    }

    @Test fun `older versions ignore future cardio notes even with the wrong type`() {
        for (version in 9..18) {
            val encoded = codec.encode(document().copy(schemaVersion = version))
            val poisoned = encoded.replace("\"note\":\"Easy\"", "\"note\":{}")
            val expected = codec.decode(encoded).toSnapshot()
            assertEquals(expected, codec.decode(poisoned).toSnapshot(), "schema v$version")
            assertEquals("", expected.cardioSessions.single().note)
        }
    }
}
