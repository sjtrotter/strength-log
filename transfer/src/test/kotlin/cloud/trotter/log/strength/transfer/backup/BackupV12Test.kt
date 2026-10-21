package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BackupV12Test {
    private val codec = BackupCodec()
    private val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true)
    private val sets = listOf(
        CardioSetResultBackup(11, CardioBlockJson.encodeSet(spec), "SHORTENED", 100, 380, "MANUAL"),
        CardioSetResultBackup(12, CardioBlockJson.encodeSet(
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
        ), "FINISHED", 120),
        CardioSetResultBackup(13, CardioBlockJson.encodeSet(
            CardioSetSpec(CardioRole.COOLDOWN, Measure.Time(300)),
        ), "SKIPPED", 0),
    )
    private val session = CardioSessionBackup(
        id = 7, dayId = "A", mode = "OUTDOOR_RUN", hard = true, label = "Track",
        startedAt = 1_000, completedAt = 221_000, seconds = 220, stepsCompleted = 1,
        activity = "TRACK_RUN", sets = sets,
    )
    private fun document(session: CardioSessionBackup = this.session) = BackupDocument(
        settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
        cardioSessions = listOf(session),
    )
    private fun decode(document: BackupDocument) = codec.decode(codec.encode(document))

    @Test fun `v12 round trip preserves ordered specs actuals sources ids and activity`() {
        val before = document()
        val text = codec.encode(before)
        val decoded = codec.decode(text)
        assertEquals(12, decoded.schemaVersion)
        assertEquals(before, decoded)
        val snapshot = decoded.toSnapshot()
        assertEquals("TRACK_RUN", snapshot.cardioSessions.single().activity)
        assertEquals(listOf(11L, 12L, 13L), snapshot.cardioSetResults.map { it.id })
        assertEquals(listOf(0, 1, 2), snapshot.cardioSetResults.map { it.position })
        assertTrue(snapshot.cardioSetResults.all { it.cardioSessionId == 7L })
        val results = snapshot.cardioSetResults.map { it.toDomain() }
        assertEquals(spec, results.first().spec)
        assertEquals(CardioSetStatus.SHORTENED, results.first().status)
        assertEquals(380, results.first().metres)
        assertEquals(DistanceSource.MANUAL, results.first().distanceSource)
        assertEquals(CardioRole.RECOVERY, results[1].spec.role)
        assertEquals(CardioSetStatus.SKIPPED, results.last().status)
        assertNull(results.last().metres)
        assertNull(results.last().distanceSource)
        assertEquals(before, snapshot.toDocument())
        assertEquals(text, codec.encode(snapshot.toDocument()))
    }

    @Test fun `absent activity and sets stay legacy summaries`() {
        val summary = session.copy(activity = null, sets = emptyList())
        val document = document(summary)
        val text = codec.encode(document).replace(",\"activity\":null", "").replace(",\"sets\":[]", "")
        val restored = codec.decode(text)
        assertEquals(document, restored)
        assertNull(restored.toSnapshot().cardioSessions.single().activity)
        assertTrue(restored.toSnapshot().cardioSetResults.isEmpty())
    }

    @Test fun `v1 through v11 ignore new session fields even with wrong types`() {
        val legacySettings = codec.decode(checkNotNull(javaClass.getResource("/backup/v7_backup.json")).readText()).settings
        for (version in 1..11) {
            val summary = session.copy(activity = null, sets = emptyList())
            val baseline = document(summary).copy(
                schemaVersion = version,
                settings = if (version < 9) legacySettings.copy(suggestedDay = null) else document().settings,
            )
            val decoded = decode(baseline)
            val expected = decoded.toSnapshot()
            assertNull(expected.cardioSessions.single().activity)
            assertTrue(expected.cardioSetResults.isEmpty())
            val root = Json.parseToJsonElement(codec.encode(baseline)).jsonObject
            val row = root.getValue("cardioSessions").jsonArray.single().jsonObject
            listOf(JsonPrimitive("invalid"), JsonObject(emptyMap())).forEach { stray ->
                val strayRow = JsonObject(row + mapOf("activity" to stray, "sets" to stray))
                val withUnknown = JsonObject(root + ("cardioSessions" to JsonArray(listOf(strayRow))))
                assertEquals(decoded, codec.decode(withUnknown.toString()), "schema v$version")
            }
            assertEquals(expected, baseline.copy(cardioSessions = listOf(session)).toSnapshot())
        }
    }

    @Test fun `invalid spec status source and negative actuals are rejected before restore`() {
        val valid = sets.first()
        listOf(
            valid.copy(specJson = "not json"),
            valid.copy(specJson = "{}"),
            valid.copy(specJson = """{"role":"WORK","end":{"type":"time","seconds":0}}"""),
            valid.copy(status = "FUTURE"),
            valid.copy(distanceSource = null),
            valid.copy(metres = null),
            valid.copy(distanceSource = "FUTURE"),
            valid.copy(metres = -1),
            valid.copy(elapsedSeconds = -1),
        ).forEach { invalid ->
            assertFailsWith<BackupError.InvalidPayload> { decode(document(session.copy(sets = listOf(invalid)))) }
        }
        assertFailsWith<BackupError.Inconsistent> { decode(document(session.copy(activity = "FUTURE"))) }
        assertFailsWith<BackupError.Inconsistent> { decode(document(session.copy(sets = listOf(valid, valid)))) }
        assertFailsWith<BackupError.Inconsistent> { decode(document(session.copy(sets = listOf(valid.copy(id = 0))))) }
        val secondSession = session.copy(id = 8)
        assertFailsWith<BackupError.Inconsistent> {
            decode(document().copy(cardioSessions = listOf(session, secondSession)))
        }
    }

    @Test fun `known zero and all distance sources survive backup validation`() {
        DistanceSource.entries.forEach { source ->
            val set = sets.first().copy(metres = 0, distanceSource = source.name)
            assertEquals(set, decode(document(session.copy(sets = listOf(set)))).cardioSessions.single().sets.single())
        }
    }
}
