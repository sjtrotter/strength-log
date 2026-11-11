package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingSourceBackupTest {
    private val codec = BackupCodec()
    private fun document() = BackupDocument(
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
        cardioSessions = listOf(CardioSessionBackup(12, "A", "OUTDOOR_RUN", false, "Run", 1000, 1691000, 1690, 1,
            activity = "ROAD_RUN", sets = listOf(CardioSetResultBackup(13,
                CardioBlockJson.encodeSet(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))), "FINISHED", 1690,
                metres = 5600, distanceSource = "MANUAL", metresAtEnd = 5050, secondsAtEnd = 1500,
                readingSource = "TYPED")))),
    )

    @Test fun `v27 typed reading source round trips through the snapshot and document`() {
        val source = document()
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source, decoded)
        val snapshot = decoded.toSnapshot()
        val row = snapshot.cardioSetResults.single()
        assertEquals("TYPED", row.readingSource)
        assertEquals(ReadingSource.TYPED, row.toDomain().readingSource)
        assertEquals(5050 to 1500, row.metresAtEnd to row.secondsAtEnd)

        val exported = snapshot.toDocument()
        assertEquals(27, exported.schemaVersion)
        assertEquals(source.cardioSessions, exported.cardioSessions)
        assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
    }

    @Test fun `a v0_10_0 v27 document without readingSource decodes as null`() {
        val root = Json.parseToJsonElement(codec.encode(document())).jsonObject
        val sessions = root.getValue("cardioSessions").jsonArray
        val legacy = JsonObject(root + ("cardioSessions" to JsonArray(sessions.map { session ->
            val fields = session.jsonObject
            JsonObject((fields - "increaseApplied") + ("sets" to JsonArray(fields.getValue("sets").jsonArray.map {
                JsonObject(it.jsonObject - "readingSource")
            })))
        }))).toString()
        assertFalse("readingSource" in legacy)

        val decoded = codec.decode(legacy)
        assertEquals(27, decoded.schemaVersion)
        assertNull(decoded.cardioSessions.single().sets.single().readingSource)
        val row = decoded.toSnapshot().cardioSetResults.single()
        assertNull(row.readingSource)
        assertNull(row.toDomain().readingSource)
        assertEquals(5050 to 1500, row.metresAtEnd to row.secondsAtEnd)
        assertNull(decoded.toSnapshot().toDocument().cardioSessions.single().sets.single().readingSource)
    }

    @Test fun `unknown reading sources are inconsistent`() {
        val source = document()
        for (name in listOf("GPS", "typed")) {
            val invalid = source.copy(cardioSessions = source.cardioSessions.map { session ->
                session.copy(sets = session.sets.map { it.copy(readingSource = name) })
            })
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(invalid)) }
        }
    }

    @Test fun `a pre-v27 document discards the reading source with the readings`() {
        val snapshot = codec.decode(codec.encode(document().copy(schemaVersion = 26))).toSnapshot()
        val row = snapshot.cardioSetResults.single()
        assertNull(row.metresAtEnd)
        assertNull(row.secondsAtEnd)
        assertNull(row.readingSource)
    }
}
