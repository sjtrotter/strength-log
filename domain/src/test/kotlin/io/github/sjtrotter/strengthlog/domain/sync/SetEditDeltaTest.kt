package io.github.sjtrotter.strengthlog.domain.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SetEditDeltaTest {

    private val json = Json

    @Test
    fun `round-trips a fully-populated delta`() {
        val delta = SetEditDelta(
            dayId = "A",
            programExerciseId = 1L,
            slot = "main",
            setIndex = 4,
            weightLb = 245.0,
            reps = 5,
            done = true,
            editedAtMillis = 1_720_000_000_000L,
        )
        val encoded = json.encodeToString(SetEditDelta.serializer(), delta)
        assertEquals(delta, json.decodeFromString(SetEditDelta.serializer(), encoded))
    }

    @Test
    fun `unchanged fields serialize and decode as null rather than a default value`() {
        val doneOnly = SetEditDelta(
            dayId = "A",
            programExerciseId = 1L,
            slot = "ss",
            setIndex = 0,
            done = true,
            editedAtMillis = 1L,
        )
        val decoded = json.decodeFromString(
            SetEditDelta.serializer(),
            json.encodeToString(SetEditDelta.serializer(), doneOnly),
        )
        assertNull(decoded.weightLb)
        assertNull(decoded.reps)
        assertEquals(true, decoded.done)
    }

    @Test
    fun `seconds defaults to null and round-trips a TIMED hold edit`() {
        val plain = SetEditDelta(dayId = "A", programExerciseId = 1L, slot = "main", setIndex = 0, editedAtMillis = 1L)
        assertNull(plain.seconds)

        val hold = plain.copy(seconds = 60)
        val decoded = json.decodeFromString(
            SetEditDelta.serializer(),
            json.encodeToString(SetEditDelta.serializer(), hold),
        )
        assertEquals(60, decoded.seconds)
        assertNull(decoded.weightLb)
    }

    @Test
    fun `an old delta without a seconds key decodes to null (backward compatible)`() {
        val lenient = Json { ignoreUnknownKeys = true }
        val oldWire = """{"schemaVersion":1,"dayId":"A","programExerciseId":1,"slot":"main",
            "setIndex":0,"reps":8,"editedAtMillis":5}""".trimIndent()
        val decoded = lenient.decodeFromString(SetEditDelta.serializer(), oldWire)
        assertNull(decoded.seconds)
        assertEquals(8, decoded.reps)
    }

    @Test
    fun `schemaVersion defaults to 1`() {
        val delta = SetEditDelta(
            dayId = "A",
            programExerciseId = 1L,
            slot = "main",
            setIndex = 0,
            editedAtMillis = 0L,
        )
        assertEquals(1, delta.schemaVersion)
    }
}
