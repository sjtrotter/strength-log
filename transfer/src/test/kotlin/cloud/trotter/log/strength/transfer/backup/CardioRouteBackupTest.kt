package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.db.entity.CardioRouteEntity
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
class CardioRouteBackupTest {
    private val codec = BackupCodec()
    private val route = """{"v":1,"p":[[0,0,0],[4,11,0]]}"""
    private fun document(route: String? = null) = BackupDocument(
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
        cardioSessions = listOf(CardioSessionBackup(12, "A", "OUTDOOR_RUN", false, "Run", 1000, 1691000, 1690, 1,
            activity = "ROAD_RUN", sets = listOf(CardioSetResultBackup(13,
                CardioBlockJson.encodeSet(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))), "FINISHED", 1690,
                metres = 5600, distanceSource = "MANUAL", metresAtEnd = 5050, secondsAtEnd = 1500,
                readingSource = "TYPED")), route = route)),
    )

    @Test fun `a saved route round trips through the document and snapshot`() {
        val source = document(route)
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source, decoded)
        assertEquals(route, decoded.cardioSessions.single().route)
        val snapshot = decoded.toSnapshot()
        assertEquals(listOf(CardioRouteEntity(12, route)), snapshot.cardioRoutes)
        assertEquals(source.cardioSessions, snapshot.toDocument().cardioSessions)
    }

    @Test fun `an absent route never adds a key to the backup`() {
        assertFalse("\"route\"" in codec.encode(document()))
    }

    @Test fun `a v0_10_0 v27 document without a route restores no routes`() {
        val root = Json.parseToJsonElement(codec.encode(document())).jsonObject
        val sessions = root.getValue("cardioSessions").jsonArray
        val legacy = JsonObject(root + ("cardioSessions" to JsonArray(sessions.map { session ->
            val fields = session.jsonObject
            JsonObject((fields - "increaseApplied") + ("sets" to JsonArray(fields.getValue("sets").jsonArray.map {
                JsonObject(it.jsonObject - "readingSource")
            })))
        }))).toString()
        assertFalse("\"route\"" in legacy)
        val decoded = codec.decode(legacy)
        assertEquals(27, decoded.schemaVersion)
        assertNull(decoded.cardioSessions.single().route)
        assertTrue(decoded.toSnapshot().cardioRoutes.isEmpty())
    }

    @Test fun `an invalid saved route is inconsistent`() {
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document("x"))) }
    }
}
