package cloud.trotter.log.strength.transfer.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupV17Test {
    private val codec = BackupCodec()
    private fun document() = BackupDocument(
        settings = SettingsBackup(211, 33, "INTERMEDIATE", weightUnit = "KG", wizardComplete = true,
            bodyweightLbPrecise = 210.5),
        sessions = listOf(
            SessionBackup(1, "A", "Day A", completedAt = 1000, bodyweightLb = 211, bodyweightLbPrecise = 210.5),
            SessionBackup(2, "csv:A", "Imported", completedAt = 2000),
        ),
    )

    @Test fun `v17 round trip preserves precise settings sessions and unknown bodyweight`() {
        val snapshot = codec.decode(codec.encode(document())).toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(17, exported.schemaVersion)
        assertEquals(211, exported.settings.bodyweightLb)
        assertEquals(210.5, exported.settings.bodyweightLbPrecise)
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        assertEquals(210.5, restored.answers.config.bodyweightLb)
        assertEquals(210.5, restored.sessions.first().bodyweightLb)
        assertNull(restored.sessions.last().bodyweightLb)
        assertEquals(snapshot, restored)
    }

    @Test fun `v16 without precise fields restores whole pounds`() {
        val legacy = document().let { it.copy(
            schemaVersion = 16,
            settings = it.settings.copy(bodyweightLb = 185, bodyweightLbPrecise = null),
            sessions = it.sessions.map { session -> session.copy(bodyweightLbPrecise = null) },
        ) }
        val encoded = codec.encode(legacy).replace(",\"bodyweightLbPrecise\":null", "")
        val snapshot = codec.decode(encoded).toSnapshot()
        assertEquals(185.0, snapshot.answers.config.bodyweightLb)
        assertEquals(211.0, snapshot.sessions.first().bodyweightLb)
        assertNull(snapshot.sessions.last().bodyweightLb)
    }

    @Test fun `older versions ignore malformed future precision fields`() {
        val legacy = codec.encode(document().copy(schemaVersion = 16))
            .replace("\"bodyweightLbPrecise\":210.5", "\"bodyweightLbPrecise\":{}")
        assertEquals(211.0, codec.decode(legacy).toSnapshot().answers.config.bodyweightLb)
    }

    @Test fun `invalid precise bodyweight is rejected before restore`() {
        for (value in listOf(0.0, -1.0, 210.55)) {
            val doc = document()
            assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(doc.copy(settings = doc.settings.copy(bodyweightLbPrecise = value))))
            }
            assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(doc.copy(sessions = listOf(doc.sessions.first().copy(bodyweightLbPrecise = value)))))
            }
        }
    }
}
