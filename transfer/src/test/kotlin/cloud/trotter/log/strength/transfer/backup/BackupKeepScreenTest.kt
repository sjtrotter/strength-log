package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.theme.KeepScreenOn
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class BackupKeepScreenTest {
    private val codec = BackupCodec()
    private fun document() = BackupDocument(
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
    )

    private fun legacy(on: Boolean?): String {
        val root = Json.parseToJsonElement(codec.encode(document())).jsonObject
        val settings = root.getValue("settings").jsonObject - "keepScreen" - "keepScreenOn"
        return JsonObject(root + ("settings" to JsonObject(
            if (on == null) settings else settings + ("keepScreenOn" to JsonPrimitive(on)),
        ))).toString()
    }

    @Test fun `released v27 without keepScreen restores by its boolean`() {
        assertEquals(KeepScreenOn.DURING_WORKOUTS, codec.decode(legacy(true)).toSnapshot().keepScreenOn)
        assertEquals(KeepScreenOn.WHILE_TIMER, codec.decode(legacy(false)).toSnapshot().keepScreenOn)
        assertEquals(KeepScreenOn.WHILE_TIMER, codec.decode(legacy(null)).toSnapshot().keepScreenOn)
    }

    @Test fun `a present valid name wins over the legacy boolean`() {
        for (mode in KeepScreenOn.entries) {
            val doc = document().copy(settings = document().settings.copy(
                keepScreenOn = mode != KeepScreenOn.DURING_WORKOUTS, keepScreen = mode.name,
            ))
            assertEquals(mode, codec.decode(codec.encode(doc)).toSnapshot().keepScreenOn)
        }
    }

    @Test fun `an unknown name falls back to the boolean`() {
        for (on in listOf(false, true)) {
            val doc = document().copy(settings = document().settings.copy(keepScreenOn = on, keepScreen = "SOMETIMES"))
            assertEquals(KeepScreenOn.fromLegacy(on), codec.decode(codec.encode(doc)).toSnapshot().keepScreenOn)
        }
    }

    @Test fun `re-export stays at the current v27 schema`() {
        val exported = codec.decode(legacy(true)).toSnapshot().toDocument()
        assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
        assertEquals(27, exported.schemaVersion)
        assertEquals("DURING_WORKOUTS", exported.settings.keepScreen)
    }
}
