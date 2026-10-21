package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class CardioBlockJsonTest {
    @Test
    fun `all activities roles measures and optional aims round trip deterministically`() {
        CardioActivity.entries.forEach { activity ->
            val block = CardioBlock(activity, listOf(
                CardioSetSpec(CardioRole.WARMUP, Measure.Time(1)),
                CardioSetSpec(CardioRole.WORK, Measure.Distance(200_000), Measure.Time(86_400), hard = true),
                CardioSetSpec(CardioRole.RECOVERY, Measure.Time(86_400), Measure.Distance(1)),
                CardioSetSpec(CardioRole.COOLDOWN, Measure.Distance(1)),
            ))
            val json = CardioBlockJson.encode(block)
            CardioBlockJson.validate(json)
            assertEquals(block, CardioBlockJson.decode(json))
            assertEquals(json, CardioBlockJson.encode(block))
            val day = ProgramDay("A", "Running", "", emptyList(), CardioSuggestion("Easy", "", false),
                kind = ProgramDayKind.CARDIO, cardioBlock = block)
            assertEquals(day, day.toEntity(1).toDomain(emptyList()))
        }
        val day = ProgramDay("A", "Strength", "", emptyList(), null)
        assertNull(day.toEntity(1).cardioBlockJson)
        assertEquals(day, day.toEntity(1).toDomain(emptyList()))
        assertNull(CardioBlockJson.decode(null))
        assertNull(day.toEntity(1).copy(cardioBlockJson = "bad").toDomain(emptyList()).cardioBlock)
    }

    @Test
    fun `standalone specs use exactly the block set encoding`() {
        val sets = listOf(
            CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120), Measure.Distance(100)),
            CardioSetSpec(CardioRole.COOLDOWN, Measure.Distance(500)),
        )
        val block = CardioBlockJson.encode(CardioBlock(CardioActivity.TRACK_RUN, sets))
        val embedded = Json.parseToJsonElement(block).jsonObject.getValue("sets").jsonArray
        sets.forEachIndexed { index, spec ->
            val encoded = CardioBlockJson.encodeSet(spec)
            assertEquals(embedded[index].toString(), encoded)
            assertEquals(spec, CardioBlockJson.decodeSet(encoded))
        }
    }

    @Test
    fun `malformed unknown incomplete and invalid payloads decode to null and fail validation`() {
        val time = """{"type":"time","seconds":120}"""
        val distance = """{"type":"distance","metres":400}"""
        val invalidSets = listOf(
            "{}", """{"role":"FUTURE","end":$time}""",
            """{"role":"WORK","end":{"type":"future","seconds":120}}""",
            """{"role":"WORK","end":{"type":"time"}}""",
            """{"role":"WORK","end":{"type":"distance"}}""",
            """{"role":"WORK","end":{"type":"time","seconds":120,"metres":400}}""",
            """{"role":"WORK","end":{"type":"distance","metres":400,"seconds":120}}""",
            """{"role":"WORK","end":$time,"aim":$time}""",
            """{"role":"WORK","end":$distance,"aim":$distance}""",
        ) + listOf(-1, 0, 86_401).flatMap { seconds ->
            listOf(
                """{"role":"WORK","end":{"type":"time","seconds":$seconds}}""",
                """{"role":"WORK","end":$distance,"aim":{"type":"time","seconds":$seconds}}""",
            )
        } + listOf(-1, 0, 200_001).flatMap { metres ->
            listOf(
                """{"role":"WORK","end":{"type":"distance","metres":$metres}}""",
                """{"role":"WORK","end":$time,"aim":{"type":"distance","metres":$metres}}""",
            )
        }
        invalidSets.forEach { json ->
            assertFailsWith<IllegalArgumentException>(json) { CardioBlockJson.decodeSet(json) }
        }
        val invalid = listOf(
            "not json", "null", "{}", """{"activity":"ROAD_RUN"}""",
            """{"activity":"FUTURE","sets":[{"role":"WORK","end":$time}]}""",
            """{"activity":"ROAD_RUN","sets":[]}""",
        ) + invalidSets.map { """{"activity":"ROAD_RUN","sets":[$it]}""" }
        invalid.forEach { json ->
            assertNull(CardioBlockJson.decode(json), json)
            assertFailsWith<IllegalArgumentException>(json) { CardioBlockJson.validate(json) }
        }
    }

    @Test
    fun `enum names and measure discriminators are stable and additive fields are tolerated`() {
        val json = """{"activity":"ROAD_RUN","sets":[{"role":"WORK","end":{"type":"distance","metres":400,"future":true},"aim":{"type":"time","seconds":105},"hard":true,"future":1}],"future":{}}"""
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        ))
        assertEquals(block, CardioBlockJson.decode(json))
        assertEquals(
            """{"activity":"ROAD_RUN","sets":[{"role":"WORK","end":{"type":"distance","metres":400},"aim":{"type":"time","seconds":105},"hard":true}]}""",
            CardioBlockJson.encode(block),
        )
    }
}
