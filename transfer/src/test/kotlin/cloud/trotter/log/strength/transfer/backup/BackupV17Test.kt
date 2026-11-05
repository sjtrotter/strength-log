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

    private fun legacyDocument(version: Int): BackupDocument {
        val current = document().withoutV17Fields()
        val settings = if (version < 9) {
            codec.decode(checkNotNull(javaClass.getResource("/backup/v7_backup.json")).readText()).settings
        } else current.settings
        return current.copy(
            schemaVersion = version,
            settings = settings.copy(bodyweightLb = 185, suggestedDay = null),
            sessions = current.sessions.map { session ->
                session.copy(
                    bodyweightLb = session.bodyweightLb ?: if (version < 5) 0 else null,
                )
            },
        )
    }

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

    @Test fun `v1 through v16 restore whole pounds and unknown weights through v17 export`() {
        for (version in 1..16) {
            val text = codec.encode(legacyDocument(version))
            val absentPrecision = text.replace(",\"bodyweightLbPrecise\":null", "")
            val variants = buildList {
                add(text)
                add(absentPrecision)
                if (version >= 5) add(absentPrecision.replace(",\"bodyweightLb\":null", ""))
            }
            for (json in variants) {
                val decoded = codec.decode(json)
                assertEquals(version, decoded.schemaVersion)
                assertNull(decoded.settings.bodyweightLbPrecise)
                decoded.sessions.forEach { assertNull(it.bodyweightLbPrecise) }
                val snapshot = decoded.toSnapshot()
                assertEquals(185.0, snapshot.answers.config.bodyweightLb, "schema v$version")
                assertEquals(listOf(211.0, null), snapshot.sessions.map { it.bodyweightLb }, "schema v$version")

                val exported = snapshot.toDocument()
                assertEquals(17, exported.schemaVersion)
                assertEquals(185, exported.settings.bodyweightLb)
                assertEquals(185.0, exported.settings.bodyweightLbPrecise)
                assertEquals(listOf(211, null), exported.sessions.map { it.bodyweightLb })
                assertEquals(listOf(211.0, null), exported.sessions.map { it.bodyweightLbPrecise })
                val restored = codec.decode(codec.encode(exported)).toSnapshot()
                assertEquals(snapshot.answers.config.bodyweightLb, restored.answers.config.bodyweightLb)
                assertEquals(snapshot.sessions, restored.sessions, "schema v$version")
            }
        }
    }

    @Test fun `v1 through v16 ignore future precision fields in settings and sessions`() {
        for (version in 1..16) {
            val text = codec.encode(legacyDocument(version))
            val expected = codec.decode(text).toSnapshot()
            for (value in listOf("210.5", "{}")) {
                val stray = text.replace("\"bodyweightLbPrecise\":null", "\"bodyweightLbPrecise\":$value")
                assertEquals(expected, codec.decode(stray).toSnapshot(), "schema v$version with $value")
            }
        }
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

internal fun BackupDocument.withoutV17Fields(): BackupDocument = copy(
    settings = settings.copy(bodyweightLbPrecise = null),
    sessions = sessions.map { it.copy(bodyweightLbPrecise = null) },
)
