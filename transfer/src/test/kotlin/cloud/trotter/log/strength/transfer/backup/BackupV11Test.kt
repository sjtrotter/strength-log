package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
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

class BackupV11Test {
    private val codec = BackupCodec()
    private val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(
        CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
        CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
        CardioSetSpec(CardioRole.COOLDOWN, Measure.Time(300), Measure.Distance(500)),
    ))

    private fun snapshot() = FullSnapshot(
        answers = WizardAnswers(),
        answersCarryRecipe = false,
        unit = WeightUnit.LB,
        wizardComplete = true,
        suggestedDay = null,
        selectedPlanId = 1,
        restSettings = RestSettings(),
        plans = listOf(PlanEntity(1, "Running", PlanOrigin.AUTHORED.name, 0, 1_000, suggestedDayId = "A")),
        days = listOf(ProgramDayEntity(1, "A", 0, "Track", "",
            CardioDto.encode(CardioSuggestion("Easy", "Zone 2", false)), kind = "CARDIO",
            cardioBlockJson = CardioBlockJson.encode(block))),
        exercises = emptyList(),
        customExercises = emptyList(),
        logs = emptyList(),
        sessions = emptyList(),
        sessionSets = emptyList(),
        cardioSessions = listOf(CardioSessionEntity(
            id = 1, dayId = "A", mode = "LOW_IMPACT", hard = false, label = "Easy",
            startedAt = 1_000, completedAt = 1_501_000, seconds = 1_500, stepsCompleted = 1,
            planId = 1, planName = "Running",
        )),
    )

    private fun withBlock(json: String?): BackupDocument {
        val document = snapshot().toDocument()
        val plan = document.plans.single()
        return document.copy(plans = listOf(plan.copy(days = listOf(plan.days.single().copy(cardioBlockJson = json)))))
    }

    @Test
    fun `v11 round trip preserves authored block legacy fields and deterministic export`() {
        val before = snapshot()
        val encoded = codec.encode(before.toDocument())
        val decoded = codec.decode(encoded)
        val restored = decoded.toSnapshot()
        assertEquals(11, decoded.schemaVersion)
        assertEquals(before, restored)
        assertEquals(block, CardioBlockJson.decode(restored.days.single().cardioBlockJson))
        assertEquals(encoded, codec.encode(before.toDocument()))
        assertEquals(encoded, codec.encode(restored.toDocument()))
    }

    @Test
    fun `v11 absent or null block stays null`() {
        val document = withBlock(null)
        val text = codec.encode(document)
        listOf(text, text.replace(",\"cardioBlockJson\":null", "")).forEach { json ->
            val decoded = codec.decode(json)
            assertEquals(document, decoded)
            assertNull(decoded.toSnapshot().days.single().cardioBlockJson)
        }
    }

    @Test
    fun `v11 invalid embedded blocks are rejected before restore`() {
        listOf(
            "not json", "{}", "null", """{"activity":"FUTURE","sets":[]}""",
            """{"activity":"ROAD_RUN","sets":[]}""",
            """{"activity":"ROAD_RUN","sets":[{"role":"WORK","end":{"type":"time","seconds":0}}]}""",
            """{"activity":"ROAD_RUN","sets":[{"role":"WORK","end":{"type":"future"}}]}""",
            """{"activity":"ROAD_RUN","sets":[{"role":"WORK","end":{"type":"time","seconds":120},"aim":{"type":"time","seconds":100}}]}""",
        ).forEach { blockJson ->
            val error = assertFailsWith<BackupError.InvalidPayload> {
                codec.decode(codec.encode(withBlock(blockJson)))
            }
            assertTrue("cardioBlockJson of day 'A'" in error.message.orEmpty())
        }
    }

    @Test
    fun `v1 through v10 ignore stray blocks including wrong JSON types and restore original rows`() {
        val legacySettings = codec.decode(checkNotNull(javaClass.getResource("/backup/v7_backup.json")).readText()).settings
        for (version in 1..10) {
            val current = withBlock(null)
            val day = current.plans.single().days.single()
            val document = current.copy(
                schemaVersion = version,
                settings = if (version < 9) legacySettings else current.settings,
                plans = if (version >= 8) current.plans else emptyList(),
                program = if (version < 8) listOf(day) else emptyList(),
            )
            val literal = codec.encode(document).replace(",\"cardioBlockJson\":null", "")
            val expected = codec.decode(literal).toSnapshot()
            assertNull(expected.days.single().cardioBlockJson)
            val root = Json.parseToJsonElement(literal).jsonObject
            val dayObject = if (version < 8) root.getValue("program").jsonArray.single().jsonObject else {
                root.getValue("plans").jsonArray.single().jsonObject.getValue("days").jsonArray.single().jsonObject
            }
            listOf(JsonPrimitive("not json"), JsonPrimitive(CardioBlockJson.encode(block)), JsonObject(emptyMap())).forEach { stray ->
                val strayDay = JsonObject(dayObject + ("cardioBlockJson" to stray))
                val withUnknown = if (version < 8) {
                    JsonObject(root + ("program" to JsonArray(listOf(strayDay))))
                } else {
                    val plan = root.getValue("plans").jsonArray.single().jsonObject
                    val strayPlan = JsonObject(plan + ("days" to JsonArray(listOf(strayDay))))
                    JsonObject(root + ("plans" to JsonArray(listOf(strayPlan))))
                }
                assertEquals(expected, codec.decode(withUnknown.toString()).toSnapshot(), "schema v$version")
            }
            val direct = if (version < 8) {
                document.copy(program = listOf(day.copy(cardioBlockJson = "invalid")))
            } else {
                val plan = document.plans.single().copy(days = listOf(day.copy(cardioBlockJson = "invalid")))
                document.copy(plans = listOf(plan))
            }
            assertNull(direct.toSnapshot().days.single().cardioBlockJson)
        }
    }
}
