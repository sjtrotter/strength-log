package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.serialization.CardioDto
import io.github.sjtrotter.strengthlog.data.serialization.SetJson
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

/**
 * Pins the other half of the tolerant-on-read contract documented on [SetJson]:
 * unknown fields from a newer build are silently dropped (SetSerializationTest),
 * but every field [io.github.sjtrotter.strengthlog.data.serialization.SetDto] and
 * [io.github.sjtrotter.strengthlog.data.serialization.CardioDto] declare today is
 * required — neither DTO has an optional/defaulted field yet, so a genuinely
 * truncated row (missing a field, not just carrying an extra one) must fail
 * loudly rather than silently coerce into a wrong value. If either DTO gains a
 * defaulted field later, add a case here proving the old-row/missing-field
 * decode falls back to that default instead of throwing.
 */
class SerializationEdgeCasesTest {

    @Test
    fun `a set row missing a required field fails loudly instead of guessing`() {
        val truncated = """[{"weightLb":100.0,"reps":5,"kind":"TOP"}]""" // no "done"
        assertFailsWith<SerializationException> { SetJson.decodeSets(truncated) }
    }

    @Test
    fun `a cardio row missing a required field fails loudly instead of guessing`() {
        val truncated = """{"label":"Zone 2","detail":"20-30 min conversational"}""" // no "hard"
        assertFailsWith<SerializationException> {
            CardioDto.decode(truncated)
        }
    }

    @Test
    fun `malformed json fails loudly rather than returning a partial result`() {
        assertFailsWith<SerializationException> { SetJson.decodeSets("not json") }
    }
}
